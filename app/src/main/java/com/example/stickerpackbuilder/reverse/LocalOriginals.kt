package com.example.stickerpackbuilder.reverse

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract as Documents
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

/** Read-only SAF recovery. A filename/thumbnail is never enough: original SHA-256 must match. */
object LocalOriginals {
    fun remember(context: Context, tree: Uri) {
        require(tree.scheme == "content" && Documents.isTreeUri(tree)) { "Choose the WhatsApp Stickers folder." }
        context.contentResolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.getSharedPreferences("reverse", 0).edit().putString("originalsFolder", tree.toString()).apply()
    }
    /** Reuse consent already granted, including folders connected by earlier versions. */
    fun availableTrees(context: Context): List<Uri> {
        val configured = context.getSharedPreferences("reverse", 0).getString("originalsFolder", "")
        return context.contentResolver.persistedUriPermissions.filter { it.isReadPermission }.map { it.uri }
            .filter { uri -> Documents.isTreeUri(uri) && (uri.toString() == configured ||
                runCatching { Documents.getTreeDocumentId(uri).contains("whatsapp", true) }.getOrDefault(false)) }
            .distinct().sortedBy { if (it.toString() == configured) 0 else 1 }
    }
    fun recoverAvailable(context: Context, wanted: Set<String>, repo: ReverseRepository): Int {
        val remaining = wanted.filterNot { repo.verified(it) }.toMutableSet(); var total = 0
        for (tree in availableTrees(context)) {
            if (remaining.isEmpty()) break
            try { total += recover(context, tree, remaining, repo) }
            catch (_: SecurityException) { continue } // A revoked folder is offered again by the setup flow.
            remaining.removeAll { repo.verified(it) }
        }
        return total
    }
    fun recover(context: Context, tree: Uri, wanted: Set<String>, repo: ReverseRepository): Int {
        val resolver = context.contentResolver
        val index = File(context.filesDir, "reverse/local-index.json")
        val cache = runCatching { JSONObject(index.readText()) }.getOrDefault(JSONObject())
        val remaining = wanted.toMutableSet(); var found = 0; var scanned = 0
        val started = android.os.SystemClock.elapsedRealtime()
        fun scan(parent: String, depth: Int) {
            if (depth > 5 || remaining.isEmpty() || scanned >= 5000) return
            val children = Documents.buildChildDocumentsUriUsingTree(tree, parent)
            resolver.query(children, arrayOf(Documents.Document.COLUMN_DOCUMENT_ID, Documents.Document.COLUMN_DISPLAY_NAME,
                Documents.Document.COLUMN_MIME_TYPE, Documents.Document.COLUMN_SIZE, Documents.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { rows ->
                while (rows.moveToNext() && remaining.isNotEmpty() && scanned < 5000) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    check(android.os.SystemClock.elapsedRealtime() - started < 120_000) { "Folder scan paused after two minutes. Choose the WhatsApp Stickers folder to narrow the search." }
                    val id = rows.getString(0); val name = rows.getString(1).orEmpty(); val type = rows.getString(2)
                    if (type == Documents.Document.MIME_TYPE_DIR) { scan(id, depth + 1); continue }
                    val reportedSize = if(rows.isNull(3))null else rows.getLong(3)
                    if (!name.endsWith(".webp", true) || reportedSize != null && reportedSize !in 12..8L*1024*1024) continue
                    scanned++
                    val uri = Documents.buildDocumentUriUsingTree(tree, id)
                    val stamp = "${rows.getLong(3)}:${rows.getLong(4)}"
                    val key = uri.toString(); val old = cache.optJSONObject(key)
                    val hash = if (rows.getLong(4) > 0 && old?.optString("stamp") == stamp) old.optString("hash") else {
                        val digest = MessageDigest.getInstance("SHA-256")
                        resolver.openInputStream(uri)!!.use { input ->
                            val bytes = ByteArray(65536); var total = 0
                            while (true) { if(Thread.currentThread().isInterrupted)throw InterruptedException();val n = input.read(bytes); if (n < 0) break; total += n; check(total <= 8*1024*1024); digest.update(bytes, 0, n) }
                        }
                        digest.digest().joinToString("") { "%02x".format(it) }.also { cache.put(key, JSONObject().put("stamp", stamp).put("hash", it)) }
                    }
                    if (hash in remaining) {
                        val target = repo.original(hash); target.parentFile!!.mkdirs(); val temp = File(target.parentFile, "$hash.local.tmp")
                        try {
                            resolver.openInputStream(uri)!!.use { input -> temp.outputStream().use { output ->
                                val bytes=ByteArray(65536);var total=0
                                while(true){val n=input.read(bytes);if(n<0)break;total+=n;check(total<=8*1024*1024);output.write(bytes,0,n)}
                            } }
                            if (ReverseRepository.verified(temp, hash)) {
                                java.nio.file.Files.move(temp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
                                remaining.remove(hash); found++
                            }
                        } finally { temp.delete() }
                    }
                }
            }
        }
        try { scan(Documents.getTreeDocumentId(tree), 0) } finally { index.writeText(cache.toString()) }
        return found
    }
}
