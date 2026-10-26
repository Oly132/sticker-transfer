package com.example.stickerpackbuilder

import android.graphics.*
import android.graphics.drawable.AnimatedImageDrawable
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ConversionTest {
    private val context = RuntimeEnvironment.getApplication()
    @Test fun fastTransferPreservesCompatibleWebpBytesAndAnimationTiming() {
        val converted = File(context.cacheDir, "compatible.webp")
        StickerConverter.convert(fixture("motion.gif"), converted)
        val copied = File(context.cacheDir, "fast-copy.webp")
        assertTrue(StickerConverter.convert(converted, copied, fastTransfer = true).animated)
        assertArrayEquals(converted.readBytes(), copied.readBytes())
        val fastGif = File(context.cacheDir, "fast-gif.webp")
        StickerConverter.convert(fixture("motion.gif"), fastGif, fastTransfer = true)
        StickerConverter.validate(fastGif, true)
        assertEquals(WebpContainer.parse(converted.readBytes()).frames.sumOf { it.duration }, WebpContainer.parse(fastGif.readBytes()).frames.sumOf { it.duration })
    }
    private fun fixture(name: String): File = File(context.cacheDir, name).apply {
        ConversionTest::class.java.classLoader!!.getResourceAsStream(name)!!.use { input -> outputStream().use { input.copyTo(it) } }
    }
    @Test fun preservesTransparencyAndAspectRatioWithinSizeLimit() {
        val source = File(context.cacheDir, "wide.png")
        val bitmap = Bitmap.createBitmap(800, 200, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.RED)
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        val out = File(context.cacheDir, "wide.webp")
        assertFalse(StickerConverter.convert(source, out).animated)
        assertTrue(out.length() <= StickerConverter.STATIC_LIMIT)
        val decoded = BitmapFactory.decodeFile(out.path)
        assertEquals(512, decoded.width); assertEquals(512, decoded.height)
        assertEquals(0, Color.alpha(decoded.getPixel(256, 30)))
        assertTrue(Color.red(decoded.getPixel(256, 256)) > 230)
        decoded.recycle()
        val icon = File(context.cacheDir, "tray.webp")
        StickerConverter.buildTrayIcon(out, icon)
        StickerConverter.validateIcon(icon)
    }
    @Test fun convertsGifToAnActualAnimatedWebp() {
        val out = File(context.cacheDir, "gif.webp")
        assertTrue(StickerConverter.convert(fixture("motion.gif"), out).animated)
        StickerConverter.validate(out, true)
        val info = WebpContainer.parse(out.readBytes())
        assertEquals(3, info.frames.size)
        assertEquals(listOf(120, 180, 240), info.frames.map { it.duration })
        File("../../../work/converted-gif.webp").apply { parentFile.mkdirs(); writeBytes(out.readBytes()) }
        assertTrue(ImageDecoder.decodeDrawable(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(out.readBytes()))) is AnimatedImageDrawable)
        StickerConverter.openFrames(out)!!.use { frames ->
            val first = frames.next(); assertTrue(Color.red(first.getPixel(256, 256)) > 230); first.recycle()
            val second = frames.next(); assertTrue(Color.green(second.getPixel(256, 256)) > 100); second.recycle()
            val third = frames.next(); assertTrue(Color.blue(third.getPixel(256, 256)) > 230); third.recycle()
        }
    }
    @Test fun convertsAnimatedWebpAndKeepsAlpha() {
        val out = File(context.cacheDir, "motion.webp")
        assertTrue(StickerConverter.convert(fixture("motion-source.webp"), out).animated)
        StickerConverter.validate(out, true)
        File("../../../work/converted-webp.webp").apply { parentFile.mkdirs(); writeBytes(out.readBytes()) }
        assertTrue(ImageDecoder.decodeDrawable(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(out.readBytes()))) is AnimatedImageDrawable)
        StickerConverter.openFrames(out)!!.use { frames ->
            repeat(frames.count) {
                val bitmap = frames.next()
                assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)))
                bitmap.recycle()
            }
        }
    }
    @Test fun rejectsLongAnimationAndInvalidImagesInsteadOfClaimingSuccess() {
        val invalid = File(context.cacheDir, "invalid").apply { writeText("not an image") }
        assertThrows(IllegalArgumentException::class.java) { StickerConverter.convert(invalid, File(context.cacheDir, "invalid.webp")) }
        assertThrows(IllegalArgumentException::class.java) {
            StickerConverter.convert(fixture("long.gif"), File(context.cacheDir, "long.webp"))
        }
    }
    @Test fun reconstructsCroppedAnimationFramesAndDisposal() {
        val source = fixture("partial-source.webp")
        assertTrue(WebpContainer.parse(source.readBytes()).frames.any { it.x > 0 || it.y > 0 })
        val out = File(context.cacheDir, "partial.webp")
        assertTrue(StickerConverter.convert(source, out).animated)
        StickerConverter.openFrames(out)!!.use { frames ->
            val channels = listOf(0, 1, 2)
            val positions = listOf(96, 256, 416)
            repeat(3) { index ->
                val frame = frames.next()
                val pixel = frame.getPixel(positions[index], 192)
                val channel = when(channels[index]) { 0 -> Color.red(pixel); 1 -> Color.green(pixel); else -> Color.blue(pixel) }
                assertTrue(channel > 100)
                if (index > 0) assertEquals(0, Color.alpha(frame.getPixel(positions[index - 1], 192)))
                frame.recycle()
            }
        }
    }
    @Test fun validatesTimingAndRiffBounds() {
        val fixture = fixture("motion-source.webp").readBytes()
        val damaged = fixture.copyOf().apply { this[4] = 0 }
        assertThrows(IllegalArgumentException::class.java) { WebpContainer.parse(damaged) }
        val image = Bitmap.createBitmap(512,512, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val stream = java.io.ByteArrayOutputStream()
        @Suppress("DEPRECATION") image.compress(Bitmap.CompressFormat.WEBP, 90, stream)
        image.recycle()
        val bad = File(context.cacheDir, "bad-timing.webp")
        bad.writeBytes(WebpContainer.riff(WebpContainer.animationHeader() +
            WebpContainer.animationFrame(stream.toByteArray(), 6000) +
            WebpContainer.animationFrame(stream.toByteArray(), 6000)))
        assertThrows(IllegalArgumentException::class.java) { StickerConverter.validate(bad, true) }
    }
    @Test fun detailedAnimationFitsByReducingFrameRateWithoutChangingLength() {
        val source = File(context.cacheDir, "detailed-source.webp")
        val random = java.util.Random(812)
        val body = java.io.ByteArrayOutputStream().apply { write(WebpContainer.animationHeader(240, 240)) }
        repeat(65) {
            val bitmap = Bitmap.createBitmap(240, 240, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(240 * 240) { Color.rgb(random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
            bitmap.setPixels(pixels, 0, 240, 0, 0, 240, 240)
            val encoded = java.io.ByteArrayOutputStream()
            @Suppress("DEPRECATION") bitmap.compress(Bitmap.CompressFormat.WEBP, 90, encoded)
            bitmap.recycle()
            body.write(WebpContainer.animationFrame(encoded.toByteArray(), 60))
        }
        source.writeBytes(WebpContainer.riff(body.toByteArray()))
        val out = File(context.cacheDir, "detailed.webp")
        assertTrue(StickerConverter.convert(source, out).animated)
        StickerConverter.validate(out, true)
        val result = WebpContainer.parse(out.readBytes())
        assertTrue(result.frames.size in 2 until 65)
        assertEquals(3900, result.frames.sumOf { it.duration })
        assertTrue(ImageDecoder.decodeDrawable(ImageDecoder.createSource(java.nio.ByteBuffer.wrap(out.readBytes()))) is AnimatedImageDrawable)
    }
}

