package com.example.stickerpackbuilder.ui

import android.graphics.Bitmap
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.recyclerview.widget.RecyclerView
import androidx.lifecycle.LifecycleCoroutineScope
import com.example.stickerpackbuilder.StickerConverter
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.*
import java.io.File

class StickerGridAdapter(
    private val files: List<File>, private val selected: Set<String>,
    private val scope: LifecycleCoroutineScope, private val animated: Boolean,
    private val toggle: (String) -> Unit, private val preview: (File) -> Unit,
    private val selectable: Boolean = true
) : RecyclerView.Adapter<StickerGridAdapter.Holder>() {
    private val holders = mutableSetOf<Holder>()
    class Holder(val card: MaterialCardView, val image: ImageView, val label: TextView) : RecyclerView.ViewHolder(card) {
        var job: Job? = null
        var bitmap: Bitmap? = null
    }
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val ctx = parent.context
        val density = ctx.resources.displayMetrics.density
        val card = MaterialCardView(ctx).apply {
            radius = 16 * density; cardElevation = 0f
            setCardBackgroundColor(0xff1b2633.toInt())
            layoutParams = ViewGroup.MarginLayoutParams(-1, (132 * density).toInt()).apply { setMargins(5, 5, 5, 5) }
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER }
        val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER; setPadding(10, 10, 10, 10) }
        val label = TextView(ctx).apply { textSize = 12f; gravity = Gravity.CENTER; setTextColor(0xffa8b5c5.toInt()) }
        col.addView(image, LinearLayout.LayoutParams(-1, 0, 1f))
        col.addView(label, LinearLayout.LayoutParams(-1, (30 * density).toInt()))
        card.addView(col)
        return Holder(card, image, label).also { holders.add(it) }
    }
    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.job?.cancel()
        holder.image.setImageDrawable(null)
        holder.bitmap?.recycle(); holder.bitmap = null
        val file = files[position]
        val checked = file.name in selected
        holder.card.strokeWidth = if (checked) 3 else 0
        holder.card.strokeColor = 0xff64e3c0.toInt()
        holder.label.text = if (!selectable) "Tap to preview" else if (checked) "✓ Selected" else if (animated) "Animated · hold to play" else "Hold to preview"
        holder.card.contentDescription = "Sticker ${position + 1}, " + if (checked) "selected" else "not selected"
        holder.card.setOnClickListener { toggle(file.name) }
        holder.card.setOnLongClickListener { preview(file); true }
        holder.job = scope.launch {
            val bitmap = withContext(Dispatchers.IO) { runCatching { StickerConverter.firstFrame(file) }.getOrNull() }
            if (isActive && bitmap != null) {
                holder.bitmap = bitmap
                holder.image.setImageBitmap(bitmap)
            } else bitmap?.recycle()
        }
    }
    override fun onViewRecycled(holder: Holder) {
        holder.job?.cancel(); holder.image.setImageDrawable(null); holder.bitmap?.recycle(); holder.bitmap = null
    }
    override fun getItemCount() = files.size
    override fun onDetachedFromRecyclerView(recyclerView: RecyclerView) {
        holders.forEach { it.job?.cancel(); it.image.setImageDrawable(null); it.bitmap?.recycle(); it.bitmap = null }
        holders.clear()
        super.onDetachedFromRecyclerView(recyclerView)
    }
}

