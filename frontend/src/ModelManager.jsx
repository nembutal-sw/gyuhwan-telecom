import React, { useEffect, useMemo, useState } from 'react';
import { api } from './api';
import MediaPicker from './MediaPicker';
import { uploadLibraryImage } from './MediaLibrary';
import './model-manager.css';

export const SELLING_STATUSES = [['selling', '판매 중'], ['sold_out', '품절'], ['incoming', '입고 예정'], ['discontinued', '판매 종료']];
const statusLabel = value => SELLING_STATUSES.find(([key]) => key === value)?.[1] || '판매 중';
const blank = () => ({ name: '', brand: '삼성', code: '', network: '5G', capacity: '256', image: '', imageAlt: '', sellingStatus: 'incoming', hidden: false, colors: [], colorStatuses: {} });
const editFields = ['name', 'brand', 'code', 'network', 'capacity', 'image', 'imageAlt', 'sellingStatus', 'hidden', 'colors', 'colorStatuses'];
const formFor = model => Object.fromEntries(editFields.map(key => [key, model[key] ?? blank()[key]]));

/** 모델 정보·사진·색상 재고를 한 화면에서 편집한다. 가격 조건은 같은 ID로 가격표 메뉴에 자동 추가된다. */
export default function ModelManager({ canEdit = false, onRequireLogin, user, active = true }) {
 const [data, setData] = useState(null), [query, setQuery] = useState(''), [brand, setBrand] = useState('전체'), [statusFilter, setStatusFilter] = useState('전체');
 const [selected, setSelected] = useState(null), [form, setForm] = useState(blank), [review, setReview] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState(''), [notice, setNotice] = useState('');
 const [showMedia, setShowMedia] = useState(false), canUpload = user?.owner || user?.permissions?.includes('MEDIA_EDIT');
 const [colorText, setColorText] = useState('');
 function fail(problem) { setError(problem.message); if (problem.status === 401) onRequireLogin?.(); }
 async function load(keep = false) { const value = await api('/api/admin/catalog'); setData(value); if (!keep) { setSelected(null); setForm(blank()); setReview(false); } return value; }
 const models = useMemo(() => (data?.products || []).filter(model => {
  const text = `${model.id} ${model.name} ${model.brand} ${model.code}`.replace(/\s+/g, '').toLowerCase();
  return (brand === '전체' || model.brand === brand) && (statusFilter === '전체' || (statusFilter === 'hidden' ? model.hidden : model.sellingStatus === statusFilter)) && text.includes(query.replace(/\s+/g, '').toLowerCase());
 }), [data, query, brand, statusFilter]);
 const current = data?.products.find(model => model.id === selected);
 const modified = JSON.stringify(form) !== JSON.stringify(current ? formFor(current) : blank());
 // 다른 메뉴에서 모델·색상을 수정했다면 돌아올 때 최신 정보를 읽되 작성 중인 폼은 보존한다.
 useEffect(() => { if (active && !modified && !busy) { setBusy(true); load(true).then(latest => { const model = latest.products.find(item => item.id === selected); if (model) { setForm(formFor(model)); setColorText(model.colors.join('\n')); } }).catch(fail).finally(() => setBusy(false)); } }, [active]);
 useEffect(() => { if (!modified) return; const warn = event => { event.preventDefault(); event.returnValue = ''; }; window.addEventListener('beforeunload', warn); return () => window.removeEventListener('beforeunload', warn); }, [modified]);
 function select(model) {
  if (modified && !window.confirm('작성 중인 모델 수정 내용을 버리고 선택을 바꿀까요?')) return;
  setSelected(model?.id ?? null); setForm(model ? formFor(model) : blank()); setColorText(model?.colors?.join('\n') || ''); setReview(false); setError(''); setNotice('');
 }
 function change(field, value) { setForm(old => ({ ...old, [field]: value })); setReview(false); setError(''); }
 function colorsChanged(text) {
  setColorText(text);
  const colors = [...new Set(text.split(/[,\n]/).map(v => v.trim()).filter(Boolean))];
  setForm(old => ({ ...old, colors, colorStatuses: Object.fromEntries(colors.map(color => [color, old.colorStatuses[color] || 'selling'])) })); setReview(false);
 }
 async function upload(file) {
  if (!file) return;
  setBusy(true); setError('');
  try { const uploaded = await uploadLibraryImage(file); change('image', uploaded.url);
  } catch (problem) { fail(problem); } finally { setBusy(false); }
 }
 function prepare(event) {
  event.preventDefault(); setError('');
  if (![form.name, form.brand, form.code].every(value => value.trim())) return setError('모델명·제조사·모델코드를 입력하세요.');
  if (!/^(\d{1,4}|1T|문의)$/.test(form.capacity)) return setError('용량은 GB 숫자, 1T 또는 문의로 입력하세요.');
  setReview(true);
 }
 async function save() {
  if (!canEdit || busy) return; setBusy(true); setError('');
  try {
   // 대표 사진을 바꾸면 기존 통신사별 사진도 같은 사진으로 갱신하여 선택한 이미지가 실제 카드에 보인다.
   const modelPatch = { ...form }; if (current && form.image !== (current.image || '')) modelPatch.carrierImages = Object.fromEntries(['SKT', 'KT', 'LG U+'].map(carrier => [carrier, form.image || '']));
   const result = await api(selected === null ? '/api/admin/catalog/models' : `/api/admin/catalog/models/${selected}`, { method: selected === null ? 'POST' : 'PUT', body: { revision: data.revision, model: modelPatch } });
   const latest = await load(true); const model = latest.products.find(item => item.id === result.model.id); setSelected(model.id); setForm(formFor(model)); setColorText(model.colors.join('\n')); setReview(false);
   setNotice(`${model.name}을 저장했습니다. 고객 페이지에 판매 상태가 반영되며 가격표에서도 같은 모델을 확인할 수 있습니다.`); window.dispatchEvent(new CustomEvent('catalog-updated'));
  } catch (problem) { fail(problem); } finally { setBusy(false); }
 }
 if (!data) return <section className="panel"><h1>모델·판매 상태 관리</h1><p>{error || '모델 목록을 불러오는 중…'}</p></section>;
 return <section className="model-manager">
  <header className="model-manager-heading"><div><h1>모델·판매 상태 관리</h1><p className="hint">새 모델을 등록하면 가격표에 모든 통신사·가입 조건이 자동으로 추가됩니다. 품절과 숨김은 별도입니다.</p></div><button disabled={!canEdit || busy} onClick={() => select(null)}>+ 신규 모델 등록</button></header>
  {notice && <p className="model-notice" role="status">{notice}</p>}{error && <p className="error" role="alert">{error}</p>}
  {!canEdit && <p className="hint">조회 전용입니다. 모델 수정 권한은 대표 관리자에게 요청하세요.</p>}
  <div className="model-manager-layout"><div className="panel model-manager-list">
   <div className="model-filters"><label>모델 검색<input type="search" placeholder="모델명·코드·ID 검색" value={query} onChange={event => setQuery(event.target.value)} /></label><label>제조사<select value={brand} onChange={event => setBrand(event.target.value)}><option>전체</option>{[...new Set(data.products.map(model => model.brand))].map(value => <option key={value}>{value}</option>)}</select></label><label>판매 상태<select value={statusFilter} onChange={event => setStatusFilter(event.target.value)}><option>전체</option>{SELLING_STATUSES.map(([key, label]) => <option key={key} value={key}>{label}</option>)}<option value="hidden">고객 페이지 숨김</option></select></label></div>
   <p className="hint">검색 결과 {models.length}개 / 전체 {data.products.length}개 모델</p>
   <div className="model-list-scroll"><table><thead><tr><th>모델</th><th>판매 상태</th><th>고객 목록</th></tr></thead><tbody>{models.map(model => <tr key={model.id} className={selected === model.id ? 'is-selected' : ''}><td><button className="model-select" disabled={busy} onClick={() => select(model)}><strong>{model.name}</strong><small>{model.brand} · {model.code} · ID {model.id}</small></button></td><td><span className={`model-status status-${model.sellingStatus}`}>{statusLabel(model.sellingStatus)}</span></td><td>{model.hidden ? '숨김' : model.customerListed ? '표시' : '가격표만'}</td></tr>)}</tbody></table>{!models.length && <p>검색 조건에 맞는 모델이 없습니다.</p>}</div>
  </div><form className="panel model-manager-form" onSubmit={prepare}>
   <h2>{current ? `모델 수정 · ID ${current.id}` : '신규 모델 등록'}</h2><p className="hint">{current ? 'ID는 가격표와 연결되어 변경하거나 삭제할 수 없습니다. 판매 종료 또는 숨김으로 관리하세요.' : '용량이 다르면 각각 별도 모델로 등록하세요. 가격은 가격표에서 입력할 수 있습니다.'}</p>
   {current && !current.customerListed && <p className="hint">현재 가격표에만 등록된 모델입니다. 모델 정보를 수정해서 저장하면 고객 목록에도 추가됩니다. 노출하지 않으려면 숨기기를 선택하세요.</p>}
   <fieldset disabled={!canEdit || busy}>
    <div className="model-form-grid"><label>모델명<input required maxLength={150} value={form.name} onChange={event => change('name', event.target.value)} /></label><label>제조사<input required list="model-brands" maxLength={150} value={form.brand} onChange={event => change('brand', event.target.value)} /><datalist id="model-brands">{['삼성', 'Apple', 'ALT', '키즈폰', '기타'].map(value => <option key={value}>{value}</option>)}</datalist></label><label>모델코드<input required maxLength={150} value={form.code} onChange={event => change('code', event.target.value)} /></label><label>용량 (GB / 1T / 문의)<input required disabled={current?.capacityLocked} value={form.capacity} onChange={event => change('capacity', event.target.value)} />{current?.capacityLocked && <span className="hint">기존 용량 연결 유지 · 다른 용량은 신규 등록</span>}</label><label>네트워크<select value={form.network} onChange={event => change('network', event.target.value)}>{['5G', 'LTE', '확인 필요'].map(value => <option key={value}>{value}</option>)}</select></label><label>모델 판매 상태<select value={form.sellingStatus} onChange={event => change('sellingStatus', event.target.value)}>{SELLING_STATUSES.map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label></div>
    <label className="model-hidden"><input type="checkbox" checked={form.hidden} onChange={event => change('hidden', event.target.checked)} /> 고객 페이지에서 숨기기</label><p className="hint">품절·입고 예정은 상태를 표시합니다. 숨기기를 선택하면 목록과 모델 선택 메뉴에서 제외됩니다.</p>
    <label>대표 사진 주소<input maxLength={2000} value={form.image || ''} onChange={event => change('image', event.target.value)} placeholder="사진 보관함 주소 또는 HTTPS 이미지 URL" /></label><button type="button" onClick={() => setShowMedia(true)}>사진 보관함에서 선택</button>{canUpload && <label>사진 업로드<input type="file" accept="image/png,image/jpeg,image/webp" onChange={event => { upload(event.target.files?.[0]); event.target.value = ''; }} /></label>}
    {form.image && <img className="model-photo" src={form.image.startsWith('/') || /^https?:/.test(form.image) ? form.image : `/${form.image}`} alt={form.imageAlt || form.name || '대표 사진 미리보기'} />}
    <label>사진 설명<input maxLength={150} value={form.imageAlt} onChange={event => change('imageAlt', event.target.value)} /></label>
    <label>색상 목록<textarea value={colorText} onChange={event => colorsChanged(event.target.value)} placeholder="블랙&#10;화이트" /><span className="hint">한 줄에 한 색상 또는 쉼표로 구분하세요. 저장하면 가격표의 선택 색상도 함께 반영됩니다.</span></label>
    {form.colors.length > 0 && <div className="model-color-statuses"><h3>색상별 판매 상태</h3>{form.colors.map(color => <label key={color}>{color}<select value={form.colorStatuses[color] || 'selling'} onChange={event => change('colorStatuses', { ...form.colorStatuses, [color]: event.target.value })}>{SELLING_STATUSES.map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select></label>)}</div>}
    <button className="primary" type="submit" disabled={!modified}>저장 전 확인</button>
   </fieldset>
   {review && <div className="model-save-review"><h3>고객 페이지 반영 내용</h3><p><strong>{form.name}</strong> · {form.brand} · {form.capacity} {/^\d+$/.test(form.capacity) ? 'GB' : ''}</p><p>판매 상태: <strong>{statusLabel(form.sellingStatus)}</strong> · {form.hidden ? '고객 페이지에서 숨김' : '고객 페이지에 표시'}</p><p className="hint">저장하면 즉시 공개됩니다. 새 모델의 가격이 비어 있으면 고객에게 확인 필요로 안내합니다.</p><div className="actions"><button type="button" className="primary" disabled={busy} onClick={save}>{busy ? '저장 중…' : '확인하고 저장'}</button><button type="button" onClick={() => setReview(false)} disabled={busy}>계속 수정</button></div></div>}
  </form></div>{showMedia && <MediaPicker user={user} onRequireLogin={onRequireLogin} onSelect={url => change('image', url)} onClose={() => setShowMedia(false)} />}
 </section>;
}
