package com.example.stickerpackbuilder.sync

import com.example.stickerpackbuilder.OriginPolicy
import okhttp3.*
import java.io.File

object StickerDownload {
    private val cacheLocks = Array(32) { Any() }
    private val trimLock = Any()
    private val cacheWrites = java.util.concurrent.atomic.AtomicInteger()
    private val client = OkHttpClient.Builder().followRedirects(false).dns(object : Dns {
        override fun lookup(hostname: String) = Dns.SYSTEM.lookup(hostname).also { addresses ->
            require(addresses.isNotEmpty() && addresses.all(OriginPolicy::isPublicAddress)) { "Sticker host is unavailable" }
        }
    }).callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
    fun fetch(initial: String, file: File) {
        var url = initial
        repeat(4) {
            require(OriginPolicy.isStickerUrl(url)) { "Unapproved sticker address" }
            client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                if (response.isRedirect) { url = response.request.url.resolve(response.header("Location") ?: error("Invalid redirect"))!!.toString(); return@repeat }
                require(response.isSuccessful) { "Sticker couldn't load. Check your connection and try again." }
                val body = response.body ?: error("Empty sticker")
                require(body.contentLength() <= 8 * 1024 * 1024) { "Sticker is too large" }
                var total = 0L
                body.byteStream().use { input -> file.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) { val n = input.read(buffer); if (n < 0) break; total += n; require(total <= 8 * 1024 * 1024) { "Sticker is too large" }; output.write(buffer, 0, n) }
                } }
                return
            }
        }
        error("Too many redirects")
    }
    fun cached(context: android.content.Context, url: String) = File(File(context.cacheDir, "sticker-choices").apply { mkdirs() }, CollectedStickerLedger.assetKey(url) + ".source")
    fun previewSource(context: android.content.Context, url: String): File {
        val file = cached(context, url)
        return synchronized(cacheLocks[(file.name.hashCode() and Int.MAX_VALUE) % cacheLocks.size]) {
        if (!file.exists()) {
            val temporary = File(file.parentFile, file.name + ".part")
            try { fetch(url, temporary); check(temporary.renameTo(file)) } finally { temporary.delete() }
            if (cacheWrites.incrementAndGet() % 8 == 0) synchronized(trimLock) {
            val files = file.parentFile!!.listFiles().orEmpty()
            var total = files.sumOf { it.length() }
            val oldBefore = System.currentTimeMillis() - 60_000
            files.filter { it != file && it.extension == "source" && it.lastModified() < oldBefore }.sortedBy { it.lastModified() }.forEach {
                if (total > 128L * 1024 * 1024) { val size = it.length(); if (it.delete()) total -= size }
            }
            }
        }
        file.setLastModified(System.currentTimeMillis())
        file
        }
    }
}
