package com.example.stickerpackbuilder.sync

import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import com.example.stickerpackbuilder.ui.Screen
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

class ChooseStickersActivity : Screen() {
    private val selected = linkedSetOf<String>()
    private lateinit var action: android.widget.Button
    private lateinit var heading: TextView
    private val previews = mutableMapOf<String, File>()
    private val failures = mutableSetOf<String>()
    private val loading = mutableSetOf<String>()
    private val previewSlots = kotlinx.coroutines.sync.Semaphore(4)
    private lateinit var grid: androidx.recyclerview.widget.RecyclerView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll(savedInstanceState?.getStringArrayList("selected") ?: emptyList())
        toolbar("Choose stickers").menu.add("Refresh").setOnMenuItemClickListener {
            setResult(RESULT_OK, android.content.Intent().putExtra("refresh", true)); finish(); true
        }
        column.setPadding(dp(18), 0, dp(18), dp(12))
        heading = text("Choose your favourites", 22f)
        column.addView(heading)
        column.addView(text("Tap the ones you want. Synced stickers are already in WhatsApp.", 13f, true))
        space(column, 12)
        val journal = JSONObject(File(filesDir, "transfer/journal.json").readText())
        val catalog = journal.getJSONArray("catalog")
        val urls = (0 until catalog.length()).map { catalog.getString(it) }.distinctBy(CollectedStickerLedger::assetKey)
        val ledger = CollectedStickerLedger(File(filesDir, "transfer/collected.properties"))
        val gallery = TransferredGallery(this)
        val existing = gallery.entries().associateBy { it.getString("key") }
        grid = androidx.recyclerview.widget.RecyclerView(this).apply { layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@ChooseStickersActivity, 3) }
        column.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        action = button("Transfer selected stickers") {
            setResult(RESULT_OK, android.content.Intent().putStringArrayListExtra("selectedKeys", ArrayList(selected))); finish()
        }
        action.isEnabled = selected.isNotEmpty()
        column.addView(action, LinearLayout.LayoutParams(-1, dp(58)))
        fun update() { heading.text = "${selected.size} selected · ${urls.size} Saved"; action.text = "Transfer ${selected.size} " + if (selected.size == 1) "sticker" else "stickers"; action.isEnabled = selected.isNotEmpty() }
        grid.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<Tile>() {
            override fun getItemCount() = urls.size
            override fun onCreateViewHolder(parent: android.view.ViewGroup, type: Int): Tile {
                val card = com.google.android.material.card.MaterialCardView(this@ChooseStickersActivity).apply {
                    radius = dp(16).toFloat(); cardElevation = 0f; strokeWidth = dp(1)
                    setCardBackgroundColor(0xff1b2b3d.toInt())
                    layoutParams = androidx.recyclerview.widget.RecyclerView.LayoutParams(-1, dp(140)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                    com.example.stickerpackbuilder.ui.Motion.press(this)
                }
                val box = LinearLayout(this@ChooseStickersActivity).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(6), dp(8), dp(6), dp(8))
                }
                val image = ImageView(this@ChooseStickersActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                box.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
                val check = CheckBox(this@ChooseStickersActivity).apply { textSize = 11f; setTextColor(-1); minHeight = dp(32) }
                box.addView(check)
                card.addView(box, FrameLayout.LayoutParams(-1, -1))
                return Tile(card, image, check)
            }
            override fun onBindViewHolder(holder: Tile, position: Int) {
                val url = urls[position]; val key = CollectedStickerLedger.assetKey(url); val synced = ledger.contains(url)
                holder.check.setOnCheckedChangeListener(null)
                holder.check.text = if (synced) "Synced" else if (key in failures) "Tap to retry" else "Select"
                holder.check.isChecked = synced || key in selected; holder.check.isEnabled = !synced && key !in failures
                val card = holder.itemView as com.google.android.material.card.MaterialCardView
                card.isSelected = synced || key in selected
                card.strokeColor = if (card.isSelected) 0xff64e3c0.toInt() else 0xff354b5c.toInt()
                holder.image.contentDescription = "Saved sticker ${position + 1}"
                holder.check.contentDescription = if (synced) "Saved sticker ${position + 1}, already synced" else "Select Saved sticker ${position + 1}"
                val file = if (synced) existing[key]?.let(gallery::preview) else previews[key] ?: StickerDownload.cached(this@ChooseStickersActivity, url).takeIf(File::exists)
                holder.check.isEnabled = !synced && file?.exists() == true
                com.bumptech.glide.Glide.with(holder.image).clear(holder.image)
                if (file?.exists() == true) com.bumptech.glide.Glide.with(holder.image).asBitmap().load(file).override(dp(100), dp(100)).into(holder.image)
                else holder.image.setImageResource(android.R.drawable.ic_menu_gallery)
                holder.check.setOnCheckedChangeListener { _, checked ->
                    if (checked) selected.add(key) else selected.remove(key)
                    card.isSelected = checked
                    card.strokeColor = if (checked) 0xff64e3c0.toInt() else 0xff354b5c.toInt()
                    update()
                }
                holder.itemView.setOnClickListener {
                    if (synced) return@setOnClickListener
                    if (key in failures) { failures.remove(key); notifyItemChanged(position) }
                    else if (holder.check.isEnabled) holder.check.isChecked = !holder.check.isChecked
                }
                if (!synced && file == null && key !in loading && key !in failures) {
                    loading.add(key)
                    lifecycleScope.launch {
                        val loaded = withContext(Dispatchers.IO) {
                            previewSlots.acquire()
                            try { runCatching { StickerDownload.previewSource(this@ChooseStickersActivity, url) }.getOrNull() }
                            finally { previewSlots.release() }
                        }
                        loading.remove(key)
                        if (loaded != null) previews[key] = loaded else failures.add(key)
                        notifyItemChanged(position)
                    }
                }
            }
        }
        update()
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putStringArrayList("selected", ArrayList(selected)); super.onSaveInstanceState(outState) }
    private class Tile(view: View, val image: ImageView, val check: CheckBox) : androidx.recyclerview.widget.RecyclerView.ViewHolder(view)
}
