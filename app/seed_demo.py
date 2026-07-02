"""Seed demo data from the real VBHIS Grade 5D screenshots so the app can be
explored before any Classroom account is linked. Run: python -m app.seed_demo
Safe to re-run; skips if demo child already exists."""
import json
from datetime import date, timedelta

from . import db

TIMETABLE_5D = {
    "mon": ["Assembly", "Club", "Sci", "Math", "Art", "ELit", "Dance", "SST"],
    "tue": ["PT Swim", "Sci", "III L", "ELit", "II L", "ELang", "Math", "SST"],
    "wed": ["Sci", "T&D", "III L", "Math", "SST", "ELang", "ELit", "Math"],
    "thu": ["Math", "PT", "Sci", "II L", "Lib", "Tech", "SST", "Karate"],
    "thu_note": "PT today — send sports shoes. Karate last period — send the karate uniform.",
    "fri": ["II L", "PT Swim", "Sci", "Math", "ELang", "SST", "ELit", "Music"],
    "fri_note": "Swimming today — pack swimwear and towel.",
}


def run():
    db.init_db()
    if any(ch["name"] == "Aarav (demo)" for ch in db.children()):
        print("Demo data already present — skipping.")
        return

    kid = db.add_child("Aarav (demo)")
    db.update_child(kid, timetable_json=json.dumps(TIMETABLE_5D))
    today = date.today()

    p1, _ = db.upsert_post(
        kid, "demo-idcard", "announcement",
        course_name="Grade 5D (AY 2026-2027)",
        title="Info on ID card and submission of passport size photo and organiser office copy",
        body=("Dear Parent,\nPlease note that your child's ID card for the academic year "
              "2026-27 has been issued. As per government regulations, submission of the "
              "child's Aadhaar card is mandatory.\nAlso, please send:\n"
              "• 3 passport-size photographs of your child (in school uniform)\n"
              "• Fill pages 3, 5, 6, 7, 8 in the organiser and submit the office copy\n"
              "• Submit to the class teacher on or before Friday, 3 July 2026"),
        link=None, attachments=[],
        posted_at="2026-06-29T10:00:00Z", updated_at="2026-06-29T10:00:00Z")
    db.add_item(kid, "ID card: photos + organiser office copy", post_id=p1,
                detail="Issued for AY 2026-27; Aadhaar submission is mandatory.",
                due_date="2026-07-03",
                checklist=[
                    {"text": "3 passport-size photos (school uniform)", "done": True},
                    {"text": "Fill organiser pages 3, 5, 6, 7, 8", "done": False},
                    {"text": "Paste parent + student photo in organiser", "done": False},
                    {"text": "Submit Aadhaar copy to class teacher", "done": False}])

    p2, _ = db.upsert_post(
        kid, "demo-tamiltest", "announcement",
        course_name="II Language Tamil",
        title="Class test -1- II Language Tamil - அறிவு நிலா",
        body=("Dear children,\nThis is to remind you that there will be a Class Test for "
              "the topic \"அறிவு நிலா\" tomorrow. Kindly prepare for the class test.\n"
              "Regards, Krithika."),
        link=None, attachments=[],
        posted_at=(today.isoformat() + "T08:00:00Z"),
        updated_at=(today.isoformat() + "T08:00:00Z"))
    db.add_item(kid, "Class Test — Tamil: அறிவு நிலா", post_id=p2,
                detail=("Teacher wrote \"tomorrow\" — read as "
                        f"{(today + timedelta(days=1)).strftime('%a %d %b')} from the post time. Confirm."),
                due_date=(today + timedelta(days=1)).isoformat(), needs_confirm=1)

    p3, _ = db.upsert_post(
        kid, "demo-assembly", "announcement",
        course_name="Grade 5D (AY 2026-2027)",
        title="Upcoming assembly of grade 5D — 06.07.2026 (Monday)",
        body=("Dear Parents,\nKindly find the attached file below for the upcoming assembly "
              "of grade 5D to be held on 06.07.2026 (Monday).\nRegards, Subhashree"),
        link=None,
        attachments=[{"type": "file", "title": "Assembly content-5D.pdf", "link": "#"}],
        posted_at="2026-07-01T09:00:00Z", updated_at="2026-07-01T09:00:00Z")
    db.add_item(kid, "Grade 5D Assembly — prepare over the weekend", post_id=p3,
                detail="Details in the attached PDF (Assembly content-5D.pdf).",
                due_date="2026-07-06")

    for gid, cname, title in [
        ("demo-m1", "II Language Tamil", "New material: II Language Tamil - மரபுச் சொற்கள்"),
        ("demo-m2", "English Language", "New material: ARTICLES"),
        ("demo-m3", "English Literature", "New material: Novel: Chapter 4 - At Flourish and Blotts"),
        ("demo-m4", "III Lang Hindi", "New material: III Lang Hindi"),
    ]:
        db.upsert_post(kid, gid, "material", course_name=cname, title=title, body="",
                       link=None, attachments=[],
                       posted_at="2026-07-01T16:00:00Z", updated_at="2026-07-01T16:00:00Z")

    # Timetable announcement itself, searchable in the library
    db.upsert_post(
        kid, "demo-timetable", "announcement",
        course_name="Grade 5D (AY 2026-2027)",
        title="Timetable for the academic year 2026-2027",
        body=("Dear Students,\nPlease refer to the timetable for the academic year 2026-2027. "
              "This timetable will be followed from tomorrow.\nRegards, VBHIS"),
        link=None, attachments=[{"type": "file", "title": "5D.jpg", "link": "#"}],
        posted_at="2026-06-20T10:00:00Z", updated_at="2026-06-20T10:00:00Z")

    # Mark all demo posts extracted so heuristics don't double-create items.
    for post in db.unextracted_posts():
        db.mark_extracted(post["id"])
    print("Demo data seeded: 1 child, 6 posts, 3 action items.")


if __name__ == "__main__":
    run()
