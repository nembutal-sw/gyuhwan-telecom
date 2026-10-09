// 고객 페이지를 관리자 iframe에서 그대로 보여주며 화면 위치와 편집 필드를 부모 창에 연결한다.
// sync-main-page.mjs로 원본 화면을 다시 수집하면 DOM 선택자와 필드 매핑을 함께 점검해야 한다.
import { isAdminPreview, loadedContentResource } from './content-data.js';
import { contentRegions, regionForSection } from './content-regions.js';
import { collectDynamicBindings, guardPreviewNavigation } from './preview-bindings.js';

export async function receivePreviewPayload() {
  // 메시지는 같은 출처의 실제 부모 창에서 온 초안만 수신한다. 일반 고객 페이지에서는 대기하지 않는다.
  if (!isAdminPreview || parent === window) return null;
  return new Promise(resolve => {
    let timer;
    function receive(event) {
      if (event.origin !== location.origin || event.source !== parent || event.data?.type !== 'gyuhwan-preview' || !Array.isArray(event.data.sections)) return;
      clearTimeout(timer);
      removeEventListener('message', receive);
      resolve(event.data);
    }
    addEventListener('message', receive);
    timer = setTimeout(() => { removeEventListener('message', receive); resolve(null); }, 12000);
    parent.postMessage({ type: 'gyuhwan-preview-ready' }, location.origin);
  });
}

