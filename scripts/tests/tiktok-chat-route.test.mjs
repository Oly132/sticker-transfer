import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const script=fs.readFileSync('app/src/main/assets/tiktok-chat-route.js','utf8');
function setup({ready=false,count=1,helper=true,list=true}={}) {
 let verified=ready,clicks=0,now=1000;
 const window={};
 if(helper) window.__stickerTransfer={isSelfChat:uid=>({verified:uid==='12345678'&&verified})};
 const rows=Array.from({length:count},()=>({children:[],textContent:'My self chat',click(){clicks++}}));
 const route=vm.runInNewContext(`(${script})`,{window,document:{querySelector:()=>list?{querySelectorAll:()=>rows}:null},Date:{now:()=>now}});
 return {call:()=>JSON.parse(route('12345678','My self chat')),clicks:()=>clicks,ready:()=>verified=true,advance:()=>now+=700};
}
test('a verified warm self chat returns immediately without clicking or list loading',()=>{
 const s=setup({ready:true,list:false});assert.equal(s.call().verified,true);assert.equal(s.clicks(),0);
});
test('ambiguous names never open a potentially wrong conversation',()=>{
 const s=setup({count:2});assert.equal(s.call().ambiguous,true);assert.equal(s.clicks(),0);
});
test('a unique row is clicked once while React is still updating, then UID is verified',()=>{
 const s=setup();assert.equal(s.call().clicked,true);assert.equal(s.clicks(),1);
 assert.equal(s.call().waiting,true);assert.equal(s.clicks(),1);
 s.ready();assert.equal(s.call().verified,true);assert.equal(s.clicks(),1);
});
test('a click that did not open the chat may be retried after a bounded interval',()=>{
 const s=setup();s.call();s.advance();s.call();assert.equal(s.clicks(),2);
});
test('a replaced document requests helper installation instead of throwing an error',()=>{
 const s=setup({helper:false});assert.equal(s.call().needsHelper,true);assert.equal(s.clicks(),0);
});
test('a matching display name alone never counts as verified self identity',()=>{
 const s=setup();assert.equal(s.call().verified,false);assert.equal(s.call().waiting,true);
});
