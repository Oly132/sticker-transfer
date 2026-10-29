import fs from 'node:fs';
import path from 'node:path';
import {BufferJSON,initAuthCreds,proto} from 'baileys';

export function atomicJson(file, value, replacer) {
 const text=JSON.stringify(value,replacer), temp=file+'.tmp';
 fs.mkdirSync(path.dirname(file),{recursive:true});
 if(fs.existsSync(file)) {
  try {JSON.parse(fs.readFileSync(file,'utf8'));fs.copyFileSync(file,file+'.bak.tmp');fs.renameSync(file+'.bak.tmp',file+'.bak')}catch{}
 }
 const descriptor=fs.openSync(temp,'w');
 try {fs.writeFileSync(descriptor,text);if(path.basename(file)==='creds.json')fs.fsyncSync(descriptor)}finally{fs.closeSync(descriptor)}
 fs.renameSync(temp,file);
}
export function resilientJson(file, reviver, valid=()=>true) {
 for(const candidate of [file,file+'.bak']) {
  try {
   const value=JSON.parse(fs.readFileSync(candidate,'utf8'),reviver);
   if(!valid(value))continue;
   if(candidate!==file) {fs.copyFileSync(candidate,file+'.repair');fs.renameSync(file+'.repair',file)}
   return value;
  }catch{}
 }
 return null;
}
export function atomicAuthState(folder) {
 fs.mkdirSync(folder,{recursive:true});
 const filename=name=>path.join(folder,name.replace(/\//g,'__').replace(/:/g,'-'));
 const read=name=>resilientJson(filename(name),BufferJSON.reviver);
 let creds=resilientJson(filename('creds.json'),BufferJSON.reviver,value=>!!value?.noiseKey?.private&&!!value?.signedIdentityKey?.private);
 if(!creds?.noiseKey?.private||!creds?.signedIdentityKey?.private) {
  if(fs.existsSync(filename('creds.json'))) fs.renameSync(filename('creds.json'),filename('creds.corrupt-'+Date.now()+'.json'));
  creds=initAuthCreds();
 }
 const state={creds,keys:{
  get:async(type,ids)=>Object.fromEntries(ids.map(id=>{
   let value=read(`${type}-${id}.json`);
   if(type==='app-state-sync-key'&&value)value=proto.Message.AppStateSyncKeyData.fromObject(value);
   return [id,value];
  })),
  set:async data=>{
   for(const [category,entries] of Object.entries(data))for(const [id,value] of Object.entries(entries)) {
    const file=filename(`${category}-${id}.json`);
    if(value)atomicJson(file,value,BufferJSON.replacer);
    else {fs.rmSync(file,{force:true});fs.rmSync(file+'.bak',{force:true})}
   }
  }
 }};
 return {state,saveCreds:()=>atomicJson(filename('creds.json'),creds,BufferJSON.replacer)};
}
