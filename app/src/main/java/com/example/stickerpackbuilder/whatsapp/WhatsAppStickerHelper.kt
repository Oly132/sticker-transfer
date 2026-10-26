package com.example.stickerpackbuilder.whatsapp

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import com.example.stickerpackbuilder.PackRepository
import com.example.stickerpackbuilder.models.StickerPack
import com.google.android.material.dialog.MaterialAlertDialogBuilder

object WhatsAppStickerHelper {
    const val ADD_STICKER_REQUEST_CODE = 200
    private val packages = listOf("com.whatsapp" to "WhatsApp", "com.whatsapp.w4b" to "WhatsApp Business")
    private fun showMessage(activity: Activity, message: String) {
        MaterialAlertDialogBuilder(activity).setTitle("Add sticker pack")
            .setMessage(message).setPositiveButton("OK", null).show()
    }
    private fun installed(activity: Activity) = packages.filter {
        try { activity.packageManager.getPackageInfo(it.first, PackageManager.GET_ACTIVITIES); true }
        catch (_: PackageManager.NameNotFoundException) { false }
    }
    fun isWhatsAppInstalled(activity: Activity) = installed(activity).isNotEmpty()
    fun addStickerPackToWhatsApp(activity: Activity, pack: StickerPack) {
        val choices = installed(activity)
        if (choices.isEmpty()) {
            showMessage(activity, "Install WhatsApp or WhatsApp Business, then return here to add your pack.")
            return
        }
        if (choices.size > 1) MaterialAlertDialogBuilder(activity).setTitle("Choose an app")
            .setItems(choices.map { it.second }.toTypedArray()) { _, which ->
                launch(activity, pack, choices[which].first)
            }.show()
        else launch(activity, pack, choices.first().first)
    }
    private fun launch(activity: Activity, pack: StickerPack, target: String) {
        try {
            val sealed = PackRepository.sealForExport(activity, pack.identifier)
            val intent = Intent("com.whatsapp.intent.action.ENABLE_STICKER_PACK").setPackage(target)
                .putExtra("sticker_pack_id", sealed.identifier)
                .putExtra("sticker_pack_authority", "${activity.packageName}.stickercontentprovider")
                .putExtra("sticker_pack_name", sealed.name)
            activity.startActivityForResult(intent, ADD_STICKER_REQUEST_CODE)
        } catch (e: Exception) { showMessage(activity, e.message ?: "Couldn't open WhatsApp. Try again.") }
    }
    fun handleResult(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != ADD_STICKER_REQUEST_CODE) return
        showMessage(activity, when {
            resultCode == Activity.RESULT_OK -> "Your pack is ready in WhatsApp."
            data?.getStringExtra("validation_error") != null ->
                "WhatsApp couldn't add this pack: ${data.getStringExtra("validation_error")}"
            else -> "The pack wasn't added. If it's already in WhatsApp, check your sticker tray."
        })
    }
}

