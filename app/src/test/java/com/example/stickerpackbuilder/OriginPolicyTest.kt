package com.example.stickerpackbuilder

import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress

class OriginPolicyTest {
    @Test fun rejectsSpoofedOriginsAndUnsafeSchemes() {
        assertTrue(OriginPolicy.isTikTokPage("https://www.tiktok.com/messages"))
        assertTrue(OriginPolicy.isStickerUrl("https://p16.tiktokcdn.com/sticker.webp"))
        listOf("http://www.tiktok.com", "https://tiktok.com.evil.test", "https://evil-tiktok.com",
            "https://user@www.tiktok.com", "file:///tmp/a", "https://www.tiktok.com:8443",
            "https://www.tiktok.com./messages").forEach { assertFalse(it, OriginPolicy.isTikTokPage(it)) }
        assertFalse(OriginPolicy.isStickerUrl("https://127.0.0.1/a"))
        assertFalse(OriginPolicy.isStickerUrl("https://example.com/a"))
    }
    @Test fun blocksLocalAndPrivateNetworkDestinations() {
        listOf("127.0.0.1", "0.0.0.0", "10.0.0.1", "192.168.1.1", "169.254.0.1",
            "100.64.1.2", "172.16.0.1", "::1", "fc00::1", "fe80::1").forEach {
            assertFalse(it, OriginPolicy.isPublicAddress(InetAddress.getByName(it)))
        }
        assertTrue(OriginPolicy.isPublicAddress(InetAddress.getByName("8.8.8.8")))
    }
}

