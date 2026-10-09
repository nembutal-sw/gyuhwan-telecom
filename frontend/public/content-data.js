// 고객 화면의 공개 자료 로더와 관리자 미리보기 초안 저장소다. 자료 파일은 sync-main-page.mjs 재수집 시 갱신될 수 있다.
const editableResources = new Set(['catalog.json', 'products.json', 'addons.json', 'internet-plans.json', 'installation-rules.json', 'purchase-notices.json', 'upcoming-duo.json']);
export const isAdminPreview = location.pathname === '/admin_preview';
let draftPayload = null;
const resources = new Map();
const loaded = new Map();
const originals = new Map();
let previewConfig;
let originalConfig;

export function setPreviewPayload(payload) {
  if (!isAdminPreview) return;
  draftPayload = payload && Array.isArray(payload.sections) ? payload : null;
  resources.clear();
  loaded.clear();
  originals.clear();
  previewConfig = undefined;
  originalConfig = undefined;
}

// 원본 모듈이 이미 잡아 둔 객체 참조를 유지한다. 초안은 브라우저 객체만 바꾸며 저장 API를 호출하지 않는다.
function updateObject(target, source) {
  for (const key of Object.keys(target)) if (!Object.hasOwn(source, key)) delete target[key];
  for (const [key, value] of Object.entries(source)) {
    if (['__proto__', 'constructor', 'prototype'].includes(key)) continue;
    if (value && typeof value === 'object' && target[key] && typeof target[key] === 'object' && Array.isArray(value) === Array.isArray(target[key])) updateObject(target[key], value);
    else target[key] = structuredClone(value);
  }
  if (Array.isArray(target)) target.length = source.length;
}

function applyDraft(data, resource) {
  if (draftPayload) for (const section of draftPayload.sections) for (const field of section.fields || []) if (field.resource === resource) applyPointer(data, field);
}

export function patchPreviewPayload(payload) {
  // 초안 전체를 최초 저장본에 다시 적용해 되돌린 필드의 이전 값이 화면에 남지 않게 한다.
  if (!isAdminPreview || !payload || !Array.isArray(payload.sections)) return;
  draftPayload = payload;
  for (const [resource, data] of loaded) {
    const next = structuredClone(originals.get(resource));
    applyDraft(next, resource);
    updateObject(data, next);
  }
  if (previewConfig && originalConfig) {
    const next = structuredClone(originalConfig);
    if (payload.config && typeof payload.config === 'object') next.config = structuredClone(payload.config);
    updateObject(previewConfig, next);
  }
}

function draftValue(field) {
  if (field.kind !== 'number') return String(field.value ?? '');
  // 비워 둔 nullable 숫자는 null(미등록)이고, 잘못된 초안은 undefined로 적용하지 않는다.
  const value = String(field.value ?? '').trim();
  if (!value && field.nullable) return null;
  if (!/^\d+$/.test(value)) return undefined;
  const number = Number(value);
  return Number.isSafeInteger(number) && number <= 100000000 ? number : undefined;
}

function applyPointer(data, field) {
  // 관리자 스키마에 등록된 기존 JSON 경로만 갱신하고 프로토타입 관련 키는 차단한다.
  if (typeof field.pointer !== 'string' || !field.pointer.startsWith('/')) return;
  const keys = field.pointer.slice(1).split('/').map(key => key.replaceAll('~1', '/').replaceAll('~0', '~'));
  if (keys.some(key => ['__proto__', 'constructor', 'prototype'].includes(key))) return;
  let target = data;
  for (const key of keys.slice(0, -1)) {
    if (!target || typeof target !== 'object' || !Object.hasOwn(target, key)) return;
    target = target[key];
  }
  const last = keys.at(-1), value = draftValue(field);
  if (value !== undefined && target && typeof target === 'object' && Object.hasOwn(target, last)) target[last] = value;
}

export function loadContentResource(resource) {
  // 허용 목록의 자료만 공개 API에서 받아 원본 스냅샷과 화면용 객체를 분리해 보관한다.
  if (!editableResources.has(resource)) return Promise.reject(Error('지원하지 않는 홈페이지 자료입니다.'));
  if (!resources.has(resource)) resources.set(resource, (async () => {
    const response = await fetch(`/api/public/content/data/${resource}`, { credentials: 'same-origin', cache: 'no-store' });
    if (!response.ok) throw Error('홈페이지 자료를 불러오지 못했습니다.');
    const data = await response.json();
    if (isAdminPreview) {
      originals.set(resource, structuredClone(data));
      applyDraft(data, resource);
    }
    loaded.set(resource, data);
    return data;
  })());
  return resources.get(resource);
}

export function loadedContentResource(resource) { return loaded.get(resource); }

export async function loadPublicConfig() {
  // 가격표 역시 미리보기에서는 서버 저장본 위에 부모 창이 보낸 초안만 임시 적용한다.
  if (isAdminPreview && previewConfig) return previewConfig;
  const response = await fetch('/api/public/config', { credentials: 'same-origin', cache: 'no-store' });
  const data = await response.json();
  if (!response.ok) throw Error(data?.error || '가격 정보를 불러오지 못했습니다.');
  if (isAdminPreview) {
    originalConfig = structuredClone(data);
    if (draftPayload?.config && typeof draftPayload.config === 'object') data.config = structuredClone(draftPayload.config);
    previewConfig = data;
  }
  return data;
}
