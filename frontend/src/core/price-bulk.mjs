import {compilePriceDrafts,PRICE_FIELDS,TEXT_FIELDS,buildPriceRows} from './price-grid.mjs';
import {parseAmount} from './quote-core.mjs';

/** 선택한 키에만 변경을 적용하고 전체 계산·검증을 통과한 초안 사본을 반환한다. */
export function prepareBulkPrice(config,drafts,products,keys,operation) {
 const current=compilePriceDrafts(config,drafts,products);
 if(current.errors.length)throw Error('현재 입력 오류를 먼저 해결한 뒤 일괄 수정하세요.');
 const rows=buildPriceRows(products,current.config),known=new Map(rows.map(row=>[row.key,row]));
 const selected=[...new Set(keys)].map(key=>known.get(key));
 if(!selected.length||selected.some(row=>!row))throw Error('수정할 행을 선택하세요.');
 const next=structuredClone(drafts||{});let skipped=0;
 const fields=[...PRICE_FIELDS,...TEXT_FIELDS];
 if(!['set','add','copy'].includes(operation.mode))throw Error('수정 방식을 확인하세요.');
 let source;
 if(operation.mode==='copy'){source=current.config.offers[operation.sourceKey];if(!source)throw Error('복사할 등록 조건을 선택하세요.');}
 else if(!fields.includes(operation.field))throw Error('수정할 항목을 확인하세요.');
 for(const row of selected){
  if(operation.mode!=='copy'&&(operation.field==='common'&&row.discount==='choice'||operation.field==='discountBase'&&row.discount!=='choice')){skipped++;continue;}
  const patch={};
  if(operation.mode==='copy'){
   // 다른 모델의 출고가·지원금은 유지하고 요금제/유지조건만 복사한다.
   for(const field of [...TEXT_FIELDS,'planFee','addonFee','addonMonths'])if(Object.hasOwn(source,field))patch[field]=source[field]??'';
   if(row.discount==='choice'&&Object.hasOwn(source,'discountBase'))patch.discountBase=source.discountBase??'';
   patch.addonSchedule=source.addonSchedule?structuredClone(source.addonSchedule):null;
  }else if(operation.mode==='add'){
   if(!PRICE_FIELDS.includes(operation.field))throw Error('증감은 금액·개월 항목에만 사용할 수 있습니다.');
   const amount=parseAmount(operation.value,true),before=current.config.offers[row.key]?.[operation.field];
   if(amount===null||!Number.isSafeInteger(before))throw Error('증감할 금액이 미등록된 행이 있습니다. 값 지정으로 입력하거나 해당 행을 제외하세요.');
   const after=before+amount;if(!Number.isSafeInteger(after))throw Error('증감한 값의 범위를 확인하세요.');patch[operation.field]=after;
  }else patch[operation.field]=operation.value;
  next[row.key]={...next[row.key],...patch};
 }
 if(skipped===selected.length)throw Error('선택한 할인 방식에는 해당 항목이 적용되지 않습니다.');
 const result=compilePriceDrafts(config,next,products);
 if(result.errors.length)throw Error(`일괄 수정 후 오류: ${result.errors[0].message}`);
 const changed=result.changes.filter(change=>keys.includes(change.key));
 return {drafts:next,changes:changed,skipped,targetCount:selected.length-skipped};
}
