package com.example.stickerpackbuilder.sync

internal object NativeChatNavigation {
    fun name(value: String) = value.replace(Regex("[\\u200e\\u200f\\u202a-\\u202e\\u2066-\\u2069]"), "").trim()
    fun matchesName(value: String, expected: String) = name(expected).isNotEmpty() && name(value) == name(expected)
    fun isRowName(text: String, description: String, id: String?, expected: String) =
        matchesName(text, expected) || (id == "com.zhiliaoapp.musically:id/user_name" && matchesName(description, expected))
    fun canOpenRow(insideConversationList: Boolean, ownProfile: Boolean, hasEditor: Boolean) =
        insideConversationList && !ownProfile && !hasEditor
}
