package com.example.stickerpackbuilder.reverse

/** A throttled native action must never turn into an unthrottled fallback tap. */
internal class ImportActivation(private val timing: ImportTimingPolicy) {
    fun perform(key:String,now:Long,native:()->Boolean,gesture:()->Boolean):Boolean {
        if(!timing.allow("activate:$key",now))return false
        return native() || gesture()
    }
}
