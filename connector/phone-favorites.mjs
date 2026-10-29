export class OwnedFavorites {
 constructor(snapshot,save){this.active=new Set(snapshot.active||[]);this.removed=new Set(snapshot.removed||[]);this.save=save}
 commit(active,removed){
  this.save({active:[...active],removed:[...removed]});
  this.active.clear();for(const hash of active)this.active.add(hash);
  this.removed.clear();for(const hash of removed)this.removed.add(hash);
 }
 added(hash){const active=new Set(this.active),removed=new Set(this.removed);active.add(hash);removed.delete(hash);this.commit(active,removed)}
 async remove(hash,apply){
  if(typeof hash!=='string'||(!this.active.has(hash)&&!this.removed.has(hash)))throw new Error('This sticker was not added by Sticker Sync');
  if(this.removed.has(hash))return {removed:true,alreadyRemoved:true};
  await apply(hash);
  const active=new Set(this.active),removed=new Set(this.removed);active.delete(hash);removed.add(hash);this.commit(active,removed);
  return {removed:true,alreadyRemoved:false};
 }
}
