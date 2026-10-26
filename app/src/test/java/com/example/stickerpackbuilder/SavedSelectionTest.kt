package com.example.stickerpackbuilder

import com.example.stickerpackbuilder.sync.SavedStickerSelection
import org.junit.Assert.*
import org.junit.Test

class SavedSelectionTest {
    private val prefix = "com.zhiliaoapp.musically:id/"
    @Test fun starTabRecentItemsAndCreateTileAreExcluded() {
        assertFalse(SavedStickerSelection.allowed(prefix + "ssb", prefix + "hfq", 1, 2))
        assertFalse(SavedStickerSelection.allowed(prefix + "ssb", prefix + "kpd", 3, 2))
        assertTrue(SavedStickerSelection.allowed(prefix + "ssb", prefix + "hfq", 3, 2))
    }
    @Test fun scrollingIntoOfficialPacksCannotProduceTransferCandidates() {
        assertFalse(SavedStickerSelection.allowed(prefix + "u8o", prefix + "hfq", 4, 2))
        assertFalse(SavedStickerSelection.allowed(null, prefix + "hfq", 4, 2))
        assertFalse(SavedStickerSelection.allowed(prefix + "ssb", prefix + "hfq", 4, -1))
    }
    @Test fun overlappingPagesKeepTheSameStickerIdentity() {
        val sent = mutableSetOf(SavedStickerSelection.key(7, 1))
        assertFalse(sent.add(SavedStickerSelection.key(7, 1)))
        assertTrue(sent.add(SavedStickerSelection.key(8, 1)))
    }
    @Test fun tiktok47LayoutAcceptsSavedTilesButNotRecentOrAddTiles() {
        assertTrue(SavedStickerSelection.isTabRow(prefix + "hgx"))
        assertTrue(SavedStickerSelection.isPickerButton(prefix + "kve"))
        assertTrue(SavedStickerSelection.isSavedGrid(prefix + "sqo"))
        assertTrue(SavedStickerSelection.allowed(prefix + "sqo", prefix + "hg3", 3, 2))
        assertFalse(SavedStickerSelection.allowed(prefix + "sqo", prefix + "hg3", 1, 2))
        assertFalse(SavedStickerSelection.allowed(prefix + "sqo", prefix + "ko1", 3, 2))
        assertFalse(SavedStickerSelection.allowed(prefix + "sqo", prefix + "hg3", 3, -1))
    }
    @Test fun layoutPairsCannotBeMixedOrUsedByAnotherPackage() {
        assertFalse(SavedStickerSelection.allowed(prefix + "ssb", prefix + "hg3", 3, 2))
        assertFalse(SavedStickerSelection.allowed(prefix + "sqo", prefix + "hfq", 3, 2))
        assertFalse(SavedStickerSelection.allowed("another.app:id/sqo", prefix + "hg3", 3, 2))
        assertFalse(SavedStickerSelection.isTabRow("another.app:id/hgx"))
        assertFalse(SavedStickerSelection.isSavedGrid(prefix + "official_pack"))
    }
}
