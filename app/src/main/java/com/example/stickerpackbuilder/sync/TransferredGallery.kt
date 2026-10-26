package com.example.stickerpackbuilder.sync

import android.content.Context
import android.graphics.Bitmap
import com.example.stickerpackbuilder.StickerConverter
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Properties

class TransferredGallery(context: Context) {
    data class Delivered(val url: String, val sticker: File, val animated: Boolean, val hash: String)
    companion object { private val storageLock = Any() }
    private val folder = File(context.filesDir, "transfer/gallery").apply { mkdirs() }
    private val index = File(folder, "index.json")
    fun entries(): List<JSONObject> = synchronized(storageLock) { if (index.exists()) JSONArray(index.readText()).let { a ->
        (0 until a.length()).map { a.getJSONObject(it) }.sortedByDescending { it.optLong("time") }
    } else emptyList() }
    fun preview(entry: JSONObject) = File(folder, entry.getString("key") + ".webp")
    fun seedHistory(ledger: File) = synchronized(storageLock) {
        if (!ledger.exists()) return@synchronized
        val existing = entries().associateBy { it.getString("key") }.toMutableMap()
        val previousSize = existing.size
        val properties = Properties().apply { ledger.inputStream().use { load(it) } }
        properties.stringPropertyNames().filter { it.matches(Regex("[0-9a-f]{64}")) }.forEach { key ->
            existing.putIfAbsent(key, JSONObject().put("key", key).put("time", properties.getProperty(key).toLongOrNull() ?: 0))
        }
        if (existing.size != previousSize) save(existing.values.toList())
    }
    fun remember(url: String, sticker: File, animated: Boolean, whatsappHash: String = "") =
        rememberBatch(listOf(Delivered(url, sticker, animated, whatsappHash)))
    fun rememberBatch(stickers: List<Delivered>) = synchronized(storageLock) {
        if (stickers.isEmpty()) return@synchronized
        val existing = entries().associateBy { it.getString("key") }.toMutableMap()
        for ((url, sticker, animated, whatsappHash) in stickers) {
        val key = CollectedStickerLedger.assetKey(url)
        val frame = StickerConverter.firstFrame(sticker)
        val small = try { StickerConverter.fitToSquare(frame, 160) } finally { frame.recycle() }
        val thumbnail = File(folder, "$key.webp")
        val temporary = File(folder, "$key.tmp")
        try { temporary.outputStream().use {
            @Suppress("DEPRECATION")
            check(small.compress(if (android.os.Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP, 80, it))
        } } finally { small.recycle() }
        java.nio.file.Files.move(temporary.toPath(), thumbnail.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        existing[key] = JSONObject().put("key", key).put("animated", animated)
            .put("whatsappHash", whatsappHash)
            .put("time", existing[key]?.optLong("time") ?: System.currentTimeMillis())
        }
        save(existing.values.toList())
    }
    fun hasPreview(url: String) = File(folder, CollectedStickerLedger.assetKey(url) + ".webp").exists()
    fun forget(keys: Set<String>) = synchronized(storageLock) {
        val previous = entries()
        save(previous.filterNot { it.getString("key") in keys })
        previous.filter { it.getString("key") in keys }.forEach { preview(it).delete() }
    }
    private fun save(entries: List<JSONObject>) {
        val temp = File(folder, "index.tmp")
        temp.writeText(JSONArray(entries).toString())
        java.nio.file.Files.move(temp.toPath(), index.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }
}
