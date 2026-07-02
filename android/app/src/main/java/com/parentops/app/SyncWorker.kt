package com.parentops.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Background sync + a notification when something needs attention soon. */
class SyncWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val data = Store.load(ctx)
        if (data.children.isEmpty()) return Result.success()
        try {
            SyncManager.sync(ctx, data)
        } catch (e: Exception) {
            return Result.retry()
        }
        val today = LocalDate.now().toString()
        val tomorrow = LocalDate.now().plusDays(1).toString()
        val urgent = data.items.filter {
            it.status == "open" && it.dueDate != null && it.dueDate!! <= tomorrow
        }
        if (urgent.isNotEmpty()) {
            notify(ctx, urgent.size, urgent.first().title,
                dueToday = urgent.count { it.dueDate!! <= today })
        }
        return Result.success()
    }

    private fun notify(ctx: Context, count: Int, first: String, dueToday: Int) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(
            "parentops", "ParentOps reminders", NotificationManager.IMPORTANCE_DEFAULT))
        val pi = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = if (count == 1) first
            else "$first — and ${count - 1} more due by tomorrow"
        val title = if (dueToday > 0) "⏰ $dueToday due today" else "📌 Due tomorrow"
        nm.notify(1, NotificationCompat.Builder(ctx, "parentops")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build())
    }

    companion object {
        fun schedule(ctx: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(
                "parentops-sync", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
