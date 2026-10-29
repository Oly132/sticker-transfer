package com.example.stickerpackbuilder.reverse

import org.junit.Assert.*
import org.junit.Test

class ImportActivationTest {
    @Test fun aThrottledAcceptedClickCannotFallThroughToAnotherTouch() {
        val activation=ImportActivation(ImportTimingPolicy())
        var clicks=0;var gestures=0
        fun attempt(time:Long)=activation.perform("album",time,{clicks++;true},{gestures++;true})
        assertTrue(attempt(1000));assertFalse(attempt(1100));assertFalse(attempt(1600))
        assertEquals(1,clicks);assertEquals(0,gestures)
        assertTrue(attempt(1700));assertEquals(2,clicks)
    }
    @Test fun anUnsupportedActionGetsOneFallbackAndANewControlDoesNotWait() {
        val activation=ImportActivation(ImportTimingPolicy())
        var gestures=0
        assertTrue(activation.perform("header",1000,{false},{gestures++;true}))
        assertFalse(activation.perform("header",1100,{false},{gestures++;true}))
        assertTrue(activation.perform("exact-row",1100,{true},{gestures++;true}))
        assertEquals(1,gestures)
    }
}
