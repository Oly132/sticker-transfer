import {createHmac,timingSafeEqual} from 'node:crypto';

/** Check the server's complete record envelope, including records a decoder cannot read.
 * Individual record value/index MAC checks remain enabled in the decoder.
 * Reuses the same LT-hash/key expansion primitives as the packaged sync library.
 */
export async function verifySnapshotEnvelope(snapshot,getKey,{makeLtHashGenerator,newLTHashState,expandAppStateKeys}) {
 const records=snapshot?.records;
 if(!Array.isArray(records)||records.length>100000)return false;
 const generator=makeLtHashGenerator(newLTHashState());
 for(const record of records){
  const index=record?.index?.blob,value=record?.value?.blob;
  if(!index||index.length!==32||!value||value.length<32)return false;
  generator.mix({indexMac:Buffer.from(index),valueMac:Buffer.from(value).subarray(-32),operation:0});
 }
 const keyId=snapshot?.keyId?.id;if(!keyId)return false;
 const key=await getKey(Buffer.from(keyId).toString('base64'));if(!key?.keyData)return false;
 const version=Buffer.alloc(8);
 try{version.writeBigUInt64BE(BigInt(snapshot.version.version.toString()));}catch{return false;}
 const expected=createHmac('sha256',expandAppStateKeys(key.keyData).snapshotMacKey)
  .update(Buffer.concat([Buffer.from(generator.finish().hash),version,Buffer.from('regular_low')])).digest();
 const actual=Buffer.from(snapshot.mac||[]);
 return actual.length===expected.length&&timingSafeEqual(actual,expected);
}
