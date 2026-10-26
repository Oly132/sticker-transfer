package com.example.stickerpackbuilder

import com.example.stickerpackbuilder.sync.NativeChatNavigation
import org.junit.Assert.*
import org.junit.Test

class NativeChatNavigationTest {
    @Test fun avatarDescriptionDoesNotDuplicateTheActualConversationRow() {
        assertFalse(NativeChatNavigation.isRowName("", "My account", null, "My account"))
        assertTrue(NativeChatNavigation.isRowName("\u200eMy account", "", "com.zhiliaoapp.musically:id/user_name", "My account"))
    }
    @Test fun profileHeadingCannotBeTreatedAsAConversationRowDuringNavigation() {
        assertFalse(NativeChatNavigation.canOpenRow(false, true, false))
        assertFalse(NativeChatNavigation.canOpenRow(true, true, false))
        assertFalse(NativeChatNavigation.canOpenRow(false, false, false))
        assertTrue(NativeChatNavigation.canOpenRow(true, false, false))
    }
    @Test fun aChatHeadingMustNotBeTappedAgain() {
        assertFalse(NativeChatNavigation.canOpenRow(true, false, true))
    }
    @Test fun inboxDirectionMarkersDoNotHideTheSelfConversation() {
        assertTrue(NativeChatNavigation.matchesName("\u200eMy account", "My account"))
        assertTrue(NativeChatNavigation.matchesName("My account", "\u200eMy account"))
        assertFalse(NativeChatNavigation.matchesName("Other account", "My account"))
        assertFalse(NativeChatNavigation.matchesName("", ""))
    }
}
