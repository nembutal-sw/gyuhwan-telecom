// 원본 고객 페이지의 모델 목록·견적·상담 UI다. sync-main-page.mjs가 재수집 후 연결 코드를 덧붙일 수 있다.
// 관리자 초안은 content-data.js의 화면용 객체로만 들어오며 실제 저장은 관리자 API에서 처리한다.
import {loadContentResource,loadPublicConfig,loadedContentResource,isAdminPreview} from './content-data.js';
import {rankPolicyBest} from './policy-best.mjs';
import {excludedAddonModels,addonGuideText,refreshAdminAddonGuide} from './store-addon-policy.mjs';
import {carrierPolicies,policyText} from './purchase-policy.mjs';
import {createQuoteImages} from './quote-image.mjs';
import {keyFor} from './quote-core.mjs';
import {calculatePurchaseQuote as calculateQuote} from './installment-core.mjs';
import {findGroup,variantId,visibleGroups,sellingLabel} from './catalog-core.mjs';
import {trackModelView,setInterestModel} from './interest-tracking.js';
const $=s=>document.querySelector(s),$$=s=>document.querySelectorAll(s),esc=v=>String(v??'').replace(/\b\d{1,3}(?:,\d{3})+\b/g,n=>n.replaceAll(',','.')).replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const subsidyLabel=()=>state.carrier==='LG U+'?'유통망 지원금':'공통 지원금';
const extraLabel=()=>'추가지원금';
const won=n=>n===null||n===undefined?'확인 필요':`${n.toLocaleString('ko-KR').replaceAll(',','.')}원`;
let addonCatalog=null;
// 목록·견적 선택 상태를 한곳에 두고 공개 가격표와 모델 자료가 준비되면 동적 화면을 그린다.
const state={addonMode:'registered',addonIds:[],addonMonths:24,groups:[],group:null,capacity:'256',carrier:'SKT',brand:'전체',query:'',selling:'전체',limit:6,products:[],config:{offers:{},colors:{}},ready:false,selected:null,type:'번호이동',discount:'common',months:24,color:'',updated:null};
let verifiedColors={};fetch('/model-colors.json').then(r=>{if(!r.ok)throw Error();return r.json();}).then(v=>{verifiedColors=v;if(state.selected){renderColors();renderQuote();}}).catch(()=>{});
let verifiedRetail=null;fetch('/verified-retail.json').then(r=>{if(!r.ok)throw Error();return r.json();}).then(v=>{verifiedRetail=v;if(state.selected)renderQuote();}).catch(()=>{if(state.selected)renderQuote();});
let references={};fetch('/official-prices.json').then(r=>r.json()).then(v=>{references=v;if(state.selected)renderQuote();});
const imgSrc=value=>!value?null:/^(?:https?:|data:|blob:|\/)/.test(value)?value:'/'+value;
const photo=p=>imgSrc(p.carrierImages?.[state.carrier]||p.image);
const dialog=$('#purchase-dialog');let requestId=0;
// 늦게 끝난 이전 가격표 응답은 requestId로 버려 최신 선택 조건을 덮지 않는다.
async function loadConfig(){const id=++requestId;state.ready=false;try{const data=await loadPublicConfig();if(id!==requestId)return;state.config=data.config;state.updated=data.updatedAt;state.ready=true;$('#admin-link').hidden=true;specialPrices();if(state.groups.length)renderProducts();}catch{if(id===requestId){state.ready=false;$('#selection-label').textContent='가격 정보 연결을 확인해 주세요.';}}if(state.selected&&dialog.open)renderQuote();}
const upcomingDuo=await loadContentResource('upcoming-duo.json').catch(()=>({"id":"iphone-duo-upcoming","name":"아이폰 듀오","brand":"Apple","network":"준비중","code":"판매 조건 준비중","image":"official/iphone-duo-colors.jpg","upcoming":true}));
function brandLogo(brand){const file=brand==='삼성'?'samsung':brand==='Apple'?'apple':null;return file?`<img class="card-brand-logo card-brand-${file}" src="${esc(document.querySelector('.manufacturer-'+file)?.getAttribute('src')||'/manufacturer-'+file+'-user.png?v=20261009a')}" alt="${esc(brand)}">`:`<span>${esc(brand)}</span>`;}
function filtered(){reflectFeaturedStatuses();const q=state.query.replace(/\s+/g,'').toLowerCase();return allModelGroups().map(g=>({...g,...state.products.find(p=>p.id===g.id),name:g.name,isNew:g.isNew,inquiryOnly:g.inquiryOnly})).filter(p=>(state.selling==='전체'||(p.sellingStatus||'incoming')===state.selling)&&(state.brand==='전체'||p.brand===state.brand||(state.brand==='기타'&&!['삼성','Apple'].includes(p.brand)))&&`${p.name}${p.code||''}`.replace(/\s+/g,'').toLowerCase().includes(q));}
// 추천·특가 구역도 같은 숨김·판매 상태를 사용해 고객이 판매 종료를 놓치지 않도록 한다.
function reflectFeaturedStatuses(){for(const button of $$('[data-product]')){const card=button.closest('.best-card,.special-offer'),product=state.products.find(p=>p.id===Number(button.dataset.product));if(!card||!product)continue;card.hidden=product.hidden&&!isAdminPreview;let badge=card.querySelector('.featured-model-status');if(!badge){badge=document.createElement('p');button.insertAdjacentElement('beforebegin',badge);}badge.className=`featured-model-status model-availability status-${product.sellingStatus||'selling'}`;badge.textContent=sellingLabel(product.sellingStatus);}}
function policyBestBadge(id){
 if(!state.ready)return '';
 const best=rankPolicyBest(state.groups,state.config.offers).find(row=>row.id===id&&row.rank<=3);
 if(!best)return '';
 return `<span class="policy-hot-badge" aria-label="추가지원금 추천 ${esc(best.carriers.map(c=>c.name).join('·'))}"><strong>HOT</strong></span>`;
}
function renderProducts(){const list=filtered(),items=list.slice(0,state.limit);$('#result-count').textContent=`전체 ${list.length}개 모델 · ${items.length}개 표시`;$('#catalog-empty').hidden=!!list.length;$('#show-more').hidden=items.length>=list.length;$('#show-more').textContent=`모델 더 보기 (${items.length} / ${list.length})`;$('#product-grid').innerHTML=items.map(p=>`<article class="product-card"><div class="product-card-top">${brandLogo(p.brand)}<div class="product-card-tags">${policyBestBadge(p.id)}<span class="product-network ${p.upcoming?'network-upcoming':p.network==='5G'?'network-5g':'network-lte'}">${esc(p.network)}</span></div></div>${photo(p)?`<div class="product-image"><img src="${esc(photo(p))}" alt="${esc(p.imageAlt||p.name)}" loading="lazy"></div>`:`<div class="product-image product-image-placeholder"><strong>${esc(p.name)}</strong><span>제품 이미지 준비 중</span></div>`}<h3>${p.upcoming?'<span class="upcoming-tag">준비중</span> ':p.isNew?'<span class="new-tag">NEW</span> ':''}${esc(p.name)}</h3>${p.isNew?'<p class="price-rise">NEW 모델 출고가 적용</p>':''}<p class="model-availability status-${esc(p.sellingStatus||'incoming')}">${sellingLabel(p.sellingStatus||'incoming')}</p><p class="product-code">${esc(p.carrierCodes?.[state.carrier]||p.code)}</p><p class="card-quote-note">${p.upcoming?'가격·지원금·구매 조건 준비중':'모델 선택 후 통신사별 견적 확인'}</p><button class="button" ${p.upcoming?'data-upcoming-duo':`data-product="${p.id}"`}>${['sold_out','incoming','discontinued'].includes(p.sellingStatus)?'조건·입고 문의':'견적보기'}</button></article>`).join('');}
function selectCarrier(){$('#selection-label').textContent='모델별 휴대폰 견적';}
function quoteId(){return variantId(state.group,state.capacity);}
// 모델 ID·통신사·가입 유형·할인 방식의 조합으로 가격표 항목을 찾는다. 미등록 항목은 빈 객체다.
function offer(){const id=quoteId();return state.ready&&id!==null?state.config.offers[keyFor(id,state.carrier,state.type,state.discount)]||{}:{};}
// 공식 확인 출고가와 다르거나 확인 자료가 없으면 출고가를 null로 표시해 임의 계산을 피한다.
function effectiveOffer(){const o=offer(),v=verifiedRetail?.[`${quoteId()}|${state.carrier}`];return verifiedRetail===null||(v&&o.retail!=null&&o.retail!==v.retail)?{...o,retail:null}:o;}
function renderAddons(){
 const o=offer(),excluded=excludedAddonModels.includes(quoteId()??state.selected?.id);
 $('#addon-section-heading').closest('fieldset').hidden=false;
 $('#addon-section-heading').innerHTML=`7. <span class="quote-carrier-logo ${state.carrier==='KT'?'is-kt':state.carrier==='LG U+'?'is-lg':'is-sk'}"><img src="${carrierLogo(state.carrier)}" alt="${esc(state.carrier)}"></span> 부가서비스`;
 const lines=(o.addonDetails||'상품명·월 요금·유지 조건은 매장 등록 후 안내됩니다.').split('\n').filter(Boolean);
 const noteIndex=lines.findIndex(line=>line.startsWith('M은')||line.startsWith('유지기간 이후')||line.startsWith('부가서비스 가입 및 유지 조건은'));
 const products=noteIndex<0?lines:lines.slice(0,noteIndex);
 $('#addon-products').innerHTML=`<div class="quote-addon-list"><h4>적용 부가서비스</h4>${excluded?'<p>이 모델은 부가서비스를 적용하지 않습니다.</p>':products.map(line=>`<p class="quote-addon-item">${esc(officialAddonLine(line))}</p>`).join('')}</div><div class="quote-addon-totals"><div class="amount-row"><span>월 부가서비스 합계</span><strong>${won(excluded?0:o.addonFee)}</strong></div><div class="amount-row"><span>예상 청구기간</span><strong>${excluded?'청구 없음':o.addonMonths==null?'확인 필요':o.addonMonths+'개월 (최대)'}</strong></div></div>${excluded?'':`<p class="field-hint">${esc(addonGuideText)}</p><p class="field-hint">보험료는 안내 기준이며 단말기·보장 조건에 따라 달라질 수 있습니다.</p>`}`;
}

