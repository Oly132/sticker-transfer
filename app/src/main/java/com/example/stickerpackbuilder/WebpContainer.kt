package com.example.stickerpackbuilder

import java.io.ByteArrayOutputStream

/** RIFF container handling. Frame compression itself is Android's bitmap codec. */
object WebpContainer {
    data class Chunk(val tag: String, val data: ByteArray)
    data class Info(val width: Int, val height: Int, val frames: List<Frame>, val background: Int)
    data class Frame(val x: Int, val y: Int, val width: Int, val height: Int,
        val duration: Int, val dispose: Boolean, val blend: Boolean, val payload: ByteArray)
    fun uint(data: ByteArray, offset: Int, count: Int): Int {
        require(offset >= 0 && offset + count <= data.size) { "Incomplete image" }
        var result = 0
        for (i in 0 until count) result = result or ((data[offset + i].toInt() and 255) shl (8 * i))
        return result
    }
    fun little(value: Int, count: Int) = ByteArray(count) { (value ushr (it * 8)).toByte() }
    fun chunks(bytes: ByteArray, start: Int = 12): List<Chunk> {
        val list = mutableListOf<Chunk>()
        var p = start
        while (p < bytes.size) {
            require(p + 8 <= bytes.size) { "Incomplete WebP chunk" }
            val size = uint(bytes, p + 4, 4)
            require(size >= 0 && size <= bytes.size - p - 8) { "Invalid WebP chunk size" }
            list.add(Chunk(String(bytes, p, 4, Charsets.US_ASCII), bytes.copyOfRange(p + 8, p + 8 + size)))
            p += 8 + size + (size and 1)
        }
        require(p == bytes.size) { "Incomplete WebP padding" }
        return list
    }
    fun isWebp(bytes: ByteArray) = bytes.size >= 12 &&
        String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" &&
        String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"
    fun parse(bytes: ByteArray): Info {
        require(isWebp(bytes) && uint(bytes, 4, 4) == bytes.size - 8) { "Invalid WebP container" }
        val cs = chunks(bytes)
        val extended = cs.firstOrNull { it.tag == "VP8X" }
        val dims = if (extended != null) {
            require(extended.data.size == 10)
            Pair(uint(extended.data, 4, 3) + 1, uint(extended.data, 7, 3) + 1)
        } else {
            val image = cs.firstOrNull { it.tag == "VP8 " || it.tag == "VP8L" }
                ?: error("Missing WebP image")
            if (image.tag == "VP8L") {
                require(image.data.size >= 5 && image.data[0] == 0x2f.toByte())
                val bits = uint(image.data, 1, 4)
                Pair((bits and 0x3fff) + 1, ((bits ushr 14) and 0x3fff) + 1)
            } else {
                require(image.data.size >= 10 &&
                    image.data.sliceArray(3..5).contentEquals(byteArrayOf(0x9d.toByte(), 1, 0x2a)))
                Pair(uint(image.data, 6, 2) and 0x3fff, uint(image.data, 8, 2) and 0x3fff)
            }
        }
        require(dims.first in 1..2048 && dims.second in 1..2048) { "Image is too large (maximum 2048 pixels per side)" }
        val anim = cs.firstOrNull { it.tag == "ANIM" }
        val frames = cs.filter { it.tag == "ANMF" }.map {
            val d = it.data
            require(d.size >= 24) { "Incomplete animation frame" }
            val f = Frame(uint(d, 0, 3) * 2, uint(d, 3, 3) * 2,
                uint(d, 6, 3) + 1, uint(d, 9, 3) + 1, uint(d, 12, 3),
                uint(d, 15, 1) and 1 != 0, uint(d, 15, 1) and 2 == 0, d.copyOfRange(16, d.size))
            require(f.x.toLong() + f.width <= dims.first && f.y.toLong() + f.height <= dims.second)
            val payload = chunks(f.payload, 0)
            require(payload.count { c -> c.tag == "VP8 " || c.tag == "VP8L" } == 1)
            f
        }
        val animated = extended != null && uint(extended.data, 0, 1) and 2 != 0
        require(animated == frames.isNotEmpty() && (frames.isEmpty() || anim?.data?.size == 6)) {
            "Invalid animation metadata"
        }
        require(frames.size <= 300) { "Animation has too many frames (maximum 300)" }
        return Info(dims.first, dims.second, frames, anim?.let { uint(it.data, 0, 4) } ?: 0)
    }
    fun chunk(tag: String, data: ByteArray) = ByteArrayOutputStream().apply {
        write(tag.toByteArray(Charsets.US_ASCII)); write(little(data.size, 4)); write(data)
        if (data.size and 1 != 0) write(0)
    }.toByteArray()
    fun riff(body: ByteArray) = ByteArrayOutputStream().apply {
        write("RIFF".toByteArray()); write(little(body.size + 4, 4)); write("WEBP".toByteArray()); write(body)
    }.toByteArray()
    fun extended(width: Int, height: Int, flags: Int) =
        chunk("VP8X", byteArrayOf(flags.toByte(), 0, 0, 0) + little(width - 1, 3) + little(height - 1, 3))
    fun standalone(frame: Frame): ByteArray =
        riff(extended(frame.width, frame.height, 16) + frame.payload)
    fun animationHeader(width: Int = 512, height: Int = 512) = extended(width, height, 18) + chunk("ANIM", ByteArray(6))
    fun animationFrame(encoded: ByteArray, duration: Int): ByteArray {
        require(duration in 8..10000)
        val dimensions = parse(encoded)
        val imageChunks = chunks(encoded).filter { it.tag in listOf("ALPH", "VP8 ", "VP8L") }
        require(imageChunks.count { it.tag == "VP8 " || it.tag == "VP8L" } == 1)
        val payload = ByteArrayOutputStream().apply {
            imageChunks.forEach { write(chunk(it.tag, it.data)) }
        }.toByteArray()
        // Full canvas replacement; independent frames preserve transparent pixels.
        return chunk("ANMF", ByteArray(6) + little(dimensions.width - 1, 3) + little(dimensions.height - 1, 3) +
            little(duration, 3) + byteArrayOf(2) + payload)
    }
}

