"""SQLite storage for ParentOps: children, posts, action items."""
import json
import os
import sqlite3
from contextlib import contextmanager

DB_PATH = os.environ.get("PARENTOPS_DB", "parentops.db")

SCHEMA = """
CREATE TABLE IF NOT EXISTS children (
    id INTEGER PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT UNIQUE,
    color TEXT NOT NULL DEFAULT '#4653e8',
    token_json TEXT,
    timetable_json TEXT
);
CREATE TABLE IF NOT EXISTS posts (
    id INTEGER PRIMARY KEY,
    child_id INTEGER NOT NULL REFERENCES children(id) ON DELETE CASCADE,
    google_id TEXT NOT NULL,
    kind TEXT NOT NULL,              -- announcement | courseWork | material
    course_id TEXT,
    course_name TEXT,
    title TEXT,
    body TEXT,
    link TEXT,
    attachments_json TEXT,
    posted_at TEXT,
    updated_at TEXT,
    extracted INTEGER NOT NULL DEFAULT 0,
    UNIQUE(child_id, kind, google_id)
);
CREATE TABLE IF NOT EXISTS settings (
    key TEXT PRIMARY KEY,
    value TEXT
);
CREATE TABLE IF NOT EXISTS items (
    id INTEGER PRIMARY KEY,
    post_id INTEGER REFERENCES posts(id) ON DELETE CASCADE,
    child_id INTEGER NOT NULL REFERENCES children(id) ON DELETE CASCADE,
    title TEXT NOT NULL,
    detail TEXT,
    due_date TEXT,                   -- ISO date or NULL
    amount TEXT,
    checklist_json TEXT,             -- [{"text":..., "done":false}, ...]
    needs_confirm INTEGER NOT NULL DEFAULT 0,
    status TEXT NOT NULL DEFAULT 'open',   -- open | done | dismissed
    done_at TEXT,
    created_at TEXT DEFAULT (datetime('now'))
);
"""


@contextmanager
def conn():
    c = sqlite3.connect(DB_PATH)
    c.row_factory = sqlite3.Row
    c.execute("PRAGMA foreign_keys = ON")
    try:
        yield c
        c.commit()
    finally:
        c.close()


def init_db():
    with conn() as c:
        c.executescript(SCHEMA)
        try:  # migration for DBs created before item categories existed
            c.execute("ALTER TABLE items ADD COLUMN category TEXT")
        except sqlite3.OperationalError:
            pass


# ---------- settings ----------

def get_setting(key, default=None):
    with conn() as c:
        r = c.execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
        return r["value"] if r else default


def set_setting(key, value):
    with conn() as c:
        c.execute("INSERT INTO settings (key, value) VALUES (?,?)"
                  " ON CONFLICT(key) DO UPDATE SET value=excluded.value", (key, value))


def delete_setting(key):
    with conn() as c:
        c.execute("DELETE FROM settings WHERE key=?", (key,))


# ---------- children ----------

CHILD_COLORS = ["#4653e8", "#0e9384", "#b54708", "#7a5af8"]


def add_child(name, email=None, token_json=None):
    with conn() as c:
        n = c.execute("SELECT COUNT(*) FROM children").fetchone()[0]
        color = CHILD_COLORS[n % len(CHILD_COLORS)]
        cur = c.execute(
            "INSERT INTO children (name, email, color, token_json) VALUES (?,?,?,?) "
            "ON CONFLICT(email) DO UPDATE SET token_json=excluded.token_json "
            "RETURNING id",
            (name, email, color, token_json),
        )
        return cur.fetchone()[0]


def children():
    with conn() as c:
        return [dict(r) for r in c.execute("SELECT * FROM children ORDER BY id")]


def child(child_id):
    with conn() as c:
        r = c.execute("SELECT * FROM children WHERE id=?", (child_id,)).fetchone()
        return dict(r) if r else None


def update_child(child_id, **fields):
    sets = ", ".join(f"{k}=?" for k in fields)
    with conn() as c:
        c.execute(f"UPDATE children SET {sets} WHERE id=?", (*fields.values(), child_id))


def delete_child(child_id):
    with conn() as c:
        c.execute("DELETE FROM children WHERE id=?", (child_id,))


# ---------- posts ----------

def upsert_post(child_id, google_id, kind, **f):
    """Insert or update a post. Returns (post_id, is_new_or_changed)."""
    with conn() as c:
        row = c.execute(
            "SELECT id, updated_at FROM posts WHERE child_id=? AND kind=? AND google_id=?",
            (child_id, kind, google_id),
        ).fetchone()
        if row is None:
            cur = c.execute(
                "INSERT INTO posts (child_id, google_id, kind, course_id, course_name,"
                " title, body, link, attachments_json, posted_at, updated_at)"
                " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                (child_id, google_id, kind, f.get("course_id"), f.get("course_name"),
                 f.get("title"), f.get("body"), f.get("link"),
                 json.dumps(f.get("attachments", [])), f.get("posted_at"), f.get("updated_at")),
            )
            return cur.lastrowid, True
        if row["updated_at"] != f.get("updated_at"):
            c.execute(
                "UPDATE posts SET title=?, body=?, link=?, attachments_json=?,"
                " updated_at=?, extracted=0 WHERE id=?",
                (f.get("title"), f.get("body"), f.get("link"),
                 json.dumps(f.get("attachments", [])), f.get("updated_at"), row["id"]),
            )
            return row["id"], True
        return row["id"], False


