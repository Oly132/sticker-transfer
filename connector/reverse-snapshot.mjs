/** Decode a separate read-only snapshot: never reset or write the live app-state version. */
export async function readFavoriteSnapshot(socket, favorites, {extract,decode,verifyEnvelope,signal,diagnostic=()=>{}}={}) {
 const revision=favorites.revision;
 const result=await socket.query({tag:'iq',attrs:{to:'s.whatsapp.net',xmlns:'w:sync:app:state',type:'set'},
  content:[{tag:'sync',attrs:{},content:[{tag:'collection',attrs:{name:'regular_low',version:'0',return_snapshot:'true'}}]}]},15000);
 const collection=(await extract(result,{signal})).regular_low;
 if(!collection?.snapshot)throw new Error('WhatsApp did not return its favourites snapshot. Try refreshing again.');
 let partial=false;
 const strictLogger={warn(_details,message){
  if(message==='LTHash verification failed on snapshot, continuing with partial state')partial=true;
  else throw new Error('Favourite snapshot integrity verification failed');
 }};
 const keys=new Map();
 const getKey=async id=>{
  if(!keys.has(id))keys.set(id,(await socket.authState.keys.get('app-state-sync-key',[id]))[id]);
  return keys.get(id);
 };
 const {mutationMap}=await decode('regular_low',collection.snapshot,async id=>
  getKey(id),undefined,true,strictLogger);
 if(partial){
  const verified=verifyEnvelope&&await verifyEnvelope(collection.snapshot,getKey);
  const records=collection.snapshot.records?.length||0,decoded=Object.keys(mutationMap).length;
  diagnostic({records,decoded,envelopeVerified:!!verified,partial:true});
  // Legacy app-state can have a broken aggregate after the library skips bad
  // records. Recover ONLY the individually value/index-MAC-verified records.
  // This is explicitly partial, never a verified full-state replacement.
  // A bad envelope without skipped records is a different failure and rejected.
  if(!verified&&(decoded===0||decoded>=records))throw new Error('Favourite snapshot integrity verification failed');
 }
 // Snapshot decoding completed before applying anything to our private catalogue.
 // Unreadable records may include favourites. Never infer removals from a partial catalogue.
 if(!partial&&favorites.replaceSnapshot)favorites.replaceSnapshot(Object.values(mutationMap),revision);
 else favorites.batch(()=>{for(const mutation of Object.values(mutationMap))favorites.capture({syncAction:mutation});});
 return favorites.list();
}
