package com.example.stickerpackbuilder.reverse
import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.content.Intent
import android.net.Uri
import com.example.stickerpackbuilder.sync.NativeProfileCheck
import com.example.stickerpackbuilder.sync.NativeChatNavigation
/** Create is allowed only after verified file selection. Send is never tapped. */
class ReverseImporter(private val owner: com.example.stickerpackbuilder.sync.TikTokTransferService) : android.content.ContextWrapper(owner) {
    companion object { const val TIKTOK="com.zhiliaoapp.musically" }
    private val thread=android.os.HandlerThread("reverse-navigation").apply{start()}
    private val handler=Handler(thread.looper)
    private val main=Handler(Looper.getMainLooper())
    private val epoch=java.util.concurrent.atomic.AtomicLong()
    private val gestureSerial=java.util.concurrent.atomic.AtomicLong()
    private val timing=ImportTimingPolicy()
    private val activation=ImportActivation(timing)
    private val wakeQueued=java.util.concurrent.atomic.AtomicBoolean()
    @Volatile private var gesturePending=false
    @Volatile private var cancelBlockedUntil=0L
    private var lastSnapshotAt=0L
    private var submittedCallback:(()->Unit)?=null
    private lateinit var staged:StickerStager.Staged
    private val durations=linkedMapOf<String,Long>()
    private var phaseAt=0L
    private var runTicket=0L
    private val rootInActiveWindow get()=if(android.os.Build.VERSION.SDK_INT>=33)
        owner.getRootInActiveWindow(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_HYBRID) else owner.rootInActiveWindow
    private val windows get()=owner.windows
    private fun performGlobalAction(action:Int)=owner.performGlobalAction(action)
    private fun dispatchGesture(gesture:GestureDescription, callback:AccessibilityService.GestureResultCallback?, target:Handler?):Boolean {
        val ticket=runTicket;val serial=gestureSerial.incrementAndGet()
        cancelBlockedUntil=android.os.SystemClock.uptimeMillis()+3_000
        val latch=java.util.concurrent.CountDownLatch(1)
        val result=java.util.concurrent.atomic.AtomicBoolean(false)
        val expired=java.util.concurrent.atomic.AtomicBoolean(false)
        val finished=java.util.concurrent.atomic.AtomicBoolean(false)
        main.post {
            if(expired.get()||!running||ticket!=epoch.get()){latch.countDown();return@post}
            val view=cover?.view
            val manager=getSystemService(WINDOW_SERVICE) as android.view.WindowManager
            fun touchable(value:Boolean){
                val params=view?.layoutParams as? android.view.WindowManager.LayoutParams?:return
                params.flags=if(value)params.flags and android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    else params.flags or android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                runCatching{manager.updateViewLayout(view,params)}
            }
            fun complete(cancelled:Boolean,deliver:Boolean=true){
                if(!finished.compareAndSet(false,true))return
                if(serial==gestureSerial.get()){
                    cancelBlockedUntil=android.os.SystemClock.uptimeMillis()+400
                    gesturePending=false;touchable(true)
                }
                if(deliver)(target?:handler).post{
                    if(running&&ticket==epoch.get()){
                        if(cancelled)callback?.onCancelled(gesture) else callback?.onCompleted(gesture)
                    }
                }
            }
            gesturePending=true;touchable(false)
            main.postDelayed({
                if(ticket!=epoch.get()||expired.get()){complete(true,false);latch.countDown();return@postDelayed}
                val dispatched=runCatching{owner.dispatchGesture(gesture,object:AccessibilityService.GestureResultCallback(){
                    override fun onCompleted(description:GestureDescription?)=complete(false)
                    override fun onCancelled(description:GestureDescription?)=complete(true)
                },main)}.getOrDefault(false)
                result.set(dispatched)
                if(!dispatched)complete(true,false) else main.postDelayed({
                    if(!finished.get()){
                        android.util.Log.w("ReverseSpeed","Gesture callback missing; releasing input guard")
                        complete(true)
                    }
                },1_800)
                latch.countDown()
            },32)
        }
        if(!latch.await(2,java.util.concurrent.TimeUnit.SECONDS)){expired.set(true);return false}
        return result.get()
    }
    private var completion:((Boolean,String)->Unit)?=null
    @Volatile private var closed=false
    private var verifiedAccount=false
    private var inspectionOnly=false
    private var holdCover=false
    private var batchCancel:(()->Unit)?=null
    private var batchCompleted=0
    private var batchTotal=0
    val running get()=phase!="idle"
    private var cover:com.example.stickerpackbuilder.sync.TransferCover.Surface?=null
    private fun showCover(){main.post{
        if(cover!=null||closed)return@post
        runCatching {
            val surface=com.example.stickerpackbuilder.sync.TransferCover.create(owner,true){
                if(gesturePending||android.os.SystemClock.uptimeMillis()<cancelBlockedUntil){
                    android.util.Log.i("ReverseSync","Ignored cover input during injected gesture")
                }else{android.util.Log.i("ReverseSync","Cover cancellation requested");batchCancel?.invoke();cancel()}
            };cover=surface;surface.progress(batchCompleted,batchTotal)
            (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).addView(surface.view,com.example.stickerpackbuilder.sync.TransferCover.windowParams())
        }.onFailure{batchCancel?.invoke();handler.post{stop("The transfer screen couldn't open. Check Sticker Sync's automation permission and try again.")}}
    }}
    private fun hideCover(restoreAudio:Boolean=true){main.post{cover?.let{runCatching{(getSystemService(WINDOW_SERVICE) as android.view.WindowManager).removeView(it.view)}};cover=null;if(restoreAudio)com.example.stickerpackbuilder.sync.TransferAudio.release(owner)}}
    fun startBatch(total:Int=0,cancel:()->Unit){batchCompleted=0;batchTotal=total;check(!closed){"Enable Sticker Sync automation again before transferring."};require(!running);verifiedAccount=false;com.example.stickerpackbuilder.sync.TransferAudio.acquire(owner);holdCover=true;batchCancel=cancel;showCover()}
    fun updateProgress(completed:Int,total:Int){main.post{batchCompleted=completed;batchTotal=total;cover?.progress(completed,total)}}
    fun endBatch(){holdCover=false;batchCancel=null;restoreFlags();main.postDelayed({if(!holdCover){hideCover(restoreAudio=false);com.example.stickerpackbuilder.sync.TransferAudio.releaseLater(owner)}},180)}
    fun cancel(){epoch.incrementAndGet();handler.post{if(running)stop(if(createStartedAt>0)"Stopped after requesting creation. Check TikTok Saved before retrying." else "Transfer stopped.")}}
    fun destroy(){closed=true;epoch.incrementAndGet();handler.removeCallbacksAndMessages(null);phase="idle";hideCover();completion?.let{done->main.post{done(false,"Automation was interrupted. Check Saved before retrying a pending sticker.")}};completion=null;thread.quitSafely()}
    @Volatile private var phase="idle"
        set(value){
            if(field!=value){
                val now=android.os.SystemClock.uptimeMillis()
                if(field!="idle"&&phaseAt>0)durations[field]=(durations[field]?:0)+(now-phaseAt)
                phaseAt=now;android.util.Log.i("ReverseSync","Importer phase: $value")
            }
            field=value
        }
    private var album=""
    private var started=0L
    private var acted=0L
    private var ownName=""
    private var backs=0
    private var inboxOpenedAt=0L
    private var createTaps=0
    private var stagedHash=""
    private var lastDebug=""
    private var selectedHash=""
    private var createStartedAt=0L
    private var fileTaps=0
    private var lastPreviewDiagnostic=0L
    private var cameraRecoveries=0
    private var albumOpens=0
    private var albumSelections=0
    private var albumSearches=0
    private var albumSearchTurns=0
    private var albumSearchBackwards=false
    private var albumMenuWindow=-1
    private var albumMenuBounds:Rect?=null
    private var originalServiceFlags:Int?=null
    private var originalNotificationTimeout:Long?=null
    private val prefs by lazy { getSharedPreferences("reverse",0) }
    fun onAccessibilityEvent(event:AccessibilityEvent?) {
        if(event?.packageName?.toString()!=TIKTOK||!running||closed||!wakeQueued.compareAndSet(false,true))return
        val ticket=epoch.get()
        handler.postDelayed({
            wakeQueued.set(false)
            if(ticket==epoch.get()&&running&&!gesturePending){handler.removeCallbacks(tick);tick.run()}
        },35)
    }
    private val tick=object:Runnable { override fun run() {
        if(phase=="idle")return
        try { advance() }catch(e:Exception){ stop("Helper stopped: ${e.message}. You can select the staged transfer album manually.") }
        if(phase!="idle")handler.postDelayed(this,ImportTimingPolicy.POLL_MS)
    } }
    fun begin(staged:StickerStager.Staged,onSubmitted:()->Unit={},inspectionOnly:Boolean=false,done:(Boolean,String)->Unit){
        check(!closed){"Automation disconnected. Enable Sticker Sync in Accessibility and retry."}
        require(!running){"A reverse import is already running"}
        // Complete muting before either the cover post or navigation worker can run.
        com.example.stickerpackbuilder.sync.TransferAudio.acquire(owner)
        val ticket=epoch.incrementAndGet()
        this.staged=staged;this.inspectionOnly=inspectionOnly;submittedCallback=onSubmitted;durations.clear();phaseAt=0;started=android.os.SystemClock.uptimeMillis()
        timing.reset();lastSnapshotAt=0;wakeQueued.set(false)
        phase="starting"
        completion=done
        val info=owner.serviceInfo
        val include=android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        if(originalServiceFlags==null){originalServiceFlags=info.flags;originalNotificationTimeout=info.notificationTimeout}
        if(info.flags and include==0||info.notificationTimeout!=30L){info.flags=info.flags or include;info.notificationTimeout=30L;owner.serviceInfo=info}
        showCover()
        handler.post { if(ticket!=epoch.get())return@post;try { runTicket=ticket;beginOnWorker() }catch(e:Exception){stop(e.message?:"Could not prepare this sticker.")} }
    }
    private fun beginOnWorker(){
        album=staged.album
        require(album.matches(Regex("RSL_[a-f0-9]{12}"))){"Prepare a sticker first"}
        stagedHash=StickerStager.verify(this,staged)
        if(!inspectionOnly&&verifiedAccount&&ReverseHistory.submitted(this,staged.sourceId)){stop("Already added to this TikTok account.",true);return}
        phase=if(verifiedAccount)"picker" else "identity";acted=0;if(!verifiedAccount)ownName="";backs=0;inboxOpenedAt=0
        createTaps=0;selectedHash="";createStartedAt=0;fileTaps=0;cameraRecoveries=0;albumOpens=0;albumSelections=0;albumSearches=0;albumSearchTurns=0;albumSearchBackwards=false;albumMenuWindow=-1;albumMenuBounds=null
        report(if(verifiedAccount)"Preparing your sticker…" else "Connecting to TikTok…")
        if(!verifiedAccount)startActivity(packageManager.getLaunchIntentForPackage(TIKTOK)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        handler.removeCallbacks(tick);handler.postDelayed(tick,35)
    }
    private fun walk(node:AccessibilityNodeInfo,depth:Int=0):List<AccessibilityNodeInfo> {
        val output=ArrayList<AccessibilityNodeInfo>()
        fun visit(current:AccessibilityNodeInfo,level:Int){
            if(level>30||output.size>=2000)return
            output.add(current)
            for(i in 0 until current.childCount){if(output.size>=2000)break;current.getChild(i)?.let{visit(it,level+1)}}
        }
        visit(node,depth);return output
    }
    private fun focusedNodes(root:AccessibilityNodeInfo):List<AccessibilityNodeInfo> {
        if(phase=="identity"){
            val indexed=ArrayList<AccessibilityNodeInfo>()
            for(id in listOf("t4s","t8f"))indexed.addAll(root.findAccessibilityNodeInfosByViewId("$TIKTOK:id/$id"))
            indexed.addAll(root.findAccessibilityNodeInfosByText("Edit profile"))
            // Keep the native Inbox shortcut with the owner fields. Without it,
            // the fast profile snapshot always falls back to a slower deep link.
            indexed.addAll(root.findAccessibilityNodeInfosByText("Inbox"))
            val fields=listOf("t4s","t8f").all{id->indexed.any{it.isVisibleToUser&&it.viewIdResourceName=="$TIKTOK:id/$id"&&!it.text.isNullOrBlank()}}
            fun owner()=NativeProfileCheck.isOwnProfile(indexed.filter{it.isVisibleToUser}.flatMap{listOfNotNull(it.text?.toString(),it.contentDescription?.toString())})
            if(fields&&!owner())for(term in listOf("Create a Story","Private videos"))indexed.addAll(root.findAccessibilityNodeInfosByText(term))
            if(fields&&owner())return indexed.distinct().filter{it.isVisibleToUser}
            for(term in listOf("Profile","Log in","Sign up"))indexed.addAll(root.findAccessibilityNodeInfosByText(term))
            val visible=indexed.distinct().filter{it.isVisibleToUser}
            if(!owner()&&visible.any{it.isClickable&&label(it)=="Profile"})return visible
            return walk(root)
        }
        if(phase=="inbox"){
            val indexed=ArrayList<AccessibilityNodeInfo>()
            for(id in listOf("ii5","lxl","kve","kwq"))indexed.addAll(root.findAccessibilityNodeInfosByViewId("$TIKTOK:id/$id"))
            val chatNodes=indexed.distinct().filter{it.isVisibleToUser}
            val ownHeader=chatNodes.any{it.viewIdResourceName in listOf("$TIKTOK:id/ii5","$TIKTOK:id/lxl")&&NativeChatNavigation.matchesName(label(it),ownName)}
            val toggle=chatNodes.any{it.viewIdResourceName in listOf("$TIKTOK:id/kve","$TIKTOK:id/kwq")&&it.isClickable}
            if(ownHeader&&toggle)return chatNodes
            for(term in listOf(ownName,"Inbox","Edit profile").filter{it.isNotBlank()})indexed.addAll(root.findAccessibilityNodeInfosByText(term))
            val visible=indexed.distinct().filter{it.isVisibleToUser}
            val ownRows=visible.filter{it.className?.toString()=="android.widget.TextView"&&inList(it)&&NativeChatNavigation.isRowName(it.text?.toString().orEmpty(),it.contentDescription?.toString().orEmpty(),it.viewIdResourceName,ownName)}
            if(ownRows.size==1)return visible
            return walk(root)
        }
        if(phase in setOf("opening","returning","album-opening","album-loading","album","single-image","preview","creating"))return walk(root)
        val result=ArrayList<AccessibilityNodeInfo>()
        val terms=when(phase){
            "album","single-image"->listOf("Create sticker","Posts","Recents","RSL_")
            "creating"->listOf("Saved","Create sticker","Close","Posts","Recents")
            "preview"->listOf("Create sticker","Close","Cutout","Add text","Posts","Recents",album)
            else->listOf("Saved","Create sticker","Posts","Recents","RSL_")
        }
        for(term in terms)result.addAll(root.findAccessibilityNodeInfosByText(term))
        val ids=listOf("kve","kwq","hgx","hgk","ko1","kpd","sqo","ssb")
        if(phase=="picker")for(id in ids)result.addAll(root.findAccessibilityNodeInfosByViewId("$TIKTOK:id/$id"))
        val nodes=result.distinct().filter{it.isVisibleToUser}
        val labels=nodes.map(::label).toSet()
        if("Create sticker" in labels)return walk(root)
        val complete=when(phase){
            "single-image"->"Create sticker" in labels&&"Posts" in labels&&album in labels
            "preview"->"Create sticker" in labels&&labels.any{it in setOf("Close","Cutout","Add text")}
            "creating"->labels.any{it in setOf("Saved","Create sticker","Close")}
            "album"->labels.any{it=="Recents"||it.startsWith("RSL_")}
            else->nodes.any{it.viewIdResourceName?.substringAfterLast('/') in ids||label(it)=="Create sticker"}
        }
        // Some custom accessibility providers do not support indexed text queries.
        // Keep the bounded traversal fallback when required controls are missing.
        return if(complete)nodes else walk(root)
    }

    private fun inputAllowed(kind:String,node:AccessibilityNodeInfo):Boolean {
        if(runTicket!=epoch.get()||closed)return false
        val bounds=rect(node)
        return timing.allow("$kind:${node.windowId}:${node.viewIdResourceName}:${label(node)}:${bounds.flattenToString()}",android.os.SystemClock.uptimeMillis())
    }
    private fun label(node:AccessibilityNodeInfo)=node.text?.toString()?.takeIf{it.isNotBlank()}?:node.contentDescription?.toString().orEmpty()
    private fun rect(node:AccessibilityNodeInfo)=Rect().also{node.getBoundsInScreen(it)}
    @Suppress("DEPRECATION") private fun displayBounds():Rect {
        val metrics=android.util.DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as android.view.WindowManager).defaultDisplay.getRealMetrics(metrics)
        return Rect(0,0,metrics.widthPixels,metrics.heightPixels)
    }
    private fun clickable(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
        var current:AccessibilityNodeInfo?=node
        repeat(6){if(current?.isClickable==true)return current;current=current?.parent}
        return null
    }
    private fun rememberedAlbumScroller(nodes:List<AccessibilityNodeInfo>)=nodes.firstOrNull {
        it.isScrollable&&it.windowId==albumMenuWindow&&rect(it)==albumMenuBounds
    }
    private fun albumList(nodes:List<AccessibilityNodeInfo>,screen:Rect):Boolean {
        val knownRow=nodes.any {
            val text=rect(it);val name=label(it)
            if(!ImportControlPolicy.albumListEntry(name,text.left,screen.width()))false
            else {
                val row=clickable(it)?.let(::rect)
                row!=null&&ImportControlPolicy.albumMenuRow(name,text.left,row.left,row.right,screen.width())&&
                    row.top>=0&&row.bottom<=screen.height()&&row.height()>0
            }
        }
        // A search may scroll Recents/RSL rows out of view. Retain only the
        // exact dropdown window and viewport proven from one of those rows.
        return knownRow||(phase=="album"&&rememberedAlbumScroller(nodes)!=null)
    }
    /** Scroll only inside the dropdown containing a verified album row. Never
     * scroll Recents' photo grid or substitute its cells for a missing album. */
    private fun searchAlbumMenu(nodes:List<AccessibilityNodeInfo>,screen:Rect,now:Long) {
        if(albumSearches>=12||albumSearchTurns>=2||!timing.allow("album-search",now,450))return
        fun scroll(node:AccessibilityNodeInfo) {
            val bounds=rect(node)
            albumMenuWindow=node.windowId;albumMenuBounds=Rect(bounds)
            val action=if(albumSearchBackwards)AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD else AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
            val accepted=node.performAction(action)
            albumSearches++
            if(!accepted){albumSearchBackwards=!albumSearchBackwards;albumSearchTurns++}
            android.util.Log.i("ReverseSpeed","Album search scroll=$albumSearches accepted=$accepted")
        }
        rememberedAlbumScroller(nodes)?.let{scroll(it);return}
        val marker=nodes.firstOrNull {
            val text=rect(it);val name=label(it)
            if(!ImportControlPolicy.albumListEntry(name,text.left,screen.width()))false
            else {
                val row=clickable(it)?.let(::rect)
                row!=null&&ImportControlPolicy.albumMenuRow(name,text.left,row.left,row.right,screen.width())
            }
        }?:return
        var parent=clickable(marker)?.parent
        repeat(7){
            val node=parent?:return
            val bounds=rect(node)
            if(node.isScrollable&&ImportControlPolicy.touchInside(bounds.left,bounds.top,bounds.right,bounds.bottom,screen.width(),screen.height())&&
                bounds.top>screen.height()*.18&&bounds.width()>=screen.width()*.85) {
                scroll(node);return
            }
            parent=node.parent
        }
    }
    private fun albumHeader(nodes:List<AccessibilityNodeInfo>,screen:Rect):AccessibilityNodeInfo? {
        // Geometry alone also matches TikTok's camera-permission explanation.
        // A real album header must belong to the Create picker, with its Posts tile.
        if(nodes.none{label(it)=="Create sticker"&&rect(it).top<screen.height()*.18}||
            nodes.none{label(it)=="Posts"})return null
        return nodes.firstOrNull {
            it.className?.toString()=="android.widget.TextView"&&label(it).isNotBlank()&&
                rect(it).left<screen.width()*.15&&rect(it).width()>0&&
                rect(it).top>screen.height()*.18&&rect(it).top<screen.height()*.45
        }
    }
    private fun click(node:AccessibilityNodeInfo):Boolean = activate(node)
    private fun activate(node:AccessibilityNodeInfo):Boolean {
        if(runTicket!=epoch.get()||closed)return false
        val bounds=rect(node);val screen=displayBounds()
        if(!ImportControlPolicy.touchInside(bounds.left,bounds.top,bounds.right,bounds.bottom,screen.width(),screen.height()))return false
        val key="${node.windowId}:${node.viewIdResourceName}:${label(node)}:${bounds.flattenToString()}"
        return activation.perform(key,android.os.SystemClock.uptimeMillis(),native={
            val start=android.os.SystemClock.uptimeMillis()
            val accepted=clickable(node)?.performAction(AccessibilityNodeInfo.ACTION_CLICK)==true
            android.util.Log.i("ReverseSpeed","native phase=$phase ms=${android.os.SystemClock.uptimeMillis()-start} accepted=$accepted")
            accepted
        },gesture={injectTap(bounds)})
    }
    private fun restoreFlags(){main.post{runCatching{
        val info=owner.serviceInfo
        originalServiceFlags?.let{info.flags=it}
        originalNotificationTimeout?.let{info.notificationTimeout=it}
        originalServiceFlags=null;originalNotificationTimeout=null
        owner.serviceInfo=info
    }}}
    private fun stop(message:String,success:Boolean=false){
        epoch.incrementAndGet();phase="idle";
        val total=android.os.SystemClock.uptimeMillis()-started
        runCatching {
            val folder=java.io.File(filesDir,"reverse").apply{mkdirs()}
            val report=org.json.JSONObject().put("at",System.currentTimeMillis()).put("success",success).put("inspectionOnly",inspectionOnly).put("elapsedMs",total).put("phasesMs",org.json.JSONObject(durations as Map<*,*>))
            val log=java.io.File(folder,"performance.json")
            val history=runCatching{org.json.JSONArray(log.readText())}.getOrDefault(org.json.JSONArray())
            val trimmed=org.json.JSONArray();for(i in maxOf(0,history.length()-49) until history.length())trimmed.put(history.get(i));trimmed.put(report);log.writeText(trimmed.toString())
        }
        android.util.Log.i("ReverseSync","IMPORT_TIMING: ms=$total, success=$success, phases=$durations")
        prefs.edit().putString("helperStatus",message).apply();handler.removeCallbacks(tick)
        if(!holdCover)restoreFlags()
        if(!holdCover)hideCover();val done=completion;completion=null;main.post{done?.invoke(success,message)}
    }
    private fun report(message:String){prefs.edit().putString("helperStatus",message).apply();main.post{cover?.status?.text=message}}
    private fun tap(node:AccessibilityNodeInfo):Boolean {
        if(!inputAllowed("tap",node))return false
        val bounds=rect(node)
        val screen=displayBounds()
        if(!ImportControlPolicy.touchInside(bounds.left,bounds.top,bounds.right,bounds.bottom,screen.width(),screen.height()))return false
        return injectTap(bounds)
    }
    private fun injectTap(bounds:Rect):Boolean {
        val path=Path().apply{moveTo(bounds.exactCenterX(),bounds.exactCenterY())}
        return dispatchGesture(GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,70)).build(),null,null)
    }
    private fun inList(node:AccessibilityNodeInfo):Boolean {
        var parent=node.parent
        repeat(7){if(parent?.className?.toString()=="androidx.recyclerview.widget.RecyclerView")return true;parent=parent?.parent}
        return false
    }
    private fun openInbox(nodes:List<AccessibilityNodeInfo>,now:Long){
        if(runTicket!=epoch.get())return
        phase="inbox";acted=now;inboxOpenedAt=now
        // Same route as the verified forward-transfer service: prefer TikTok's
        // actual Inbox tab. The deep link is only a fallback, not the main route.
        val inbox=nodes.firstOrNull{label(it)=="Inbox"&&clickable(it)!=null}
        if(inbox==null||!click(inbox))
            startActivity(Intent(Intent.ACTION_VIEW,Uri.parse("snssdk1233://chatting/message")).setPackage(TIKTOK).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        report("Getting TikTok ready…")
    }
    private fun advance(){
        if(runTicket!=epoch.get()||closed)return
        val now=android.os.SystemClock.uptimeMillis()
        if(now-started>120_000){stop(if(createStartedAt>0)"The transfer timed out after requesting creation. Check Saved before retrying."
            else "The transfer helper timed out before creation. Nothing was created or sent.");return}
        // TikTok's sticker tray and Compose picker may be separate accessibility
        // windows. Inspect all visible TikTok windows, not just the chat root.
        if(gesturePending||phase=="selecting"||now-lastSnapshotAt<ImportTimingPolicy.OBSERVE_MS)return
        lastSnapshotAt=now
        val snapshotStart=android.os.SystemClock.uptimeMillis()
        val foregroundRoot=rootInActiveWindow?:return
        val currentWindows by lazy{windows}
        // Never tap a lower TikTok window through an Android permission dialog.
        val foregroundPackage=foregroundRoot.packageName?.toString()
        if(foregroundPackage!=null&&foregroundPackage!=TIKTOK&&foregroundPackage!=packageName){
            if(foregroundPackage.contains("permissioncontroller"))stop("Finish TikTok's permission request, then try again. Your completed stickers are kept.")
            return
        }
        if(foregroundPackage==packageName&&currentWindows.any{it.id==foregroundRoot.windowId&&it.type==android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION})return
        val active=foregroundRoot.takeIf{it.packageName?.toString()==TIKTOK}
        val singleWindow=phase!="picker"
        // The active native picker/editor already identifies its window. Avoid
        // retrieving every system/overlay root and fetching the same root again.
        val roots=if(singleWindow&&active!=null)listOf(active) else {
            val appRoots=currentWindows.mapNotNull{window->window.root?.takeIf{it.packageName?.toString()==TIKTOK}?.let{window.isActive to it}}
            if(singleWindow)listOfNotNull(appRoots.firstOrNull{it.first}?.second?:active?:appRoots.firstOrNull()?.second)
            else (listOfNotNull(active)+appRoots.map{it.second}).distinct()
        }
        if(roots.isEmpty())return
        val nodes=roots.flatMap{focusedNodes(it)}.distinct().filter{it.isVisibleToUser}
        val snapshotMs=android.os.SystemClock.uptimeMillis()-snapshotStart
        if(snapshotMs>100)android.util.Log.i("ReverseSpeed","snapshot phase=$phase ms=$snapshotMs nodes=${nodes.size}")
        val physicalScreen=displayBounds()
        val createScreen=ImportControlPolicy.creationSurface(
            nodes.filter{label(it)=="Create sticker"}.map{rect(it).top},
            nodes.filter{label(it)=="Close"}.map{rect(it).top},physicalScreen.height(),
            nodes.any{label(it)=="Posts"},nodes.any{label(it)=="Saved"})
        if(phase in setOf("picker","album-opening","album-loading","album","single-image")&&
            nodes.any{label(it)=="Allow TikTok to access your camera and microphone"}){
            selectedHash="";phase="returning";acted=now
            performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);return
        }
        if(phase=="returning") {
            if(createScreen&&nodes.any{label(it)=="Posts"}&&albumHeader(nodes,physicalScreen)!=null){phase="picker";return}
            if(now-acted>5_000)stop("TikTok couldn't reopen the sticker picker. Please try again. Nothing was created.")
            return
        }
        if(phase=="opening") {
            // Opening a native sheet can temporarily hide both its source and
            // destination. Do not navigate Back or toggle the tray in that gap.
            if(createScreen)phase="picker"
            else {
                if(now-acted>2_000)phase="picker"
                return
            }
            // The same verified snapshot already contains the picker controls.
            // Handle them immediately instead of taking another full snapshot.
        }
        if(phase=="album-opening"||phase=="album-loading") {
            val menuOpen=albumList(nodes,physicalScreen)
            val selected=albumHeader(nodes,physicalScreen)?.let{label(it)==album}==true
            when {
                !menuOpen&&selected -> phase="single-image"
                phase=="album-opening"&&menuOpen -> phase="album"
                now-acted<1_200 -> return
                menuOpen -> phase="album"
                else -> {phase="picker";return}
            }
        }
        if(phase=="identity") {
            if(now-started>20_000){stop("TikTok account verification timed out. Open your own profile in TikTok and try again.");return}
            if(nodes.any{label(it) in listOf("Log in","Sign up")}&&!NativeProfileCheck.isOwnProfile(nodes.flatMap{listOfNotNull(it.text?.toString(),it.contentDescription?.toString())})){
                stop("Sign in to the normal TikTok app, then return here to transfer.");return
            }
            val values=nodes.flatMap{listOfNotNull(it.text?.toString(),it.contentDescription?.toString())}
            if(NativeProfileCheck.isOwnProfile(values)){
                ownName=NativeChatNavigation.name(nodes.firstOrNull{it.viewIdResourceName=="$TIKTOK:id/t8f"}?.text?.toString().orEmpty())
                val handle=nodes.firstOrNull{it.viewIdResourceName=="$TIKTOK:id/t4s"}?.text?.toString()?.trim()?.removePrefix("@").orEmpty()
                if(ownName.isNotBlank()&&handle.matches(Regex("[A-Za-z0-9_.]{2,30}"))){
                    prefs.edit().putString("destination",handle.lowercase()).commit();verifiedAccount=true
                    if(!inspectionOnly&&ReverseHistory.submitted(this,staged.sourceId)){stop("Already added to this TikTok account.",true);return}
                    openInbox(nodes,now)
                }
            }else{
                val profile=nodes.firstOrNull{it.isClickable&&(label(it)=="Profile")}
                if(profile!=null){click(profile);acted=now;report("Connecting to TikTok…")}
                else if(backs<8&&timing.allow("back",now,900)){if(performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))backs++;acted=now}
            }
            return
        }
        if(phase=="inbox") {
            val editor=nodes.any{it.className?.toString()=="android.widget.EditText"||it.isClickable&&it.viewIdResourceName in listOf("$TIKTOK:id/kve","$TIKTOK:id/kwq")}
            val ownHeader=nodes.any{it.viewIdResourceName in listOf("$TIKTOK:id/ii5","$TIKTOK:id/lxl")&&NativeChatNavigation.matchesName(label(it),ownName)}
            if(editor&&ownHeader){phase="picker";acted=now;return}
            if(!editor) {
                val ownProfile=NativeProfileCheck.isOwnProfile(nodes.flatMap{listOfNotNull(it.text?.toString(),it.contentDescription?.toString())})
                val rows=nodes.filter { it.className?.toString()=="android.widget.TextView"&&inList(it)&&
                    NativeChatNavigation.isRowName(it.text?.toString().orEmpty(),it.contentDescription?.toString().orEmpty(),it.viewIdResourceName,ownName)&&
                    NativeChatNavigation.canOpenRow(inList(it),ownProfile,editor) }
                if(rows.size==1){if(click(rows.single()))acted=android.os.SystemClock.uptimeMillis();return}
                nodes.firstOrNull{it.isClickable&&(it.text?.toString()=="Inbox"||it.contentDescription?.toString()=="Inbox")}?.let{click(it);acted=now}
            }
            if(now-inboxOpenedAt>16_000){stop("Your self-chat could not be uniquely opened. Nothing was sent. Open your self-chat and continue the transfer.")}
            return
        }
        if(phase=="picker") {
            val button=nodes.firstOrNull{it.viewIdResourceName in listOf("$TIKTOK:id/kve","$TIKTOK:id/kwq")&&it.isClickable}
            val tabs=nodes.firstOrNull{it.viewIdResourceName in listOf("$TIKTOK:id/hgx","$TIKTOK:id/hgk")}
            val savedHeader=nodes.firstOrNull{label(it)=="Saved"}
            // Same direct-ID lookup technique used by the forward app's Saved
            // collector: TikTok can omit this child from normal traversal.
            val addIds=listOf("$TIKTOK:id/ko1","$TIKTOK:id/kpd")
            val directAdds=roots.flatMap{r->addIds.flatMap{id->r.findAccessibilityNodeInfosByViewId(id)}}
            val addTile=(directAdds+nodes.filter{it.viewIdResourceName in addIds}).distinct().filter{it.isVisibleToUser}.singleOrNull()
            // Android may omit the non-important GridView container while still
            // exposing its Saved heading and clickable Add child. Those verified
            // controls, in order, are sufficient to open creation (never send).
            val savedGrid=savedHeader!=null&&(nodes.any{it.viewIdResourceName in listOf("$TIKTOK:id/sqo","$TIKTOK:id/ssb")}||
                addTile?.let{rect(it).top>=rect(savedHeader).bottom}==true)
            val action=ImportPickerPolicy.action(createScreen,savedGrid,tabs!=null,button!=null)
            val debug="picker action=$action roots=${roots.size} nodes=${nodes.size} add=${addTile!=null}"
            if(debug!=lastDebug){android.util.Log.i("ReverseLab",debug);lastDebug=debug}
            // Handle an open picker before its toggle button, which remains in
            // the tree. Otherwise each tick closes and reopens the picker.
            when(action) {
                ImportPickerPolicy.Action.ALBUM -> {
                    if(albumList(nodes,physicalScreen)){phase="album";acted=now;return}
                    val header=albumHeader(nodes,physicalScreen)
                    if(header!=null&&label(header)==album){phase="single-image";acted=now;return}
                    if(header!=null){
                        if(albumOpens>=3){stop("TikTok couldn't open the transfer album. Nothing was created.");return}
                        if(activate(header)){albumOpens++;phase="album-opening";acted=android.os.SystemClock.uptimeMillis();report("Preparing your sticker…")}
                    }
                    else if(nodes.any{label(it)=="Add text"||
                        label(it)=="Create sticker"&&rect(it).top>physicalScreen.height()*.65}){performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);phase="returning";acted=now}
                }
                ImportPickerPolicy.Action.CREATE -> addTile?.let{
                    report("Preparing your stickers…")
                    if(!timing.allow("plus",now))return
                    if(createTaps>=3){stop("TikTok's sticker editor didn't open. Please try again. Nothing was created.");return}
                    // Use the tile's native action first; a verified-bound gesture
                    // is a fallback when this TikTok build ignores that action.
                    val accepted=if(createTaps==0)activate(it) else tap(it)
                    if(accepted){createTaps++;phase="opening";acted=now}
                }
                ImportPickerPolicy.Action.SAVED_TAB -> tabs?.let { row ->
                    if(!timing.allow("saved-tab",now))return
                    if(!row.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD)) {
                        (0 until row.childCount).mapNotNull{row.getChild(it)}.filter{it.isClickable}.minByOrNull{rect(it).left}?.let{click(it)}
                    }
                    acted=now
                }
                ImportPickerPolicy.Action.OPEN_PICKER -> {button?.let{click(it)};acted=now}
                else -> {
                    val values=nodes.flatMap{listOfNotNull(it.text?.toString(),it.contentDescription?.toString())}
                    if(NativeProfileCheck.isOwnProfile(values)) {
                        val name=NativeChatNavigation.name(nodes.firstOrNull{it.viewIdResourceName=="$TIKTOK:id/t8f"}?.text?.toString().orEmpty())
                        val handle=nodes.firstOrNull{it.viewIdResourceName=="$TIKTOK:id/t4s"}?.text?.toString()?.trim()?.removePrefix("@").orEmpty()
                        if(name.isNotBlank()&&handle.matches(Regex("[A-Za-z0-9_.]{2,30}"))){ownName=name;openInbox(nodes,now)}
                        else stop("Your own profile opened, but its account fields changed. Open your self-chat and continue the transfer.")
                    } else {
                        val profile=nodes.firstOrNull{it.isClickable&&(it.text?.toString()=="Profile"||it.contentDescription?.toString()=="Profile")}
                        if(profile!=null){click(profile);acted=now;report("Connecting to TikTok…")}
                        else if(backs<8&&timing.allow("back",now,900)){if(performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))backs++;acted=now}
                    }
                }
            }
        } else if(phase=="album") {
            val header=albumHeader(nodes,physicalScreen)
            if(!albumList(nodes,physicalScreen)&&header!=null&&label(header)==album){phase="single-image";acted=now;return}
            val rows=nodes.filter{label(it)==album&&ImportControlPolicy.albumRow(rect(it).left,physicalScreen.width())}
            val albumState="album list=${albumList(nodes,physicalScreen)} rows=${rows.size} selected=${header?.let{label(it)==album}==true}"
            if(albumState!=lastDebug){lastDebug=albumState;android.util.Log.i("ReverseSpeed","$albumState fileAgeMs=${now-staged.readyAt}")}
            if(albumList(nodes,physicalScreen)&&rows.size==1){
                if(albumSelections>=3){stop("TikTok couldn't select the transfer album. Nothing was created.");return}
                val row=rows.single();val bounds=rect(row)
                if(!ImportControlPolicy.touchInside(bounds.left,bounds.top,bounds.right,bounds.bottom,physicalScreen.width(),physicalScreen.height())){
                    if(timing.allow("show-album-row",now))row.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.id)
                }else if(activate(row)){albumSelections++;phase="album-loading";acted=android.os.SystemClock.uptimeMillis()}
            } else if(albumList(nodes,physicalScreen)&&rows.isEmpty())searchAlbumMenu(nodes,physicalScreen,now)
            else if(!albumList(nodes,physicalScreen)&&header!=null&&now-acted>=1_200){phase="picker"}

        } else if(phase=="single-image") {
            // The album is a fresh random directory created for this one staged
            // image. Never tap Recents, another album, or TikTok's Create button.
            // A matching album row in an open dropdown is not the selected
            // album's header. Require the active window itself to be the picker.
            val imageNodes=nodes
            if(albumList(imageNodes,physicalScreen)){phase="album";acted=now;return}
            val heading=albumHeader(imageNodes,physicalScreen)?.takeIf{label(it)==album}?:return
            if(imageNodes.none{label(it)=="Create sticker"}||imageNodes.none{label(it)=="Posts"})return
            if(!ImportTimingPolicy.fileReady(now,staged.readyAt,staged.animated))return
            require(StickerStager.verify(this,staged)==stagedHash){"The staged file changed during navigation"}
            if(now-acted<500 && fileTaps>0)return
            if(fileTaps>=3){stop("TikTok did not open the verified file in its editor. Nothing was created.");return}
            val screen=physicalScreen;val bar=rect(heading)
            // Recent TikTok versions prepend a camera tile, even inside an
            // album containing exactly one verified file. Its thumbnail is not
            // exposed by Compose, so derive the adjacent file cell from the
            // semantically identified Capture tile, never from Recents.
            val capture=imageNodes.firstOrNull{label(it)=="Capture"}
            val cameraBounds=capture?.let{clickable(it)?.let(::rect)}
            if(capture!=null&&cameraBounds==null)return
            val point=ImportControlPolicy.fileCell(screen.width(),screen.height(),bar.bottom,
                cameraBounds?.let{listOf(it.left,it.top,it.right,it.bottom)})?:return
            val path=Path().apply{moveTo(point.first,point.second)}
            val gesture=GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path,0,70)).build()
            phase="selecting"
            fileTaps++
            val accepted=dispatchGesture(gesture,object:AccessibilityService.GestureResultCallback(){
                override fun onCompleted(description:GestureDescription?) {
                    selectedHash=stagedHash;phase="preview";acted=android.os.SystemClock.uptimeMillis();handler.removeCallbacks(tick);handler.post(tick)
                    report("Preparing your sticker…")
                }
                override fun onCancelled(description:GestureDescription?){
                    // A cancelled or missing callback does not tell us whether
                    // the picker opened the file. Observe the next screen:
                    // preview only accepts its guarded editor; a still-open
                    // verified album is retried, and camera is rejected.
                    selectedHash=stagedHash;phase="preview";acted=android.os.SystemClock.uptimeMillis()
                    report("Preparing your sticker…")
                }
            },handler)
            if(!accepted)stop("TikTok did not accept file selection. Nothing was created.")
        } else if(phase=="preview"||phase=="creating") {
            val previewNodes=nodes
            val editorTools=previewNodes.any{label(it)=="Add text"||label(it)=="Cutout"}
            val picker=previewNodes.any{label(it)=="Posts"||label(it)=="Recents"||label(it)==album}
            val animatedEditor=staged.animated&&previewNodes.any{label(it)=="Close"}&&!picker
            val create=previewNodes.singleOrNull{ImportControlPolicy.createButton(label(it),rect(it).top,rect(it).bottom,physicalScreen.height(),editorTools,picker,animatedEditor)}
            if(phase=="preview") {
                if(now-acted>25_000){stop("TikTok couldn't load this sticker. Your completed transfers are saved; please try again.");return}
                if(previewNodes.any{label(it)=="Allow TikTok to access your camera and microphone"}) {
                    if(cameraRecoveries++>=1){stop("TikTok opened its camera instead of the sticker. Nothing was created.");return}
                    selectedHash="";phase="returning";acted=now
                    performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    report("Preparing your sticker…");return
                }
                val debug="preview tools=$editorTools picker=$picker button=${create!=null} display=${physicalScreen.width()}x${physicalScreen.height()}"
                if(debug!=lastDebug){android.util.Log.i("ReverseLab",debug);lastDebug=debug}
                if(create==null) {
                    if(now-lastPreviewDiagnostic>5_000){
                        lastPreviewDiagnostic=now
                        val known=setOf("Create sticker","Create","Close","Add text","Cutout","Posts","Recents","Saved")
                        android.util.Log.i("ReverseSync","Preview controls="+previewNodes.filter{label(it) in known}.joinToString{"${label(it)}:${rect(it).flattenToString()}:enabled=${it.isEnabled}"}+" nodes=${previewNodes.size}")
                    }
                    if(now-acted>1_000&&picker&&!albumList(previewNodes,physicalScreen)&&albumHeader(previewNodes,physicalScreen)?.let{label(it)==album}==true) {
                        phase="single-image";acted=now;report("Preparing your sticker…")
                    }
                    return
                }
                val createBounds=rect(create)
                if(!ImportControlPolicy.touchInside(createBounds.left,createBounds.top,createBounds.right,createBounds.bottom,physicalScreen.width(),physicalScreen.height()))return
                // Wait for a stable ready editor, rather than the old 1.4-second GIF delay.
                if(now-acted < if(staged.animated)250L else 60L)return
                var parent:AccessibilityNodeInfo?=create
                repeat(6){if(parent?.isEnabled==false)return;parent=parent?.parent}
                require(selectedHash==stagedHash&&StickerStager.verify(this,staged)==stagedHash){"File verification changed before creation"}
                if(previewNodes.any{label(it)=="Posts"||label(it)=="Recents"||label(it)==album})return
                if(inspectionOnly){stop("Verified preview ready. Nothing was created or sent.",true);return}
                // Journal before the external action. If Android kills the lab
                // during upload, the next run asks for a check rather than
                // blindly creating the same original again.
                ReverseHistory.record(this,org.json.JSONObject().put("sourceId",staged.sourceId)
                    .put("stagedUri",staged.uri.toString()).put("album",staged.album)
                    .put("submitted",true).put("result","Create requested; confirmation pending")
                    .put("verification","attempt journal; not a success claim").put("at",System.currentTimeMillis()))
                if(activate(create)) {phase="creating";createStartedAt=now;acted=now;report("Adding to TikTok…");submittedCallback?.invoke();submittedCallback=null}
                else {
                    ReverseHistory.record(this,org.json.JSONObject().put("sourceId",staged.sourceId)
                        .put("submitted",false).put("result","Create gesture was not dispatched").put("at",System.currentTimeMillis()))
                    stop("TikTok did not accept the Create press. You can retry this test.")
                }
            } else {
                if(create==null&&!picker&&!editorTools&&previewNodes.none{label(it)=="Create sticker"}&&previewNodes.any{label(it)=="Saved"}) {
                    ReverseHistory.record(this,org.json.JSONObject().put("sourceId",staged.sourceId)
                        .put("stagedHash",stagedHash).put("animated",staged.animated)
                        .put("submitted",true).put("result","TikTok returned to Saved after Create")
                        .put("verification","UI transition; thumbnail/animation still needs visual confirmation").put("at",System.currentTimeMillis()))
                    stop("Added to TikTok Saved. The thumbnail may take a moment to load.",true)
                } else if(now-createStartedAt>60_000)stop("Creation was submitted but TikTok did not confirm it. Check Saved before retrying to avoid a duplicate.")
            }
        }
    }
}
