const vm = require('vm');
const fs = require('fs');
const assert = require('assert');
const source = fs.readFileSync('app/assets/offline-map/compat.js', 'utf8');
const context = vm.createContext({});
vm.runInContext(`
 this.self=this; this.document={}; delete this.globalThis;
 delete Array.prototype.flat; delete Array.prototype.flatMap;
 this.Worker=function(){}; Worker.prototype.postMessage=function(message, transfer){
   if(!Array.isArray(transfer)) throw Error('Old worker requires transfer array');
   this.transferLength=transfer.length;
 };
 this.MessagePort=function(){}; MessagePort.prototype.postMessage=Worker.prototype.postMessage;
`, context);
vm.runInContext(source, context);
assert.equal(vm.runInContext('globalThis===self', context), true);
assert.equal(vm.runInContext('JSON.stringify([1,[2,[3]]].flat(2))', context), '[1,2,3]');
assert.equal(vm.runInContext('JSON.stringify([,1,,[2]].flat())', context), '[1,2]');
assert.equal(vm.runInContext('JSON.stringify([1,2].flatMap(x=>[x,x]))', context), '[1,1,2,2]');
assert.equal(vm.runInContext('var w=new Worker();w.postMessage({}, {transfer:[1,2]});w.transferLength', context), 2);
assert.equal(vm.runInContext('w.postMessage({}, [1]);w.transferLength', context), 1);
assert.equal(vm.runInContext('w.postMessage({});w.transferLength', context), 0);
// Also exercise the dedicated worker's global postMessage overload.
vm.runInContext('delete this.document;this.postMessage=Worker.prototype.postMessage', context);
vm.runInContext(source, context);
assert.equal(vm.runInContext('self.postMessage({}, {transfer:[1]}); self.transferLength', context), 1);
console.log('Legacy page/worker API compatibility checks passed');
