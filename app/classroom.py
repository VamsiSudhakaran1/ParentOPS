"""Fetch courses, announcements, coursework and materials for a linked child."""
from googleapiclient.discovery import build

from . import db


def _service(creds):
    return build("classroom", "v1", credentials=creds, cache_discovery=False)


def user_profile(creds):
    """Name + email of the signed-in student (used when linking)."""
    oauth2 = build("oauth2", "v2", credentials=creds, cache_discovery=False)
    info = oauth2.userinfo().get().execute()
    return info.get("name") or info.get("email"), info.get("email")


def _attachments(materials):
    out = []
    for m in materials or []:
        if "driveFile" in m:
            f = m["driveFile"].get("driveFile", {})
            out.append({"type": "file", "title": f.get("title", "file"),
                        "link": f.get("alternateLink"), "thumb": f.get("thumbnailUrl")})
        elif "link" in m:
            out.append({"type": "link", "title": m["link"].get("title", "link"),
                        "link": m["link"].get("url")})
        elif "youtubeVideo" in m:
            out.append({"type": "video", "title": m["youtubeVideo"].get("title", "video"),
                        "link": m["youtubeVideo"].get("alternateLink")})
        elif "form" in m:
            out.append({"type": "form", "title": m["form"].get("title", "form"),
                        "link": m["form"].get("formUrl")})
    return out


def _pages(request_fn, key, **kwargs):
    token = None
    while True:
        resp = request_fn(pageToken=token, **kwargs).execute()
        yield from resp.get(key, [])
        token = resp.get("nextPageToken")
        if not token:
            break


def sync_child(child_row, creds, max_per_course=40):
    """Pull latest posts for one child. Returns count of new/changed posts."""
    svc = _service(creds)
    changed = 0
    courses = list(_pages(svc.courses().list, "courses", courseStates=["ACTIVE"]))
    for course in courses:
        cid, cname = course["id"], course.get("name", "Course")
        common = dict(course_id=cid, course_name=cname)

        for a in _pages(svc.courses().announcements().list, "announcements",
                        courseId=cid, orderBy="updateTime desc", pageSize=max_per_course):
            text = a.get("text", "")
            _, is_new = db.upsert_post(
                child_row["id"], a["id"], "announcement",
                title=(text.split("\n", 1)[0][:120] or "Announcement"),
                body=text, link=a.get("alternateLink"),
                attachments=_attachments(a.get("materials")),
                posted_at=a.get("creationTime"), updated_at=a.get("updateTime"), **common)
            changed += is_new

        for w in _pages(svc.courses().courseWork().list, "courseWork",
                        courseId=cid, orderBy="updateTime desc", pageSize=max_per_course):
            due = w.get("dueDate")
            due_iso = (f"{due['year']:04d}-{due['month']:02d}-{due['day']:02d}"
                       if due else None)
            body = w.get("description", "")
            if due_iso:
                body = f"[Due: {due_iso}]\n{body}"
            _, is_new = db.upsert_post(
                child_row["id"], w["id"], "courseWork",
                title=w.get("title", "Assignment"), body=body,
                link=w.get("alternateLink"), attachments=_attachments(w.get("materials")),
                posted_at=w.get("creationTime"), updated_at=w.get("updateTime"), **common)
            changed += is_new

        for m in _pages(svc.courses().courseWorkMaterials().list, "courseWorkMaterial",
                        courseId=cid, orderBy="updateTime desc", pageSize=max_per_course):
            _, is_new = db.upsert_post(
                child_row["id"], m["id"], "material",
                title=m.get("title", "Material"), body=m.get("description", ""),
                link=m.get("alternateLink"), attachments=_attachments(m.get("materials")),
                posted_at=m.get("creationTime"), updated_at=m.get("updateTime"), **common)
            changed += is_new
    return changed
