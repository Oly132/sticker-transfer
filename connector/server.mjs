import makeWASocket,{Browsers,DisconnectReason,encryptedStream,proto,downloadContentFromMessage,extractSyncdPatches,decodeSyncdSnapshot,makeLtHashGenerator,newLTHashState} from 'baileys';
import {expandAppStateKeys} from 'whatsapp-rust-bridge';
import {atomicAuthState,atomicJson,resilientJson} from './phone-auth-store.mjs';
import {ConnectionSupervisor} from './phone-connection.mjs';
import {OwnedFavorites} from './phone-favorites.mjs';
import {ReverseFavorites} from './reverse-favorites.mjs';
import {ReverseDownloads,DownloadFailure,classify} from './reverse-downloads.mjs';
import {readFavoriteSnapshot} from './reverse-snapshot.mjs';
import {verifySnapshotEnvelope} from './reverse-integrity.mjs';
import fs from 'node:fs';
import path from 'node:path';
import http from 'node:http';
import {createHash} from 'node:crypto';
import {fileURLToPath} from 'node:url';
const root=path.dirname(fileURLToPath(import.meta.url));
const config=JSON.parse(fs.readFileSync(path.join(root,'rpc-config.json'),'utf8'));
const savedFile=path.join(root,'transferred.json');
const ownershipFile=path.join(root,'owned-favorites.json');
const ownership=new OwnedFavorites(resilientJson(ownershipFile)||{active:resilientJson(savedFile)||[]},value=>atomicJson(ownershipFile,value));
const transferred=ownership.active;
const reverse=new ReverseFavorites(resilientJson(path.join(root,'reverse-favorites.json'))||[],rows=>atomicJson(path.join(root,'reverse-favorites.json'),rows));
const reverseList=()=>reverse.list().map(row=>({...row,fromTikTok:transferred.has(reverse.rows.get(row.id)?.hash)}));
const downloads=new ReverseDownloads({folder:path.join(root,'../reverse/originals'),download:downloadContentFromMessage});
let reverseRefresh=null,reversePartial=false;
let code='',busy=false;
const logger={level:'trace',child(){return this},trace(value){reverse.capture(value)},debug(){},info(){},warn(){},error(){},fatal(){}};
let {state,saveCreds}=atomicAuthState(path.join(root,'auth'));
const connection=new ConnectionSupervisor({credentials:()=>state.creds,saveCreds:()=>saveCreds(),loggedOutReason:DisconnectReason.loggedOut,
 createSocket:()=>makeWASocket({auth:state,logger,browser:Browsers.ubuntu('Chrome'),connectTimeoutMs:15000,keepAliveIntervalMs:15000,
  syncFullHistory:false,shouldSyncHistoryMessage:()=>false,markOnlineOnConnect:false})});
