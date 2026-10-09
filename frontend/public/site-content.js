// 저장된 문구·이미지·영상 URL을 원본 고객 페이지 DOM에 적용하고 편집용 iframe을 연결한다.
// sync-main-page.mjs가 고객 페이지를 다시 수집할 수 있으므로 연결 코드를 갱신할 때 재수집 결과도 확인한다.
import { isAdminPreview, loadContentResource, loadPublicConfig, setPreviewPayload, patchPreviewPayload } from './content-data.js';
import { receivePreviewPayload, installPreviewLocations } from './preview.js';

const attributes = new Set(['src', 'href', 'alt', 'title', 'placeholder', 'data-product']);

function videoUrl(value) {
  // HTTPS YouTube·Vimeo 주소만 지정된 임베드 호스트로 바꿔 iframe에 넣는다.
  try {
    const url = new URL(String(value).trim());
    if (url.protocol !== 'https:' || url.username || url.password || (url.port && url.port !== '443')) return null;
    const host = url.hostname.toLowerCase();
    const youtube = ['youtube.com', 'www.youtube.com', 'm.youtube.com', 'youtube-nocookie.com', 'www.youtube-nocookie.com'];
    let id = null;
    if (host === 'youtu.be') id = /^\/([A-Za-z0-9_-]{11})\/?$/.exec(url.pathname)?.[1];
    else if (youtube.includes(host)) {
      if (url.pathname === '/watch') id = url.searchParams.get('v');
      else id = /^\/(?:embed|shorts|live)\/([A-Za-z0-9_-]{11})\/?$/.exec(url.pathname)?.[1];
    }
    if (id && /^[A-Za-z0-9_-]{11}$/.test(id)) return `https://www.youtube-nocookie.com/embed/${id}?autoplay=1&mute=1&loop=1&playlist=${id}&playsinline=1`;
    let vimeo = null;
    if (['vimeo.com', 'www.vimeo.com'].includes(host)) vimeo = /^\/(\d+)(?:\/([A-Za-z0-9]+))?\/?$/.exec(url.pathname);
    else if (host === 'player.vimeo.com') vimeo = /^\/video\/(\d+)\/?$/.exec(url.pathname);
    if (vimeo) {
      const hash = vimeo[2] || url.searchParams.get('h');
      if (hash && !/^[A-Za-z0-9]+$/.test(hash)) return null;
      const embed = new URL(`https://player.vimeo.com/video/${vimeo[1]}`);
      if (hash) embed.searchParams.set('h', hash);
      return embed.href;
    }
    return null;
  } catch { return null; }
}

export function applyContent(sections) {
  // 텍스트 노드나 허용된 속성만 수정한다. HTML 문자열은 삽입하지 않는다.
  for (const section of sections || []) {
    for (const field of section.fields || []) {
      if (typeof field.node !== 'string' || typeof field.value !== 'string') continue;
      const element = document.querySelector(`[data-content-node="${CSS.escape(field.node)}"]`);
      if (!element) continue;
      if (field.attribute) {
        if (!attributes.has(field.attribute)) continue;
        const value = field.kind === 'video' ? videoUrl(field.value) : field.value;
        if (field.kind === 'video' && value === null) { element.removeAttribute(field.attribute); continue; }
        if (value !== null && (!['src', 'href'].includes(field.attribute) || !/^\s*(?:javascript:|data:|blob:)/i.test(value)) && element.getAttribute(field.attribute) !== value) element.setAttribute(field.attribute, value);
      } else if (Number.isInteger(field.textIndex)) {
        const text = element.childNodes[field.textIndex];
        if (text?.nodeType === Node.TEXT_NODE && text.data !== field.value) text.data = field.value;
      }
    }
  }
}

// 저장본을 읽는 동안 같은 출처의 관리자 부모 창에서 미리보기 초안을 기다린다.
const previewPayloadPromise = receivePreviewPayload();
let savedContent;
try {
  const response = await fetch('/api/public/content', { cache: 'no-store' });
  if (response.ok) savedContent = await response.json();
} catch (error) {
  console.warn('저장된 홈페이지 내용을 불러오지 못해 기본 화면을 표시합니다.', error);
}

const previewPayload = await previewPayloadPromise;
setPreviewPayload(previewPayload);
applyContent(previewPayload?.sections || savedContent?.sections);
if (isAdminPreview) {
  const resources = [...new Set((previewPayload?.sections || savedContent?.sections || []).flatMap(section => section.fields || []).map(field => field.resource).filter(Boolean))];
  await Promise.allSettled([...resources.map(loadContentResource), loadPublicConfig()]);
}

// 정적 DOM을 적용한 뒤 원본 모델·견적·인터넷 동작을 불러온다. 동적으로 생성된 영역의 편집 연결도 그 뒤에 만든다.
const [offlineModule, internetModule] = await Promise.all([import('/offline.js'), import('/internet.js')]);
if (isAdminPreview) {
  await offlineModule.publicDataReady;
  let currentPayload = previewPayload;
  applyContent(previewPayload?.sections || savedContent?.sections);
  installPreviewLocations(previewPayload, offlineModule.applyAdminPreviewSelection, {
    quoteContext: offlineModule.adminPreviewContext,
    internetContext: internetModule.adminInternetPreviewContext,
    reapplyStatic: applyContent,
    async applyPatch(next) {
      // 관리자 입력은 미리보기 메모리만 갱신한다. 동적 자료·가격이 바뀐 경우 계산 화면을 다시 렌더링한다.
      const prior = currentPayload;
      currentPayload = next;
      const resourceValues = value => JSON.stringify((value?.sections || []).flatMap(section => section.fields || []).filter(field => field.resource).map(field => [field.resource,field.pointer,field.value]));
      const resourcesChanged = resourceValues(prior) !== resourceValues(next);
      const configChanged = JSON.stringify(prior?.config) !== JSON.stringify(next.config);
      patchPreviewPayload(next);
      if (resourcesChanged || configChanged) {
        await offlineModule.refreshAdminPreview?.();
        internetModule.refreshAdminInternetPreview?.();
      }
      applyContent(next.sections);
    },
  });
}
