package com.example.stickerpackbuilder.sync

/** A refreshed entire list may omit a previously deleted anchor. Never include a known old ID. */
object RecoveredStickerBoundary {
    fun completeFreshList(expected: Int, refreshed: Boolean, oldest: Boolean, atTop: Boolean, atBottom: Boolean,
                          visible: List<String>, recovered: Set<String>, baseline: Set<String>): Boolean =
        expected > 0 && refreshed && oldest && atTop && atBottom && visible.size == expected &&
            visible.toSet().size == expected && recovered.size == expected && visible.toSet() == recovered &&
            recovered.none { it in baseline }
}
