"""ParentOps — FastAPI app: OAuth linking, sync, Today/Library/Digest views."""
import json
import os
from datetime import date, datetime, timedelta

from fastapi import FastAPI, Form, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from starlette.middleware.sessions import SessionMiddleware

from . import auth, classroom, db, extract

app = FastAPI(title="ParentOps")
app.add_middleware(SessionMiddleware,
                   secret_key=os.environ.get("PARENTOPS_SECRET", "dev-secret-change-me"))

HERE = os.path.dirname(__file__)
app.mount("/static", StaticFiles(directory=os.path.join(HERE, "static")), name="static")
templates = Jinja2Templates(directory=os.path.join(HERE, "templates"))
templates.env.filters["nicedate"] = lambda iso: (
    datetime.fromisoformat(iso).strftime("%a %d %b") if iso else "No date")
templates.env.filters["dow"] = lambda iso: (
    datetime.fromisoformat(iso).strftime("%a") if iso else "—")
templates.env.filters["fromjson"] = json.loads

db.init_db()

WEEKDAYS = ["mon", "tue", "wed", "thu", "fri", "sat", "sun"]


def _sections(child_id=None):
    """Group open items for the Today screen."""
    today = date.today().isoformat()
    week = (date.today() + timedelta(days=7)).isoformat()
    overdue, soon, later, undated, holidays = [], [], [], [], []
    for it in db.items("open", child_id):
        if it.get("category") == "holiday" and it["due_date"] == today:
            holidays.append(it)
        elif not it["due_date"]:
            undated.append(it)
        elif it["due_date"] < today:
            overdue.append(it)
        elif it["due_date"] <= week:
            soon.append(it)
        else:
            later.append(it)
    return dict(overdue=overdue, soon=soon, later=later, undated=undated,
                holidays=holidays, today=today)


def _timetables_today():
    out = []
    key = WEEKDAYS[date.today().weekday()]
    for ch in db.children():
        try:
            grid = json.loads(ch.get("timetable_json") or "{}")
        except ValueError:
            grid = {}
        periods = grid.get(key)
        if periods:
            out.append({"child": ch, "periods": periods, "note": grid.get(f"{key}_note")})
    return out


@app.get("/", response_class=HTMLResponse)
def today(request: Request, child: int | None = None):
    s = _sections(child)
    holiday_kids = {h["child_id"] for h in s["holidays"]}
    timetables = [t for t in _timetables_today()
                  if t["child"]["id"] not in holiday_kids]
    return templates.TemplateResponse(request, "today.html", dict(
        active="today", children=db.children(), sel_child=child,
        oauth_ready=auth.oauth_configured(), timetables=timetables,
        done_today_count=len(db.items_done_on(date.today().isoformat(), child)),
        **s))


@app.get("/finished", response_class=HTMLResponse)
def finished(request: Request, child: int | None = None):
    return templates.TemplateResponse(request, "finished.html", dict(
        active="finished", children=db.children(), sel_child=child,
        done=db.items_done(child), today=date.today().isoformat()))


@app.get("/library", response_class=HTMLResponse)
def library(request: Request, q: str | None = None, child: int | None = None,
            course: str | None = None):
    return templates.TemplateResponse(request, "library.html", dict(
        active="library", children=db.children(), sel_child=child, q=q or "",
        sel_course=course, courses=db.courses_summary(),
        posts=db.search_posts(q, child, course)))


@app.get("/digest", response_class=HTMLResponse)
def digest(request: Request, mode: str = "morning"):
    today_iso = date.today().isoformat()
    tomorrow_iso = (date.today() + timedelta(days=1)).isoformat()
    s = _sections()
    due_today = [i for i in s["soon"] if i["due_date"] == today_iso]
    due_tomorrow = [i for i in s["soon"] if i["due_date"] == tomorrow_iso]
    holiday_kids = {h["child_id"] for h in s["holidays"]}
    return templates.TemplateResponse(request, "digest.html", dict(
        active="digest", mode=mode, children=db.children(),
        overdue=s["overdue"], holidays=s["holidays"], due_today=due_today,
        due_tomorrow=due_tomorrow, done_today=db.items_done_on(today_iso),
        timetables=[t for t in _timetables_today()
                    if t["child"]["id"] not in holiday_kids],
        today=today_iso))


