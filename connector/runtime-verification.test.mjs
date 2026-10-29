import {test,after} from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import fs from 'node:fs';
import {ReverseFavorites} from './reverse-favorites.mjs';
import {readFavoriteSnapshot} from './reverse-snapshot.mjs';
import {verifySnapshotEnvelope} from './reverse-integrity.mjs';
import {expandAppStateKeys} from '../app/build/runtime-verification/node_modules/whatsapp-rust-bridge/dist/index.js';
const runtime=await import('../app/build/runtime-verification/node_modules/baileys/lib/index.js');
const {getHttpStream,downloadContentFromMessage,encryptedStream}=runtime;
test('packaged connector exports the functions used by both transfer directions',()=>{
 for(const name of ['default','downloadContentFromMessage','extractSyncdPatches','decodeSyncdSnapshot','encryptedStream'])assert.equal(typeof runtime[name],'function',name);
});
test('packaged HTTP stream respects cancellation before receiving headers',async()=>{
 const server=http.createServer(()=>{});await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),50);
 try{await assert.rejects(getHttpStream(`http://127.0.0.1:${server.address().port}/stall`,{signal:controller.signal}));}
 finally{clearTimeout(timer);server.closeAllConnections();await new Promise(r=>server.close(r));}
});
test('packaged media decrypts a real encrypted fixture and propagates a truncated connection',async()=>{
 const original=Buffer.from('RIFF1234WEBPtest encrypted fixture content');
 const media=await encryptedStream(original,'sticker');
 const encrypted=fs.readFileSync(media.encFilePath);
 const server=http.createServer((req,res)=>{
  if(req.url==='/ok'){res.writeHead(200);res.end(encrypted);}
  else{res.writeHead(200,{'Content-Length':encrypted.length+100});res.write(encrypted.subarray(0,8));setTimeout(()=>res.destroy(),10);}
 });
 await new Promise(r=>server.listen(0,'127.0.0.1',r));const base=`http://127.0.0.1:${server.address().port}`;
 try{
  const stream=await downloadContentFromMessage({url:base+'/ok',mediaKey:media.mediaKey},'sticker');
  const chunks=[];for await(const chunk of stream)chunks.push(chunk);assert.deepEqual(Buffer.concat(chunks),original);
  const broken=await downloadContentFromMessage({url:base+'/broken',mediaKey:media.mediaKey},'sticker');
  await assert.rejects((async()=>{for await(const chunk of broken){void chunk;}})());
 }finally{fs.rmSync(media.encFilePath,{force:true});server.closeAllConnections();await new Promise(r=>server.close(r));}
});
test('real app-state snapshot decodes sticker metadata without changing the linked session',async()=>{
 const hash=Buffer.alloc(32,7).toString('base64'),keyId=Buffer.alloc(6,3).toString('base64');
 const key={keyData:Buffer.alloc(32,8)};
 const encoded=await runtime.encodeSyncdPatch({type:'regular_low',index:['favoriteSticker',hash],apiVersion:7,
  operation:runtime.proto.SyncdMutation.SyncdOperation.SET,
  syncAction:{timestamp:1234,stickerAction:{isFavorite:true,directPath:'/fixture',mediaKey:Buffer.alloc(32,4),mimetype:'image/webp'}}},
  keyId,runtime.newLTHashState(),async()=>key);
 const snapshot={version:{version:encoded.state.version},records:encoded.patch.mutations.map(m=>m.record),keyId:encoded.patch.keyId,mac:encoded.patch.snapshotMac};
 const socket={query:async()=>({}),authState:{keys:{get:async(_kind,ids)=>({[ids[0]]:key}),set:()=>assert.fail('live keys must not change')}}};
 const favorites=new ReverseFavorites();
 const helpers={extract:async()=>({regular_low:{snapshot}}),decode:runtime.decodeSyncdSnapshot};
 const rows=await readFavoriteSnapshot(socket,favorites,helpers);
 assert.equal(rows.length,1);assert.equal(rows[0].id,Buffer.from(hash,'base64').toString('hex'));
 assert.equal(favorites.rows.get(rows[0].id).mediaKey,Buffer.alloc(32,4).toString('base64'));
 snapshot.mac=Buffer.alloc(32);
 await assert.rejects(readFavoriteSnapshot(socket,favorites,helpers),/integrity/);
 assert.equal(favorites.rows.size,1,'bad snapshot must not erase the last good catalogue');
});
test('an unreadable record cannot block authenticated favourites or erase prior favourites',async()=>{
 const keyId=Buffer.alloc(6,3).toString('base64'),key={keyData:Buffer.alloc(32,8)};
 const patch=byte=>({type:'regular_low',index:['favoriteSticker',Buffer.alloc(32,byte).toString('base64')],apiVersion:7,
  operation:runtime.proto.SyncdMutation.SyncdOperation.SET,syncAction:{timestamp:1234,stickerAction:{isFavorite:true,directPath:'/fixture',mediaKey:Buffer.alloc(32,4)}}});
 const one=await runtime.encodeSyncdPatch(patch(7),keyId,runtime.newLTHashState(),async()=>key);
 const two=await runtime.encodeSyncdPatch(patch(9),keyId,one.state,async()=>key);
 const corrupt=runtime.proto.SyncdRecord.decode(runtime.proto.SyncdRecord.encode(two.patch.mutations[0].record).finish());
 corrupt.value.blob[0]^=1; // Keep its recorded value MAC, invalidate the individual encrypted payload.
 const snapshot={version:{version:two.state.version},records:[one.patch.mutations[0].record,corrupt],keyId:two.patch.keyId,mac:two.patch.snapshotMac};
 const favorites=new ReverseFavorites([{id:'aa'.repeat(32),observedAt:1}]);let diagnostic;
 const socket={query:async()=>({}),authState:{keys:{get:async(_k,ids)=>({[ids[0]]:key}),set:()=>assert.fail('must not change live state')}}};
 const helpers={extract:async()=>({regular_low:{snapshot}}),decode:runtime.decodeSyncdSnapshot,
  verifyEnvelope:(value,getKey)=>verifySnapshotEnvelope(value,getKey,{...runtime,expandAppStateKeys}),diagnostic:value=>diagnostic=value};
 const result=await readFavoriteSnapshot(socket,favorites,helpers);
 assert.equal(result.length,2);assert(favorites.rows.has('07'.repeat(32)));assert(!favorites.rows.has('09'.repeat(32)));
 assert.deepEqual(diagnostic,{records:2,decoded:1,envelopeVerified:true,partial:true});
 snapshot.mac=Buffer.alloc(32);
 await readFavoriteSnapshot(socket,favorites,helpers);
 assert.equal(diagnostic.envelopeVerified,false);
 assert.equal(diagnostic.partial,true);
 assert(!favorites.rows.has('09'.repeat(32)),'a partial recovery must never accept the bad encrypted record');
 assert.equal(favorites.rows.size,2);
});
