package com.example.stickerpackbuilder

object StickerScraperScript {
    const val SCRIPT = """
(function() {
  if (window.__stickerSyncInstalled) return;
  window.__stickerSyncInstalled = true;
  var selected = new Map(), selectionMode = false, scheduled = false, exporting = false;
  var selector = 'img[alt="sticker" i], img[class*="StickerImage"], [data-e2e="sticker-image"] img';
  function notify() {
    if (window.AndroidBridge) window.AndroidBridge.onSelectionCountChanged(selected.size);
  }
  function clear() {
    selected.forEach(function(img) { img.style.outline = ''; });
    selected.clear(); notify();
  }
  function attach(img) {
    if (img.__stickerSyncBound) return;
    img.__stickerSyncBound = true;
    img.addEventListener('click', function(e) {
      if (!selectionMode || exporting) return;
      e.preventDefault(); e.stopImmediatePropagation();
      var url = img.currentSrc || img.src;
      if (!url || url.indexOf('https://') !== 0) {
        if (window.AndroidBridge) window.AndroidBridge.onError('This sticker cannot be downloaded. Save it and import from your phone.');
        return;
      }
      if (selected.has(url)) {
        selected.get(url).style.outline = '';
        selected.delete(url);
      } else {
        if (selected.size >= 30) {
          if (window.AndroidBridge) window.AndroidBridge.onError('Select up to 30 stickers per import.');
          return;
        }
        selected.set(url, img);
        img.style.outline = '3px solid #64E3C0';
      }
      notify();
    }, true);
  }
  function scan() {
    scheduled = false;
    var root = document.querySelector('[data-e2e="dm-new-chatbox"]') || document;
    root.querySelectorAll(selector).forEach(attach);
    // Restore outlines when TikTok recycles a message element.
    root.querySelectorAll(selector).forEach(function(img) {
      var url = img.currentSrc || img.src;
      if (selected.has(url)) { selected.set(url, img); img.style.outline = '3px solid #64E3C0'; }
      else img.style.outline = '';
    });
  }
  new MutationObserver(function() {
    if (!scheduled) { scheduled = true; requestAnimationFrame(scan); }
  }).observe(document.body, {childList:true, subtree:true, attributes:true, attributeFilter:['src','srcset','alt','class']});
  scan();
  window.__stickerSync = window.__stickerSync || {};
  window.__stickerSync.setSelectionMode = function(on) {
    selectionMode = on; if (!on) clear();
  };
  window.__stickerSync.exportResult = function(success) {
    exporting = false;
    if (success) clear(); else notify();
  };
  window.__stickerSync.exportSelected = function() {
    if (!selectionMode || exporting || !selected.size || !window.AndroidBridge) return;
    exporting = true;
    window.AndroidBridge.onStickersExported(JSON.stringify(Array.from(selected.keys())));
    setTimeout(function() { exporting = false; }, 10000);
  };
})();
"""
}

