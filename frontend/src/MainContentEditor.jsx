// 메인 페이지 직접 편집기. DB 저장본과 입력 초안을 분리하고 실제 고객 화면 iframe에 초안을 미리 보낸다.
import React, { useEffect, useMemo, useRef, useState } from 'react';
import { api } from './api';
import MainPagePreview from './MainPagePreview';
import DraftBar,{useServerDraft} from './useServerDraft.jsx';
import MediaPicker from './MediaPicker.jsx';
import './operations.css';
import SavedDataInfo, {savedAtLabel,historySavedAt} from './SavedDataInfo.jsx';
import { contentRegions, regionForSection, sectionHints } from '../content-regions.js';
import './visual-content-editor.css';

const MAX_IMAGE_BYTES = 1500000;
const MAX_IMAGE_EDGE = 2000;
const fieldValue = field => String(field.value ?? '');
const shortLabel = field => {
  const label = String(field.label || '수정 항목').replace(/^\[\d+\]\s*/, '').replace(/\s+/g, ' ').trim();
  return label.length > 85 ? `${label.slice(0, 85)}…` : label;
};
const dateLabel = savedAtLabel;

function previewSource(value) {
  if (!String(value).trim()) return '';
  try {
    const url = new URL(String(value).trim(), `${window.location.origin}/`);
    return ['http:', 'https:'].includes(url.protocol) ? url.href : '';
  } catch {
    return '';
  }
}

// 영상 미리보기에는 허용된 HTTPS YouTube·Vimeo 주소만 플레이어 URL로 바꾼다. 공개 화면도 같은 범위를 허용한다.
function videoSource(value) {
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
    if (id && /^[A-Za-z0-9_-]{11}$/.test(id)) return { src: `https://www.youtube-nocookie.com/embed/${id}` };
    let vimeo = null;
    if (['vimeo.com', 'www.vimeo.com'].includes(host)) vimeo = /^\/(\d+)(?:\/([A-Za-z0-9]+))?\/?$/.exec(url.pathname);
    else if (host === 'player.vimeo.com') vimeo = /^\/video\/(\d+)\/?$/.exec(url.pathname);
    if (!vimeo) return null;
    const hash = vimeo[2] || url.searchParams.get('h');
    if (hash && !/^[A-Za-z0-9]+$/.test(hash)) return null;
    const embed = new URL(`https://player.vimeo.com/video/${vimeo[1]}`);
    if (hash) embed.searchParams.set('h', hash);
    return { src: embed.href };
  } catch { return null; }
}

function ImagePreview({ value, label }) {
  const src = previewSource(value);
  const [failed, setFailed] = useState(false);
  useEffect(() => setFailed(false), [src]);
  if (!src || failed) return <div className="content-media-empty">{failed ? '이 주소의 이미지를 불러오지 못했습니다.' : '사진 주소를 입력하거나 파일을 올려 주세요.'}</div>;
  return <img className="content-image-preview" src={src} alt={label} loading="lazy" onError={() => setFailed(true)} />;
}

function VideoPreview({ value, label }) {
  const video = videoSource(value);
  if (!video) return <div className="content-media-empty">HTTPS 유튜브·Vimeo 영상 주소를 입력해 주세요.</div>;
  return <iframe className="content-video-preview" src={video.src} title={`${label} 미리보기`} loading="lazy" allow="encrypted-media; picture-in-picture" allowFullScreen referrerPolicy="strict-origin-when-cross-origin" />;
}

async function canvasBlob(canvas, type, quality) {
  return new Promise((resolve, reject) => canvas.toBlob(blob => blob ? resolve(blob) : reject(Error('이미지 크기를 조정하지 못했습니다. 다른 사진을 선택해 주세요.')), type, quality));
}

