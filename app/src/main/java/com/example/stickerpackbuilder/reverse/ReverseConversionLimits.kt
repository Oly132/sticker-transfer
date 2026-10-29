package com.example.stickerpackbuilder.reverse

/** Bound work by frame count and bytes; long pauses do not require more frames. */
internal object ReverseConversionLimits {
    const val MAX_DURATION_MS=60_000
    const val MAX_FRAMES=300
    fun animationAllowed(frameCount:Int,durations:List<Int>)=
        frameCount in 2..MAX_FRAMES&&durations.size==frameCount&&
            durations.all{it in 8..MAX_DURATION_MS}&&durations.sumOf{it.toLong()}<=MAX_DURATION_MS
}
