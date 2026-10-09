/**
 * 원본 공개 페이지를 로컬 정적 파일로 옮기고 기존 DOM에 편집용 data-content-node를 부여한다.
 * 동시에 SiteContentService의 허용 필드 스키마(site-content-seed.json)를 생성한다.
 * 실행하면 수집 대상 파일과 스키마를 갱신한다. 기존 운영 필드 ID와의 대응을 검토한 뒤 실행한다.
 */
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { createHash } from 'node:crypto';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const publicRoot = path.join(root, 'frontend', 'public');
const origin = 'https://gyuhwan-telecom.destiny7845120.chatgpt.site';
const source = new URL(origin);
const visited = new Set();
const manifest = [];
const unsupported = new Set();
const pending = [];
const titleMap = {
  header: '상단 메뉴', home: '아이폰 듀오 메인 배너', 'preorder-video': '사전예약 영상',
  best: '인기 휴대폰 · 특가', shop: '휴대폰 견적 안내', internet: '인터넷 · TV', contact: '상담 안내',
  store: '매장 정보', 'store-map': '매장 지도', faq: '자주 묻는 질문', addons: '부가서비스 안내',
  'site-qr': '홈페이지 QR', footer: '하단 정보', dialogs: '견적 · 상담 창 안내', other: '기타 안내',
};
const dynamicIds = new Set([
  'special-prices', 'selection-label', 'result-count', 'product-grid', 'dialog-product', 'capacity-options',
  'capacity-help', 'color-options', 'model-color-source', 'addon-section-heading', 'addon-products',
  'quote-selection', 'official-reference', 'quote-values', 'quote-warning', 'copy-status', 'consultation-status',
  'quote-image-results', 'addon-guide', 'net-result', 'net-explanation', 'net-extra-tv', 'net-bundle', 'net-sources',
  'net-tv', 'discount-help', 'contract-details', 'plan-details', 'addon-details', 'penalty-details',
  'channel-pending', 'common-discount-label', 'model-switch', 'duo-model-switch',
]);
const voidTags = new Set(['area', 'base', 'br', 'col', 'embed', 'hr', 'img', 'input', 'link', 'meta', 'param', 'source', 'track', 'wbr']);

