"""Google OAuth: link a child's Classroom account with read-only scopes.

OAuth client credentials come from either a pasted Client ID/Secret saved in
Settings (encrypted at rest) or a local client_secret.json file. Linked
account tokens are encrypted before being stored.
"""
import json
import os

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import Flow

from . import db, secure

# Read-only Classroom scopes only — no Drive scope in v0 (attachments open
# via their Classroom/Drive links instead), which keeps the OAuth grant minimal.
SCOPES = [
    "openid",
    "https://www.googleapis.com/auth/userinfo.email",
    "https://www.googleapis.com/auth/userinfo.profile",
    "https://www.googleapis.com/auth/classroom.courses.readonly",
    "https://www.googleapis.com/auth/classroom.announcements.readonly",
    "https://www.googleapis.com/auth/classroom.coursework.me.readonly",
    "https://www.googleapis.com/auth/classroom.courseworkmaterials.readonly",
]

CLIENT_SECRET_FILE = os.environ.get("GOOGLE_CLIENT_SECRET_FILE", "client_secret.json")

# Google returns granted scopes in a different order / superset; don't hard-fail.
os.environ.setdefault("OAUTHLIB_RELAX_TOKEN_SCOPE", "1")
if os.environ.get("PARENTOPS_ALLOW_HTTP", "1") == "1":
    # Local development only: allow http://localhost redirect URIs.
    os.environ.setdefault("OAUTHLIB_INSECURE_TRANSPORT", "1")


def _client_config():
    enc = db.get_setting("google_client_config")
    if enc:
        raw = secure.decrypt(enc)
        if raw:
            return json.loads(raw)
    if os.path.exists(CLIENT_SECRET_FILE):
        with open(CLIENT_SECRET_FILE) as f:
            return json.load(f)
    return None


def save_client_config(client_id: str, client_secret: str):
    cfg = {"web": {
        "client_id": client_id.strip(),
        "client_secret": client_secret.strip(),
        "auth_uri": "https://accounts.google.com/o/oauth2/auth",
        "token_uri": "https://oauth2.googleapis.com/token",
    }}
    db.set_setting("google_client_config", secure.encrypt(json.dumps(cfg)))


def client_id_hint():
    """Masked client id for display, or None."""
    cfg = _client_config()
    if not cfg:
        return None
    cid = (cfg.get("web") or cfg.get("installed") or {}).get("client_id", "")
    return cid[:12] + "…" if cid else None


def oauth_configured():
    return _client_config() is not None


def _redirect_uri(request):
    base = os.environ.get("PARENTOPS_BASE_URL") or str(request.base_url).rstrip("/")
    return f"{base}/oauth/callback"


def start_flow(request):
    flow = Flow.from_client_config(
        _client_config(), scopes=SCOPES, redirect_uri=_redirect_uri(request))
    auth_url, state = flow.authorization_url(
        access_type="offline", prompt="consent", include_granted_scopes="true")
    return auth_url, state


def finish_flow(request, state, authorization_response):
    flow = Flow.from_client_config(
        _client_config(), scopes=SCOPES, state=state,
        redirect_uri=_redirect_uri(request))
    flow.fetch_token(authorization_response=authorization_response)
    return flow.credentials


def creds_to_json(creds):
    """Serialized credentials, encrypted for storage."""
    return secure.encrypt(creds.to_json())


def creds_from_json(token_json):
    raw = secure.decrypt(token_json) or token_json  # legacy plaintext fallback
    creds = Credentials.from_authorized_user_info(json.loads(raw), SCOPES)
    if creds.expired and creds.refresh_token:
        creds.refresh(Request())
    return creds
