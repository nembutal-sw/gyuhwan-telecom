// 원본 페이지의 동적 DOM을 관리자 편집 스키마의 자료 경로와 잇는다. 재수집한 DOM 구조가 바뀌면 이 매핑도 점검한다.
// JSON 포인터는 편집 스키마에서 해석하며 운영자 화면에는 그대로 표시하지 않는다.
export const pointerPart = value => String(value).replaceAll('~', '~0').replaceAll('/', '~1');

// 링크는 첫 클릭에 이동하므로 캡처 단계에서 먼저 가로채 편집 대상을 연다.
// 이동·전화·다운로드·원본 이벤트 처리보다 앞서 막되 모델 카드와 필터는 상태 탐색에 사용할 수 있게 둔다.
export function guardPreviewNavigation(event, { editMode, editingDisabled, resolveTarget, requestEdit }) {
  if (!editMode || event.target.closest?.('[data-preview-ui="true"]')) return false;
  const action = event.target.closest?.('a[href],button[data-content-node][data-product],button[data-content-node][data-upcoming-duo],button[data-content-node][data-net-carrier],#member-open,#copy-inquiry,#quote-copy-text,#quote-save-jpg');
  if (!action) return false;
  event.preventDefault();
  event.stopImmediatePropagation();
  if (!editingDisabled) {
    const target = resolveTarget(event);
    if (target.bindings.length) requestEdit(target.bindings, target.element);
  }
  return true;
}

export function productFieldPointers(products, catalog, id, carrier) {
  // 대표 모델과 용량별 모델의 화면 요소를 각각 catalog/products의 실제 JSON 경로에 연결한다.
  const index = products.findIndex(product => product.id === Number(id));
  const groupIndex = catalog.findIndex(group => group.id === Number(id) || Object.values(group.variants || {}).includes(Number(id)));
  if (index < 0) return null;
  const product = products[index], base = `/${index}`, activeCarrier = pointerPart(carrier);
  return {
    title: groupIndex < 0 ? null : `/${groupIndex}/name`,
    image: [...new Set([product.carrierImages?.[carrier] ? `${base}/carrierImages/${activeCarrier}` : `${base}/image`, `${base}/image`, `${base}/carrierImages/${activeCarrier}`, `${base}/imageAlt`])],
    code: [...new Set([product.carrierCodes?.[carrier] ? `${base}/carrierCodes/${activeCarrier}` : `${base}/code`, `${base}/code`])],
    name: `${base}/name`,
  };
}

export function internetFieldPointers({ carrier, speed, selections }) {
  // TV 선택 여부에 따라 인터넷 단독 요금과 결합 요금의 경로가 달라진다.
  const base = `/${pointerPart(carrier)}`, active = selections.filter(Boolean);
  return {
    base,
    internet: `${base}/${active.length ? 'withTV' : 'internet'}/${speed}`,
    tv: selections.map(index => [`${base}/tv/${index}/0`, `${base}/tv/${index}/1`]),
    installationInternet: `${base}/${active.length ? 'internetWithTV' : 'internetOnly'}`,
    installationTV: active.length ? [`${base}/tvFirst`, ...(active.length > 1 ? [`${base}/tvAdditional`] : [])] : [],
  };
}

