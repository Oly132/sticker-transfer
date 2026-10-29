package com.example.stickerpackbuilder.reverse

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.example.stickerpackbuilder.sync.TransferActivity

/** Compatibility entry for older pending intents; directions now share one dashboard. */
class ReverseTransferActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState:Bundle?){
        super.onCreate(savedInstanceState)
        startActivity(Intent(this,TransferActivity::class.java)
            .putExtra(TransferActivity.DIRECTION_EXTRA,true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0,0)
    }
}
