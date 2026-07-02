# ParentOps

**Never miss a school circular, fee deadline, dress code, event, or class test again.**

ParentOps reads your kids' Google Classroom accounts and turns announcements,
coursework and materials into one merged, searchable household view:

- ✅ **Action items** with due dates, amounts and tickable checklists
- ⚠️ **Confirm-date flow** — when a teacher writes "test tomorrow", the app
  resolves the date from the post time but asks *you* to confirm it
- ☀️ **Morning brief** — carried-over tasks, due today, today's timetable
- 🌙 **Evening review** — done today, still pending, prepare-tonight for tomorrow
- 📚 **Library** — every post ever, subject-wise, full-text searchable
  (the anti-endless-scroll)
- 👧👦 **Multi-child** — both kids' schools in one screen, color-tagged
- 📱 **Installable** on iOS and Android home screens as a PWA

This is the **v0 fetch-and-verify app** (FastAPI + SQLite + PWA). Its job is to
prove the pipeline — OAuth → fetch → extract → act — with real accounts. The
native mobile app (see `ARCHITECTURE.md`) reuses this backend as-is. Product
thinking lives in `PLAN.md`; the original UI concept in `mockups/`.

---

## 1. Deploy locally (demo mode — 2 minutes, no Google setup)

Requires Python 3.11+.

**macOS / Linux:**

```bash
git clone https://github.com/VamsiSudhakaran1/ParentOPS.git
cd ParentOPS
python3 -m venv .venv && source .venv/bin/activate
pip install -r requirements.txt
python -m app.seed_demo                               # sample data from real 5D circulars
uvicorn app.main:app --port 8000
```

**Windows (cmd):**

```bat
git clone https://github.com/VamsiSudhakaran1/ParentOPS.git
cd ParentOPS
python -m venv .venv
.venv\Scripts\activate
pip install -r requirements.txt
python -m app.seed_demo
python -m uvicorn app.main:app --port 8000
```

(PowerShell: activate with `.venv\Scripts\Activate.ps1`; if blocked, run
`Set-ExecutionPolicy -ExecutionPolicy RemoteSigned -Scope CurrentUser` once.
The prompt shows `(.venv)` when the venv is active.)

Open **http://localhost:8000**.

### Check it works (demo)

| # | Do this | You should see |
|---|---------|----------------|
| 1 | Open `/` | Today screen: timetable strip + "Next 7 days" with the ID-card item and its 4-point checklist |
| 2 | Tap a checklist box | It toggles green and strikes through |
| 3 | Find the Tamil class-test item | Amber **⚠ Confirm date** tag with a date picker; tap **Confirm** and it becomes a normal item |
| 4 | Tap **Done ✓** on any item | It moves to "Done today" with an Undo button |
| 5 | Go to **📚 Library**, search `tamil` | The class-test circular and Tamil material posts |
| 6 | Go to **☀️ Digest** and switch to 🌙 | Morning brief and evening review views |
| 7 | Go to **⚙️ Settings** | The demo child, timetable JSON editor, status panel |

If all seven pass, the install is good. Reset anytime with
`rm parentops.db && python -m app.seed_demo`.

---

## 2. Connect real Classroom accounts

### Step 1 — Create a Google OAuth client (one-time, ~10 minutes)

1. Go to https://console.cloud.google.com → create a project (e.g. `parentops`).
2. **APIs & Services → Library** → enable **Google Classroom API**.
3. **APIs & Services → OAuth consent screen**:
   - User type: **External**; leave publishing status as **Testing**
     (Testing mode needs no Google verification and allows 100 test users).
   - **Add both kids' school email addresses as Test users.**
4. **APIs & Services → Credentials → Create credentials → OAuth client ID**:
   - Application type: **Web application**
   - Authorized redirect URI: `http://localhost:8000/oauth/callback`
   - Download the JSON → save as `client_secret.json` in the project root
     (it is `.gitignore`d — never commit it).

### Step 2 — Run and link

```bash
export ANTHROPIC_API_KEY=sk-ant-...   # optional but recommended: AI extraction
uvicorn app.main:app --port 8000
```

