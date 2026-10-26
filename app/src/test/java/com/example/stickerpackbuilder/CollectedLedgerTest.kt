package com.example.stickerpackbuilder

import com.example.stickerpackbuilder.sync.CollectedStickerLedger
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CollectedLedgerTest {
    @get:Rule val folder = TemporaryFolder()
    @Test fun renewedSignaturesAndCdnShardsDoNotCauseAnotherDownload() {
        val old = "https://p16.ibyteimg.com/tos/sticker-123~tplv-origin.image?x-expires=1&x-signature=old"
        val fresh = "https://p19.ibyteimg.com/tos/sticker-123~tplv-origin.image?x-expires=2&x-signature=new"
        val file = File(folder.root, "collected.properties")
        CollectedStickerLedger(file).record(old)
        assertTrue(CollectedStickerLedger(file).contains(fresh))
        assertFalse(CollectedStickerLedger(file).contains(fresh.replace("sticker-123", "sticker-456")))
    }
    @Test fun distinctIdBasedAddressesAndMultipleSavedEntriesRemainDistinct() {
        val file = File(folder.root, "collected.properties")
        val a = "https://www.tiktok.com/sticker?id=1"
        val b = "https://www.tiktok.com/sticker?id=2"
        val ledger = CollectedStickerLedger(file)
        ledger.record(a); ledger.record(b)
        val reopened = CollectedStickerLedger(file)
        assertTrue(reopened.contains(a)); assertTrue(reopened.contains(b))
    }
}