@app.get("/settings", response_class=HTMLResponse)
def settings(request: Request, msg: str | None = None):
    kids = db.children()
    for k in kids:
        k["timetable_pretty"] = json.dumps(
            json.loads(k.get("timetable_json") or "{}"), indent=1)
    return templates.TemplateResponse(request, "settings.html", dict(
        active="settings", children=kids, msg=msg,
        oauth_ready=auth.oauth_configured(),
        has_api_key=bool(os.environ.get("ANTHROPIC_API_KEY")),
        demo=os.environ.get("PARENTOPS_DEMO") == "1"))


# ---------- OAuth ----------

@app.get("/link")
def link(request: Request):
    if not auth.oauth_configured():
        return RedirectResponse("/settings?msg=Missing+client_secret.json+—+see+README+step+1", 303)
    url, state = auth.start_flow(request)
    request.session["oauth_state"] = state
    return RedirectResponse(url, 303)


@app.get("/oauth/callback")
def oauth_callback(request: Request):
    state = request.session.pop("oauth_state", None)
    try:
        creds = auth.finish_flow(request, state, str(request.url))
        name, email = classroom.user_profile(creds)
    except Exception as e:
        return RedirectResponse(f"/settings?msg=Link+failed:+{type(e).__name__}", 303)
    db.add_child(name, email=email, token_json=auth.creds_to_json(creds))
    return RedirectResponse("/settings?msg=Linked+" + (email or name), 303)


# ---------- actions ----------

@app.post("/sync")
def sync():
    fetched = 0
    errors = []
    for ch in db.children():
        if not ch.get("token_json"):
            continue
        try:
            creds = auth.creds_from_json(ch["token_json"])
            fetched += classroom.sync_child(ch, creds)
            db.update_child(ch["id"], token_json=auth.creds_to_json(creds))
        except Exception as e:
            errors.append(f"{ch['name']}: {type(e).__name__}")
    extract.run_pending()
    msg = f"Synced+{fetched}+new/changed+posts"
    if errors:
        msg += "+—+errors:+" + ",".join(errors)
    return RedirectResponse(f"/?msg={msg}", 303)


@app.post("/items/{item_id}/done")
def item_done(item_id: int):
    db.update_item(item_id, status="done", done_at=datetime.now().isoformat())
    return RedirectResponse("/", 303)


@app.post("/items/{item_id}/reopen")
def item_reopen(item_id: int):
    db.update_item(item_id, status="open", done_at=None)
    return RedirectResponse("/finished", 303)


@app.post("/items/{item_id}/dismiss")
def item_dismiss(item_id: int):
    db.update_item(item_id, status="dismissed")
    return RedirectResponse("/", 303)


@app.post("/items/{item_id}/check/{idx}")
def item_check(item_id: int, idx: int):
    it = db.item(item_id)
    if it and 0 <= idx < len(it["checklist"]):
        it["checklist"][idx]["done"] = not it["checklist"][idx]["done"]
        db.update_item(item_id, checklist=it["checklist"])
    return RedirectResponse("/", 303)


@app.post("/children/{child_id}/rename")
def child_rename(child_id: int, name: str = Form(...)):
    db.update_child(child_id, name=name.strip() or "Child")
    return RedirectResponse("/settings?msg=Renamed", 303)


@app.post("/children/{child_id}/timetable")
def child_timetable(child_id: int, timetable: str = Form("")):
    try:
        parsed = json.loads(timetable or "{}")
    except ValueError:
        return RedirectResponse("/settings?msg=Timetable+is+not+valid+JSON", 303)
    db.update_child(child_id, timetable_json=json.dumps(parsed))
    return RedirectResponse("/settings?msg=Timetable+saved", 303)


@app.post("/children/{child_id}/unlink")
def child_unlink(child_id: int):
    db.delete_child(child_id)
    return RedirectResponse("/settings?msg=Removed", 303)
