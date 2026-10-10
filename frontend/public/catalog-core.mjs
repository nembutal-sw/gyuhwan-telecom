// 모델 그룹과 용량별 상품 ID를 연결하는 작은 계산 모듈이다. 원본 카탈로그 재수집 시 그룹 구조를 점검한다.
export function findGroup(groups,id){return groups.find(g=>Object.values(g.variants).includes(id))||null;}
// 미등록 용량·문의 전용 모델은 null로 돌려 다른 모델의 가격을 대신 적용하지 않는다.
export function variantId(group,capacity){if(!group||group.inquiryOnly)return null;const id=group.variants[capacity];return Number.isInteger(id)?id:null;}
// 숨긴 용량은 모델 선택 목록과 용량 버튼에서 제외한다. 대표 용량이 숨겨지면 보이는 용량을 대표로 쓴다.
export function visibleGroups(groups,products){const byId=new Map(products.map(p=>[p.id,p]));return groups.flatMap(group=>{const variants=Object.fromEntries(Object.entries(group.variants).filter(([,id])=>!Number.isInteger(id)||!byId.get(id)?.hidden));const ids=Object.values(variants).filter(Number.isInteger);if(!ids.length)return [];const id=ids.includes(group.id)?group.id:ids[0];return [{...group,id,variants}];});}
export function sellingLabel(status){return ({selling:'판매 중',sold_out:'품절',incoming:'입고 예정',discontinued:'판매 종료'})[status]||'판매 중';}
