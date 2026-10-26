package com.example.stickerpackbuilder

import android.content.*
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.robolectric.RuntimeEnvironment
import androidx.work.*
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowContentResolver
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImportWorkerTest {
    private val context = RuntimeEnvironment.getApplication()
    private class Images(val file: File) : ContentProvider() {
        override fun onCreate() = true
        override fun openFile(uri: Uri, mode: String) = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
        override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?): Cursor? = null
        override fun getType(uri: Uri) = "image/png"
        override fun insert(uri: Uri, values: ContentValues?): Uri? = null
        override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
        override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    }
    @Before fun clean() {
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }
    private fun job(file: File): Pair<String, ImportWorker> {
        val provider = Images(file)
        provider.attachInfo(context, ProviderInfo().apply { authority = "test.images" })
        ShadowContentResolver.registerProviderInternal("test.images", provider)
        val id = UUID.randomUUID().toString()
        ImportJobs.write(context, id, JSONObject().put("local", true).put("automatic", false)
            .put("items", JSONArray().put(JSONObject().put("source", "content://test.images/sticker").put("state", "pending"))))
        return Pair(id, TestListenableWorkerBuilder<ImportWorker>(context)
            .setInputData(workDataOf("job" to id)).build())
    }
    @Test fun failedConversionIsReportedAndNotCountedAsExported() = runBlocking {
        val file = File(context.cacheDir, "broken").apply { writeText("not an image") }
        val (id, worker) = job(file)
        val result = worker.doWork() as ListenableWorker.Result.Success
        assertEquals(1, result.outputData.getInt("failed", -1))
        assertTrue(result.outputData.getString("message")!!.startsWith("0 imported"))
        assertEquals("failed", ImportJobs.read(context, id).getJSONArray("items").getJSONObject(0).getString("state"))
        assertTrue(PackRepository.pending(context).isEmpty())
    }
    @Test fun rerunningACompletedJobDoesNotDuplicateItsSticker() = runBlocking {
        val file = File(context.cacheDir, "input.png")
        javaClass.classLoader!!.getResourceAsStream("still.png")!!.use { input -> file.outputStream().use { input.copyTo(it) } }
        val (id, worker) = job(file)
        worker.doWork()
        val resumed = TestListenableWorkerBuilder<ImportWorker>(context).setInputData(workDataOf("job" to id)).build()
        resumed.doWork()
        assertEquals(1, PackRepository.pending(context).size)
        assertEquals("done", ImportJobs.read(context, id).getJSONArray("items").getJSONObject(0).getString("state"))
    }
}

