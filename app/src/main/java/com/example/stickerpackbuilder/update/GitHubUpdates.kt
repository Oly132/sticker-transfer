package com.example.stickerpackbuilder.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.example.stickerpackbuilder.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AppUpdate(val tag: String, val name: String, val url: String, val bytes: Long, val sha256: String?) {
    fun json() = JSONObject().put("tag", tag).put("name", name).put("url", url).put("bytes", bytes).put("sha256", sha256 ?: "").toString()
}

/** Only public release data is fetched. No GitHub token or account/session data is sent. */
object GitHubUpdates {
    const val REPOSITORY = "Oly132/sticker-transfer"
    const val API = "https://api.github.com/repos/$REPOSITORY/releases/latest"
    const val MAX_APK_BYTES = 250L * 1024 * 1024
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build()
    private val downloadLock = Any()

    private fun version(value: String): List<Int>? {
        val match = Regex("^v?(\\d{1,5})\\.(\\d{1,5})(?:\\.(\\d{1,5}))?(?:-samsung)?$").matchEntire(value) ?: return null
        return (1..3).map { match.groupValues[it].ifBlank { "0" }.toInt() }
    }
    fun newer(candidate: String, installed: String): Boolean {
        val a = version(candidate) ?: return false
        val b = version(installed) ?: return false
        for (i in a.indices) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }
    fun trustedAsset(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        val segments = parsed.pathSegments
        return parsed.scheme == "https" && parsed.host == "github.com" && parsed.port == 443 &&
            parsed.username.isEmpty() && parsed.password.isEmpty() && segments.size == 6 &&
            segments.take(4) == listOf("Oly132", "sticker-transfer", "releases", "download") &&
            segments.drop(4).none { it.isBlank() || it == "." || it == ".." || '/' in it || '\\' in it } &&
            segments.last().endsWith(".apk", true)
    }
    private fun trustedDownload(url: String): Boolean {
        if (trustedAsset(url)) return true
        val u = url.toHttpUrlOrNull() ?: return false
        return u.scheme == "https" && u.port == 443 && u.username.isEmpty() && u.password.isEmpty() &&
            u.host in setOf("release-assets.githubusercontent.com", "objects.githubusercontent.com")
    }
    fun parse(release: JSONObject, installed: String): AppUpdate? {
        if (release.optBoolean("draft") || release.optBoolean("prerelease")) return null
        val tag = release.optString("tag_name")
        if (!newer(tag, installed)) return null
        val assets = release.optJSONArray("assets") ?: return null
        val apks = (0 until assets.length()).map { assets.getJSONObject(it) }.filter {
            it.optString("name").endsWith(".apk", true) && it.optString("state", "uploaded") == "uploaded"
        }
        val apk = apks.singleOrNull { it.optString("name") == "StickerTransfer.apk" } ?: apks.singleOrNull() ?: return null
        val url = apk.optString("browser_download_url")
        val bytes = apk.optLong("size")
        require(trustedAsset(url) && bytes in 1..MAX_APK_BYTES) { "This release has an invalid app download." }
        val digest = apk.optString("digest").takeUnless { it.isBlank() || it == "null" }
        val hash = digest?.removePrefix("sha256:")?.lowercase()
        require(hash == null || digest!!.startsWith("sha256:") && hash.matches(Regex("[a-f0-9]{64}"))) { "This release has an invalid download checksum." }
        return AppUpdate(tag, apk.optString("name"), url, bytes, hash)
    }
    fun restore(value: String): AppUpdate {
        require(value.length < 8192)
        val j = JSONObject(value)
        val info = AppUpdate(j.getString("tag"), j.getString("name"), j.getString("url"), j.getLong("bytes"), j.optString("sha256").takeIf { it.isNotEmpty() })
        require(version(info.tag) != null && trustedAsset(info.url) && info.bytes in 1..MAX_APK_BYTES)
        require(info.sha256 == null || info.sha256.matches(Regex("[a-f0-9]{64}")))
        return info
    }
    fun check(context: Context, force: Boolean = false): AppUpdate? {
        val prefs = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong("checkedAt", 0) in 0 until 15 * 60_000) {
            return prefs.getString("release", null)?.let { parse(JSONObject(it), BuildConfig.VERSION_NAME) }
        }
        if (!force && now - prefs.getLong("failedAt", 0) in 0 until 2 * 60_000) return null
        try {
            val request = Request.Builder().url(API).header("Accept", "application/vnd.github+json")
                .header("User-Agent", "StickerTransfer/${BuildConfig.VERSION_NAME}").build()
            client.newCall(request).apply { timeout().timeout(12, TimeUnit.SECONDS) }.execute().use { response ->
                if (response.code == 404) return null
                check(response.isSuccessful) { "Couldn't check GitHub right now. Try again later." }
                val body = response.body ?: error("GitHub returned an empty response.")
                val raw = body.byteStream().use { input ->
                    val output = java.io.ByteArrayOutputStream()
                    val buffer = ByteArray(8192)
                    while (output.size() <= 1024 * 1024) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
                require(raw.size <= 1024 * 1024) { "GitHub returned too much release data." }
                val json = raw.toString(Charsets.UTF_8)
                val update = parse(JSONObject(json), BuildConfig.VERSION_NAME)
                prefs.edit().putLong("checkedAt", now).putString("release", json).remove("failedAt").apply()
                return update
            }
        } catch (e: Exception) { prefs.edit().putLong("failedAt", now).apply(); throw e }
    }
    fun download(context: Context, update: AppUpdate, keepGoing: () -> Unit, progress: (Int) -> Unit): File = synchronized(downloadLock) {
        require(trustedAsset(update.url) && update.bytes in 1..MAX_APK_BYTES)
        val folder = File(context.cacheDir, "updates").apply { mkdirs() }
        val part = File(folder, "download.part.apk")
        val apk = File(folder, "update.apk")
        var url = update.url
        try {
            repeat(6) {
                keepGoing()
                require(trustedDownload(url)) { "The update redirected outside GitHub." }
                client.newCall(Request.Builder().url(url).header("User-Agent", "StickerTransfer/${BuildConfig.VERSION_NAME}").build())
                    .apply { timeout().timeout(5, TimeUnit.MINUTES) }.execute().use { response ->
                    if (response.code in listOf(301, 302, 303, 307, 308)) {
                        url = response.request.url.resolve(response.header("Location") ?: error("Missing GitHub download redirect."))?.toString()
                            ?: error("Invalid GitHub download redirect.")
                        return@use
                    }
                    check(response.isSuccessful) { "The update couldn't download. Check your connection and retry." }
                    val body = response.body ?: error("Empty update download.")
                    require(body.contentLength() <= MAX_APK_BYTES) { "This update is too large." }
                    val hash = MessageDigest.getInstance("SHA-256")
                    var total = 0L
                    var lastProgress = -1
                    body.byteStream().use { input -> part.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            keepGoing()
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= update.bytes && total <= MAX_APK_BYTES) { "The update size didn't match its release." }
                            hash.update(buffer, 0, count); output.write(buffer, 0, count)
                            val percent = (total * 100 / update.bytes).toInt()
                            if (percent != lastProgress) { progress(percent); lastProgress = percent }
                        }
                    } }
                    require(total == update.bytes) { "The update download was interrupted. Please retry." }
                    val actual = hash.digest().joinToString("") { "%02x".format(it) }
                    require(update.sha256 == null || actual == update.sha256) { "The update checksum didn't match. Please retry." }
                    verifyApk(context, part)
                    java.nio.file.Files.move(part.toPath(), apk.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    return apk
                }
            }
            throw IOException("Too many GitHub download redirects.")
        } catch (e: Exception) { part.delete(); throw e }
    }
    fun verifyIdentity(packageName: String, installedPackage: String, version: Long, installedVersion: Long,
                       signatures: Set<String>, installedSignatures: Set<String>) {
        require(packageName == installedPackage) { "This APK belongs to a different app." }
        require(version > installedVersion) { "This APK isn't newer than your installed app." }
        require(signatures.isNotEmpty() && signatures == installedSignatures) { "This APK wasn't signed with Sticker Transfer's installed signing key." }
    }
    @Suppress("DEPRECATION")
    private fun verifyApk(context: Context, file: File) {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = pm.getPackageArchiveInfo(file.absolutePath, flags) ?: error("The downloaded file isn't a valid Android app.")
        val current = pm.getPackageInfo(context.packageName, flags)
        fun signatures(info: android.content.pm.PackageInfo) =
            (if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures).orEmpty()
                .map { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { byte -> "%02x".format(byte) } }.toSet()
        verifyIdentity(candidate.packageName, context.packageName,
            if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong(),
            if (Build.VERSION.SDK_INT >= 28) current.longVersionCode else current.versionCode.toLong(), signatures(candidate), signatures(current))
        require(candidate.applicationInfo?.minSdkVersion?.let { it <= Build.VERSION.SDK_INT } == true) { "This update needs a newer Android version." }
    }
}
