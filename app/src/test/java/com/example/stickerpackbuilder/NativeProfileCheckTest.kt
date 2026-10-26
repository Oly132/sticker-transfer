package com.example.stickerpackbuilder

import com.example.stickerpackbuilder.sync.NativeProfileCheck
import org.junit.Assert.*
import org.junit.Test

class NativeProfileCheckTest {
    @Test fun publicProfileWithMatchingUsernameIsNeverSufficient() {
        assertFalse(NativeProfileCheck.verified(listOf("@test.account", "Follow", "Message"), "test.account"))
    }
    @Test fun currentOwnerLayoutWithoutEditButtonCanBeVerified() {
        assertTrue(NativeProfileCheck.verified(listOf("", "@test.account", "Create a Story", "Private videos"), "test.account"))
    }
    @Test fun differentAccountOrDisplayNameCannotPass() {
        assertFalse(NativeProfileCheck.verified(listOf("@other.account", "Edit profile"), "test.account"))
        assertFalse(NativeProfileCheck.verified(listOf("Display name", "Edit profile"), "test.account"))
    }
    @Test fun formattingAndCaseDoNotCauseFalseFailures() {
        assertTrue(NativeProfileCheck.verified(listOf(" @Test.Account ", "Edit  profile"), "test.account"))
        assertTrue(NativeProfileCheck.isProfileTab("Profile, selected"))
        assertFalse(NativeProfileCheck.isProfileTab("Profile photo"))
        assertFalse(NativeProfileCheck.isProfileTab("Edit profile"))
    }
}
