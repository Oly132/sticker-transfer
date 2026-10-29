package com.example.stickerpackbuilder.reverse

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract as Documents
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LocalOriginalsTest {
    private val context get()=RuntimeEnvironment.getApplication()
    @Before fun clean(){File(context.filesDir,"reverse").deleteRecursively();context.getSharedPreferences("reverse",0).edit().clear().commit()}
    companion object {
        val tree:Uri = Documents.buildTreeDocumentUri("stickers.test.documents","primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers")
        fun connect(context:android.content.Context, files:Map<String,File>):DocumentsFixture {
            val provider=DocumentsFixture(files)
            provider.attachInfo(context,android.content.pm.ProviderInfo().apply{authority="stickers.test.documents"})
            ShadowContentResolver.registerProviderInternal("stickers.test.documents",provider)
            LocalOriginals.remember(context,tree)
            return provider
        }
    }
    private fun sticker(name:String,color:Int):File {
        val file=File(context.cacheDir,name)
        val bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply{eraseColor(color)}
        file.outputStream().use{@Suppress("DEPRECATION") bitmap.compress(Bitmap.CompressFormat.WEBP,100,it)};bitmap.recycle();return file
    }
    @Test fun grantedFolderIsReusedAndOnlyTheExactFavouriteIsRecoveredEvenWithoutReportedSize(){
        val source=sticker("local.webp",Color.GREEN);val unrelated=sticker("other.webp",Color.RED)
        val provider=connect(context,mapOf("original" to source,"unrelated" to unrelated))
        val repo=ReverseRepository(context);val id=ReverseRepository.hash(source)
        assertEquals(listOf(tree),LocalOriginals.availableTrees(context))
        assertEquals(1,LocalOriginals.recoverAvailable(context,setOf(id),repo))
        assertTrue(repo.verified(id));assertArrayEquals(source.readBytes(),repo.original(id).readBytes())
        assertFalse(repo.original(ReverseRepository.hash(unrelated)).exists())
        assertTrue(provider.reads>0)
        val reads=provider.reads;val queries=provider.queries
        assertEquals(0,LocalOriginals.recoverAvailable(context,setOf(id),repo))
        assertEquals(reads,provider.reads);assertEquals(queries,provider.queries)
        // The old picker flow persisted permission but never remembered its URI.
        context.getSharedPreferences("reverse",0).edit().remove("originalsFolder").commit()
        assertEquals(listOf(tree),LocalOriginals.availableTrees(context))
        assertEquals(0,LocalOriginals.recoverAvailable(context,emptySet(),repo))
    }
    @Test fun anUnrelatedGrantIsNotSilentlyScanned(){
        val unrelated=Documents.buildTreeDocumentUri("stickers.test.documents","primary:Documents")
        context.contentResolver.takePersistableUriPermission(unrelated,android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertTrue(LocalOriginals.availableTrees(context).isEmpty())
    }
    class DocumentsFixture(private val files:Map<String,File>):ContentProvider(){
        var reads=0
        var queries=0
        override fun onCreate()=true
        override fun getType(uri:Uri)="image/webp"
        override fun insert(uri:Uri,values:ContentValues?):Uri?=error("Read-only")
        override fun update(uri:Uri,values:ContentValues?,selection:String?,args:Array<out String>?):Int=error("Read-only")
        override fun delete(uri:Uri,selection:String?,args:Array<out String>?):Int=error("Read-only")
        override fun openFile(uri:Uri,mode:String):ParcelFileDescriptor{
            require(mode=="r");reads++
            return ParcelFileDescriptor.open(files.getValue(Documents.getDocumentId(uri)),ParcelFileDescriptor.MODE_READ_ONLY)
        }
        override fun query(uri:Uri,projection:Array<out String>?,selection:String?,args:Array<out String>?,sort:String?):Cursor{
            queries++
            val columns=projection!!
            return MatrixCursor(columns).apply{
                for((id,file) in files)addRow(columns.map{when(it){
                    Documents.Document.COLUMN_DOCUMENT_ID->id
                    Documents.Document.COLUMN_DISPLAY_NAME->file.name
                    Documents.Document.COLUMN_MIME_TYPE->"image/webp"
                    Documents.Document.COLUMN_SIZE->null // Legal for document providers; stream stays bounded.
                    Documents.Document.COLUMN_LAST_MODIFIED->file.lastModified()
                    else->null
                }}.toTypedArray())
            }
        }
    }
}
