/**
 * 원본 사이트의 HTML·CSS·모듈·모델 자료를 따라가며 이미지 파일 자체를 내려받는다.
 * frontend/public과 backend/static에 같은 바이트를 저장하고 출처·SHA-256 목록을 남긴다.
 * 원본 URL 수집과 실제 파일 갱신 도구이므로 일반 개발 빌드에서 자동 실행하지 않는다.
 */
import fs from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {createHash} from 'node:crypto';

const root = fileURLToPath(new URL('../', import.meta.url));
const source = new URL(process.argv[2] || 'https://gyuhwan-telecom.destiny7845120.chatgpt.site/');
const publicDir = path.join(root, 'frontend', 'public');
const staticDir = path.join(root, 'backend', 'src', 'main', 'resources', 'static');
const assets = new Map();
const visited = new Set();

async function get(url) {
  const response = await fetch(url, {signal: AbortSignal.timeout(30000)});
  if (!response.ok) throw new Error(`${response.status}: ${url}`);
  return response;
}

// 같은 원본의 이미지 경로만 허용하고 출력 경로가 public 디렉터리 밖으로 벗어나지 않게 한다.
function addImage(reference, from) {
  if (!reference || reference.startsWith('data:') || reference.includes('${')) return;
  const url = new URL(reference.replaceAll('&amp;', '&'), from);
  if (url.origin !== source.origin || !/\.(?:png|jpe?g|webp|gif|svg|avif|ico)$/i.test(url.pathname)) return;
  const relative = decodeURIComponent(url.pathname).replace(/^\/+/, '');
  const target = path.resolve(publicDir, relative);
  if (!target.startsWith(publicDir + path.sep)) throw new Error(`Invalid image path: ${url}`);
  const existing = assets.get(relative);
  if (existing) existing.references.add(new URL(from).pathname);
  else assets.set(relative, {url, references: new Set([new URL(from).pathname])});
}

