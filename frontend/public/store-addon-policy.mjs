// 부가서비스 유지 기간을 월별 청구표에 반영한다. 안내 문구는 공개 자료에서 읽고 미리보기 초안이면 다시 갱신한다.
// 자료 연결 부분은 sync-main-page.mjs 재수집 대상이므로 재수집 후 계산과 문구를 함께 확인한다.
import {loadContentResource,loadedContentResource,isAdminPreview} from './content-data.js';
const defaultAddonGuideText='부가서비스 가입 및 유지 조건은 매장 제공 안내표 기준입니다. M은 가입월이며 M+ 뒤의 숫자는 가입월로부터 몇 개월 뒤인지를 뜻합니다. 유지기간 이후 해지를 가정한 예상 견적이며 자동 해지되지 않습니다. 실제 가입일·해지 가능일·일할 요금은 개통 시 확인하세요.';
export let addonGuideText=await loadContentResource('purchase-notices.json').then(v=>v.addonGuideText).catch(()=>defaultAddonGuideText);
export function refreshAdminAddonGuide(){if(isAdminPreview)addonGuideText=loadedContentResource('purchase-notices.json')?.addonGuideText??defaultAddonGuideText;}
export const addonRevision='2026-10-08-kt-essential-l';
// 이 모델들은 부가서비스 가입·청구를 계산하지 않는다.
export const excludedAddonModels=[23,24,47];
export function modelAddonPolicy(id,carrier,catalog){
 if(excludedAddonModels.includes(Number(id)))return {addonFee:0,addonMonths:0,addonSchedule:Array(24).fill(0),addonDetails:'부가서비스 제외 모델 · 가입 및 청구 없음'};
 const items=catalog.carriers[carrier];if(!items)return {};
 // M은 가입월, M+n은 그 뒤 n개월째까지를 뜻하므로 청구 회차로 바꾼다.
 const periods=items.map(a=>a.period.startsWith('M+')?Number(a.period.match(/\d+/)[0])+1:Number(a.period.match(/\d+/)[0]));
 const schedule=Array.from({length:24},(_,i)=>items.reduce((sum,a,j)=>sum+(i<periods[j]?a.fee:0),0));
 const won=n=>n.toLocaleString('ko-KR').replaceAll(',','.');
 return {addonFee:schedule[0],addonMonths:Math.max(...periods),addonSchedule:schedule,addonDetails:items.map(a=>`${a.name} · 월 ${won(a.fee)}원${a.from?'부터':''} · ${a.period}`).join('\n')+'\n'+addonGuideText+'\n보험료는 안내 기준이며 단말기·보장 조건에 따라 달라질 수 있습니다.'};
}
export function refreshAddons(config,catalog){
 // 이전 부가서비스 기준의 가격표만 새 기준으로 보정하고, 이미 갱신된 항목은 유지한다.
 return {...config,addonRevision,offers:Object.fromEntries(Object.entries(config.offers).map(([key,o])=>{const [id,carrier]=key.split('|');return [key,(config.addonRevision===addonRevision||(config.addonRevision==='2026-10-07-image-exclusions'&&carrier!=='KT'))&&!excludedAddonModels.includes(Number(id))?o:{...o,...modelAddonPolicy(id,carrier,catalog)}];}))};
}