function officialAddonLine(line){const a=addonCatalog?.carriers?.[state.carrier]?.find(a=>line.startsWith(a.name+' ·'));return a&&a.officialName?a.officialName+line.slice(a.name.length):line;}
let addonReturnNotice=await loadContentResource('purchase-notices.json').then(v=>v.addonReturnNotice).catch(()=>'유지기간 내 해지 및 미유치 시, 판매점지원금 일부를 반환해야 해요.');
function renderAddonNotice(o,id,carrier,catalog,paragraphs){
 if(excludedAddonModels.includes(Number(id)))return '<p>이 모델은 부가서비스를 적용하지 않습니다. 가입 및 청구 없음.</p>';
 const items=catalog?.carriers?.[carrier]||[];
 const standardFee=items.reduce((n,a)=>n+a.fee,0);
 const matched=items.length&&o.addonFee===standardFee&&items.every(a=>(o.addonDetails||'').includes(a.name));
 const content=matched?items.map(a=>`<article class="addon-notice-card"><div class="addon-notice-title"><h4>${esc(a.officialName||a.name)}</h4><span>${esc(a.period)} 유지 · 매장 기준</span></div><dl class="notice-facts"><div><dt>${a.insurance?'월 보험·서비스료 · 매장 예상':'월 이용요금'}</dt><dd>${won(a.fee)}${a.insurance?' (상품별 상이)':''}</dd></div><div><dt>유지기간</dt><dd>${esc(a.period)} · 개통 시 해지 가능일 확인</dd></div></dl><p>${esc(a.description)}</p>${a.insurance?`<p class="field-hint">${esc(a.note)}</p>`:''}<a href="${esc(a.source)}" target="_blank" rel="noopener noreferrer">${esc(carrier)} 공식 상품 안내</a>${(a.extraSources||[]).map(x=>` · <a href="${esc(x.url)}" target="_blank" rel="noopener noreferrer">${esc(x.name)} 공식 안내</a>`).join('')}</article>`).join(''):paragraphs(o.addonDetails?.split('\n').filter(line=>!line.startsWith('M은')&&!line.startsWith('유지기간 이후')&&!line.startsWith('부가서비스 가입 및 유지 조건은')).map(officialAddonLine).join('\n'),'서비스명·가입 여부·월 요금·유지기간은 개통 시 확인하세요.')+items.map(a=>`<a href="${esc(a.source)}" target="_blank" rel="noopener noreferrer">${esc(a.officialName||a.name)} 공식 안내</a>`).join(' · ');
 return content+`<dl class="notice-facts"><div><dt>부가서비스 월 합계 · 견적 기준</dt><dd>${won(o.addonFee)}</dd></div><div><dt>계산에 반영한 청구 기간</dt><dd>${o.addonMonths==null?'확인 필요':o.addonMonths+'개월 (최대)'}</dd></div></dl><p class="field-hint">${esc(addonGuideText)}</p><p class="addon-return-notice">${esc(addonReturnNotice)}</p><p class="field-hint">개통 전 안내받고 동의한 판매점 지원금 조건에 한해 적용합니다. 반환 대상·금액·예외는 판매점 약정에서 확인하세요. 통신사 위약금과는 별도입니다.</p>`;
}