export function collectDynamicBindings(doc, resources, quote, internet) {
  // 원본 스크립트가 다시 그린 카드·견적·설치비 영역도 해당 관리자 필드를 찾아 편집할 수 있게 한다.
  const bindings = [];
  const add = (element, resource, pointers) => {
    if (element) bindings.push({ element, resource, pointers: (Array.isArray(pointers) ? pointers : [pointers]).filter(Boolean) });
  };
  const products = resources('products.json') || [], catalog = resources('catalog.json') || [];
  function productArea(area, id) {
    if (!area) return;
    const pointers = productFieldPointers(products, catalog, id, quote.carrier);
    if (!pointers) return;
    add(area.querySelector('h3'), 'catalog.json', pointers.title);
    add(area.querySelector('.product-code'), 'products.json', pointers.code);
    add(area.querySelector('.product-image img, img:not(.card-brand-logo)'), 'products.json', pointers.image);
  }
  doc.querySelectorAll('#product-grid [data-product]').forEach(button => productArea(button.closest('.product-card'), Number(button.dataset.product)));
  productArea(doc.querySelector('#dialog-product'), quote.productId);
  doc.querySelectorAll('#product-grid [data-upcoming-duo]').forEach(button => {
    const area = button.closest('.product-card');
    add(area?.querySelector('h3'), 'upcoming-duo.json', '/name');
    add(area?.querySelector('.product-code'), 'upcoming-duo.json', '/code');
    add(area?.querySelector('.product-image img'), 'upcoming-duo.json', '/image');
  });

  const addons = resources('addons.json');
  let rowIndex = 0;
  for (const [carrier, items] of Object.entries(addons?.carriers || {})) items.forEach((item, index) => {
    const row = doc.querySelectorAll('#addon-guide tbody tr')[rowIndex++], cells = row?.querySelectorAll('td');
    const base = `/carriers/${pointerPart(carrier)}/${index}`;
    add(cells?.[0], 'addons.json', [item.officialName ? `${base}/officialName` : `${base}/name`, `${base}/name`, `${base}/officialName`]);
    add(cells?.[1], 'addons.json', `${base}/fee`);
    add(cells?.[2], 'addons.json', `${base}/period`);
  });
  const currentAddons = addons?.carriers?.[quote.carrier] || [];
  const addonCards = doc.querySelectorAll('#addon-details .addon-notice-card');
  currentAddons.forEach((item, index) => {
    const card = addonCards[index], base = `/carriers/${pointerPart(quote.carrier)}/${index}`;
    if (card) {
      add(card.querySelector('h4'), 'addons.json', [item.officialName ? `${base}/officialName` : `${base}/name`, `${base}/name`]);
      add(card.querySelector('.addon-notice-title span'), 'addons.json', `${base}/period`);
      const amounts = card.querySelectorAll('dd');
      add(amounts[0], 'addons.json', `${base}/fee`); add(amounts[1], 'addons.json', `${base}/period`);
      add(card.querySelector(':scope > p'), 'addons.json', `${base}/description`);
      add(card.querySelector(':scope > .field-hint'), 'addons.json', `${base}/note`);
      card.querySelectorAll('a').forEach((element, sourceIndex) => add(element, 'addons.json', sourceIndex ? [`${base}/extraSources/${sourceIndex - 1}/name`, `${base}/extraSources/${sourceIndex - 1}/url`] : `${base}/source`));
    } else {
      const link = [...doc.querySelectorAll('#addon-details > a')].find(element => element.getAttribute('href') === item.source);
      add(link, 'addons.json', [item.officialName ? `${base}/officialName` : `${base}/name`, `${base}/source`]);
    }
  });
  const notice = 'purchase-notices.json', carrierBase = `/carrierPolicies/${pointerPart(quote.carrier)}`;
  add(doc.querySelector('#addon-guide > .field-hint'), notice, '/addonGuideText');
  add(doc.querySelector('#addon-products > .field-hint'), notice, '/addonGuideText');
  add(doc.querySelector('#addon-details > .field-hint'), notice, '/addonGuideText');
  add(doc.querySelector('#addon-details .addon-return-notice'), notice, '/addonReturnNotice');
  add(doc.querySelector('#contract-details a'), notice, `${carrierBase}/choiceSource`);
  add(doc.querySelector('#penalty-details a'), notice, `${carrierBase}/source`);
  if (quote.discount === 'common') {
    const policy = resources(notice)?.carrierPolicies?.[quote.carrier]?.plan;
    if (policy) add([...doc.querySelectorAll('#penalty-details p')].find(element => element.textContent === policy), notice, `${carrierBase}/plan`);
  }

  if (internet) {
    const pointers = internetFieldPointers(internet), base = pointers.base;
    const rows = [...doc.querySelectorAll('#net-result > .amount-row')];
    add(rows[0]?.querySelector('strong'), 'internet-plans.json', pointers.internet);
    pointers.tv.forEach((fields, index) => {
      add(rows[index + 1]?.querySelector('span'), 'internet-plans.json', fields[0]);
      add(rows[index + 1]?.querySelector('strong'), 'internet-plans.json', fields[1]);
    });
    const installationOffset = pointers.tv.length + 2;
    add(rows[installationOffset]?.querySelector('strong'), 'installation-rules.json', pointers.installationInternet);
    add(rows[installationOffset + 1]?.querySelector('strong'), 'installation-rules.json', pointers.installationTV);
    add(doc.querySelector('#net-explanation'), 'internet-plans.json', `${base}/description`);
    add(doc.querySelector('#net-bundle'), 'internet-plans.json', `${base}/bundle`);
    add(doc.querySelector('#net-result details p'), 'installation-rules.json', `${base}/promotion`);
    doc.querySelectorAll('#net-result details a').forEach((element, index) => add(element, 'installation-rules.json', `${base}/sources/${index}`));
    doc.querySelectorAll('#net-sources a').forEach((element, index) => add(element, 'internet-plans.json', [`${base}/sources/${index}/0`, `${base}/sources/${index}/1`]));
  }
  return bindings;
}
