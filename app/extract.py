"""Turn raw Classroom posts into action items.

Uses the Claude API when ANTHROPIC_API_KEY is set; otherwise falls back to a
conservative heuristic that only creates low-confidence items when it sees a
clear signal (a date, "tomorrow", an amount, or an action keyword).
"""
import json
import os
import re
from datetime import date, datetime, timedelta

from dateutil import parser as dateparse

from . import db

ACTION_WORDS = re.compile(
    r"\b(submit|bring|send|pay|due|last date|deadline|test|exam|fill|wear|"
    r"attend|meeting|holiday|fee|fees|remind|prepare|complete|carry)\b", re.I)
DATE_PAT = re.compile(r"\b(\d{1,2})[./-](\d{1,2})[./-](\d{2,4})\b")
DATE_WORD_PAT = re.compile(  # e.g. "20-Jul-2026", "3 July 2026"
    r"\b(\d{1,2})[ \-]([A-Za-z]{3,9})[ \-,]+(\d{4})\b")
HOLIDAY_PAT = re.compile(r"\bholiday|school (will remain |remains )?closed\b", re.I)
AMOUNT_PAT = re.compile(r"(?:₹|rs\.?|inr)\s?([\d,]+)", re.I)
BULLET_PAT = re.compile(r"^\s*(?:[•\-\*•]|\d+[.)])\s+(.{3,120})$", re.M)

EXTRACT_MODEL = os.environ.get("EXTRACT_MODEL", "claude-sonnet-5")

WEEKDAY_KEYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]
DEFAULT_SCHOOL_DAYS = ["mon", "tue", "wed", "thu", "fri"]


def school_days_for(child_row):
    """A child's school days, derived from their timetable keys (a 'sat' key
    means Saturday classes). Falls back to Mon-Fri."""
    try:
        grid = json.loads(child_row.get("timetable_json") or "{}")
    except ValueError:
        grid = {}
    days = [d for d in WEEKDAY_KEYS if d in grid]
    return days or DEFAULT_SCHOOL_DAYS


def next_school_day(after, school_days):
    """The next school day strictly after `after` — so 'tomorrow' written on a
    Friday resolves to Monday (or Saturday, if the school has Saturday classes)."""
    for i in range(1, 8):
        d = after + timedelta(days=i)
        if WEEKDAY_KEYS[d.weekday()] in school_days:
            return d
    return after + timedelta(days=1)

PROMPT = """You are the extraction engine of ParentOps, an app that turns school
Google Classroom posts into action items for parents in India.

Post metadata:
- Course/subject: {course}
- Posted on: {posted} (use this to resolve relative dates like "tomorrow")
- Kind: {kind}
- This child's school days: {school_days}. Resolve "tomorrow"/"next class" to
  the NEXT SCHOOL DAY — "tomorrow" written on a Friday means Monday, unless
  Saturday is listed as a school day.

Post text:
---
{body}
---

Extract parent-facing action items. Return ONLY a JSON array (no prose, no code
fences). Each element:
{{
  "title": "short imperative summary, max 80 chars",
  "detail": "one-line context for the parent, or null",
  "due_date": "YYYY-MM-DD or null",
  "date_confidence": "explicit" | "inferred" | "none",
  "amount": "e.g. ₹1,250, or null",
  "category": "holiday" | "fee" | "test" | "event" | "task",
  "checklist": ["specific sub-task", ...]   // [] if none
}}

Rules:
- Purely informational posts (new study material, no action needed) -> []
- EXCEPTION: holiday / school-closed announcements DO produce an item with
  category "holiday" and the holiday date as due_date.
- "tomorrow"/"next Friday" etc.: resolve against the posted date and mark
  date_confidence "inferred", never "explicit". Note the inference in detail
  (e.g. 'teacher wrote "tomorrow"').
- Never invent deadlines, amounts, or checklist entries not present in the text.
- Checklist entries must be concrete physical actions (photos to send, pages to
  fill, items to bring), not restatements of the title."""