const carrierLogo=c=>document.querySelector(c==='SKT'?'.net-skt-logo':c==='KT'?'.net-kt-mark img':'.tv-overview .lgu-mark img')?.getAttribute('src')||(c==='SKT'?'/carrier-skt-official.png':c==='KT'?'/carrier-kt-official.png':'/carrier-lgu-transparent.png');
loadContentResource('addons.json').then(data=>{addonCatalog=data;$('#addon-guide').innerHTML=`<div class="addon-table-wrap"><table class="store-addon-table"><thead><tr><th>통신사</th><th>부가서비스명</th><th>월 이용요금</th><th>유지기간</th></tr></thead><tbody>${Object.entries(data.carriers).map(([carrier,items])=>items.map((a,i)=>`<tr>${i===0?`<th rowspan="${items.length}">${carrier==='KT'?'<span class="kt-addon-mark">':carrier==='LG U+'?'<span class="lgu-mark">':''}<img class="addon-logo" src="${carrierLogo(carrier)}" alt="${esc(carrier)}">${carrier==='KT'||carrier==='LG U+'?'</span>':''}<span class="sr-only">${esc(carrier)}</span></th>`:''}<td>${esc(a.officialName||a.name)}</td><td>${won(a.fee)}${a.insurance?' (매장 예상)':a.from?'부터':''}</td><td>${esc(a.period)}</td></tr>`).join('')).join('')}</tbody></table></div><p class="field-hint">${esc(addonGuideText)}<br>보험료는 안내 기준이며 단말기·보장 조건에 따라 달라질 수 있습니다.</p>`;renderAddons();if(state.selected)renderQuote();}).catch(()=>{$('#addon-guide').textContent='상품 정보를 불러오지 못했습니다. 매장에 문의해 주세요.';renderAddons();});
function specialPrices(){const o=state.config.offers[keyFor(14,'SKT','기기변경','common')]||{};$('#special-prices').innerHTML=`<div class="amount-row"><span>출고가</span><strong>${won(o.retail)}</strong></div><div class="amount-row"><span>공통 지원금</span><strong>${won(o.common)}</strong></div><div class="amount-row"><span>추가지원금</span><strong>${o.supportInquiry?'문의':won(o.support)}</strong></div><small>SKT · 기기변경 · 공통 지원금 기준 / 개통 시 재확인</small>`;}
function renderCapacity(){const capacities=state.group.managed?Object.keys(state.group.variants):state.group.id===23?['128']:['256','512','1T',...Object.keys(state.group.variants).filter(x=>!['256','512','1T'].includes(x))];$('#capacity-options').innerHTML=capacities.map(v=>`<label><input type="radio" name="capacity" value="${esc(v)}" ${state.capacity===v?'checked':''} ${!['1T',...Object.keys(state.group.variants)].includes(v)?'disabled':''}>${v==='1T'?(variantId(state.group,v)===null?'1T (문의)':'1T'):v==='문의'?'모델·용량 문의':v+'GB'}</label>`).join('');$('#capacity-help').textContent=quoteId()===null?'선택한 용량 또는 모델은 문의 전용입니다. 다른 용량의 가격을 대신 계산하지 않습니다.':'용량별 출고가와 지원금으로 계산합니다. 미등록 금액은 매장에 문의해 주세요.';}
function renderColors(){const p=state.selected,info=verifiedColors[p.id],colors=state.config.colors[p.id]?.length?state.config.colors[p.id]:(p.colors||info?.colors||[]),statuses=p.colorStatuses||{};if(!colors.includes(state.color)||['sold_out','discontinued'].includes(statuses[state.color]))state.color='';$('#color-options').innerHTML=colors.map(c=>`<label><input type="radio" name="color" value="${esc(c)}" ${state.color===c?'checked':''} ${['sold_out','discontinued'].includes(statuses[c])?'disabled':''}><img src="${esc(state.config.colorImages?.[p.id]?.[c]||'/color-swatches/'+encodeURIComponent(c)+'.svg')}" alt="" onerror="this.hidden=true"><span>${esc(c)}${statuses[c]&&statuses[c]!=='selling'?' · '+sellingLabel(statuses[c]):''}</span></label>`).join('')||'<p class="field-hint">등록된 색상이 없습니다.</p>';$('#model-color-source').innerHTML=info?`공식 색상명 · 색상칩은 화면용 참고 색상입니다. 실제 제품 색상·재고는 상담 확인. 대표 이미지와 선택 색상은 다를 수 있습니다. <a href="${esc(info.source)}" target="_blank" rel="noopener noreferrer">공식 색상 확인</a>`:'매장에서 등록한 색상·판매 상태입니다. 최종 재고는 상담 시 확인해 주세요.';}
// 계산 함수의 null·issues는 확인이 필요한 가격으로 유지하며 숫자 0으로 보정하지 않는다.
function renderQuote(){document.querySelector('#common-discount-label').textContent=subsidyLabel();renderAddons();const o=effectiveOffer(),q=calculateQuote(o,state.discount,state.months,state.type);const p=state.selected;
 const ref=quoteId()===null?null:references[`${quoteId()}|${state.carrier}`];$('#official-reference').innerHTML=ref?.sourceType==='xeronote'?`<strong>제로노트 조회 · ${esc(ref.date)}</strong><p>${esc(ref.modelCode||'정확한 모델 확인 필요')}<br>출고가 ${won(ref.retail)}<br>${subsidyLabel()} (${esc(state.type)}): ${won(ref.subsidies?.[state.type])}<br>조회 기준: ${esc(ref.planName||'모델 확인 필요')} · ${won(ref.planFee)}</p><p>${esc(ref.note)}${ref.publishedAt?'<br>지원금 공시일 '+esc(ref.publishedAt):''}</p><a href="${esc(ref.source)}" target="_blank" rel="noopener noreferrer">제로노트 조회 출처 (로그인 필요)</a>`:ref?.sourceType==='merchant'?`<strong>매장 제공 SK 지원금 정책표 · ${esc(ref.date)}</strong><p>출고가 ${won(ref.retail)}<br>번호이동 지원금 ${won(ref.newAndMnp)}<br>기기변경 지원금 ${won(ref.change)}</p><p>${esc(ref.note)}</p><p>선택약정은 위 공통 지원금을 차감하지 않습니다.</p>`:ref?`<strong>공식 참고 정보 · ${esc(ref.date)}</strong><p>${ref.retail?'출고가 '+won(ref.retail):'통신사 출고가 확인 필요'}${ref.referenceRetail?'<br>자급제 소비자가 '+won(ref.referenceRetail):''}<br>공통 지원금: ${ref.common==null?'확인 필요':won(ref.common)}</p><p>${esc(ref.note)} · 매장 최종 조건 별도 확인</p><a href="${esc(ref.source)}" target="_blank" rel="noopener noreferrer">공식 모델 정보 ↗</a>${ref.subsidySource?` · <a href="${esc(ref.subsidySource)}" target="_blank" rel="noopener noreferrer">지원금 조회 ↗</a>`:''}`:'<p>이 모델의 공식 출고가·지원금은 확인 중입니다. 미확인 금액은 계산하지 않습니다.</p>';
 const pic=$('#dialog-product img');if(pic)pic.src=state.config.colorImages?.[p.id]?.[state.color]||photo(p);
 $('#quote-selection').textContent=`${state.group.name} · ${state.capacity==='문의'?'용량 문의':state.capacity==='1T'?(quoteId()===null?'1T (문의)':'1T'):state.capacity+'GB'}\n${state.carrier} · ${state.type} · ${state.discount==='common'?subsidyLabel():'선택약정'}\n${state.color||'색상 미선택'} · ${state.months===0?'일시불':'24개월 할부'}\n${o.planName||'요금제 확인 필요'}`;
 const summaryPhoto=$('#quote-summary-photo');summaryPhoto.src=state.config.colorImages?.[p.id]?.[state.color]||photo(p);summaryPhoto.hidden=!photo(p)&&!state.config.colorImages?.[p.id]?.[state.color];
 $('#discount-help').textContent=state.discount==='common'?`출고가에서 등록된 ${subsidyLabel()}과 ${extraLabel()}을 각각 한 번 차감합니다.`:`${subsidyLabel()}은 차감하지 않습니다. 등록된 할인 대상 요금의 25%를 요금에서 할인합니다.`;
 const row=(label,value,big=false)=>`<div class="amount-row ${big?'strong':''} ${/^[−-]/.test(String(value))?'negative-amount':''} ${['휴대폰 월 납부금','휴대폰 납부금'].includes(label)?'device-monthly':''}"><span>${label}</span><strong>${value}</strong></div>`;
 const deviceMonthly=q.deviceSchedule?.[0]??null,serviceMonthly=q.planMonthly!==null&&o.addonFee!=null&&o.addonMonths!=null?q.planMonthly+(o.addonMonths>0?o.addonFee:0):null,totalSupport=o.support!=null&&(state.discount==='choice'||o.common!=null)?o.support+(state.discount==='choice'?0:o.common):null;
 $('#quote-values').innerHTML=`<div class="compact-payment">
 ${row('출고가',won(o.retail))}
 ${row('출고가 할인합계',totalSupport==null?'확인 필요':'−'+won(totalSupport))}
 <div class="compact-discounts">${row('└ 공통지원금',state.discount==='choice'?'미적용':o.common==null?'확인 필요':'−'+won(o.common))}${row('└ 추가지원금',o.supportInquiry?'문의':o.support==null?'확인 필요':'−'+won(o.support))}</div>
 ${row('할부원금',won(q.principal))}
 ${row(state.months===0?'휴대폰 납부금':'휴대폰 월 납부금',won(state.months===0?q.upfront:deviceMonthly),true)}
 <div class="payment-divider"></div>
 ${row('요금제 금액',won(o.planFee))}
 <p class="applied-plan-note">└ ${esc(o.planName||'적용 요금제 확인 필요')} · ${won(o.planFee)}(부가세 포함)</p>
 ${state.discount==='choice'?row('선택약정 25% 할인',q.planDiscount==null?'확인 필요':'−'+won(q.planDiscount)):''}
 ${row('통신요금',won(q.planMonthly),true)}
 ${row('부가서비스 · 첫 달',won(o.addonFee))}
 <div class="compact-grand-total">${row('월 납부금액',q.schedule?won(q.schedule[0].total-q.simFee):'확인 필요',true)}</div>
 <p class="field-hint">첫 달 기준 · 유심비 별도${state.months===0?' · 휴대폰 납부금 별도':''}<br>부가서비스 종료 후에는 해당 요금이 제외됩니다.</p>
 ${row('유심비용 · 첫 달 1회',won(q.simFee))}
 ${row('첫 달 납부 예상 금액 · 유심 포함',q.schedule?won(q.schedule[0].total):'확인 필요')}
 <p class="field-hint interest-summary">${state.months===0?'일시불 · 할부이자 없음':`휴대폰 월 납부금에 연 5.9% 원리금균등 할부이자가 포함되어 있습니다.<br>단말기 총 할부이자: ${won(q.installmentFee)} (24개월 합계 · 별도 가산 없음)<br>1~23회차 휴대폰 월 납부금은 10원 단위로 절사하며, 차액은 마지막 회차에 반영됩니다.`}</p>
 ${o.supportInquiry?'<p class="field-hint">추가지원금은 상담 후 확정됩니다.</p>':''}
 </div>`;

 const checked=verifiedRetail?.[`${quoteId()}|${state.carrier}`];if(checked)$('#official-reference').innerHTML+=`<p>${esc(checked.sourceName||'공식')} 확인 출고가: <strong>${won(checked.retail)}</strong> · ${esc(checked.checkedAt)}<br><a href="${esc(checked.source)}" target="_blank" rel="noopener noreferrer">출고가 조회 출처</a></p>`;const issues=[...q.issues];if(verifiedRetail===null)issues.push('공식 출고가 대조 정보를 불러오는 중이거나 연결이 끊겼습니다. 재시도 전 단말기 금액 계산을 보류합니다.');if(checked&&offer().retail!=null&&offer().retail!==checked.retail)issues.push(`매장 등록 출고가 ${won(offer().retail)}와 공식 확인값 ${won(checked.retail)}가 달라 계산을 보류합니다. 매장 가격을 확인해 주세요.`);if(quoteId()===null)issues.push('이 모델·용량은 문의 전용입니다. 카카오톡으로 재고와 견적을 확인해 주세요.');if(!state.ready)issues.unshift('최신 가격 정보를 불러올 수 없어 계산을 중지했습니다.');else if(!q.schedule)issues.push('가격·요금제·부가서비스·할부 조건 중 미등록 항목이 있어 월 납부액을 계산하지 않았습니다.');$('#quote-warning').textContent=issues.join(' ');
 const paragraphs=(text,fallback)=>`<p>${String(text||fallback).split('\n').map(line=>line.startsWith('공식 상세 안내: https://')?`<a href="${esc(line.slice('공식 상세 안내: '.length).trim())}" target="_blank" rel="noopener noreferrer">요금제 공식 상세 내용 보기</a>`:esc(line)).join('<br>')}</p>`;
 $('#plan-details').innerHTML=`<h4>${esc(o.planName||'요금제 등록 전')}</h4><dl class="notice-facts"><div><dt>월정액 · 부가세 포함</dt><dd>${won(o.planFee)}</dd></div>${state.discount==='choice'?`<div><dt>선택약정 할인 대상 금액</dt><dd>${won(o.discountBase)}</dd></div>`:''}</dl>`+paragraphs(o.planDetails,'데이터 제공량·소진 후 속도·통화·문자·테더링·약정기간·요금제 유지 조건은 매장 확인 후 안내합니다. 등록되지 않은 혜택을 제공하는 것으로 해석하지 않습니다.');
 $('#addon-details').innerHTML=renderAddonNotice(o,quoteId()??state.selected?.id,state.carrier,addonCatalog,paragraphs);
 const policy=carrierPolicies[state.carrier];
 $('#contract-details').innerHTML=`<dl class="notice-facts"><div><dt>${esc(state.carrier)} · ${subsidyLabel()}</dt><dd>24개월 약정 기준</dd></div><div><dt>선택약정 25%</dt><dd>12개월 또는 24개월 · 계약 시 선택</dd></div><div><dt>단말기 결제</dt><dd>${state.months===0?'일시불':'24개월 할부'} · 약정기간과 별도</dd></div></dl><p class="field-hint">24개월 납부 미리보기는 약정기간 선택 또는 가입 완료를 의미하지 않습니다.</p><a href="${policy.choiceSource}" target="_blank" rel="noopener noreferrer">${esc(state.carrier)} 선택약정 공식 안내</a>`;
 $('#penalty-details').innerHTML=policyText(state.carrier,state.discount).split('\n').map(t=>`<p>${esc(t)}</p>`).join('')+`<a href="${policy.source}" target="_blank" rel="noopener noreferrer">${esc(state.carrier)} 공식 유지조건 확인</a>`+paragraphs(o.penaltyDetails,'개별 위약금은 개통 전 계약서에서 확인해 주세요.')+paragraphs(o.terms,'매장 지원금 유지 조건은 별도 확인해 주세요.');

 $('#copy-status').textContent='';$('#copy-fallback').hidden=true;
}
function allModelGroups(){return [upcomingDuo,...visibleGroups(state.groups,state.products)];}
function openUpcomingDuo(){if(dialog.open)dialog.close();$('#duo-model-switch').innerHTML=allModelGroups().map(g=>`<option value="${g.id}" ${g.upcoming?'selected':''}>${esc(g.name)}${g.upcoming?' · 준비중':''}</option>`).join('');$('#duo-upcoming-dialog').showModal();}
function changeModel(value){if(value===upcomingDuo.id){openUpcomingDuo();return;}if($('#duo-upcoming-dialog').open)$('#duo-upcoming-dialog').close();openProduct(Number(value));}
// 카드 선택을 현재 견적 조건으로 옮기고 가격 정보가 돌아오면 같은 대화창을 다시 갱신한다.
function openProduct(id){const product=state.products.find(p=>p.id===id);if(!product||product.hidden&&!isAdminPreview)return;$('#model-switch').innerHTML=allModelGroups().map(g=>`<option value="${g.id}" ${findGroup(state.groups,id)?.id===g.id?'selected':''}>${esc(g.name)}</option>`).join('')+(id===14?'<option value="14" selected>갤럭시 S25 256GB · 특가</option>':'');state.addonMode='registered';state.addonIds=[];state.addonMonths=24;renderAddons();state.selected=product;state.group=findGroup(visibleGroups(state.groups,state.products),id)||{id,name:state.selected.name,variants:{[product.capacity||'256']:id},managed:true};state.capacity=Object.entries(state.group.variants).find(([,v])=>v===id)?.[0]||'256';setInterestModel(id);trackModelView(id);if(id===14){state.carrier='SKT';state.type='기기변경';state.discount='common';}state.color='';state.ready=false;renderDialogProduct();for(const name of ['carrier','type','discount','months'])$$(`input[name="${name}"]`).forEach(i=>i.checked=i.value===String(state[name]));renderCapacity();renderColors();renderQuote();if(!dialog.open)dialog.showModal();document.body.classList.add('dialog-open');loadConfig().then(()=>{renderColors();renderQuote();});$('#model-switch').focus();}
function renderDialogProduct(){const p=state.selected;$('#dialog-product').innerHTML=`${photo(p)?`<img src="${esc(photo(p))}" alt="${esc(p.imageAlt||p.name)}">`:''}<div><h3>${esc(state.group.name)}</h3><p class="model-availability status-${esc(p.sellingStatus||'selling')}">${sellingLabel(p.sellingStatus)}${p.sellingStatus!=='selling'?' · 최종 취급·입고는 상담 확인':''}</p><p class="product-code">${quoteId()===null?'모델·용량 확인 후 견적 안내':esc(p.carrierCodes?.[state.carrier]||p.code)}</p>${state.group.isNew?'<p class="price-rise">NEW 모델 출고가 적용</p>':''}</div>`;}
function closeDialog(){dialog.close();}
document.addEventListener('click',e=>{if(e.target.closest('[data-upcoming-duo]')){openUpcomingDuo();return;}if(e.target.closest('[data-close-duo]')){$('#duo-upcoming-dialog').close();return;}const c=e.target.closest('[data-carrier]');if(c){state.carrier=c.dataset.carrier;selectCarrier();}const b=e.target.closest('[data-brand]');if(b){state.brand=b.dataset.brand;state.limit=6;$$('[data-brand]').forEach(x=>{const active=x.dataset.brand===state.brand;x.classList.toggle('active',active);x.setAttribute('aria-pressed',String(active));});renderProducts();}const p=e.target.closest('[data-product]');if(p)openProduct(Number(p.dataset.product));});
$('#model-search').addEventListener('input',e=>{state.query=e.target.value;state.limit=6;renderProducts();});$('#reset-search').addEventListener('click',()=>{state.query='';$('#model-search').value='';renderProducts();});$('#show-more').addEventListener('click',()=>{const old=state.limit;state.limit+=6;renderProducts();$$('[data-product]')[old]?.focus();});
// 기존 검색·제조사 메뉴 옆에 판매 상태 필터를 둔다. 원본 콘텐츠 요소의 노드 순서는 바꾸지 않는다.
const sellingFilter=document.createElement('label');sellingFilter.className='catalog-selling-filter';sellingFilter.innerHTML='<span>판매 상태</span><select aria-label="판매 상태로 모델 찾기"><option value="전체">전체</option><option value="selling">판매 중</option><option value="sold_out">품절</option><option value="incoming">입고 예정</option><option value="discontinued">판매 종료</option></select>';$('#model-search').closest('label').parentElement.insertAdjacentElement('afterend',sellingFilter);sellingFilter.querySelector('select').addEventListener('change',e=>{state.selling=e.target.value;state.limit=6;renderProducts();});
dialog.addEventListener('change',e=>{const name=e.target.name;if(name==='capacity'){state.capacity=e.target.value;const id=quoteId();state.selected=state.products.find(p=>p.id===(id??state.group.id));if(id!==null){setInterestModel(id);trackModelView(id);}state.color='';renderCapacity();renderDialogProduct();renderColors();renderQuote();return;}if(['carrier','type','discount','months','color'].includes(name)){state[name]=name==='months'?Number(e.target.value):e.target.value;if(name==='carrier'){state.addonIds=[];renderAddons();renderDialogProduct();renderColors();}selectCarrier();renderQuote();}});$('#dialog-close').addEventListener('click',closeDialog);dialog.addEventListener('close',()=>{document.body.classList.remove('dialog-open'); $(`[data-product="${state.group.id}"]`)?.focus();});
const consultation=$('#consultation-dialog');
// 화면에 표시한 같은 조건으로 복사·JPG 저장용 텍스트를 만든다. 개인정보 입력값은 포함하지 않는다.
function quoteSummary(){const o=effectiveOffer(),q=calculateQuote(o,state.discount,state.months,state.type);return `작성 시각: ${new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',dateStyle:'medium',timeStyle:'short'}).format(new Date())}
${$('#quote-selection').textContent}

출고가: ${won(o.retail)}
${subsidyLabel()}: ${state.discount==='choice'?'미적용':won(o.common)}
${extraLabel()}: ${o.supportInquiry?'문의':won(o.support)}
구매 원금: ${won(q.principal)}
결제 방법: ${state.months===0?'일시불':'24개월 할부'}
단말기 총 할부이자 (월 납부금에 포함): ${state.months===0?'해당 없음':won(q.installmentFee)}${q.feeEstimated?' (예상)':''}
${state.months===24?`휴대폰 월 납부금 (1~23회차): ${won(q.deviceSchedule?.[0])}\n마지막 회차 휴대폰 납부금: ${won(q.deviceSchedule?.[23])}\n10원 단위 절사 차액은 마지막 회차에 반영됩니다.`:''}

요금제: ${o.planName||'등록 전'}
월정액: ${won(o.planFee)}
부가서비스: ${won(o.addonFee)} · 최대 ${o.addonMonths??'확인 필요'}개월
선택약정 할인: ${state.discount==='choice'?won(q.planDiscount):'미적용'}
할인 후 월 통신요금: ${won(q.planMonthly)}
유심비용 · 첫 달 1회: ${won(q.simFee)}
첫 달 예상 총 납부액 · 유심 포함: ${q.schedule?won(q.schedule[0].total):'등록값 확인 필요'}

${q.feeEstimated?'할부이자는 연 5.9% 기준 예상값입니다. ':''}예상 총액은 선택한 전체 구매 조건 기준입니다.
실제 청구액은 개통일과 사용량에 따라 달라질 수 있습니다.`;}

