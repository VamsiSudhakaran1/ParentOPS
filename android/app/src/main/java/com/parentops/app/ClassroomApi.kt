package com.parentops.app

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Direct REST calls to the Google Classroom API from the device. */
object ClassroomApi {
    private const val BASE = "https://classroom.googleapis.com/v1"
    private val http = OkHttpClient()

    private fun get(token: String, url: String): JSONObject {
        val req = Request.Builder().url(url)
            .header("Authorization", "Bearer $token").build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string() ?: "{}"
            if (!resp.isSuccessful) {
                throw RuntimeException("Classroom API ${resp.code}: ${body.take(200)}")
            }
            return JSONObject(body)
        }
    }

    private fun paged(token: String, url: String, listKey: String): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        var pageToken: String? = null
        do {
            val sep = if (url.contains('?')) "&" else "?"
            val full = url + (pageToken?.let { "${sep}pageToken=$it" } ?: "")
            val resp = get(token, full)
            val arr: JSONArray = resp.optJSONArray(listKey) ?: JSONArray()
            for (i in 0 until arr.length()) out.add(arr.getJSONObject(i))
            pageToken = resp.optString("nextPageToken").ifEmpty { null }
        } while (pageToken != null)
        return out
    }

    private fun attachments(materials: JSONArray?): List<Attachment> {
        val out = mutableListOf<Attachment>()
        if (materials == null) return out
        for (i in 0 until materials.length()) {
            val m = materials.getJSONObject(i)
            m.optJSONObject("driveFile")?.optJSONObject("driveFile")?.let {
                out.add(Attachment(it.optString("title", "file"), it.optString("alternateLink")))
            }
            m.optJSONObject("link")?.let {
                out.add(Attachment(it.optString("title", "link"), it.optString("url")))
            }
            m.optJSONObject("youtubeVideo")?.let {
                out.add(Attachment(it.optString("title", "video"), it.optString("alternateLink")))
            }
            m.optJSONObject("form")?.let {
                out.add(Attachment(it.optString("title", "form"), it.optString("formUrl")))
            }
        }
        return out
    }

    /** Everything for one child: announcements, coursework, materials. */
    fun fetchAll(token: String, childEmail: String): List<Post> {
        val posts = mutableListOf<Post>()
        val courses = paged(token, "$BASE/courses?courseStates=ACTIVE", "courses")
        for (course in courses) {
            val cid = course.getString("id")
            val cname = course.optString("name", "Course")

            for (a in paged(token, "$BASE/courses/$cid/announcements?pageSize=40", "announcements")) {
                val text = a.optString("text")
                posts.add(Post(
                    key = "$childEmail|announcement|${a.getString("id")}",
                    childEmail = childEmail, kind = "announcement", courseName = cname,
                    title = text.lineSequence().firstOrNull()?.take(120) ?: "Announcement",
                    body = text, link = a.optString("alternateLink"),
                    attachments = attachments(a.optJSONArray("materials")),
                    postedAt = a.optString("creationTime"),
                    updatedAt = a.optString("updateTime")))
            }

            for (w in paged(token, "$BASE/courses/$cid/courseWork?pageSize=40", "courseWork")) {
                var body = w.optString("description")
                w.optJSONObject("dueDate")?.let {
                    val due = "%04d-%02d-%02d".format(
                        it.optInt("year"), it.optInt("month"), it.optInt("day"))
                    body = "[Due: $due]\n$body"
                }
                posts.add(Post(
                    key = "$childEmail|courseWork|${w.getString("id")}",
                    childEmail = childEmail, kind = "courseWork", courseName = cname,
                    title = w.optString("title", "Assignment"), body = body,
                    link = w.optString("alternateLink"),
                    attachments = attachments(w.optJSONArray("materials")),
                    postedAt = w.optString("creationTime"),
                    updatedAt = w.optString("updateTime")))
            }

            for (m in paged(token, "$BASE/courses/$cid/courseWorkMaterials?pageSize=40", "courseWorkMaterial")) {
                posts.add(Post(
                    key = "$childEmail|material|${m.getString("id")}",
                    childEmail = childEmail, kind = "material", courseName = cname,
                    title = m.optString("title", "Material"), body = m.optString("description"),
                    link = m.optString("alternateLink"),
                    attachments = attachments(m.optJSONArray("materials")),
                    postedAt = m.optString("creationTime"),
                    updatedAt = m.optString("updateTime")))
            }
        }
        return posts
    }
}
