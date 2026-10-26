package com.example.stickerpackbuilder

import android.content.Context
import android.util.AtomicFile
import com.example.stickerpackbuilder.models.Sticker
import com.example.stickerpackbuilder.models.StickerPack
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.UUID

object PackRepository {
    data class Pending(val fileName: String, val animated: Boolean, val hash: String)
    private data class Library(val packs: MutableList<StickerPack>, val pending: MutableList<Pending>)
    enum class ImportResult { ADDED, DUPLICATE }
    private fun id() = UUID.randomUUID().toString()
    fun incomingDir(context: Context) = File(context.filesDir, "incoming").apply { mkdirs() }
    fun pendingDir(context: Context) = File(context.filesDir, "pending").apply { mkdirs() }
    fun stickersRootDir(context: Context) = File(context.filesDir, "stickers").apply { mkdirs() }
    fun packDir(context: Context, packId: String): File {
        require(packId.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid pack identifier" }
        return File(stickersRootDir(context), packId)
    }
    private fun manifest(context: Context) = AtomicFile(File(context.filesDir, "library.json"))
    private fun read(context: Context): Library {
        val f = manifest(context)
        if (!f.baseFile.exists() && !File(f.baseFile.path + ".bak").exists()) {
            val old = File(context.filesDir, "packs.json")
            val packs = if (old.exists()) {
                val arr = JSONArray(old.readText())
                (0 until arr.length()).map { StickerPack.fromJson(arr.getJSONObject(it)) }.toMutableList()
            } else mutableListOf()
            val pending = pendingDir(context).listFiles()?.filter { it.extension == "webp" }?.map {
                Pending(it.name, WebpContainer.parse(it.readBytes()).frames.size > 1, sha256(it))
            }?.toMutableList() ?: mutableListOf()
            return Library(packs, pending).also { save(context, it) }
        }
        try {
            val obj = f.openRead().use { JSONObject(it.bufferedReader().readText()) }
            val packs = obj.getJSONArray("packs")
            val pending = obj.getJSONArray("pending")
            return Library((0 until packs.length()).map {
                StickerPack.fromJson(packs.getJSONObject(it))
            }.toMutableList(), (0 until pending.length()).map {
                val p = pending.getJSONObject(it)
                Pending(p.getString("fileName"), p.getBoolean("animated"), p.getString("hash"))
            }.toMutableList())
        } catch (e: Exception) {
            throw IllegalStateException("Couldn't read your library. Your files have been kept; restart the app.", e)
        }
    }
    private fun save(context: Context, library: Library) {
        val bytes = JSONObject().put("schema", 2)
            .put("packs", JSONArray().apply { library.packs.forEach { put(it.toJson()) } })
            .put("pending", JSONArray().apply {
                library.pending.forEach { put(JSONObject().put("fileName", it.fileName)
                    .put("animated", it.animated).put("hash", it.hash)) }
            }).toString().toByteArray()
        val f = manifest(context)
        val stream = f.startWrite()
        try { stream.write(bytes); f.finishWrite(stream) }
        catch (e: Exception) { f.failWrite(stream); throw e }
    }
    @Synchronized fun loadPacks(context: Context): MutableList<StickerPack> = read(context).packs
    @Synchronized fun pending(context: Context): List<Pending> = read(context).pending
    fun listPendingFiles(context: Context) = pending(context).map { it.fileName }
    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buffer = ByteArray(8192)
            while (true) { val n = stream.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    @Synchronized fun containsHash(context: Context, hash: String): Boolean {
        val l = read(context)
        return l.pending.any { it.hash == hash } || l.packs.any { p -> p.stickers.any { it.hash == hash } }
    }
    @Synchronized fun accept(context: Context, converted: File, animated: Boolean, hash: String,
        automatic: Boolean): ImportResult {
        val l = read(context)
        if (l.pending.any { it.hash == hash } || l.packs.any { p -> p.stickers.any { it.hash == hash } })
            return ImportResult.DUPLICATE
        StickerConverter.validate(converted, animated)
        if (!automatic) {
            require(l.pending.size < 300) { "Review or delete waiting stickers before importing more (300 maximum)" }
            val name = "pending_" + id() + ".webp"
            val dest = File(pendingDir(context), name)
            converted.copyTo(dest)
            l.pending.add(Pending(name, animated, hash))
            try { save(context, l) } catch (e: Exception) { dest.delete(); throw e }
        } else {
            var pack = l.packs.lastOrNull {
                it.identifier.startsWith("auto_") && !it.sealed && it.animated == animated && it.stickers.size < 30
            }
            if (pack == null) {
                require(l.packs.size < 10) { "You have 10 packs. Delete a pack or use review mode." }
                pack = StickerPack("auto_" + id(), if (animated) "Animated stickers" else "TikTok stickers",
                    animated = animated)
                l.packs.add(pack)
            }
            val dir = packDir(context, pack.identifier).apply { mkdirs() }
            val name = "sticker_" + id() + ".webp"
            val dest = File(dir, name)
            converted.copyTo(dest)
            var newIcon: File? = null
            try {
                if (pack.trayIconFileName.isBlank()) {
                    newIcon = File(dir, "tray_" + id() + ".webp")
                    StickerConverter.buildTrayIcon(dest, newIcon)
                    pack.trayIconFileName = newIcon.name
                }
                pack.stickers.add(Sticker(name, hash = hash))
                pack.version++
                if (pack.stickers.size == 30) pack.sealed = true
                save(context, l)
            } catch (e: Exception) { dest.delete(); newIcon?.delete(); throw e }
        }
        return ImportResult.ADDED
    }
    @Synchronized fun createPackFromPending(context: Context, selectedFileNames: List<String>,
        packName: String): StickerPack {
        require(selectedFileNames.distinct().size == selectedFileNames.size)
        val l = read(context)
        val chosen = selectedFileNames.map { name ->
            l.pending.firstOrNull { it.fileName == name } ?: error("A selected sticker is no longer available")
        }
        require(chosen.size in 3..30) { "Choose between 3 and 30 stickers" }
        require(chosen.map { it.animated }.distinct().size == 1) { "Choose only still stickers or only animated stickers" }
        val pack = StickerPack("pack_" + id(), cleanName(packName), animated = chosen.first().animated, sealed = true)
        require(l.packs.size < 10) { "You have 10 packs. Delete a pack before making another." }
        val dest = packDir(context, pack.identifier).apply { mkdirs() }
        try {
            chosen.forEach { pending ->
                val source = safeAsset(pendingDir(context), pending.fileName)
                StickerConverter.validate(source, pending.animated)
                val name = "sticker_" + id() + ".webp"
                source.copyTo(File(dest, name))
                pack.stickers.add(Sticker(name, hash = pending.hash))
            }
            val icon = File(dest, "tray.webp")
            StickerConverter.buildTrayIcon(File(dest, pack.stickers.first().fileName), icon)
            pack.trayIconFileName = icon.name
            l.packs.add(pack)
            l.pending.removeAll(chosen.toSet())
            save(context, l)
        } catch (e: Exception) { dest.deleteRecursively(); throw e }
        chosen.forEach { File(pendingDir(context), it.fileName).delete() }
        return pack
    }
    @Synchronized fun appendPending(context: Context, identifier: String, names: List<String>) {
        require(names.isNotEmpty() && names.distinct().size == names.size)
        val l = read(context)
        val pack = l.packs.firstOrNull { it.identifier == identifier } ?: error("Pack no longer exists")
        val chosen = names.map { name ->
            l.pending.firstOrNull { it.fileName == name } ?: error("A selected sticker is no longer available")
        }
        require(pack.stickers.size + chosen.size in 3..30) { "The completed pack must contain 3–30 stickers" }
        require(chosen.all { it.animated == pack.animated }) { "Choose the same sticker type as this pack" }
        val dir = packDir(context, identifier).apply { mkdirs() }
        val copied = mutableListOf<File>()
        var newIcon: File? = null
        try {
            chosen.forEach {
                val source = safeAsset(pendingDir(context), it.fileName)
                StickerConverter.validate(source, pack.animated)
                val dest = File(dir, "sticker_" + id() + ".webp")
                source.copyTo(dest); copied.add(dest)
                pack.stickers.add(Sticker(dest.name, hash = it.hash))
            }
            if (pack.trayIconFileName.isBlank()) {
                newIcon = File(dir, "tray_" + id() + ".webp")
                StickerConverter.buildTrayIcon(copied.first(), newIcon)
                pack.trayIconFileName = newIcon.name
            }
            pack.sealed = true; pack.version++
            l.pending.removeAll(chosen.toSet())
            save(context, l)
        } catch (e: Exception) { copied.forEach { it.delete() }; newIcon?.delete(); throw e }
        chosen.forEach { safeAsset(pendingDir(context), it.fileName).delete() }
    }
    private fun cleanName(name: String): String {
        val cleaned = name.trim().ifBlank { "My stickers" }
        require(cleaned.length <= 128) { "Pack names can contain up to 128 characters" }
        return cleaned
    }
    @Synchronized fun rename(context: Context, identifier: String, name: String) {
        val l = read(context)
        val pack = l.packs.firstOrNull { it.identifier == identifier } ?: error("Pack no longer exists")
        pack.name = cleanName(name); pack.version++
        save(context, l)
    }
    @Synchronized fun sealForExport(context: Context, identifier: String): StickerPack {
        val l = read(context)
        val pack = l.packs.firstOrNull { it.identifier == identifier } ?: error("Pack no longer exists")
        validatePack(context, pack)
        pack.sealed = true
        save(context, l)
        return pack
    }
    fun validatePack(context: Context, pack: StickerPack) {
        require(pack.isComplete()) { "This pack needs at least 3 stickers" }
        val dir = packDir(context, pack.identifier)
        StickerConverter.validateIcon(safeAsset(dir, pack.trayIconFileName))
        pack.stickers.forEach { StickerConverter.validate(safeAsset(dir, it.fileName), pack.animated) }
    }
    fun safeAsset(dir: File, name: String): File {
        require(name.matches(Regex("[A-Za-z0-9_-]+\\.webp"))) { "Invalid sticker filename" }
        val file = File(dir, name).canonicalFile
        require(file.parentFile == dir.canonicalFile) { "Invalid sticker path" }
        return file
    }
    @Synchronized fun removeSticker(context: Context, identifier: String, name: String) {
        val l = read(context)
        val pack = l.packs.firstOrNull { it.identifier == identifier } ?: error("Pack no longer exists")
        val removed = pack.stickers.firstOrNull { it.fileName == name } ?: return
        pack.stickers.remove(removed)
        val dir = packDir(context, identifier)
        val oldIcon = pack.trayIconFileName
        var newIcon: File? = null
        if (pack.stickers.isEmpty()) pack.trayIconFileName = ""
        else {
            newIcon = File(dir, "tray_" + id() + ".webp")
            StickerConverter.buildTrayIcon(safeAsset(dir, pack.stickers.first().fileName), newIcon)
            pack.trayIconFileName = newIcon.name
        }
        pack.sealed = true; pack.version++
        try { save(context, l) } catch (e: Exception) { newIcon?.delete(); throw e }
        safeAsset(dir, name).delete()
        if (oldIcon.isNotBlank()) safeAsset(dir, oldIcon).delete()
    }
    @Synchronized fun deletePack(context: Context, identifier: String) {
        val l = read(context)
        l.packs.removeAll { it.identifier == identifier }
        save(context, l)
        packDir(context, identifier).deleteRecursively()
    }
    @Synchronized fun deletePending(context: Context, names: List<String>) {
        val l = read(context)
        val chosen = l.pending.filter { it.fileName in names }
        l.pending.removeAll(chosen.toSet())
        save(context, l)
        chosen.forEach { safeAsset(pendingDir(context), it.fileName).delete() }
    }
    fun isAutomaticMode(context: Context): Boolean {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        if (!prefs.contains("automatic")) {
            val legacy = File(context.filesDir, "settings.json")
            val value = runCatching { JSONObject(legacy.readText()).optBoolean("automatic", false) }.getOrDefault(false)
            prefs.edit().putBoolean("automatic", value).apply()
        }
        return prefs.getBoolean("automatic", false)
    }
    fun setAutomaticMode(context: Context, automatic: Boolean) =
        context.getSharedPreferences("settings", Context.MODE_PRIVATE).edit().putBoolean("automatic", automatic).apply()
}

