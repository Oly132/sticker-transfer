(function () {
  // App context identifies the signed-in user; profile-page UserModule entries do not.
  try {
    const element = document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__');
    if (!element) return JSON.stringify({state:'pending'});
    const context = JSON.parse(element.textContent).__DEFAULT_SCOPE__?.['webapp.app-context'];
    if (!context) return JSON.stringify({state:'pending'});
    const user = context.user;
    const id = user?.uid == null ? '' : String(user.uid);
    const handle = typeof user?.uniqueId === 'string' ? user.uniqueId.trim() : '';
    if (/^[0-9]{5,30}$/.test(id) && /^[A-Za-z0-9_.]{2,30}$/.test(handle)) {
      const name = String(user.nickName || user.nickname || handle).trim() || handle;
      return JSON.stringify({state:'authenticated',id,handle,name});
    }
    return JSON.stringify({state:document.readyState === 'loading' ? 'pending' : 'guest'});
  } catch (_) { return JSON.stringify({state:'pending'}); }
})()
