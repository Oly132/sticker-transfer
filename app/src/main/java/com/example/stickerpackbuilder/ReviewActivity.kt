package com.example.stickerpackbuilder

import android.content.Intent
import android.os.Bundle
import android.text.InputFilter
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.stickerpackbuilder.ui.Screen
import com.example.stickerpackbuilder.ui.StickerGridAdapter
import com.example.stickerpackbuilder.ui.previewSticker
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.io.File

class ReviewActivity : Screen() {
    private val selected = linkedSetOf<String>()
    private var animated = false
    private var items: List<PackRepository.Pending> = emptyList()
    private lateinit var grid: RecyclerView
    private lateinit var count: TextView
    private lateinit var create: MaterialButton
    private lateinit var delete: MaterialButton
    private lateinit var toggle: MaterialButtonToggleGroup
    private var destination: com.example.stickerpackbuilder.models.StickerPack? = null
    private fun capacity() = 30 - (destination?.stickers?.size ?: 0)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        animated = savedInstanceState?.getBoolean("animated") ?: false
        selected.addAll(savedInstanceState?.getStringArrayList("selected") ?: emptyList())
        toolbar("Review stickers")
        val intro = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), 0, dp(20), dp(12)) }
        intro.addView(text("Choose 3–30 stickers of one type. Hold a sticker to preview it.", 14f, true))
        space(intro, 12)
        toggle = MaterialButtonToggleGroup(this).apply { isSingleSelection = true; isSelectionRequired = true }
        val still = button("Still", true) {}.apply { id = R.id.filter_still }
        val motion = button("Animated", true) {}.apply { id = R.id.filter_animated }
        toggle.addView(still, LinearLayout.LayoutParams(0, -2, 1f))
        toggle.addView(motion, LinearLayout.LayoutParams(0, -2, 1f))
        toggle.check(if (animated) R.id.filter_animated else R.id.filter_still)
        toggle.addOnButtonCheckedListener { _, id, checked ->
            if (checked) { animated = id == R.id.filter_animated; selected.clear(); render() }
        }
        intro.addView(toggle)
        count = text("", 14f, true); intro.addView(count)
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(button("Select up to 30", true) {
            selected.clear(); selected.addAll(visible().take(capacity()).map { it.fileName }); render()
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actions.addView(button("Clear", true) { selected.clear(); render() }, LinearLayout.LayoutParams(0, -2, 1f))
        intro.addView(actions); column.addView(intro)
        grid = RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@ReviewActivity, 3)
            setPadding(dp(14), 0, dp(14), dp(8))
            clipToPadding = false
        }
        column.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        val footer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(14)) }
        create = button("Create pack") { namePack() }
        delete = button("Delete selected", true) { deleteSelected() }
        footer.addView(create); footer.addView(delete); column.addView(footer)
        task({ Pair(PackRepository.pending(this), intent.getStringExtra("destination")?.let { id ->
            PackRepository.loadPacks(this).firstOrNull { pack -> pack.identifier == id } ?: error("Pack no longer exists")
        }) }) { (pending, target) ->
            items = pending
            destination = target
            if (target != null) {
                animated = target.animated
                toggle.check(if (animated) R.id.filter_animated else R.id.filter_still)
                for (i in 0 until toggle.childCount) toggle.getChildAt(i).isEnabled = false
            }
            if (destination == null && savedInstanceState == null && items.none { p -> !p.animated } && items.isNotEmpty()) toggle.check(R.id.filter_animated)
            selected.retainAll(visible().map { p -> p.fileName }.toSet())
            render()
        }
    }
    private fun visible() = items.filter { it.animated == animated }
    private fun render() {
        val files = visible().map { File(PackRepository.pendingDir(this), it.fileName) }
        val state = grid.layoutManager?.onSaveInstanceState()
        grid.adapter = StickerGridAdapter(files, selected.toSet(), lifecycleScope, animated, { name ->
            if (name in selected) selected.remove(name)
            else if (selected.size < capacity()) selected.add(name) else message("This pack can hold ${capacity()} more stickers.")
            render()
        }, { file -> previewSticker(file, animated) })
        grid.layoutManager?.onRestoreInstanceState(state)
        count.text = if (files.isEmpty()) "No ${if (animated) "animated" else "still"} stickers waiting" else
            "${selected.size} selected · ${files.size} available"
        create.text = if (destination == null) "Create pack (${selected.size}/30)" else "Add ${selected.size} to ${destination!!.name}"
        create.isEnabled = selected.isNotEmpty() && selected.size <= capacity() &&
            selected.size + (destination?.stickers?.size ?: 0) >= 3
        delete.isEnabled = selected.isNotEmpty()
    }
    private fun namePack() {
        val target = destination
        if (target != null) {
            task({ PackRepository.appendPending(this, target.identifier, selected.toList()) }) {
                finish()
            }
            return
        }
        val layout = TextInputLayout(this).apply { hint = "Pack name"; setPadding(dp(24), dp(12), dp(24), 0) }
        val input = TextInputEditText(layout.context).apply {
            setSingleLine(); filters = arrayOf(InputFilter.LengthFilter(128)); setText(if (animated) "Animated favourites" else "My favourites")
        }
        layout.addView(input)
        val dialog = MaterialAlertDialogBuilder(this).setTitle("Name your pack").setView(layout)
            .setNegativeButton("Cancel", null).setPositiveButton("Create", null).create()
        dialog.setOnShowListener {
            dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = input.text.toString().trim()
                if (name.isBlank()) { layout.error = "Enter a name"; return@setOnClickListener }
                dialog.dismiss()
                task({ PackRepository.createPackFromPending(this, selected.toList(), name) }) { pack ->
                    startActivity(Intent(this, PackDetailActivity::class.java).putExtra("pack", pack.identifier))
                    finish()
                }
            }
        }
        dialog.show()
    }
    private fun deleteSelected() {
        MaterialAlertDialogBuilder(this).setTitle("Delete ${selected.size} stickers?")
            .setMessage("This removes the selected waiting stickers from this app.")
            .setNegativeButton("Keep", null).setPositiveButton("Delete") { _, _ ->
                task({ PackRepository.deletePending(this, selected.toList()); PackRepository.pending(this) }) {
                    items = it; selected.clear(); render()
                }
            }.show()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("animated", animated); outState.putStringArrayList("selected", ArrayList(selected))
        super.onSaveInstanceState(outState)
    }
}

