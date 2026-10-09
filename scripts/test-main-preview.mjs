// 실제 편집 스키마와 DOM 바인딩을 대조하고, 입력 반영·원복·링크 이동 차단을 검사한다.
// VM의 최소 DOM 대역을 사용해 서버 저장 없이 관리자 미리보기의 자료 흐름을 검증한다.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import { productFieldPointers, internetFieldPointers, pointerPart, collectDynamicBindings, guardPreviewNavigation } from '../frontend/public/preview-bindings.js';

const readJSON = async path => JSON.parse(await readFile(new URL(path, import.meta.url), 'utf8'));
const seed = await readJSON('../backend/src/main/resources/site-content-seed.json');
const products = await readJSON('../frontend/public/products.json');
const catalog = await readJSON('../frontend/public/catalog.json');
const schema = new Set(seed.sections.flatMap(section => section.fields.filter(field => field.resource).map(field => `${field.resource}:${field.pointer}`)));
const hasPointer = (resource, pointer) => assert(schema.has(`${resource}:${pointer}`), `No schema field for ${resource}:${pointer}`);

let productMappings = 0;
for (const group of catalog) for (const id of Object.values(group.variants).filter(id => id !== null)) for (const carrier of ['SKT','KT','LG U+']) {
  const pointers = productFieldPointers(products, catalog, id, carrier);
  assert(pointers);
  hasPointer('catalog.json', pointers.title);
  for (const pointer of [...pointers.image, ...pointers.code]) hasPointer('products.json', pointer);
  productMappings++;
}
let internetMappings = 0;
for (const carrier of ['SKT','KT','LG U+']) for (const speed of [0,1,2]) for (const selections of [[0],[1],[1,2],[1,2,3]]) {
  const pointers = internetFieldPointers({carrier,speed,selections});
  hasPointer('internet-plans.json', pointers.internet);
  for (const pointer of pointers.tv.flat()) hasPointer('internet-plans.json', pointer);
  for (const pointer of [pointers.installationInternet,...pointers.installationTV]) hasPointer('installation-rules.json', pointer);
  internetMappings++;
}
assert.equal(pointerPart('LG U+'), 'LG U+');
assert.equal(pointerPart('a/b~c'), 'a~1b~0c');

// Check the actual HTML's direct child-node positions, including BR and nested
// STRONG nodes. Overlay labels must not be inserted into these source elements.
const html = await readFile(new URL('../frontend/public/site.html', import.meta.url), 'utf8');
const nodes = new Map(), root = {children:[]}, stack = [root];
const voids = new Set('area base br col embed hr img input link meta param source track wbr'.split(' '));
const decode = value => value.replace(/&(?:amp|lt|gt|quot|apos|#39|#x[\da-f]+|#\d+);/gi, entity => {
  const key = entity.slice(1,-1).toLowerCase();
  if (key.startsWith('#x')) return String.fromCodePoint(parseInt(key.slice(2),16));
  if (key.startsWith('#')) return String.fromCodePoint(Number(key.slice(1)));
  return {amp:'&',lt:'<',gt:'>',quot:'"',apos:"'"}[key];
});
for (const token of html.match(/<!--[\s\S]*?-->|<![^>]*>|<[^>]*>|[^<]+/g) || []) {
  if (/^<!/.test(token)) { if(token.startsWith('<!--')) stack.at(-1).children.push({type:8}); continue; }
  if (token.startsWith('</')) { const tag = /^<\/([^\s>]+)/.exec(token)?.[1].toLowerCase(); while(stack.length>1){if(stack.pop().tag===tag)break;} continue; }
  if (token.startsWith('<')) {
    const tag = /^<([^\s/>]+)/.exec(token)?.[1].toLowerCase(); if(!tag)continue;
    const attrs = Object.fromEntries([...token.matchAll(/([\w:-]+)\s*=\s*["']([^"']*)["']/g)].map(match=>[match[1],decode(match[2])]));
    const element = {type:1,tag,attrs,children:[]}; stack.at(-1).children.push(element);
    if(attrs['data-content-node'])nodes.set(attrs['data-content-node'],element);
    if(!voids.has(tag)&&!token.endsWith('/>'))stack.push(element);
  } else stack.at(-1).children.push({type:3,data:decode(token)});
}
let staticMappings = 0;
for (const section of seed.sections) for (const field of section.fields.filter(field=>field.node)) {
  const element = nodes.get(field.node); assert(element, `Missing source node: ${field.id}`);
  if(field.attribute)assert(Object.hasOwn(element.attrs,field.attribute), `Missing attribute: ${field.id}`);
  else {assert.equal(element.children[field.textIndex]?.type,3,`TextNode moved: ${field.id}`);assert.equal(element.children[field.textIndex].data,field.value,`TextNode differs: ${field.id}`);}
  staticMappings++;
}

