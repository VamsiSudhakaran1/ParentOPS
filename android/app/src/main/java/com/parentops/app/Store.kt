package com.parentops.app

import android.content.Context
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** All app data lives in one JSON file on the device — no server anywhere. */
object Store {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun file(ctx: Context) = File(ctx.filesDir, "parentops.json")

    fun load(ctx: Context): AppData = try {
        val f = file(ctx)
        if (f.exists()) json.decodeFromString<AppData>(f.readText()) else AppData()
    } catch (e: Exception) {
        AppData()
    }

    fun save(ctx: Context, data: AppData) {
        val tmp = File(ctx.filesDir, "parentops.json.tmp")
        tmp.writeText(json.encodeToString(data))
        tmp.renameTo(file(ctx))
    }
}