// 큰 원본 사진은 브라우저에서 크기·용량을 줄여 업로드 API 한도에 맞춘다.
async function prepareImage(file) {
  if (!['image/png', 'image/jpeg', 'image/webp'].includes(file.type)) throw Error('PNG·JPG·WebP 이미지 파일을 선택해 주세요.');
  if (file.size > 25000000) throw Error('25MB 이하 원본 이미지를 선택해 주세요. 큰 사진은 자동으로 크기를 줄여 올립니다.');
  let bitmap;
  try {
    bitmap = await createImageBitmap(file);
  } catch {
    throw Error('이미지 파일을 읽을 수 없습니다. 정상적인 PNG·JPG·WebP 사진을 선택해 주세요.');
  }
  try {
    if (!bitmap.width || !bitmap.height) throw Error('사진의 가로·세로 크기를 확인하지 못했습니다.');
    if (file.size <= MAX_IMAGE_BYTES && Math.max(bitmap.width, bitmap.height) <= MAX_IMAGE_EDGE) return { blob: file, fileName: file.name };
    const canvas = document.createElement('canvas');
    const scale = Math.min(1, MAX_IMAGE_EDGE / Math.max(bitmap.width, bitmap.height));
    let width = Math.max(1, Math.round(bitmap.width * scale));
    let height = Math.max(1, Math.round(bitmap.height * scale));
    for (let attempt = 0; attempt < 5; attempt++) {
      canvas.width = width;
      canvas.height = height;
      const context = canvas.getContext('2d');
      if (!context) throw Error('브라우저에서 이미지 크기를 조정할 수 없습니다.');
      context.drawImage(bitmap, 0, 0, width, height);
      for (const quality of [.86, .72, .58]) {
        let blob = await canvasBlob(canvas, 'image/webp', quality);
        if (blob.type !== 'image/webp') {
          context.globalCompositeOperation = 'destination-over';
          context.fillStyle = '#fff';
          context.fillRect(0, 0, width, height);
          context.globalCompositeOperation = 'source-over';
          blob = await canvasBlob(canvas, 'image/jpeg', quality);
        }
        if (blob.size <= MAX_IMAGE_BYTES) return { blob, fileName: `${file.name.replace(/\.[^.]*$/, '') || 'mainpage'}-resized.${blob.type === 'image/webp' ? 'webp' : 'jpg'}` };
      }
      width = Math.max(1, Math.round(width * .75));
      height = Math.max(1, Math.round(height * .75));
    }
    throw Error('사진을 1.5MB 이하로 줄이지 못했습니다. 더 작은 원본을 선택해 주세요.');
  } finally {
    bitmap.close();
  }
}

async function blobBase64(blob) {
  const bytes = new Uint8Array(await blob.arrayBuffer());
  let binary = '';
  for (let offset = 0; offset < bytes.length; offset += 8192) binary += String.fromCharCode(...bytes.subarray(offset, offset + 8192));
  return btoa(binary);
}