// 순환 import를 visited로 끊고 CSS/import 의존성까지 조사해 화면 밖의 이미지도 수집한다.
async function inspectSource(url) {
  if (url.origin !== source.origin || visited.has(url.href)) return;
  visited.add(url.href);
  const text = await (await get(url)).text();
  const imagePattern = /(?:https?:\/\/[^"'`<>\s(){}]+|(?:\/|\.\.?\/|official\/|products\/)[^"'`<>\s(){}]+)\.(?:png|jpe?g|webp|gif|svg|avif|ico)(?:\?[^"'`<>\s()]*)?/gi;
  for (const match of text.matchAll(imagePattern)) addImage(match[0], url);
  const dependencies = [];
  if (/\.css$/i.test(url.pathname)) {
    for (const match of text.matchAll(/@import\s+(?:url\()?['"]([^'"]+\.css(?:\?[^'"]*)?)['"]/g)) dependencies.push(match[1]);
  } else {
    for (const match of text.matchAll(/(?:\bfrom\s*|\bimport\s*)['"]([^'"]+\.(?:m?js|css)(?:\?[^'"]*)?)['"]/g)) dependencies.push(match[1]);
    for (const match of text.matchAll(/<(?:script|link)\b[^>]*\b(?:src|href)\s*=\s*['"]([^'"]+\.(?:m?js|css)(?:\?[^'"]*)?)['"]/gi)) dependencies.push(match[1]);
  }
  await Promise.all(dependencies.map(reference => inspectSource(new URL(reference, url))));
  return text;
}

const [html, products, modelColors, configResponse] = await Promise.all([
  inspectSource(source),
  get(new URL('/products.json', source)).then(response => response.json()),
  get(new URL('/model-colors.json', source)).then(response => response.json()),
  get(new URL('/api/config', source)).then(response => response.json())
]);

for (const product of products) {
  addImage(product.image, new URL('/products.json', source));
  for (const image of Object.values(product.carrierImages || {})) addImage(image, new URL('/products.json', source));
}
const colors = new Set([
  ...Object.values(modelColors).flatMap(model => model.colors || []),
  ...Object.values(configResponse.config?.colors || {}).flat()
]);
for (const color of colors) addImage(`/color-swatches/${encodeURIComponent(color)}.svg`, new URL('/model-colors.json', source));

// HTTP 성공 응답이 오류 HTML일 수도 있으므로 확장자 대신 파일 시그니처를 검증한다.
function imageType(bytes) {
  if (bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10]))) return 'image/png';
  if (bytes[0] === 255 && bytes[1] === 216 && bytes[2] === 255) return 'image/jpeg';
  if (bytes.toString('ascii', 0, 4) === 'RIFF' && bytes.toString('ascii', 8, 12) === 'WEBP') return 'image/webp';
  if (/^GIF8[79]a$/.test(bytes.toString('ascii', 0, 6))) return 'image/gif';
  if (/<svg(?:\s|>)/i.test(bytes.toString('utf8'))) return 'image/svg+xml';
  if (bytes.length > 12 && bytes.toString('ascii', 4, 8) === 'ftyp' && /avif|avis/.test(bytes.toString('ascii', 8, 40))) return 'image/avif';
  if (bytes[0] === 0 && bytes[1] === 0 && bytes[2] === 1 && bytes[3] === 0) return 'image/x-icon';
  throw new Error('Response is not a recognized image');
}

const records = [];
async function save(relative, bytes, url, references) {
  const type = imageType(bytes);
  for (const directory of [publicDir, staticDir]) {
    const target = path.resolve(directory, relative);
    if (!target.startsWith(directory + path.sep)) throw new Error(`Invalid output path: ${relative}`);
    await fs.mkdir(path.dirname(target), {recursive: true});
    await fs.writeFile(target, bytes);
  }
  records.push({path: relative, sourceUrl: url, bytes: bytes.length, contentType: type, sha256: createHash('sha256').update(bytes).digest('hex'), referencedBy: [...references].sort()});
}

// 다운로드 동시성을 여섯 개로 제한해 원본 서버와 로컬 메모리 사용을 제한한다.
const queue = [...assets.entries()];
await Promise.all(Array.from({length: 6}, async () => {
  while (queue.length) {
    const [relative, asset] = queue.shift();
    const response = await get(asset.url);
    await save(relative, Buffer.from(await response.arrayBuffer()), asset.url.href, asset.references);
  }
}));

const favicon = html.match(/\bhref="(data:image\/svg\+xml,[^"]+)"/i);
if (favicon) await save('favicon.svg', Buffer.from(decodeURIComponent(favicon[1].split(',').slice(1).join(','))), source.href + '#inline-favicon', new Set(['/']));

// Uploaded colour images are stored in config as data URLs; preserve their bytes too.
for (const [model, images] of Object.entries(configResponse.config?.colorImages || {})) {
  for (const [color, data] of Object.entries(images)) {
    const match = data.match(/^data:image\/(png|jpeg|webp);base64,([A-Za-z0-9+/]+={0,2})$/);
    if (!match) throw new Error(`Invalid uploaded image: ${model}/${color}`);
    await save(`uploaded-colors/${model}/${color}.${match[1] === 'jpeg' ? 'jpg' : match[1]}`, Buffer.from(match[2], 'base64'), new URL('/api/config', source).href + `#${model}/${encodeURIComponent(color)}`, new Set(['/api/config']));
  }
}

records.sort((a, b) => a.path.localeCompare(b.path, 'en'));
const manifest = {source: source.href, capturedAt: new Date().toISOString(), count: records.length, totalBytes: records.reduce((sum, record) => sum + record.bytes, 0), assets: records};
await fs.writeFile(path.join(root, 'docs', 'site-images-manifest.json'), JSON.stringify(manifest, null, 2) + '\n');
console.log(`Saved ${manifest.count} image files (${manifest.totalBytes.toLocaleString('en-US')} bytes) to frontend/public and backend static resources.`);
