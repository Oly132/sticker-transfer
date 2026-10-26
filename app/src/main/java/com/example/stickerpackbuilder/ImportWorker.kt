package com.example.stickerpackbuilder

import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import android.webkit.CookieManager
import androidx.work.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.TimeUnit

object ImportJobs {
    const val TAG = "sticker-import"
    private fun dir(context: Context) = File(context.filesDir, "imports").apply { mkdirs() }
    private fun file(context: Context, id: String): AtomicFile {
        require(id.matches(Regex("[a-f0-9-]{36}")))
        return AtomicFile(File(dir(context), id + ".json"))
    }
    @Synchronized fun read(context: Context, id: String): JSONObject =
        file(context, id).openRead().use { JSONObject(it.bufferedReader().readText()) }
    @Synchronized fun write(context: Context, id: String, obj: JSONObject) {
        val f = file(context, id)
        val stream = f.startWrite()
        try { stream.write(obj.toString().toByteArray()); f.finishWrite(stream) }
        catch (e: Exception) { f.failWrite(stream); throw e }
    }
    fun enqueue(context: Context, sources: List<String>, local: Boolean = false): UUID {
        require(sources.isNotEmpty() && sources.size <= 30) { "Import up to 30 stickers at a time" }
        require(sources.all { if (local) Uri.parse(it).scheme == "content" else OriginPolicy.isStickerUrl(it) }) {
            "An image address isn't supported. Try saving it and importing from your phone."
        }
        val id = UUID.randomUUID().toString()
        val items = JSONArray().apply {
            sources.distinct().forEach { put(JSONObject().put("source", it).put("state", "pending")) }
        }
        write(context, id, JSONObject().put("items", items).put("local", local).put("created", System.currentTimeMillis())
            .put("automatic", PackRepository.isAutomaticMode(context)))
        val request = OneTimeWorkRequestBuilder<ImportWorker>()
            .setInputData(workDataOf("job" to id)).addTag(TAG)
            .setConstraints(Constraints.Builder().apply {
                if (!local) setRequiredNetworkType(NetworkType.CONNECTED)
            }.build()).build()
        WorkManager.getInstance(context).beginUniqueWork(TAG, ExistingWorkPolicy.APPEND_OR_REPLACE, request).enqueue()
        return request.id
    }
    fun retry(context: Context, id: String): UUID {
        val job = read(context, id)
        val items = job.getJSONArray("items")
        val failed = (0 until items.length()).map { items.getJSONObject(it) }.filter { it.optString("state") == "failed" }
        require(failed.isNotEmpty()) { "Nothing to retry" }
        return enqueue(context, failed.map { it.getString("source") }, job.getBoolean("local")).also {
            failed.forEach { entry -> entry.put("state", "retried") }
            write(context, id, job)
        }
    }
    fun dismiss(context: Context, id: String) {
        val job = read(context, id)
        if (job.getBoolean("local")) {
            val items = job.getJSONArray("items")
            for (i in 0 until items.length()) release(context, items.getJSONObject(i).getString("source"))
        }
        job.put("dismissed", true)
        write(context, id, job)
    }
    fun release(context: Context, source: String) {
        if (source.startsWith("content:")) runCatching {
            context.contentResolver.releasePersistableUriPermission(Uri.parse(source),
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}

class ImportWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS).dns(object : Dns {
            override fun lookup(hostname: String) = Dns.SYSTEM.lookup(hostname).also { addresses ->
                require(addresses.isNotEmpty() && addresses.all(OriginPolicy::isPublicAddress)) { "Unsafe image address" }
            }
        }).build()
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val id = inputData.getString("job") ?: return@withContext Result.failure()
        val job = try { ImportJobs.read(applicationContext, id) } catch (_: Exception) {
            return@withContext Result.failure(workDataOf("message" to "Couldn't open this import. Select your stickers again."))
        }
        val items = job.getJSONArray("items")
        var done = 0
        var duplicates = 0
        var failed = 0
        for (i in 0 until items.length()) {
            if (isStopped) throw CancellationException()
            val entry = items.getJSONObject(i)
            when (entry.optString("state")) {
                "done" -> { done++; continue }
                "duplicate" -> { duplicates++; continue }
                "failed", "retried" -> { failed++; continue }
            }
            setProgress(workDataOf("message" to "Importing sticker ${i + 1} of ${items.length()}",
                "current" to i, "total" to items.length()))
            val source = entry.getString("source")
            val raw = File(PackRepository.incomingDir(applicationContext), "${id}_${i}.bin")
            val converted = File(PackRepository.incomingDir(applicationContext), "${id}_${i}.webp")
            try {
                if (job.getBoolean("local")) {
                    require(Uri.parse(source).scheme == "content")
                    applicationContext.contentResolver.openInputStream(Uri.parse(source)).use {
                        requireNotNull(it) { "Couldn't open that image. Select it again." }
                        copyLimited(it, raw)
                    }
                } else {
                    var last: Exception? = null
                    for (attempt in 0..2) {
                        try { download(source, raw); last = null; break }
                        catch (e: IOException) { last = e; if (attempt < 2) delay(1000L * (attempt + 1)) }
                    }
                    if (last != null) throw last
                }
                val hash = PackRepository.sha256(raw)
                val result = if (PackRepository.containsHash(applicationContext, hash)) PackRepository.ImportResult.DUPLICATE
                else {
                    val type = StickerConverter.convert(raw, converted)
                    if (isStopped) throw CancellationException()
                    PackRepository.accept(applicationContext, converted, type.animated, hash, job.getBoolean("automatic"))
                }
                if (result == PackRepository.ImportResult.ADDED) { entry.put("state", "done"); done++ }
                else { entry.put("state", "duplicate"); duplicates++ }
                ImportJobs.write(applicationContext, id, job)
                ImportJobs.release(applicationContext, source)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                failed++
                entry.put("state", "failed").put("error", friendlyError(e))
                ImportJobs.write(applicationContext, id, job)
            } finally { raw.delete(); converted.delete() }
        }
        val message = buildString {
            append("$done imported")
            if (duplicates > 0) append(" · $duplicates already in your library")
            if (failed > 0) append(" · $failed couldn't import")
            if (done > 0) append(if (job.getBoolean("automatic")) ". Your packs are ready to review." else ". Review your stickers below.")
        }
        Result.success(workDataOf("job" to id, "message" to message, "failed" to failed))
    }
    private fun friendlyError(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> "Download timed out. Try again."
        is java.net.UnknownHostException -> "Couldn't reach the image server. Check your connection."
        is IOException -> "Download failed. Check your connection and try again."
        else -> e.message?.take(220) ?: "Couldn't import this image."
    }
    private fun copyLimited(input: java.io.InputStream, out: File) {
        out.outputStream().use { output ->
            val buffer = ByteArray(8192)
            var total = 0
            while (true) {
                if (isStopped) throw CancellationException()
                val n = input.read(buffer)
                if (n < 0) break
                total += n
                require(total <= 8 * 1024 * 1024) { "Image exceeds 8 MB" }
                output.write(buffer, 0, n)
            }
            require(total > 0) { "The image is empty" }
        }
    }
    private fun download(source: String, out: File) {
        var current = source
        repeat(5) {
            require(OriginPolicy.isStickerUrl(current)) { "Image redirects to an unsupported server" }
            val request = Request.Builder().url(current).header("Referer", "https://www.tiktok.com/")
            if (OriginPolicy.isTikTokPage(current)) CookieManager.getInstance().getCookie(current)?.let {
                request.header("Cookie", it)
            }
            client.newCall(request.build()).execute().use { response ->
                if (response.code in listOf(301,302,303,307,308)) {
                    current = response.request.url.resolve(response.header("Location") ?: error("Missing image redirect"))
                        ?.toString() ?: error("Invalid image redirect")
                } else {
                    if (response.code == 429 || response.code >= 500) throw IOException("Temporary download failure")
                    require(response.isSuccessful) { "Image link expired or unavailable. Select it again in TikTok." }
                    val body = requireNotNull(response.body)
                    require(body.contentLength() <= 8 * 1024 * 1024) { "Image exceeds 8 MB" }
                    val type = body.contentType()?.toString().orEmpty()
                    require(type.startsWith("image/") || type.startsWith("application/octet-stream") || type.isEmpty()) {
                        "The server didn't return an image"
                    }
                    body.byteStream().use { copyLimited(it, out) }
                    return
                }
            }
        }
        error("Too many image redirects")
    }
}