async function fetchBytes(url, options) {
  const response = await fetch(url, options);
  if (!response.ok) throw new Error(`${response.status} ${url}`);
  return Buffer.from(await response.arrayBuffer());
}
async function save(relative, bytes) {
  const destination = path.resolve(publicRoot, relative.replace(/^\/+/, ''));
  if (!destination.startsWith(publicRoot + path.sep)) throw new Error(`Unsafe asset path: ${relative}`);
  await mkdir(path.dirname(destination), { recursive: true });
  await writeFile(destination, bytes);
}
function dependencies(text, base, css = false) {
  const refs = [];
  if (css) {
    for (const match of text.matchAll(/(?:url\(\s*['"]?([^'"\s)]+)['"]?\s*\)|@import\s+['"]([^'"]+)['"])/g)) refs.push(match[1] || match[2]);
  } else {
    for (const match of text.matchAll(/(?:\bfrom\s*|\bimport\s*|\bfetch\(\s*)['"]([^'"]+)['"]/g)) refs.push(match[1]);
  }
  for (const ref of refs) {
    if (/^(data:|blob:|#)/.test(ref)) continue;
    const url = new URL(ref, base);
    if (url.origin !== source.origin) continue;
    if (url.pathname.startsWith('/api/')) { unsupported.add(url.pathname); continue; }
    enqueue(url);
  }
}
function enqueue(url, relative = url.pathname) {
  const key = url.href;
  if (visited.has(key)) return;
  visited.add(key);
  pending.push({ url, relative: decodeURIComponent(relative.replace(/^\/+/, '')) });
}
async function downloadAsset({ url, relative }) {
  // These authored local modules bind content and preview drafts; remote snapshots do not replace them.
  if (['site-content.js', 'preview.js', 'content-data.js', 'content-regions.js', 'preview-bindings.js'].includes(relative)) return;
  let bytes = await fetchBytes(url);
  let text;
  if (/\.(?:css|js|mjs|json|html)$/.test(relative)) text = bytes.toString('utf8');
  if (/\.css$/.test(relative)) dependencies(text, url, true);
  if (/\.(?:js|mjs)$/.test(relative)) dependencies(text, url);
  if (relative === 'offline.js') {
    text = text.replace("fetch('/api/config'", "fetch('/api/public/config'");
    // The section editor owns the displayed links and their labels. Keep the quote flow using
    // the edited header link without rewriting every static contact element after binding.
    text = text.replace('let channelUrl=null;', "let channelUrl=document.querySelector('[data-channel-link]')?.getAttribute('href')||null;");
    text = text.replace(/\$\$\('\[data-channel-link\]'\)\.forEach\(a=>\{[^\n]+\}\);/, '/* Static contact links and labels are applied by site-content.js. */');
    text = text.replace(/^fetch\('\/contact-channel\.json'[^\n]+$/m, 'applyChannel();');
    text = text.replace("$('#admin-link').hidden=!data.canEdit;", "$('#admin-link').hidden=true;");
  }
  if (text !== undefined) bytes = Buffer.from(text, 'utf8');
  await save(relative, bytes);
  manifest.push({ source: url.href, file: relative, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') });
}

function decode(text) {
  const named = { amp: '&', lt: '<', gt: '>', quot: '"', apos: "'", nbsp: '\u00a0', copy: '©', reg: '®', ndash: '–', mdash: '—' };
  return text.replace(/&(#x[\da-f]+|#\d+|[a-z]+);/gi, (full, entity) => {
    if (entity[0] !== '#') return named[entity] ?? full;
    const value = entity[1].toLowerCase() === 'x' ? parseInt(entity.slice(2), 16) : parseInt(entity.slice(1), 10);
    return value > 0 && value <= 0x10ffff ? String.fromCodePoint(value) : '\ufffd';
  });
}
function attributes(tag) {
  const attrs = {};
  const inside = tag.replace(/^<\s*[\w:-]+/, '').replace(/\/?\s*>$/, '');
  for (const match of inside.matchAll(/([^\s=]+)(?:\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s"'=<>`]+)))?/g)) {
    attrs[match[1].toLowerCase()] = decode(match[2] ?? match[3] ?? match[4] ?? '');
  }
  return attrs;
}
// DOM을 다시 직렬화하지 않고 원본 위치를 기록해 기존 문구·태그·스타일을 보존한다.
function parse(html) {
  const document = { tag: '#document', children: [], parent: null, attrs: {} };
  const stack = [document];
  const elements = [];
  let offset = 0;
  while (offset < html.length) {
    const current = stack.at(-1);
    if (html[offset] !== '<') {
      const next = html.indexOf('<', offset);
      const end = next === -1 ? html.length : next;
      const text = decode(html.slice(offset, end));
      const previous = current.children.at(-1);
      if (previous?.type === 'text') previous.value += text;
      else current.children.push({ type: 'text', value: text });
      offset = end;
      continue;
    }
    if (html.startsWith('<!--', offset)) {
      const commentEnd = html.indexOf('-->', offset + 4);
      current.children.push({ type: 'comment' });
      offset = commentEnd < 0 ? html.length : commentEnd + 3;
      continue;
    }
    let quote = null;
    let end = offset + 1;
    for (; end < html.length; end++) {
      const character = html[end];
      if (quote) { if (character === quote) quote = null; }
      else if (character === '"' || character === "'") quote = character;
      else if (character === '>') break;
    }
    const token = html.slice(offset, end + 1);
    const closing = /^<\s*\/\s*([\w:-]+)/.exec(token);
    const opening = /^<\s*([\w:-]+)/.exec(token);
    if (closing) {
      const tag = closing[1].toLowerCase();
      for (let index = stack.length - 1; index > 0; index--) {
        if (stack[index].tag === tag) { stack.length = index; break; }
      }
    } else if (opening) {
      const tag = opening[1].toLowerCase();
      const element = { tag, attrs: attributes(token), parent: current, children: [], type: 'element', end, token };
      current.children.push(element);
      elements.push(element);
      if (!voidTags.has(tag) && !/\/\s*>$/.test(token)) stack.push(element);
    }
    offset = end + 1;
  }
  return elements;
}
function ancestors(element) {
  const found = [];
  for (let node = element; node; node = node.parent) found.push(node);
  return found;
}
function sectionId(element) {
  const lineage = ancestors(element);
  if (lineage.some(node => node.tag === 'dialog')) return 'dialogs';
  for (const node of lineage) {
    if (node.tag === 'header') return 'header';
    if (node.tag === 'footer') return 'footer';
    if (Object.hasOwn(titleMap, node.attrs.id)) return node.attrs.id;
    if (node.attrs.id === 'store-map-slot' || (node.attrs.class || '').split(/\s+/).includes('store-map')) return 'store-map';
    if ((node.attrs.class || '').split(/\s+/).includes('preorder-video')) return 'preorder-video';
    if ((node.attrs.class || '').split(/\s+/).includes('faq')) return 'faq';
    if ((node.attrs.class || '').split(/\s+/).includes('site-qr')) return 'site-qr';
  }
  return 'other';
}
function label(element, kind, index) {
  const type = kind === 'image' ? '이미지' : kind === 'video' ? '영상 주소' : kind === 'url' ? '링크 주소'
    : kind === 'product' ? '견적 모델 ID' : /^h[1-6]$/.test(element.tag) ? '제목'
      : element.tag === 'button' || element.tag === 'a' ? '버튼 · 링크 문구'
        : element.tag === 'small' ? '보조 문구' : '문구';
  const context = element.attrs.alt || element.children.filter(child => child.type === 'text').map(child => child.value.trim()).join(' ').slice(0, 50);
  return `${type} ${index}${context ? ` · ${context}` : ''}`;
}
// TextNode와 허용 속성만 편집 필드로 등록한다. 레이아웃·글꼴·스크립트는 편집 대상이 아니다.
function markContent(html) {
  const sections = new Map();
  const edits = [];
  let nodeCount = 0;
  let fieldCount = 0;
  for (const element of parse(html)) {
    const lineage = ancestors(element);
    if (!lineage.some(node => ['header', 'main', 'footer', 'dialog'].includes(node.tag))
      && !lineage.some(node => ['site-qr', 'cert-link', 'mobile-bar'].some(className => (node.attrs.class || '').split(/\s+/).includes(className)))) continue;
    if (lineage.some(node => ['script', 'style', 'svg', 'noscript', 'textarea'].includes(node.tag)
      || dynamicIds.has(node.attrs.id))) continue;
    const candidates = [];
    element.children.forEach((child, textIndex) => {
      if (child.type === 'text' && child.value.trim()) candidates.push({ kind: 'text', value: child.value, textIndex });
    });
    if (element.tag === 'img' && element.attrs.src) {
      candidates.push({ kind: 'image', value: element.attrs.src, attribute: 'src' });
      candidates.push({ kind: 'text', value: element.attrs.alt || '', attribute: 'alt', description: '이미지 설명' });
    }
    if (element.tag === 'iframe' && element.attrs.src) {
      candidates.push({ kind: 'video', value: element.attrs.src, attribute: 'src' });
      candidates.push({ kind: 'text', value: element.attrs.title || '', attribute: 'title', description: '영상 설명' });
    }
    if (element.tag === 'a' && element.attrs.href && !/^(#|javascript:|data:|blob:)/i.test(element.attrs.href)) candidates.push({ kind: 'url', value: element.attrs.href, attribute: 'href' });
    if (element.attrs.placeholder !== undefined) candidates.push({ kind: 'text', value: element.attrs.placeholder, attribute: 'placeholder', description: '입력 안내' });
    if (element.attrs['data-product'] !== undefined) candidates.push({ kind: 'product', value: element.attrs['data-product'], attribute: 'data-product' });
    if (!candidates.length) continue;
    const group = sectionId(element);
    if (!sections.has(group)) sections.set(group, { id: group, title: titleMap[group], fields: [] });
    const node = `content-${String(++nodeCount).padStart(4, '0')}`;
    edits.push({ offset: element.end, value: ` data-content-node="${node}"` });
    for (const candidate of candidates) {
      const id = `field-${String(++fieldCount).padStart(4, '0')}`;
      sections.get(group).fields.push({ id, label: candidate.description || label(element, candidate.kind, sections.get(group).fields.length + 1), node, ...candidate });
    }
  }
  for (const edit of edits.sort((left, right) => right.offset - left.offset)) html = html.slice(0, edit.offset) + edit.value + html.slice(edit.offset);
  return { html, sections: [...sections.values()], nodeCount, fieldCount };
}

async function downloadFonts() {
  const cssPath = path.join(publicRoot, 'style.css');
  let css = await readFile(cssPath, 'utf8');
  const importUrl = /@import\s+url\(['"]?(https:\/\/fonts\.googleapis\.com\/[^'"\s)]+)['"]?\);/.exec(css)?.[1];
  if (!importUrl) return;
  let fontCss = (await fetchBytes(importUrl, { headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36' } })).toString('utf8');
  const urls = [...new Set([...fontCss.matchAll(/url\((https:\/\/fonts\.gstatic\.com\/[^)]+)\)/g)].map(match => match[1]))];
  const queue = [...urls];
  await Promise.all(Array.from({ length: 6 }, async () => {
    while (queue.length) {
      const url = queue.shift();
      const extension = path.extname(new URL(url).pathname) || '.woff2';
      const relative = `fonts/${createHash('sha256').update(url).digest('hex').slice(0, 20)}${extension}`;
      const bytes = await fetchBytes(url);
      await save(relative, bytes);
      fontCss = fontCss.split(url).join(`/${relative}`);
      manifest.push({ source: url, file: relative, bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex') });
    }
  }));
  await save('fonts/noto-sans-kr.css', Buffer.from(fontCss));
  manifest.push({ source: importUrl, file: 'fonts/noto-sans-kr.css', bytes: Buffer.byteLength(fontCss), sha256: createHash('sha256').update(fontCss).digest('hex') });
  css = css.replace(importUrl, '/fonts/noto-sans-kr.css');
  await save('style.css', Buffer.from(css));
  Object.assign(manifest.find(asset => asset.file === 'style.css'), { bytes: Buffer.byteLength(css), sha256: createHash('sha256').update(css).digest('hex') });
}

// 동적으로 렌더링되는 모델/인터넷 자료에는 DOM ID 대신 resource와 JSON Pointer를 연결한다.
async function editableData(marked) {
  let count = marked.fieldCount;
  const sections = new Map();
  const resources = [];
  const titles = { catalog: '휴대폰 카드 모델명', products: '휴대폰 모델 · 사진', addons: '부가서비스 상품', internet: '인터넷 · TV 상품과 요금', installation: '인터넷 · TV 설치 안내', notices: '통신사 구매 · 유지 안내', upcoming: '아이폰 듀오 카드' };
  const labels = { name: '모델 · 상품명', code: '모델코드', image: '대표 사진', imageAlt: '사진 설명', carrierImages: '통신사 사진', carrierCodes: '통신사 모델코드', officialName: '공식 상품명', fee: '월 요금 (원)', period: '유지기간', description: '상품 설명', note: '보조 안내', source: '공식 안내 주소', sources: '공식 안내', extraSources: '추가 안내', url: '주소', checkedAt: '안내 확인일', internet: '인터넷 요금 (원)', withTV: 'TV 결합 인터넷 요금 (원)', tv: 'TV 상품', bundle: '가족 결합 안내', promotion: '행사 안내', internetOnly: '인터넷 단독 설치비 (원)', internetWithTV: 'TV 결합 인터넷 설치비 (원)', tvFirst: '첫 TV 설치비 (원)', tvAdditional: '추가 TV 설치비 (원)', plan: '요금제 유지 안내', choiceSource: '선택약정 안내 주소', addonGuideText: '부가서비스 공통 안내', addonReturnNotice: '부가서비스 반환 안내', carrierPolicies: '통신사 안내' };
  const pointerPart = value => String(value).replaceAll('~', '~0').replaceAll('/', '~1');
  function field(group, resource, pointer, value, kind, label, nullable = false) {
    if (!sections.has(group)) sections.set(group, { id: `${group}-data`, title: titles[group], fields: [] });
    sections.get(group).fields.push({ id: `field-${String(++count).padStart(4, '0')}`, label, kind,
      value: value == null ? '' : String(value), resource, pointer, ...(nullable ? { nullable: true } : {}) });
  }
  function walk(group, resource, value, pointer = '', label = '', skip = new Set()) {
    if (value && typeof value === 'object') {
      for (const [key, child] of Object.entries(value)) {
        if (skip.has(key)) continue;
        walk(group, resource, child, `${pointer}/${pointerPart(key)}`, `${label}${label ? ' · ' : ''}${labels[key] || key}`, skip);
      }
    } else if (typeof value === 'string') field(group, resource, pointer, value, /^(https?:|tel:|mailto:)/.test(value) ? 'url' : 'text', label);
    else if (typeof value === 'number' || value === null) field(group, resource, pointer, value, 'number', label, value === null);
  }
  async function json(resource, data) {
    await save(resource, Buffer.from(JSON.stringify(data, null, 2) + '\n'));
    resources.push(resource);
  }
  const products = JSON.parse(await readFile(path.join(publicRoot, 'products.json'), 'utf8'));
  products.forEach((product, index) => {
    product.imageAlt ??= '';
    product.carrierImages ??= {};
    product.carrierCodes ??= {};
    const title = `[${product.id}] ${product.name}`;
    for (const key of ['name', 'code', 'image', 'imageAlt']) field('products', 'products.json', `/${index}/${key}`, product[key], key === 'image' ? 'image' : 'text', `${title} · ${labels[key]}`, key === 'image');
    for (const carrier of ['SKT', 'KT', 'LG U+']) {
      product.carrierImages[carrier] ??= null;
      product.carrierCodes[carrier] ??= product.code || '';
      field('products', 'products.json', `/${index}/carrierImages/${pointerPart(carrier)}`, product.carrierImages[carrier], 'image', `${title} · ${carrier} 사진`, true);
      field('products', 'products.json', `/${index}/carrierCodes/${pointerPart(carrier)}`, product.carrierCodes[carrier], 'text', `${title} · ${carrier} 모델코드`);
    }
  });
  await json('products.json', products);
  const catalog = JSON.parse(await readFile(path.join(publicRoot, 'catalog.json'), 'utf8'));
  catalog.forEach((model, index) => field('catalog', 'catalog.json', `/${index}/name`, model.name, 'text', `[${model.id}] ${model.name} · 카드 모델명`));
  resources.push('catalog.json');
  const addons = JSON.parse(await readFile(path.join(publicRoot, 'addons.json'), 'utf8'));
  walk('addons', 'addons.json', addons, '', '', new Set(['id', 'from', 'insurance', 'sourceType']));
  resources.push('addons.json');
  const internetModule = await import(pathToFileURL(path.join(publicRoot, 'internet-core.mjs')).href + `?snapshot=${Date.now()}`);
  const installationModule = await import(pathToFileURL(path.join(publicRoot, 'installation-core.mjs')).href + `?snapshot=${Date.now()}`);
  const purchaseModule = await import(pathToFileURL(path.join(publicRoot, 'purchase-policy.mjs')).href + `?snapshot=${Date.now()}`);
  const addonModule = await import(pathToFileURL(path.join(publicRoot, 'store-addon-policy.mjs')).href + `?snapshot=${Date.now()}`);
  const notices = { carrierPolicies: purchaseModule.carrierPolicies, addonGuideText: addonModule.addonGuideText,
    addonReturnNotice: '유지기간 내 해지 및 미유치 시, 판매점지원금 일부를 반환해야 해요.' };
  for (const [group, resource, data] of [['internet', 'internet-plans.json', internetModule.plans], ['installation', 'installation-rules.json', installationModule.installationRules], ['notices', 'purchase-notices.json', notices]]) {
    await json(resource, data);
    walk(group, resource, data);
  }
  const upcoming = { id: 'iphone-duo-upcoming', name: '아이폰 듀오', brand: 'Apple', network: '준비중', code: '판매 조건 준비중', image: 'official/iphone-duo-colors.jpg', upcoming: true };
  await json('upcoming-duo.json', upcoming);
  for (const key of ['name', 'code', 'image']) field('upcoming', 'upcoming-duo.json', `/${key}`, upcoming[key], key === 'image' ? 'image' : 'text', labels[key]);
  for (const [filename, binding, resource, property] of [['internet-core.mjs', 'plans', 'internet-plans.json'], ['installation-core.mjs', 'installationRules', 'installation-rules.json'], ['purchase-policy.mjs', 'carrierPolicies', 'purchase-notices.json', 'carrierPolicies']]) {
    let js = await readFile(path.join(publicRoot, filename), 'utf8');
    js = js.replace(`export const ${binding}=`, `const default${binding}=`);
    const bindingCode = `export const ${binding}=await fetch('/api/public/content/data/${resource}',{cache:'no-store'}).then(r=>{if(!r.ok)throw Error();return r.json();}).then(v=>${property ? `v.${property}` : 'v'}).catch(()=>default${binding});\n`;
    js = js.replace('export function ', bindingCode + 'export function ');
    await save(filename, Buffer.from(js));
  }
  let addonJs = await readFile(path.join(publicRoot, 'store-addon-policy.mjs'), 'utf8');
  addonJs = addonJs.replace('export const addonGuideText=', 'const defaultAddonGuideText=');
  addonJs = addonJs.replace('export const addonRevision=', "export const addonGuideText=await fetch('/api/public/content/data/purchase-notices.json',{cache:'no-store'}).then(r=>{if(!r.ok)throw Error();return r.json();}).then(v=>v.addonGuideText).catch(()=>defaultAddonGuideText);\nexport const addonRevision=");
  await save('store-addon-policy.mjs', Buffer.from(addonJs));
  let offline = await readFile(path.join(publicRoot, 'offline.js'), 'utf8');
  for (const resource of ['catalog.json', 'products.json', 'addons.json']) offline = offline.replaceAll(`fetch('/${resource}'`, `fetch('/api/public/content/data/${resource}'`);
  offline = offline.replace(/const upcomingDuo=\{[^\n]+\};/, `const upcomingDuo=await fetch('/api/public/content/data/upcoming-duo.json',{cache:'no-store'}).then(r=>{if(!r.ok)throw Error();return r.json();}).catch(()=>(${JSON.stringify(upcoming)}));`);
  offline = offline.replace('const photo=p=>p.carrierImages?.[state.carrier]||p.image;', "const imgSrc=value=>!value?null:/^(?:https?:|data:|blob:|\\/)/.test(value)?value:'/'+value;\nconst photo=p=>imgSrc(p.carrierImages?.[state.carrier]||p.image);");
  offline = offline.replaceAll('src="/${esc(photo(p))}"', 'src="${esc(photo(p))}"').replaceAll("'/'+photo(p)", 'photo(p)');
  offline = offline.replace('src="/manufacturer-${file}-user.png?v=20261009a"', 'src="${esc(document.querySelector(\'.manufacturer-\'+file)?.getAttribute(\'src\')||\'/manufacturer-\'+file+\'-user.png?v=20261009a\')}"');
  offline = offline.replace("const carrierLogo=c=>c==='SKT'?'/carrier-skt-official.png':c==='KT'?'/carrier-kt-official.png':'/carrier-lgu-transparent.png';", "const carrierLogo=c=>document.querySelector(c==='SKT'?'.net-skt-logo':c==='KT'?'.net-kt-mark img':'.tv-overview .lgu-mark img')?.getAttribute('src')||(c==='SKT'?'/carrier-skt-official.png':c==='KT'?'/carrier-kt-official.png':'/carrier-lgu-transparent.png');");
  offline = offline.replace("const addonReturnNotice='유지기간 내 해지 및 미유치 시, 판매점지원금 일부를 반환해야 해요.';", "const addonReturnNotice=await fetch('/api/public/content/data/purchase-notices.json',{cache:'no-store'}).then(r=>{if(!r.ok)throw Error();return r.json();}).then(v=>v.addonReturnNotice).catch(()=>'유지기간 내 해지 및 미유치 시, 판매점지원금 일부를 반환해야 해요.');");
  offline = offline.replaceAll('${addonReturnNotice}', '${esc(addonReturnNotice)}');
  await save('offline.js', Buffer.from(offline));
  let internet = await readFile(path.join(publicRoot, 'internet.js'), 'utf8');
  internet = "const esc=v=>String(v??'').replace(/[&<>\"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;',\"'\":'&#39;'}[c]));\n" + internet;
  for (const value of ['name', 'i.name', 'rule.promotion', 'u', 'url']) internet = internet.replaceAll('${' + value + '}', '${esc(' + value + ')}');
  await save('internet.js', Buffer.from(internet));
  marked.sections.push(...sections.values());
  marked.fieldCount = count;
  return resources;
}

// 원본 모듈의 자료 조회를 공통 어댑터로 바꿔 저장 전 입력도 같은 계산·렌더링 흐름을 사용한다.
async function enableDraftPreviewData() {
  for (const filename of ['offline.js', 'internet-core.mjs', 'installation-core.mjs', 'purchase-policy.mjs', 'store-addon-policy.mjs']) {
    let js = await readFile(path.join(publicRoot, filename), 'utf8');
    js = js.replace(/fetch\('\/api\/public\/content\/data\/([^']+)'(?:,\{cache:'no-store'\})?\)\.then\(r=>\{if\(!r.ok\)throw Error\(\);return r.json\(\);\}\)/g, (_, resource) => `loadContentResource('${resource}')`);
    js = js.replace(/fetch\('\/api\/public\/content\/data\/([^']+)'\)\.then\(r=>r.json\(\)\)/g, (_, resource) => `loadContentResource('${resource}')`);
    if (filename === 'offline.js') js = js.replace("const r=await fetch('/api/public/config',{cache:'no-store'});const data=await r.json();if(!r.ok)throw new Error(data.error);", 'const data=await loadPublicConfig();');
    if (filename === 'offline.js') js = js.replace(/^Promise\.all\(\[loadContentResource\('catalog\.json'\)/m, "export const publicDataReady=Promise.all([loadContentResource('catalog.json')");
    if (!js.includes("from './content-data.js'")) js = `import {loadContentResource${filename === 'offline.js' ? ',loadPublicConfig,loadedContentResource,isAdminPreview' : filename === 'store-addon-policy.mjs' ? ',loadedContentResource,isAdminPreview' : ''}} from './content-data.js';\n` + js;
    if (filename === 'offline.js') {
      js = js.replace('{excludedAddonModels,addonGuideText}', '{excludedAddonModels,addonGuideText,refreshAdminAddonGuide}').replace('const addonReturnNotice=', 'let addonReturnNotice=');
    }
    if (filename === 'store-addon-policy.mjs') {
      js = js.replace('export const addonGuideText=', 'export let addonGuideText=');
      if (!js.includes('export function refreshAdminAddonGuide')) js += "\nexport function refreshAdminAddonGuide(){if(isAdminPreview)addonGuideText=loadedContentResource('purchase-notices.json')?.addonGuideText??defaultAddonGuideText;}\n";
    }
    if (filename === 'offline.js' && !js.includes('export function applyAdminPreviewSelection')) js += '\n' + await readFile(path.join(root, 'scripts', 'preview-quote-function.js'), 'utf8');
    await save(filename, Buffer.from(js));
  }
  const internetPath = path.join(publicRoot, 'internet.js');
  const internet = await readFile(internetPath, 'utf8');
  if (!internet.includes('export function refreshAdminInternetPreview')) await save('internet.js', Buffer.from(internet + '\n' + await readFile(path.join(root, 'scripts', 'preview-internet-functions.js'), 'utf8')));
  await save('content-regions.js', await readFile(path.join(root, 'frontend', 'content-regions.js')));
}

await mkdir(publicRoot, { recursive: true });
let html = (await fetchBytes(origin + '/')).toString('utf8');
for (const match of html.matchAll(/<(?:link|script)\b[^>]*(?:href|src)=["']([^"']+)["'][^>]*>/gi)) {
  if (/^(data:|https?:)/.test(match[1])) continue;
  enqueue(new URL(match[1], origin + '/'));
}
// The module dependency graph contains all application JSON and imported .mjs files.
enqueue(new URL('/contact-channel.json', origin));
while (pending.length) await downloadAsset(pending.shift());
await downloadFonts();
for (const [urlPath, filename] of [['/privacy', 'privacy.html'], ['/purchase-guide', 'purchase-guide.html']]) {
  let legal = (await fetchBytes(origin + urlPath)).toString('utf8');
  legal = legal.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '');
  await save(filename, Buffer.from(legal));
}
// Remove source deployment/Cloudflare scripts; local modules start only after content binding.
html = html.replace(/<script\b[^>]*>[\s\S]*?<\/script>/gi, '')
  .replaceAll('href="/privacy"', 'href="/privacy.html"')
  .replaceAll('href="/purchase-guide"', 'href="/purchase-guide.html"');
const marked = markContent(html);
const editableResources = await editableData(marked);
await enableDraftPreviewData();
unsupported.delete('/api/config');
await save('site.html', Buffer.from(marked.html.replace('</body>', '<script type="module" src="/site-content.js"></script></body>')));
await writeFile(path.join(root, 'backend', 'src', 'main', 'resources', 'site-content-seed.json'), JSON.stringify({ sections: marked.sections }, null, 2) + '\n');
await save('main-page-source-manifest.json', Buffer.from(JSON.stringify({ source: origin, assets: manifest, sections: marked.sections.map(section => ({ id: section.id, fields: section.fields.length })), apiMappings: { '/api/config': '/api/public/config' }, editableResources, unsupportedApis: [...unsupported], dynamicData: { prices: '/api/public/config', catalog: ['/catalog.json', '/products.json', '/model-colors.json'], officialReferences: ['/official-prices.json', '/verified-retail.json'], addons: '/addons.json', internetPlans: '/internet-core.mjs', purchaseNotices: ['/purchase-policy.mjs', '/store-addon-policy.mjs'] } }, null, 2) + '\n'));
console.log(JSON.stringify({ assets: manifest.length, nodes: marked.nodeCount, fields: marked.fieldCount, sections: marked.sections.map(section => ({ id: section.id, fields: section.fields.length })), unsupportedApis: [...unsupported] }));
