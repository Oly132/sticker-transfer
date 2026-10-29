import {createHash} from 'node:crypto';
export class ReverseFavorites {
 constructor(saved=[],persist=()=>{}) {this.rows=new Map(saved.map(row=>[row.id,row]));this.persist=persist;this.batching=false;this.revision=0;this.changed=new Map();}
 capture(event) {
  const mutation=event?.syncAction, action=mutation?.syncAction?.value?.stickerAction;
  const index=mutation?.index;
  if(!action||!Array.isArray(index)||index[0]!=='favoriteSticker'||typeof index[1]!=='string')return false;
  const hash=Buffer.from(index[1],'base64');if(hash.length!==32)return false;
  const id=hash.toString('hex');
  this.changed.set(id,++this.revision);
  if(action.isFavorite!==true){this.rows.delete(id);this.save();return true;}
  if(!action.directPath||!action.mediaKey)return false;
  const key=Buffer.isBuffer(action.mediaKey)?action.mediaKey:Buffer.from(action.mediaKey.data||action.mediaKey,'base64');
  if(key.length!==32)return false;
  this.rows.set(id,{id,hash:index[1],directPath:action.directPath,mediaKey:key.toString('base64'),observedAt:this.rows.get(id)?.observedAt||Number(mutation.syncAction?.value?.timestamp)||Date.now(),
   mimetype:action.mimetype||'image/webp',isLottie:!!action.isLottie});this.save();return true;
 }
 batch(block){this.batching=true;try{block()}finally{this.batching=false;this.save()}}
 replaceSnapshot(mutations,revision){
  const snapshot=new ReverseFavorites();
  snapshot.batch(()=>{for(const mutation of mutations)snapshot.capture({syncAction:mutation})});
  for(const [id,row] of snapshot.rows){const old=this.rows.get(id);if(old)row.observedAt=old.observedAt;}
  // Live changes arriving during the network request take precedence.
  for(const [id,version] of this.changed)if(version>revision){
   if(this.rows.has(id))snapshot.rows.set(id,this.rows.get(id));else snapshot.rows.delete(id);
  }
  this.rows=snapshot.rows;this.save();
 }
 save(){if(!this.batching)this.persist([...this.rows.values()]);}
 list(){return [...this.rows.values()].map(({id,observedAt,isLottie})=>({id,observedAt,isLottie}));}
 verified(id,bytes){return /^[a-f0-9]{64}$/.test(id)&&createHash('sha256').update(bytes).digest('hex')===id;}
}
