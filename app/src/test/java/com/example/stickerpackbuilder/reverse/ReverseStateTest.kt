package com.example.stickerpackbuilder.reverse

import android.graphics.Bitmap
import android.graphics.Color
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReverseStateTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun clean() {
        File(context.filesDir, "reverse").deleteRecursively()
        context.getSharedPreferences("reverse", 0).edit().clear().putString("destination", "first.account").commit()
    }
    @Test fun interruptedCreationBlocksOnlyThatDestinationUntilExplicitlyCleared() {
        val id = "a".repeat(64)
        ReverseHistory.record(context, JSONObject().put("sourceId", id).put("submitted", true).put("result", "Create requested; confirmation pending"))
        assertTrue(ReverseHistory.submitted(context, id))
        context.getSharedPreferences("reverse", 0).edit().putString("destination", "other.account").commit()
        assertFalse(ReverseHistory.submitted(context, id))
        context.getSharedPreferences("reverse", 0).edit().putString("destination", "first.account").commit()
        assertTrue(ReverseHistory.submitted(context, id))
        ReverseHistory.record(context, JSONObject().put("sourceId", id).put("submitted", false).put("result", "User confirmed not created"))
        assertFalse(ReverseHistory.submitted(context, id))
    }
    @Test fun originalCacheRejectsWrongStickerAndChangedBytes() {
        val repo = ReverseRepository(context)
        val temp = File(context.cacheDir, "verified.webp")
        val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        temp.outputStream().use { @Suppress("DEPRECATION") image.compress(Bitmap.CompressFormat.WEBP,100,it) }; image.recycle()
        val id = ReverseRepository.hash(temp)
        val target = repo.original(id).apply { parentFile!!.mkdirs() }; temp.copyTo(target, true)
        assertTrue(repo.verified(id))
        assertFalse(ReverseRepository.verified(target, "0".repeat(64)))
        target.appendBytes(byteArrayOf(0))
        assertFalse(repo.verified(id))
        assertFalse(ReverseRepository.verified(File(context.cacheDir,"missing-file"),id))
    }
    @Test fun stagingUsesItsOwnSingleFileAlbumAndRejectsTampering() {
        val provider=TestImages(File(context.cacheDir,"test-image-provider")).apply { attachInfo(context,android.content.pm.ProviderInfo().apply { authority="media" }) }
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media",provider)
        val source = File(context.cacheDir,"stage.png")
        val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.RED)}
        source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val prepared=StickerStager.prepare(source,File(context.cacheDir,"stage-prepared"))
        val uri=StickerStager.stage(context,prepared,"b".repeat(64))
        assertEquals(context.getSharedPreferences("reverse",0).getString("stagedHash",null),StickerStager.verifyCurrent(context))
        context.contentResolver.openOutputStream(uri)!!.use{it.write(byteArrayOf(1,2,3))}
        assertThrows(IllegalArgumentException::class.java){StickerStager.verifyCurrent(context)}
    }
    @Test fun stagingAheadNeverChangesOrDeletesTheCurrentUploadsFile() {
        val provider=TestImages(File(context.cacheDir,"pipeline-provider")).apply{attachInfo(context,android.content.pm.ProviderInfo().apply{authority="media"})}
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media",provider)
        fun prepared(name:String,color:Int):StickerStager.Prepared {
            val source=File(context.cacheDir,name)
            val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}
            source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
            return StickerStager.prepare(source,File(context.cacheDir,"pipeline-conversion"))
        }
        val first=StickerStager.stageVerified(context,prepared("one.png",Color.RED),"a".repeat(64),true)
        val second=StickerStager.stageVerified(context,prepared("two.png",Color.GREEN),"b".repeat(64),true)
        assertNotEquals(first.uri,second.uri);assertNotEquals(first.album,second.album)
        assertEquals(second.uri,StickerStager.current(context).uri)
        assertEquals(first.hash,StickerStager.verify(context,first))
        assertEquals(second.hash,StickerStager.verify(context,second))
        StickerStager.discard(context,first)
        assertEquals(second.uri,StickerStager.current(context).uri)
        assertEquals(second.hash,StickerStager.verify(context,second))
    }
    @Test fun orphanCleanupPreservesUncertainUploadsAcrossAccountsAndForeignImages() {
        val provider=TestImages(File(context.cacheDir,"orphan-provider")).apply{attachInfo(context,android.content.pm.ProviderInfo().apply{authority="media"})}
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media",provider)
        val source=File(context.cacheDir,"orphan-source.png")
        val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888).apply{eraseColor(Color.BLUE)}
        source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val prepared=StickerStager.prepare(source,File(context.cacheDir,"orphan-prepared"))
        val pending=StickerStager.stageVerified(context,prepared,"a".repeat(64),true)
        val unused=StickerStager.stageVerified(context,prepared,"b".repeat(64),true)
        val foreign=StickerStager.stageVerified(context,prepared,"c".repeat(64),true)
        val personal=StickerStager.stageVerified(context,prepared,"d".repeat(64),true)
        provider.change(foreign.uri,"owner_package_name","other.app")
        provider.change(personal.uri,"_display_name","holiday.png")
        ReverseHistory.record(context,JSONObject().put("sourceId",pending.sourceId).put("submitted",true).put("stagedUri",pending.uri.toString()))
        context.getSharedPreferences("reverse",0).edit().putString("destination","different.account").commit()
        assertEquals(1,StickerStager.cleanupUnused(context))
        assertEquals(pending.hash,StickerStager.verify(context,pending))
        assertThrows(IllegalArgumentException::class.java){StickerStager.verify(context,unused)}
        assertNotNull(context.contentResolver.openInputStream(foreign.uri)?.use{it.read()})
        assertNotNull(context.contentResolver.openInputStream(personal.uri)?.use{it.read()})
    }
    @Test fun anUnreadableJournalPreventsOrphanDeletion() {
        val provider=TestImages(File(context.cacheDir,"unknown-provider")).apply{attachInfo(context,android.content.pm.ProviderInfo().apply{authority="media"})}
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal("media",provider)
        val source=File(context.cacheDir,"unknown-source.png")
        val bitmap=Bitmap.createBitmap(16,16,Bitmap.Config.ARGB_8888)
        source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val prepared=StickerStager.prepare(source,File(context.cacheDir,"unknown-prepared"))
        val file=StickerStager.stageVerified(context,prepared,"e".repeat(64),true)
        File(context.filesDir,"reverse/history.json").apply{parentFile!!.mkdirs()}.writeText("incomplete")
        assertEquals(0,StickerStager.cleanupUnused(context))
        assertEquals(file.hash,StickerStager.verify(context,file))
        File(context.filesDir,"reverse/history.json").apply{parentFile!!.mkdirs()}.writeText(org.json.JSONArray().put(JSONObject().put("submitted",true).put("stagedUri","file:///unknown")).toString())
        assertEquals(0,StickerStager.cleanupUnused(context))
        assertEquals(file.hash,StickerStager.verify(context,file))
    }
    class TestImages(private val prefix:File) : android.content.ContentProvider() {
        private val rows=linkedMapOf<Long,android.content.ContentValues>()
        private var sequence=0L
        fun change(uri:android.net.Uri,key:String,value:String){rows.getValue(uri.lastPathSegment!!.toLong()).put(key,value)}
        private fun bytes(id:Long)=File(prefix.parentFile,prefix.name+"-"+id)
        override fun onCreate()=true
        override fun getType(uri:android.net.Uri)="image/png"
        override fun insert(uri:android.net.Uri,values:android.content.ContentValues?):android.net.Uri {
            val id=++sequence;rows[id]=android.content.ContentValues(values).apply{put("owner_package_name",RuntimeEnvironment.getApplication().packageName)};bytes(id).writeBytes(byteArrayOf())
            return android.content.ContentUris.withAppendedId(uri,id)
        }
        override fun openFile(uri:android.net.Uri,mode:String):android.os.ParcelFileDescriptor = android.os.ParcelFileDescriptor.open(bytes(uri.lastPathSegment!!.toLong()),
            if(mode.contains('w'))android.os.ParcelFileDescriptor.MODE_READ_WRITE or android.os.ParcelFileDescriptor.MODE_TRUNCATE else android.os.ParcelFileDescriptor.MODE_READ_ONLY)
        override fun update(uri:android.net.Uri,values:android.content.ContentValues?,selection:String?,args:Array<out String>?):Int {
            rows[uri.lastPathSegment!!.toLong()]?.putAll(values);return 1
        }
        override fun delete(uri:android.net.Uri,selection:String?,args:Array<out String>?):Int {
            val id=uri.lastPathSegment!!.toLong();rows.remove(id);bytes(id).delete();return 1
        }
        override fun query(uri:android.net.Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):android.database.Cursor {
            val columns=projection?:arrayOf("_id","_display_name")
            return android.database.MatrixCursor(columns).apply {
                rows.filter{(_,values)->args.isNullOrEmpty()||selection?.contains("LIKE")==true||values.getAsString("relative_path")==args[0]}.forEach{(id,values)->
                    addRow(columns.map{if(it=="_id")id else values.getAsString(it)}.toTypedArray())
                }
            }
        }
    }
}
