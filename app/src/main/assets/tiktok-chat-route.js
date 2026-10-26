// Readiness-driven self-chat navigation. No sticker taps, sends or cleanup operations.
(function (uid, name) {
  try {
    const helper = window.__stickerTransfer;
    if (!helper) return JSON.stringify({needsHelper:true});
    if (helper.isSelfChat(uid).verified) return JSON.stringify({verified:true});
    const list = document.querySelector('[data-e2e="dm-new-conversation-list"]');
    if (!list) return JSON.stringify({waiting:true});
    const matches = Array.from(list.querySelectorAll('*')).filter(element =>
      !element.children.length && (element.textContent || '').trim() === name);
    if (matches.length !== 1) return JSON.stringify({waiting:true,ambiguous:matches.length>1});
    const now = Date.now();
    const pending = window.__stickerOpeningSelf;
    if (pending?.uid === uid && pending?.name === name && now - pending.at < 650)
      return JSON.stringify({waiting:true});
    matches[0].click();
    window.__stickerOpeningSelf = {uid,name,at:now};
    // React can finish synchronously. Verify its actual conversation identity, not header text.
    return JSON.stringify({verified:helper.isSelfChat(uid).verified,clicked:true});
  } catch (_) { return JSON.stringify({waiting:true}); }
})
