package com.example.stickerpackbuilder.sync

import androidx.lifecycle.MutableLiveData

object TransferState {
    enum class Phase { IDLE, VERIFYING, COPYING, COLLECTING, DELIVERING, CLEANING, COMPLETE, ERROR, CANCELLED }
    data class Status(val phase: Phase = Phase.IDLE, val message: String = "Ready", val copied: Int = 0, val delivered: Int = 0)
    val status = MutableLiveData(Status())
    var selfId = ""
    var selfHandle = ""
    var selfName = ""
    var copied = 0
    fun update(phase: Phase, message: String, delivered: Int = 0) {
        android.util.Log.i("StickerTransfer", "$phase: $message")
        status.postValue(Status(phase, message, copied, delivered))
    }
}
