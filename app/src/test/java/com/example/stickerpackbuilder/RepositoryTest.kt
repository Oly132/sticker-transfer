package com.example.stickerpackbuilder

import android.content.Context
import android.content.pm.ProviderInfo
import android.net.Uri
import org.robolectric.RuntimeEnvironment
import com.example.stickerpackbuilder.provider.StickerContentProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RepositoryTest {
    private val context = RuntimeEnvironment.getApplication()
    @Before fun clean() { context.filesDir.listFiles()?.forEach { it.deleteRecursively() } }
    private fun converted(animated: Boolean = false): File {
        val name = if (animated) "motion.gif" else "still.png"
        val source = File(context.cacheDir, name)
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { input -> source.outputStream().use { input.copyTo(it) } }
        return File(context.cacheDir, if (animated) "animated.webp" else "still.webp").also {
            StickerConverter.convert(source, it)
        }
    }
    @Test fun duplicateImportsDoNotGrowTheLibrary() {
        val file = converted()
        assertEquals(PackRepository.ImportResult.ADDED, PackRepository.accept(context, file, false, "same-hash", false))
        assertEquals(PackRepository.ImportResult.DUPLICATE, PackRepository.accept(context, file, false, "same-hash", false))
        assertEquals(1, PackRepository.pending(context).size)
    }
    @Test fun mixedPacksAreRejectedAndEditsIncrementVersion() {
        val still = converted(); val motion = converted(true)
        repeat(3) { PackRepository.accept(context, still, false, "s$it", false) }
        repeat(3) { PackRepository.accept(context, motion, true, "a$it", false) }
        val all = PackRepository.pending(context)
        assertThrows(IllegalArgumentException::class.java) {
            PackRepository.createPackFromPending(context, all.take(4).map { it.fileName }, "Mixed")
        }
        assertEquals(6, PackRepository.pending(context).size)
        val pack = PackRepository.createPackFromPending(context, all.filter { !it.animated }.map { it.fileName }, "Favourites")
        PackRepository.validatePack(context, pack)
        PackRepository.rename(context, pack.identifier, "New name")
        val renamed = PackRepository.loadPacks(context).single()
        assertTrue(renamed.version > pack.version)
        PackRepository.removeSticker(context, pack.identifier, renamed.stickers.first().fileName)
        val edited = PackRepository.loadPacks(context).single()
        assertTrue(edited.version > renamed.version); assertFalse(edited.isComplete())
        PackRepository.deletePack(context, edited.identifier)
        assertTrue(PackRepository.loadPacks(context).isEmpty())
        assertFalse(PackRepository.packDir(context, pack.identifier).exists())
    }
    @Test fun automaticPacksSeparateTypesAndStopFillingAfterExport() {
        val still = converted(); val motion = converted(true)
        repeat(3) { PackRepository.accept(context, still, false, "s$it", true) }
        repeat(3) { PackRepository.accept(context, motion, true, "a$it", true) }
        val old = PackRepository.loadPacks(context).first { !it.animated }
        PackRepository.sealForExport(context, old.identifier)
        PackRepository.accept(context, still, false, "next", true)
        val packs = PackRepository.loadPacks(context)
        assertEquals(3, packs.size)
        assertEquals(3, packs.first { it.identifier == old.identifier }.stickers.size)
        assertEquals(1, packs.count { it.animated })
    }
    @Test fun concurrentImportsDoNotLoseUpdates() {
        val file = converted()
        val pool = Executors.newFixedThreadPool(4)
        try {
            val futures = (0 until 24).map { i -> pool.submit {
                PackRepository.accept(context, file, false, "hash$i", true)
            } }
            futures.forEach { it.get() }
            assertEquals(24, PackRepository.loadPacks(context).single().stickers.size)
        } finally { pool.shutdown() }
    }
    @Test fun waitingStickersCanRepairAPackAfterRemoval() {
        val file = converted()
        repeat(4) { PackRepository.accept(context, file, false, "s$it", false) }
        val waiting = PackRepository.pending(context)
        val pack = PackRepository.createPackFromPending(context, waiting.take(3).map { it.fileName }, "Test")
        PackRepository.removeSticker(context, pack.identifier, pack.stickers.first().fileName)
        PackRepository.appendPending(context, pack.identifier, listOf(waiting.last().fileName))
        val repaired = PackRepository.loadPacks(context).single()
        assertTrue(repaired.isComplete())
        assertTrue(repaired.version > pack.version)
        assertTrue(PackRepository.pending(context).isEmpty())
        PackRepository.validatePack(context, repaired)
    }
    @Test fun corruptedMetadataIsPreservedAndReported() {
        PackRepository.pending(context)
        val metadata = File(context.filesDir, "library.json").apply { writeText("broken") }
        assertThrows(IllegalStateException::class.java) { PackRepository.loadPacks(context) }
        assertEquals("broken", metadata.readText())
    }
    @Test fun providerOnlyServesRegisteredAssetsAndRejectsWrites() {
        val file = converted()
        repeat(3) { PackRepository.accept(context, file, false, "s$it", false) }
        val pack = PackRepository.createPackFromPending(context, PackRepository.listPendingFiles(context), "Test")
        val provider = StickerContentProvider()
        val authority = "${context.packageName}.stickercontentprovider"
        provider.attachInfo(context, ProviderInfo().apply { this.authority = authority })
        val base = "content://$authority/stickers_asset/${pack.identifier}/"
        assertNotNull(provider.openAssetFile(Uri.parse(base + pack.trayIconFileName), "r")?.also { it.close() })
        assertNull(provider.openAssetFile(Uri.parse(base + "..%2F..%2Flibrary.json"), "r"))
        assertNull(provider.openAssetFile(Uri.parse(base + "unregistered.webp"), "r"))
        assertThrows(java.io.FileNotFoundException::class.java) {
            provider.openAssetFile(Uri.parse(base + pack.trayIconFileName), "w")
        }
        assertThrows(IllegalArgumentException::class.java) {
            PackRepository.safeAsset(PackRepository.packDir(context, pack.identifier), "../library.json")
        }
    }
}

