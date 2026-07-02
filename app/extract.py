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
AMOUNT_PAT = re.compile(r"(?:₹|rs\.?|inr)\s?([\d,]+)", re.I)
BULLET_PAT = re.compile(r"^\s*(?:[•\-\*•]|\d+[.)])\s+(.{3,120})$", re.M)

EXTRACT_MODEL = os.environ.get("EXTRACT_MODEL", "claude-sonnet-5")

PROMPT = """You are the extraction engine of ParentOps, an app that turns school
Google Classroom posts into action items for parents in India.

Post metadata:
- Course/subject: {course}
- Posted on: {posted} (use this to resolve relative dates like "tomorrow")
- Kind: {kind}

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
  "checklist": ["specific sub-task", ...]   // [] if none
}}

Rules:
- Purely informational posts (new study material, no action needed) -> []
- "tomorrow"/"next Friday" etc.: resolve against the posted date and mark
  date_confidence "inferred", never "explicit".
- Never invent deadlines, amounts, or checklist entries not present in the text.
- Checklist entries must be concrete physical actions (photos to send, pages to
  fill, items to bring), not restatements of the title."""


def _heuristic(post):
    body = f"{post.get('title') or ''}\n{post.get('body') or ''}"
    if not ACTION_WORDS.search(body):
        return []
    due, confidence = None, "none"
    m = DATE_PAT.search(body)
    posted = _posted_date(post)
    if m:
        d, mo, y = int(m[1]), int(m[2]), int(m[3])
        y = y + 2000 if y < 100 else y
        try:
            due, confidence = date(y, mo, d).isoformat(), "explicit"
        except ValueError:
            pass
    elif re.search(r"\btomorrow\b", body, re.I) and posted:
        due, confidence = (posted + timedelta(days=1)).isoformat(), "inferred"
    amount = None
    am = AMOUNT_PAT.search(body)
    if am:
        amount = f"₹{am[1]}"
    checklist = [{"text": t.strip(), "done": False} for t in BULLET_PAT.findall(body)][:8]
    if not (due or amount or checklist):
        return []
    return [{
        "title": (post.get("title") or "School notice")[:80],
        "detail": "Auto-detected without AI — open the original post to verify.",
        "due_date": due, "date_confidence": confidence,
        "amount": amount, "checklist": checklist,
    }]


def _posted_date(post):
    try:
        return dateparse.parse(post.get("posted_at")).date()
    except (TypeError, ValueError):
        return None


def _claude(post):
    import anthropic
    client = anthropic.Anthropic()
    msg = client.messages.create(
        model=EXTRACT_MODEL, max_tokens=1500,
        messages=[{"role": "user", "content": PROMPT.format(
            course=post.get("course_name") or "unknown",
            posted=post.get("posted_at") or "unknown",
            kind=post.get("kind"),
            body=(post.get("title") or "") + "\n" + (post.get("body") or "")[:6000],
        )}])
    text = msg.content[0].text.strip()
    text = re.sub(r"^```(?:json)?|```$", "", text, flags=re.M).strip()
    items = json.loads(text)
    for it in items:
        it["checklist"] = [{"text": t, "done": False} for t in it.get("checklist") or []]
    return items


def extract_post(post):
    """Extraction for one post -> list of item dicts (schema of PROMPT)."""
    if os.environ.get("ANTHROPIC_API_KEY"):
        try:
            return _claude(post)
        except Exception as e:  # fall back rather than lose the post
            print(f"[extract] Claude failed for post {post['id']}: {e}")
    return _heuristic(post)


def run_pending():
    """Extract all posts not yet processed. Returns number of items created."""
    created = 0
    for post in db.unextracted_posts():
        for it in extract_post(post):
            db.add_item(
                post["child_id"], it["title"][:120], post_id=post["id"],
                detail=it.get("detail"), due_date=it.get("due_date"),
                amount=it.get("amount"), checklist=it.get("checklist"),
                needs_confirm=1 if it.get("date_confidence") == "inferred" else 0)
            created += 1
        db.mark_extracted(post["id"])
    return created
