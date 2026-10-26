(function () {
  'use strict';
  const root = () => document.querySelector('[data-e2e="dm-new-chatbox"]');
  let verifiedUid = '', modelCache = new Map(), allowedCleanup = new Set();
  function client() {
    const element=root();let fiber=element?.[Object.keys(element).find(k=>k.startsWith('__reactFiber'))],instance,conversationId;
    for(let depth=0;fiber&&depth<80;depth++,fiber=fiber.return){const p=fiber.memoizedProps;if(p?.instance)instance=p.instance;if(p?.value?.instance)instance=p.value.instance;if(p?.value?.conversationId)conversationId=p.value.conversationId;if(p?.conversationId)conversationId=p.conversationId}
    if(!verifiedUid||!instance?.getConversation)return null;
    if(instance.ctx?.option?.userId&&String(instance.ctx.option.userId)!==verifiedUid)return null;
    if(!conversationId?.endsWith(':'+verifiedUid+':'+verifiedUid)||conversationId.split(':').length!==4)conversationId='0:1:'+verifiedUid+':'+verifiedUid;
    let conversation;try{conversation=instance.getConversation({conversationId})}catch{return null}
    return conversation?.id===conversationId?{instance,conversation}:null;
  }
  function paneIsSelf(uid) {
    const element = root();
    if (!element || !/^[0-9]{5,30}$/.test(uid)) return {verified:false};
    let fiber = element[Object.keys(element).find(k => k.startsWith('__reactFiber'))];
    for (let depth = 0; fiber && depth < 50; depth++, fiber = fiber.return) {
      const id = fiber.memoizedProps?.value?.conversationId || fiber.memoizedProps?.conversationId;
      if (typeof id === 'string') return {verified:id.split(':').length === 4 && id.endsWith(':'+uid+':'+uid)};
    }
    return {verified:false};
  }
  function isSelfChat(uid) {
    if(!/^[0-9]{5,30}$/.test(uid))return {verified:false};
    let account;try{account=JSON.parse(document.getElementById('__UNIVERSAL_DATA_FOR_REHYDRATION__').textContent).__DEFAULT_SCOPE__['webapp.app-context'].user}catch{}
    if(String(account?.uid)!==uid){verifiedUid='';return {verified:false};}
    const pane=paneIsSelf(uid);verifiedUid=uid;
    if(pane.verified)return pane;
    if(client())return {verified:true,modelOnly:true};
    verifiedUid='';return {verified:false};
  }
  function metadata(item) {
    let fiber = item[Object.keys(item).find(k => k.startsWith('__reactFiber'))];
    let id = '', prev, next;
    for (let depth = 0; fiber && depth < 80; depth++, fiber = fiber.return) {
      const p = fiber.memoizedProps;
      if (!p || typeof p !== 'object') continue;
      if (typeof p.clientId === 'string') { id = p.clientId; prev = p.prevClientId; next = p.nextClientId; break; }
      if (typeof p.value?.clientId === 'string') id = p.value.clientId;
    }
    if (!id || id.length > 160) throw Error('TikTok message identifiers changed. Your messages were preserved.');
    const img = item.querySelector('img[alt="sticker" i],img[class*="StickerImage"]');
    return {id, prev: prev || null, next: next || null, url: img ? (img.currentSrc || img.src) : null};
  }
  function items() {
    if(!paneIsSelf(verifiedUid).verified)throw Error('The visible conversation is not your self-chat');
    return Array.from(root()?.querySelectorAll('[data-e2e="dm-new-chat-item"]') || []).map(item => ({element:item, record:metadata(item)}));
  }
  function scrollContainer() {
    return Array.from(root()?.querySelectorAll('div') || []).filter(e =>
      ['auto','scroll'].includes(getComputedStyle(e).overflowY) && e.clientHeight > 50)
      .sort((a,b)=>(b.scrollHeight-b.clientHeight)-(a.scrollHeight-a.clientHeight))[0];
  }
  function snapshot() {
    const list = items(), container = scrollContainer();
    return {records:list.map(i => i.record), scrollable:!!container,
      atTop:container ? container.scrollTop < 2 : true, atBottom:container ? container.scrollHeight-container.clientHeight-container.scrollTop < 3 : true};
  }
  function scroll(direction) {
    const e = scrollContainer();
    if (!e) return {moved:false};
    const before=e.scrollTop;
    e.scrollTop=direction==='up'?0:e.scrollHeight;
    e.dispatchEvent(new Event('scroll', {bubbles:true}));
    return {moved:before!==e.scrollTop};
  }
  function openMenu(id) {
    const match=items().filter(i=>i.record.id===id);
    if(match.length!==1 || !match[0].record.url) throw Error('The copied sticker could not be uniquely identified.');
    const item=match[0].element, horizontal=item.querySelector('[class*="DivMessageHorizontalContainer"]');
    if(horizontal) {
      const p=horizontal[Object.keys(horizontal).find(k=>k.startsWith('__reactProps'))];
      p?.onMouseEnter?.({currentTarget:horizontal,target:horizontal});
    }
    const more=item.querySelector('[data-e2e="dm-new-more-btn"]');
    if(!more) return {opened:false};
    const props=more[Object.keys(more).find(k=>k.startsWith('__reactProps'))];
    props?.onMouseEnter?.({currentTarget:more,target:more,preventDefault(){},stopPropagation(){}});
    more.dispatchEvent(new MouseEvent('mouseover',{bubbles:true}));
    return {opened:true};
  }
  function deleteCopied(id) {
    const buttons=Array.from(document.querySelectorAll('[data-e2e="dm-new-delete-msg-btn"]'))
      .filter(e=>{try{return metadata(e).id===id;}catch{return false;}});
    if(buttons.length!==1) return {clicked:false,count:buttons.length};
    buttons[0].click();
    return {clicked:true};
  }
  function modelRecord(message) {
    const url=message.contentPb?.sticker_card?.sticker?.url_list?.[0]||null;
    return {id:message.clientId,url,index:String(message.indexInConversation),serverId:message.serverId,prev:null,next:null};
  }
  async function mark() {
    const c=client();if(!c?.instance.getMessagesByConversation)return {available:false};
    const result=await c.instance.getMessagesByConversation({conversation:c.conversation,cursor:'9223372036854775807',limit:1});
    const indices=(result.messages||[]).map(m=>String(m.indexInConversation));
    return {available:true,index:indices.reduce((a,b)=>BigInt(a)>BigInt(b)?a:b,'0')};
  }
  async function retrieve(expected, knownIds=[], cleanupIds=[], minimumIndex=null) {
    const c=client();if(!c?.instance.getMessagesByConversation)return {available:false};
    let cursor='9223372036854775807',hasMore=true;const visible=new Map();modelCache=new Map();
    const known=new Set(knownIds);let foundKnown=known.size===0,reachedBoundary=false;
    for(let page=0;page<Math.min(120,Math.ceil(expected/50)+12);page++){
      const result=await c.instance.getMessagesByConversation({conversation:c.conversation,cursor,limit:50});
      for(const m of result.messages||[]){if(m.conversationId!==c.conversation.id||typeof m.clientId!=='string')throw Error('Unexpected conversation record');modelCache.set(m.clientId,m);const index=String(m.indexInConversation);if(minimumIndex!==null&&BigInt(index)<=BigInt(minimumIndex))reachedBoundary=true;if(m.visible&&(minimumIndex===null||BigInt(index)>BigInt(minimumIndex))){visible.set(m.clientId,modelRecord(m));if(known.has(m.clientId))foundKnown=true;}}
      hasMore=!!result.hasMore;
      const next=String(result.cursor);
      if((cleanupIds.length?cleanupIds.every(id=>modelCache.has(id)):minimumIndex!==null?reachedBoundary:visible.size>=expected&&foundKnown)||!hasMore||next===cursor)break;
      cursor=next;
    }
    const records=[...visible.values()].sort((a,b)=>BigInt(a.index)<BigInt(b.index)?-1:BigInt(a.index)>BigInt(b.index)?1:0);
    records.forEach((r,i)=>{r.prev=records[i-1]?.id||null;r.next=records[i+1]?.id||null});
    return {available:true,records,reachedBoundary:reachedBoundary||!hasMore,deleted:[...modelCache.values()].filter(m=>!m.visible).map(m=>m.clientId),atTop:!hasMore,atBottom:true};
  }
  function armCleanup(ids, baselineIds) {
    const baseline=new Set(baselineIds);
    if(new Set(ids).size!==ids.length||ids.some(id=>baseline.has(id)||!modelCache.get(id)?.contentPb?.sticker_card?.sticker?.url_list?.[0]))return {armed:false};
    allowedCleanup=new Set(ids);return {armed:!!client()};
  }
  async function deleteBatch(ids) {
    const c=client(),api=c?.instance.plugins?.find(p=>typeof p.api?.DeleteMessage==='function')?.api;
    if(!api||ids.length>16||new Set(ids).size!==ids.length||ids.some(id=>!allowedCleanup.has(id)||modelCache.get(id)?.conversationId!==c.conversation.id))throw Error('Scoped cleanup unavailable');
    const pending=ids.map(id=>modelCache.get(id)).filter(m=>m.visible);
    const already=ids.filter(id=>!modelCache.get(id).visible);
    const attempts=await Promise.allSettled(pending.map(m=>{
      const Long=m.indexInConversation.constructor;
      return api.DeleteMessage({conversationId:c.conversation.id,conversationShortId:Long.fromString(c.conversation.shortId),conversationType:m.conversationType,serverId:Long.fromString(m.serverId),inboxType:c.conversation.inboxType});
    }));
    const accepted=pending.filter((_,i)=>attempts[i].status==='fulfilled');
    const confirmed=accepted.length?await c.instance.getMessages({conversation:c.conversation,messages:accepted,upsert:true}):[];
    const removed=[...new Set([...already,...(confirmed||[]).filter(m=>m.conversationId===c.conversation.id&&!m.visible).map(m=>m.clientId)])];
    for(const id of removed){const m=modelCache.get(id);if(m)await c.instance.deleteMessage({message:m,localOnly:true});allowedCleanup.delete(id);}
    return {removed:removed.filter(id=>ids.includes(id)),failed:ids.filter(id=>!removed.includes(id))};
  }
  window.__stickerTransfer={snapshot,scroll,openMenu,deleteCopied,isSelfChat,mark,retrieve,armCleanup,deleteBatch};
  return JSON.stringify({installed:true});
})()
