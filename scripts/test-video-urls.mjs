// 고객 페이지와 관리자 영상 미리보기가 같은 HTTPS 제공자 목록을 사용하는지 검사한다.
// 잘못된 URL은 기존 iframe src까지 제거해야 하며 임의 외부 페이지를 iframe으로 열지 않는다.
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import vm from 'node:vm';

const publicSource = await readFile(new URL('../frontend/public/site-content.js', import.meta.url), 'utf8');
const adminSource = await readFile(new URL('../frontend/src/MainContentEditor.jsx', import.meta.url), 'utf8');
function extract(source, start, end, symbol) {
  const code = source.slice(source.indexOf(start), source.indexOf(end));
  assert(code.startsWith(start), `Missing ${symbol}`);
  const context = vm.createContext({ URL });
  vm.runInContext(`${code}\nthis.result = ${symbol};`, context);
  return context.result;
}
const publicVideo = extract(publicSource, 'function videoUrl(value)', 'export function applyContent', 'videoUrl');
const adminVideo = extract(adminSource, 'function videoSource(value)', 'function ImagePreview', 'videoSource');

const videoId = '3vvJeRHe3sk';
const valid = [
  [`https://youtu.be/${videoId}`, `https://www.youtube-nocookie.com/embed/${videoId}`],
  [`https://youtube.com/watch?v=${videoId}&t=15`, `https://www.youtube-nocookie.com/embed/${videoId}`],
  [`https://m.youtube.com/shorts/${videoId}`, `https://www.youtube-nocookie.com/embed/${videoId}`],
  [`https://www.youtube-nocookie.com/embed/${videoId}?autoplay=1&mute=1&loop=1&playlist=${videoId}&playsinline=1`, `https://www.youtube-nocookie.com/embed/${videoId}`],
  ['https://vimeo.com/12345678/abc123', 'https://player.vimeo.com/video/12345678?h=abc123'],
  ['https://player.vimeo.com/video/12345678?h=abc123', 'https://player.vimeo.com/video/12345678?h=abc123'],
];
for (const [input, expectedPrefix] of valid) {
  assert(publicVideo(input)?.startsWith(expectedPrefix), `Public rejected ${input}`);
  assert.equal(adminVideo(input)?.src, expectedPrefix, `Admin preview disagrees for ${input}`);
}
const rejected = [
  'http://youtube.com/watch?v=3vvJeRHe3sk',
  'https://youtube.com.evil.invalid/watch?v=3vvJeRHe3sk',
  'https://youtube.com:444/embed/3vvJeRHe3sk',
  'https://someone@youtube.com/embed/3vvJeRHe3sk',
  'https://youtube.com/embed/3vvJeRHe3sk/other',
  'https://vimeo.com.evil.invalid/1234',
  'https://player.vimeo.com/video/1234?h=bad%2Fhash',
  'https://example.com/admin_login',
  'https://example.com/demo.mp4',
  'javascript:alert(1)',
  '/local-video.html',
];
for (const input of rejected) {
  assert.equal(publicVideo(input), null, `Public accepted ${input}`);
  assert.equal(adminVideo(input), null, `Admin preview accepted ${input}`);
}
const element = {
  attrs: new Map([['src', 'https://example.com/old-page']]),
  getAttribute(name) { return this.attrs.get(name); },
  setAttribute(name, value) { this.attrs.set(name, value); },
  removeAttribute(name) { this.attrs.delete(name); },
};
const context = vm.createContext({
  URL,
  CSS: { escape: value => value },
  Node: { TEXT_NODE: 3 },
  document: { querySelector: () => element },
});
const publicBindings = publicSource.slice(publicSource.indexOf('const attributes ='), publicSource.indexOf('const previewPayloadPromise')).replace('export function applyContent', 'function applyContent');
vm.runInContext(`${publicBindings}\nthis.apply = applyContent;`, context);
context.apply([{ fields: [{ node: 'video', kind: 'video', attribute: 'src', value: 'https://example.com/admin_login' }] }]);
assert.equal(element.attrs.has('src'), false, 'Invalid video must remove a previous iframe source');
context.apply([{ fields: [{ node: 'video', kind: 'video', attribute: 'src', value: `https://youtu.be/${videoId}` }] }]);
assert(element.attrs.get('src')?.startsWith(`https://www.youtube-nocookie.com/embed/${videoId}`));
console.log(`Video URL allowlist: ${valid.length} accepted and ${rejected.length} rejected in public and admin preview.`);
