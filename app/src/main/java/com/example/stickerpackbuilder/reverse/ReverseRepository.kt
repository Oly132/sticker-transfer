package com.example.stickerpackbuilder.reverse

import android.content.Context
import com.example.stickerpackbuilder.sync.PhoneRuntime
import com.example.stickerpackbuilder.sync.TransferredGallery
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

object ReverseState { @Volatile var active = false }
data class ReverseSticker(val id: String, val lottie: Boolean = false, val time: Long = 0, val fromTikTok: Boolean = false)
class ReverseRepository(private val context: Context) {
    private val root = File(context.filesDir, "reverse").apply { mkdirs() }
    private val catalogue = File(root, "catalogue.json")
    fun original(id: String): File { require(id.matches(Regex("[a-f0-9]{64}"))); return File(root, "originals/$id.webp") }
    fun partial()=context.getSharedPreferences("reverse",0).getBoolean("partialCatalogue",false)
    fun cached(): List<ReverseSticker> = runCatching { parse(JSONArray(catalogue.readText())) }.getOrDefault(emptyList())
    private fun parse(array: JSONArray) = (0 until array.length()).map { array.getJSONObject(it) }
        .filter { it.optString("id").matches(Regex("[a-f0-9]{64}")) }
        .map { ReverseSticker(it.getString("id"), it.optBoolean("isLottie"), it.optLong("observedAt"), it.optBoolean("fromTikTok")) }.distinctBy { it.id }.sortedByDescending { it.time }
    fun refresh(): List<ReverseSticker> {
        val response = PhoneRuntime.request(context, "/reverse/refresh", JSONObject())
        val array = response.getJSONArray("favorites")
        context.getSharedPreferences("reverse",0).edit().putBoolean("partialCatalogue",response.optBoolean("partial")).apply()
        val temp = File(root, "catalogue.tmp"); temp.writeText(array.toString())
        java.nio.file.Files.move(temp.toPath(), catalogue.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        return parse(array)
    }
    fun fromTikTok(): Set<String> = TransferredGallery(context).entries().mapNotNull { row ->
        runCatching { android.util.Base64.decode(row.optString("whatsappHash"), android.util.Base64.DEFAULT).takeIf { it.size == 32 }?.joinToString("") { "%02x".format(it) } }.getOrNull()
    }.toSet() + cached().filter { it.fromTikTok }.map { it.id }
    fun verified(id: String): Boolean = verified(original(id), id)
    suspend fun download(rows: List<ReverseSticker>, retry: Boolean = false, progress: (String, String?) -> Unit) = supervisorScope {
        val slots = Semaphore(4)
        rows.map { row -> async(Dispatchers.IO) { slots.withPermit {
            ensureActive()
            val failure = if (row.lottie) "Lottie originals aren't supported by TikTok's image importer." else runCatching {
                if (!verified(row.id)) runInterruptible { PhoneRuntime.request(context, "/reverse/download", JSONObject().put("id", row.id).put("retry", retry)) }
                check(verified(row.id)) { "This original failed verification. Nothing was imported." }
            }.exceptionOrNull()?.message
            withContext(Dispatchers.Main) { progress(row.id, failure) }
        } } }.awaitAll()
    }
    companion object {
        fun hash(file: File): String = file.inputStream().use { input ->
            val digest = MessageDigest.getInstance("SHA-256"); val bytes = ByteArray(65536)
            while (true) { if (Thread.currentThread().isInterrupted) throw InterruptedException(); val count = input.read(bytes); if (count < 0) break; digest.update(bytes, 0, count) }
            digest.digest().joinToString("") { "%02x".format(it) }
        }
        fun verified(file: File, id: String): Boolean = runCatching {
            if (file.length() !in 12..8L*1024*1024) return false
            val header = ByteArray(12); file.inputStream().use { if (it.read(header) != 12) return false }
            String(header, 0, 4, Charsets.US_ASCII) == "RIFF" && String(header, 8, 4, Charsets.US_ASCII) == "WEBP" && hash(file) == id
        }.getOrDefault(false)
    }
}
