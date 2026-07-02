package com.parentops.app

import android.content.Context
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Pulls Classroom for every linked child and extracts new action items. */
object SyncManager {

    suspend fun sync(ctx: Context, data: AppData): Pair<Int, List<String>> =
        withContext(Dispatchers.IO) {
            var newPosts = 0
            val errors = mutableListOf<String>()
            for (child in data.children) {
                if (!child.email.contains("@")) continue  // manual child, no account
                try {
                    val token = GoogleAuthHelper.tokenOrApproval(ctx, child.email)
                    val fetched = ClassroomApi.fetchAll(token, child.email)
                    val known = data.posts.map { it.key }.toHashSet()
                    val knownItems = data.items.map { it.id }.toHashSet()
                    for (post in fetched) {
                        if (post.key in known) continue
                        data.posts.add(post)
                        newPosts++
                        for (item in Extract.fromPost(post, child)) {
                            if (item.id !in knownItems) data.items.add(item)
                        }
                    }
                } catch (e: GoogleAuthHelper.NeedsApproval) {
                    throw e // caller launches the approval intent
                } catch (e: Exception) {
                    errors.add("${child.name}: ${e.message?.take(120)}")
                }
            }
            data.posts.sortByDescending { it.postedAt }
            data.lastSync = LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("d MMM HH:mm"))
            data.lastSyncError = errors.joinToString("; ").ifEmpty { null }
            Store.save(ctx, data)
            Pair(newPosts, errors)
        }
}
