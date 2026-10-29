import fs from 'node:fs';
import path from 'node:path';
import {createHash, randomUUID} from 'node:crypto';

const MAX = 8 * 1024 * 1024;
export function validOriginal(id, bytes) {
 return bytes.length >= 12 && bytes.length <= MAX && bytes.toString('ascii',0,4)==='RIFF' &&
 bytes.toString('ascii',8,12)==='WEBP' && createHash('sha256').update(bytes).digest('hex')===id;
}
export class DownloadFailure extends Error {
 constructor(code,message,{status=0,retryable=false}={}){super(message);this.code=code;this.status=status;this.retryable=retryable;}
}
export function classify(error) {
 if(error instanceof DownloadFailure)return error;
 const status=Number(error?.output?.statusCode||error?.response?.status||error?.status||0);
 if([404,410,401,403].includes(status))return new DownloadFailure('ORIGINAL_UNAVAILABLE',
  'WhatsApp no longer serves this original. Locate its file on your phone to recover it.',{status});
 const transient=!status||status===408||status===429||status>=500;
 return new DownloadFailure(transient?'DOWNLOAD_RETRY':'DOWNLOAD_REJECTED',
  transient?'This original could not download yet. Your other stickers are ready; retry it shortly.':'WhatsApp rejected this original download.',
  {status,retryable:transient});
}
/** Bounded, deduplicated downloads. Only hash-verified WebP originals enter the cache. */
export class ReverseDownloads {
 constructor({folder,download,timeout=9000,pause=ms=>new Promise(r=>setTimeout(r,ms)),now=Date.now}) {
  Object.assign(this,{folder,download,timeout,pause,now});this.pending=new Map();this.failures=new Map();fs.mkdirSync(folder,{recursive:true});
  this.resetNetworkFailures();
 }
 resetNetworkFailures(){this.networkFailures=0;this.networkUntil=0;}
 file(id){if(!/^[a-f0-9]{64}$/.test(id))throw new DownloadFailure('INVALID_STICKER','Invalid sticker');return path.join(this.folder,id+'.webp');}
 async get(row,{retry=false}={}) {
  const file=this.file(row.id);
  if(fs.existsSync(file)){if(validOriginal(row.id,fs.readFileSync(file)))return {filename:path.basename(file),verified:true,cached:true};fs.unlinkSync(file);}
  if(this.pending.has(row.id))return this.pending.get(row.id);
  if(this.networkUntil>this.now())throw new DownloadFailure('DOWNLOAD_RETRY','Network downloads paused after repeated failures. Saved originals are still usable; tap Retry to reconnect.',{retryable:true});
  const fingerprint=createHash('sha256').update((row.directPath||'')+(row.mediaKey||'')).digest('hex');
  const failed=this.failures.get(row.id);
  if(!retry&&failed?.fingerprint===fingerprint&&failed.until>this.now())throw failed.error;
  const task=this.fetch(row,file).catch(error=>{
   const safe=classify(error);
   if(safe.retryable&&++this.networkFailures>=3)this.networkUntil=this.now()+15000;
   this.failures.set(row.id,{fingerprint,error:safe,until:this.now()+(safe.retryable?15000:300000)});throw safe;
  }).finally(()=>this.pending.delete(row.id));
  this.pending.set(row.id,task);return task;
 }
 async fetch(row,file) {
  if(row.isLottie)throw new DownloadFailure('UNSUPPORTED','Lottie stickers cannot be imported into TikTok.');
  for(let attempt=0;attempt<2;attempt++){
   const controller=new AbortController();let stream,timer;
   try {
    const work=(async()=>{
     stream=await this.download({directPath:row.directPath,mediaKey:Buffer.from(row.mediaKey,'base64')},'sticker',{options:{signal:controller.signal}});
     if(controller.signal.aborted){stream.destroy();throw new Error('Download timed out');}
     let size=0;const chunks=[];
     for await(const chunk of stream){size+=chunk.length;if(size>MAX)throw new DownloadFailure('OVERSIZE','This original exceeds the 8 MB limit.');chunks.push(chunk);}
     return Buffer.concat(chunks);
    })();
    const deadline=new Promise((_,reject)=>{timer=setTimeout(()=>{controller.abort();stream?.destroy();reject(new DownloadFailure('DOWNLOAD_RETRY','The download timed out. You can retry this sticker.',{retryable:true}));},this.timeout);});
    const bytes=await Promise.race([work,deadline]);
    if(!validOriginal(row.id,bytes))throw new DownloadFailure('INVALID_ORIGINAL','The original failed its integrity check. Nothing was imported.');
    const temp=file+'.'+randomUUID()+'.tmp';
    try{fs.writeFileSync(temp,bytes);fs.renameSync(temp,file);}finally{if(fs.existsSync(temp))fs.unlinkSync(temp);}
    this.resetNetworkFailures();this.failures.delete(row.id);return {filename:path.basename(file),verified:true,cached:false};
   }catch(error){const safe=classify(error);if(!safe.retryable||attempt===1)throw safe;await this.pause(250);}
   finally{clearTimeout(timer);controller.abort();stream?.destroy();}
  }
 }
}
