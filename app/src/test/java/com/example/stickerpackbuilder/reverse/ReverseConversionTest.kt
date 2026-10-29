package com.example.stickerpackbuilder.reverse

import android.graphics.*
import com.example.stickerpackbuilder.StickerConverter
import com.example.stickerpackbuilder.WebpContainer
import com.bumptech.glide.gifdecoder.GifHeaderParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReverseConversionTest {
    @Test fun gifKeepsOpaqueBlackAndEvenTinyTransparentAreasSeparate() {
        val source=File(folder,"black.webp")
        val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLACK);setPixel(0,0,Color.TRANSPARENT) }
        val encoded=java.io.ByteArrayOutputStream().also{bitmap.compress(Bitmap.CompressFormat.WEBP,100,it)}.toByteArray();bitmap.recycle()
        val frame=WebpContainer.animationFrame(encoded,100)
        source.writeBytes(WebpContainer.riff(WebpContainer.animationHeader(64,64)+frame+frame))
        val prepared=StickerStager.prepare(source,File(folder,"black-gif"))
        StickerConverter.openFrames(prepared.file)!!.use { frames -> repeat(2) {
            val image=frames.next();assertEquals(255,Color.alpha(image.getPixel(32,32)));assertEquals(0,Color.alpha(image.getPixel(0,0)));image.recycle()
        } }
    }
    private val folder get()=RuntimeEnvironment.getApplication().cacheDir
    private fun fixture(name:String)=File(folder,name).apply { ReverseConversionTest::class.java.classLoader!!.getResourceAsStream(name)!!.use { input->outputStream().use{input.copyTo(it)} } }
    @Test fun staticPngKeepsPixelsAndTransparency() {
        val source=File(folder,"source.png")
        val bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888)
        bitmap.setPixel(32,32,Color.RED)
        source.outputStream().use{bitmap.compress(Bitmap.CompressFormat.PNG,100,it)};bitmap.recycle()
        val prepared=StickerStager.prepare(source,File(folder,"prepared"))
        assertFalse(prepared.animated)
        val decoded=BitmapFactory.decodeFile(prepared.file.path)
        assertEquals(Color.RED,decoded.getPixel(32,32));assertEquals(0,Color.alpha(decoded.getPixel(0,0)));decoded.recycle()
    }
    @Test fun animatedWebpBecomesRealMultiFrameGifWithTimingAndTransparency() {
        val source=fixture("motion-source.webp")
        val original=WebpContainer.parse(source.readBytes())
        val prepared=StickerStager.prepare(source,File(folder,"animated"))
        assertTrue(prepared.animated)
        val header=GifHeaderParser().setData(prepared.file.readBytes()).parseHeader()
        assertEquals(original.frames.size,header.numFrames)
        StickerConverter.openFrames(prepared.file)!!.use { frames ->
            assertEquals(original.frames.map{ ((it.duration/10.0).toInt()*10).coerceAtLeast(10) },frames.durations)
            repeat(frames.count) { val frame=frames.next();assertEquals(0,Color.alpha(frame.getPixel(0,0)));frame.recycle() }
        }
    }
    @Test fun longPausesArePreservedInsteadOfFlatteningOrSpeedingUpTheAnimation() {
        val prepared=StickerStager.prepare(fixture("long.gif"),File(folder,"long-pauses"))
        assertTrue(prepared.animated)
        StickerConverter.openFrames(prepared.file)!!.use { frames ->
            assertEquals(3,frames.count)
            assertEquals(listOf(5000,5000,5000),frames.durations)
            repeat(frames.count){frames.next().recycle()}
        }
    }
    @Test fun excessiveTotalDurationIsStillBounded() {
        val bytes=fixture("long.gif").readBytes()
        for(index in 0 until bytes.size-7) {
            if(bytes[index]==0x21.toByte()&&bytes[index+1]==0xf9.toByte()&&bytes[index+2]==4.toByte()){
                // Three 25-second holds exceed the import duration bound.
                bytes[index+4]=0xc4.toByte();bytes[index+5]=9
            }
        }
        val source=File(folder,"over-sixty.gif").apply{writeBytes(bytes)}
        assertThrows(IllegalArgumentException::class.java){StickerStager.prepare(source,File(folder,"over-sixty"))}
    }
    @Test fun malformedOriginalCannotBeStagedAsAValidImage() {
        val source=File(folder,"bad.webp").apply{writeText("not an image")}
        assertThrows(IllegalArgumentException::class.java){StickerStager.prepare(source,File(folder,"bad-output"))}
    }
}
