package com.example.stickerpackbuilder.reverse

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Journal external creation before pressing Create; an uncertain attempt blocks duplicates. */
object ReverseHistory {
    private fun file(context: Context) = File(context.filesDir, "reverse/history.json").apply { parentFile!!.mkdirs() }
    private fun destination(context: Context) = context.getSharedPreferences("reverse", 0).getString("destination", "").orEmpty()
    private fun entries(context: Context): List<JSONObject> = file(context).takeIf { it.exists() }?.let {
        JSONArray(it.readText()).let { a -> (0 until a.length()).map { i -> a.getJSONObject(i) } }
    }.orEmpty()
    @Synchronized fun record(context: Context, entry: JSONObject) {
        val account = destination(context)
        require(account.isNotBlank()) { "Verify your TikTok account first" }
        entry.put("destination", account)
        val rows = entries(context).filterNot { it.optString("destination") == account && it.optString("sourceId") == entry.optString("sourceId") } + entry
        val target = file(context); val temp = File(target.parentFile, "history.tmp")
        temp.writeText(JSONArray(rows).toString())
        java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
    /** Across all destinations: never discard a source still referenced by an uncertain creation. */
    @Synchronized fun protectedStagedUris(context:Context):Set<String> = entries(context)
        .filter{it.optBoolean("submitted")}.mapNotNull{it.optString("stagedUri").takeIf(String::isNotBlank)}.toSet()
    @Synchronized fun submittedIds(context:Context):Set<String> = entries(context)
        .filter{it.optString("destination")==destination(context)&&it.optBoolean("submitted")}
        .map{it.optString("sourceId")}.toSet()
    @Synchronized fun submitted(context: Context, id: String): Boolean = entries(context).any {
        it.optString("destination") == destination(context) && it.optString("sourceId") == id && it.optBoolean("submitted")
    }
    @Synchronized fun result(context: Context, id: String): String = entries(context).firstOrNull {
        it.optString("destination") == destination(context) && it.optString("sourceId") == id
    }?.optString("result").orEmpty()
}
