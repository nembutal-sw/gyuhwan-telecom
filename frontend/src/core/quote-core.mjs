// 고객 견적과 관리자 가격 검증이 공유하는 계산 규칙. public 복사본과 동기화해 두어야 한다.
export const amountFields=['retail','common','support','installmentFee','planFee','discountBase','addonFee','addonMonths'];
// 금액은 원 단위 안전 정수만 허용하고 추가지원금의 음수는 문의 표시 변환용으로만 받는다.
export function parseAmount(raw,allowNegative=false){if(raw===null||raw===undefined||String(raw).trim()==='')return null;const s=String(raw).trim();if(!(allowNegative?/^-?(?:\d+|\d{1,3}(?:,\d{3})+)$/:/^(?:\d+|\d{1,3}(?:,\d{3})+)$/).test(s))throw new Error(allowNegative?'추가지원금은 음수를 포함한 원 단위 정수로 입력해 주세요.':'금액은 0 이상의 원 단위 정수로 입력해 주세요.');const n=Number(s.replaceAll(',',''));if(!Number.isSafeInteger(n)||Math.abs(n)>100000000)throw new Error('입력 범위를 확인해 주세요.');return n;}
// 가격 조건의 복합 키 형식은 모델|통신사|가입 유형|할인 방법이며 서버 저장 형식과 같아야 한다.
export function keyFor(id,carrier,type,discount){return `${id}|${carrier}|${type}|${discount}`;}
// 입력이 비면 임의 0원으로 채우지 않고 미계산 상태로 둔다. 24개월 견적의 월별 스케줄도 만든다.
export function calculateQuote(o={},discount='common',months=24){
 const r={principal:null,deviceTotal:null,planDiscount:null,planMonthly:null,schedule:null,deviceSchedule:null,upfront:null,issues:[]};
 const read=k=>{const v=o[k];if(v===undefined||v===null||v==='')return null;if(!Number.isSafeInteger(v)||(k!=='support'&&v<0)||Math.abs(v)>100000000){r.issues.push(`${k}: 입력값 오류`);return null;}return v;};
 const retail=read('retail'),support=read('support'),common=discount==='choice'?0:read('common');
 if(!['common','choice'].includes(discount)||![0,24].includes(months)){r.issues.push('선택 항목 오류');return r;}
 if(retail!==null&&support!==null&&common!==null){const n=retail-common-support;if(n<0)r.issues.push('지원금 합계가 출고가를 초과합니다.');else if(n>100000000)r.issues.push('구매 원금 입력 범위를 확인해 주세요.');else r.principal=n;}
 const fee=read('planFee'),base=discount==='choice'?read('discountBase'):0;
 if(fee!==null&&base!==null){if(base>fee)r.issues.push('선택약정 할인 대상 금액이 월정액을 초과합니다.');else if(discount==='choice'&&base%4!==0)r.issues.push('선택약정 할인액에 원 미만 금액이 있어 통신사 처리 기준 확인이 필요합니다.');else{r.planDiscount=discount==='choice'?base/4:0;r.planMonthly=fee-r.planDiscount;}}
 const installmentFee=months===0?0:read('installmentFee');
 if(r.principal!==null&&installmentFee!==null){r.deviceTotal=r.principal+installmentFee;r.upfront=months===0?r.principal:0;const regular=months===24?Math.floor(r.deviceTotal/240)*10:0;r.deviceSchedule=Array.from({length:24},(_,i)=>months===0?0:(i===23?r.deviceTotal-regular*23:regular));}
 const addon=read('addonFee'),duration=read('addonMonths');
 if(duration!==null&&duration>24)r.issues.push('부가서비스 계산 기간은 0~24개월 범위입니다.');
 if(addon!==null&&duration!==null&&addon>0&&duration===0)r.issues.push('유료 부가서비스의 청구 개월 수를 입력해 주세요.');
 const addonSchedule=o.addonSchedule;const validSchedule=Array.isArray(addonSchedule)&&addonSchedule.length===24&&addonSchedule.every(n=>Number.isSafeInteger(n)&&n>=0&&n<=100000000);
 if(addonSchedule!==undefined&&!validSchedule)r.issues.push('부가서비스 월별 금액 오류');
 if(r.deviceSchedule&&r.planMonthly!==null&&addon!==null&&duration!==null&&duration<=24&&!(addon>0&&duration===0))r.schedule=r.deviceSchedule.map((device,i)=>({month:i+1,device,plan:r.planMonthly,addon:validSchedule?addonSchedule[i]:(i<duration?addon:0),total:device+r.planMonthly+(validSchedule?addonSchedule[i]:(i<duration?addon:0))}));
 return r;
}
// 전체 설정 저장 전에 필드·범위·색상·이미지 용량과 계산 규칙을 최종 확인한다.
export function validateConfig(c,ids){
 if(!c||typeof c!=='object'||!c.offers||!c.colors||Array.isArray(c.offers)||Array.isArray(c.colors))throw new Error('설정 형식을 확인해 주세요.');
 if(Object.keys(c.offers).length>5400)throw new Error('설정은 5,400개 조건까지 저장할 수 있습니다.');
 const fields=new Set([...amountFields,'planName','planDetails','addonDetails','addonSchedule','supportInquiry','penaltyDetails','terms']);
 for(const [key,o]of Object.entries(c.offers)){
 const [id,carrier,type,discount,...rest]=key.split('|');
 if(rest.length||!ids.includes(Number(id))||!['SKT','KT','LG U+'].includes(carrier)||!['번호이동','기기변경','신규가입'].includes(type)||!['common','choice'].includes(discount)||!o||typeof o!=='object'||Array.isArray(o))throw new Error('견적 항목을 확인해 주세요.');
 for(const [f,v]of Object.entries(o)){if(!fields.has(f))throw new Error('알 수 없는 입력 항목');if(f==='supportInquiry'){if(typeof v!=='boolean'||(v&&o.support!==0))throw new Error('문의 지원금은 계산값 0원으로 저장해 주세요.');}else if(f==='addonSchedule'){if(!Array.isArray(v)||v.length!==24||v.some(n=>!Number.isSafeInteger(n)||n<0||n>100000000))throw new Error('부가서비스 월별 금액 오류');}else if(amountFields.includes(f)){if(v!==null&&(!Number.isSafeInteger(v)||(f!=='support'&&v<0)||Math.abs(v)>100000000))throw new Error('금액은 원 단위 정수만 저장할 수 있습니다.');}else if(typeof v!=='string'||v.length>6000)throw new Error('안내문 길이를 확인해 주세요.');}
 if(discount==='choice'&&o.common!=null&&o.common!==0)throw new Error('선택약정에 공통 지원금을 중복 적용할 수 없습니다.');const q=calculateQuote(o,discount,24);const invalid=q.issues.filter(issue=>issue!=='지원금 합계가 출고가를 초과합니다.');if(invalid.length)throw new Error(invalid.join(' '));
 }
 for(const [id,colors]of Object.entries(c.colors))if(!ids.includes(Number(id))||!Array.isArray(colors)||colors.length>30||colors.some(x=>typeof x!=='string'||!x.trim()||x.length>60)||new Set(colors).size!==colors.length)throw new Error('색상 정보를 확인해 주세요.');if(c.colorImages!==undefined){
 if(!c.colorImages||typeof c.colorImages!=='object'||Array.isArray(c.colorImages))throw new Error('색상 이미지 형식 오류');
 let total=0;
 for(const [id,images] of Object.entries(c.colorImages)){
  if(!ids.includes(Number(id))||!images||typeof images!=='object'||Array.isArray(images)||Object.keys(images).length>30)throw new Error('색상 이미지 모델 오류');
  for(const [name,src] of Object.entries(images)){
   const safePath=typeof src==='string'&&src.length<=2000&&!src.includes('..')&&!src.startsWith('//')&&/^\/?[A-Za-z0-9_%+./-]+\.(?:png|jpg|jpeg|webp|svg)$/.test(src);
   if(!name.trim()||name.length>60||typeof src!=='string'||src.length>40000||!(/^data:image\/(?:png|jpeg|webp);base64,[A-Za-z0-9+/]+={0,2}$/.test(src)||safePath))throw new Error('색상 이미지를 다시 등록해 주세요.');
   total+=src.length;
  }
 }
 if(total>600000)throw new Error('색상 이미지 저장 용량을 초과했습니다. 사용하지 않는 이미지를 삭제해 주세요.');
 }
 return c;
}
