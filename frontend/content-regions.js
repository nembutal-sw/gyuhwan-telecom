// 실제 고객 페이지의 시각적 구역과 콘텐츠 섹션 ID를 연결한다. 미리보기 위치 이동·구역 메뉴가 함께 사용한다.
export const contentRegions = [
  { id: 'header', number: '01', title: '상단 메뉴', selector: 'header', sections: ['header'] },
  { id: 'home', number: '02', title: '메인 배너', selector: '#home', sections: ['home'] },
  { id: 'preorder-video', number: '03', title: '사전예약 영상', selector: '.preorder-video', sections: ['preorder-video'] },
  { id: 'best', number: '04', title: '인기 휴대폰·특가', selector: '#best', sections: ['best'] },
  { id: 'shop', number: '05', title: '휴대폰 견적·모델 카드', selector: '#shop', sections: ['shop', 'catalog-data', 'products-data', 'upcoming-data', 'dialogs', 'notices-data'] },
  { id: 'internet', number: '06', title: '인터넷·TV', selector: '#internet', sections: ['internet', 'internet-data', 'installation-data'] },
  { id: 'contact', number: '07', title: '상담 안내', selector: '#contact', sections: ['contact'] },
  { id: 'store', number: '08', title: '매장 정보', selector: '#store', sections: ['store'] },
  { id: 'store-map', number: '09', title: '매장 지도', selector: '#store-map-slot, .store-map', sections: ['store-map'] },
  { id: 'faq', number: '10', title: '자주 묻는 질문', selector: '.faq', sections: ['faq'] },
  { id: 'addons', number: '11', title: '부가서비스 안내', selector: '#addons', sections: ['addons', 'addons-data'] },
  { id: 'site-qr', number: '12', title: '홈페이지 QR', selector: '.site-qr', sections: ['site-qr'] },
  { id: 'footer', number: '13', title: '하단 정보', selector: 'footer', sections: ['footer'] },
  { id: 'other', number: '14', title: '고정 안내·기타', selector: '.cert-link, .mobile-bar, .offline-note', sections: ['other'] },
];

// 화면에 직접 드러나지 않는 상품 JSON 자료도 운영자가 찾을 수 있게 섹션별 설명을 붙인다.
export const sectionHints = {
  header: '메뉴·로고', home: '배너 문구·사진', 'preorder-video': '영상', best: '특가 카드·문구',
  shop: '화면 안내 문구', 'catalog-data': '카드 모델명', 'products-data': '모델 정보·사진',
  'upcoming-data': '준비중 모델 카드', dialogs: '견적·상담 창', 'notices-data': '구매·유지 안내',
  internet: '화면 문구·로고', 'internet-data': '상품·월 요금', 'installation-data': '설치비·설치 안내',
  addons: '화면 안내 문구', 'addons-data': '부가서비스 상품',
};

// 여러 데이터 섹션이 같은 화면 구역에 속할 수 있으므로 포함 관계로 찾는다.
export function regionForSection(sectionId) {
  return contentRegions.find(region => region.sections.includes(sectionId)) || contentRegions.at(-1);
}
