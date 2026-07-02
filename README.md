# ParentOps

Reads your kids' Google Classroom accounts and turns announcements, coursework
and materials into one merged, searchable household view: action items with
due dates and checklists, a morning brief / evening review, per-subject
library, and today's timetable.

This is the **v0 fetch-and-verify app**: a web app (installable on your phone
home screen as a PWA) backed by FastAPI. Its job is to prove the pipeline —
OAuth → fetch → extract → act — with your real accounts. The native
iOS/Android app (see `ARCHITECTURE.md`) reuses this backend as-is.

## Quick start (demo mode — no Google setup needed)

```bash
pip install -r requirements.txt
python -m app.seed_demo          # loads sample data from the real 5D circulars
PARENTOPS_DEMO=1 uvicorn app.main:app --port 8000
```

Open http://localhost:8000 — you'll see the Today screen populated with the
ID-card deadline, the Tamil class-test (with the confirm-date flow), the
assembly, the subject library, and Thursday's timetable.

## Connecting real Classroom accounts

### Step 1 — Create a Google OAuth client (one-time, ~10 minutes)

1. Go to https://console.cloud.google.com → create a project (e.g. `parentops`).
2. **APIs & Services → Library** → enable **Google Classroom API**.
3. **APIs & Services → OAuth consent screen**:
   - User type: **External**; publishing status stays **Testing**
     (no Google verification needed in Testing mode — up to 100 test users).
   - **Add both kids' school email addresses as Test users.**
4. **APIs & Services → Credentials → Create credentials → OAuth client ID**:
   - Application type: **Web application**
   - Authorized redirect URI: `http://localhost:8000/oauth/callback`
   - Download the JSON and save it as `client_secret.json` in this folder.

### Step 2 — Run and link

```bash
pip install -r requirements.txt
export ANTHROPIC_API_KEY=sk-ant-...   # optional but recommended: AI extraction
uvicorn app.main:app --port 8000
```

Open http://localhost:8000 → **Settings → Link a child's Classroom account**
→ sign in with the child's school Google account → repeat for the second
child → tap **⟳ Sync**.

> **The go/no-go moment:** if the school's Workspace admin blocks third-party
> apps, Google will show an "app blocked by administrator" error at sign-in.
> That single screen answers the only real unknown in this project. If it
> appears, the fallback is manual forwarding/upload (see `PLAN.md`).

### Step 3 — Use it from your phone

- Same Wi-Fi: run `uvicorn app.main:app --host 0.0.0.0 --port 8000` and open
  `http://<laptop-ip>:8000` on the phone → browser menu → **Add to Home
  Screen**. (Link accounts from the laptop; viewing/ticking works anywhere.)
- Always-on: deploy to any small host with HTTPS (Cloud Run, Fly.io, a ₹300/mo
  VPS), set `PARENTOPS_BASE_URL=https://yourdomain` and add
  `https://yourdomain/oauth/callback` as a redirect URI in the Google console.
  Then set a strong `PARENTOPS_SECRET` and put it behind auth — it's your
  kids' data.

## Configuration (env vars)

| Variable | Default | Purpose |
|---|---|---|
| `ANTHROPIC_API_KEY` | unset | Enables Claude extraction; otherwise a conservative heuristic runs |
| `EXTRACT_MODEL` | `claude-sonnet-5` | Claude model for extraction |
| `GOOGLE_CLIENT_SECRET_FILE` | `client_secret.json` | OAuth client file path |
| `PARENTOPS_DB` | `parentops.db` | SQLite path |
| `PARENTOPS_BASE_URL` | request host | Public base URL when deployed |
| `PARENTOPS_SECRET` | dev value | Session signing key — set in production |
| `PARENTOPS_DEMO` | unset | `1` shows the demo badge in Settings |

## What's in v0 / what's next

**In:** OAuth linking (read-only scopes, revocable), multi-child merge, sync of
announcements + coursework + materials, AI/heuristic extraction into action
items with confirm-date flow, checklists, morning/evening digest views,
subject-wise library with search, per-child timetable with day-of hints,
installable PWA.

**Next (see `ARCHITECTURE.md`):** background sync scheduler + push
notifications (FCM), Pub/Sub registrations for coursework changes, PDF
attachment parsing, timetable extraction from an image, native Flutter shell,
spouse sharing.
