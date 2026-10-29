package com.example.stickerpackbuilder

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.work.testing.WorkManagerTestInitHelper
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w393dp-h800dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class UiSmokeTest {
    @Test fun requiredUpdateBlocksTransfersEvenIfTheReleaseWasPreviouslyDismissed() {
        val release = org.json.JSONObject().put("tag_name", "v99.0.0")
            .put("body", com.example.stickerpackbuilder.update.GitHubUpdates.MANDATORY_MARKER)
            .put("assets", org.json.JSONArray().put(org.json.JSONObject().put("name", "StickerTransfer.apk")
                .put("browser_download_url", "https://github.com/Oly132/sticker-transfer/releases/download/v99.0.0/StickerTransfer.apk")
                .put("size", 100)))
        context.getSharedPreferences("app-updates", 0).edit().putString("release", release.toString())
            .putString("dismissedTag", "v99.0.0").putLong("checkedAt", System.currentTimeMillis()).commit()
        val controller = Robolectric.buildActivity(com.example.stickerpackbuilder.sync.TransferActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.javaClass.getDeclaredMethod("beginTransfer").apply { isAccessible = true }.invoke(activity)
            val launched = shadowOf(activity).nextStartedActivity
            assertEquals(com.example.stickerpackbuilder.update.UpdateActivity::class.java.name, launched.component!!.className)
            assertTrue(com.example.stickerpackbuilder.update.GitHubUpdates.restore(launched.getStringExtra("update")!!).mandatory)
        } finally { controller.pause().stop().destroy() }
    }
    private val context = RuntimeEnvironment.getApplication()
    @Before fun clean() {
        context.filesDir.listFiles()?.forEach { it.deleteRecursively() }
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
        context.getSharedPreferences("auto-transfer", 0).edit().clear().commit()
        context.getSharedPreferences("transfer-ui",0).edit().clear().commit()
        com.example.stickerpackbuilder.reverse.ReverseState.active=false
        // UI rendering tests use a cached current release, never a live GitHub request.
        context.getSharedPreferences("app-updates", 0).edit().clear()
            .putLong("checkedAt", System.currentTimeMillis())
            .putString("release", "{\"tag_name\":\"v4.7.0\",\"assets\":[]}").commit()
        com.example.stickerpackbuilder.sync.TransferState.status.value = com.example.stickerpackbuilder.sync.TransferState.Status()
    }
    private fun settle() {
        repeat(40) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(20) }
        shadowOf(Looper.getMainLooper()).idle()
    }
    private fun all(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList()
    private fun capture(activity: android.app.Activity, name: String) {
        settle()
        captureView(activity.window.decorView, name)
    }
    private fun captureView(root: View, name: String) {
        root.measure(View.MeasureSpec.makeMeasureSpec(1179, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(2400, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1179, 2400)
        val bitmap = Bitmap.createBitmap(1179, 2400, Bitmap.Config.ARGB_8888)
        root.draw(Canvas(bitmap))
        val dir = File("../../../work/test-screens").apply { mkdirs() }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun checkScrollingGallery(activity: android.app.Activity, name: String) {
        val views=all(activity.window.decorView)
        val grid=views.filterIsInstance<androidx.recyclerview.widget.RecyclerView>().single{it.isShown}
        val appBar=views.filterIsInstance<com.google.android.material.appbar.AppBarLayout>().single{it.isShown}
        val original=android.graphics.Rect().also{assertTrue(grid.getGlobalVisibleRect(it))}
        assertTrue(grid.startNestedScroll(androidx.core.view.ViewCompat.SCROLL_AXIS_VERTICAL))
        val consumed=IntArray(2)
        grid.dispatchNestedPreScroll(0,2000,consumed,null)
        assertTrue(consumed[1]>0)
        grid.stopNestedScroll()
        capture(activity,"$name-scrolled")
        val expanded=android.graphics.Rect().also{assertTrue(grid.getGlobalVisibleRect(it))}
        assertTrue("Normal scrolling should make more room for the grid",expanded.top<original.top)
        val headerBounds=android.graphics.Rect()
        if(appBar.getGlobalVisibleRect(headerBounds))assertFalse("Header must not cover stickers",android.graphics.Rect.intersects(headerBounds,expanded))
        assertTrue(grid.startNestedScroll(androidx.core.view.ViewCompat.SCROLL_AXIS_VERTICAL))
        grid.dispatchNestedPreScroll(0,-2000,IntArray(2),null)
        grid.stopNestedScroll();capture(activity,"$name-returned")
    }
    private fun seed(): File {
        val source = File(context.cacheDir, "still.png")
        javaClass.classLoader!!.getResourceAsStream("still.png")!!.use { input -> source.outputStream().use { input.copyTo(it) } }
        val out = File(context.cacheDir, "sticker.webp")
        StickerConverter.convert(source, out)
        repeat(3) { PackRepository.accept(context, out, false, "s$it", false) }
        return out
    }
    private fun chooseFirstBatch() {
        val sheet = org.robolectric.shadows.ShadowDialog.getLatestDialog() as com.google.android.material.bottomsheet.BottomSheetDialog
        all(sheet.window!!.decorView).single { it.contentDescription?.toString() == "10 most recent" }.performClick()
        all(sheet.window!!.decorView).filterIsInstance<android.widget.Button>().single { it.text.toString() == "Continue" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
    }
    @Test fun emptyCompletionAndLinkingScreensRenderAndRejectIncompleteNumber() {
        val settings = Robolectric.buildActivity(com.example.stickerpackbuilder.sync.AutoTransferSettingsActivity::class.java).setup()
        capture(settings.get(), "auto-transfer-settings")
        settings.pause().stop().destroy()
        val surface = com.example.stickerpackbuilder.sync.TransferCover.create(context) {}
        captureView(surface.view, "transfer-cover")
        val reverseCover=com.example.stickerpackbuilder.sync.TransferCover.create(context,true) {}
        reverseCover.progress(0,3)
        assertEquals("0 / 3",reverseCover.counter.text.toString())
        reverseCover.status.text="Adding to TikTok…"
        reverseCover.progress(1,3)
        assertEquals("1 / 3",reverseCover.counter.text.toString())
        assertEquals("Adding to TikTok…",reverseCover.status.text.toString())
        captureView(reverseCover.view,"reverse-transfer-progress")
        reverseCover.progress(3,3)
        assertEquals("3 / 3",reverseCover.counter.text.toString())
        val home = Robolectric.buildActivity(com.example.stickerpackbuilder.sync.TransferActivity::class.java).setup()
        capture(home.get(), "home-empty")
        com.example.stickerpackbuilder.sync.TransferState.update(com.example.stickerpackbuilder.sync.TransferState.Phase.COMPLETE, "Your stickers are in WhatsApp.")
        capture(home.get(), "home-complete")
        home.pause().stop().destroy()
        val link = Robolectric.buildActivity(com.example.stickerpackbuilder.sync.LinkWhatsAppActivity::class.java).setup()
        val field = all(link.get().window.decorView).filterIsInstance<com.google.android.material.textfield.TextInputLayout>().single()
        field.editText!!.setText("31")
        all(link.get().window.decorView).filterIsInstance<android.widget.Button>().single { it.text.toString() == "Link WhatsApp" }.performClick()
        assertNotNull(field.error)
        capture(link.get(), "link-number-validation")
        link.pause().stop().destroy()
    }
    @Test fun whatsappLinkKeepsCodeAndOtherPhoneFallbackAfterRecreation() {
        val saved = android.os.Bundle().apply {
            putString("linkingCode", "ABCD1234")
            putBoolean("otherPhone", true)
        }
        val controller = Robolectric.buildActivity(com.example.stickerpackbuilder.sync.LinkWhatsAppActivity::class.java)
            .create(saved).start().resume().visible()
        try {
            val activity = controller.get()
            val views = all(activity.window.decorView)
            assertTrue(views.filterIsInstance<com.google.android.material.switchmaterial.SwitchMaterial>().single().isChecked)
            assertTrue(views.filterIsInstance<TextView>().any { it.text.contains("Link with phone number instead") })
            assertTrue(views.filterIsInstance<TextView>().any { it.text.contains("ABCD – 1234") })
            assertNull(shadowOf(activity).nextStartedActivity)
            views.filterIsInstance<android.widget.Button>().single { it.text.toString() == "Copy code" }.performClick()
            val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            assertEquals("ABCD1234", clipboard.primaryClip!!.getItemAt(0).text.toString())
            views.filterIsInstance<android.widget.Button>().single { it.text.toString() == "Open WhatsApp" }.performClick()
            // No installed WhatsApp: keep the code and manual instructions instead of crashing or opening a browser.
            assertNull(shadowOf(activity).nextStartedActivity)
            assertEquals("ABCD1234", clipboard.primaryClip!!.getItemAt(0).text.toString())
        } finally { controller.pause().stop().destroy() }
    }
    @Test fun directionSwitchKeepsTheSameDashboardAndDoesNotLaunchAnActivity() {
        val controller=Robolectric.buildActivity(com.example.stickerpackbuilder.sync.TransferActivity::class.java).setup()
        try{
            val activity=controller.get();capture(activity,"direction-forward")
            val glow=all(activity.window.decorView).filterIsInstance<com.example.stickerpackbuilder.sync.TransferGlow>().single()
            val settings=all(activity.window.decorView).filterIsInstance<android.widget.Button>().single{it.text.toString()=="Settings"}
            fun swap(){all(activity.window.decorView).filterIsInstance<android.widget.Button>().single{it.isShown&&it.contentDescription?.toString()?.startsWith("Reverse transfer direction")==true}.performClick();settle()}
            swap();capture(activity,"direction-reverse")
            assertNull(shadowOf(activity).nextStartedActivity)
            assertSame(glow,all(activity.window.decorView).filterIsInstance<com.example.stickerpackbuilder.sync.TransferGlow>().single())
            assertSame(settings,all(activity.window.decorView).filterIsInstance<android.widget.Button>().single{it.text.toString()=="Settings"})
            assertTrue(all(activity.window.decorView).filterIsInstance<TextView>().any{it.isShown&&it.text.toString()=="From WhatsApp to TikTok. One little tap."})
            activity.onBackPressedDispatcher.onBackPressed();settle()
            assertFalse(activity.isFinishing)
            assertTrue(all(activity.window.decorView).filterIsInstance<android.widget.Button>().any{it.isShown&&it.text.toString()=="Transfer all"})
            assertNull(shadowOf(activity).nextStartedActivity)
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun reverseGalleryUsesExistingThemeAndSelectAllSkipsSubmittedStickers() {
        context.getSharedPreferences("reverse",0).edit().clear().putString("destination","test.account").putBoolean("partialCatalogue",true).commit()
        val root=File(context.filesDir,"reverse").apply{mkdirs()}
        val rows=org.json.JSONArray()
        repeat(4){i->
            val image=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply{eraseColor(listOf(android.graphics.Color.RED,android.graphics.Color.GREEN,android.graphics.Color.BLUE,android.graphics.Color.YELLOW)[i])}
            val file=File(root,"fixture-$i.webp")
            file.outputStream().use{@Suppress("DEPRECATION") image.compress(Bitmap.CompressFormat.WEBP,100,it)};image.recycle()
            val id=com.example.stickerpackbuilder.reverse.ReverseRepository.hash(file)
            val target=com.example.stickerpackbuilder.reverse.ReverseRepository(context).original(id).apply{parentFile!!.mkdirs()}
            file.copyTo(target,true)
            rows.put(org.json.JSONObject().put("id",id).put("observedAt",i).put("fromTikTok",i==3))
            if(i==0)com.example.stickerpackbuilder.reverse.ReverseHistory.record(context,org.json.JSONObject().put("sourceId",id).put("submitted",true).put("result","Create requested; confirmation pending"))
        }
        val local=File(root,"local-only.webp")
        val localImage=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply{eraseColor(android.graphics.Color.MAGENTA)}
        local.outputStream().use{@Suppress("DEPRECATION") localImage.compress(Bitmap.CompressFormat.WEBP,100,it)};localImage.recycle()
        val localId=com.example.stickerpackbuilder.reverse.ReverseRepository.hash(local)
        rows.put(org.json.JSONObject().put("id",localId).put("observedAt",5))
        com.example.stickerpackbuilder.reverse.LocalOriginalsTest.connect(context,mapOf("local" to local))
        File(root,"catalogue.json").writeText(rows.toString())
        val controller=Robolectric.buildActivity(com.example.stickerpackbuilder.sync.TransferActivity::class.java,
            Intent(context,com.example.stickerpackbuilder.sync.TransferActivity::class.java).putExtra(com.example.stickerpackbuilder.sync.TransferActivity.DIRECTION_EXTRA,true)).setup()
        try{
            val activity=controller.get();capture(activity,"reverse-gallery")
            assertTrue(all(activity.window.decorView).filterIsInstance<TextView>().any{it.text.toString().contains("Some favourites may be missing")})
            assertTrue(com.example.stickerpackbuilder.reverse.ReverseRepository(context).verified(localId))
            assertEquals(4,all(activity.window.decorView).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().single{it.isShown}.adapter!!.itemCount)
            assertFalse(all(activity.window.decorView).filterIsInstance<TextView>().any{it.text.toString().contains("From TikTok already")})
            val buttons=all(activity.window.decorView).filterIsInstance<android.widget.Button>()
            buttons.single{it.text.toString()=="Select all"}.performClick()
            assertTrue(all(activity.window.decorView).filterIsInstance<TextView>().any{it.text.toString()=="Transfer 3 selected"})
            val retainedList=all(activity.window.decorView).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().single{it.isShown}
            fun visibleGalleryHeight():Int {
                val bounds=android.graphics.Rect()
                retainedList.getGlobalVisibleRect(bounds)
                return bounds.height()
            }
            val collapsedHeight=visibleGalleryHeight()
            val transfer=all(activity.window.decorView).filterIsInstance<android.widget.Button>().single{it.isShown&&it.text.toString()=="Transfer 3 selected"}
            val transferBounds=android.graphics.Rect()
            assertTrue(transfer.getGlobalVisibleRect(transferBounds))
            assertEquals(transfer.height,transferBounds.height())
            val consumed=IntArray(2)
            assertTrue(retainedList.startNestedScroll(androidx.core.view.ViewCompat.SCROLL_AXIS_VERTICAL))
            retainedList.dispatchNestedPreScroll(0,activity.dp(500),consumed,null)
            assertTrue("Scrolling the grid should collapse the header",consumed[1]>0)
            retainedList.stopNestedScroll()
            capture(activity,"reverse-gallery-expanded")
            assertTrue("Expanded gallery should have more room for tiles",visibleGalleryHeight()>collapsedHeight)
            assertTrue(all(activity.window.decorView).filterIsInstance<android.widget.Button>().any{it.isShown&&it.text.toString()=="Transfer 3 selected"})
            assertFalse(all(activity.window.decorView).any{it.contentDescription=="Expand sticker gallery"})
            assertFalse(all(activity.window.decorView).any{it.contentDescription=="Collapse sticker gallery"})
            assertTrue(retainedList.startNestedScroll(androidx.core.view.ViewCompat.SCROLL_AXIS_VERTICAL))
            consumed.fill(0)
            retainedList.dispatchNestedPreScroll(0,-activity.dp(600),consumed,null)
            assertTrue("Scrolling down should reveal the header",consumed[1]<0)
            retainedList.stopNestedScroll()
            capture(activity,"reverse-gallery-collapsed")
            assertTrue(visibleGalleryHeight()<activity.window.decorView.height-activity.dp(200))
            assertSame(retainedList,all(activity.window.decorView).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().single{it.isShown})
            activity.showTransferDirection(false);activity.showTransferDirection(true);settle()
            assertSame(retainedList,all(activity.window.decorView).filterIsInstance<androidx.recyclerview.widget.RecyclerView>().single{it.isShown})
            assertTrue(all(activity.window.decorView).filterIsInstance<TextView>().any{it.isShown&&it.text.toString()=="Transfer 3 selected"})
            assertNull(shadowOf(activity).nextStartedActivity)
            buttons.single{it.text.toString()=="Clear"}.performClick()
            assertTrue(all(activity.window.decorView).filterIsInstance<TextView>().any{it.text.toString()=="Transfer 3 new stickers"})
            capture(activity,"reverse-selected-cleared")
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun reverseChoicesSurviveRecreationWhileTheForwardDirectionIsVisible() {
        context.getSharedPreferences("reverse",0).edit().clear().putString("destination","test.account").commit()
        val repo=com.example.stickerpackbuilder.reverse.ReverseRepository(context)
        val rows=org.json.JSONArray()
        repeat(2){index->
            val bitmap=Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply{eraseColor(if(index==0)android.graphics.Color.RED else android.graphics.Color.BLUE)}
            val source=File(context.cacheDir,"direction-$index.webp")
            source.outputStream().use{@Suppress("DEPRECATION") bitmap.compress(Bitmap.CompressFormat.WEBP,100,it)};bitmap.recycle()
            val id=com.example.stickerpackbuilder.reverse.ReverseRepository.hash(source)
            source.copyTo(repo.original(id).apply{parentFile!!.mkdirs()},true)
            rows.put(org.json.JSONObject().put("id",id).put("observedAt",index))
        }
        File(context.filesDir,"reverse/catalogue.json").writeText(rows.toString())
        var controller=Robolectric.buildActivity(com.example.stickerpackbuilder.sync.TransferActivity::class.java,
            Intent(context,com.example.stickerpackbuilder.sync.TransferActivity::class.java).putExtra(com.example.stickerpackbuilder.sync.TransferActivity.DIRECTION_EXTRA,true)).setup()
        try{
            capture(controller.get(),"direction-selection")
            all(controller.get().window.decorView).filterIsInstance<android.widget.Button>().single{it.isShown&&it.text.toString()=="Select all"}.performClick()
            controller.get().showTransferDirection(false)
            repeat(2){
                val state=android.os.Bundle();val originalIntent=Intent(controller.get().intent)
                controller.pause().stop().saveInstanceState(state).destroy()
                controller=Robolectric.buildActivity(com.example.stickerpackbuilder.sync.TransferActivity::class.java,originalIntent).create(state).start().resume().visible()
                settle()
                assertTrue(all(controller.get().window.decorView).filterIsInstance<android.widget.Button>().any{it.isShown&&it.text.toString()=="Transfer all"})
            }
            controller.get().showTransferDirection(true);capture(controller.get(),"direction-selection-restored")
            assertTrue(all(controller.get().window.decorView).filterIsInstance<android.widget.Button>().any{it.isShown&&it.text.toString()=="Transfer 2 selected"})
            assertNull(shadowOf(controller.get()).nextStartedActivity)
        }finally{controller.pause().stop().destroy()}
    }
    @Test fun homeReviewAndPackEditorOpenWithRealAssets() {
        val sticker = seed()
        com.example.stickerpackbuilder.sync.TransferredGallery(context).remember("https://p16.ibyteimg.com/tos/ui-sticker", sticker, false)
        val home = Robolectric.buildActivity(com.example.stickerpackbuilder.sync.TransferActivity::class.java).setup()
        capture(home.get(), "home")
        checkScrollingGallery(home.get(),"forward-gallery")
        assertEquals(5, all(home.get().window.decorView).filterIsInstance<android.widget.Button>().count{it.isShown})
        assertTrue(all(home.get().window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "Transfer all" })
        assertTrue(all(home.get().window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "Your collection · 1" })
        home.pause().stop().destroy()
        val review = Robolectric.buildActivity(ReviewActivity::class.java).setup()
        capture(review.get(), "review")
        assertTrue(all(review.get().window.decorView).filterIsInstance<TextView>().any { it.text.toString().contains("3 available") })
        review.pause().stop().destroy()
        val pack = PackRepository.createPackFromPending(context, PackRepository.listPendingFiles(context), "Weekend favourites")
        val editor = Robolectric.buildActivity(PackDetailActivity::class.java,
            Intent(context, PackDetailActivity::class.java).putExtra("pack", pack.identifier)).setup()
        capture(editor.get(), "pack")
        assertTrue(all(editor.get().window.decorView).filterIsInstance<TextView>().any { it.text.toString() == "Weekend favourites" })
        editor.pause().stop().destroy()
    }
    @Test fun customLoginAndOfficialFallbackRemainAvailable() {
        val browser = Robolectric.buildActivity(BrowseActivity::class.java).setup()
        capture(browser.get(), "login")
        val fallback = all(browser.get().window.decorView).filterIsInstance<TextView>().first {
            it.text.toString() == "Use TikTok’s official login instead"
        }
        assertTrue(fallback.isShown)
        fallback.performClick()
        assertFalse(fallback.isShown)
        assertTrue(all(browser.get().window.decorView).filterIsInstance<TextView>().any {
            it.text.toString().contains("Sign in on TikTok below")
        })
        browser.pause().stop().destroy()
    }
    @Test fun galleryKeepsPreviewsAndDeduplicatesAcrossRestarts() {
        val sticker = seed()
        val url = "https://p16.ibyteimg.com/tos/gallery-restart~origin.image?x-expires=1"
        val gallery = com.example.stickerpackbuilder.sync.TransferredGallery(context)
        gallery.remember(url, sticker, false)
        val reopened = com.example.stickerpackbuilder.sync.TransferredGallery(context)
        reopened.remember(url.replace("p16", "p19").replace("expires=1", "expires=2"), sticker, false)
        assertEquals(1, reopened.entries().size)
        assertTrue(reopened.preview(reopened.entries().single()).isFile)
        val bitmap = android.graphics.BitmapFactory.decodeFile(reopened.preview(reopened.entries().single()).path)
        assertEquals(160, bitmap.width); bitmap.recycle()
        val ledgerFile = File(context.filesDir, "transfer/collected.properties")
        com.example.stickerpackbuilder.sync.CollectedStickerLedger(ledgerFile).record(url)
        reopened.seedHistory(ledgerFile)
        assertEquals(1, reopened.entries().size)
        assertTrue(reopened.hasPreview(url))
    }
    @Test fun concurrentGalleryInstancesKeepBothHistoriesWithoutSharingTemporaryWrites() {
        val ledgerFiles = (1..2).map { number ->
            File(context.filesDir, "history-$number.properties").also {
                com.example.stickerpackbuilder.sync.CollectedStickerLedger(it).record("https://p16.ibyteimg.com/tos/concurrent-$number")
            }
        }
        val start = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val tasks = ledgerFiles.map { file -> pool.submit {
                val gallery = com.example.stickerpackbuilder.sync.TransferredGallery(context)
                start.await()
                repeat(50) { gallery.seedHistory(file); gallery.entries() }
            } }
            start.countDown()
            tasks.forEach { it.get(10, java.util.concurrent.TimeUnit.SECONDS) }
            assertEquals(2, com.example.stickerpackbuilder.sync.TransferredGallery(context).entries().size)
        } finally { pool.shutdownNow() }
    }
    @Test fun confirmedBatchHistorySurvivesReopenAndKeepsPriorStickers() {
        val sticker = seed()
        val gallery = com.example.stickerpackbuilder.sync.TransferredGallery(context)
        val prior = "https://p16.ibyteimg.com/tos/batch-prior"
        gallery.remember(prior, sticker, false, "prior")
        val batch = (1..16).map { "https://p16.ibyteimg.com/tos/batch-$it" }
        gallery.rememberBatch(batch.mapIndexed { i, url ->
            com.example.stickerpackbuilder.sync.TransferredGallery.Delivered(url, sticker, false, "hash-$i")
        })
        val ledgerFile = File(context.filesDir, "transfer/collected.properties")
        val ledger = com.example.stickerpackbuilder.sync.CollectedStickerLedger(ledgerFile)
        ledger.record(prior); ledger.recordBatch(batch)
        val reopened = com.example.stickerpackbuilder.sync.TransferredGallery(context).entries()
        assertEquals(17, reopened.size)
        assertEquals((batch + prior).map(com.example.stickerpackbuilder.sync.CollectedStickerLedger::assetKey).toSet(), reopened.map { it.getString("key") }.toSet())
        assertTrue((batch + prior).all { com.example.stickerpackbuilder.sync.CollectedStickerLedger(ledgerFile).contains(it) })
        assertTrue(reopened.all { gallery.preview(it).exists() })
    }
    @Test fun missingPermissionIsRequestedFromTheSingleTransferAction() {
        com.example.stickerpackbuilder.sync.TikTokTransferService.instance = null
        val home = Robolectric.buildActivity(com.example.stickerpackbuilder.sync.TransferActivity::class.java).setup()
        all(home.get().window.decorView).filterIsInstance<android.widget.Button>().first { it.text.toString() == "Transfer all" }.performClick()
        chooseFirstBatch()
        val permission = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        captureView(permission.window!!.decorView, "permission-guide")
        assertTrue(all(permission.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == "Open permissions" })
        permission.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        settle()
        assertEquals("Waiting for automation permission…", com.example.stickerpackbuilder.sync.TransferState.status.value!!.message)
        home.pause().stop().destroy()
    }
    @Test fun blockedAutomationHelpOpensOnlyThisAppsInfo() {
        com.example.stickerpackbuilder.sync.TikTokTransferService.instance = null
        val home = Robolectric.buildActivity(com.example.stickerpackbuilder.sync.TransferActivity::class.java).setup()
        all(home.get().window.decorView).filterIsInstance<android.widget.Button>().first { it.text.toString() == "Transfer all" }.performClick()
        chooseFirstBatch()
        (org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog)
            .getButton(android.content.DialogInterface.BUTTON_NEUTRAL).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val help = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        captureView(help.window!!.decorView, "restricted-guide")
        assertTrue(all(help.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("Allow restricted settings") })
        val helpText = all(help.window!!.decorView).filterIsInstance<TextView>().joinToString("\n") { it.text.toString() }
        assertTrue(helpText.contains("Controlled by restricted settings"))
        assertTrue(helpText.contains("Settings → Apps → Sticker Sync"))
        assertTrue(helpText.contains("2. Confirm with your PIN or fingerprint."))
        assertTrue(helpText.contains("Accessibility → Installed apps → Sticker Sync"))
        help.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val intent = shadowOf(home.get()).nextStartedActivity
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, intent.action)
        assertEquals("package:${home.get().packageName}", intent.data.toString())
        home.pause().stop().destroy()
    }
    @Test fun returningWithoutAutomationShowsRestrictedSettingsInstructions() {
        com.example.stickerpackbuilder.sync.TikTokTransferService.instance = null
        val home = Robolectric.buildActivity(com.example.stickerpackbuilder.sync.TransferActivity::class.java).setup()
        all(home.get().window.decorView).filterIsInstance<android.widget.Button>().first { it.text.toString() == "Transfer all" }.performClick()
        chooseFirstBatch()
        (org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog)
            .getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
        shadowOf(Looper.getMainLooper()).idle()
        val launched = shadowOf(home.get()).nextStartedActivityForResult
        assertEquals(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS, launched.intent.action)
        home.get().activityResultRegistry.dispatchResult(launched.requestCode, android.app.Activity.RESULT_CANCELED, null)
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(3))
        val help = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertTrue(all(help.window!!.decorView).filterIsInstance<TextView>().any { it.text.contains("Controlled by restricted settings") })
        assertEquals("Open App info", help.getButton(android.content.DialogInterface.BUTTON_POSITIVE).text.toString())
        home.pause().stop().destroy()
    }
    @Test fun batchCardsRequireConfirmationAndRememberTheChosenLimit() {
        com.example.stickerpackbuilder.sync.TikTokTransferService.instance = null
        context.getSharedPreferences("transfer", 0).edit().putInt("maximum", 20).apply()
        val home = Robolectric.buildActivity(com.example.stickerpackbuilder.sync.TransferActivity::class.java).setup()
        all(home.get().window.decorView).filterIsInstance<android.widget.Button>().first { it.text.toString() == "Transfer all" }.performClick()
        val sheet = org.robolectric.shadows.ShadowDialog.getLatestDialog() as com.google.android.material.bottomsheet.BottomSheetDialog
        val card = all(sheet.window!!.decorView).single { it.contentDescription?.toString() == "20 most recent" } as com.google.android.material.card.MaterialCardView
        assertTrue(card.isChecked)
        captureView(sheet.window!!.decorView, "batch-sheet")
        all(sheet.window!!.decorView).single { it.contentDescription?.toString() == "200 most recent" }.performClick()
        assertFalse(card.isChecked)
        assertEquals(20, context.getSharedPreferences("transfer", 0).getInt("maximum", 0))
        assertTrue(sheet.isShowing)
        all(sheet.window!!.decorView).filterIsInstance<android.widget.Button>().single { it.text.toString() == "Continue" }.performClick()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(200, context.getSharedPreferences("transfer", 0).getInt("maximum", 0))
        assertFalse(sheet.isShowing)
        home.pause().stop().destroy()
    }
    @Test fun customSelectionReturnsOnlyCheckedUnsyncedSticker() {
        val sticker = seed()
        val first = "https://p16.ibyteimg.com/tos/already-synced"
        val second = "https://p16.ibyteimg.com/tos/choose-this"
        com.example.stickerpackbuilder.sync.CollectedStickerLedger(File(context.filesDir, "transfer/collected.properties")).record(first)
        sticker.copyTo(com.example.stickerpackbuilder.sync.StickerDownload.cached(context, second), overwrite = true)
        File(context.filesDir, "transfer/journal.json").writeText(org.json.JSONObject()
            .put("catalog", org.json.JSONArray(listOf(first, second))).toString())
        val picker = Robolectric.buildActivity(com.example.stickerpackbuilder.sync.ChooseStickersActivity::class.java).setup()
        capture(picker.get(), "custom")
        checkScrollingGallery(picker.get(),"custom-gallery")
        val boxes = all(picker.get().window.decorView).filterIsInstance<android.widget.CheckBox>()
        assertEquals(2, boxes.size)
        assertFalse(boxes[0].isEnabled); assertTrue(boxes[1].isEnabled)
        boxes[1].performClick()
        all(picker.get().window.decorView).filterIsInstance<android.widget.Button>().filterNot { it is android.widget.CheckBox }
            .single { it.text.toString() == "Transfer 1 sticker" }.performClick()
        assertEquals(listOf(com.example.stickerpackbuilder.sync.CollectedStickerLedger.assetKey(second)),
            shadowOf(picker.get()).resultIntent.getStringArrayListExtra("selectedKeys"))
        picker.pause().stop().destroy()
    }
    @Test fun removingSelectedHistoryKeepsOthersAndAllowsRetransfer() {
        val sticker = seed()
        val gallery = com.example.stickerpackbuilder.sync.TransferredGallery(context)
        val ledgerFile = File(context.filesDir, "transfer/collected.properties")
        val ledger = com.example.stickerpackbuilder.sync.CollectedStickerLedger(ledgerFile)
        val urls = (1..5).map { "https://p16.ibyteimg.com/tos/remove-$it" }
        urls.forEachIndexed { index, url ->
            gallery.remember(url, sticker, false, android.util.Base64.encodeToString(ByteArray(32) { index.toByte() }, android.util.Base64.NO_WRAP))
            ledger.record(url)
        }
        val selected = setOf(urls[1], urls[3]).map(com.example.stickerpackbuilder.sync.CollectedStickerLedger::assetKey).toSet()
        ledger.forgetKeys(selected); gallery.forget(selected)
        gallery.seedHistory(ledgerFile)
        assertEquals(3, gallery.entries().size)
        val reopened = com.example.stickerpackbuilder.sync.CollectedStickerLedger(ledgerFile)
        assertFalse(reopened.contains(urls[1])); assertFalse(reopened.contains(urls[3]))
        assertTrue(reopened.contains(urls[0])); assertTrue(reopened.contains(urls[2])); assertTrue(reopened.contains(urls[4]))
        assertTrue(gallery.entries().all { it.optString("whatsappHash").isNotBlank() })
        assertFalse(gallery.hasPreview(urls[1])); assertTrue(gallery.hasPreview(urls[0]))
    }
    @Test fun removalPickerConfirmsOnlyCheckedStickersWithoutChangingHistoryEarly() {
        val sticker = seed()
        val gallery = com.example.stickerpackbuilder.sync.TransferredGallery(context)
        repeat(3) { number -> gallery.remember("https://p16.ibyteimg.com/tos/remove-ui-$number", sticker, false,
            android.util.Base64.encodeToString(ByteArray(32) { number.toByte() }, android.util.Base64.NO_WRAP)) }
        val picker = Robolectric.buildActivity(com.example.stickerpackbuilder.sync.RemoveStickersActivity::class.java).setup()
        capture(picker.get(), "remove-stickers")
        checkScrollingGallery(picker.get(),"remove-gallery")
        val checks = all(picker.get().window.decorView).filterIsInstance<android.widget.CheckBox>()
        assertEquals(3, checks.size)
        checks[0].performClick(); checks[2].performClick()
        val selectAll = all(picker.get().window.decorView).filterIsInstance<android.widget.Button>().single { it.text.toString() == "Select all" }
        selectAll.performClick(); capture(picker.get(), "remove-all-selected")
        assertTrue(all(picker.get().window.decorView).filterIsInstance<android.widget.CheckBox>().all { it.isChecked })
        assertEquals("Clear selection", selectAll.text.toString())
        selectAll.performClick(); settle()
        assertTrue(all(picker.get().window.decorView).filterIsInstance<android.widget.CheckBox>().none { it.isChecked })
        all(picker.get().window.decorView).filterIsInstance<android.widget.CheckBox>().let { boxes -> boxes[0].performClick(); boxes[2].performClick() }
        all(picker.get().window.decorView).filterIsInstance<android.widget.Button>().single { it.text.toString() == "Remove 2 from WhatsApp" }.performClick()
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue(all(dialog.window!!.decorView).filterIsInstance<TextView>().any { it.text.toString() == "Remove 2 favourites?" })
        assertEquals(3, gallery.entries().size)
        all(dialog.window!!.decorView).filterIsInstance<android.widget.Button>().single { it.text.toString().equals("Keep them", true) }.performClick()
        assertEquals(3, gallery.entries().size)
        picker.pause().stop().destroy()
    }
}

