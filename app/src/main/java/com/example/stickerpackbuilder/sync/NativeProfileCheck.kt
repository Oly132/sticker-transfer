package com.example.stickerpackbuilder.sync

/** Identity is only accepted on an owner-only profile surface, never a public profile. */
internal object NativeProfileCheck {
    private fun clean(value: String) = value.trim().replace(Regex("\\s+"), " ")
    fun isOwnProfile(labels: List<String>): Boolean {
        val values = labels.map(::clean)
        return values.any { it.equals("Edit profile", true) } ||
            (values.any { it.equals("Create a Story", true) } && values.any { it.equals("Private videos", true) })
    }
    fun verified(labels: List<String>, handle: String) = isOwnProfile(labels) && labels.any {
        clean(it).removePrefix("@").equals(handle, true)
    }
    fun isProfileTab(value: String) = clean(value).matches(Regex("Profile(?:,.*)?", RegexOption.IGNORE_CASE))
}
