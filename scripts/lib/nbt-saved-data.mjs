import assert from 'node:assert/strict';import {gunzipSync,gzipSync} from 'node:zlib';
export function readNbt(compressed){
 const b=gunzipSync(compressed,{maxOutputLength:16*1024*1024});let at=0;
 const bytes=n=>{assert.ok(n>=0&&at+n<=b.length);const v=b.subarray(at,at+n);at+=n;return v;};
 const num=(n,method)=>bytes(n)[method](0),str=()=>bytes(num(2,'readUInt16BE')).toString('utf8');
 function value(t,depth=0){assert.ok(depth<64);switch(t){
  case 1:return num(1,'readInt8');case 2:return num(2,'readInt16BE');case 3:return num(4,'readInt32BE');case 4:return num(8,'readBigInt64BE');case 5:return num(4,'readFloatBE');case 6:return num(8,'readDoubleBE');
  case 7:return Buffer.from(bytes(num(4,'readInt32BE')));case 8:return str();
  case 9:{const type=num(1,'readUInt8'),n=num(4,'readInt32BE');assert.ok(n>=0&&n<=1000000);return {type,items:Array.from({length:n},()=>value(type,depth+1))};}
  case 10:{const result={};for(let type=num(1,'readUInt8');type;type=num(1,'readUInt8')){const name=str();assert.ok(!Object.hasOwn(result,name));result[name]={type,value:value(type,depth+1)};}return result;}
  case 11:case 12:{const n=num(4,'readInt32BE');assert.ok(n>=0&&n<=1000000);return Array.from({length:n},()=>num(t===11?4:8,t===11?'readInt32BE':'readBigInt64BE'));}
  default:throw Error('Unsupported NBT type '+t);
 }}
 const type=num(1,'readUInt8');assert.equal(type,10);const name=str(),data=value(type);assert.equal(at,b.length);return {name,data};
}
export function writeNbt(root){
 const chunks=[];function num(n,method,v){const b=Buffer.alloc(n);b[method](v,0);chunks.push(b);}function str(s){const b=Buffer.from(s,'utf8');assert.ok(b.length<=65535);num(2,'writeUInt16BE',b.length);chunks.push(b);}
 function value(t,v){switch(t){case 1:num(1,'writeInt8',v);break;case 2:num(2,'writeInt16BE',v);break;case 3:num(4,'writeInt32BE',v);break;case 4:num(8,'writeBigInt64BE',BigInt(v));break;case 5:num(4,'writeFloatBE',v);break;case 6:num(8,'writeDoubleBE',v);break;
 case 7:num(4,'writeInt32BE',v.length);chunks.push(v);break;case 8:str(v);break;
 case 9:num(1,'writeUInt8',v.type);num(4,'writeInt32BE',v.items.length);for(const item of v.items)value(v.type,item);break;
 case 10:for(const [name,tag]of Object.entries(v)){num(1,'writeUInt8',tag.type);str(name);value(tag.type,tag.value);}num(1,'writeUInt8',0);break;
 case 11:case 12:num(4,'writeInt32BE',v.length);for(const x of v)num(t===11?4:8,t===11?'writeInt32BE':'writeBigInt64BE',t===11?x:BigInt(x));break;
 default:throw Error('Unsupported NBT type '+t);
 }}num(1,'writeUInt8',10);str(root.name);value(10,root.data);return gzipSync(Buffer.concat(chunks));
}
export function grantBetaPass(root,uuid){
 assert.match(uuid,/^[a-f0-9]{8}-(?:[a-f0-9]{4}-){3}[a-f0-9]{12}$/);const b=Buffer.from(uuid.replaceAll('-',''),'hex'),ints=Array.from({length:4},(_,i)=>b.readInt32BE(i*4));
 const data=root.data.data;assert.equal(data.type,10);const store=data.value;
 if(store.season)assert.equal(store.season.value,'beta-rift-1');else store.season={type:8,value:'beta-rift-1'};
 store.open??={type:1,value:1};store.players??={type:9,value:{type:10,items:[]}};assert.equal(store.players.type,9);assert.equal(store.players.value.type,10);
 const rows=store.players.value.items,matches=rows.filter(r=>r.uuid?.type===11&&r.uuid.value.join(',')===ints.join(','));assert.ok(matches.length<=1);
 let row=matches[0];if(!row){row={uuid:{type:11,value:ints},xp:{type:3,value:0},rewards:{type:3,value:0},day:{type:8,value:''},week:{type:8,value:''},dc:{type:3,value:0},wc:{type:3,value:0},daily:{type:12,value:Array(6).fill(0n)},weekly:{type:12,value:Array(6).fill(0n)},equipped:{type:10,value:{}}};rows.push(row);}
 const before={xp:row.xp?.value??0,rewards:row.rewards?.value??0};row.xp={type:3,value:1000};row.rewards={type:3,value:1023};
 return {before,after:{xp:1000,rewards:1023},other_players:rows.length-1};
}
