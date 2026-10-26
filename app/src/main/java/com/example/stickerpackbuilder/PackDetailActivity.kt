package com.example.stickerpackbuilder

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.stickerpackbuilder.models.StickerPack
import com.example.stickerpackbuilder.ui.Screen
import com.example.stickerpackbuilder.ui.StickerGridAdapter
import com.example.stickerpackbuilder.ui.previewSticker
import com.example.stickerpackbuilder.whatsapp.WhatsAppStickerHelper
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File

class PackDetailActivity : Screen() {
    private var pack: StickerPack? = null
    private val identifier by lazy { intent.getStringExtra("pack") ?: "" }
    private lateinit var heading: TextView
    private lateinit var description: TextView
    private lateinit var grid: RecyclerView
    private lateinit var add: MaterialButton
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val bar = toolbar("Sticker pack")
        bar.menu.add("Rename").setOnMenuItemClickListener { rename(); true }
        bar.menu.add("Delete pack").setOnMenuItemClickListener { delete(); true }
        val header = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(16)) }
        heading = title(""); description = text("", 14f, true)
        header.addView(heading); space(header, 8); header.addView(description)
        header.addView(text("Tap to preview. Hold to remove a sticker.", 13f, true))
        column.addView(header)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@PackDetailActivity, 3); setPadding(dp(14), 0, dp(14), 0)
        }
        column.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        add = button("Add to WhatsApp") { pack?.let { WhatsAppStickerHelper.addStickerPackToWhatsApp(this, it) } }
        column.addView(button("Add waiting stickers", true) {
            if ((pack?.stickers?.size ?: 30) >= 30) message("This pack already has 30 stickers.")
            else startActivity(Intent(this, ReviewActivity::class.java).putExtra("destination", identifier))
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(20), dp(8), dp(20), 0) })
        column.addView(add, LinearLayout.LayoutParams(-1, -2).apply { setMargins(dp(20), dp(12), dp(20), dp(18)) })
    }
    override fun onResume() { super.onResume(); refresh() }
    private fun refresh() {
        task({ PackRepository.loadPacks(this).firstOrNull { it.identifier == identifier } }) { loaded ->
            if (loaded == null) { finish(); return@task }
            pack = loaded
            heading.text = loaded.name
            description.text = "${loaded.stickers.size}/30 ${if (loaded.animated) "animated" else "still"} stickers" +
                if (loaded.isComplete()) " · Ready for WhatsApp" else " · Needs at least 3 stickers"
            add.isEnabled = loaded.isComplete()
            val files = loaded.stickers.map { File(PackRepository.packDir(this, loaded.identifier), it.fileName) }
            grid.adapter = StickerGridAdapter(files, emptySet(), lifecycleScope, loaded.animated,
                { name -> previewSticker(File(PackRepository.packDir(this, loaded.identifier), name), loaded.animated) },
                { file -> remove(file.name) }, selectable = false)
        }
    }
    private fun rename() {
        val current = pack ?: return
        val layout = TextInputLayout(this).apply { hint = "Pack name"; setPadding(dp(24), dp(12), dp(24), 0) }
        val input = TextInputEditText(layout.context).apply {
            setSingleLine(); filters = arrayOf(InputFilter.LengthFilter(128)); setText(current.name)
        }
        layout.addView(input)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Rename pack").setView(layout)
            .setNegativeButton("Cancel", null).setPositiveButton("Save", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) { layout.error = "Enter a name"; return@setOnClickListener }
                dialog.dismiss()
                task({ PackRepository.rename(this, identifier, name) }) { refresh() }
            }
        }
        dialog.show()
    }
    private fun remove(name: String) {
        val current = pack ?: return
        val extra = if (current.stickers.size <= 3) " The pack will need at least 3 stickers before it can be added again." else ""
        MaterialAlertDialogBuilder(this).setTitle("Remove this sticker?")
            .setMessage("This updates the pack in this app. Add the updated pack to WhatsApp again afterwards.$extra")
            .setNegativeButton("Keep", null).setPositiveButton("Remove") { _, _ ->
                task({ PackRepository.removeSticker(this, identifier, name) }) { refresh() }
            }.show()
    }
    private fun delete() {
        if (pack == null) return
        MaterialAlertDialogBuilder(this).setTitle("Delete this pack?")
            .setMessage("This removes the pack and its files from Sticker Sync. To remove a copy already added to WhatsApp, manage it in WhatsApp.")
            .setNegativeButton("Keep", null).setPositiveButton("Delete") { _, _ ->
                task({ PackRepository.deletePack(this, identifier) }) { finish() }
            }.show()
    }
    @Deprecated("Activity result compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        WhatsAppStickerHelper.handleResult(this, requestCode, resultCode, data)
    }
}

