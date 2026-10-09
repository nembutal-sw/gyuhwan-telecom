// 관리자 미리보기와 고객 견적이 공유하는 24개월 할부 추정. public 복사본과 동기화해 둔다.
import {calculateQuote} from './quote-core.mjs';
export const annualRate=0.059;
// 연 5.9% 원리금균등 추정 이자를 원 단위로 반올림한다. 실제 통신사 청구액은 다를 수 있다.
export function estimatedInstallmentFee(principal,months=24){if(!Number.isSafeInteger(principal)||principal<0||principal>100000000||months!==24)throw Error('할부 계산값을 확인해 주세요.');if(principal===0)return 0;const r=annualRate/12,payment=principal*r/(1-Math.pow(1+r,-months));return Math.round(payment*months-principal);}
// 원금 계산 결과에 추정 이자를 넣어 기기·요금제의 월별 견적을 다시 계산한다.
export function calculateQuoteWithInterest(offer={},discount='common',months=24){const principal=calculateQuote(offer,discount,0).principal;const estimated=months===24&&principal!==null;const fee=months===0?0:estimated?estimatedInstallmentFee(principal):null;return {...calculateQuote({...offer,installmentFee:fee},discount,months),installmentFee:fee,feeEstimated:estimated,annualRate:months===24?annualRate:null};}

// 번호이동 유심비는 첫 달에만 더해 고객에게 보이는 총액과 월별 값을 맞춘다.
export function calculatePurchaseQuote(offer={},discount='common',months=24,type='기기변경'){
 const q=calculateQuoteWithInterest(offer,discount,months),simFee=type==='번호이동'?7700:0;
 return {...q,simFee,schedule:q.schedule?.map((row,i)=>({...row,sim:i===0?simFee:0,total:row.total+(i===0?simFee:0)}))??null};
}
