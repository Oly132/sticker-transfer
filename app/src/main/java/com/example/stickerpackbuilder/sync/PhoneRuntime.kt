package com.example.stickerpackbuilder.sync

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.delay
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.ZipInputStream

object PhoneRuntime {
    private val started = AtomicBoolean(false)
    private val client = OkHttpClient.Builder().callTimeout(45, java.util.concurrent.TimeUnit.SECONDS).build()
    private external fun launch(script: String, temp: String): Int
    fun start(context: Context, onExit: () -> Unit = {}) {
        if (!started.compareAndSet(false, true)) return
        try {
            val root = File(context.filesDir, "phone-runtime").apply { mkdirs() }
            val revision = File(root, "revision")
            if (!revision.exists() || revision.readText() != "15") {
                ZipInputStream(context.assets.open("phone-runtime.zip")).use { zip ->
                    var entry = zip.nextEntry
                    while (entry != null) {
                        val file = File(root, entry.name)
                        require(file.canonicalPath.startsWith(root.canonicalPath + File.separator))
                        if (entry.isDirectory) file.mkdirs() else {
                            file.parentFile!!.mkdirs(); file.outputStream().use { zip.copyTo(it) }
                        }
                        entry = zip.nextEntry
                    }
                }
                revision.writeText("15")
            }
            val config = File(root, "rpc-config.json")
            if (!config.exists()) config.writeText(JSONObject().put("token", UUID.randomUUID().toString() + UUID.randomUUID()).toString())
            val temp = File(context.cacheDir, "node-temp").apply { mkdirs() }
            System.loadLibrary("node")
            System.loadLibrary("sticker_runtime")
            Thread({ try { launch(File(root, "server.mjs").absolutePath, temp.absolutePath) } finally { onExit() } }, "phone-connector").start()
        } catch (e: Throwable) { started.set(false); throw e }
    }
    fun request(context: Context, path: String, body: JSONObject? = null): JSONObject {
        val token = JSONObject(File(context.filesDir, "phone-runtime/rpc-config.json").readText()).getString("token")
        val builder = Request.Builder().url("http://127.0.0.1:8769$path").header("Authorization", "Bearer $token")
        if (body != null) builder.post(body.toString().toRequestBody("application/json".toMediaType()))
        val timeout = if (path == "/status" || path == "/reconnect") 2L else 45L
        val call = client.newCall(builder.build())
        call.timeout().timeout(timeout, java.util.concurrent.TimeUnit.SECONDS)
        return call.execute().use {
            val result = JSONObject(it.body?.string() ?: "{}")
            require(it.isSuccessful) { result.optString("error", "Connection failed") }
            result
        }
    }
    suspend fun restart(context: Context) {
        context.stopService(Intent(context, PhoneConnectorService::class.java))
        delay(800)
        context.startForegroundService(Intent(context, PhoneConnectorService::class.java))
    }
}
