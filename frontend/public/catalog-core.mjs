// 모델 그룹과 용량별 상품 ID를 연결하는 작은 계산 모듈이다. 원본 카탈로그 재수집 시 그룹 구조를 점검한다.
export function findGroup(groups,id){return groups.find(g=>Object.values(g.variants).includes(id))||null;}
// 미등록 용량·문의 전용 모델은 null로 돌려 다른 모델의 가격을 대신 적용하지 않는다.
export function variantId(group,capacity){if(!group||capacity==='1T'||group.inquiryOnly)return null;const id=group.variants[capacity];return Number.isInteger(id)?id:null;}
