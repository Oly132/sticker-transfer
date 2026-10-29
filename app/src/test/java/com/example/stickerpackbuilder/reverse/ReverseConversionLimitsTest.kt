package com.example.stickerpackbuilder.reverse

import org.junit.Assert.*
import org.junit.Test

class ReverseConversionLimitsTest {
    @Test fun longPausesInTheActualThreeFrameOriginalArePreserved() {
        assertTrue(ReverseConversionLimits.animationAllowed(3,listOf(293,879,13478)))
        assertTrue(ReverseConversionLimits.animationAllowed(2,listOf(30000,30000)))
        assertFalse(ReverseConversionLimits.animationAllowed(2,listOf(30000,30001)))
    }
    @Test fun oversizedOrInvalidTimingCannotOverflowIntoAnAcceptedAnimation() {
        assertFalse(ReverseConversionLimits.animationAllowed(300,List(300){Int.MAX_VALUE}))
        assertFalse(ReverseConversionLimits.animationAllowed(301,List(301){8}))
        assertFalse(ReverseConversionLimits.animationAllowed(3,listOf(100,100)))
        assertFalse(ReverseConversionLimits.animationAllowed(2,listOf(0,100)))
    }
}