// Hit testing must prefer the clicked title line before a wrapping link href.
const previewSource = await readFile(new URL('../frontend/public/preview.js',import.meta.url),'utf8');
const resolver = previewSource.slice(previewSource.indexOf('  function fieldsForEvent('),previewSource.indexOf('  function requestEdit('));
class FakeElement {
  constructor(tag,parentElement=null,attrs={}){this.tag=tag;this.parentElement=parentElement;this.attrs=attrs;}
  contains(other){for(let element=other;element;element=element.parentElement)if(element===this)return true;return false;}
  closest(selector){
    for(let element=this;element;element=element.parentElement)for(const part of selector.split(',')){
      const tag=/^[a-z]+/.exec(part)?.[0],id=/#([\w-]+)/.exec(part)?.[1];
      const attributes=[...part.matchAll(/\[([^=\]]+)(?:="([^"]*)")?\]/g)];
      if((!tag||element.tag===tag)&&(!id||element.attrs.id===id)&&attributes.every(([,name,value])=>Object.hasOwn(element.attrs,name)&&(value===undefined||element.attrs[name]===value)))return element;
    }
    return null;
  }
}
const anchor=new FakeElement('a'), container=new FakeElement('div',anchor), title=new FakeElement('h1',container), strong=new FakeElement('strong',title);
const text = (id,top) => ({field:{id},sectionId:'home',textNode:{box:{left:10,right:100,top,bottom:top+20}}});
const first=text('title-first-line',10), second=text('title-second-line',40), nested=text('nested-title',70), href={field:{id:'banner-link',attribute:'href'},sectionId:'home'};
const bindings = new Map([[anchor,[href]],[title,[second,first]],[strong,[nested]]]);
const sandbox={Element:FakeElement,editBindings:bindings,document:{createRange(){let node;return{selectNodeContents(value){node=value;},getClientRects(){return [node.box];}};}}};
vm.createContext(sandbox);vm.runInContext(resolver,sandbox);
assert.deepEqual(Array.from(sandbox.fieldsForEvent({target:title,clientX:20,clientY:15}).bindings,b=>b.field.id),['title-first-line','banner-link']);
assert.deepEqual(Array.from(sandbox.fieldsForEvent({target:strong,clientX:20,clientY:75}).bindings,b=>b.field.id),['nested-title','banner-link']);
assert.deepEqual(Array.from(sandbox.fieldsForEvent({target:title}).bindings,b=>b.field.id),['title-second-line','title-first-line','banner-link']);
const logoLink=new FakeElement('a',null,{href:'#home'}),logo=new FakeElement('img',logoLink);
bindings.set(logo,[{field:{id:'logo-image'},sectionId:'header'}]);
assert.deepEqual(Array.from(sandbox.fieldsForEvent({target:logoLink}).bindings,b=>b.field.id),['logo-image']);

// First-click capture must open the editor before either the anchor default or
// a source-page click handler can navigate. Exercise real static menu bindings
// as well as dial/download links, nested links, and mapped action buttons.
const headerMenu=seed.sections.find(section=>section.id==='header').fields.find(field=>field.node==='content-0004');
const menu=new FakeElement('a',null,{href:'#shop'}),menuIcon=new FakeElement('span',menu);
bindings.set(menu,[{field:headerMenu,sectionId:'header'}]);
let navigationChecks=0;
function guardedClick(target,options={}){
  const result={prevented:false,stopped:false,requests:[],resolutions:0};
  const event={target,preventDefault(){result.prevented=true;},stopImmediatePropagation(){result.stopped=true;}};
  result.handled=guardPreviewNavigation(event,{editMode:true,editingDisabled:false,resolveTarget(next){result.resolutions++;return sandbox.fieldsForEvent(next);},requestEdit(fields,element){result.requests.push({ids:fields.map(binding=>binding.field.id),element});},...options});
  navigationChecks++;
  return result;
}
const menuClick=guardedClick(menuIcon);
assert(menuClick.handled&&menuClick.prevented&&menuClick.stopped);
assert.deepEqual(Array.from(menuClick.requests[0].ids),[headerMenu.id]);
for(const href of ['https://open.kakao.com/o/test','/privacy.html','tel:01085318058','/gyuhwan-site-qr.png']){
  const link=new FakeElement('a',null,{href,...(href.endsWith('.png')?{download:''}:{})});
  bindings.set(link,[{field:{id:`link-${href}`},sectionId:'contact'}]);
  const result=guardedClick(link);
  assert(result.handled&&result.prevented&&result.stopped&&result.requests.length===1);
  const preview=guardedClick(link,{editMode:false});
  assert(!preview.handled&&!preview.prevented&&!preview.stopped&&!preview.resolutions);
}
for(const attrs of [{'data-product':'38','data-content-node':'content-0027'},{'data-net-carrier':'KT','data-content-node':'content-0079'},{id:'member-open'},{id:'copy-inquiry'}]){
  const action=new FakeElement('button',null,attrs);
  bindings.set(action,[{field:{id:`action-${navigationChecks}`},sectionId:'best'}]);
  const result=guardedClick(action);
  assert(result.handled&&result.prevented&&result.stopped&&result.requests.length===1);
}
for(const attrs of [{'data-product':'38'},{'data-brand':'전체'},{'data-carrier':'KT'},{id:'dialog-close'},{id:'show-more'}]){
  const result=guardedClick(new FakeElement('button',null,attrs));
  assert(!result.handled&&!result.prevented&&!result.stopped&&!result.resolutions);
}
const locked=guardedClick(menu,{editingDisabled:true});
assert(locked.prevented&&locked.stopped&&!locked.requests.length&&!locked.resolutions);
const unmapped=guardedClick(new FakeElement('a',null,{href:'https://unmapped.example/'}));
assert(unmapped.prevented&&unmapped.stopped&&!unmapped.requests.length);
const editorLayer=new FakeElement('div',null,{'data-preview-ui':'true'}),editorButton=new FakeElement('a',editorLayer,{href:'#home'});
assert(!guardedClick(editorButton).handled);

// Draft patches mutate the existing browser objects and can be reverted to the
// saved baseline. They must neither mutate fixtures nor send writes/fetch again.
globalThis.location={pathname:'/admin_preview',origin:'http://localhost'};
const resourceFixtures = Object.fromEntries(await Promise.all(['products.json','catalog.json','internet-plans.json','installation-rules.json','purchase-notices.json','addons.json','upcoming-duo.json'].map(async resource=>[resource,await readJSON(`../frontend/public/${resource}`)])));
const element = name => ({name,textContent:'',getAttribute(){return null;},querySelector(selector){return element(`${name} ${selector}`);},querySelectorAll(){return[];}});
const card = element('product-card'), button={dataset:{product:'38'},closest(){return card;}};
const addonRows=Object.values(resourceFixtures['addons.json'].carriers).flat().map((item,index)=>({...element(`addon-${index}`),querySelectorAll(){return [element(`addon-${index}-name`),element(`addon-${index}-fee`),element(`addon-${index}-period`)];}}));
const netRows=Array.from({length:8},(_,index)=>element(`net-row-${index}`));
const dynamicDoc={querySelector:selector=>selector==='#dialog-product'?null:element(selector),querySelectorAll:selector=>({'#product-grid [data-product]':[button],'#addon-guide tbody tr':addonRows,'#net-result > .amount-row':netRows,'#net-result details a':[element('install-source-0'),element('install-source-1')],'#net-sources a':[element('plan-source-0'),element('plan-source-1')]}[selector]||[])};
const dynamicBindings=collectDynamicBindings(dynamicDoc,resource=>resourceFixtures[resource],{carrier:'KT',discount:'common'}, {carrier:'KT',speed:1,selections:[1,2]});
for(const binding of dynamicBindings)for(const pointer of binding.pointers)hasPointer(binding.resource,pointer);
assert(dynamicBindings.find(binding=>binding.element.name==='product-card h3').pointers.includes(`/${catalog.findIndex(group=>group.id===38)}/name`));
assert.equal(dynamicBindings.find(binding=>binding.element.name==='net-row-4 strong').pointers[0],'/KT/internetWithTV');
assert.deepEqual(dynamicBindings.find(binding=>binding.element.name==='net-row-5 strong').pointers,['/KT/tvFirst','/KT/tvAdditional']);
assert(dynamicBindings.some(binding=>binding.element.name==='addon-0-fee'&&binding.pointers[0]==='/carriers/SKT/0/fee'));
const requests=[];
globalThis.fetch=async(url,options={})=>{requests.push({url,options});return {ok:true,json:async()=>structuredClone(url==='/api/public/config'?{config:{offers:{saved:{retail:123}},colors:{}},updatedAt:'saved'}:resourceFixtures[url.split('/').at(-1)])};};
const data=await import('../frontend/public/content-data.js');
data.setPreviewPayload({sections:seed.sections});
const loadedProduct=await data.loadContentResource('products.json'), objectRef=loadedProduct[0], imageRef=objectRef.carrierImages;
const plans=await data.loadContentResource('internet-plans.json'), tvRef=plans.KT.tv[1];
const config=await data.loadPublicConfig();
const draft=structuredClone(seed.sections);
draft.find(section=>section.id==='products-data').fields.find(field=>field.pointer==='/0/code').value='DRAFT-CODE';
draft.find(section=>section.id==='internet-data').fields.find(field=>field.pointer==='/KT/tv/1/1').value='12345';
draft.push({id:'attack',fields:[{resource:'products.json',pointer:'/__proto__/polluted',kind:'text',value:'bad'}]});
const before=requests.length;
data.patchPreviewPayload({sections:draft,config:{offers:{draft:{retail:999}},colors:{}}});
assert.equal(loadedProduct[0],objectRef);assert.equal(objectRef.carrierImages,imageRef);assert.equal(objectRef.code,'DRAFT-CODE');assert.equal(resourceFixtures['products.json'][0].code,products[0].code);
assert.equal(plans.KT.tv[1],tvRef);assert.equal(tvRef[1],12345);assert.equal(config.config.offers.draft.retail,999);assert.equal({}.polluted,undefined);
assert.equal(requests.length,before);assert(requests.every(request=>!request.options.method||request.options.method==='GET'));
data.patchPreviewPayload({sections:seed.sections});assert.equal(objectRef.code,products[0].code);assert.equal(config.config.offers.saved.retail,123);
await assert.rejects(data.loadContentResource('unapproved.json'));
console.log(`Preview checks passed: ${staticMappings} actual static fields, ${productMappings} product/carrier mappings, ${internetMappings} internet selections, ${dynamicBindings.length} dynamic DOM targets, ${navigationChecks} navigation/edit-mode cases, TextNode priority, in-memory patch identity/revert/no writes.`);
