package com.example.stickerpackbuilder.reverse

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.MediaStore
import com.example.stickerpackbuilder.StickerConverter
import com.bumptech.glide.gifencoder.AnimatedGifEncoder
import java.io.File
import java.util.UUID
import java.security.MessageDigest

object StickerStager {
    private fun digest(input:java.io.InputStream):String = input.use {
        val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024);var total=0L
        while(true){val count=it.read(buffer);if(count<0)break;total+=count;require(total<=20L*1024*1024){"Staged file is too large"};hash.update(buffer,0,count)}
        hash.digest().joinToString(""){byte->"%02x".format(byte)}
    }
    data class Staged(val album:String,val uri:Uri,val hash:String,val sourceId:String,val animated:Boolean,val frames:Int,val readyAt:Long)
    fun current(context:Context):Staged {
        val prefs=context.getSharedPreferences("reverse",0)
        return Staged(prefs.getString("album","").orEmpty(),Uri.parse(prefs.getString("stagedUri","").orEmpty()),
            prefs.getString("stagedHash","").orEmpty(),prefs.getString("sourceId","").orEmpty(),prefs.getBoolean("animated",false),
            prefs.getInt("frames",1),prefs.getLong("stagedAt",0))
    }
    fun verifyCurrent(context:Context):String = verify(context,current(context))
    fun verify(context:Context,staged:Staged):String {
        val album=staged.album
        require(album.matches(Regex("RSL_[a-f0-9]{12}"))) {"Stage a sticker first"}
        val expected=staged.hash
        require(expected.matches(Regex("[a-f0-9]{64}"))){"This old staged file needs to be staged again"}
        val uri=staged.uri
        require(uri.scheme=="content"&&uri.authority=="media"){"Invalid staged image"}
        context.contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.RELATIVE_PATH} = ?",arrayOf("Pictures/$album/"),null)!!.use { rows ->
            require(rows.count==1&&rows.moveToFirst()&&rows.getLong(0).toString()==uri.lastPathSegment){"The transfer album must contain exactly its one staged sticker"}
            require(rows.getString(1) in listOf("reverse.png","reverse.gif")){"Unexpected staged file"}
        }
        require(digest(context.contentResolver.openInputStream(uri)!!)==expected){"The staged image changed. Nothing was imported."}
        return expected
    }
    data class Prepared(val file: File, val animated: Boolean, val frameCount: Int)
    fun prepare(source: File, folder: File): Prepared {
        val id=ReverseRepository.hash(source)
        val cached=File(folder,"$id-v1").apply{mkdirs()}
        val metadata=File(cached,"prepared.json")
        if(metadata.exists())runCatching {
            val json=org.json.JSONObject(metadata.readText())
            val animated=json.getBoolean("animated")
            val file=File(cached,if(animated)"preview.gif" else "preview.png")
            if(file.length()>0&&ReverseRepository.hash(file)==json.getString("hash"))return Prepared(file,animated,json.getInt("frames"))
        }
        val result=prepareUncached(source,cached)
        metadata.writeText(org.json.JSONObject().put("animated",result.animated).put("frames",result.frameCount).put("hash",ReverseRepository.hash(result.file)).toString())
        return result
    }
    private fun prepareUncached(source: File, folder: File): Prepared {
        require(source.length() in 1..8L*1024*1024) { "Choose an original under 8 MB" }
        folder.mkdirs()
        val frames = StickerConverter.openFrames(source)
        if (frames != null && frames.count > 1) frames.use {
            require(ReverseConversionLimits.animationAllowed(it.count,it.durations)) { "Animation exceeds the 300-frame / 60-second import limit" }
            val out = File(folder, "preview.gif")
            try {
                out.outputStream().buffered().use { stream ->
                    val encoder = AnimatedGifEncoder().apply { setRepeat(0); setQuality(10); setDispose(2) }
                    check(encoder.start(stream))
                    repeat(it.count) { index ->
                        if(Thread.currentThread().isInterrupted)throw InterruptedException()
                        val bitmap = it.next()
                        try { encoder.setDelay(it.durations[index]); check(encoder.addFrame(bitmap)) }
                        finally { bitmap.recycle() }
                        require(out.length() <= 20L*1024*1024) { "Converted GIF exceeds the 20 MB import limit" }
                    }
                    check(encoder.finish())
                }
                return Prepared(out, true, it.count)
            } catch (error: Throwable) { out.delete(); throw error }
        }
        frames?.close()
        val dimensions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(source.absolutePath, dimensions)
        require(dimensions.outWidth in 1..2048 && dimensions.outHeight in 1..2048) { "Invalid or oversized sticker image" }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath) ?: error("This sticker could not be decoded")
        try {
            require(bitmap.width <= 2048 && bitmap.height <= 2048) { "Image is too large for this experiment" }
            val out = File(folder, "preview.png")
            out.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
            return Prepared(out, false, 1)
        } finally { bitmap.recycle() }
    }
    fun stage(context: Context, prepared: Prepared, sourceId: String): Uri = stageVerified(context,prepared,sourceId).uri
    fun stageVerified(context: Context, prepared: Prepared, sourceId: String, retainPrevious:Boolean=false): Staged {
        require(android.os.Build.VERSION.SDK_INT >= 29) { "Gallery staging requires Android 10 or newer" }
        val prefs = context.getSharedPreferences("reverse", 0)
        if(!retainPrevious)prefs.getString("stagedUri", null)?.let { old -> runCatching { context.contentResolver.delete(Uri.parse(old), null, null) } }
        val album = "RSL_" + UUID.randomUUID().toString().replace("-", "").take(12)
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "reverse.${if(prepared.animated) "gif" else "png"}")
            put(MediaStore.Images.Media.MIME_TYPE, if(prepared.animated) "image/gif" else "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/$album/")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("Couldn't stage sticker")
        try {
            val expectedHash=digest(prepared.file.inputStream())
            context.contentResolver.openOutputStream(uri)!!.use { out -> prepared.file.inputStream().use { it.copyTo(out) } }
            require(digest(context.contentResolver.openInputStream(uri)!!)==expectedHash){"The staged file did not match the converted sticker"}
            context.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING,0) }, null, null)
            val readyAt=android.os.SystemClock.uptimeMillis()
            prefs.edit().putLong("stagedAt",readyAt).putString("stagedUri",uri.toString()).putString("stagedHash",expectedHash).putString("album",album).putString("sourceId",sourceId)
                .putBoolean("animated",prepared.animated).putInt("frames",prepared.frameCount).putString("proof","staged; not yet verified in TikTok").apply()
            return Staged(album,uri,expectedHash,sourceId,prepared.animated,prepared.frameCount,readyAt)
        } catch (error: Throwable) { context.contentResolver.delete(uri,null,null);throw error }
    }
    /** Recover temporary albums after process death. Only this app's own generated
     * rows qualify; submitted sources from any account stay protected. Call before
     * starting a batch, never while another batch is publishing its lookahead.
     */
    fun cleanupUnused(context:Context):Int {
        if(android.os.Build.VERSION.SDK_INT<29)return 0
        val protected=runCatching{ReverseHistory.protectedStagedUris(context).map{
            val uri=Uri.parse(it)
            require(uri.scheme=="content"&&uri.authority=="media"&&uri.lastPathSegment?.toLongOrNull()?.let{id->id>0}==true)
            uri.lastPathSegment!!
        }.toSet()}.getOrElse{return 0}
        val resolver=context.contentResolver
        val candidates=runCatching{
            val result=ArrayList<Uri>()
            resolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID,MediaStore.Images.Media.RELATIVE_PATH,MediaStore.Images.Media.DISPLAY_NAME,MediaStore.MediaColumns.OWNER_PACKAGE_NAME),
                "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.OWNER_PACKAGE_NAME} = ?",
                arrayOf("Pictures/RSL_%",context.packageName),null)?.use{rows->
                while(rows.moveToNext()){
                    val id=rows.getLong(0)
                    if(id.toString() !in protected&&rows.getString(1).orEmpty().matches(Regex("Pictures/RSL_[a-f0-9]{12}/"))&&
                        rows.getString(2) in listOf("reverse.png","reverse.gif")&&rows.getString(3)==context.packageName)
                        result.add(android.content.ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,id))
                }
            }
            result
        }.getOrElse{return 0}
        var count=0
        for(uri in candidates)runCatching{
            if(resolver.delete(uri,null,null)>0){
                count++
                val prefs=context.getSharedPreferences("reverse",0)
                if(prefs.getString("stagedUri","")==uri.toString())prefs.edit().remove("stagedUri").remove("stagedHash").remove("album").apply()
            }
        }
        return count
    }
    fun discard(context:Context,staged:Staged) {
        require(staged.album.matches(Regex("RSL_[a-f0-9]{12}"))&&staged.uri.scheme=="content"&&staged.uri.authority=="media")
        context.contentResolver.delete(staged.uri,null,null)
        val prefs=context.getSharedPreferences("reverse",0)
        if(prefs.getString("stagedUri","")==staged.uri.toString())prefs.edit().remove("stagedUri").remove("stagedHash").remove("album").apply()
    }
}
