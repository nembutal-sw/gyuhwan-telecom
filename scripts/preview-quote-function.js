// sync-main-page.mjs가 offline.js 끝에 붙이는 코드 조각. 원본 모듈의 state·렌더 함수를 사용한다.
// 단독 실행 모듈이 아니며 관리자 iframe에서 모델/구매 조건을 선택·재렌더링하는 연결점이다.
export function applyAdminPreviewSelection(selection) {
  if (!isAdminPreview || !selection || !Number.isInteger(Number(selection.model))) return false;
  const model = Number(selection.model);
  if (!state.products.some(product => product.id === model)) return false;
  openProduct(model);
  if (['SKT', 'KT', 'LG U+'].includes(selection.carrier)) state.carrier = selection.carrier;
  if (['번호이동', '기기변경', '신규가입'].includes(selection.type)) state.type = selection.type;
  if (['common', 'choice'].includes(selection.discount)) state.discount = selection.discount;
  for (const name of ['carrier', 'type', 'discount', 'months']) $$(`input[name="${name}"]`).forEach(input => { input.checked = input.value === String(state[name]); });
  renderCapacity();
  renderAddons();
  renderDialogProduct();
  renderColors();
  selectCarrier();
  renderQuote();
  return true;
}

export function adminPreviewContext() {
  return { carrier: state.carrier, productId: state.selected?.id, groupId: state.group?.id, discount: state.discount };
}

export async function refreshAdminPreview() {
  if (!isAdminPreview) return;
  refreshAdminAddonGuide();
  addonReturnNotice = loadedContentResource('purchase-notices.json')?.addonReturnNotice ?? addonReturnNotice;
  addonCatalog = loadedContentResource('addons.json') || addonCatalog;
  if (addonCatalog) {
    $('#addon-guide').innerHTML = `<div class="addon-table-wrap"><table class="store-addon-table"><thead><tr><th>통신사</th><th>부가서비스명</th><th>월 이용요금</th><th>유지기간</th></tr></thead><tbody>${Object.entries(addonCatalog.carriers).map(([carrier,items])=>items.map((a,i)=>`<tr>${i===0?`<th rowspan="${items.length}">${carrier==='KT'?'<span class="kt-addon-mark">':carrier==='LG U+'?'<span class="lgu-mark">':''}<img class="addon-logo" src="${carrierLogo(carrier)}" alt="${esc(carrier)}">${carrier==='KT'||carrier==='LG U+'?'</span>':''}<span class="sr-only">${esc(carrier)}</span></th>`:''}<td>${esc(a.officialName||a.name)}</td><td>${won(a.fee)}${a.insurance?' (매장 예상)':a.from?'부터':''}</td><td>${esc(a.period)}</td></tr>`).join('')).join('')}</tbody></table></div><p class="field-hint">${esc(addonGuideText)}<br>보험료는 안내 기준이며 단말기·보장 조건에 따라 달라질 수 있습니다.</p>`;
  }
  await loadConfig();
  if (state.selected) {
    state.group = findGroup(state.groups, state.selected.id) || state.group;
    renderDialogProduct(); renderAddons(); renderColors(); renderQuote();
  }
  renderProducts();
}