def unextracted_posts():
    with conn() as c:
        return [dict(r) for r in c.execute(
            "SELECT p.*, ch.name AS child_name FROM posts p JOIN children ch ON ch.id=p.child_id"
            " WHERE p.extracted=0 ORDER BY p.posted_at")]


def mark_extracted(post_id):
    with conn() as c:
        c.execute("UPDATE posts SET extracted=1 WHERE id=?", (post_id,))


def search_posts(q=None, child_id=None, course=None, limit=200):
    sql = ("SELECT p.*, ch.name AS child_name, ch.color AS child_color FROM posts p"
           " JOIN children ch ON ch.id=p.child_id WHERE 1=1")
    args = []
    if q:
        sql += " AND (p.title LIKE ? OR p.body LIKE ? OR p.course_name LIKE ?)"
        like = f"%{q}%"
        args += [like, like, like]
    if child_id:
        sql += " AND p.child_id=?"
        args.append(child_id)
    if course:
        sql += " AND p.course_name=?"
        args.append(course)
    sql += " ORDER BY p.posted_at DESC LIMIT ?"
    args.append(limit)
    with conn() as c:
        return [dict(r) for r in c.execute(sql, args)]


def courses_summary():
    with conn() as c:
        return [dict(r) for r in c.execute(
            "SELECT p.child_id, ch.name AS child_name, ch.color AS child_color,"
            " p.course_name, COUNT(*) AS n, MAX(p.posted_at) AS latest"
            " FROM posts p JOIN children ch ON ch.id=p.child_id"
            " WHERE p.course_name IS NOT NULL"
            " GROUP BY p.child_id, p.course_name ORDER BY latest DESC")]


# ---------- items ----------

def add_item(child_id, title, post_id=None, detail=None, due_date=None, amount=None,
             checklist=None, category=None):
    with conn() as c:
        cur = c.execute(
            "INSERT INTO items (post_id, child_id, title, detail, due_date, amount,"
            " checklist_json, category) VALUES (?,?,?,?,?,?,?,?)",
            (post_id, child_id, title, detail, due_date, amount,
             json.dumps(checklist or []), category),
        )
        return cur.lastrowid


def items(status="open", child_id=None):
    sql = ("SELECT i.*, ch.name AS child_name, ch.color AS child_color,"
           " p.course_name, p.link AS post_link"
           " FROM items i JOIN children ch ON ch.id=i.child_id"
           " LEFT JOIN posts p ON p.id=i.post_id WHERE i.status=?")
    args = [status]
    if child_id:
        sql += " AND i.child_id=?"
        args.append(child_id)
    sql += " ORDER BY i.due_date IS NULL, i.due_date, i.id"
    with conn() as c:
        rows = [dict(r) for r in c.execute(sql, args)]
    for r in rows:
        r["checklist"] = json.loads(r.get("checklist_json") or "[]")
    return rows


def item(item_id):
    with conn() as c:
        r = c.execute("SELECT * FROM items WHERE id=?", (item_id,)).fetchone()
        if not r:
            return None
        d = dict(r)
        d["checklist"] = json.loads(d.get("checklist_json") or "[]")
        return d


def update_item(item_id, **fields):
    if "checklist" in fields:
        fields["checklist_json"] = json.dumps(fields.pop("checklist"))
    sets = ", ".join(f"{k}=?" for k in fields)
    with conn() as c:
        c.execute(f"UPDATE items SET {sets} WHERE id=?", (*fields.values(), item_id))


def items_done(child_id=None, limit=300):
    """All finished tasks, newest first, with the day they were completed."""
    sql = ("SELECT i.*, ch.name AS child_name, ch.color AS child_color,"
           " p.course_name, p.link AS post_link, date(i.done_at) AS done_day"
           " FROM items i JOIN children ch ON ch.id=i.child_id"
           " LEFT JOIN posts p ON p.id=i.post_id WHERE i.status='done'")
    args = []
    if child_id:
        sql += " AND i.child_id=?"
        args.append(child_id)
    sql += " ORDER BY i.done_at DESC LIMIT ?"
    args.append(limit)
    with conn() as c:
        rows = [dict(r) for r in c.execute(sql, args)]
    for r in rows:
        r["checklist"] = json.loads(r.get("checklist_json") or "[]")
    return rows


def items_done_on(date_iso, child_id=None):
    sql = ("SELECT i.*, ch.name AS child_name, ch.color AS child_color FROM items i"
           " JOIN children ch ON ch.id=i.child_id"
           " WHERE i.status='done' AND date(i.done_at)=?")
    args = [date_iso]
    if child_id:
        sql += " AND i.child_id=?"
        args.append(child_id)
    with conn() as c:
        rows = [dict(r) for r in c.execute(sql, args)]
    for r in rows:
        r["checklist"] = json.loads(r.get("checklist_json") or "[]")
    return rows