function friendlyFieldLabel(field) {
  return field.attribute === 'alt' ? '사진 설명' : field.attribute === 'title' ? '설명' : field.kind === 'image' ? '사진' : field.kind === 'video' ? '동영상 주소' : field.kind === 'url' ? '클릭하면 이동할 주소' : field.kind === 'product' ? '연결할 모델' : field.node && field.kind === 'text' ? /^제목/.test(field.label) ? '제목' : /^보조/.test(field.label) ? '안내 문구' : '문구' : shortLabel(field);
}
function ContentField({ field, displayLabel, value, products, disabled, uploading, onChange, onUpload, onPick, canUpload }) {
  const label = displayLabel || friendlyFieldLabel(field);
  const inputId = `main-content-${encodeURIComponent(field.id)}`;
  const modified = value !== fieldValue(field);
  return <article className={`content-field${modified ? ' is-modified' : ''}`}>
    <label htmlFor={inputId} className="content-field-label" title={String(field.label || field.id)}>{label}{modified && <span className="content-modified">수정됨</span>}</label>
    {field.kind === 'number' ? <input id={inputId} inputMode="numeric" value={value} disabled={disabled} placeholder="미등록" onChange={event => onChange(field.id, event.target.value)} /> : field.kind === 'text' || !['image', 'video', 'url', 'product'].includes(field.kind) ? <textarea id={inputId} rows={value.length > 220 ? 5 : 3} value={value} disabled={disabled} onChange={event => onChange(field.id, event.target.value)} /> : field.kind === 'product' ? <>
      {products.length ? <select id={inputId} value={value} disabled={disabled} onChange={event => onChange(field.id, event.target.value)}>
        <option value="">연결할 모델 선택</option>
        {value && !products.some(product => String(product.id) === value) && <option value={value}>현재 연결 모델 · ID {value}</option>}
        {products.map(product => <option key={product.id} value={String(product.id)}>{product.name} · {product.code} · ID {product.id}</option>)}
      </select> : <input id={inputId} type="number" min="0" step="1" value={value} disabled={disabled} onChange={event => onChange(field.id, event.target.value)} />}
    </> : <>
      {field.kind === 'image' && <ImagePreview value={value} label={label} />}
      {field.kind === 'video' && <VideoPreview value={value} label={label} />}
      <input id={inputId} inputMode="url" value={value} disabled={disabled} placeholder={field.kind === 'url' ? '연결 주소' : field.kind === 'video' ? 'https://youtu.be/… 또는 https://vimeo.com/…' : '파일 경로 또는 https:// 주소'} onChange={event => onChange(field.id, event.target.value)} />
      {field.kind === 'image' && <button className="media-select-inline" disabled={disabled} onClick={()=>onPick(field)}>사진 보관함에서 선택</button>}
      {field.kind === 'image' && <label className="content-upload">새 사진 올리기 · PNG·JPG·WebP<input type="file" accept="image/png,image/jpeg,image/webp" disabled={disabled||!canUpload} onChange={event => { const file = event.target.files?.[0]; event.target.value = ''; onUpload(field, file); }} /><span className="hint">{uploading === field.id ? '사진 크기를 조정하고 올리는 중…' : '큰 사진은 가로·세로 최대 2,000px, 1.5MB 이하로 자동 조정합니다.'}</span></label>}
      {field.kind === 'url' && previewSource(value) && <a className="content-url-preview" href={previewSource(value)} target="_blank" rel="noopener noreferrer">연결 주소 열기</a>}
    </>}
  </article>;
}

