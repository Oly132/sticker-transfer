import {test} from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
const source=fs.readFileSync('app/src/main/assets/tiktok-session.js','utf8');
function probe(content,readyState='complete') {
 return JSON.parse(vm.runInNewContext(source,{document:{readyState,getElementById:()=>content===undefined?null:{textContent:typeof content==='string'?content:JSON.stringify(content)}}}));
}
test('missing, partial and malformed hydration stay pending, not logged out',()=>{
 assert.equal(probe().state,'pending');
 assert.equal(probe({}).state,'pending');
 assert.equal(probe('{').state,'pending');
 assert.equal(probe({__DEFAULT_SCOPE__:{'webapp.app-context':{user:{}}}},'loading').state,'pending');
});
test('authenticated account accepts numeric/string uid and both nickname spellings',()=>{
 for(const [uid,nickname] of [['12345678',{nickName:'Display name'}],[12345678,{nickname:'Display name'}]]) {
  assert.deepEqual(probe({__DEFAULT_SCOPE__:{'webapp.app-context':{user:{uid,uniqueId:'test.account',...nickname}}}}),
   {state:'authenticated',id:'12345678',handle:'test.account',name:'Display name'});
 }
});
test('an absent nickname does not create a false login failure',()=>{
 const value=probe({__DEFAULT_SCOPE__:{'webapp.app-context':{user:{uid:'12345678',uniqueId:'test.account'}}}});
 assert.equal(value.state,'authenticated');assert.equal(value.name,'test.account');
});
test('another profile in UserModule never substitutes the signed-in account',()=>{
 assert.equal(probe({UserModule:{users:{example:{uid:'12345678',uniqueId:'other'}}}}).state,'pending');
});
test('guest state requires parsed app context and rejects invalid account IDs',()=>{
 for(const user of [null,{}, {uid:'undefined',uniqueId:'test.account'}, {uid:'12345678',uniqueId:'bad/handle'}]) {
  assert.equal(probe({__DEFAULT_SCOPE__:{'webapp.app-context':{user}}}).state,'guest');
 }
});
