package com.example.stickerpackbuilder

import android.graphics.*
import android.os.Build
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

object StickerConverter {
    const val STATIC_LIMIT = 100 * 1024
    const val ANIMATED_LIMIT = 500 * 1024
    const val ICON_LIMIT = 50 * 1024
    data class Converted(val animated: Boolean)
    interface Frames : AutoCloseable {
        val count: Int
        val durations: List<Int>
        fun next(): Bitmap
    }
    private class GifFrames(bytes: ByteArray) : Frames {
        private val header = GifHeaderParser().setData(bytes).parseHeader()
        init {
            require(header.status == GifDecoder.STATUS_OK && header.numFrames in 1..300) {
                "GIF is damaged or has more than 300 frames"
            }
            require(header.width in 1..2048 && header.height in 1..2048) { "GIF dimensions exceed 2048 pixels" }
        }
        private val decoder = StandardGifDecoder(object : GifDecoder.BitmapProvider {
            override fun obtain(w: Int, h: Int, config: Bitmap.Config) = Bitmap.createBitmap(w, h, config)
            override fun release(bitmap: Bitmap) = bitmap.recycle()
            override fun obtainByteArray(size: Int) = ByteArray(size)
            override fun release(bytes: ByteArray) {}
            override fun obtainIntArray(size: Int) = IntArray(size)
            override fun release(array: IntArray) {}
        }, header, ByteBuffer.wrap(bytes))
        override val count = header.numFrames
        override val durations = (0 until count).map { decoder.getDelay(it).coerceAtLeast(8) }
        override fun next(): Bitmap {
            decoder.advance()
            val frame = decoder.nextFrame ?: error("Couldn't decode GIF frame")
            require(decoder.status == GifDecoder.STATUS_OK) { "GIF has incomplete frame data" }
            return frame
        }
        override fun close() = decoder.clear()
    }
    private class WebpFrames(bytes: ByteArray) : Frames {
        private val info = WebpContainer.parse(bytes)
        private val bitmap = Bitmap.createBitmap(info.width, info.height, Bitmap.Config.ARGB_8888)
        private val canvas = Canvas(bitmap)
        private var index = 0
        init { canvas.drawColor(info.background, PorterDuff.Mode.SRC) }
        override val count = info.frames.size
        override val durations = info.frames.map { it.duration.coerceAtLeast(8) }
        override fun next(): Bitmap {
            if (index > 0) {
                val prev = info.frames[index - 1]
                if (prev.dispose) canvas.drawRect(prev.x.toFloat(), prev.y.toFloat(),
                    (prev.x + prev.width).toFloat(), (prev.y + prev.height).toFloat(),
                    Paint().apply { color = info.background; xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) })
            }
            val frame = info.frames[index++]
            val standalone = WebpContainer.standalone(frame)
            val part = BitmapFactory.decodeByteArray(standalone, 0, standalone.size)
                ?: error("Couldn't decode WebP frame")
            require(part.width == frame.width && part.height == frame.height) { "Invalid frame dimensions" }
            canvas.drawBitmap(part, frame.x.toFloat(), frame.y.toFloat(), Paint().apply {
                if (!frame.blend) xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC)
            })
            part.recycle()
            return bitmap.copy(Bitmap.Config.ARGB_8888, false)
        }
        override fun close() = bitmap.recycle()
    }
    private fun frames(bytes: ByteArray): Frames? {
        if (bytes.size >= 6 && String(bytes, 0, 3, Charsets.US_ASCII) == "GIF") return GifFrames(bytes)
        if (WebpContainer.isWebp(bytes) && WebpContainer.parse(bytes).frames.isNotEmpty()) return WebpFrames(bytes)
        // APNG must not silently turn into a still sticker.
        if (bytes.size >= 8 && bytes.take(8).toByteArray().contentEquals(
                byteArrayOf(137.toByte(),80,78,71,13,10,26,10))) {
            var p = 8
            while (p + 12 <= bytes.size) {
                val length = ByteBuffer.wrap(bytes, p, 4).int
                require(length >= 0 && length <= bytes.size - p - 12) { "Incomplete PNG" }
                require(String(bytes, p + 4, 4, Charsets.US_ASCII) != "acTL") {
                    "APNG isn't supported yet. Use GIF or animated WebP instead."
                }
                p += length + 12
            }
        }
        return null
    }
    fun openFrames(file: File): Frames? = frames(file.readBytes())
    fun convert(src: File, out: File, fastTransfer: Boolean = false): Converted {
        require(src.length() in 1..8L * 1024 * 1024) { "Choose an image under 8 MB" }
        val bytes = src.readBytes()
        if (fastTransfer && WebpContainer.isWebp(bytes)) {
            val info = WebpContainer.parse(bytes)
            val animated = info.frames.size > 1
            if (info.width == 512 && info.height == 512 && bytes.size <= (if (animated) ANIMATED_LIMIT else STATIC_LIMIT) &&
                (info.frames.isEmpty() || animated && info.frames.all { it.duration >= 8 } && info.frames.sumOf { it.duration } <= 10000)) {
                if (animated) info.frames.forEach { frame ->
                    val encoded = WebpContainer.standalone(frame)
                    val bitmap = BitmapFactory.decodeByteArray(encoded, 0, encoded.size) ?: error("Sticker is damaged")
                    try { require(bitmap.width == frame.width && bitmap.height == frame.height) } finally { bitmap.recycle() }
                }
                out.writeBytes(bytes); validate(out, animated); return Converted(animated)
            }
        }
        val source = frames(bytes)
        if (source != null) source.use {
            if (it.count > 1) {
                val durations = it.durations
                require(durations.sum() <= 10000) { "Animation is longer than 10 seconds. Choose a shorter sticker." }
                // Re-create the decoder for each quality pass; never retain a full animation in memory.
                val passes = if (fastTransfer) listOf(Triple(75, 100, 512), Triple(55, 125, 512), Triple(40, 150, 256),
                    Triple(25, 200, 256), Triple(15, 250, 128), Triple(15, 400, 128)) else listOf(Triple(90, 0, 512), Triple(75, 0, 512), Triple(60, 0, 512),
                    Triple(40, 0, 512), Triple(25, 0, 512), Triple(60, 100, 512), Triple(40, 150, 512),
                    Triple(25, 200, 512), Triple(15, 250, 512), Triple(25, 250, 256), Triple(25, 250, 128), Triple(15, 400, 128))
                for ((pass, settings) in passes.withIndex()) {
                    val (quality, minimumDuration, detailSize) = settings
                    val groups = linkedMapOf<Int, Int>()
                    var start = 0
                    while (start < durations.size) {
                        var end = start; var duration = 0
                        do { duration += durations[end++] } while (end < durations.size && duration < minimumDuration.coerceAtMost(durations.sum() / 2))
                        groups[start] = duration; start = end
                    }
                    val body = ByteArrayOutputStream()
                    body.write(WebpContainer.animationHeader())
                    val decoder = if (pass == 0) it else frames(bytes)!!
                    var encodedCount = 0
                    try {
                        repeat(decoder.count) { index ->
                            if (Thread.currentThread().isInterrupted) error("Import cancelled")
                            val frame = decoder.next()
                            try {
                                // Decode every frame to preserve blending/disposal, but merge display
                                // intervals when detail cannot fit the size limit at the original rate.
                                groups[index]?.let { duration ->
                                    val detail = if (detailSize == 512) frame else fitToSquare(frame, detailSize)
                                    val square = try { fitToSquare(detail, 512) } finally { if (detail !== frame) detail.recycle() }
                                    try { body.write(WebpContainer.animationFrame(encode(square, quality), duration)) }
                                    finally { square.recycle() }
                                    encodedCount++
                                }
                            } finally { frame.recycle() }
                            if (body.size() > ANIMATED_LIMIT) throw TooLarge()
                            // Avoid encoding a long high-detail sequence when an eight-frame
                            // sample is already well beyond the budget for this pass.
                            if (encodedCount >= 8 && encodedCount < groups.size &&
                                body.size().toLong() * groups.size > ANIMATED_LIMIT.toLong() * encodedCount * 14 / 10) throw TooLarge()
                        }
                        val result = WebpContainer.riff(body.toByteArray())
                        if (result.size <= ANIMATED_LIMIT) {
                            out.writeBytes(result)
                            validate(out, true)
                            return Converted(true)
                        }
                    } catch (_: TooLarge) {
                        // Try the next quality level.
                    } finally { if (decoder !== it) decoder.close() }
                }
                error("Animation is too detailed to fit 500 KB. Choose a simpler or shorter sticker.")
            }
            val bitmap = it.next()
            val square = fitToSquare(bitmap, 512)
            bitmap.recycle()
            try { out.writeBytes(compressUnder(square, STATIC_LIMIT)) } finally { square.recycle() }
            validate(out, false)
            return Converted(false)
        }
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        require(opts.outWidth in 1..8192 && opts.outHeight in 1..8192) { "Unsupported, damaged, or oversized image" }
        var sample = 1
        while (maxOf(opts.outWidth, opts.outHeight) / sample > 2048) sample *= 2
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample }) ?: error("Couldn't decode the image")
        val square = fitToSquare(bitmap, 512)
        bitmap.recycle()
        try { out.writeBytes(compressUnder(square, STATIC_LIMIT)) } finally { square.recycle() }
        validate(out, false)
        return Converted(false)
    }
    private class TooLarge : RuntimeException()
    fun firstFrame(file: File): Bitmap {
        val bytes = file.readBytes()
        val source = frames(bytes)
        return if (source != null) source.use { it.next() } else
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("Couldn't load sticker")
    }
    fun buildTrayIcon(sticker: File, out: File) {
        val bitmap = firstFrame(sticker)
        val icon = fitToSquare(bitmap, 96)
        bitmap.recycle()
        try { out.writeBytes(compressUnder(icon, ICON_LIMIT)) } finally { icon.recycle() }
        validateIcon(out)
    }
    fun validate(file: File, animated: Boolean) {
        require(file.isFile && file.length() in 1..(if (animated) ANIMATED_LIMIT else STATIC_LIMIT).toLong()) {
            "Sticker is missing or too large"
        }
        val info = WebpContainer.parse(file.readBytes())
        require(info.width == 512 && info.height == 512) { "Sticker must be 512 × 512" }
        require((info.frames.size > 1) == animated) { "Pack contains the wrong sticker type" }
        if (animated) require(info.frames.all { it.duration >= 8 } &&
            info.frames.sumOf { it.duration } <= 10000) { "Animation timing is invalid" }
        else {
            val bitmap = BitmapFactory.decodeFile(file.path) ?: error("Sticker is damaged")
            bitmap.recycle()
        }
    }
    fun validateIcon(file: File) {
        require(file.isFile && file.length() in 1..ICON_LIMIT.toLong()) { "Pack icon is missing or too large" }
        val info = WebpContainer.parse(file.readBytes())
        require(info.width == 96 && info.height == 96 && info.frames.isEmpty()) { "Pack icon is invalid" }
    }
    fun fitToSquare(src: Bitmap, size: Int): Bitmap {
        val ratio = size.toFloat() / maxOf(src.width, src.height)
        val w = src.width * ratio
        val h = src.height * ratio
        val result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(result).drawBitmap(src, null, RectF((size - w) / 2, (size - h) / 2,
            (size + w) / 2, (size + h) / 2), Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))
        return result
    }
    private fun compressUnder(bitmap: Bitmap, maxBytes: Int): ByteArray {
        for (quality in listOf(95, 85, 75, 60, 45, 30, 15)) {
            val bytes = encode(bitmap, quality)
            if (bytes.size <= maxBytes) return bytes
        }
        error("Image couldn't fit the sticker size limit")
    }
    @Suppress("DEPRECATION")
    private fun encode(bitmap: Bitmap, quality: Int): ByteArray = ByteArrayOutputStream().use {
        val format = if (Build.VERSION.SDK_INT >= 30) Bitmap.CompressFormat.WEBP_LOSSY else Bitmap.CompressFormat.WEBP
        check(bitmap.compress(format, quality, it)) { "Image encoder failed" }
        it.toByteArray()
    }
}

