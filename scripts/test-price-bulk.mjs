import {test} from 'node:test';
import assert from 'node:assert/strict';
import {prepareBulkPrice} from '../frontend/src/core/price-bulk.mjs';
import {compilePriceDrafts} from '../frontend/src/core/price-grid.mjs';
const products=[{id:1,name:'첫 모델'},{id:2,name:'둘 모델'}],a='1|SKT|번호이동|common',b='2|SKT|번호이동|common',choice='2|KT|기기변경|choice';
const offer={retail:1000000,common:300000,support:100000,planFee:80000,addonFee:5000,addonMonths:3,planName:'요금제',terms:'유지 조건',installmentFee:30000};
const config={offers:{[a]:{...offer,addonSchedule:Array(24).fill(1000)},[b]:{...offer,retail:1500000},[choice]:{...offer,common:null,discountBase:80000}},colors:{}};
test('bulk delta touches selected keys only, includes hidden selection, and recalculates interest without mutating input',()=>{
 const original=structuredClone(config),drafts={[choice]:{planName:'작성 중'}};
 const result=prepareBulkPrice(config,drafts,products,[a,b],{mode:'add',field:'support',value:'10,000'}),saved=compilePriceDrafts(config,result.drafts,products).config;
 assert.equal(saved.offers[a].support,110000);assert.equal(saved.offers[b].support,110000);assert.equal(saved.offers[choice].planName,'작성 중');assert.notEqual(saved.offers[a].installmentFee,30000);assert.deepEqual(config,original);assert.deepEqual(drafts,{[choice]:{planName:'작성 중'}});
});
test('set recognizes zero/empty and skips an inapplicable discount without silently applying it',()=>{
 let result=prepareBulkPrice(config,{},products,[a,choice],{mode:'set',field:'common',value:'0'});assert.equal(result.skipped,1);assert.equal(result.drafts[a].common,'0');assert.equal(result.drafts[choice],undefined);
 result=prepareBulkPrice(config,{},products,[a],{mode:'set',field:'terms',value:''});assert.equal(compilePriceDrafts(config,result.drafts,products).config.offers[a].terms,'');
 assert.throws(()=>prepareBulkPrice(config,{},products,['1|KT|신규가입|common'],{mode:'add',field:'support',value:'1'}),/미등록/);
});
test('condition copy retains target device prices and copies or removes monthly service schedule explicitly',()=>{
 let result=prepareBulkPrice(config,{},products,[b],{mode:'copy',sourceKey:a});let saved=compilePriceDrafts(config,result.drafts,products).config;
 assert.equal(saved.offers[b].retail,1500000);assert.equal(saved.offers[b].support,100000);assert.deepEqual(saved.offers[b].addonSchedule,Array(24).fill(1000));
 result=prepareBulkPrice(config,{},products,[a],{mode:'copy',sourceKey:b});saved=compilePriceDrafts(config,result.drafts,products).config;assert.equal(Object.hasOwn(saved.offers[a],'addonSchedule'),false);
});
test('invalid group operation cannot produce a partially applicable draft',()=>{
 assert.throws(()=>prepareBulkPrice(config,{},products,[a,b],{mode:'set',field:'addonMonths',value:'25'}),/오류/);
 assert.throws(()=>prepareBulkPrice(config,{},products,[a],{mode:'add',field:'retail',value:'-2000000'}),/오류/);
 assert.throws(()=>prepareBulkPrice(config,{},products,['999|SKT|번호이동|common'],{mode:'set',field:'support',value:'1'}),/선택/);
});
