package com.example.stickerpackbuilder.reverse

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.stickerpackbuilder.sync.*
import com.example.stickerpackbuilder.ui.Motion
import android.content.ContextWrapper
import kotlinx.coroutines.*
import java.io.File
import kotlin.coroutines.resume

/** Retained reverse-direction content hosted by the single transfer dashboard. */
class ReverseTransferController(private val host:TransferActivity,private val restored:Bundle?) : ContextWrapper(host) {
    val column=LinearLayout(host).apply{orientation=LinearLayout.VERTICAL}
    val header=LinearLayout(host).apply{orientation=LinearLayout.VERTICAL}
    val footer=LinearLayout(host).apply{orientation=LinearLayout.VERTICAL}
    private var built=false
    private var cacheDirty=false
    private var cacheReady=false
    private var cachedWork=false
    private var workGeneration=0
    private var returnToForwardOnStop=false
    val isBusy get()=work?.isActive==true
    val blocksDirectionChange get()=isBusy&&!cachedWork
    val keepConnectionWarm get()=isBusy||pendingLink
    private val lifecycleScope get()=host.lifecycleScope
    private fun dp(value:Int)=host.dp(value)
    private fun text(value:String,size:Float=15f,secondary:Boolean=false)=host.text(value,size,secondary)
    private fun button(label:String,outlined:Boolean=false,action:()->Unit)=host.button(label,outlined,action)
    private fun space(parent:LinearLayout,height:Int=16)=host.space(parent,height)
    private fun message(value:String)=host.message(value)
    private fun error(error:Throwable)=host.error(error)
    private fun guide(heading:String,copy:CharSequence,primary:String,proceed:()->Unit,secondary:String="Not now",extra:Pair<String,()->Unit>?=null)=
        host.guide(heading,copy,primary,proceed,secondary,extra)
    fun content():LinearLayout {
        if(!built){built=true;build(restored)}
        return column
    }
    fun markCacheDirty(){cacheDirty=true}
    fun onShown(){if(built&&(cacheDirty||!cacheReady)&&!isBusy)loadCached()}
    fun onHidden()=Unit
    fun refresh(){if(!isBusy){content();load()}}

