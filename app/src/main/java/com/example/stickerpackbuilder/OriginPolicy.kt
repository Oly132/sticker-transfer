package com.example.stickerpackbuilder

import java.net.URI
import java.net.InetAddress
import java.util.Locale

object OriginPolicy {
    private val imageDomains = listOf("tiktok.com", "tiktokcdn.com", "tiktokcdn-us.com",
        "tiktokcdn-eu.com", "byteoversea.com", "ibytedtos.com", "ibyteimg.com", "muscdn.com")
    private fun matches(host: String, domain: String) = host == domain || host.endsWith(".$domain")
    private fun host(url: String): String? = try {
        val uri = URI(url)
        if (uri.scheme != "https" || uri.rawUserInfo != null || uri.port !in listOf(-1, 443)) null
        else uri.host?.lowercase(Locale.ROOT)
    } catch (_: Exception) { null }
    fun isTikTokPage(url: String) = host(url)?.let { matches(it, "tiktok.com") } == true
    fun isStickerUrl(url: String) = url.length <= 4096 &&
        host(url)?.let { h -> imageDomains.any { matches(h, it) } } == true
    fun isPublicAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress) return false
        val b = address.address
        if (b.size == 4) {
            val a = b[0].toInt() and 255
            val c = b[1].toInt() and 255
            if (a == 0 || a >= 224 || (a == 100 && c in 64..127) ||
                (a == 192 && c == 0) || (a == 198 && c in 18..19)) return false
        } else if ((b[0].toInt() and 0xfe) == 0xfc) return false
        return true
    }
}