let quoteFiles=[],quoteUrls=[],imageGeneration=0;
function clearQuoteImages(){imageGeneration++;quoteUrls.forEach(u=>URL.revokeObjectURL(u));quoteUrls=[];quoteFiles=[];$('#quote-image-results').replaceChildren();}
let channelUrl=document.querySelector('[data-channel-link]')?.getAttribute('href')||null;
function applyChannel(){
 /* Static contact links and labels are applied by site-content.js. */
 $('#channel-pending').hidden=!!channelUrl;$$('[data-channel-pending]').forEach(p=>p.hidden=!!channelUrl);
}
applyChannel();
$('#copy-inquiry').addEventListener('click',()=>{clearQuoteImages();$('#consultation-text').value=quoteSummary();$('#consultation-text').hidden=true;$('#consultation-status').textContent='견적을 복사하거나 JPG로 저장하세요.';applyChannel();consultation.showModal();});
$('#consultation-close').addEventListener('click',()=>consultation.close());
consultation.addEventListener('close',()=>{clearQuoteImages();$('#consultation-text').value='';});
$('#quote-copy-text').addEventListener('click',async()=>{const value=quoteSummary();try{await navigator.clipboard.writeText(value);$('#consultation-status').textContent='견적을 복사했습니다. 채팅에 직접 붙여넣어 주세요.';}catch{$('#consultation-text').value=value;$('#consultation-text').hidden=false;$('#consultation-text').focus();$('#consultation-text').select();$('#consultation-status').textContent='아래 견적 내용을 직접 복사해 주세요.';}});
$('#quote-save-jpg').addEventListener('click',async e=>{clearQuoteImages();const generation=imageGeneration,button=e.currentTarget;button.disabled=true;$('#consultation-status').textContent='JPG 견적서를 만드는 중…';try{const files=await createQuoteImages(quoteSummary());if(generation!==imageGeneration||!consultation.open)return;quoteFiles=files;for(const file of files){const url=URL.createObjectURL(file);quoteUrls.push(url);const article=document.createElement('div'),img=document.createElement('img'),a=document.createElement('a');img.src=url;img.alt='이름·전화번호 없는 상담 견적서';a.href=url;a.download=file.name;a.textContent='JPG 견적서 저장';a.className='button outline';article.append(img,a);$('#quote-image-results').append(article);}$('#consultation-status').textContent='아래 JPG 저장 버튼으로 저장한 뒤 카카오톡 채팅에 직접 첨부해 주세요.';}catch(err){if(generation===imageGeneration)$('#consultation-status').textContent=err.message||'이미지를 만들지 못했습니다. 견적 복사를 이용해 주세요.';}finally{button.disabled=false;}});
document.addEventListener('click',e=>{const a=e.target.closest('[data-channel-link]');if(a&&!channelUrl){e.preventDefault();if(consultation.open)$('#consultation-status').textContent='채널 연결 준비 중입니다. 전화상담 010-8531-8058로 문의해 주세요.';else $('#contact').scrollIntoView({behavior:'smooth',block:'start'});}});