def _find_due(body, posted, school_days):
    m = DATE_PAT.search(body)
    if m:
        d, mo, y = int(m[1]), int(m[2]), int(m[3])
        y = y + 2000 if y < 100 else y
        try:
            return date(y, mo, d).isoformat(), "explicit"
        except ValueError:
            pass
    m = DATE_WORD_PAT.search(body)
    if m:
        try:  # "20-Jul-2026" / "3 July 2026"
            return dateparse.parse(" ".join(m.groups()), dayfirst=True).date().isoformat(), "explicit"
        except (ValueError, OverflowError):
            pass
    if re.search(r"\btomorrow\b", body, re.I) and posted:
        return next_school_day(posted, school_days).isoformat(), "inferred"
    return None, "none"


def _heuristic(post, school_days):
    body = f"{post.get('title') or ''}\n{post.get('body') or ''}"
    is_holiday = bool(HOLIDAY_PAT.search(body))
    if not (ACTION_WORDS.search(body) or is_holiday):
        return []
    posted = _posted_date(post)
    due, confidence = _find_due(body, posted, school_days)
    amount = None
    am = AMOUNT_PAT.search(body)
    if am:
        amount = f"₹{am[1]}"
    checklist = [{"text": t.strip(), "done": False} for t in BULLET_PAT.findall(body)][:8]
    if not (due or amount or checklist or is_holiday):
        return []
    if is_holiday:
        category = "holiday"
    elif amount:
        category = "fee"
    elif re.search(r"\btest|exam\b", body, re.I):
        category = "test"
    else:
        category = "task"
    return [{
        "title": (post.get("title") or "School notice")[:80],
        "detail": "Auto-detected without AI — open the original post to verify.",
        "due_date": due, "date_confidence": confidence,
        "amount": amount, "category": category, "checklist": checklist,
    }]


def _posted_date(post):
    try:
        return dateparse.parse(post.get("posted_at")).date()
    except (TypeError, ValueError):
        return None


def _claude(post, school_days):
    import anthropic
    client = anthropic.Anthropic()
    msg = client.messages.create(
        model=EXTRACT_MODEL, max_tokens=1500,
        messages=[{"role": "user", "content": PROMPT.format(
            course=post.get("course_name") or "unknown",
            posted=post.get("posted_at") or "unknown",
            kind=post.get("kind"),
            school_days=", ".join(school_days),
            body=(post.get("title") or "") + "\n" + (post.get("body") or "")[:6000],
        )}])
    text = msg.content[0].text.strip()
    text = re.sub(r"^```(?:json)?|```$", "", text, flags=re.M).strip()
    items = json.loads(text)
    for it in items:
        it["checklist"] = [{"text": t, "done": False} for t in it.get("checklist") or []]
    return items


def extract_post(post, school_days=None):
    """Extraction for one post -> list of item dicts (schema of PROMPT)."""
    school_days = school_days or DEFAULT_SCHOOL_DAYS
    if os.environ.get("ANTHROPIC_API_KEY"):
        try:
            return _claude(post, school_days)
        except Exception as e:  # fall back rather than lose the post
            print(f"[extract] Claude failed for post {post['id']}: {e}")
    return _heuristic(post, school_days)


def run_pending():
    """Extract all posts not yet processed. Returns number of items created."""
    created = 0
    days_by_child = {ch["id"]: school_days_for(ch) for ch in db.children()}
    for post in db.unextracted_posts():
        for it in extract_post(post, days_by_child.get(post["child_id"])):
            db.add_item(
                post["child_id"], it["title"][:120], post_id=post["id"],
                detail=it.get("detail"), due_date=it.get("due_date"),
                amount=it.get("amount"), checklist=it.get("checklist"),
                category=it.get("category"))
            created += 1
        db.mark_extracted(post["id"])
    return created
