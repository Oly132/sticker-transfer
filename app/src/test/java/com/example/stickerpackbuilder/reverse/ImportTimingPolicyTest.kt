package com.example.stickerpackbuilder.reverse

import org.junit.Assert.*
import org.junit.Test

class ImportTimingPolicyTest {
    @Test fun aNewReadyControlCanActImmediatelyWithoutRepeatingThePreviousTap() {
        val policy=ImportTimingPolicy()
        assertTrue(policy.allow("open-albums",1000))
        assertTrue(policy.allow("choose-exact-album",1035))
        assertFalse(policy.allow("open-albums",1050))
        assertFalse(policy.allow("choose-exact-album",1100))
        assertTrue(policy.allow("choose-exact-album",1700))
        policy.reset();assertTrue(policy.allow("choose-exact-album",1701))
    }
    @Test fun anAlbumIndexedDuringThePreviousUploadNeedsNoExtraFixedWait() {
        assertTrue(ImportTimingPolicy.fileReady(5000,1000,true))
        assertFalse(ImportTimingPolicy.fileReady(1050,1000,true))
        assertFalse(ImportTimingPolicy.fileReady(5000,0,false))
        assertTrue(ImportTimingPolicy.fileReady(1200,1000,false))
    }
}
