package com.parentops.app

import kotlinx.serialization.Serializable

@Serializable
data class Child(
    val email: String,
    var name: String,
    val color: Long,                       // ARGB color for this child's tag
    var timetable: Map<String, List<String>> = emptyMap(),  // "mon".."sun" -> periods
)

@Serializable
data class Post(
    val key: String,                       // childEmail|kind|googleId
    val childEmail: String,
    val kind: String,                      // announcement | courseWork | material
    val courseName: String,
    val title: String,
    val body: String,
    val link: String? = null,
    val attachments: List<Attachment> = emptyList(),
    val postedAt: String = "",             // ISO instant from the API
    val updatedAt: String = "",
)

@Serializable
data class Attachment(val title: String, val link: String)

@Serializable
data class ChecklistEntry(var text: String, var done: Boolean = false)

@Serializable
data class ActionItem(
    val id: String,                        // postKey#index
    val childEmail: String,
    val postKey: String? = null,
    var title: String,
    var detail: String? = null,
    var dueDate: String? = null,           // YYYY-MM-DD
    var amount: String? = null,
    var category: String? = null,          // holiday | fee | test | event | task
    var checklist: MutableList<ChecklistEntry> = mutableListOf(),
    var status: String = "open",           // open | done | dismissed
    var doneAt: String? = null,            // YYYY-MM-DD
)

@Serializable
data class AppData(
    val children: MutableList<Child> = mutableListOf(),
    val posts: MutableList<Post> = mutableListOf(),
    val items: MutableList<ActionItem> = mutableListOf(),
    var lastSync: String? = null,
    var lastSyncError: String? = null,
)

val CHILD_COLORS = listOf(0xFF4653E8, 0xFF0E9384, 0xFFB54708, 0xFF7A5AF8)
