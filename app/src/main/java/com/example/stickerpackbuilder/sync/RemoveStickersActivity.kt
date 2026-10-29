package com.example.stickerpackbuilder.sync

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.RecyclerView
import com.example.stickerpackbuilder.ui.Screen
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.*
import org.json.JSONObject
import java.io.File

class RemoveStickersActivity : Screen() {
    private val selected = linkedSetOf<String>()
    private var entries = emptyList<JSONObject>()
    private var running = false
    private lateinit var grid: RecyclerView
    private lateinit var action: android.widget.Button
    private lateinit var status: TextView
    private lateinit var selectAll: android.widget.Button
    private val link = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK && selected.isNotEmpty()) removeSelected()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selected.addAll(savedInstanceState?.getStringArrayList("selected") ?: emptyList())
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        column.setBackgroundColor(0xff0d1420.toInt())
        toolbar("Manage your stickers")
        column.setPadding(dp(18), 0, dp(18), dp(16))
        val intro=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        intro.addView(title("Make room for favourites."))
        intro.addView(text("Select stickers you added with Sticker Sync to remove from WhatsApp favourites.", 14f, true))
        space(intro, 12)
        status = text("Loading your collection…", 14f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        intro.addView(status, LinearLayout.LayoutParams(-1, -2))
        space(intro, 8)
        val selection=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        selectAll = button("Select all", true) {
            if (running || entries.isEmpty()) return@button
            val available = entries.map { it.getString("whatsappHash") }.toSet()
            if (selected.containsAll(available)) selected.clear() else selected.addAll(available)
            updateSelection(); grid.adapter?.notifyDataSetChanged()
        }.apply { isEnabled = false }
        selection.addView(selectAll, LinearLayout.LayoutParams(-1, dp(50)))
        space(selection, 8)
        grid = RecyclerView(this).apply { layoutManager = androidx.recyclerview.widget.GridLayoutManager(this@RemoveStickersActivity, 3) }
        selection.addView(grid, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(com.example.stickerpackbuilder.ui.ScrollingGallery(this,intro,selection).view, LinearLayout.LayoutParams(-1, 0, 1f))
        action = button("Select stickers to remove") {
            guide("Remove ${selected.size} ${if (selected.size == 1) "favourite" else "favourites"}?",
                "These stickers will be removed from WhatsApp favourites. You can transfer them again later.",
                "Remove", { removeSelected() }, secondary = "Keep them")
        }.apply { isEnabled = false }
        column.addView(action, LinearLayout.LayoutParams(-1, dp(58)))
        reload()
    }
    private fun reload(message: String? = null) {
        lifecycleScope.launch {
            val all = withContext(Dispatchers.IO) { TransferredGallery(this@RemoveStickersActivity).entries() }
            entries = all.filter { it.optString("whatsappHash").matches(Regex("[A-Za-z0-9+/]{43}=")) }.distinctBy { it.getString("whatsappHash") }
            selected.retainAll(entries.map { it.getString("whatsappHash") }.toSet())
            grid.adapter = object : RecyclerView.Adapter<Tile>() {
                override fun getItemCount() = entries.size
                override fun onCreateViewHolder(parent: android.view.ViewGroup, type: Int): Tile {
                    val card = MaterialCardView(this@RemoveStickersActivity).apply {
                        radius = dp(16).toFloat(); cardElevation = 0f; strokeWidth = dp(1)
                        setCardBackgroundColor(com.example.stickerpackbuilder.ui.Polish.SURFACE)
                        com.example.stickerpackbuilder.ui.Motion.press(this)
                        layoutParams = RecyclerView.LayoutParams(-1, dp(136)).apply { setMargins(dp(4), dp(4), dp(4), dp(4)) }
                    }
                    val box = LinearLayout(this@RemoveStickersActivity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(8), dp(8), 0) }
                    val image = ImageView(this@RemoveStickersActivity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
                    val check = com.google.android.material.checkbox.MaterialCheckBox(this@RemoveStickersActivity).apply { text = "Select"; textSize = 11f; setTextColor(-1);com.example.stickerpackbuilder.ui.Polish.selection(this) }
                    box.addView(image, LinearLayout.LayoutParams(-1, 0, 1f)); box.addView(check)
                    card.addView(box, FrameLayout.LayoutParams(-1, -1))
                    return Tile(card, image, check)
                }
                override fun onBindViewHolder(holder: Tile, position: Int) {
                    val entry = entries[position]; val hash = entry.getString("whatsappHash")
                    val preview = TransferredGallery(this@RemoveStickersActivity).preview(entry)
                    com.bumptech.glide.Glide.with(holder.image).clear(holder.image)
                    if (preview.exists()) com.bumptech.glide.Glide.with(holder.image).asBitmap().load(preview).override(dp(100), dp(100)).into(holder.image)
                    else holder.image.setImageResource(com.example.stickerpackbuilder.R.drawable.ic_ui_sticker)
                    holder.check.setOnCheckedChangeListener(null)
                    holder.check.isChecked = hash in selected; holder.check.isEnabled = !running
                    holder.card.isEnabled = !running
                    holder.card.isSelected = hash in selected
                    holder.check.contentDescription = "Select transferred sticker ${position + 1} for removal"
                    holder.card.strokeColor = if (hash in selected) 0xff64e3c0.toInt() else 0xff354b5c.toInt()
                    holder.check.setOnCheckedChangeListener { _, checked ->
                        if (running) return@setOnCheckedChangeListener
                        if (checked) selected.add(hash) else selected.remove(hash)
                        holder.card.strokeColor = if (checked) 0xff64e3c0.toInt() else 0xff354b5c.toInt()
                        holder.card.isSelected = checked
                        updateSelection()
                    }
                    holder.card.setOnClickListener { if (!running) holder.check.toggle() }
                }
            }
            updateSelection()
            if (message != null) status.text = message
            else if (entries.isEmpty()) status.text = if (all.isEmpty()) "Your collection is empty." else "These older transfers have no removal record. Remove them directly in WhatsApp."
        }
    }
    private fun updateSelection() {
        selectAll.isEnabled = !running && entries.isNotEmpty()
        selectAll.text = if (entries.isNotEmpty() && selected.containsAll(entries.map { it.getString("whatsappHash") })) "Clear selection" else "Select all"
        selectAll.contentDescription = if (selectAll.text == "Clear selection") "Clear sticker removal selection" else "Select all ${entries.size} stickers for removal"
        action.isEnabled = !running && selected.isNotEmpty()
        action.text = if (running) "Removing…" else if (selected.isEmpty()) "Select stickers to remove" else "Remove ${selected.size} from WhatsApp"
        com.example.stickerpackbuilder.ui.Polish.syncIcon(action);com.example.stickerpackbuilder.ui.Polish.syncIcon(selectAll)
        if (!running) status.text = "${selected.size} selected · ${entries.size} in your collection"
    }
    private fun removeSelected() {
        if (running || selected.isEmpty()) return
        val requested = selected.toList()
        running = true; updateSelection(); grid.adapter?.notifyDataSetChanged()
        startForegroundService(Intent(this, PhoneConnectorService::class.java))
        lifecycleScope.launch {
            var removed = 0
            try {
                status.text = "Connecting to WhatsApp…"
                val info = ConnectorReady.await(this@RemoveStickersActivity) { status.text = it }
                if (!info.optBoolean("registered")) {
                    link.launch(Intent(this@RemoveStickersActivity, LinkWhatsAppActivity::class.java).putExtra("returnAfterLink", true))
                    return@launch
                }
                for (hash in requested) {
                    ensureActive()
                    status.text = "Removing selected stickers · ${removed + 1} of ${requested.size}"
                    withContext(NonCancellable + Dispatchers.IO) {
                        val result = PhoneRuntime.request(this@RemoveStickersActivity, "/remove-favorite", JSONObject().put("hash", hash))
                        check(result.optBoolean("removed")) { "WhatsApp didn't confirm removal. Try again." }
                        val gallery = TransferredGallery(this@RemoveStickersActivity)
                        val keys = gallery.entries().filter { it.optString("whatsappHash") == hash }.map { it.getString("key") }.toSet()
                        CollectedStickerLedger(File(filesDir, "transfer/collected.properties")).forgetKeys(keys)
                        gallery.forget(keys)
                    }
                    selected.remove(hash); removed++
                }
                reload("$removed ${if (removed == 1) "sticker removed" else "stickers removed"} from WhatsApp favourites.")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { reload("$removed removed. ${e.message ?: "Couldn't finish. Try again."}") }
            finally {
                running = false; updateSelection(); grid.adapter?.notifyDataSetChanged()
                stopService(Intent(this@RemoveStickersActivity, PhoneConnectorService::class.java))
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putStringArrayList("selected", ArrayList(selected)); super.onSaveInstanceState(outState) }
    private class Tile(val card: MaterialCardView, val image: ImageView, val check: CheckBox) : RecyclerView.ViewHolder(card)
}