const status=()=>({...connection.status(),node:process.version,transferred:transferred.size,busy,pid:process.pid});
http.createServer(async(req,res)=>{
 res.setHeader('Content-Type','application/json');res.setHeader('Cache-Control','no-store');
 const reply=(code,value)=>{res.writeHead(code);res.end(JSON.stringify(value))};
 if(req.headers.authorization!=='Bearer '+config.token||req.headers.origin){reply(403,{error:'Unauthorized'});return}
 try{
  if(req.method==='GET'&&req.url==='/status'){reply(200,status());return}
  if(req.method==='GET'&&req.url==='/reverse/favorites'){reply(200,{favorites:reverseList()});return}
  let raw='';for await(const chunk of req){raw+=chunk;if(raw.length>8192)throw new Error('Request too large')}
  const body=JSON.parse(raw||'{}');
  if(req.method==='POST'&&req.url==='/reverse/refresh'){
   downloads.resetNetworkFailures();
   if(!connection.status().ready)throw new Error('Connect WhatsApp first.');
   if(!reverseRefresh){
    reversePartial=false;
    const controller=new AbortController();const timer=setTimeout(()=>controller.abort(),18000);
    reverseRefresh=readFavoriteSnapshot(connection.socket,reverse,{extract:extractSyncdPatches,decode:decodeSyncdSnapshot,
     verifyEnvelope:(snapshot,getKey)=>verifySnapshotEnvelope(snapshot,getKey,{makeLtHashGenerator,newLTHashState,expandAppStateKeys}),
     diagnostic:value=>{reversePartial=value.partial===true;},signal:controller.signal}).finally(()=>{clearTimeout(timer);reverseRefresh=null});
   }
   await reverseRefresh;reply(200,{favorites:reverseList(),partial:reversePartial});return;
  }
  if(req.method==='POST'&&req.url==='/reverse/download'){
   const row=reverse.rows.get(body.id);if(!row)throw new Error('Refresh your WhatsApp favourites first.');
   reply(200,await downloads.get(row,{retry:body.retry===true}));return;
  }
  if(req.method==='POST'&&req.url==='/reconnect') {
   if(!busy&&!connection.loggedOut)connection.connect();
   reply(200,status());return;
  }
  if(req.method==='POST'&&req.url==='/pair'){
   if(state.creds.registered&&!connection.loggedOut){reply(200,{alreadyLinked:true});return}
   if(state.creds.pairingCode||connection.loggedOut){
    connection.epoch++;connection.socket?.ev.removeAllListeners('creds.update');connection.socket?.ev.removeAllListeners('connection.update');connection.socket?.end(new Error('Fresh pairing'));
    fs.rmSync(path.join(root,'auth'),{recursive:true,force:true});
    ({state,saveCreds}=atomicAuthState(path.join(root,'auth')));connection.loggedOut=false;connection.connect();
    for(let wait=0;wait<20&&!connection.readyToPair;wait++)await new Promise(r=>setTimeout(r,300));
   }
   if(!connection.readyToPair)throw new Error('Connection is starting. Try again in a moment.');
   if(!/^[1-9][0-9]{6,14}$/.test(body.number))throw new Error('Enter your phone number with country code');
   code=await connection.socket.requestPairingCode(body.number);reply(200,{code});return;
  }
  if(req.method==='POST'&&req.url==='/remove-favorite'){
   if(!status().ready)throw new Error('WhatsApp is not ready. Reconnect and try again.');
   if(busy)throw new Error('A sticker operation is still running');
   busy=true;
   try{const result=await ownership.remove(body.hash,hash=>connection.socket.appPatch({type:'regular_low',index:['favoriteSticker',hash],apiVersion:7,operation:proto.SyncdMutation.SyncdOperation.SET,syncAction:{timestamp:Date.now(),stickerAction:{isFavorite:false}}}));reply(200,result)}finally{busy=false}
   return;
  }
  if(req.method==='POST'&&req.url==='/favorite-batch'){
   if(!status().ready)throw new Error('WhatsApp is not ready. Reconnect and try again.');
   if(busy)throw new Error('A sticker operation is still running');
   if(!Array.isArray(body.filenames)||body.filenames.length<1||body.filenames.length>16||new Set(body.filenames).size!==body.filenames.length)throw new Error('Invalid sticker batch');
   busy=true;
   try{
    const results=[],newByHash=new Map(),started=Date.now();let fallback=false;
    for(let start=0;start<body.filenames.length;start+=16){
     const group=await Promise.all(body.filenames.slice(start,start+16).map(async filename=>{
      try{
       if(!/^[A-Za-z0-9_-]+\.webp$/.test(filename))throw new Error('Invalid sticker file');
       const buffer=fs.readFileSync(path.join(root,'../transfer/converted',filename));
       if(buffer.length>512000||buffer.toString('ascii',0,4)!=='RIFF'||buffer.toString('ascii',8,12)!=='WEBP')throw new Error('Invalid sticker');
       const hash=createHash('sha256').update(buffer).digest('base64');
       if(transferred.has(hash))return {filename,hash,added:false,duplicate:true};
       const media=await encryptedStream(buffer,'sticker',{logger});
       try{
        const upload=await connection.socket.waUploadToServer(media.encFilePath,{mediaType:'sticker',fileEncSha256B64:media.fileEncSha256.toString('base64'),timeoutMs:15000});
        return {filename,hash,action:{fileEncSha256:media.fileEncSha256,mediaKey:media.mediaKey,mimetype:'image/webp',height:512,width:512,directPath:upload.directPath,fileLength:media.fileLength,isFavorite:true,deviceIdHint:0,isLottie:false,isAvatarSticker:false}};
       }finally{fs.rmSync(media.encFilePath,{force:true})}
      }catch(e){return {filename,error:e.message?.replace(/https?:\/\/\S+/g,'[address]').slice(0,160)||'Sticker upload failed'}}
     }));
     for(const row of group){results.push(row);if(row.action&&!newByHash.has(row.hash))newByHash.set(row.hash,row)}
    }
    const fresh=[...newByHash.values()],uploadedAt=Date.now();
    const patch=row=>({type:'regular_low',index:['favoriteSticker',row.hash],apiVersion:7,operation:proto.SyncdMutation.SyncdOperation.SET,syncAction:{timestamp:Date.now(),stickerAction:row.action}});
    if(fresh.length){
     try{await connection.socket.appPatch({type:'regular_low',stickerBatch:fresh.map(patch)});for(const row of fresh)ownership.added(row.hash)}
     catch{
      fallback=true;
      // A rejected or interrupted batch falls back to the existing checked single-sticker path.
      for(const row of fresh)try{await connection.socket.appPatch(patch(row));ownership.added(row.hash)}catch{row.error='WhatsApp did not confirm this sticker. Try again.'}
     }
    }
    const added=new Set();
    const stats={count:body.filenames.length,uploadMs:uploadedAt-started,totalMs:Date.now()-started,fallback};
    atomicJson(path.join(root,'last-batch-performance.json'),stats);
    reply(200,{performance:stats,results:results.map(row=>{
     if(row.error)return {filename:row.filename,error:row.error};
     if(row.action){const canonical=newByHash.get(row.hash);if(canonical.error)return {filename:row.filename,error:canonical.error};const first=!added.has(row.hash);added.add(row.hash);return {filename:row.filename,hash:row.hash,added:first,duplicate:!first}}
     return row;
    })});
   }finally{busy=false}
   return;
  }
  if(req.method==='POST'&&req.url==='/favorite'){
   if(!status().ready)throw new Error('WhatsApp is not ready. Link it and wait a moment.');
   if(busy)throw new Error('A sticker is still transferring');
   if(!/^[A-Za-z0-9_-]+\.webp$/.test(body.filename))throw new Error('Invalid sticker file');
   const filename=path.join(root,'../transfer/converted',body.filename);
   const buffer=fs.readFileSync(filename);if(buffer.length>512000||buffer.toString('ascii',0,4)!=='RIFF'||buffer.toString('ascii',8,12)!=='WEBP')throw new Error('Invalid sticker');
   const hash=createHash('sha256').update(buffer).digest('base64');
   if(transferred.has(hash)){reply(200,{added:false,duplicate:true,hash});return}
   busy=true;
   const sock=connection.socket;
   try{
    const media=await encryptedStream(buffer,'sticker',{logger});
    try{
     const upload=await sock.waUploadToServer(media.encFilePath,{mediaType:'sticker',fileEncSha256B64:media.fileEncSha256.toString('base64'),timeoutMs:30000});
     await sock.appPatch({type:'regular_low',index:['favoriteSticker',hash],apiVersion:7,operation:proto.SyncdMutation.SyncdOperation.SET,syncAction:{timestamp:Date.now(),stickerAction:{fileEncSha256:media.fileEncSha256,mediaKey:media.mediaKey,mimetype:'image/webp',height:512,width:512,directPath:upload.directPath,fileLength:media.fileLength,isFavorite:true,deviceIdHint:0,isLottie:false,isAvatarSticker:false}}});
     ownership.added(hash);reply(200,{added:true,hash});
    }finally{fs.rmSync(media.encFilePath,{force:true})}
   }finally{busy=false}
   return;
  }
  reply(404,{error:'Unknown operation'});
 }catch(e){if(e instanceof DownloadFailure){reply(422,{error:e.message,errorCode:e.code,httpStatus:e.status,retryable:e.retryable});return}reply(400,{error:e.message?.replace(/https?:\/\/\S+/g,'[address]').slice(0,180)||'Could not finish'})}
}).listen(8769,'127.0.0.1');
console.log('Sticker Sync phone connector started');
connection.connect();
