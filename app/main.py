"""ParentOps — FastAPI app: OAuth linking, sync, Today/Library/Digest views."""
import json
import os
import threading
import time
from datetime import date, datetime, timedelta

from fastapi import FastAPI, Form, Request
from fastapi.responses import HTMLResponse, RedirectResponse
from fastapi.staticfiles import StaticFiles
from fastapi.templating import Jinja2Templates
from starlette.middleware.sessions import SessionMiddleware

from . import auth, classroom, db, extract, secure

app = FastAPI(title="ParentOps")

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

# ---------- household PIN gate + security headers ----------

_PUBLIC = {"/login", "/setup", "/health"}
_LOGIN_FAILS = {}  # ip -> [count, locked_until_ts]


@app.middleware("http")
async def gatekeeper(request: Request, call_next):
    path = request.url.path
    if not (path in _PUBLIC or path.startswith("/static/")):
        if db.get_setting("pin_hash") is None:
            if path != "/setup":
                return RedirectResponse("/setup", 303)
        elif not request.session.get("authed"):
            return RedirectResponse("/login", 303)
    response = await call_next(request)
    response.headers.setdefault("X-Frame-Options", "DENY")
    response.headers.setdefault("X-Content-Type-Options", "nosniff")
    response.headers.setdefault("Referrer-Policy", "same-origin")
    return response


# Added AFTER the gatekeeper so it wraps it — session must exist before the
# gate checks request.session (later-added middleware runs first).
app.add_middleware(SessionMiddleware,
                   secret_key=os.environ.get("PARENTOPS_SECRET", "dev-secret-change-me"),
                   same_site="lax")


@app.get("/health")
def health():
    return {"ok": True}


@app.get("/setup", response_class=HTMLResponse)
def setup_page(request: Request):
    if db.get_setting("pin_hash"):
        return RedirectResponse("/", 303)
    return templates.TemplateResponse(request, "setup.html", {})


@app.post("/setup")
def setup_save(request: Request, pin: str = Form(...), pin2: str = Form(...)):
    if db.get_setting("pin_hash"):
        return RedirectResponse("/", 303)
    if len(pin) < 4 or pin != pin2:
        return templates.TemplateResponse(request, "setup.html", dict(
            error="PINs must match and be at least 4 characters."))
    db.set_setting("pin_hash", secure.hash_pin(pin))
    request.session["authed"] = True
    return RedirectResponse("/", 303)


@app.get("/login", response_class=HTMLResponse)
def login_page(request: Request):
    if request.session.get("authed"):
        return RedirectResponse("/", 303)
    return templates.TemplateResponse(request, "login.html", {})


@app.post("/login")
def login(request: Request, pin: str = Form(...)):
    ip = request.client.host if request.client else "?"
    fails = _LOGIN_FAILS.get(ip, [0, 0])
    if time.time() < fails[1]:
        return templates.TemplateResponse(request, "login.html", dict(
            error="Too many attempts — wait a minute and try again."))
    if secure.verify_pin(pin, db.get_setting("pin_hash") or ""):
        _LOGIN_FAILS.pop(ip, None)
        request.session["authed"] = True
        return RedirectResponse("/", 303)
    fails[0] += 1
    if fails[0] >= 5:
        fails = [0, time.time() + 60]
    _LOGIN_FAILS[ip] = fails
    return templates.TemplateResponse(request, "login.html", dict(
        error="Wrong PIN, try again."))


@app.post("/logout")
def logout(request: Request):
    request.session.clear()
    return RedirectResponse("/login", 303)


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
        client_hint=auth.client_id_hint(),
        last_sync=db.get_setting("last_sync"),
        last_sync_error=db.get_setting("last_sync_error"),
        autosync_min=os.environ.get("PARENTOPS_SYNC_MINUTES", "15"),
        has_api_key=bool(os.environ.get("ANTHROPIC_API_KEY")),
        demo=os.environ.get("PARENTOPS_DEMO") == "1"))


@app.post("/settings/google")
def settings_google(client_id: str = Form(...), client_secret: str = Form(...)):
    if not client_id.strip() or not client_secret.strip():
        return RedirectResponse("/settings?msg=Both+fields+are+required", 303)
    auth.save_client_config(client_id, client_secret)
    return RedirectResponse("/settings?msg=Google+credentials+saved+—+you+can+link+accounts+now", 303)


@app.post("/settings/pin")
def settings_pin(current: str = Form(...), pin: str = Form(...), pin2: str = Form(...)):
    if not secure.verify_pin(current, db.get_setting("pin_hash") or ""):
        return RedirectResponse("/settings?msg=Current+PIN+is+wrong", 303)
    if len(pin) < 4 or pin != pin2:
        return RedirectResponse("/settings?msg=New+PINs+must+match+(4%2B+characters)", 303)
    db.set_setting("pin_hash", secure.hash_pin(pin))
    return RedirectResponse("/settings?msg=PIN+changed", 303)


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
    child_id = db.add_child(name, email=email, token_json=auth.creds_to_json(creds))
    fetched, errors = do_sync(only_child_id=child_id)  # first sync right away
    if errors:
        return RedirectResponse(f"/settings?msg=Linked+{email or name}+—+first+sync+failed", 303)
    return RedirectResponse(f"/?msg=Linked+{email or name}+—+{fetched}+posts+synced", 303)


# ---------- sync ----------

def do_sync(only_child_id=None):
    """Fetch + extract for all linked children. Safe to call from any thread."""
    fetched, errors = 0, []
    for ch in db.children():
        if not ch.get("token_json") or (only_child_id and ch["id"] != only_child_id):
            continue
        try:
            creds = auth.creds_from_json(ch["token_json"])
            fetched += classroom.sync_child(ch, creds)
            db.update_child(ch["id"], token_json=auth.creds_to_json(creds))
        except Exception as e:
            errors.append(f"{ch['name']}: {type(e).__name__}")
    extract.run_pending()
    db.set_setting("last_sync", datetime.now().strftime("%d %b %H:%M"))
    db.set_setting("last_sync_error", "; ".join(errors) if errors else "")
    return fetched, errors


def _autosync_loop():
    interval = max(5, int(os.environ.get("PARENTOPS_SYNC_MINUTES", "15"))) * 60
    while True:
        time.sleep(interval)
        try:
            do_sync()
        except Exception as e:
            print(f"[autosync] {type(e).__name__}: {e}")


@app.on_event("startup")
def start_autosync():
    if os.environ.get("PARENTOPS_AUTOSYNC", "1") == "1":
        threading.Thread(target=_autosync_loop, daemon=True).start()


# ---------- actions ----------

@app.post("/sync")
def sync():
    fetched, errors = do_sync()
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
