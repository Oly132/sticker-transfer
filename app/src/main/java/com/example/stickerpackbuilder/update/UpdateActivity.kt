package com.example.stickerpackbuilder.update

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.stickerpackbuilder.BuildConfig
import com.example.stickerpackbuilder.ui.Screen
import kotlinx.coroutines.*
import java.io.File

data class UpdateState(val text: String = "Checking GitHub for updates…", val info: AppUpdate? = null,
                       val percent: Int = -1, val file: File? = null, val failed: Boolean = false, val busy: Boolean = true)

class UpdateModel(application: Application) : AndroidViewModel(application) {
    val state = MutableLiveData(UpdateState())
    private var work: Job? = null
    private var started = false
    fun start(initial: AppUpdate?, download: Boolean = false) {
        if (started) return
        started = true
        if (initial != null) {
            state.value = UpdateState("Version ${initial.tag.removePrefix("v")} is available.", initial, busy = false)
            if (download) download(initial)
        } else check()
    }
    fun check() {
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            state.value = UpdateState()
            try {
                val update = withContext(Dispatchers.IO) { GitHubUpdates.check(getApplication(), force = true) }
                state.value = if (update == null) UpdateState("You're up to date. Version ${BuildConfig.VERSION_NAME}.", busy = false)
                    else UpdateState("Version ${update.tag.removePrefix("v")} is available.", update, busy = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.value = UpdateState(e.message ?: "Couldn't check for updates. Please retry.", failed = true, busy = false) }
        }
    }
    fun download(info: AppUpdate) {
        if (work?.isActive == true) return
        work = viewModelScope.launch {
            state.value = UpdateState("Downloading your update…", info, percent = 0)
            try {
                val task = currentCoroutineContext()
                val file = withContext(Dispatchers.IO) {
                    GitHubUpdates.download(getApplication(), info, { task.ensureActive() }) { percent ->
                        state.postValue(UpdateState(if (percent == 100) "Checking the downloaded app…" else "Downloading update · $percent%", info, percent))
                    }
                }
                state.value = UpdateState("Ready to install. Android will ask you to confirm the update. Your stickers and logins stay here.", info, 100, file, busy = false)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.value = UpdateState(e.message ?: "Couldn't download the update. Please retry.", info, failed = true, busy = false) }
        }
    }
}

class UpdateActivity : Screen() {
    private lateinit var model: UpdateModel
    private var waitingForPermission = false
    private val permission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        waitingForPermission = false
        if (packageManager.canRequestPackageInstalls()) install()
    }
    private val installer = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == RESULT_OK) finish()
        else message("Installation wasn't completed. Tap Install update to try again.")
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        toolbar("App updates").setNavigationOnClickListener {
            if (model.state.value?.info?.mandatory == true) finishAffinity() else finish()
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (model.state.value?.info?.mandatory == true) finishAffinity() else finish()
            }
        })
        val body = body()
        body.addView(title("Keep your favourites flowing."))
        body.addView(text("Updates come from Sticker Transfer's GitHub releases. Your account connections and collection are kept when you install over this app.", 14f, true))
        space(body, 20)
        val copy = text("Checking GitHub for updates…", 16f).apply { accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE }
        body.addView(copy)
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; progressTintList = android.content.res.ColorStateList.valueOf(0xff64e3c0.toInt())
        }
        body.addView(progress, android.widget.LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(16); bottomMargin = dp(18) })
        model = ViewModelProvider(this)[UpdateModel::class.java]
        val action = button("Please wait…") {
            val current = model.state.value ?: return@button
            when {
                current.file != null -> install()
                current.info != null -> model.download(current.info)
                else -> model.check()
            }
        }
        body.addView(action)
        model.state.observe(this) { state ->
            copy.text = if (state.info?.mandatory == true) "Update required to continue transferring.\n\n${state.text}" else state.text
            progress.visibility = if (state.busy) android.view.View.VISIBLE else android.view.View.GONE
            progress.isIndeterminate = state.percent < 0
            if (state.percent >= 0) progress.progress = state.percent
            action.isEnabled = !state.busy
            action.text = when {
                state.busy -> "Please wait…"
                state.file != null -> "Install update"
                state.info != null && state.failed -> "Retry download"
                state.info != null -> "Download update"
                else -> "Check again"
            }
        }
        val initial = intent.getStringExtra("update")?.let { runCatching { GitHubUpdates.restore(it) }.getOrNull() }
        model.start(initial, download = initial != null)
    }
    private fun install() {
        if (com.example.stickerpackbuilder.sync.AutoTransfer.active()) {
            message("Finish or stop the current sticker transfer before installing this update.")
            return
        }
        val file = model.state.value?.file?.takeIf { it.isFile } ?: return
        if (!packageManager.canRequestPackageInstalls()) {
            if (waitingForPermission) return
            guide("Allow app updates", "Android needs your permission to install updates from Sticker Sync. Turn on “Allow from this source”, then return here. This is needed only once.",
                "Open settings", {
                    waitingForPermission = true
                    permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
                })
            return
        }
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.updates", file)
            @Suppress("DEPRECATION")
            val intent = Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).putExtra(Intent.EXTRA_RETURN_RESULT, true)
            intent.clipData = android.content.ClipData.newRawUri("Sticker Transfer update", uri)
            installer.launch(intent)
        } catch (e: Exception) { message(e.message ?: "Android couldn't open its installer. Please try again.") }
    }
}
