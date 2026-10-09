// 대표 256GB·번호이동·선택약정 조건의 추가지원금만 비교해 모델 추천 표시 순위를 만든다.
// 월 납부액이나 전체 구매 비용의 순위가 아니며, 가격표 자료가 바뀌면 다시 계산된다.
export function rankPolicyBest(groups,offers){
 const rows=[];
 for(const group of groups){
  const id=group.variants?.['256'];if(!Number.isInteger(id)||group.inquiryOnly)continue;
  const candidates=['SKT','KT','LG U+'].map(carrier=>({carrier,offer:offers[`${id}|${carrier}|번호이동|choice`]})).filter(({offer:o})=>o&&Number.isSafeInteger(o.support)&&o.support>0&&!o.supportInquiry&&Number.isSafeInteger(o.retail)&&o.retail>=o.support&&Number.isSafeInteger(o.planFee)&&o.planFee>0);
  if(!candidates.length)continue;
  const support=Math.max(...candidates.map(c=>c.offer.support));
  rows.push({id:group.id,variantId:id,name:group.name,capacity:'256',support,carriers:candidates.filter(c=>c.offer.support===support).map(c=>({name:c.carrier,planFee:c.offer.planFee}))});
 }
 rows.sort((a,b)=>b.support-a.support||a.id-b.id);
 // 지원금이 같은 모델은 같은 순위로 표시한다.
 return rows.map(row=>({...row,rank:1+rows.filter(other=>other.support>row.support).length}));
}