const member=$('#member-dialog');$('#member-open').addEventListener('click',()=>member.showModal());$('#member-close').addEventListener('click',()=>member.close());
// 편집용 미리보기는 이 Promise가 끝난 뒤 동적 카드와 필드 연결을 만든다.
export const publicDataReady=Promise.all([loadContentResource('catalog.json'),loadContentResource('products.json'),loadConfig()]).then(([groups,products])=>{state.groups=groups;state.products=products;selectCarrier();renderProducts();}).catch(()=>{$('#result-count').textContent='모델 정보를 불러오지 못했습니다. 새로고침해 주세요.';});

$('#model-switch').addEventListener('change',e=>changeModel(e.target.value));
$('#duo-model-switch').addEventListener('change',e=>changeModel(e.target.value));

// 고객이 견적창을 열어 둔 동안 저장된 새 가격표를 주기적으로 다시 읽는다.
let refreshInFlight=false;
async function refreshVisibleQuote(){if(document.hidden||!dialog.open||refreshInFlight)return;refreshInFlight=true;try{await loadConfig();renderColors();}finally{refreshInFlight=false;}}
setInterval(refreshVisibleQuote,15000);
window.addEventListener('focus',refreshVisibleQuote);
document.addEventListener('visibilitychange',refreshVisibleQuote);
if('BroadcastChannel' in window){const updates=new BroadcastChannel('gyuhwan-quote-updates');updates.onmessage=refreshVisibleQuote;}

