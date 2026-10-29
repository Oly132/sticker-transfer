package com.example.stickerpackbuilder.sync

/** Only the Saved grid and rows below its heading can produce transfer taps. */
object SavedStickerSelection {
    // Keep each verified grid/tile pair together: never accept an image from a different pack.
    private val layouts = listOf("ssb" to "hfq", "sqo" to "hg3", "srt" to "hgv")
    val savedGridIds: List<String> = layouts.map { "com.zhiliaoapp.musically:id/${it.first}" }
    fun isSavedGrid(id: String?) = layouts.any { id == "com.zhiliaoapp.musically:id/${it.first}" }
    fun isTabRow(id: String?) = id in listOf("com.zhiliaoapp.musically:id/hgk", "com.zhiliaoapp.musically:id/hgx", "com.zhiliaoapp.musically:id/hhp")
    fun isPickerButton(id: String?) = id in listOf("com.zhiliaoapp.musically:id/kwq", "com.zhiliaoapp.musically:id/kve", "com.zhiliaoapp.musically:id/kw7")
    fun allowed(gridId: String?, itemId: String?, row: Int, headingRow: Int) =
        layouts.any { (grid, tile) -> gridId == "com.zhiliaoapp.musically:id/$grid" && itemId == "com.zhiliaoapp.musically:id/$tile" } &&
            headingRow >= 0 && row > headingRow
    fun key(row: Int, column: Int): String {
        require(row >= 0 && column >= 0) { "Invalid Saved sticker index" }
        return "$row,$column"
    }
}
