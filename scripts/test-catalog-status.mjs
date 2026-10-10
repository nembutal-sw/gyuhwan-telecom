// 서버에서 받은 ID를 유지하면서 숨긴 용량·미등록 용량·신규 1T 모델 선택이 섞이지 않게 검사한다.
import assert from 'node:assert/strict';
import {visibleGroups,variantId,sellingLabel} from '../frontend/public/catalog-core.mjs';
const original=[{id:8,name:'플립',variants:{256:8,512:9}},{id:48,name:'신규',variants:{'1T':48},managed:true}];
const visible=visibleGroups(original,[{id:8,hidden:true},{id:9,hidden:false},{id:48,hidden:false}]);
assert.equal(visible[0].id,9);assert.deepEqual(visible[0].variants,{512:9});assert.equal(variantId(visible[0],'256'),null);assert.equal(variantId(visible[1],'1T'),48);
assert.equal(visibleGroups(original,[{id:8,hidden:true},{id:9,hidden:true},{id:48,hidden:true}]).length,0);
assert.equal(original[0].id,8);assert.equal(sellingLabel('incoming'),'입고 예정');
console.log('Catalog selling status and capacity checks passed.');
