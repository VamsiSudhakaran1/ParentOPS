"""Google OAuth: link a child's Classroom account with read-only scopes."""
import json
import os

from google.auth.transport.requests import Request
from google.oauth2.credentials import Credentials
from google_auth_oauthlib.flow import Flow

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


def oauth_configured():
    return os.path.exists(CLIENT_SECRET_FILE)


def _redirect_uri(request):
    base = os.environ.get("PARENTOPS_BASE_URL") or str(request.base_url).rstrip("/")
    return f"{base}/oauth/callback"


def start_flow(request):
    flow = Flow.from_client_secrets_file(
        CLIENT_SECRET_FILE, scopes=SCOPES, redirect_uri=_redirect_uri(request))
    auth_url, state = flow.authorization_url(
        access_type="offline", prompt="consent", include_granted_scopes="true")
    return auth_url, state


def finish_flow(request, state, authorization_response):
    flow = Flow.from_client_secrets_file(
        CLIENT_SECRET_FILE, scopes=SCOPES, state=state,
        redirect_uri=_redirect_uri(request))
    flow.fetch_token(authorization_response=authorization_response)
    creds = flow.credentials
    return creds


def creds_to_json(creds):
    return creds.to_json()


def creds_from_json(token_json):
    creds = Credentials.from_authorized_user_info(json.loads(token_json), SCOPES)
    if creds.expired and creds.refresh_token:
        creds.refresh(Request())
    return creds