    private val repo by lazy { ReverseRepository(this) }
    private var rows = emptyList<ReverseSticker>()
    private val ready = mutableSetOf<String>()
    private val selected = mutableSetOf<String>()
    private val failures = mutableMapOf<String, String>()
    private val unreadable = mutableMapOf<String, String>()
    private var origins = emptySet<String>()
    private val submitted = mutableSetOf<String>()
    private var work: Job? = null
    private lateinit var status: TextView
    private lateinit var statusSurface: com.example.stickerpackbuilder.ui.Polish.StatusPanel
    private lateinit var action: Button
    private lateinit var selectAll: Button
    private lateinit var recovery: LinearLayout
    private lateinit var empty: TextView
    private lateinit var adapter: Stickers
    private var pendingPermission = false
    private var pendingLink = false
    private var afterPermission: List<String> = emptyList()
    private var afterPermissionAll = false
    private data class PendingFolder(val ids: List<String>, val allNew: Boolean)
    private var pendingFolder: PendingFolder? = null
    private var initialJob: Job? = null
    private val permission = host.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        lifecycleScope.launch {
            initialJob?.join()
            repeat(10) { if (TikTokTransferService.instance == null) delay(200) }
            if (TikTokTransferService.instance != null) {
                pendingPermission = false
                transfer(afterPermission, afterPermissionAll)
            } else restrictedHelp()
        }
    }
    private val link = host.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (pendingLink) { pendingLink = false; load() }
    }
    private val folder = host.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        lifecycleScope.launch {
            initialJob?.join()
            val resume = pendingFolder; pendingFolder = null
            if (uri == null) { status.text = "Folder setup paused. Your downloaded originals are kept."; return@launch }
            try { LocalOriginals.remember(this@ReverseTransferController, uri) }
            catch (e: Exception) { error(e); return@launch }
            if (resume != null) transfer(resume.ids, resume.allNew)
            else runWork("Finding your local stickers…") {
                val count = recoverLocalMissing()
                status.text = "$count originals recovered. Your stickers are ready to choose."
            }
        }
    }

    private fun build(savedInstanceState:Bundle?) {
        selected.addAll(savedInstanceState?.getStringArrayList("selectedIds").orEmpty())
        afterPermission = savedInstanceState?.getStringArrayList("permissionIds").orEmpty()
        afterPermissionAll = savedInstanceState?.getBoolean("permissionAll", false) == true
        if(savedInstanceState?.getBoolean("folderPending",false)==true)pendingFolder=PendingFolder(
            savedInstanceState.getStringArrayList("folderIds").orEmpty(),savedInstanceState.getBoolean("folderAll",false))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL;setPadding(0,0,0,dp(8)) }
        header.addView(body)
        val alphaWarning = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0xff29251f.toInt())
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), 0xff756044.toInt())
            }
            addView(text("Early alpha · WhatsApp → TikTok", 14f).apply {
                setTextColor(0xffffd394.toInt())
                typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            })
            addView(text("This direction is still in very early testing. Transfers are very slow and have a high error rate. You may need to retry.", 13f).apply {
                setTextColor(0xffe3d7c7.toInt())
                setPadding(0, dp(4), 0, 0)
            })
        }
        body.addView(alphaWarning, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) })
        status = text("Load your favourites to get started.", 14f).apply { setTextColor(0xffaee9dc.toInt()); accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        statusSurface=com.example.stickerpackbuilder.ui.Polish.StatusPanel(this,status)
        body.addView(statusSurface,LinearLayout.LayoutParams(-1,-2))
        space(body,8)
        action = button("Load WhatsApp favourites") {
            if (work?.isActive == true) { work?.cancel(); TikTokTransferService.instance?.reverseImporter?.cancel() }
            else if (rows.isEmpty()) load()
            else transfer(if (selected.isNotEmpty()) selected.toList() else eligible(), allNew = selected.isEmpty())
        }
        val selection = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val tools = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        tools.addView(text("Your WhatsApp favourites", 15f), LinearLayout.LayoutParams(0, -2, 1f))
        selectAll = button("Select all", true) {
            val ids = eligible()
            if (ids.isNotEmpty() && selected.containsAll(ids)) selected.clear() else selected.addAll(ids)
            adapter.notifyDataSetChanged(); render()
        }.apply { textSize = 12f }
        tools.addView(selectAll, LinearLayout.LayoutParams(-2, dp(44)))
        selection.addView(tools)
        recovery = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        selection.addView(recovery)
        empty = text("Your stickers will appear here. Only new selections are imported; previously submitted stickers are skipped.", 14f, true).apply { setPadding(dp(24), dp(20), dp(24), dp(12)) }
        selection.addView(empty)
        adapter = Stickers()
        selection.addView(RecyclerView(this).apply {
            layoutManager = GridLayoutManager(this@ReverseTransferController, 3)
            adapter = this@ReverseTransferController.adapter
            setPadding(0, dp(8), 0, dp(24)); clipToPadding = false
            itemAnimator = null
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        column.addView(selection, LinearLayout.LayoutParams(-1, 0, 1f))
        footer.addView(action, LinearLayout.LayoutParams(-1, dp(58)))
        loadCached()
    }
    private fun loadCached(){
        cacheDirty=false
        runWork("Loading your stickers…",cached=true) {
            rows=withContext(Dispatchers.IO){repo.cached()}
            inspectCache();recoverLocalMissing();cacheReady=true
            status.text=if(rows.isEmpty())"Load your favourites to get started." else "${eligible().size} new stickers ready."+if(repo.partial())"\nSome favourites may be missing. Refresh to check again." else ""
        }
        initialJob=work
    }
    fun onBack(){
        if(isBusy&&cachedWork){host.showTransferDirection(false);return}
        if(isBusy)host.guide("Stop this transfer?","Completed stickers stay saved. Any creation already submitted will be kept in the duplicate-prevention history.","Stop",{
            returnToForwardOnStop=true
            val active=work
            active?.invokeOnCompletion{host.runOnUiThread{if(!host.isFinishing&&!host.isDestroyed)host.showTransferDirection(false)}}
            active?.cancel();TikTokTransferService.instance?.reverseImporter?.cancel()
        },"Keep transferring")
        else host.showTransferDirection(false)
    }
    private fun needsOriginals() = rows.any { !it.lottie && it.id !in ready && it.id !in origins && it.id !in submitted }
    private fun eligible() = rows.filter { it.id in ready && it.id !in origins && it.id !in submitted }.map { it.id }
    private suspend fun inspectCache() {
        val result = withContext(Dispatchers.IO) {
            val excluded = repo.fromTikTok()
            val visible = rows.filterNot { it.id in excluded }
            val found = visible.filter { it.id !in unreadable && repo.verified(it.id) }.map { it.id }.toSet()
            val submittedIds=ReverseHistory.submittedIds(this@ReverseTransferController)
            val imported=visible.map{it.id}.filter{it in submittedIds}.toSet()
            Pair(visible, Triple(found, excluded, imported))
        }
        rows = result.first
        ready.clear(); ready.addAll(result.second.first); origins = result.second.second
        submitted.clear(); submitted.addAll(result.second.third)
        failures.keys.retainAll(rows.map { it.id }.toSet())
        ready.forEach { failures.remove(it) }
        selected.retainAll(eligible().toSet())
    }
    private suspend fun recoverLocalMissing(): Int {
        val missing = rows.filter { !it.lottie && it.id !in ready && it.id !in submitted && it.id !in unreadable }.map { it.id }.toSet()
        if(missing.isEmpty() || LocalOriginals.availableTrees(this).isEmpty())return 0
        status.text = "Loading stickers from your WhatsApp folder…"
        val count = runInterruptible(Dispatchers.IO) { LocalOriginals.recoverAvailable(this@ReverseTransferController,missing,repo) }
        if(count>0){inspectCache();adapter.notifyDataSetChanged()}
        return count
    }
    private fun connectFolder(resume: PendingFolder? = null) {
        pendingFolder = resume
        guide("Connect your sticker folder once", "Open your WhatsApp Stickers folder, then tap Use this folder and Allow. Sticker Sync will check this folder automatically on future transfers.", "Choose folder", {
            folder.launch(android.provider.DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Stickers"))
        }, extra = if(resume != null && eligible().isNotEmpty()) "Transfer available" to { pendingFolder=null; transfer(eligible(),false) } else null)
    }
    fun saveState():Bundle {
        if(!built)return Bundle(restored?:Bundle())
        return Bundle().apply {
        putStringArrayList("permissionIds",ArrayList(afterPermission));putBoolean("permissionAll",afterPermissionAll)
        putStringArrayList("selectedIds",ArrayList(selected))
        pendingFolder?.let{putBoolean("folderPending",true);putStringArrayList("folderIds",ArrayList(it.ids));putBoolean("folderAll",it.allNew)}
    }
    }
    private fun render() {
        val busy = work?.isActive == true
        action.text = when { busy -> "Cancel"; rows.isEmpty() && origins.isEmpty() -> "Load WhatsApp favourites"; rows.isEmpty() -> "All caught up"; selected.isNotEmpty() -> "Transfer ${selected.size} selected"; eligible().isEmpty() && needsOriginals() -> "Load and transfer stickers"; eligible().isEmpty() -> "All caught up"; else -> "Transfer ${eligible().size} new stickers" }
        action.isEnabled = busy || rows.isEmpty() && origins.isEmpty() || eligible().isNotEmpty() || needsOriginals()
        selectAll.isEnabled = !busy && eligible().isNotEmpty()
        selectAll.text = if (eligible().isNotEmpty() && selected.containsAll(eligible())) "Clear" else "Select all"
        empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
        empty.text = if(origins.isNotEmpty())"No new WhatsApp stickers to transfer. Refresh to check again." else "Your stickers will appear here. Previously submitted stickers are skipped automatically."
        recovery.removeAllViews()
        recovery.visibility = if (failures.isEmpty()) View.GONE else View.VISIBLE
        if (failures.isNotEmpty()) {
            recovery.addView(text("${failures.size} originals aren't available yet. Other stickers can be transferred now.", 13f, true))
            val buttons = LinearLayout(this)
            buttons.addView(button("Retry", true) { downloadMissing(true) }.apply { isEnabled = !busy }, LinearLayout.LayoutParams(0, dp(46), 1f))
            buttons.addView(button("Find on phone", true) {
                connectFolder()
            }.apply { isEnabled = !busy }, LinearLayout.LayoutParams(0, dp(46), 1f))
            recovery.addView(buttons)
        }
        com.example.stickerpackbuilder.ui.Polish.syncIcon(action)
        com.example.stickerpackbuilder.ui.Polish.syncIcon(selectAll)
        statusSurface.show(if(busy)com.example.stickerpackbuilder.ui.Polish.Status.BUSY else if(failures.isNotEmpty()||repo.partial())com.example.stickerpackbuilder.ui.Polish.Status.WARNING else com.example.stickerpackbuilder.ui.Polish.Status.READY)
        host.reverseContentChanged()
    }
    private fun runWork(copy:String,cached:Boolean=false,block:suspend()->Unit){
        if(isBusy)return
        if(!cached&&(ReverseState.active||AutoTransfer.active()||host.forwardTransferBusy())){message("Finish the current transfer first.");return}
        if(!cached)ReverseState.active=true
        cachedWork=cached
        val generation=++workGeneration
        work=lifecycleScope.launch(start=CoroutineStart.LAZY){
            try{status.text=copy;block()}
            catch(error:CancellationException){if(generation==workGeneration)status.text="Stopped. Completed files are kept for next time.";throw error}
            catch(error:Exception){if(generation==workGeneration)status.text=error.message?:"Couldn't finish. Your completed files are kept."}
            finally{
                if(!cached)ReverseState.active=false
                if(generation==workGeneration){work=null;cachedWork=false;render();adapter.notifyDataSetChanged()}
            }
        }
        work!!.start();render()
    }
    private suspend fun connected(): Boolean {
        startForegroundService(Intent(this, PhoneConnectorService::class.java))
        var registered: Boolean? = null
        val deadline=android.os.SystemClock.elapsedRealtime()+12_000
        var restarted=false
        repeat(25) { attempt ->
            val result = withContext(Dispatchers.IO) { runCatching { PhoneRuntime.request(this@ReverseTransferController, "/status") }.getOrNull() }
            if(android.os.SystemClock.elapsedRealtime()>deadline)throw IllegalStateException("WhatsApp is still reconnecting. Tap Refresh to retry; your saved originals are kept.")
            if(result==null&&attempt>=4&&!restarted){
                restarted=true
                status.text="Restoring your WhatsApp connection…"
                PhoneRuntime.restart(this@ReverseTransferController)
            }
            if (result != null) {
                registered = result.optBoolean("registered")
                if (result.optBoolean("ready")) return true
                if (registered == false && result.optBoolean("readyToPair")) { pendingLink = true; link.launch(Intent(this, LinkWhatsAppActivity::class.java)); return false }
                if (attempt == 8 && registered == true) withContext(Dispatchers.IO) { runCatching { PhoneRuntime.request(this@ReverseTransferController, "/reconnect", org.json.JSONObject()) } }
            }
            delay(250)
        }
        if (registered == false) { pendingLink = true; link.launch(Intent(this, LinkWhatsAppActivity::class.java)); return false }
        throw IllegalStateException("WhatsApp is still reconnecting. Your saved originals remain usable. Try Refresh shortly.")
    }
    private fun load() = runWork("Checking your WhatsApp connection…") {
        if (!connected()) return@runWork
        status.text = "Reading your WhatsApp favourites…"
        rows = withContext(Dispatchers.IO) { repo.refresh() }
        inspectCache(); adapter.notifyDataSetChanged()
        downloadRows(false)
    }
    private fun downloadMissing(retry: Boolean) = runWork("Recovering unavailable originals…") {
        if(connected()){
            rows=withContext(Dispatchers.IO){repo.refresh()}
            inspectCache();adapter.notifyDataSetChanged();downloadRows(retry)
        }
    }
    private suspend fun downloadRows(retry: Boolean) {
        recoverLocalMissing()
        failures.keys.retainAll(rows.map { it.id }.toSet())
        val pending = rows.filter { it.id !in ready && it.id !in origins && it.id !in submitted }
        var completed = 0
        repo.download(pending, retry) { id, failure ->
            completed++
            if (failure == null) { ready.add(id); failures.remove(id) } else failures[id] = failure
            val position = rows.indexOfFirst { it.id == id }; if (position >= 0) adapter.notifyItemChanged(position)
            status.text = "Preparing originals · $completed / ${pending.size} checked"
        }
        status.text = if (rows.isEmpty()) "No WhatsApp favourites were found. Favourite a sticker in WhatsApp, then Refresh." else "${eligible().size} new stickers ready · ${failures.size} unavailable" + if(repo.partial())"\nSome favourites may be missing. Refresh to check again." else ""
    }
    private fun restrictedHelp() {
        guide("Allow Sticker Sync automation", "If Android says ‘Controlled by restricted settings’:\n\nOpen Settings → Apps → Sticker Sync.\n1. Tap ⋮ → Allow restricted settings.\n2. Confirm with your PIN or fingerprint.\n3. Return to Accessibility → Installed apps → Sticker Sync and enable it.", "Open app settings", {
            permission.launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName")))
        }, extra = "Accessibility" to { permission.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
    }
    private fun transfer(ids: List<String>, allNew: Boolean = false) {
        val requestStartedAt=android.os.SystemClock.uptimeMillis()
        val required = com.example.stickerpackbuilder.update.GitHubUpdates.requiredCached(this)
        if (required != null) { startActivity(Intent(this, com.example.stickerpackbuilder.update.UpdateActivity::class.java).putExtra("update", required.json())); return }
        if (android.os.Build.VERSION.SDK_INT < 29) { message("WhatsApp → TikTok needs Android 10 or newer."); return }
        if (ids.isEmpty() && !(allNew && needsOriginals())) { message("Select a new, available sticker first."); return }
        if(allNew && needsOriginals() && LocalOriginals.availableTrees(this).isEmpty()){
            connectFolder(PendingFolder(ids, true));return
        }
        val service = TikTokTransferService.instance
        if (service == null) {
            afterPermission = ids; afterPermissionAll = allNew; pendingPermission = true
            guide("One permission, then you're ready", "Sticker Sync uses Android automation to open your own TikTok Saved picker and create only the files you select. Keep your phone unlocked during transfer.", "Enable automation", { permission.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) })
            return
        }
        returnToForwardOnStop=false
        runWork("Preparing your selected stickers…") {
            recoverLocalMissing()
            val queue = (if(allNew) eligible() else ids).distinct().filter { it in eligible() }
            if(queue.isEmpty()) { status.text="No new originals were found in that folder. Refresh or choose another sticker folder.";return@runWork }
            withContext(Dispatchers.IO){StickerStager.cleanupUnused(this@ReverseTransferController)}
            service.reverseImporter.startBatch(queue.size) { work?.cancel() }
            val batchStarted=android.os.SystemClock.uptimeMillis()
            val prepareMs=java.util.concurrent.atomic.AtomicLong()
            val stageMs=java.util.concurrent.atomic.AtomicLong()
            var completed=0
            var skipped=0
            val createRequests=java.util.concurrent.atomic.AtomicInteger()
            try {
                val pipeline=ReverseBatchPipeline<StickerStager.Prepared,StickerStager.Staged>(
                    prepare={ id ->
                        val start=android.os.SystemClock.uptimeMillis()
                        try {
                            withContext(Dispatchers.IO) {
                                check(repo.verified(id)){"This original needs downloading again. Refresh to recover it."}
                                runInterruptible { StickerStager.prepare(repo.original(id),File(filesDir,"reverse/prepared")) }
                            }
                        } finally { prepareMs.addAndGet(android.os.SystemClock.uptimeMillis()-start) }
                    },
                    stage={ id,prepared ->
                        val start=android.os.SystemClock.uptimeMillis()
                        try { withContext(Dispatchers.IO){StickerStager.stageVerified(this@ReverseTransferController,prepared,id,retainPrevious=true)} }
                        finally { stageMs.addAndGet(android.os.SystemClock.uptimeMillis()-start) }
                    },
                    import={ _,staged,onSubmitted ->
                        status.text="Adding sticker ${completed+1} / ${queue.size}…"
                        val result=suspendCancellableCoroutine<Pair<Boolean,String>> { continuation ->
                            service.reverseImporter.begin(staged,{createRequests.incrementAndGet();onSubmitted()}) { ok,copy -> if(continuation.isActive)continuation.resume(ok to copy) }
                            continuation.invokeOnCancellation { service.reverseImporter.cancel() }
                        }
                        if(!result.first)throw IllegalStateException(result.second)
                    },
                    release={ staged -> withContext(Dispatchers.IO){StickerStager.discard(this@ReverseTransferController,staged)} },
                    progress={ count,total,id ->
                        completed=count
                        service.reverseImporter.updateProgress(count,total)
                        selected.remove(id);submitted.add(id)
                        status.text="$count / $total added to TikTok Saved"
                    },
                    preparationFailed={ id,failure ->
                        skipped++
                        val copy=when(failure.message) {
                            "Animation exceeds the 300-frame / 60-second import limit" -> "This animation exceeds the 60-second / 300-frame import limit. Your other selections can still transfer."
                            "Choose an original under 8 MB" -> "This original exceeds the 8 MB import limit. Your other selections can still transfer."
                            "Converted GIF exceeds the 20 MB import limit" -> "This animation exceeds the 20 MB conversion limit. Your other selections can still transfer."
                            else -> "This sticker couldn't be read for TikTok. Choose a different original or try downloading it again. Your other selections can still transfer."
                        }
                        unreadable[id]=copy;failures[id]=copy
                        ready.remove(id);selected.remove(id)
                    }
                )
                completed=pipeline.run(queue)
                status.text=if(skipped==0)"$completed stickers added to TikTok Saved." else "$completed / ${queue.size} added to TikTok Saved · $skipped couldn’t be read."
            } finally {
                // Keep timings numeric and local; no account, media URL or sticker content.
                withContext(NonCancellable+Dispatchers.IO){
                    runCatching { File(filesDir,"reverse/last-batch-performance.json").writeText(org.json.JSONObject()
                        .put("at",System.currentTimeMillis()).put("requested",queue.size).put("completed",completed)
                        .put("buttonToEndMs",android.os.SystemClock.uptimeMillis()-requestStartedAt)
                        .put("elapsedMs",android.os.SystemClock.uptimeMillis()-batchStarted).put("createRequests",createRequests.get()).put("skippedUnreadable",skipped)
                        .put("prepareWorkMs",prepareMs.get()).put("stageWorkMs",stageMs.get()).toString()) }
                }
                // A queued or submitted creation keeps its journal even if Android closes us.
                withContext(NonCancellable){inspectCache()}
                android.util.Log.i("ReverseSync","BATCH_TIMING: requested=${queue.size}, completed=$completed, skipped=$skipped, createRequests=${createRequests.get()}, buttonToEndMs=${android.os.SystemClock.uptimeMillis()-requestStartedAt}, prepareWorkMs=${prepareMs.get()}, stageWorkMs=${stageMs.get()}")
                service.reverseImporter.endBatch()
                host.returnFromReverseTransfer(!returnToForwardOnStop)
            }
        }
    }
    private inner class Stickers : RecyclerView.Adapter<Stickers.Holder>() {
        inner class Holder(val box: LinearLayout, val image: ImageView, val caption: TextView, val badge:ImageView) : RecyclerView.ViewHolder(box)
        override fun onCreateViewHolder(parent: ViewGroup, type: Int): Holder {
            val box = LinearLayout(this@ReverseTransferController).apply {
                orientation=LinearLayout.VERTICAL;gravity=android.view.Gravity.CENTER
                setPadding(dp(8),dp(8),dp(8),dp(6))
                layoutParams=RecyclerView.LayoutParams(-1,dp(146)).apply{setMargins(dp(4),dp(4),dp(4),dp(4))}
                Motion.press(this)
            }
            val frame=FrameLayout(this@ReverseTransferController)
            val image=ImageView(this@ReverseTransferController).apply{scaleType=ImageView.ScaleType.FIT_CENTER}
            frame.addView(image,FrameLayout.LayoutParams(-1,-1))
            val badge=ImageView(this@ReverseTransferController).apply {
                setPadding(dp(3),dp(3),dp(3),dp(3));importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO
                background=android.graphics.drawable.GradientDrawable().apply{shape=android.graphics.drawable.GradientDrawable.OVAL;setColor(0xee152230.toInt())}
            }
            frame.addView(badge,FrameLayout.LayoutParams(dp(24),dp(24),android.view.Gravity.TOP or android.view.Gravity.END))
            box.addView(frame,LinearLayout.LayoutParams(-1,0,1f))
            val caption=text("",11f,true).apply{gravity=android.view.Gravity.CENTER;maxLines=2}
            box.addView(caption,LinearLayout.LayoutParams(-1,dp(30)))
            return Holder(box,image,caption,badge)
        }
        override fun getItemCount() = rows.size
        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = rows[position]; val id = row.id
            holder.caption.text = when { id in submitted -> "Already submitted"; id in selected -> "Selected"; id in ready -> "Ready to transfer"; row.lottie -> "Unsupported original"; id in unreadable -> "Can’t read original"; id in failures -> "Original unavailable"; else -> "Waiting for original" }
            holder.box.isSelected=id in selected
            holder.box.background=com.example.stickerpackbuilder.ui.Polish.surface(this@ReverseTransferController,id in selected)
            holder.badge.setImageResource(if(id in selected||id in submitted)com.example.stickerpackbuilder.R.drawable.ic_ui_check_circle else if(id in failures)com.example.stickerpackbuilder.R.drawable.ic_ui_cloud_off else com.example.stickerpackbuilder.R.drawable.ic_ui_circle)
            holder.badge.imageTintList=android.content.res.ColorStateList.valueOf(if(id in selected)com.example.stickerpackbuilder.ui.Polish.MINT else 0xff9cb2c5.toInt())
            holder.caption.setTextColor(if(id in selected)com.example.stickerpackbuilder.ui.Polish.MINT else 0xffa8b5c5.toInt())
            holder.box.contentDescription = "Sticker ${position + 1}, ${holder.caption.text}"
            holder.box.setOnClickListener {
                if (work?.isActive == true) return@setOnClickListener
                if (id in eligible()) { if (!selected.add(id)) selected.remove(id); notifyItemChanged(position); render() }
                else if (id in failures) guide("This original isn't available", failures[id].orEmpty(), "OK", {}, secondary = "")
                else if (id in submitted && ReverseHistory.result(this@ReverseTransferController, id).contains("pending")) guide("Check TikTok Saved", "This sticker was submitted before an interruption. It is skipped to avoid a duplicate. Check TikTok Saved first. Only retry if it was never added.", "It wasn't added — retry", {
                    ReverseHistory.record(this@ReverseTransferController, org.json.JSONObject().put("sourceId",id).put("submitted",false).put("result","User confirmed not created"))
                    submitted.remove(id); notifyItemChanged(position); render()
                })
            }
            if (id in ready) { holder.image.imageTintList=null;Glide.with(holder.image).load(repo.original(id)).override(dp(110), dp(110)).into(holder.image) }
            else {
                Glide.with(holder.image).clear(holder.image)
                holder.image.setImageResource(if(id in failures)com.example.stickerpackbuilder.R.drawable.ic_ui_cloud_off else com.example.stickerpackbuilder.R.drawable.ic_ui_sticker)
                holder.image.imageTintList=android.content.res.ColorStateList.valueOf(0xff4e677e.toInt())
            }
        }
        override fun onViewRecycled(holder: Holder) { Glide.with(holder.image).clear(holder.image); super.onViewRecycled(holder) }
    }
}
