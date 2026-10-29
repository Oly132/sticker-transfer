import {test} from 'node:test';
import assert from 'node:assert/strict';
import {Readable} from 'node:stream';
import {createHash} from 'node:crypto';
import fs from 'node:fs';import os from 'node:os';import path from 'node:path';
import {ReverseDownloads,validOriginal} from './reverse-downloads.mjs';
import {readFavoriteSnapshot} from './reverse-snapshot.mjs';
import {ReverseFavorites} from './reverse-favorites.mjs';
const bytes=Buffer.from('RIFF1234WEBPtest-original');
const id=createHash('sha256').update(bytes).digest('hex');
const row={id,directPath:'/private-original',mediaKey:Buffer.alloc(32).toString('base64')};
function fixture(t,download,extra={}){const folder=fs.mkdtempSync(path.join(os.tmpdir(),'sticker-download-'));t.after(()=>fs.rmSync(folder,{recursive:true,force:true}));return new ReverseDownloads({folder,download,pause:async()=>{},...extra});}
test('parallel callers share one request; verified cache works offline',async t=>{
 let calls=0;const store=fixture(t,async()=>{calls++;return Readable.from([bytes]);});
 await Promise.all([store.get(row),store.get(row),store.get(row)]);assert.equal(calls,1);
 assert.equal((await store.get(row)).cached,true);assert.equal(calls,1);
});
test('404 preserves status, does not retry or hammer an expired URL',async t=>{
 let calls=0;const store=fixture(t,async()=>{calls++;throw {output:{statusCode:404}};});
 for(let i=0;i<3;i++)await assert.rejects(store.get(row),e=>e.code==='ORIGINAL_UNAVAILABLE'&&e.status===404&&!e.message.includes(row.directPath));
 assert.equal(calls,1);await assert.rejects(store.get(row,{retry:true}));assert.equal(calls,2);
});
test('one transient failure retries and recovers',async t=>{
 let calls=0;const store=fixture(t,async()=>{if(++calls===1)throw {status:503};return Readable.from([bytes]);});
 await store.get(row);assert.equal(calls,2);
});
test('stalled fetch is bounded and aborted',async t=>{
 let aborted=0;const store=fixture(t,async(_row,_type,{options})=>new Promise(()=>{options.signal.addEventListener('abort',()=>aborted++);}),{timeout:20});
 await assert.rejects(store.get(row),e=>e.retryable);assert.equal(aborted,2);
});
test('stream errors terminate instead of hanging',async t=>{
 const store=fixture(t,async()=>Readable.from((async function*(){yield bytes.subarray(0,4);throw Error('socket closed');})()));
 await assert.rejects(store.get(row),e=>e.retryable);
});
test('wrong bytes never enter cache; corrupt cache is replaced',async t=>{
 const bad=fixture(t,async()=>Readable.from([Buffer.from('wrong')]));await assert.rejects(bad.get(row),e=>e.code==='INVALID_ORIGINAL');assert.equal(fs.existsSync(bad.file(id)),false);
 const good=fixture(t,async()=>Readable.from([bytes]));fs.writeFileSync(good.file(id),'bad');await good.get(row);assert(validOriginal(id,fs.readFileSync(good.file(id))));
});
test('new media metadata bypasses old URL failure cooldown',async t=>{
 const store=fixture(t,async source=>{if(source.directPath===row.directPath)throw {status:410};return Readable.from([bytes]);});
 await assert.rejects(store.get(row));await store.get({...row,directPath:'/new'});
});
test('snapshot only reads keys, verifies MACs, never writes live sync version',async()=>{
 let captured=0,queries=0,reads=0;
 const socket={query:async request=>{queries++;assert.equal(request.content[0].content[0].attrs.version,'0');return {};},authState:{keys:{get:async(kind,ids)=>{reads++;assert.equal(kind,'app-state-sync-key');return {[ids[0]]:{keyData:Buffer.alloc(32)}};},set:()=>assert.fail('must not write auth state')}}};
 const result=await readFavoriteSnapshot(socket,{capture:()=>captured++,batch:f=>f(),list:()=>[1]},
 {extract:async()=>({regular_low:{snapshot:{}}}),decode:async(_n,_s,key,_v,verify)=>{assert.equal(verify,true);await key('key');return {mutationMap:{one:{}}};}});
 assert.equal(captured,1);assert.equal(reads,1);assert.equal(queries,1);assert.deepEqual(result,[1]);
});
test('a network outage does not start two timed-out requests for every favourite',async t=>{
 let calls=0;const store=fixture(t,async()=>{calls++;throw {status:503};});
 for(let i=0;i<4;i++)await assert.rejects(store.get({...row,id:i.toString(16).padStart(64,'0')}));
 assert.equal(calls,6,'circuit opens after three failed originals');
 store.resetNetworkFailures();await assert.rejects(store.get({...row,id:'f'.repeat(64)}));assert.equal(calls,8);
});
test('full snapshots remove stale favourites while preserving live edits and stable ordering',()=>{
 const mutation=(byte,isFavorite=true)=>({index:['favoriteSticker',Buffer.alloc(32,byte).toString('base64')],syncAction:{value:{stickerAction:{isFavorite,directPath:'/fixture',mediaKey:Buffer.alloc(32)}}}});
 const favorites=new ReverseFavorites();favorites.capture({syncAction:mutation(1)});favorites.capture({syncAction:mutation(2)});
 const version=favorites.revision;const firstTime=favorites.rows.get('01'.repeat(32)).observedAt;
 favorites.capture({syncAction:mutation(3)});
 favorites.replaceSnapshot([mutation(1)],version);
 assert.equal(favorites.rows.size,2);assert(favorites.rows.has('03'.repeat(32)));assert(!favorites.rows.has('02'.repeat(32)));
 assert.equal(favorites.rows.get('01'.repeat(32)).observedAt,firstTime);
 const next=favorites.revision;favorites.capture({syncAction:mutation(1,false)});favorites.replaceSnapshot([mutation(1)],next);
 assert(!favorites.rows.has('01'.repeat(32)));
});
