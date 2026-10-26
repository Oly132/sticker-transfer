package com.example.stickerpackbuilder.sync

import com.example.stickerpackbuilder.OriginPolicy
import java.io.File
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties

class CollectedStickerLedger(private val file: File) {
    private val entries = Properties().apply { if (file.exists()) file.inputStream().use { load(it) } }
    fun contains(url: String) = entries.containsKey(assetKey(url))
    fun record(url: String) {
        recordBatch(listOf(url))
    }
    fun recordBatch(urls: List<String>) {
        if (urls.isEmpty()) return
        val time = System.currentTimeMillis().toString()
        urls.forEach { entries.setProperty(assetKey(it), time) }
        save()
    }
    fun forgetKeys(keys: Set<String>) {
        keys.forEach { entries.remove(it) }
        save()
    }
    private fun save() {
        file.parentFile!!.mkdirs()
        val temporary = File(file.parentFile, "${file.name}.tmp")
        temporary.outputStream().use { entries.store(it, "Collected sticker identities") }
        Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
    companion object {
        fun assetKey(url: String): String {
            require(OriginPolicy.isStickerUrl(url)) { "Invalid original sticker address" }
            val uri = URI(url)
            val path = uri.rawPath.substringBefore('~')
            val identifying = uri.rawQuery.orEmpty().split('&').filter {
                it.substringBefore('=').lowercase() in setOf("sticker_id", "file_id", "image_id", "uri", "id")
            }.sorted().joinToString("&")
            val bytes = MessageDigest.getInstance("SHA-256").digest("$path?$identifying".toByteArray())
            val hex = "0123456789abcdef"
            return buildString(64) { bytes.forEach { byte ->
                val value = byte.toInt() and 255
                append(hex[value ushr 4]); append(hex[value and 15])
            } }
        }
    }
}
