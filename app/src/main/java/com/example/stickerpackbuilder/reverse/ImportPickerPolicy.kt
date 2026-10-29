package com.example.stickerpackbuilder.reverse

internal object ImportPickerPolicy {
    enum class Action { ALBUM, CREATE, SAVED_TAB, OPEN_PICKER, WAIT }
    fun action(createScreen:Boolean,savedGrid:Boolean,tabs:Boolean,pickerButton:Boolean)=when {
        createScreen -> Action.ALBUM
        savedGrid -> Action.CREATE
        tabs -> Action.SAVED_TAB
        pickerButton -> Action.OPEN_PICKER
        else -> Action.WAIT
    }
}
