import {encodeSyncdPatch} from 'baileys';
import {expandAppStateKeys} from 'whatsapp-rust-bridge';
import {createHmac} from 'node:crypto';
function versionBytes(version){const b=Buffer.alloc(8);b.writeBigUInt64BE(BigInt(version));return b}
export async function encodeStickerBatch(items,keyId,initial,getKey){
 if(!Array.isArray(items)||items.length<1||items.length>16||items.some(p=>p.type!=='regular_low'||p.index?.[0]!=='favoriteSticker')||new Set(items.map(p=>p.index[1])).size!==items.length)throw Error('Invalid sticker batch');
 let state=initial;const mutations=[];
 for(const item of items){const encoded=await encodeSyncdPatch(item,keyId,{...state,version:initial.version},getKey);state=encoded.state;mutations.push(...encoded.patch.mutations)}
 const keys=expandAppStateKeys((await getKey(keyId)).keyData),type=Buffer.from('regular_low'),version=versionBytes(state.version);
 const snapshotMac=createHmac('sha256',keys.snapshotMacKey).update(Buffer.concat([state.hash,version,type])).digest();
 const valueMacs=mutations.map(m=>Buffer.from(m.record.value.blob).subarray(-32));
 const patchMac=createHmac('sha256',keys.patchMacKey).update(Buffer.concat([snapshotMac,...valueMacs,version,type])).digest();
 return {state,patch:{mutations,keyId:{id:Buffer.from(keyId,'base64')},snapshotMac,patchMac}};
}