export default function MainContentEditor({canEdit=true,user,onRequireLogin}) {
  // data는 서버 저장본, drafts는 field.id별 미저장 값이다. 섹션을 옮겨도 drafts는 유지된다.
  const [data, setData] = useState(null);
  const [loadedAt, setLoadedAt] = useState(null);
  const [sectionId, setSectionId] = useState('home');
  const [drafts, setDrafts] = useState({});
  const [products, setProducts] = useState([]);
  const [history, setHistory] = useState([]);
  const [status, setStatus] = useState('메인페이지를 불러오는 중…');
  const [busy, setBusy] = useState(false);
  const [uploading, setUploading] = useState(null);
  const [pending, setPending] = useState(null);
  const [confirmation, setConfirmation] = useState(null);
  const [stale, setStale] = useState(false);
  const [unauthorized, setUnauthorized] = useState(false);
  const [fieldSearch, setFieldSearch] = useState('');
  const [editor, setEditor] = useState(null);
  const [editing, setEditing] = useState(canEdit);
  const [pickField,setPickField]=useState(null);
  const canUpload=user?.owner||user?.permissions?.includes('MEDIA_EDIT');
  const [focusFieldId, setFocusFieldId] = useState('');
  const [locateTick, setLocateTick] = useState(0);
  const drawer = useRef(null);
  const review = useRef(null);
  const confirmDialog = useRef(null);
  const frameApi = useRef(null);
  const section = data?.sections?.find(item => item.id === sectionId) || data?.sections?.[0];
  const currentValue = field => Object.hasOwn(drafts, field.id) ? drafts[field.id] : fieldValue(field);
  const allChanges = (data?.sections || []).flatMap(item => item.fields.filter(field => currentValue(field) !== fieldValue(field)).map(field => ({ section: item, field, before: fieldValue(field), after: currentValue(field) })));
  const dirty = allChanges.length > 0;
  const workspace=useServerDraft({kind:'content',ready:!!data,baseRevision:data?.revision,payload:drafts,dirty,canEdit,onRestore:(payload,base)=>{setDrafts(payload);if(base!==data.revision){setStale(true);setStatus('임시 작업의 기준 공개본이 바뀌었습니다. 최신 내용을 불러와 입력을 유지하고 비교하세요.');}},onDiscard:()=>setDrafts({})});
  const locked = busy || !!uploading || !!pending || !!confirmation || unauthorized || !!workspace.pending || canEdit&&!workspace.initialized;
  // 서버 저장본에 초안만 덧씌운 사본을 iframe으로 보내며 저장 전에는 공개 API를 바꾸지 않는다.
  const previewSections = useMemo(() => (data?.sections || []).map(item => ({ ...item, fields: item.fields.map(field => Object.hasOwn(drafts, field.id) ? { ...field, value: drafts[field.id] } : field) })), [data, drafts]);
  const modifiedFieldIds = useMemo(() => (data?.sections || []).flatMap(item => item.fields.filter(field => Object.hasOwn(drafts, field.id) && drafts[field.id] !== fieldValue(field)).map(field => field.id)), [data, drafts]);

  function reportError(error) {
    if (error.status === 401) {setUnauthorized(true);onRequireLogin?.();}
    setStatus(error.message || '요청을 처리하지 못했습니다. 입력한 내용은 유지됩니다.');
  }
  // 최신 저장본 수신 시 loadedAt은 조회 시각으로 새로 기록하고, 충돌 복구라면 초안을 보존한다.
  function acceptData(next, keepDrafts = false) {
    if (!Array.isArray(next?.sections)) throw Error('메인페이지 내용을 확인하지 못했습니다.');
    setData(next);
    setLoadedAt(new Date().toISOString());
    setSectionId(current => next.sections.some(item => item.id === current) ? current : 'home');
    if (!keepDrafts) setDrafts({});
    setPending(null); setStale(false); setUnauthorized(false);
  }
  async function load(keepDrafts = false) {
    setBusy(true);
    try {
      const results = await Promise.allSettled([api('/api/admin/content'), api('/api/admin/catalog'), api('/api/admin/content/history')]);
      if (results[0].status === 'rejected') throw results[0].reason;
      acceptData(results[0].value, keepDrafts);
      if (results[1].status === 'fulfilled') setProducts(results[1].value?.products || []);
      if (results[2].status === 'fulfilled') setHistory(Array.isArray(results[2].value) ? results[2].value : []);
      setStatus(keepDrafts ? '최신 저장값을 불러왔습니다. 입력한 수정값을 유지했으니 변경 내용을 다시 확인해 주세요.' : '페이지에서 바꾸고 싶은 문구나 사진을 더블클릭해 주세요.');
    } catch (error) { reportError(error); } finally { setBusy(false); }
  }
  const currentEditor=useRef(null);
  currentEditor.current={dirty,revision:data?.revision};
  useEffect(() => { load();const refresh=()=>Promise.all([api('/api/admin/catalog'),api('/api/admin/content')]).then(([models,latest])=>{
    setProducts(models.products||[]);
    if(latest.revision===currentEditor.current?.revision)return;
    if(currentEditor.current?.dirty){setStale(true);setPending(null);setStatus('모델 관리에서 공개 내용이 변경되었습니다. 입력은 유지됩니다. 최신 내용을 불러와 비교하세요.');}
    else acceptData(latest);
  }).catch(reportError);window.addEventListener('catalog-updated',refresh);return()=>window.removeEventListener('catalog-updated',refresh); }, []);
  useEffect(() => {
    const beforeUnload = event => { if (dirty || uploading) { event.preventDefault(); event.returnValue = ''; } };
    window.addEventListener('beforeunload', beforeUnload);
    return () => window.removeEventListener('beforeunload', beforeUnload);
  }, [dirty, uploading]);
  useEffect(() => {
    if (!editor) return;
    const frame = requestAnimationFrame(() => (drawer.current?.querySelector('textarea,input:not([type=file]),select') || drawer.current?.querySelector('button'))?.focus());
    return () => cancelAnimationFrame(frame);
  }, [editor?.key]);
  useEffect(() => {
    if (pending && review.current && !review.current.open) review.current.showModal();
    if (!pending && review.current?.open) review.current.close();
  }, [pending]);
  useEffect(() => {
    if (confirmation && confirmDialog.current && !confirmDialog.current.open) confirmDialog.current.showModal();
  }, [confirmation]);

  function chooseSection(id) {
    if (locked || !data.sections.some(item => item.id === id)) return;
    setSectionId(id); setFocusFieldId(''); setLocateTick(tick => tick + 1);
  }
  function openSectionEditor(id = sectionId) {
    if (locked) return;
    const selected = data.sections.find(item => item.id === id);
    if (!selected) return;
    setSectionId(id); setFieldSearch('');
    setEditor({ key: Date.now(), sectionId: id, fieldIds: null });
    setLocateTick(tick => tick + 1);
  }
  // iframe이 보낸 field ID를 현재 스키마에서 다시 찾아 해당 구역의 편집 서랍을 연다.
  function openContentEditor(message) {
    if (locked || !editing || !canEdit) return;
    const requested = [...new Set(Array.isArray(message.fieldIds) ? message.fieldIds : message.fieldId ? [message.fieldId] : [])];
    const matches = data.sections.flatMap(item => item.fields.filter(field => requested.includes(field.id)).map(field => ({ field, section: item }))).sort((left, right) => requested.indexOf(left.field.id) - requested.indexOf(right.field.id));
    if (!matches.length) { openSectionEditor(message.sectionId); return; }
    const target = matches[0];
    setSectionId(target.section.id); setFocusFieldId(target.field.id); setFieldSearch('');
    setEditor({ key: Date.now(), sectionId: target.section.id, fieldIds: matches.map(match => match.field.id), title: message.label });
    setStatus('변경 내용은 저장 전 화면에 바로 반영됩니다. 마지막에 변경 내용을 저장해 주세요.');
  }
  function closeEditor() { if (uploading) return; setEditor(null); frameApi.current?.focusSelected?.(); }
  function editField(id, value) {
    if (locked || !canEdit) return;
    setFocusFieldId(id); setDrafts(current => ({ ...current, [id]: value }));
  }
  // 파일 저장과 고객페이지 반영은 별개다. 반환된 URL을 초안에 넣고 최종 저장 때 공개한다.
  async function uploadImage(field, file) {
    if (!file || locked || !canEdit || !canUpload) return;
    setUploading(field.id);
    try {
      const image = await prepareImage(file);
      const result = await api('/api/admin/content/images', { method: 'POST', body: { fileName: image.fileName, base64: await blobBase64(image.blob) } });
      if (!result?.url) throw Error('사진을 올리지 못했습니다. 다시 선택해 주세요.');
      setDrafts(current => ({ ...current, [field.id]: String(result.url) }));
      setFocusFieldId(field.id);
      setStatus('새 사진을 화면에 반영했습니다. 변경 내용을 저장하면 고객 페이지에 적용됩니다.');
    } catch (error) { reportError(error); } finally { setUploading(null); }
  }
  // 검토창에 보여 줄 변경값과 현재 revision을 묶어 저장 중 입력과 요청 내용을 고정한다.
  function prepareSave() {
    if (locked || stale || !allChanges.length || !canEdit) return;
    setPending({ revision: data.revision, changes: allChanges, values: Object.freeze(Object.fromEntries(allChanges.map(change => [change.field.id, change.after]))) });
  }
  async function refreshHistory() {
    try { setHistory(await api('/api/admin/content/history')); } catch { /* 기록 조회가 실패해도 저장된 변경은 그대로 유지된다. */ }
  }
  // revision 조건부 저장으로 다른 관리자의 선행 변경을 감지한다. 409면 초안을 유지한다.
  async function saveChanges() {
    if (!pending || busy || uploading || stale) return;
    setBusy(true);
    try {
      const next = await api('/api/admin/content', { method: 'PUT', body: { revision: pending.revision, values: pending.values } });
      const count = pending.changes.length;
      acceptData(next); await refreshHistory();
      setStatus(`${count}개 변경을 공개했습니다. 고객 메인페이지에 반영되었습니다.`);
      await workspace.clear().catch(error=>setStatus(`공개는 완료됐지만 임시 작업 삭제를 확인하지 못했습니다. ${error.message}`));
    } catch (error) {
      if (error.status === 409) { setPending(null); setStale(true); setStatus('다른 관리자가 먼저 저장했습니다. 입력값은 유지됩니다. 최신 내용을 불러와 비교해 주세요.'); }
      else reportError(error);
    } finally { setBusy(false); }
  }
  async function reload() {
    if (busy || uploading) return;
    if (dirty) { setConfirmation({ kind: 'reload', title: '저장된 페이지를 다시 불러올까요?', message: '저장 전 변경은 취소됩니다.', action: '다시 불러오기' }); return; }
    await load();
  }
  function discardChanges() {
    if (locked || !dirty) return;
    setConfirmation({ kind: 'discard', title: '저장 전 변경을 취소할까요?', message: `${allChanges.length}개 변경을 취소하고 저장된 내용을 보여줍니다.`, action: '변경 취소하기' });
  }
  function restore(targetRevision) {
    if (locked) return;
    const updatedAt = historySavedAt(history.find(item => item.revision === targetRevision));
    setConfirmation({ kind: 'restore', targetRevision, title: '이전 저장본으로 되돌릴까요?', message: `${dateLabel(updatedAt)}에 저장한 모든 구역의 내용과 사진 주소로 바꿉니다. 저장 전 변경도 취소됩니다.`, action: '이 저장본 적용하기' });
  }
  // 이전 저장본 적용 역시 새 저장 작업이다. 현재 revision을 함께 보내 충돌을 막는다.
  async function restoreVersion(targetRevision) {
    if(!canEdit)return;setBusy(true);
    try { acceptData(await api('/api/admin/content/restore', { method: 'POST', body: { revision: data.revision, targetRevision } })); await refreshHistory(); setStatus('선택한 저장본으로 되돌렸습니다. 고객 메인페이지에도 반영되었습니다.'); await workspace.clear().catch(error=>setStatus(`복원은 완료됐지만 임시 작업 삭제를 확인하지 못했습니다. ${error.message}`)); }
    catch (error) { if (error.status === 409) setStale(true); reportError(error); }
    finally { setBusy(false); }
  }
  async function confirmAction() {
    const action = confirmation;
    if (!action || busy) return;
    setBusy(true);
    setConfirmation(null);
    try {
    if (action.kind === 'discard') { await workspace.clear();setDrafts({}); setStatus('변경을 취소했습니다. 저장된 페이지를 보여줍니다.'); }
    if (action.kind === 'reload') {if(dirty)await workspace.clear();await load();}
    if (action.kind === 'restore') await restoreVersion(action.targetRevision);
    } catch (error) { reportError(error); } finally { setBusy(false); }
  }
  if (!data) return <section className="panel content-loading"><h1>메인페이지 편집</h1><p role="status">{status}</p>{unauthorized ? <a href="/admin_login">관리자 로그인</a> : <button disabled={busy} onClick={() => load()}>다시 불러오기</button>}</section>;

  const selectedRegion = regionForSection(section?.id);
  const menuRegions = contentRegions.map(region => ({ ...region, items: region.sections.map(id => data.sections.find(item => item.id === id)).filter(Boolean) })).filter(region => region.items.length);
  const editingSection = data.sections.find(item => item.id === editor?.sectionId);
  const drawerFields = editor?.fieldIds ? data.sections.flatMap(item => item.fields.filter(field => editor.fieldIds.includes(field.id))).sort((left, right) => editor.fieldIds.indexOf(left.id) - editor.fieldIds.indexOf(right.id)) : editingSection?.fields || [];
  const search = fieldSearch.trim().toLocaleLowerCase('ko-KR');
  const visibleFields = drawerFields.filter(field => !search || `${field.label} ${currentValue(field)}`.toLocaleLowerCase('ko-KR').includes(search));
  const changedSections = new Set(allChanges.map(change => regionForSection(change.section.id).id)).size;
  return <div className="visual-content-editor">
    <div className="page-heading"><div><p className="eyebrow">PAGE EDITOR</p><h1>메인페이지 편집</h1><p>문구·사진은 더블클릭, 메뉴·이동 버튼은 한 번 클릭해 수정하세요.</p></div><SavedDataInfo updatedAt={data.updatedAt} loadedAt={loadedAt} dirty={dirty}/></div>
    <DraftBar workspace={workspace} dirty={dirty}/>
    <div className="visual-editor-toolbar">
      <div className="visual-edit-toggle" aria-label="화면 모드"><button disabled={!canEdit} aria-pressed={editing} onClick={() => setEditing(true)}>직접 편집</button><button aria-pressed={!editing} onClick={() => { setEditing(false); setEditor(null); }}>미리보기</button></div>
      <label className="visual-region-picker">구역 이동<select value={sectionId} disabled={locked} onChange={event => chooseSection(event.target.value)}>{menuRegions.map(region => <optgroup key={region.id} label={region.title}>{region.items.map(item => <option key={item.id} value={item.id}>{region.title}{region.items.length > 1 ? ` · ${sectionHints[item.id] || item.title}` : ''}</option>)}</optgroup>)}</select></label>
      <button type="button" disabled={locked} onClick={() => openSectionEditor()}>이 구역의 모든 항목</button>
      <div className="visual-save-tools"><span className={dirty ? 'visual-dirty-count' : 'hint'} aria-live="polite">{dirty ? `저장 전 변경 ${allChanges.length}개 · ${changedSections}개 구역` : '저장된 페이지'}</span><button type="button" disabled={!dirty || locked} onClick={discardChanges}>변경 취소</button><button type="button" className="primary" disabled={!dirty || locked || stale || !canEdit} onClick={prepareSave}>{busy ? '공개 중…' : '고객페이지에 공개'}</button></div>
    </div>
    <p role={unauthorized || stale ? 'alert' : 'status'} className={stale || unauthorized ? 'notice visual-status-error' : 'visual-editor-status'}>{status}{unauthorized && <> <a href="/admin_login">다시 로그인</a></>}</p>
    {stale && <div className="content-stale"><p>입력한 내용을 유지하며 최신 저장값을 불러옵니다.</p><button disabled={busy || !!uploading} onClick={() => load(true)}>최신 내용 불러오기 · 입력 유지</button></div>}
    <div className={`visual-editor-workspace${editor ? ' has-drawer' : ''}`}>
      <MainPagePreview open embedded editMode={editing&&canEdit} editingDisabled={locked||!canEdit} sections={previewSections} sectionId={sectionId} focusFieldId={focusFieldId} selectedFieldId={focusFieldId} modifiedFieldIds={modifiedFieldIds} locateTick={locateTick} onSelectSection={chooseSection} onEditContent={openContentEditor} apiRef={frameApi} />
      {editor && <aside ref={drawer} className="visual-edit-drawer panel" aria-label="선택한 부분 수정" onKeyDown={event => { if (event.key === 'Escape' && !pending) { event.preventDefault(); closeEditor(); } }}>
        <div className="visual-drawer-heading"><div><p>{regionForSection(editor.sectionId).title}</p><h2>{editor.fieldIds ? '선택한 부분 수정' : '이 구역의 모든 항목'}</h2></div><button type="button" disabled={!!uploading} onClick={closeEditor} aria-label="편집창 닫기">닫기</button></div>
        <p className="hint">입력하면 옆 페이지에 바로 보입니다. 창을 닫거나 다른 곳을 선택해도 수정값은 유지됩니다.</p>
        {!editor.fieldIds && <><label>편집할 항목 그룹<select value={editor.sectionId} disabled={locked} onChange={event => { const id = event.target.value; setSectionId(id); setFieldSearch(''); setEditor({ key: Date.now(), sectionId: id, fieldIds: null }); }}>{(contentRegions.find(region => region.sections.includes(editor.sectionId))?.sections || []).map(id => data.sections.find(item => item.id === id)).filter(Boolean).map(item => <option key={item.id} value={item.id}>{sectionHints[item.id] || item.title}</option>)}</select></label><label>항목 찾기<input type="search" value={fieldSearch} placeholder="화면의 문구 또는 사진 설명" onChange={event => setFieldSearch(event.target.value)} /></label></>}
        <div className="visual-drawer-fields">{visibleFields.map(field => {
          const related = visibleFields.filter(other => friendlyFieldLabel(other) === friendlyFieldLabel(field));
          const order = related.findIndex(other => other.id === field.id) + 1;
          const displayLabel = !editor.fieldIds ? shortLabel(field) : related.length > 1 ? `${friendlyFieldLabel(field)} · ${order}` : friendlyFieldLabel(field);
          return <ContentField key={field.id} field={field} displayLabel={displayLabel} value={currentValue(field)} products={products} disabled={locked||!canEdit} uploading={uploading} onChange={editField} onUpload={uploadImage} onPick={setPickField} canUpload={canUpload} />;
        })}{!visibleFields.length && <p>일치하는 항목이 없습니다.</p>}</div>
        <div className="visual-drawer-footer"><button type="button" onClick={closeEditor} disabled={!!uploading}>편집 마치기</button><span className="hint">고객 페이지에는 저장 후 반영됩니다.</span></div>
      </aside>}
    </div>
    <div className="visual-editor-bottom"><a href="/" target="_blank" rel="noopener noreferrer">고객 메인페이지 열기 ↗</a><button disabled={locked} onClick={reload}>고객페이지에 적용된 내용 다시 확인</button>{data.updatedAt && <span className="hint">마지막 저장 {dateLabel(data.updatedAt)}</span>}<details className="visual-history"><summary>이전 저장본으로 되돌리기</summary><div className="panel">{history.length ? history.map(item => <div className="history-row" key={item.revision}><span className="saved-snapshot"><strong>{dateLabel(historySavedAt(item))} 저장본</strong><small>메인페이지 모든 구역의 내용·사진·연결 주소</small></span><button disabled={locked || item.revision === data.revision || stale || !canEdit} onClick={() => restore(item.revision)}>이 저장본으로 되돌리기</button></div>) : <p>이전 저장본이 없습니다.</p>}</div></details></div>
    {pickField&&<MediaPicker user={user} onRequireLogin={onRequireLogin} onClose={()=>setPickField(null)} onSelect={url=>{editField(pickField.id,url);setPickField(null);}}/>}
    {pending && <dialog ref={review} className="visual-save-dialog" aria-labelledby="visual-save-title" onCancel={event => { event.preventDefault(); if (!busy) setPending(null); }}>
      <div className="visual-save-dialog-heading"><div><h2 id="visual-save-title">변경 내용을 저장할까요?</h2><p>{new Set(pending.changes.map(change => regionForSection(change.section.id).id)).size}개 구역의 {pending.changes.length}개 변경을 한 번에 저장합니다.</p></div><button type="button" disabled={busy} onClick={() => setPending(null)}>닫기</button></div>
      <div className="content-review-list">{pending.changes.map(change => <article key={change.field.id}><p className="content-region-location">{regionForSection(change.section.id).title}</p><h3>{shortLabel(change.field)}</h3><div className="content-review-values"><div><strong>저장된 내용</strong>{change.field.kind === 'image' && <ImagePreview value={change.before} label="기존 사진" />}<pre>{change.before || '빈칸'}</pre></div><div><strong>바뀔 내용</strong>{change.field.kind === 'image' && <ImagePreview value={change.after} label="새 사진" />}<pre>{change.after || '빈칸'}</pre></div></div></article>)}</div>
      <div className="visual-save-dialog-actions"><button disabled={busy} onClick={() => setPending(null)}>계속 수정</button><button className="primary" disabled={busy || !!uploading || stale} onClick={saveChanges}>{busy ? '공개 중…' : '확인한 내용 고객페이지에 공개'}</button></div>
    </dialog>}
    {confirmation && <dialog ref={confirmDialog} className="visual-save-dialog visual-confirm-dialog" aria-labelledby="visual-confirm-title" onCancel={event => { event.preventDefault(); setConfirmation(null); }}>
      <h2 id="visual-confirm-title">{confirmation.title}</h2><p>{confirmation.message}</p><div className="visual-save-dialog-actions"><button onClick={() => setConfirmation(null)}>계속 수정</button><button className="primary" onClick={confirmAction}>{confirmation.action}</button></div>
    </dialog>}
  </div>;
}
