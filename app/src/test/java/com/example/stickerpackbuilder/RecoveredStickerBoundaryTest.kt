package com.example.stickerpackbuilder

import com.example.stickerpackbuilder.sync.RecoveredStickerBoundary
import org.junit.Assert.*
import org.junit.Test

class RecoveredStickerBoundaryTest {
    private fun allowed(visible: List<String> = listOf("new1", "new2"), recovered: Set<String> = visible.toSet(),
                        baseline: Set<String> = setOf("original"), refreshed: Boolean = true,
                        oldest: Boolean = true, top: Boolean = true, bottom: Boolean = true) =
        RecoveredStickerBoundary.completeFreshList(2, refreshed, oldest, top, bottom, visible, recovered, baseline)
    @Test fun refreshedEntireNewChainCanReplaceMissingDeletedAnchor() {
        assertTrue(allowed())
        assertFalse(allowed(refreshed = false))
        assertFalse(allowed(oldest = false))
    }
    @Test fun preexistingMessagesAndDuplicatePositionsAreNeverCleanedAsFreshCopies() {
        assertFalse(allowed(visible = listOf("original", "new2")))
        assertFalse(allowed(visible = listOf("new1", "new1")))
        assertFalse(allowed(recovered = setOf("original", "new2")))
    }
    @Test fun partialOrDifferentViewsCannotEstablishTheWholeListBoundary() {
        assertFalse(allowed(top = false))
        assertFalse(allowed(bottom = false))
        assertFalse(allowed(visible = listOf("new1")))
        assertFalse(allowed(visible = listOf("new1", "new2", "new3")))
        assertFalse(allowed(recovered = setOf("new1", "hidden")))
    }
}
