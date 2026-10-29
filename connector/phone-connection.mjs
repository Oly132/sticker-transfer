export class ConnectionSupervisor {
 constructor({createSocket,credentials,saveCreds,loggedOutReason,schedule=setTimeout,unschedule=clearTimeout}) {
  Object.assign(this,{createSocket,credentials,saveCreds,loggedOutReason,schedule,unschedule});
  this.epoch=0;this.connected=false;this.loggedOut=false;this.readyToPair=false;this.state='Connecting';this.attempts=0;
 }
 connect() {
  const epoch=++this.epoch;
  if(this.timer)this.unschedule(this.timer);this.timer=null;
  this.connected=false;this.readyToPair=false;this.state='Reconnecting';
  if(this.socket){this.socket.ev.removeAllListeners('creds.update');this.socket.ev.removeAllListeners('connection.update');this.socket.end(new Error('Restarting connection'))}
  const socket=this.socket=this.createSocket();
  socket.ev.on('creds.update',()=>{if(epoch===this.epoch)Promise.resolve().then(()=>this.saveCreds()).catch(()=>{this.state='Could not save connection'})});
  socket.ev.on('connection.update',update=>{
   if(epoch!==this.epoch)return;
   if(update.qr){this.readyToPair=true;this.state='Ready to link'}
   if(update.connection==='open'){this.connected=true;this.loggedOut=false;this.disconnectCode=null;this.attempts=0;this.state='Connected'}
   if(update.connection==='close') {
    this.connected=false;this.readyToPair=false;
    this.disconnectCode=update.lastDisconnect?.error?.output?.statusCode??null;
    this.loggedOut=this.disconnectCode===this.loggedOutReason;
    this.state=this.loggedOut?'Link WhatsApp again':'Reconnecting';
    if(!this.loggedOut)this.timer=this.schedule(()=>{this.timer=null;try{this.connect()}catch{this.state='Connection failed'}},Math.min(10000,2500*(++this.attempts)));
    this.timer?.unref?.();
   }
  });
  return socket;
 }
 status() {
  const creds=this.credentials();
  return {connected:this.connected,ready:this.connected&&!!creds.myAppStateKeyId,readyToPair:this.readyToPair,
   registered:!!creds.registered&&!this.loggedOut,state:this.state,disconnectCode:this.disconnectCode??null};
 }
}