export function installPreviewLocations(initialPayload, applyQuoteSelection, controls = {}) {
  // 편집 표시 요소는 고객 화면 데이터와 별도 레이어에 만들고 부모 창에는 선택된 필드 ID만 보낸다.
  if (!isAdminPreview || parent === window) return;
  let payload = initialPayload || {};
  let editBindings = new Map();
  const mediaButtons = [];
  const editLayers = new Map();
  const keyboardElements = new Set();
  const layer = document.createElement('div');
  layer.id = 'gyuhwan-preview-locations';
  layer.setAttribute('aria-label', '관리자 미리보기 구역 표시');
  const style = document.createElement('style');
  style.textContent = `[data-preview-region]{outline:1px dashed #7394ef;outline-offset:-1px}[data-preview-selected="true"]{outline:4px solid #2454ef;outline-offset:-4px}#gyuhwan-preview-locations{position:absolute;inset:0 0 auto;z-index:2147483600;pointer-events:none}.gyuhwan-preview-badge{position:absolute;pointer-events:auto;border:1px solid #1636a0;border-radius:5px;background:#2454ef;color:white;padding:5px 9px;font:600 12px/1.4 Arial,sans-serif;box-shadow:0 2px 8px #0003;cursor:pointer;max-width:calc(100vw - 16px);white-space:nowrap}.gyuhwan-preview-badge:not([data-active="true"]){background:#fff;color:#2454ef;border-color:#7394ef}`;
  document.head.append(style);
  document.body.append(layer);
  const editorStyle = document.createElement('style');
  editorStyle.textContent = `body[data-preview-edit="true"] [data-preview-region],body[data-preview-visual="true"] [data-preview-region]{outline:none}body[data-preview-edit="true"] [data-preview-selected="true"],body[data-preview-visual="true"] [data-preview-selected="true"]{outline:none}[data-preview-editable="true"]{cursor:default}[data-preview-hovered="true"],[data-preview-editable="true"]:focus-visible{outline:2px solid #2563eb!important;outline-offset:3px}[data-preview-modified="true"]{outline:2px dashed #ea580c!important;outline-offset:3px}[data-preview-field-selected="true"]{outline:3px solid #2563eb!important;outline-offset:3px}.gyuhwan-edit-layer{position:absolute;inset:0 0 auto;z-index:2147483601;pointer-events:none}.gyuhwan-media-edit{position:absolute;pointer-events:auto;display:block;background:transparent!important;border:0!important;border-radius:0!important;padding:0!important;cursor:pointer;box-shadow:inset 0 0 0 1px #2563eb66}.gyuhwan-media-edit:hover,.gyuhwan-media-edit:focus-visible{outline:3px solid #2563eb;outline-offset:2px}.gyuhwan-media-edit[data-modified="true"]{box-shadow:inset 0 0 0 2px #ea580c}.gyuhwan-media-edit span{position:absolute;top:6px;left:6px;background:#fff;color:#1643ad;border:1px solid #2563eb;border-radius:5px;padding:5px 8px;font:600 12px/1.4 Arial,sans-serif;box-shadow:0 2px 7px #0003;white-space:nowrap}.gyuhwan-edit-tooltip{position:absolute;pointer-events:none;z-index:2147483602;background:#1636a0;color:#fff;border-radius:4px;padding:4px 7px;font:12px/1.4 Arial,sans-serif;white-space:nowrap}.gyuhwan-edit-layer[hidden]{display:none}`;
  document.head.append(editorStyle);
  const marks = [];
  for (const region of contentRegions) {
    const candidates = [...document.querySelectorAll(region.selector)];
    for (const element of candidates.filter(element => !candidates.some(other => other !== element && other.contains(element)))) {
    element.dataset.previewRegion = region.id;
    const badge = document.createElement('button');
    badge.type = 'button';
    badge.className = 'gyuhwan-preview-badge';
    badge.textContent = `${region.number} ${element.classList.contains('mobile-bar') ? '모바일 하단 메뉴' : region.title}`;
    badge.dataset.sectionId = region.sections[0];
    badge.addEventListener('click', event => { event.preventDefault(); event.stopPropagation(); parent.postMessage({ type: 'gyuhwan-select-section', sectionId: region.sections[0] }, location.origin); });
    layer.append(badge);
    marks.push({ region, element, badge });
    }
  }
  function positionBadges() {
    const selected = regionForSection(payload.sectionId).id;
    for (const { region, element, badge } of marks) {
      const box = element.getBoundingClientRect();
      badge.hidden = box.width === 0 || box.height === 0;
      if (payload.editMode || payload.visualEditor) badge.hidden = true;
      badge.style.left = `${Math.max(8, Math.min(innerWidth - 180, box.left + scrollX + 8))}px`;
      badge.style.top = `${Math.max(0, box.top + scrollY + 8)}px`;
      badge.dataset.active = String(region.id === selected);
      element.dataset.previewSelected = String(region.id === selected);
    }
    for (const { element, button, host } of mediaButtons) {
      const box = element.getBoundingClientRect(), origin = host === document.body ? { left: -scrollX, top: -scrollY } : host.getBoundingClientRect();
      button.hidden = !payload.editMode || !box.width || !box.height || !element.getClientRects().length;
      button.style.left = `${box.left - origin.left + (host === document.body ? 0 : host.scrollLeft)}px`;
      button.style.top = `${box.top - origin.top + (host === document.body ? 0 : host.scrollTop)}px`;
      button.style.width = `${box.width}px`; button.style.height = `${box.height}px`;
    }
  }
  let positionFrame;
  const schedulePositions = () => { cancelAnimationFrame(positionFrame); positionFrame = requestAnimationFrame(positionBadges); };
  addEventListener('resize', schedulePositions);
  addEventListener('scroll', schedulePositions, { passive: true, capture: true });
  new ResizeObserver(schedulePositions).observe(document.body);
  let touchEditTarget;
  document.addEventListener('click', event => {
    // 편집 모드의 이동 링크는 첫 클릭에 열어야 하므로 원본 페이지 핸들러보다 먼저 처리한다.
    if (payload.editMode && touchEditTarget && touchEditTarget.contains(event.target)) { event.preventDefault(); event.stopImmediatePropagation(); touchEditTarget = null; return; }
    if (guardPreviewNavigation(event, { editMode: payload.editMode, editingDisabled: payload.editingDisabled, resolveTarget: fieldsForEvent, requestEdit })) return;
    if (payload.editMode) return;
    if (event.target.closest?.('button,a,input,select,textarea,summary,video,iframe')) return;
    const area = event.target.closest?.('[data-preview-region]');
    const region = contentRegions.find(item => item.id === area?.dataset.previewRegion);
    if (region) parent.postMessage({ type: 'gyuhwan-select-section', sectionId: region.sections[0] }, location.origin);
  }, true);
  document.addEventListener('submit', event => { if (payload.editMode) event.preventDefault(); }, true);
  document.addEventListener('copy', event => { if (payload.editMode) event.preventDefault(); }, true);
  document.addEventListener('auxclick', event => { if (payload.editMode && event.target.closest?.('a')) { event.preventDefault(); event.stopImmediatePropagation(); } }, true);

  function editLayer(host) {
    if (!editLayers.has(host)) {
      const target = document.createElement('div');
      target.className = 'gyuhwan-edit-layer'; target.dataset.previewUi = 'true';
      host.append(target); editLayers.set(host, target);
    }
    return editLayers.get(host);
  }
  function fieldsForEvent(event) {
    // 화면 좌표의 텍스트 노드와 동적 자료 경로 중 실제로 누른 대상의 편집 필드를 고른다.
    const result = [];
    let primary;
    for (let element = event.target; element instanceof Element; element = element.parentElement) {
      const bindings = editBindings.get(element) || [];
      const textBindings = bindings.filter(binding => binding.textNode);
      const hit = textBindings.filter(binding => {
        const range = document.createRange(); range.selectNodeContents(binding.textNode);
        return [...range.getClientRects()].some(box => event.clientX >= box.left && event.clientX <= box.right && event.clientY >= box.top && event.clientY <= box.bottom);
      });
      const chosen = hit.length ? [...hit, ...bindings.filter(binding => !binding.textNode)] : bindings;
      if (!primary && chosen.length) primary = element;
      for (const binding of chosen) if (!result.some(item => item.field.id === binding.field.id)) result.push(binding);
      if (primary) break;
    }
    // 로고 링크의 빈 여백을 눌러도 보이는 이미지·이름을 선택한다. 텍스트를 누르면 해당 노드만 선택한다.
    const rawLink = event.target.closest?.('a');
    if (!primary && rawLink) for (const [element, bindings] of editBindings) if (rawLink.contains(element)) {
      primary ||= element;
      for (const binding of bindings) if (!result.some(item => item.field.id === binding.field.id)) result.push(binding);
    }
    // 링크 주소는 함께 편집할 수 있게 포함하되 형제 텍스트까지 선택하지 않는다.
    const link = primary?.closest('a');
    for (const binding of editBindings.get(link) || []) if (binding.field.attribute === 'href' && !result.some(item => item.field.id === binding.field.id)) result.push(binding);
    return { bindings: result, element: primary };
  }
  function requestEdit(bindings, element) {
    if (!payload.editMode || payload.editingDisabled || !bindings.length) return;
    const fieldIds = [...new Set(bindings.map(binding => binding.field.id))];
    parent.postMessage({ type: 'gyuhwan-edit-content', sectionId: bindings[0].sectionId, fieldId: fieldIds[0], fieldIds, label: element?.getAttribute('alt') || bindings[0].field.label || '' }, location.origin);
  }
  function rebuildEditBindings() {
    // 원본 모듈이 카드나 견적 DOM을 다시 만들면 화면 요소와 관리자 필드의 연결도 다시 만든다.
    controls.reapplyStatic?.(payload.sections);
    for (const element of editBindings.keys()) {
      delete element.dataset.previewEditable; delete element.dataset.previewModified; delete element.dataset.previewFieldSelected; delete element.dataset.previewHovered;
    }
    for (const element of keyboardElements) element.removeAttribute('tabindex');
    keyboardElements.clear(); mediaButtons.length = 0;
    for (const target of editLayers.values()) target.replaceChildren();
    editBindings = new Map(); document.body.dataset.previewEdit = String(!!payload.editMode); document.body.dataset.previewVisual = String(!!payload.visualEditor);
    document.querySelectorAll('dialog > .gyuhwan-preview-badge').forEach(badge => { badge.hidden = !!(payload.editMode || payload.visualEditor); });
    if (!payload.editMode) { schedulePositions(); return; }
    const lookup = new Map();
    const add = (element, binding) => {
      if (!element) return;
      if (!editBindings.has(element)) editBindings.set(element, []);
      if (!editBindings.get(element).some(item => item.field.id === binding.field.id)) editBindings.get(element).push(binding);
    };
    for (const section of payload.sections || []) for (const field of section.fields || []) {
      const binding = { field, sectionId: section.id };
      if (field.resource) lookup.set(`${field.resource}:${field.pointer}`, binding);
      if (!field.node) continue;
      const element = document.querySelector(`[data-content-node="${CSS.escape(field.node)}"]`);
      const textNode = Number.isInteger(field.textIndex) && element?.childNodes[field.textIndex];
      if (!field.attribute && textNode?.nodeType !== Node.TEXT_NODE) continue;
      add(element, { ...binding, textNode: textNode || null });
    }
    for (const target of collectDynamicBindings(document, loadedContentResource, controls.quoteContext?.() || {}, controls.internetContext?.())) {
      for (const pointer of target.pointers) {
        const binding = lookup.get(`${target.resource}:${pointer}`);
        if (binding) add(target.element, binding);
      }
    }
    const modified = new Set(payload.modifiedFieldIds || []);
    for (const [element, bindings] of editBindings) {
      element.dataset.previewEditable = 'true';
      element.dataset.previewModified = String(bindings.some(binding => modified.has(binding.field.id)));
      element.dataset.previewFieldSelected = String(bindings.some(binding => binding.field.id === payload.selectedFieldId));
      const media = element.matches('img,iframe,video');
      if (media) {
        const host = element.closest('dialog') || document.body;
        const button = document.createElement('button'); button.type = 'button'; button.className = 'gyuhwan-media-edit';
        const label = element.matches('img') ? '사진 수정' : '영상 수정';
        button.setAttribute('aria-label', `${label}: ${String(bindings[0].field.label || '').replace(/^\s*\[\d+\]\s*/, '')}`);
        button.disabled = !!payload.editingDisabled;
        button.title = '더블클릭하여 수정'; button.dataset.modified = element.dataset.previewModified;
        const text = document.createElement('span'); text.textContent = `${label}${button.dataset.modified === 'true' ? ' · 변경됨' : ''}`; button.append(text);
        button.addEventListener('click', event => { event.preventDefault(); event.stopPropagation(); requestEdit(bindings, element); });
        button.addEventListener('dblclick', event => { event.preventDefault(); event.stopPropagation(); });
        editLayer(host).append(button); mediaButtons.push({ element, button, host });
      } else if (!element.hasAttribute('tabindex') && !element.matches('button,a,input,select,textarea,summary,option,label')) {
        element.tabIndex = 0; keyboardElements.add(element);
      }
    }
    parent.postMessage({ type: 'gyuhwan-preview-mapping', mappedFieldIds: [...new Set([...editBindings.values()].flatMap(bindings => bindings.map(binding => binding.field.id)))] }, location.origin);
    schedulePositions();
  }
  let rebuildFrame;
  const scheduleBindings = () => { cancelAnimationFrame(rebuildFrame); rebuildFrame = requestAnimationFrame(rebuildEditBindings); };
  new MutationObserver(records => {
    if (records.some(record => !record.target.closest?.('[data-preview-ui="true"],#gyuhwan-preview-locations') && ![...record.addedNodes, ...record.removedNodes].every(node => node.nodeType === Node.ELEMENT_NODE && (node.matches?.('[data-preview-ui="true"]') || node.matches?.('.gyuhwan-preview-badge'))))) scheduleBindings();
  }).observe(document.body, { childList: true, subtree: true, characterData: true });
  document.addEventListener('dblclick', event => {
    if (!payload.editMode || event.target.closest?.('[data-preview-ui="true"]') || event.target.matches?.('input,select,textarea,option')) return;
    const target = fieldsForEvent(event);
    if (target.bindings.length) { event.preventDefault(); event.stopImmediatePropagation(); requestEdit(target.bindings, target.element); }
  }, true);
  document.addEventListener('keydown', event => {
    if (!payload.editMode || !['F2', 'Enter'].includes(event.key) || event.target.matches?.('input,select,textarea,option')) return;
    if (event.key === 'Enter' && event.target.matches?.('button,summary,label')) return;
    const target = fieldsForEvent(event);
    if (target.bindings.length) { event.preventDefault(); event.stopImmediatePropagation(); requestEdit(target.bindings, target.element); }
  }, true);
  document.addEventListener('pointerup', event => {
    if (!payload.editMode || event.pointerType !== 'touch' || event.target.closest?.('[data-preview-ui="true"],button,input,select,textarea,summary,label')) return;
    const target = fieldsForEvent(event);
    if (target.bindings.length) { event.preventDefault(); touchEditTarget = target.element; requestEdit(target.bindings, target.element); }
  }, true);
  let tooltip, hoveredElement;
  document.addEventListener('pointerover', event => {
    tooltip?.remove(); tooltip = null;
    if (hoveredElement) delete hoveredElement.dataset.previewHovered;
    hoveredElement = null;
    if (!payload.editMode || event.pointerType === 'touch' || event.target.closest?.('[data-preview-ui="true"]')) return;
    const target = fieldsForEvent(event);
    if (!target.bindings.length || target.element.matches('img,video,iframe')) return;
    hoveredElement = target.element; hoveredElement.dataset.previewHovered = 'true';
    const host = target.element.closest('dialog') || document.body, box = target.element.getBoundingClientRect(), origin = host === document.body ? {left:-scrollX,top:-scrollY} : host.getBoundingClientRect();
    tooltip = document.createElement('div'); tooltip.className = 'gyuhwan-edit-tooltip';
    const navigationAction = target.element.closest('a[href],button[data-content-node][data-product],button[data-content-node][data-upcoming-duo],button[data-content-node][data-net-carrier],#member-open,#copy-inquiry,#quote-copy-text,#quote-save-jpg');
    tooltip.textContent = `${target.bindings.some(binding => payload.modifiedFieldIds?.includes(binding.field.id)) ? '변경됨 · ' : ''}${navigationAction ? '클릭하여 수정' : '더블클릭하여 수정'}`;
    tooltip.style.left = `${Math.max(0, box.left - origin.left)}px`; tooltip.style.top = `${Math.max(0, box.top - origin.top - 26)}px`;
    editLayer(host).append(tooltip);
  });
  document.addEventListener('pointerout', () => { tooltip?.remove(); tooltip = null; if (hoveredElement) delete hoveredElement.dataset.previewHovered; hoveredElement = null; });

  function focusSelectedField(fieldId) {
    for (const [element, bindings] of editBindings) if (bindings.some(binding => binding.field.id === fieldId)) {
      (mediaButtons.find(item => item.element === element)?.button || element).focus({ preventScroll: true });
      return;
    }
  }

  function selectPreviewItem() {
    // 관리자 목록에서 필드를 고르면 필요한 모델·통신사·TV 조건을 실제 화면에서 선택해 그 위치로 이동한다.
    const section = payload.sections?.find(item => item.id === payload.sectionId);
    const field = section?.fields?.find(item => item.id === payload.focusFieldId);
    const resource = field?.resource;
    let variantDialog;
    if (resource === 'products.json' || resource === 'catalog.json') {
      const index = Number(field.pointer?.split('/')[1]);
      const item = loadedContentResource(resource)?.[index];
      const product = resource === 'products.json' ? item : loadedContentResource('products.json')?.find(product => product.id === item?.id);
      const group = loadedContentResource('catalog.json')?.find(group => group.id === product?.id || Object.values(group.variants || {}).includes(product?.id));
      const carrier = field.pointer?.split('/')[2] === 'carrierImages' ? field.pointer.split('/')[3].replaceAll('~1', '/').replaceAll('~0', '~') : null;
      document.querySelector('[data-brand="전체"]')?.click();
      if (carrier) document.querySelector(`[data-carrier="${CSS.escape(carrier)}"]`)?.click();
      if (item) {
        const search = document.querySelector('#model-search');
        if (search) { search.value = group?.name || item.name || product?.name || product?.code || ''; search.dispatchEvent(new Event('input', { bubbles: true })); }
        if (resource === 'products.json' && group && product.id !== group.id) {
          document.querySelector(`#product-grid [data-product="${group.id}"]`)?.click();
          const capacity = Object.entries(group.variants || {}).find(([, id]) => id === product.id)?.[0];
          const option = capacity && document.querySelector(`#purchase-dialog input[name="capacity"][value="${CSS.escape(capacity)}"]`);
          if (option) { option.checked = true; option.dispatchEvent(new Event('change', { bubbles: true })); }
          variantDialog = document.querySelector('#purchase-dialog');
        }
      }
    }
    if (resource === 'upcoming-duo.json') { const search = document.querySelector('#model-search'); if (search) { search.value = loadedContentResource(resource)?.name || ''; search.dispatchEvent(new Event('input', { bubbles: true })); } }
    if (resource === 'internet-plans.json' || resource === 'installation-rules.json') {
      const carrier = field.pointer?.split('/')[1]?.replaceAll('~1', '/').replaceAll('~0', '~');
      const select = document.querySelector('#net-carrier');
      if (select && carrier && [...select.options].some(option => option.value === carrier)) {
        select.value = carrier; select.dispatchEvent(new Event('change', { bubbles: true }));
        const parts = field.pointer.split('/');
        const speed = document.querySelector('#net-speed'), television = document.querySelector('#net-tv');
        if (['internet', 'withTV'].includes(parts[2]) && speed) { speed.value = parts[3]; if (television) television.value = parts[2] === 'withTV' ? '1' : '0'; speed.dispatchEvent(new Event('change', { bubbles: true })); }
        if (parts[2] === 'tv' && television) { television.value = parts[3]; television.dispatchEvent(new Event('change', { bubbles: true })); }
        if (parts[2] === 'promotion') document.querySelectorAll('#net-result details').forEach(details => { details.open = true; });
      }
    }
    let node = field?.node ? document.querySelector(`[data-content-node="${CSS.escape(field.node)}"]`) : null;
    let dialog = node?.closest('dialog') || variantDialog;
    if (payload.sectionId === 'notices-data' || payload.sectionId === 'dialogs') dialog ||= document.querySelector('#purchase-dialog');
    if (payload.selection && applyQuoteSelection?.(payload.selection)) dialog = document.querySelector('#purchase-dialog');
    if (dialog) {
      if (dialog.id === 'purchase-dialog' && !dialog.open) document.querySelector('#product-grid [data-product], [data-product="16"]')?.click();
      if (!dialog.open) try { dialog.showModal(); } catch {}
      dialog.dataset.previewRegion = 'shop';
      dialog.dataset.previewSelected = 'true';
      if (!dialog.querySelector('.gyuhwan-preview-badge')) {
        const badge = document.createElement('button');
        badge.type = 'button'; badge.className = 'gyuhwan-preview-badge'; badge.hidden = !!(payload.editMode || payload.visualEditor); badge.textContent = '05 견적·상담 창'; badge.style.cssText = 'top:8px;left:8px;z-index:2';
        badge.addEventListener('click', () => parent.postMessage({ type: 'gyuhwan-select-section', sectionId: 'dialogs' }, location.origin));
        dialog.append(badge);
      }
      if (node) for (let current = node.parentElement; current && current !== dialog; current = current.parentElement) if (current.tagName === 'DETAILS') current.open = true;
    }
    const region = regionForSection(payload.sectionId);
    const target = dialog || document.querySelector(region.selector) || node;
    requestAnimationFrame(() => { (node?.getClientRects().length ? node : target)?.scrollIntoView({ block: 'start', behavior: 'instant' }); positionBadges(); });
  }
  let patchSequence = 0;
  addEventListener('message', async event => {
    // 부모 창과 출처가 모두 일치할 때만 위치 이동·초안 갱신 메시지를 처리한다.
    if (event.origin !== location.origin || event.source !== parent) return;
    if (event.data?.type === 'gyuhwan-preview-locate') {
      payload = { ...payload, sectionId: event.data.sectionId, focusFieldId: event.data.focusFieldId };
      selectPreviewItem();
    } else if (event.data?.type === 'gyuhwan-preview-focus') {
      focusSelectedField(event.data.selectedFieldId || payload.selectedFieldId);
    } else if (event.data?.type === 'gyuhwan-preview-patch' && Array.isArray(event.data.sections)) {
      // 비동기 재계산 중 더 최신 초안이 오면 이전 결과를 버리고 스크롤·펼침 상태를 복원한다.
      const sequence = ++patchSequence, x = scrollX, y = scrollY;
      const scrollElements = [...document.querySelectorAll('dialog[open], dialog[open] .quote-grid, dialog[open] .quote-aside')].map(element => ({element,top:element.scrollTop,left:element.scrollLeft}));
      const expanded = [...document.querySelectorAll('details[open]')].map(element => {
        const container = element.parentElement.closest('[id]');
        return { element, containerId: container?.id, index: container ? [...container.querySelectorAll('details')].indexOf(element) : -1 };
      });
      payload = { ...payload, ...event.data };
      try {
        await controls.applyPatch?.(payload);
        if (sequence !== patchSequence) return;
        for (const item of expanded) {
          const element = item.element.isConnected ? item.element : document.getElementById(item.containerId)?.querySelectorAll('details')[item.index];
          if (element) element.open = true;
        }
        rebuildEditBindings();
        scrollTo({left:x,top:y,behavior:'instant'});
        for (const item of scrollElements) if (item.element.isConnected) {item.element.scrollTop=item.top;item.element.scrollLeft=item.left;}
        schedulePositions();
        parent.postMessage({ type: 'gyuhwan-preview-rendered', sectionId: payload.sectionId, draft: true }, location.origin);
      } catch (error) {
        parent.postMessage({ type: 'gyuhwan-preview-error', message: error.message || '미리보기를 갱신하지 못했습니다.' }, location.origin);
      }
    }
  });
  rebuildEditBindings();
  selectPreviewItem();
  parent.postMessage({ type: 'gyuhwan-preview-rendered', sectionId: payload.sectionId, draft: !!initialPayload }, location.origin);
}