Open http://localhost:8000 → **⚙️ Settings → Link a child's Classroom account**
→ sign in with the child's school Google account → approve the read-only
scopes → repeat for the second child → tap **⟳ Sync**.

> **⚡ The go/no-go moment:** if the school's Workspace admin blocks
> third-party apps, Google shows an *"app blocked by administrator"* error at
> sign-in. That single screen answers the project's only real unknown. If it
> appears, the fallback is manual forward/upload (see `PLAN.md`).

### Check it works (real data)

1. After **⟳ Sync**, the flash bar reports `Synced N new/changed posts`.
2. **Library** should list the child's actual courses under *Subjects* —
   compare against the Classroom app.
3. Real announcements appear as posts; actionable ones (dates, fees,
   "tomorrow", bring/submit/pay) become items on **Today**.
4. Items with inferred dates carry **⚠ Confirm date** — confirm or fix them.
5. Wrong/noisy extractions: **Dismiss** the item (the post stays in Library).
   Note what was wrong — that feedback tunes the extraction prompt.

### Step 3 — Use it from your phone

- **Same Wi-Fi:** `uvicorn app.main:app --host 0.0.0.0 --port 8000`, then open
  `http://<laptop-ip>:8000` on the phone → browser menu → **Add to Home
  Screen**. (Do the OAuth linking on the laptop; viewing/ticking works from
  anywhere on the network.)
- **Always-on:** deploy to any small HTTPS host (Cloud Run, Fly.io, a cheap
  VPS): set `PARENTOPS_BASE_URL=https://yourdomain`, add
  `https://yourdomain/oauth/callback` as a redirect URI in the Google console,
  set a strong `PARENTOPS_SECRET`, and put it behind authentication — it's
  your kids' data.

---

## Configuration

| Env var | Default | Purpose |
|---|---|---|
| `ANTHROPIC_API_KEY` | unset | Enables Claude extraction; otherwise a conservative heuristic runs |
| `EXTRACT_MODEL` | `claude-sonnet-5` | Claude model used for extraction |
| `GOOGLE_CLIENT_SECRET_FILE` | `client_secret.json` | OAuth client file path |
| `PARENTOPS_DB` | `parentops.db` | SQLite database path |
| `PARENTOPS_BASE_URL` | request host | Public base URL when deployed behind HTTPS |
| `PARENTOPS_SECRET` | dev value | Session signing key — set your own in production |
| `PARENTOPS_DEMO` | unset | `1` shows the demo badge in Settings |

## Troubleshooting

- **`pyo3_runtime.PanicException` / cryptography errors on startup** — an old
  distro-packaged `cryptography` is shadowing the pip one. Fix:
  `pip install --upgrade cryptography` (or use the venv as shown above).
- **`Missing client_secret.json`** — Step 1 not done, or the file isn't in the
  folder you launched from.
- **`redirect_uri_mismatch` at Google sign-in** — the redirect URI in the
  Google console must exactly match `http://localhost:8000/oauth/callback`
  (scheme, host, port, path).
- **`access_denied` / "app blocked by administrator"** — the school's admin
  restricts third-party apps for student accounts. See the go/no-go note above.
- **Sync errors for one child** — the stored token may have been revoked;
  remove the child in Settings and re-link.

## Project layout

```
app/
  main.py        FastAPI routes (Today, Digest, Library, Settings, OAuth, actions)
  auth.py        Google OAuth flow — read-only Classroom scopes only
  classroom.py   Fetchers for announcements / courseWork / materials
  extract.py     Claude-powered extraction with heuristic fallback
  db.py          SQLite schema + queries (children, posts, items)
  seed_demo.py   Demo data from the real Grade 5D circulars
  templates/     Jinja2 views    static/  CSS, PWA manifest, icons, SW
ARCHITECTURE.md  Mobile app + sync architecture and build phases
PLAN.md          Product plan: scope, doability, usability, risks
mockups/         Original HTML product mockup
```

## What's next

Background sync scheduler + FCM push notifications, Pub/Sub registrations for
coursework changes, PDF attachment parsing, timetable extraction from an
image, spouse sharing, native Flutter shell. See `ARCHITECTURE.md`.
