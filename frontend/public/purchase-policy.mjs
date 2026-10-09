// 통신사별 약정·반환 안내의 화면용 문구를 만든다. 공개 자료가 없을 때만 아래 기본 문구를 사용한다.
// 안내 자료 부분은 sync-main-page.mjs로 원본 페이지를 재수집하면 갱신될 수 있다.
import {loadContentResource} from './content-data.js';
const defaultcarrierPolicies={
 'SKT':{source:'https://www.tworld.co.kr/poc/html/product/TS3.5.1T.4.html',choiceSource:'https://www.tworld.co.kr/poc/html/product/TS3.5.1T.1.html',plan:'프리미엄패스 적용 시 181일 이상 유지 후 요금제 변경에 따른 차액정산금 면제 조건을 확인하세요. 2023년 3월 30일 이후 가입 기준 5G 월 42,000원 이상, LTE 월 20,000원 이상입니다. 대상 요금제·가입 여부에 따라 달라집니다.'},
 'KT':{source:'https://shop.kt.com/m/smart/supportAmtList.do',choiceSource:'https://product.kt.com/wDic/productDetail.do?ItemCode=1657',plan:'심플코스는 가입 180일 경과 후 5G 월 47,000원 이상(또는 지정 시니어 요금제), LTE·3G 월 20,000원 이상으로 변경하면 차액정산금 면제 대상입니다. 180일 이내 하향 변경 시 변경 전후 공통 지원금 차액이 청구됩니다.'},
 'LG U+':{source:'https://www.lguplus.com/benefit-event/ongoing/82175',choiceSource:'https://www.lguplus.com/benefit-uplus/price-discount/B400000003',plan:'식스플랜은 개통 요금제 180일 유지 후 요금제 변경 시 차액정산금 면제 조건을 적용합니다. 변경 가능한 요금제와 제외 대상은 가입한 약정에서 확인하세요. 베이직플랜은 24개월 내 요금제 변경 시 차액정산금이 발생할 수 있습니다.'}
};
export const carrierPolicies=await loadContentResource('purchase-notices.json').then(v=>v.carrierPolicies).catch(()=>defaultcarrierPolicies);
// 선택약정과 공통/유통망 지원금의 반환 기준을 섞지 않고 선택 조건에 맞는 문구를 조합한다.
export function policyText(carrier,discount){const p=carrierPolicies[carrier];const common=carrier==='LG U+'?'유통망 지원금':'공통 지원금',extra='추가지원금';return `약정기간 안내\n${discount==='choice'?'선택약정: 12개월 또는 24개월 중 계약 시 선택 · 할인 대상 통신요금 25% 할인. 이 견적의 24개월 납부표는 약정 선택을 대신하지 않습니다.':`${common}: 24개월 약정 기준 · 실제 가입 약정 확인.`}\n단말기 일시불·24개월 할부와 통신서비스 약정기간은 별도입니다.\n요금제 변경\n${discount==='choice'?'선택약정은 변경한 요금제의 할인 대상 금액에 따라 할인액이 달라집니다. 중도 해지 시 할인반환금이 발생할 수 있습니다.':p.plan}\n매장 유지조건 · 고객 안내\n개통 후 만 185일 이내 해지 및 요금제 변경 시 지원금 반환 등 위약금이 발생할 수 있습니다. 반환 대상·금액은 가입 계약과 판매점 약정에 따릅니다.\n부가서비스\n유지기간 내 해지 및 미유치 시, 판매점지원금 일부를 반환해야 해요. 개통 전 안내받고 동의한 판매점 지원금 조건에 한해 적용하며, 반환 대상·금액·예외는 판매점 약정에서 확인해 주세요.\n${common} / ${extra}\n약정 초기 해지 시 전액, 이후에는 사용기간에 따른 일부 반환이 발생할 수 있습니다. 통신사 기준과 매장 185일 유지조건은 서로 다르며, 185일 경과만으로 반환금이 면제되지 않습니다.\n선택약정은 ${common} 반환 대신 할인반환금 기준을 적용하며, 별도 지원금 약정은 따로 확인합니다.\n남은 단말기 할부금은 위약금과 별도입니다.`;}
