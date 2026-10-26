package com.example.stickerpackbuilder

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.SslErrorHandler
import android.net.http.SslError
import android.net.Uri
import android.content.Intent
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.work.WorkManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import com.google.android.material.textfield.TextInputEditText
import androidx.activity.OnBackPressedCallback
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * A built-in browser tab (not a real browser, no other sites/extensions) that loads TikTok so
 * you can log in and pick stickers to export - replaces the old desktop Chrome-extension step.
 *
 * TikTok's mobile web doesn't show a login form on tiktok.com/messages when logged out - it
 * quietly falls back to the home feed instead. Reaching the actual username/password fields
 * requires a specific tap sequence: Profile tab -> "Log in" -> "Use phone / email / username"
 * -> the "Email / Username" tab. The overlay's login flow walks through that sequence itself
 * (re-checking the page every ~600ms and clicking whatever's next), then fills and submits
 * once the real fields exist. If it ever gets stuck or hits a CAPTCHA, it steps aside so you
 * can finish manually - there's also a manual escape hatch button and a password-visibility
 * eye toggle for the same reason.
 *
 * Once logged in, it automatically switches to a spoofed desktop identity, since Messages and
 * Comments are blocked on TikTok's mobile web but not desktop.
 */
class BrowseActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var overlay: LinearLayout
    private lateinit var transitionOverlay: TextView
    private lateinit var backToContactsButton: Button
    private lateinit var overlayErrorText: TextView
    private lateinit var overlayProgressText: TextView
    private lateinit var usernameInput: EditText
    private lateinit var passwordInput: EditText
    private lateinit var loginButton: Button
    private lateinit var selectionModeButton: Button
    private lateinit var exportButton: Button
    private lateinit var statusText: TextView

    private var selectionModeOn = false
    private var desktopModeOn = false
    private var chatFocused = false
    private val pollHandler = Handler(Looper.getMainLooper())
    private val loginHandler = Handler(Looper.getMainLooper())
    private val sessionProbe by lazy { assets.open("tiktok-session.js").bufferedReader().use { it.readText() } }
    private var loginProbeEpoch = 0
    private var loginVisible = true
    private var navAttempts = 0
    private var pollAttempts = 0
    private var importId: java.util.UUID? = null
    private var bridgeAvailable = false
    private var loginRunning = false
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val NATIVE_BRIDGE_SCRIPT = """
(function() {
  if (!window.StickerSyncNative) return;
  window.AndroidBridge = {
    onChatFocusChanged: function(focused) { StickerSyncNative.postMessage(JSON.stringify({type:'chat',focused:focused})); },
    onSelectionCountChanged: function(count) { StickerSyncNative.postMessage(JSON.stringify({type:'count',count:count})); },
    onStickersExported: function(urls) { StickerSyncNative.postMessage(JSON.stringify({type:'export',urls:urls})); },
    onError: function(message) { StickerSyncNative.postMessage(JSON.stringify({type:'error',message:message})); }
  };
})();
"""
        private const val DESKTOP_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"

        private val LOGIN_COOKIE_MARKERS = listOf("sessionid", "sid_tt")

        private val EMAIL_REGEX = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
        private const val MIN_USERNAME_LENGTH = 2
        private const val MIN_PASSWORD_LENGTH = 8 // TikTok's own minimum

        private const val GREEN = 0xFF64E3C0.toInt()
        private const val GRAY = 0xFF293847.toInt()

        // 100 = no artificial zoom-out. The nav rail and sidebar are sized with CSS
        // height:100vh, which already matches the real screen height at 100% scale -
        // zooming out further was shrinking that vertically too, cutting it off short.
        private const val INITIAL_ZOOM_PERCENT = 100
        private const val NAV_STEP_DELAY_MS = 600L
        private const val MAX_NAV_ATTEMPTS = 20 // ~12s budget to reach the real login fields

        private const val FORCE_WIDE_VIEWPORT_SCRIPT = """
(function() {
  var meta = document.querySelector('meta[name="viewport"]');
  if (!meta) {
    meta = document.createElement('meta');
    meta.name = 'viewport';
    document.head.appendChild(meta);
  }
  meta.setAttribute('content', 'width=325');
})();
"""

        // Scrolls chat/message containers to their bottom so a conversation opens showing
        // the newest messages. Installs a MutationObserver so this keeps re-firing whenever
        // you switch chats - switching conversations happens inside the page (no reload),
        // so a one-off scroll on page load alone would only work for the very first chat.
        private const val SCROLL_TO_BOTTOM_SCRIPT = """
(function() {
  if (window.__scrollObserverInstalled) return;
  window.__scrollObserverInstalled = true;

  function scrollMessagesToBottom() {
    var list = document.querySelector('[data-e2e="dm-new-message-list"]');
    if (list) list.scrollTop = list.scrollHeight;
    // If no chat is open yet, there's nothing to scroll - leave the contacts list at
    // its natural position (previously a brute-force fallback here was accidentally
    // dragging the contacts list itself away from the top).
  }

  // Detects a new chat being opened by watching what actually renders (the header
  // text), rather than trying to catch the tap itself - a click listener on the
  // contacts list repeatedly failed to fire, so this reacts to the result instead,
  // which works regardless of whatever event mechanism TikTok uses internally.
  window.__stickerSync = window.__stickerSync || {};
  window.__stickerSync.lastChatIdentity = null;
  function checkForNewChatOpened() {
    if (!window.__stickerSync.setChatFocused) return;
    var header = document.querySelector('[data-e2e="dm-new-chatbox"] [class*="DivChatHeader"]');
    var identity = header ? header.textContent : null;
    if (identity && identity !== window.__stickerSync.lastChatIdentity) {
      window.__stickerSync.lastChatIdentity = identity;
      window.__stickerSync.setChatFocused(true);
      if (window.AndroidBridge) window.AndroidBridge.onChatFocusChanged(true);
    }
  }

  function onMutation() {
    var previous = window.__stickerSync.lastChatIdentity;
    checkForNewChatOpened();
    if (previous !== window.__stickerSync.lastChatIdentity) {
      setTimeout(scrollMessagesToBottom, 300);
      setTimeout(scrollMessagesToBottom, 900);
    }
  }

  scrollMessagesToBottom();
  setTimeout(scrollMessagesToBottom, 800);
  setTimeout(scrollMessagesToBottom, 1800);

  new MutationObserver(onMutation).observe(document.body, { childList: true, subtree: true });
})();
"""

        // Site-specific selectors; TikTok may change these at any time.
        // Manual toggle (driven by the native button) rather than auto-detecting taps,
        // since that proved unreliable. Uses position:fixed to guarantee the chat covers
        // the full screen regardless of whatever flex/grid sizing TikTok's own layout uses -
        // resizing siblings directly kept leaving it squeezed into a sliver instead.
        private const val LAYOUT_OVERRIDE_SCRIPT = """
(function() {
  if (window.__layoutOverrideInstalled) return;
  window.__layoutOverrideInstalled = true;

  var style = document.createElement('style');
  style.textContent = [
    '[data-e2e="dm-new-conversation-list"] { width: 100% !important; }',
    '[data-e2e="dm-new-chatbox"] { display: none !important; }',
    'body.stickersync-focus-chat [data-e2e="dm-new-chatbox"] {',
    '  display: flex !important;',
    '  position: fixed !important; top: 0 !important; left: 0 !important;',
    '  width: 100vw !important; height: 100vh !important;',
    '  z-index: 999999 !important; background: black !important;',
    '}',
    'body.stickersync-focus-chat [data-stickersync-ancestor] { transform: none !important; filter: none !important; perspective: none !important; contain: none !important; will-change: auto !important; overflow: visible !important; }',
    '[class*="DivEmojiPanelContainer"] { max-width: calc(100vw - 32px) !important; right: 0 !important; left: auto !important; }',
    // Best-effort hide of the notifications/activity feed (the "liked your comment" /
    // "is LIVE" cards) - not confirmed against real markup, may need a DevTools
    // element-pick on one of those cards if this doesn't fully catch it.
    'section[aria-label*="Notifications" i] { display: none !important; }',
    '[class*="NotificationList"], [class*="ActivityFeed"], [class*="NoticeList"] { display: none !important; }'
  ].join(' ');
  document.head.appendChild(style);

  window.__stickerSync = window.__stickerSync || {};
  window.__stickerSync.setChatFocused = function(focused) {
    var chatbox = document.querySelector('[data-e2e="dm-new-chatbox"]');
    if (!chatbox) return;
    document.body.classList.toggle('stickersync-focus-chat', focused);
    // Keep the chat inside TikTok's React root so delegated clicks still work.
    // Neutralize fixed-position containing blocks only while the chat is focused.
    document.querySelectorAll('[data-stickersync-ancestor]').forEach(function(el) {
      el.removeAttribute('data-stickersync-ancestor');
    });
    if (focused) {
      for (var parent = chatbox.parentElement; parent && parent !== document.body; parent = parent.parentElement) {
        parent.setAttribute('data-stickersync-ancestor', '');
      }
    } else {
      // lastChatIdentity is intentionally NOT reset here. The conversation list
      // recycles its DOM rows as you scroll (virtualization), which triggers our
      // mutation observer even though nothing was tapped - if we'd cleared the
      // identity, that scroll noise would look like "a new chat just opened" and
      // silently pull you back in. The trade-off: tapping the exact same contact
      // twice in a row won't reopen it (a much smaller issue than the alternative).
    }
  };

  // Auto-focus the chat the moment any contact is tapped. This was tried before and
  // seemed not to work, but the real cause was the object-overwrite bug above (this
  // listener was silently calling a function that had already been wiped out) - now
  // that setChatFocused actually persists, this should work correctly.
  function bindContactTap() {
    var list = document.querySelector('[data-e2e="dm-new-conversation-list"]');
    if (list && !list.__stickerSyncTapBound) {
      list.__stickerSyncTapBound = true;
      list.addEventListener('click', function() {
        window.__stickerSync.setChatFocused(true);
        if (window.AndroidBridge) window.AndroidBridge.onChatFocusChanged(true);
      }, true);
    }
  }
  bindContactTap();
  setTimeout(bindContactTap, 800);
  setTimeout(bindContactTap, 1800);
})();
"""

        // Walks the tap sequence needed to reach the real login fields, one step per call:
        //   Profile tab -> "Log in" -> "Use phone / email / username" -> "Email / Username" tab
        // Re-checks from scratch every time it's called (cheap DOM queries), so it naturally
        // picks up wherever the page currently is - no state to track on the JS side at all.
        // Priority order matters: most-complete state checked first, so it never double-clicks
        // past where it already is.
        private const val LOGIN_NAVIGATION_SCRIPT = """
(function() {
  function simulateTap(el) {
    var rect = el.getBoundingClientRect();
    var x = rect.left + rect.width / 2;
    var y = rect.top + rect.height / 2;
    var base = { bubbles: true, cancelable: true, composed: true, clientX: x, clientY: y, view: window };
    try { el.dispatchEvent(new PointerEvent('pointerdown', Object.assign({}, base, { pointerType: 'touch' }))); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent('mousedown', base)); } catch (e) {}
    try { el.dispatchEvent(new PointerEvent('pointerup', Object.assign({}, base, { pointerType: 'touch' }))); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent('mouseup', base)); } catch (e) {}
    try { el.click(); } catch (e) {}
  }

  function clickMatch(pattern) {
    var all = document.querySelectorAll('button, a, div, span, li, p');
    for (var i = 0; i < all.length; i++) {
      var el = all[i];
      if (el.children && el.children.length > 3) continue; // skip big containers
      var t = (el.innerText || el.textContent || '').trim();
      if (t && pattern.test(t)) {
        var clickable = el.closest('button, a, [role="button"], [role="tab"], [tabindex]') || el;
        simulateTap(clickable);
        return true;
      }
    }
    return false;
  }

  var userInput = document.querySelector(
    'input[placeholder*="mail" i], input[placeholder*="username" i], ' +
    'input[name="username"], input[autocomplete="username"]'
  );
  var passInput = document.querySelector('input[type="password"]');
  if (userInput && passInput) return 'credentials-ready';

  // TikTok pops an app-install nag over the page at various points - dismiss it first,
  // since it blocks everything underneath until closed.
  if (clickMatch(/^not now$/i)) return 'dismissed-app-nag';

  if (clickMatch(/^email\s*\/\s*username$/i)) return 'clicked-email-tab';
  if (clickMatch(/^use phone\s*\/\s*email\s*\/\s*username$/i)) return 'clicked-method-choice';
  if (clickMatch(/^log in$/i)) return 'clicked-login-button';
  if (clickMatch(/^profile$/i)) return 'clicked-profile-tab';

  return 'stuck';
})();
"""

        private const val FILL_LOGIN_FIELDS_SCRIPT_TEMPLATE = """
(function() {
  function setNativeValue(el, value) {
    var proto = Object.getPrototypeOf(el);
    var protoSetter = Object.getOwnPropertyDescriptor(proto, 'value');
    var ownSetter = Object.getOwnPropertyDescriptor(el, 'value');
    if (protoSetter && protoSetter.set && protoSetter.set !== (ownSetter && ownSetter.set)) {
      protoSetter.set.call(el, value);
    } else if (ownSetter && ownSetter.set) {
      ownSetter.set.call(el, value);
    } else {
      el.value = value;
    }
    el.dispatchEvent(new Event('input', { bubbles: true }));
    el.dispatchEvent(new Event('change', { bubbles: true }));
  }
  var userEl = document.querySelector(
    'input[placeholder*="mail" i], input[placeholder*="username" i], ' +
    'input[name="username"], input[autocomplete="username"]'
  );
  var passEl = document.querySelector('input[type="password"]');
  if (userEl) setNativeValue(userEl, %USER%);
  if (passEl) setNativeValue(passEl, %PASS%);
  return JSON.stringify({ userFound: !!userEl, passFound: !!passEl });
})();
"""

        private const val CLICK_SUBMIT_SCRIPT = """
(function() {
  function simulateTap(el) {
    var rect = el.getBoundingClientRect();
    var x = rect.left + rect.width / 2;
    var y = rect.top + rect.height / 2;
    var base = { bubbles: true, cancelable: true, composed: true, clientX: x, clientY: y, view: window };
    try { el.dispatchEvent(new PointerEvent('pointerdown', Object.assign({}, base, { pointerType: 'touch' }))); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent('mousedown', base)); } catch (e) {}
    try { el.dispatchEvent(new PointerEvent('pointerup', Object.assign({}, base, { pointerType: 'touch' }))); } catch (e) {}
    try { el.dispatchEvent(new MouseEvent('mouseup', base)); } catch (e) {}
    try { el.click(); } catch (e) {}
  }
  var btn = document.querySelector('button[data-e2e="login-button"]') ||
            Array.from(document.querySelectorAll('button')).find(function(b) {
              return /^log in$/i.test((b.innerText || '').trim());
            });
  if (btn && !btn.disabled) { simulateTap(btn); return 'true'; }
  return 'false';
})();
"""

        private const val CHECK_LOGIN_STATE_SCRIPT = """
(function() {
  var captcha = document.querySelector('[class*="captcha" i], [id*="captcha" i], iframe[src*="captcha" i]');
  var errorEl = document.querySelector('[class*="error-text" i], [class*="error-message" i]');
  var errorText = (errorEl && errorEl.innerText) ? errorEl.innerText.trim() : '';
  return JSON.stringify({captcha: !!captcha, error: errorText});
})();
"""
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Lets chrome://inspect on a connected PC show the real live DOM/CSS - much better
        // than guessing at TikTok's markup blind.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)

        val contentColumn = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        webView = WebView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
            )
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = false
            settings.setSupportZoom(false)
            settings.builtInZoomControls = false
            settings.displayZoomControls = false
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.safeBrowsingEnabled = true
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (!request.isForMainFrame) return false
                    if (OriginPolicy.isTikTokPage(request.url.toString())) return false
                    statusText.text = "This link opens outside TikTok. Use the official browser for other sign-in methods."
                    MaterialAlertDialogBuilder(this@BrowseActivity).setTitle("Open in your browser?")
                        .setMessage("You'll leave the app to open this link. Browser login doesn't sign in to this app.")
                        .setNegativeButton("Stay here", null)
                        .setPositiveButton("Open") { _, _ ->
                            if (request.url.scheme == "https") runCatching {
                                startActivity(Intent(Intent.ACTION_VIEW, request.url))
                            }
                        }.show()
                    return true
                }
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    stopTransferLoginProbe()
                    selectionModeOn = false
                    if (::selectionModeButton.isInitialized) selectionModeButton.text = "Select: Off"
                    if (::exportButton.isInitialized) { exportButton.text = "Export (0)"; exportButton.isEnabled = false }
                    chatFocused = false
                    if (::backToContactsButton.isInitialized) backToContactsButton.visibility = View.GONE
                }
                override fun onPageCommitVisible(view: WebView, url: String) {
                    super.onPageCommitVisible(view, url)
                    if (OriginPolicy.isTikTokPage(url) && intent.getBooleanExtra("loginOnly", false)) detectTransferLogin()
                }
                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    browserError("Couldn't verify TikTok's secure connection. Check your connection and date, then reload.")
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) browserError("TikTok couldn't load. Check your connection and tap Reload.")
                }
                override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, response: WebResourceResponse) {
                    if (request.isForMainFrame && response.statusCode >= 400)
                        browserError("TikTok is unavailable right now. Try Reload or sign in directly.")
                }
                override fun onPageFinished(view: WebView, url: String) {
                    super.onPageFinished(view, url)
                    if (!OriginPolicy.isTikTokPage(url)) return
                    val loginOnly = intent.getBooleanExtra("loginOnly", false)
                    if (bridgeAvailable && !loginOnly) view.evaluateJavascript(NATIVE_BRIDGE_SCRIPT, null)
                    if (!loginOnly && desktopModeOn && Uri.parse(url).path?.startsWith("/messages") == true) {
                        view.evaluateJavascript(FORCE_WIDE_VIEWPORT_SCRIPT, null)
                        view.evaluateJavascript(LAYOUT_OVERRIDE_SCRIPT, null)
                        view.evaluateJavascript(SCROLL_TO_BOTTOM_SCRIPT, null)
                        if (awaitingDesktopSwitchReveal) {
                            awaitingDesktopSwitchReveal = false
                            pollHandler.postDelayed({ transitionOverlay.visibility = View.GONE }, 400)
                        }
                    }
                    if (bridgeAvailable && !loginOnly && Uri.parse(url).path?.startsWith("/messages") == true) {
                        view.evaluateJavascript(StickerScraperScript.SCRIPT, null)
                    }
                    if (intent.getBooleanExtra("loginOnly", false)) detectTransferLogin()
                    else tryDetectLoginAndSwitch(url)
                    if (awaitingLoginPageLoad) {
                        awaitingLoginPageLoad = false
                        // Small delay to let the SPA finish hydrating before we start
                        // looking for elements to click or fill.
                        pollHandler.postDelayed({ runLoginNavigationStep() }, 500)
                    }
                }
            }
        }
        bridgeAvailable = WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) WebViewCompat.addWebMessageListener(webView, "StickerSyncNative",
            setOf("https://*.tiktok.com", "https://tiktok.com")) { _, message, origin, mainFrame, _ ->
            if (mainFrame && OriginPolicy.isTikTokPage(origin.toString()) &&
                OriginPolicy.isTikTokPage(webView.url.orEmpty())) handleNativeMessage(message.data.orEmpty())
        }
        webView.setInitialScale(INITIAL_ZOOM_PERCENT)

        val controlBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(16, 16, 16, 16)
        }
        selectionModeButton = MaterialButton(this).apply {
            cornerRadius = dp(14)
            text = "Select: Off"
            textSize = 14f
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { toggleSelectionMode() }
        }
        exportButton = MaterialButton(this).apply {
            cornerRadius = dp(14)
            text = "Export (0)"
            textSize = 14f
            isAllCaps = false
            isEnabled = false
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { exportSelected() }
        }
        statusText = TextView(this).apply {
            textSize = 13f
            maxLines = 3
            setTextColor(0xFFA8B5C5.toInt())
            text = "Log in above, open a chat, turn on Select mode, tap stickers."
            setPadding(16, 0, 16, 0)
        }
        controlBar.addView(selectionModeButton)
        controlBar.addView(exportButton)

        contentColumn.addView(webView)
        contentColumn.addView(statusText)
        contentColumn.addView(controlBar)

        // ---- Login overlay ----
        overlay = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0D1420.toInt())
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(32), dp(28), dp(24))
        }
        val title = TextView(this).apply {
            text = "Sign in to TikTok"
            textSize = 26f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, 32)
        }
        usernameInput = TextInputEditText(this).apply {
            hint = "Email or username"
            setHintTextColor(0xFF999999.toInt())
            setTextColor(0xFFFFFFFF.toInt())
        }

        val passwordRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        passwordInput = TextInputEditText(this).apply {
            hint = "Password"
            setHintTextColor(0xFF999999.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        passwordRow.addView(passwordInput)

        overlayProgressText = TextView(this).apply {
            setTextColor(0xFFAAAAAA.toInt())
            visibility = View.GONE
            setPadding(0, 16, 0, 0)
        }
        overlayErrorText = TextView(this).apply {
            setTextColor(0xFFFF9DA4.toInt())
            visibility = View.GONE
            setPadding(0, 16, 0, 0)
        }
        loginButton = MaterialButton(this).apply {
            com.example.stickerpackbuilder.ui.Motion.press(this)
            cornerRadius = dp(14)
            minHeight = dp(52)
            isAllCaps = false
            text = "Sign in"
            isEnabled = false
            val states = arrayOf(intArrayOf(android.R.attr.state_enabled), intArrayOf())
            backgroundTintList = android.content.res.ColorStateList(states, intArrayOf(GREEN, GRAY))
            setTextColor(android.content.res.ColorStateList(states, intArrayOf(0xff101923.toInt(), 0xff788ba0.toInt())))
            setOnClickListener { attemptAutoLogin() }
        }
        val escapeHatch = MaterialButton(this, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "Use TikTok’s official login instead"
            setTextColor(0xFF64E3C0.toInt())
            gravity = Gravity.CENTER
            isAllCaps = false; cornerRadius = dp(14); minHeight = dp(52)
            com.example.stickerpackbuilder.ui.Motion.press(this)
            setOnClickListener {
                revealWebView("Sign in on TikTok below. Complete any verification there.")
                webView.loadUrl("https://www.tiktok.com/login/phone-or-email/email")
            }
        }

        val validationWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = updateLoginButtonState()
        }
        usernameInput.addTextChangedListener(validationWatcher)
        passwordInput.addTextChangedListener(validationWatcher)

        overlay.addView(title)
        overlay.addView(TextView(this).apply {
            text = "Collect your favourite chat stickers. Your password is used only to fill TikTok’s login form."
            textSize = 14f
            setTextColor(0xFFA8B5C5.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(28))
        })
        usernameInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        usernameInput.setSingleLine()
        usernameInput.isSaveEnabled = false
        passwordInput.isSaveEnabled = false
        passwordInput.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        passwordInput.setSingleLine()
        val userLayout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Email or username"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat())
        }
        usernameInput.hint = null
        usernameInput.background = null
        userLayout.addView(usernameInput)
        val passLayout = TextInputLayout(this, null, com.google.android.material.R.attr.textInputOutlinedStyle).apply {
            hint = "Password"
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            setBoxCornerRadii(dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat())
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            setPadding(0, dp(12), 0, dp(12))
        }
        passwordRow.removeView(passwordInput)
        passwordInput.layoutParams = LinearLayout.LayoutParams(-1, -2)
        passwordInput.hint = null
        passwordInput.background = null
        passLayout.addView(passwordInput)
        overlay.addView(userLayout)
        overlay.addView(passLayout)
        overlay.addView(overlayProgressText)
        overlay.addView(overlayErrorText)
        overlay.addView(loginButton)
        overlay.addView(escapeHatch)
        overlay.addView(TextView(this).apply {
            text = "Back to your library"; gravity = Gravity.CENTER
            setPadding(0, dp(24), 0, 0); setTextColor(0xFFA8B5C5.toInt())
            minHeight = dp(48); isFocusable = true
            com.example.stickerpackbuilder.ui.Motion.press(this)
            setOnClickListener { finish() }
        })

        transitionOverlay = TextView(this).apply {
            setBackgroundColor(0xFF101923.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            visibility = View.GONE
        }

        backToContactsButton = Button(this).apply {
            text = "\u2190"
            textSize = 20f
            isAllCaps = false
            alpha = 0.85f
            visibility = View.GONE
            setOnClickListener { setChatFocused(false) }
        }

        val header = com.google.android.material.appbar.MaterialToolbar(this).apply {
            this.title = "TikTok"; setTitleTextColor(0xFFFFFFFF.toInt())
            setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material)
            navigationIcon?.setTint(0xFFFFFFFF.toInt())
            setNavigationOnClickListener { finish() }
            menu.add("Messages").setOnMenuItemClickListener {
                if (OriginPolicy.isTikTokPage(webView.url.orEmpty())) webView.loadUrl("https://www.tiktok.com/messages")
                true
            }
            menu.add("Reload").setOnMenuItemClickListener { webView.reload(); true }
            menu.add("Official login").setOnMenuItemClickListener {
                revealWebView("Sign in directly on TikTok.")
                webView.loadUrl("https://www.tiktok.com/login/phone-or-email/email"); true
            }
            menu.add("Sign out").setOnMenuItemClickListener { signOut(); true }
        }
        contentColumn.addView(header, 0)
        val root = FrameLayout(this)
        root.addView(contentColumn, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        val loginScroll = android.widget.ScrollView(this).apply { isFillViewport = true; addView(this@BrowseActivity.overlay) }
        root.addView(loginScroll, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(transitionOverlay, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
        ))
        root.addView(backToContactsButton, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.TOP or Gravity.START; topMargin = dp(72); leftMargin = dp(12) })
        setContentView(root)
        if (intent.getBooleanExtra("loginOnly", false)) {
            selectionModeButton.visibility = View.GONE; exportButton.visibility = View.GONE
            controlBar.visibility = View.GONE
            statusText.text = "After signing in, your transfer continues automatically."
            header.title = "Connect TikTok"; header.menu.clear()
        }
        overlay.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            loginScroll.visibility = overlay.visibility
        }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    chatFocused -> setChatFocused(false)
                    overlay.visibility == View.VISIBLE -> finish()
                    webView.canGoBack() -> webView.goBack()
                    else -> finish()
                }
            }
        })

        val alreadyLoggedIn = LOGIN_COOKIE_MARKERS.any {
            hasSessionCookie(CookieManager.getInstance().getCookie("https://www.tiktok.com"), it)
        }
        if (alreadyLoggedIn) {
            desktopModeOn = true
            webView.settings.userAgentString = DESKTOP_USER_AGENT
            overlay.visibility = View.GONE
            (overlay.parent as? View)?.visibility = View.GONE
            loginScroll.visibility = View.GONE
        }

        webView.loadUrl(if (alreadyLoggedIn) "https://www.tiktok.com/messages" else "https://www.tiktok.com/login/phone-or-email/email")
    }

    // ---------- Live validation ----------
    private var detectingTransferLogin = false
    private fun stopTransferLoginProbe() {
        loginProbeEpoch++; detectingTransferLogin = false
        loginHandler.removeCallbacksAndMessages(null)
    }
    private fun detectTransferLogin() {
        if (detectingTransferLogin || isFinishing || !loginVisible || !intent.getBooleanExtra("loginOnly", false)) return
        detectingTransferLogin = true
        val epoch = ++loginProbeEpoch
        fun check(attempt: Int) {
            if (isFinishing || isDestroyed || !loginVisible || epoch != loginProbeEpoch) return
            if (!OriginPolicy.isTikTokPage(webView.url.orEmpty())) {
                loginHandler.postDelayed({ check(attempt + 1) }, 500); return
            }
            webView.evaluateJavascript(sessionProbe) { raw ->
                if (epoch != loginProbeEpoch || isFinishing || isDestroyed || !loginVisible) return@evaluateJavascript
                val u = runCatching { JSONObject(JSONTokener(raw).nextValue().toString()) }.getOrDefault(JSONObject())
                if (u.optString("id").isNotBlank() && u.optString("name").isNotBlank()) {
                    getSharedPreferences("transfer", MODE_PRIVATE).edit().putString("selfId", u.getString("id"))
                        .putString("handle", u.getString("handle")).putString("selfName", u.getString("name")).apply()
                    stopTransferLoginProbe()
                    passwordInput.text.clear(); setResult(RESULT_OK); finish()
                } else {
                    // First read the newly signed-in page itself; only use a desktop
                    // navigation as fallback when that page does not expose app context.
                    if (attempt >= 4 && !desktopModeOn && tryDetectLoginAndSwitch(webView.url.orEmpty())) return@evaluateJavascript
                    loginHandler.postDelayed({ check(attempt + 1) }, if (attempt < 40) 200 else 700)
                }
            }
        }
        check(0)
    }

    private fun isUsernameValid(text: String): Boolean =
        EMAIL_REGEX.matches(text) || text.trim().length >= MIN_USERNAME_LENGTH

    private fun isPasswordValid(text: String): Boolean = text.length >= MIN_PASSWORD_LENGTH

    private fun updateLoginButtonState() {
        val valid = isUsernameValid(usernameInput.text.toString()) &&
            isPasswordValid(passwordInput.text.toString())
        loginButton.isEnabled = valid && !loginRunning
    }

    // ---------- Login overlay logic ----------

    private var awaitingLoginPageLoad = false

    /** Kicks off the whole flow: navigates straight to TikTok's login URL (skipping the
     * fragile Profile -> Log in tap sequence entirely), then walks whatever's left
     * (usually just "Use phone/email/username" -> "Email/Username" tab) before filling
     * and submitting once the real fields exist. */
    private fun attemptAutoLogin() {
        if (loginRunning) return
        loginRunning = true
        loginButton.isEnabled = false
        overlayErrorText.visibility = View.GONE
        overlayProgressText.visibility = View.VISIBLE
        overlayProgressText.text = "Opening the login page..."
        navAttempts = 0
        if (OriginPolicy.isTikTokPage(webView.url.orEmpty()) && Uri.parse(webView.url).path?.startsWith("/login") == true) {
            awaitingLoginPageLoad = false
            runLoginNavigationStep()
        } else {
            awaitingLoginPageLoad = true
            webView.loadUrl("https://www.tiktok.com/login/phone-or-email/email")
        }
    }

    private fun runLoginNavigationStep() {
        if (isFinishing || isDestroyed || !loginRunning || overlay.visibility != View.VISIBLE || !OriginPolicy.isTikTokPage(webView.url.orEmpty())) return

        webView.evaluateJavascript(LOGIN_NAVIGATION_SCRIPT) { rawResult ->
            if (isFinishing || isDestroyed || !loginRunning) return@evaluateJavascript
            val status = rawResult?.trim('"') ?: "stuck"

            when (status) {
                "credentials-ready" -> {
                    overlayProgressText.text = "Filling in your details..."
                    fillAndSubmit()
                }
                "dismissed-app-nag" -> {
                    overlayProgressText.text = "Closing app prompt..."
                    scheduleNextNavStep()
                }
                "clicked-profile-tab" -> {
                    overlayProgressText.text = "Opening your profile..."
                    scheduleNextNavStep()
                }
                "clicked-login-button" -> {
                    overlayProgressText.text = "Opening the login screen..."
                    scheduleNextNavStep()
                }
                "clicked-method-choice" -> {
                    overlayProgressText.text = "Choosing email/username login..."
                    scheduleNextNavStep()
                }
                "clicked-email-tab" -> {
                    overlayProgressText.text = "Almost there..."
                    scheduleNextNavStep()
                }
                else -> scheduleNextNavStep() // "stuck" this tick - the next tick may find something
            }
        }
    }

    private fun scheduleNextNavStep() {
        if (navAttempts >= MAX_NAV_ATTEMPTS) {
            revealWebView("Couldn't reach the login form automatically - continue below.")
            return
        }
        navAttempts++
        pollHandler.postDelayed({ runLoginNavigationStep() }, NAV_STEP_DELAY_MS)
    }

    private fun fillAndSubmit() {
        if (!OriginPolicy.isTikTokPage(webView.url.orEmpty()) ||
            Uri.parse(webView.url).path?.startsWith("/login") != true) {
            revealWebView("Continue on TikTok's official login page.")
            return
        }
        val user = usernameInput.text.toString()
        val pass = passwordInput.text.toString()

        val fillScript = Regex("%(USER|PASS)%").replace(FILL_LOGIN_FIELDS_SCRIPT_TEMPLATE) {
            JSONObject.quote(if (it.groupValues[1] == "USER") user else pass)
        }

        webView.evaluateJavascript(fillScript) { rawResult ->
            val (userFound, passFound) = try {
                val unwrapped = JSONTokener(rawResult ?: "\"{}\"").nextValue() as String
                val obj = JSONObject(unwrapped)
                Pair(obj.optBoolean("userFound", false), obj.optBoolean("passFound", false))
            } catch (e: Exception) {
                Pair(false, false)
            }

            if (!userFound || !passFound) {
                revealWebView("Couldn't find the email/password fields - log in below.")
                return@evaluateJavascript
            }

            pollHandler.postDelayed({ clickSubmitButton() }, 350)
        }
    }

    private fun clickSubmitButton() {
        if (!OriginPolicy.isTikTokPage(webView.url.orEmpty())) return
        passwordInput.text.clear()
        webView.evaluateJavascript(CLICK_SUBMIT_SCRIPT) { rawResult ->
            val clicked = rawResult?.trim('"') == "true"
            if (!clicked) {
                revealWebView("Couldn't find the log in button - log in below.")
                return@evaluateJavascript
            }
            overlayProgressText.text = "Logging in..."
            pollAttempts = 0
            pollHandler.postDelayed({ pollLoginState() }, 1000)
        }
    }

    private fun pollLoginState() {
        if (desktopModeOn) return
        if (overlay.visibility != View.VISIBLE) return

        if (tryDetectLoginAndSwitch(webView.url ?: "https://www.tiktok.com/messages")) return

        if (pollAttempts >= 8) {
            revealWebView("Taking a while - continue logging in below.")
            return
        }
        pollAttempts++
        webView.evaluateJavascript(CHECK_LOGIN_STATE_SCRIPT) { rawResult ->
            try {
                val unwrapped = JSONTokener(rawResult ?: "\"{}\"").nextValue() as String
                val obj = JSONObject(unwrapped)
                val captcha = obj.optBoolean("captcha", false)
                val error = obj.optString("error", "")
                when {
                    captcha -> revealWebView("Please complete the CAPTCHA below to continue.")
                    error.isNotEmpty() -> showLoginError(error)
                    else -> pollHandler.postDelayed({ pollLoginState() }, 1000)
                }
            } catch (e: Exception) {
                pollHandler.postDelayed({ pollLoginState() }, 1000)
            }
        }
    }

    private fun showLoginError(message: String) {
        loginRunning = false
        passwordInput.text.clear()
        overlayProgressText.visibility = View.GONE
        overlayErrorText.text = message
        overlayErrorText.visibility = View.VISIBLE
    }

    private fun revealWebView(message: String) {
        loginRunning = false
        awaitingLoginPageLoad = false
        passwordInput.text.clear()
        overlayProgressText.visibility = View.GONE
        overlay.visibility = View.GONE
            (overlay.parent as? View)?.visibility = View.GONE
        transitionOverlay.visibility = View.GONE
        statusText.text = message
    }

    // ---------- Login -> desktop mode switch ----------

    private var awaitingDesktopSwitchReveal = false

    private fun hasSessionCookie(cookies: String?, name: String): Boolean =
        cookies?.split(";")?.any {
            val pair = it.trim().split("=", limit = 2)
            pair.size == 2 && pair[0] == name && pair[1].isNotBlank()
        } == true

    private fun tryDetectLoginAndSwitch(url: String): Boolean {
        if (!OriginPolicy.isTikTokPage(url)) return false
        if (desktopModeOn) return true
        val cookies = CookieManager.getInstance().getCookie(url) ?: return false
        val loggedIn = LOGIN_COOKIE_MARKERS.any { hasSessionCookie(cookies, it) }
        if (loggedIn) {
            loginRunning = false
            passwordInput.text.clear()
            desktopModeOn = true
            overlay.visibility = View.GONE
            (overlay.parent as? View)?.visibility = View.GONE
            transitionOverlay.text = "Switching to desktop mode..."
            transitionOverlay.visibility = View.VISIBLE
            awaitingDesktopSwitchReveal = true
            webView.settings.userAgentString = DESKTOP_USER_AGENT
            statusText.text = "Logged in - switching to desktop mode for Messages..."
            webView.loadUrl("https://www.tiktok.com/messages")
            return true
        }
        return false
    }

    private fun setChatFocused(focused: Boolean) {
        chatFocused = focused
        showOrHideBackButton(focused)
        webView.evaluateJavascript(
            "window.__stickerSync && window.__stickerSync.setChatFocused(${chatFocused})", null
        )
    }

    /** Hides instantly (user just tapped it, wants an immediate response), but shows with
     * a short delay so the button doesn't visibly pop in before the chat has rendered. */
    private fun showOrHideBackButton(focused: Boolean) {
        if (focused) {
            pollHandler.postDelayed({ if (chatFocused) backToContactsButton.visibility = View.VISIBLE }, 250)
        } else {
            backToContactsButton.visibility = View.GONE
        }
    }

    private fun toggleSelectionMode() {
        if (!bridgeAvailable || !OriginPolicy.isTikTokPage(webView.url.orEmpty())) {
            statusText.text = "Update Android System WebView to select stickers, or import images from your phone."
            return
        }
        selectionModeOn = !selectionModeOn
        selectionModeButton.text = if (selectionModeOn) "Select: On" else "Select: Off"
        webView.evaluateJavascript("window.__stickerSync && window.__stickerSync.setSelectionMode(${selectionModeOn})", null)
        if (!selectionModeOn) {
            exportButton.text = "Export (0)"
            exportButton.isEnabled = false
        }
    }

    private fun exportSelected() {
        if (!bridgeAvailable || !OriginPolicy.isTikTokPage(webView.url.orEmpty())) return
        webView.evaluateJavascript("window.__stickerSync && window.__stickerSync.exportSelected()", null)
    }

    private fun handleNativeMessage(raw: String) {
        if (raw.length > 140_000) return
        try {
            val obj = JSONObject(raw)
            when (obj.getString("type")) {
                "chat" -> {
                    chatFocused = obj.optBoolean("focused")
                    showOrHideBackButton(chatFocused)
                }
                "count" -> {
                    val count = obj.optInt("count").coerceIn(0, 30)
                    exportButton.text = "Export ($count)"
                    exportButton.isEnabled = count > 0
                }
                "error" -> statusText.text = obj.optString("message").take(240)
                "export" -> {
                    if (!selectionModeOn || Uri.parse(webView.url).path?.startsWith("/messages") != true) return
                    val arr = JSONArray(obj.getString("urls"))
                    require(arr.length() in 1..30) { "Select up to 30 stickers at once" }
                    val urls = (0 until arr.length()).map { arr.getString(it) }
                    importId = ImportJobs.enqueue(this, urls)
                    webView.evaluateJavascript("window.__stickerSync.exportResult(true)", null)
                    watchImport(importId!!)
                    statusText.text = "Import queued. You can keep browsing."
                }
            }
        } catch (e: Exception) {
            webView.evaluateJavascript("window.__stickerSync && window.__stickerSync.exportResult && window.__stickerSync.exportResult(false)", null)
            statusText.text = e.message?.take(240) ?: "Couldn't export. Try selecting those stickers again."
        }
    }

    private fun watchImport(id: java.util.UUID) {
        WorkManager.getInstance(this).getWorkInfoByIdLiveData(id).observe(this) { info ->
            if (info != null && id == importId) {
                statusText.text = if (info.state.isFinished)
                    info.outputData.getString("message") ?: "Import finished. Check the library."
                else info.progress.getString("message") ?: "Import queued"
            }
        }
    }

    private fun browserError(message: String) {
        transitionOverlay.visibility = View.GONE
        if (loginRunning) {
            loginRunning = false
            awaitingLoginPageLoad = false
            passwordInput.text.clear()
            overlayProgressText.visibility = View.GONE
            overlayErrorText.text = message
            overlayErrorText.visibility = View.VISIBLE
        }
        statusText.text = message
    }

    private fun signOut() {
        MaterialAlertDialogBuilder(this).setTitle("Sign out of TikTok?")
            .setMessage("This clears the TikTok session in this app. Your sticker library stays here.")
            .setNegativeButton("Stay signed in", null)
            .setPositiveButton("Sign out") { _, _ ->
                pollHandler.removeCallbacksAndMessages(null)
                passwordInput.text.clear()
                android.webkit.WebStorage.getInstance().deleteAllData()
                CookieManager.getInstance().removeAllCookies {
                    CookieManager.getInstance().flush()
                    desktopModeOn = false
                    loginRunning = false
                    awaitingLoginPageLoad = false
                    awaitingDesktopSwitchReveal = false
                    selectionModeOn = false
                    webView.clearCache(true)
                    webView.clearHistory()
                    webView.settings.userAgentString = android.webkit.WebSettings.getDefaultUserAgent(this)
                    transitionOverlay.visibility = View.GONE
                    overlay.visibility = View.VISIBLE
                    (overlay.parent as? View)?.visibility = View.VISIBLE
                    overlayErrorText.visibility = View.GONE
                    overlayProgressText.visibility = View.GONE
                    webView.loadUrl("https://www.tiktok.com/login/phone-or-email/email")
                }
            }.show()
    }

    override fun onStart() {
        super.onStart(); loginVisible = true
        if (::webView.isInitialized) detectTransferLogin()
    }
    override fun onStop() {
        loginVisible = false; stopTransferLoginProbe(); super.onStop()
    }
    override fun onDestroy() {
        stopTransferLoginProbe()
        pollHandler.removeCallbacksAndMessages(null)
        passwordInput.text.clear()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
            WebViewCompat.removeWebMessageListener(webView, "StickerSyncNative")
        webView.stopLoading()
        webView.removeAllViews()
        webView.destroy()
        super.onDestroy()
    }
}