// 아래 미리보기 연결 함수는 sync-main-page.mjs가 원본 모듈에 덧붙인다. 일반 고객 화면에서는 실행하지 않는다.
export function applyAdminPreviewSelection(selection) {
  if (!isAdminPreview || !selection || !Number.isInteger(Number(selection.model))) return false;
  const model = Number(selection.model);
  if (!state.products.some(product => product.id === model)) return false;
  openProduct(model);
  if (['SKT', 'KT', 'LG U+'].includes(selection.carrier)) state.carrier = selection.carrier;
  if (['번호이동', '기기변경', '신규가입'].includes(selection.type)) state.type = selection.type;
  if (['common', 'choice'].includes(selection.discount)) state.discount = selection.discount;
  for (const name of ['carrier', 'type', 'discount', 'months']) $$(`input[name="${name}"]`).forEach(input => { input.checked = input.value === String(state[name]); });
  renderCapacity();
  renderAddons();
  renderDialogProduct();
  renderColors();
  selectCarrier();
  renderQuote();
  return true;
}

export function adminPreviewContext() {
  // 현재 대화창 선택값을 관리자 미리보기의 동적 필드 매핑에 제공한다.
  return { carrier: state.carrier, productId: state.selected?.id, groupId: state.group?.id, discount: state.discount };
}

