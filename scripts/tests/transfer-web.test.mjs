import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
function setup(otherPane=false){
 const uid='12345678',conversationId=`0:1:${uid}:${uid}`,calls=[];
 const messages=Array.from({length:120},(_,i)=>({clientId:'message-'+i,serverId:String(i+1),conversationId,indexInConversation:BigInt(i+1),visible:true,contentPb:{sticker_card:{sticker:{url_list:['https://p16.ibyteimg.com/tos/sticker-'+i]}}},conversationType:1}));
 // Long-shaped values are sufficient for the scoped API payload in these synthetic tests.
 const Long={fromString:value=>value};messages.forEach(m=>m.indexInConversation={constructor:Long,toString:()=>m.serverId});
 const conversation={id:conversationId,shortId:'1',inboxType:0};
 const instance={getConversation:()=>conversation,plugins:[{api:{DeleteMessage:async p=>{calls.push(p.serverId);messages.find(m=>m.serverId===p.serverId).visible=false}}}],
 getMessagesByConversation:async({cursor})=>{const remaining=messages.filter(m=>BigInt(m.serverId)<BigInt(cursor)).sort((a,b)=>Number(b.serverId)-Number(a.serverId));const page=remaining.slice(0,50);return {messages:page,hasMore:remaining.length>50,cursor:page.at(-1)?.serverId||'0'}},
 getMessages:async({messages:requested})=>requested,deleteMessage:async()=>{}};
 const root={'__reactFiber$test':{memoizedProps:{instance,value:{conversationId:otherPane?'0:1:'+uid+':87654321':conversationId}}}};
 const window={};vm.runInNewContext(fs.readFileSync('app/src/main/assets/transfer-web.js','utf8'),{window,document:{querySelector:()=>root,getElementById:()=>({textContent:JSON.stringify({__DEFAULT_SCOPE__:{'webapp.app-context':{user:{uid}}}})})}});
 const helper=window.__stickerTransfer;assert.equal(helper.isSelfChat(uid).verified,true);
 return {helper,calls,messages,instance};
}
test('server retrieval joins pages and skips invisible records without screen scrolling',async()=>{
 const {helper,messages}=setup();messages[119].visible=false;
 const result=await helper.retrieve(100,['message-0']);
 assert.equal(result.available,true);assert.equal(result.records.length,119);assert.equal(result.records.at(-1).id,'message-118');assert.equal(result.records[1].prev,'message-0');assert.equal(new Set(result.records.map(r=>r.id)).size,119);
});
test('fast cleanup rejects baseline IDs and removes only armed copied IDs',async()=>{
 const {helper,calls}=setup();await helper.retrieve(120);
 assert.equal(helper.armCleanup(['message-0'],['message-0']).armed,false);
 assert.equal(helper.armCleanup(['message-117','message-119'],['message-0']).armed,true);
 await assert.rejects(helper.deleteBatch(['message-0']),/Scoped/);
 const result=await helper.deleteBatch(['message-117','message-119']);
 assert.deepEqual([...result.removed],['message-117','message-119']);assert.deepEqual(calls,['118','120']);
});
test('a fulfilled network request without invisible server confirmation stays pending',async()=>{
 const {helper,instance}=setup();await helper.retrieve(120);helper.armCleanup(['message-119'],[]);
 instance.plugins[0].api.DeleteMessage=async()=>({status_code:500});
 const result=await helper.deleteBatch(['message-119']);assert.equal(result.removed.length,0);assert.deepEqual([...result.failed],['message-119']);
});
test('server watermark excludes original messages without scanning old deleted history',async()=>{
 const {helper}=setup();const result=await helper.retrieve(20,[],[],'100');
 assert.equal(result.reachedBoundary,true);assert.equal(result.records.length,20);
 assert.equal(result.records[0].index,'101');assert.equal(result.records.at(-1).index,'120');
});
test('model-only retrieval stays in the own conversation and refuses another visible pane',async()=>{
 const {helper}=setup(true);assert.equal(helper.isSelfChat('12345678').modelOnly,true);
 assert.throws(()=>helper.snapshot(),/not your self-chat/);
 assert.equal((await helper.retrieve(20,[],[],'100')).records.length,20);
});
test('another account cannot retrieve or clean messages',async()=>{
 const {helper}=setup();assert.equal(helper.isSelfChat('87654321').verified,false);
 assert.equal((await helper.retrieve(20)).available,false);
 assert.throws(()=>helper.snapshot(),/not your self-chat/);
});
