// 통신사별 인터넷·TV 첫 설치비 계산의 기본 자료와 계산 함수다. 공개 자료 로드 실패 시 기본값을 사용한다.
// 이 파일의 자료 부분은 sync-main-page.mjs 재수집에서 갱신될 수 있다.
import {loadContentResource} from './content-data.js';
const defaultinstallationRules={
 SKT:{internetOnly:36300,internetWithTV:34100,tvFirst:22000,tvAdditional:17600,sources:['https://www.bworld.co.kr/product/internet/charge.do?menu_id=P02010300','https://www.bworld.co.kr/product/btv/charge.do?menu_id=P03010300'],promotion:'현재 이 매장 견적에 적용할 설치비 전액 면제 기간은 확인되지 않았습니다. 기존 AI 5 출시 행사(2026.04.30까지)는 종료되어 자동 적용하지 않습니다.'},
 KT:{internetOnly:36000,internetWithTV:32000,tvFirst:24200,tvAdditional:15400,sources:['https://m.product.kt.com/static/prodetail/1505/mobile/m_htmlUploadType_20230207153539.html','https://m.product.kt.com/static/prodetail/common/mobile/genie_tv/m_genieTvCommon_20260821.html'],promotion:'현재 이 매장 견적에 적용할 설치비 전액 면제 행사와 종료일은 확인되지 않았습니다. 인터넷과 Wi-Fi 동시 설치 시 Wi-Fi 출동비 할인은 인터넷 설치비 전체 면제와 다릅니다.'},
 'LG U+':{internetOnly:36300,internetWithTV:34100,tvFirst:22000,tvAdditional:22000,sources:['https://www.lguplus.com/support/service/notice/2000015956','https://www.lguplus.com/support/service/notice/2000029002'],promotion:'현재 이 매장 견적에 적용할 설치비 전액 면제 기간은 확인되지 않았습니다. 공식 올인원 할인은 2026.08.31부터 프로모션 종료로 안내되어 자동 적용하지 않습니다.'}
};
// 관리자 미리보기에서는 동일 객체를 갱신하므로 계산 함수를 다시 import할 필요가 없다.
export const installationRules=await loadContentResource('installation-rules.json').then(v=>v).catch(()=>defaultinstallationRules);
// TV 회선 수에 따라 단독/동시 설치 요금과 추가 TV 설치비를 나눠 합산한다.
export function installationQuote(carrier,tvCount){const r=installationRules[carrier];if(!r||!Number.isInteger(tvCount)||tvCount<0||tvCount>3)throw Error('설치 상품을 확인해 주세요.');const internet=tvCount?r.internetWithTV:r.internetOnly,television=tvCount?r.tvFirst+(tvCount-1)*r.tvAdditional:0;return {internet,television,total:internet+television};}