export async function refreshAdminPreview() {
  // 초안 자료 객체의 참조를 유지한 채 부가서비스·색상·가격·카드를 다시 그린다.
  if (!isAdminPreview) return;
  refreshAdminAddonGuide();
  addonReturnNotice = loadedContentResource('purchase-notices.json')?.addonReturnNotice ?? addonReturnNotice;
  addonCatalog = loadedContentResource('addons.json') || addonCatalog;
  if (addonCatalog) {
    $('#addon-guide').innerHTML = `<div class="addon-table-wrap"><table class="store-addon-table"><thead><tr><th>통신사</th><th>부가서비스명</th><th>월 이용요금</th><th>유지기간</th></tr></thead><tbody>${Object.entries(addonCatalog.carriers).map(([carrier,items])=>items.map((a,i)=>`<tr>${i===0?`<th rowspan="${items.length}">${carrier==='KT'?'<span class="kt-addon-mark">':carrier==='LG U+'?'<span class="lgu-mark">':''}<img class="addon-logo" src="${carrierLogo(carrier)}" alt="${esc(carrier)}">${carrier==='KT'||carrier==='LG U+'?'</span>':''}<span class="sr-only">${esc(carrier)}</span></th>`:''}<td>${esc(a.officialName||a.name)}</td><td>${won(a.fee)}${a.insurance?' (매장 예상)':a.from?'부터':''}</td><td>${esc(a.period)}</td></tr>`).join('')).join('')}</tbody></table></div><p class="field-hint">${esc(addonGuideText)}<br>보험료는 안내 기준이며 단말기·보장 조건에 따라 달라질 수 있습니다.</p>`;
  }
  await loadConfig();
  if (state.selected) {
    state.group = findGroup(state.groups, state.selected.id) || state.group;
    renderDialogProduct(); renderAddons(); renderColors(); renderQuote();
  }
  renderProducts();
}
