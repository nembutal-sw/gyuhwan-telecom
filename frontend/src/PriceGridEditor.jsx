// 전체 모델 가격표 편집기. 서버 설정을 행으로 펼쳐 표시하고 미저장 셀 초안을 다시 설정으로 합친다.
import React, {useEffect, useMemo, useRef, useState} from 'react';
import {api, downloadFile} from './api';
import {calculatePurchaseQuote} from './core/installment-core.mjs';
import {validateConfig} from './core/quote-core.mjs';
import {buildPriceRows, filterPriceRows, compilePriceDrafts, parseGridClipboard, TEXT_FIELDS} from './core/price-grid.mjs';
import MainPagePreview from './MainPagePreview.jsx';
import PolicyTransfer from './PolicyTransfer.jsx';
import SavedDataInfo, {savedAtLabel,historySavedAt} from './SavedDataInfo.jsx';
import DraftBar,{useServerDraft} from './useServerDraft.jsx';
import PolicySchedulePanel from './PolicySchedulePanel.jsx';
import BulkPriceDialog from './BulkPriceDialog.jsx';
import MediaPicker from './MediaPicker.jsx';
import './operations.css';
import './price-grid.css';

const LABELS = {retail:'출고가', common:'공통·유통망 지원금', support:'추가지원금', planName:'요금제명', planFee:'월정액', discountBase:'선택약정 기준액', addonFee:'월 부가요금', addonMonths:'청구 개월', installmentFee:'24개월 할부이자', supportInquiry:'문의 표시', addonSchedule:'월별 부가요금', planDetails:'요금제·유지 조건 안내', addonDetails:'부가서비스 안내', penaltyDetails:'위약금 안내', terms:'구매 조건·유의사항'};
const PRICE_COLUMNS = ['retail','common','support','planName','planFee','discountBase','addonFee','addonMonths'];
// 처음에는 모든 모델을 한 행씩 보이되 조건은 SKT·번호이동·공통 지원금으로 고정한다.
const INITIAL_FILTERS = {carrier:'SKT',brand:'',type:'번호이동',discount:'common',query:'',changedOnly:false};
const brandName = brand => brand === 'Apple' ? '애플' : brand;
const discountName = row => row.discount === 'choice' ? '선택약정' : row.carrier === 'LG U+' ? '유통망 지원금' : '공통 지원금';
const rowName = row => `${row.product.name} · ${row.carrier} · ${row.type} · ${discountName(row)}`;
const cellDisabled = (row, field) => field === 'common' && row.discount === 'choice' || field === 'discountBase' && row.discount !== 'choice';
const displayValue = value => value == null ? '' : typeof value === 'number' ? value.toLocaleString('ko-KR') : String(value);
const reviewValue = (field,value) => value == null ? '미등록' : typeof value === 'boolean' ? value ? '문의' : '금액 표시' : typeof value === 'number' ? `${value.toLocaleString('ko-KR')}${field==='addonMonths'?'개월':'원'}` : Array.isArray(value) ? value.map(displayValue).join(', ') : value || '빈 문구';

function Dialog({title, children, onClose, busy=false, wide=false}) {
  const ref = useRef(null);
  useEffect(() => {
    const previous = document.activeElement;
    ref.current?.showModal();
    return () => {ref.current?.close(); if(previous?.isConnected) previous.focus();};
  }, []);
  return <dialog ref={ref} className={`price-dialog ${wide?'is-wide':''}`} onCancel={event=>{event.preventDefault();if(!busy)onClose();}}>
    <div className="price-dialog-heading"><h2>{title}</h2><button type="button" disabled={busy} onClick={onClose} aria-label="닫기">닫기</button></div>{children}
  </dialog>;
}

// 색상과 128px 사진은 모델 공통 설정이다. 통신사별 가격 행과 별도 초안으로 저장한다.
function ModelAppearance({product, catalog, config, draft, onChange, onUploadState, disabled, user}) {
  const model = String(product.id);
  const [uploading,setUploading] = useState(false), [error,setError] = useState('');
  const [pickColor,setPickColor]=useState(null);
  const canUpload=user?.owner||user?.permissions?.includes('MEDIA_EDIT');
  const colors = draft?.colors ?? (config.colors[model] || []).join(', ');
  const images = draft?.images ?? config.colorImages?.[model] ?? {};
  const colorList = [...new Set(colors.split(/[,\n]/).map(s=>s.trim()).filter(Boolean))];
  const visibleColors = colorList.length ? colorList : catalog.defaultColors[model]?.colors || [];
  async function upload(color,file) {
    if(!file)return;
    setUploading(true);onUploadState(1);setError('');
    try {
      if(!['image/png','image/jpeg','image/webp'].includes(file.type)||file.size>10000000)throw Error('10MB 이하 JPG·PNG·WebP 사진을 선택해 주세요.');
      const bitmap=await createImageBitmap(file), canvas=document.createElement('canvas');
      canvas.width=128;canvas.height=128;
      const context=canvas.getContext('2d');context.fillStyle='#fff';context.fillRect(0,0,128,128);
      const scale=Math.min(128/bitmap.width,128/bitmap.height);
      context.drawImage(bitmap,(128-bitmap.width*scale)/2,(128-bitmap.height*scale)/2,bitmap.width*scale,bitmap.height*scale);bitmap.close();
      const result=await api('/api/admin/content/images',{method:'POST',body:{fileName:file.name,base64:canvas.toDataURL('image/webp',.82).split(',')[1]}});
      onChange(model,{colors,images:{...images,[color]:result.url}});
    }catch(e){setError(e.message)}finally{setUploading(false);onUploadState(-1)}
  }
  return <section className="panel price-appearance">
    <h2>{product.name} · 색상과 사진</h2><p className="hint">선택한 모델의 모든 통신사·가입 조건에 공통으로 적용됩니다. 수정 후 상단에서 함께 저장하세요.</p>
    <fieldset disabled={disabled||uploading}><label>색상 · 쉼표로 구분<input value={colors} onChange={e=>onChange(model,{colors:e.target.value,images})}/></label>
    <p className="hint">빈칸이면 기존 기본 색상을 사용합니다.</p><div className="price-color-list">{visibleColors.map(color=><div className="color-row" key={color}>
      <img src={images[color]||`/color-swatches/${encodeURIComponent(color)}.svg`} alt={`${product.name} ${color}`} onLoad={e=>{e.currentTarget.style.visibility='visible'}} onError={e=>{e.currentTarget.style.visibility='hidden'}}/>
      <label>{color}<input disabled={!canUpload} type="file" accept="image/png,image/jpeg,image/webp" onChange={e=>{upload(color,e.target.files?.[0]);e.target.value='';}}/></label>
      <button type="button" onClick={()=>setPickColor(color)}>보관함에서 선택</button>
      <button type="button" onClick={()=>{const next={...images};delete next[color];onChange(model,{colors,images:next});}}>기본 색상 표시</button>
    </div>)}</div></fieldset>{error&&<p role="alert" className="error">{error}</p>}
    {pickColor&&<MediaPicker user={user} onClose={()=>setPickColor(null)} onSelect={url=>{onChange(model,{colors,images:{...images,[pickColor]:url}});setPickColor(null);}}/>}
  </section>;
}

export default function PriceGridEditor({onRequireLogin,canEdit=true,user}) {
  // data는 서버 저장본, drafts는 행 키·필드별 입력, appearanceDrafts는 모델 색상·사진 입력이다.
  const [data,setData]=useState(null), [catalog,setCatalog]=useState(null), [history,setHistory]=useState([]);
  const [drafts,setDrafts]=useState({}), [appearanceDrafts,setAppearanceDrafts]=useState({});
  const [filters,setFilters]=useState(INITIAL_FILTERS), [page,setPage]=useState(0), [pageSize,setPageSize]=useState(0), [view,setView]=useState('prices');
  const [loadedAt,setLoadedAt]=useState(null);
  const [busy,setBusy]=useState(false), [uploads,setUploads]=useState(0), [status,setStatus]=useState('가격표를 불러오는 중…'), [stale,setStale]=useState(false);
  const [pending,setPending]=useState(null), [confirmation,setConfirmation]=useState(null), [detailKey,setDetailKey]=useState(null);
  const [policyPreview,setPolicyPreview]=useState(null), [excelOpen,setExcelOpen]=useState(false), [model,setModel]=useState('');
  const [previewOpen,setPreviewOpen]=useState(false), [siteSections,setSiteSections]=useState([]), [previewSelection,setPreviewSelection]=useState(null);
  const cellRefs=useRef(new Map());
  const [selected,setSelected]=useState(new Set()),[bulkOpen,setBulkOpen]=useState(false);
  function report(error) {setStatus(error.message);if(error.status===401)onRequireLogin?.();}
  async function load({keepDrafts=false}={}) {
    const [latest,models]=await Promise.all([api('/api/admin/config'),api('/api/admin/catalog')]);
    setData(latest);setCatalog(models);setLoadedAt(new Date().toISOString());setModel(m=>m||String(models.products[0]?.id??''));
    if(!keepDrafts){setDrafts({});setAppearanceDrafts({});}
    setPending(null);setStale(false);
    try{setHistory(await api('/api/admin/config/history'));}catch(e){report(e);}
    setStatus(keepDrafts?`고객페이지에 적용된 가격표를 다시 불러왔습니다. 마지막 저장: ${savedAtLabel(latest.updatedAt)}. 입력은 유지했으니 변경 전후를 다시 확인하세요.`:'전체 모델을 표시합니다. 모델 선택 없이 셀에서 바로 수정하세요. 통신사·가입 조건은 위 필터에서 바꿀 수 있습니다.');
  }
  useEffect(()=>{load().catch(report);},[]);
  // 모든 모델 × 통신사 × 가입 유형 × 할인 방법을 행으로 만든 뒤 화면 필터만 적용한다.
  const rows=useMemo(()=>data&&catalog?buildPriceRows(catalog.products,data.config):[],[data,catalog]);
  // 숫자 파싱·계산·오류·실제 변경 판정은 순수 모듈에서 처리해 표와 저장 검토가 같은 결과를 쓴다.
  const compiled=useMemo(()=>data&&catalog?compilePriceDrafts(data.config,drafts,catalog.products):{config:null,changes:[],errors:[]},[data,catalog,drafts]);
  const appearanceChanges=useMemo(()=>{
    if(!data)return [];
    return Object.entries(appearanceDrafts).flatMap(([id,draft])=>{
      const result=[],colors=[...new Set(draft.colors.split(/[,\n]/).map(s=>s.trim()).filter(Boolean))];
      if(JSON.stringify(colors)!==JSON.stringify(data.config.colors[id]||[]))result.push({model:id,field:'colors',before:data.config.colors[id]||[],after:colors});
      if(JSON.stringify(draft.images)!==JSON.stringify(data.config.colorImages?.[id]||{}))result.push({model:id,field:'colorImages',before:data.config.colorImages?.[id]||{},after:draft.images});
      return result;
    });
  },[appearanceDrafts,data]);
  const dirty=compiled.changes.length>0||compiled.errors.length>0||appearanceChanges.length>0;
  const draftPayload=useMemo(()=>({drafts,appearanceDrafts}),[drafts,appearanceDrafts]);
  const workspace=useServerDraft({kind:'price',ready:!!data&&!!catalog,baseRevision:data?.revision,payload:draftPayload,dirty,canEdit,onRestore:(payload,base)=>{setDrafts(payload.drafts||{});setAppearanceDrafts(payload.appearanceDrafts||{});if(base!==data.revision){setStale(true);setStatus('임시 작업의 기준 가격표가 달라졌습니다. 최신 가격을 불러와 입력을 유지하고 비교하세요.');}},onDiscard:()=>{setDrafts({});setAppearanceDrafts({});}});
  const currentEditor=useRef(null);
  currentEditor.current={dirty,revision:data?.revision};
  // 모델 색상 변경은 가격 공개본도 바꾼다. 응답 시점의 입력을 확인해 작성 중인 작업을 지킨다.
  useEffect(()=>{const refresh=()=>Promise.all([api('/api/admin/catalog'),api('/api/admin/config')]).then(([models,latest])=>{
    setCatalog(models);
    if(latest.revision===currentEditor.current?.revision)return;
    if(currentEditor.current?.dirty){setStale(true);setPending(null);setStatus('모델 관리에서 색상이 변경되었습니다. 입력은 유지됩니다. 최신 가격을 불러와 비교하세요.');}
    else{setData(latest);setLoadedAt(new Date().toISOString());setStale(false);}
  }).catch(report);window.addEventListener('catalog-updated',refresh);return()=>window.removeEventListener('catalog-updated',refresh);},[]);
  useEffect(()=>{const handler=e=>{if(dirty){e.preventDefault();e.returnValue='';}};window.addEventListener('beforeunload',handler);return()=>window.removeEventListener('beforeunload',handler);},[dirty]);
  const changedKeys=useMemo(()=>new Set([...compiled.changes.map(c=>c.key),...compiled.errors.map(e=>e.key)]),[compiled]);
  // 실제 변경된 행만 표시한다. 셀을 원래 값으로 되돌리면 이 목록에서도 빠진다.
  const filtered=useMemo(()=>{
    const result=filterPriceRows(rows,{...filters,changedOnly:false},drafts);
    return filters.changedOnly?result.filter(row=>changedKeys.has(row.key)):result;
  },[rows,filters,drafts,changedKeys]);
  const effectivePageSize=pageSize||Math.max(filtered.length,1);
  const pageCount=Math.max(1,Math.ceil(filtered.length/effectivePageSize)),safePage=Math.min(page,pageCount-1);
  const visible=pageSize?filtered.slice(safePage*pageSize,(safePage+1)*pageSize):filtered;
  const errorByCell=useMemo(()=>new Map(compiled.errors.map(e=>[`${e.key}:${e.field}`,e.message])),[compiled.errors]);
  const locked=busy||uploads>0||!!pending||!!confirmation||!!policyPreview||!!workspace.pending||canEdit&&!workspace.initialized;
  const editLocked=locked||!canEdit;
  const columns=view==='texts'?TEXT_FIELDS:PRICE_COLUMNS;
  const detailRow=rows.find(row=>row.key===detailKey);
  const brands=[...new Set(rows.map(row=>row.brand))];
  function changeFilter(name,value){setFilters(current=>({...current,[name]:value}));setPage(0);}
  function edit(key,field,value){if(editLocked)return;setDrafts(current=>({...current,[key]:{...current[key],[field]:value}}));}
  function cellValue(row,field){return Object.hasOwn(drafts[row.key]||{},field)?drafts[row.key][field]:displayValue(row.offer?.[field]);}
  function focusCell(rowIndex,field,step=1) {
    for(let index=rowIndex;index>=0&&index<visible.length;index+=step){
      const target=cellRefs.current.get(`${visible[index].key}:${field}`);
      if(target&&!target.disabled){target.focus();target.select?.();return;}
    }
  }
  function keyDown(event,rowIndex,field) {
    if(event.key==='Enter'&&event.target.tagName!=='TEXTAREA'||event.key==='ArrowDown'||event.key==='ArrowUp'){
      if(event.target.tagName==='TEXTAREA'&&!event.ctrlKey)return;
      const step=event.key==='ArrowUp'||event.shiftKey?-1:1;
      event.preventDefault();focusCell(rowIndex+step,field,step);
    }
  }
  // 붙여넣기는 현재 보이는 행 순서를 기준으로 적용한다. 다른 페이지·비활성 셀 침범은 거절한다.
  function paste(event,rowIndex,field) {
    const text=event.clipboardData.getData('text/plain');
    if(!text.includes('\t')&&!text.includes('\n'))return;
    if(view==='texts'&&!text.includes('\t')&&!text.startsWith('"'))return;
    event.preventDefault();
    const start=columns.indexOf(field),patch={};
    try {
      const lines=parseGridClipboard(text);
      if(lines.length>visible.length-rowIndex)throw Error('붙여넣을 행이 현재 페이지를 넘습니다. 페이지당 행 수를 늘리거나 나누어 붙여넣어 주세요.');
      for(let r=0;r<lines.length;r++){
        if(lines[r].length>columns.length-start)throw Error('붙여넣을 열이 편집 가능한 열 범위를 넘습니다.');
        const row=visible[rowIndex+r];
        for(let c=0;c<lines[r].length;c++){
          const target=columns[start+c],value=lines[r][c];
          if(cellDisabled(row,target)){if(value.trim())throw Error(`${rowName(row)}의 ${LABELS[target]}은 적용되지 않는 칸입니다.`);continue;}
          (patch[row.key]??={})[target]=value;
        }
      }
      setDrafts(current=>{const next={...current};for(const [key,fields]of Object.entries(patch))next[key]={...current[key],...fields};return next;});
      setStatus(`${lines.length}개 행에 붙여넣었습니다. 변경 내용을 확인한 뒤 저장하세요.`);
    }catch(e){setStatus(e.message);}
  }
  // 행 변경과 모델 외관 변경을 합친 뒤 전체 설정 계약을 다시 검증한다.
  async function makeConfig() {
    if(compiled.errors.length)throw Error(`입력 오류 ${compiled.errors.length}개를 먼저 수정해 주세요. ${compiled.errors[0].message}`);
    const next=structuredClone(compiled.config);
    for(const change of appearanceChanges){
      if(change.field==='colors')next.colors[change.model]=change.after;
      else{next.colorImages??={};next.colorImages[change.model]=change.after;}
    }
    validateConfig(next,catalog.products.map(p=>p.id));return next;
  }
  async function prepare() {
    if(editLocked||!dirty)return;
    try{const next=await makeConfig();setPending({config:next,changes:compiled.changes,appearanceChanges,revision:data.revision});}catch(e){report(e);}
  }
  // PUT에는 읽을 당시 revision을 보내 다른 관리자의 저장과 충돌하면 초안을 남긴다.
  async function save() {
    if(!pending||busy)return;
    setBusy(true);
    try {
      const result=await api('/api/admin/config',{method:'PUT',body:{revision:pending.revision,config:pending.config}});
      setData({...data,...result,config:pending.config});setLoadedAt(new Date().toISOString());setDrafts({});setAppearanceDrafts({});setPending(null);setStale(false);
      setStatus(`${new Set(pending.changes.map(c=>c.key)).size}개 가격 조건의 변경을 저장했습니다. 메인페이지에 반영되었습니다.`);
      await workspace.clear().catch(e=>setStatus(`공개는 완료됐지만 임시 작업 삭제를 확인하지 못했습니다. ${e.message}`));
      try{setHistory(await api('/api/admin/config/history'));}catch(e){report(e);}
    }catch(e){if(e.status===409){setStale(true);setPending(null);}report(e);}finally{setBusy(false);}
  }
  async function exportPolicy(){if(busy)return;setBusy(true);try{await downloadFile('/api/admin/policies/export.xlsx',`gyuhwan-policies-${(data.updatedAt||new Date().toISOString()).slice(0,10)}.xlsx`);setStatus(`고객페이지에 적용된 전체 모델 가격표를 내려받았습니다. 마지막 저장: ${savedAtLabel(data.updatedAt)}.`)}catch(e){report(e)}finally{setBusy(false)}}
  // Excel은 서버에서 먼저 차이를 미리 계산하며, 현재 revision과 일치할 때만 최종 적용한다.
  async function previewPolicy(file) {
    if(!file||editLocked||dirty)return;setBusy(true);
    try{
      if(!/\.xlsx$/i.test(file.name)||file.size>1500000)throw Error('1.5MB 이하 .xlsx 파일을 선택해 주세요.');
      const bytes=new Uint8Array(await file.arrayBuffer());let binary='';for(let i=0;i<bytes.length;i+=8192)binary+=String.fromCharCode(...bytes.subarray(i,i+8192));
      const request=Object.freeze({revision:data.revision,fileName:file.name,base64:btoa(binary)}),response=await api('/api/admin/policies/import-excel/preview',{method:'POST',body:request});
      if(response.revision!==request.revision)throw Error('저장된 정책이 변경되었습니다. 최신 가격을 불러온 뒤 파일을 다시 선택하세요.');
      setPolicyPreview({request,response,filename:file.name});setStatus('엑셀에서 가져올 변경 전후 값을 확인해 주세요.');
    }catch(e){if(e.status===409)setStale(true);report(e)}finally{setBusy(false)}
  }
  async function applyPolicy() {
    if(!policyPreview||busy||dirty||!canEdit)return;setBusy(true);
    try{await api('/api/admin/policies/import-excel',{method:'POST',body:policyPreview.request});setPolicyPreview(null);await load();setStatus('엑셀 가격 정책을 저장했습니다.');}
    catch(e){if(e.status===409){setPolicyPreview(null);setStale(true);}report(e);}finally{setBusy(false)}
  }
  function confirmAction(action,revision){if(busy||uploads)return;setConfirmation({action,revision,updatedAt:historySavedAt(history.find(item=>item.revision===revision))});}
  async function runConfirmation() {
    const action=confirmation;setBusy(true);
    try{
      if(action.action==='discard'){await workspace.clear();setDrafts({});setAppearanceDrafts({});setStatus('입력을 취소하고 저장된 가격표로 돌아왔습니다.');}
      if(action.action==='reload'){if(dirty)await workspace.clear();await load();}
      if(action.action==='restore'){if(!canEdit)return;await api('/api/admin/config/restore',{method:'POST',body:{revision:data.revision,targetRevision:action.revision}});await load();setStatus(`${savedAtLabel(action.updatedAt)}에 저장한 가격표로 되돌렸습니다. 고객페이지에도 적용되었습니다.`);await workspace.clear().catch(e=>setStatus(`복원은 완료됐지만 임시 작업 삭제를 확인하지 못했습니다. ${e.message}`));}
      setConfirmation(null);
    }catch(e){report(e)}finally{setBusy(false)}
  }
  async function reloadStored() {
    if(locked)return;
    confirmAction('reload');
  }
  // 아직 저장하지 않은 가격을 실제 고객 견적 화면에 전달해 확인하지만 DB에는 쓰지 않는다.
  async function openMainPreview(row) {
    if(locked)return;
    try{await makeConfig();setSiteSections((await api('/api/public/content')).sections);setPreviewSelection({model:row.model,carrier:row.carrier,type:row.type,discount:row.discount});setPreviewOpen(true);}catch(e){report(e)}
  }
  const previewConfig=useMemo(()=>{
    if(!compiled.config)return null;
    const next=structuredClone(compiled.config);
    for(const change of appearanceChanges){if(change.field==='colors')next.colors[change.model]=change.after;else{next.colorImages??={};next.colorImages[change.model]=change.after;}}
    return next;
  },[compiled.config,appearanceChanges]);
  if(!data||!catalog)return <section className="price-grid-editor"><h1>가격표 관리</h1><p role="status">{status}</p><button onClick={()=>load().catch(report)}>다시 불러오기</button></section>;
  const changedVisible=filtered.filter(row=>changedKeys.has(row.key)).length;
  const visibleModels=new Set(filtered.map(row=>row.model)).size;
  return <section className="price-grid-editor">
    <div className="page-heading"><div><p className="eyebrow">PRICE & POLICY</p><h1>가격표 관리</h1><p>전체 모델을 한 표에서 확인하고, 셀을 클릭해 바로 수정하세요.</p></div><SavedDataInfo updatedAt={data.updatedAt} loadedAt={loadedAt} dirty={dirty}/></div>
    <p role="status" className="notice">{status}</p>
    <DraftBar workspace={workspace} dirty={dirty}/>
    {stale&&<div className="content-stale"><p>다른 화면에서 가격표를 저장했습니다. 최신 저장값을 불러온 후 유지된 입력과 변경 전후를 다시 확인하세요.</p><button disabled={locked} onClick={()=>{setBusy(true);load({keepDrafts:true}).catch(report).finally(()=>setBusy(false));}}>최신 가격 불러오기 · 입력 유지</button></div>}
    <div className="price-toolbar">
      <div><strong>저장 전 변경 {changedKeys.size}개 조건</strong><span>{compiled.changes.length}개 항목{appearanceChanges.length?` · 색상·사진 ${appearanceChanges.length}개`:''}</span></div>
      <button disabled={locked||!dirty} onClick={()=>confirmAction('discard')}>변경 취소</button>
      <button className="primary" disabled={editLocked||!dirty||compiled.errors.length>0||stale} onClick={prepare}>고객페이지에 공개</button>
      <button disabled={busy} aria-expanded={excelOpen} onClick={()=>setExcelOpen(!excelOpen)}>엑셀 다운로드·업로드</button>
    </div>
    {excelOpen&&<PolicyTransfer canEdit={canEdit} data={data} busy={busy} dirty={dirty} pending={pending} uploads={uploads} preview={policyPreview} onDownload={exportPolicy} onFile={previewPolicy} onApply={applyPolicy} onCancel={()=>setPolicyPreview(null)} onDiscard={()=>confirmAction('discard')}/>}
    <div className="panel price-filters">
      <label>통신사<select value={filters.carrier} onChange={e=>changeFilter('carrier',e.target.value)}><option value="">전체 통신사</option>{['SKT','KT','LG U+'].map(v=><option key={v}>{v}</option>)}</select></label>
      <label>제조사<select value={filters.brand} onChange={e=>changeFilter('brand',e.target.value)}><option value="">전체 제조사</option>{brands.map(v=><option key={v} value={v}>{brandName(v)}</option>)}</select></label>
      <label>가입 유형<select value={filters.type} onChange={e=>changeFilter('type',e.target.value)}><option value="">전체 가입 유형</option>{['번호이동','기기변경','신규가입'].map(v=><option key={v}>{v}</option>)}</select></label>
      <label>할인 방식<select value={filters.discount} onChange={e=>changeFilter('discount',e.target.value)}><option value="">전체 할인 방식</option><option value="common">공통·유통망 지원금</option><option value="choice">선택약정</option></select></label>
      <label className="price-search">직접 검색<input type="search" value={filters.query} onChange={e=>changeFilter('query',e.target.value)} placeholder="모델명, 용량, 모델 코드, 요금제명"/></label>
      <div className="price-filter-bottom"><label className="check"><input type="checkbox" checked={filters.changedOnly} onChange={e=>changeFilter('changedOnly',e.target.checked)}/>수정한 행만 보기</label><span>{visibleModels}개 모델 · {filtered.length.toLocaleString('ko-KR')}개 조건 · 현재 검색에 수정 {changedVisible}개</span><button onClick={()=>{setFilters(INITIAL_FILTERS);setPage(0);}}>필터 초기화</button></div>
    </div>
    {view!=='appearance'&&<div className="price-model-summary"><strong>{visibleModels===catalog.products.length?`전체 ${visibleModels}개 모델`:`${catalog.products.length}개 중 ${visibleModels}개 모델`} 표시 중</strong><span>{filters.carrier||'전체 통신사'} · {filters.type||'전체 가입 유형'} · {filters.discount==='choice'?'선택약정':filters.discount==='common'?'공통·유통망 지원금':'전체 할인 방식'}</span><span>{pageSize?'페이지를 나누어 표시합니다':'모든 모델이 아래 표에 있습니다. 아래로 스크롤해 확인하세요.'}</span></div>}
    <div className="price-view-tabs" aria-label="가격표 표시 항목">{[['prices','금액·요금제'],['texts','안내 문구'],['appearance','색상·사진']].map(([id,label])=><button key={id} aria-pressed={view===id} onClick={()=>setView(id)}>{label}</button>)}</div>
    {compiled.errors.length>0&&<div role="alert" className="price-errors"><strong>입력 오류 {compiled.errors.length}개</strong><p>{compiled.errors[0].message}</p><button onClick={()=>{const error=compiled.errors[0],row=rows.find(r=>r.key===error.key);setFilters({...INITIAL_FILTERS,carrier:row?.carrier||'',type:row?.type||'',discount:row?.discount||'',query:row?.product.code||row?.product.name||''});setView(TEXT_FIELDS.includes(error.field)&&error.field!=='planName'?'texts':'prices');setPage(0);setDetailKey(error.key);}}>오류 행 확인</button></div>}
    {view==='appearance'?<><label className="price-model-selector">색상·사진을 수정할 모델<select value={model} onChange={e=>setModel(e.target.value)}>{catalog.products.map(p=><option key={p.id} value={String(p.id)}>{p.name} · {p.code}</option>)}</select></label>{catalog.products.find(p=>String(p.id)===model)&&<ModelAppearance key={model} product={catalog.products.find(p=>String(p.id)===model)} catalog={catalog} config={data.config} draft={appearanceDrafts[model]} onChange={(id,value)=>setAppearanceDrafts(current=>({...current,[id]:value}))} onUploadState={delta=>setUploads(n=>n+delta)} disabled={editLocked} user={user}/>}</>:<>
      <div className="bulk-price-tools"><strong>선택한 조건 {selected.size}개</strong><button disabled={editLocked||!filtered.length} onClick={()=>setSelected(new Set(filtered.map(row=>row.key)))}>검색 결과 전체 선택 · {filtered.length}개</button><button disabled={!selected.size} onClick={()=>setSelected(new Set())}>선택 해제</button><button className="primary" disabled={editLocked||!selected.size||compiled.errors.length>0} onClick={()=>setBulkOpen(true)}>선택한 행 일괄 수정</button></div><div className="price-table-description"><p>금액은 원 단위 · <strong>빈칸은 미등록, 0은 실제 0원</strong> · Tab으로 다음 칸, Enter·↑↓로 같은 열 이동</p><p>엑셀의 여러 셀을 복사해 붙여넣을 수 있습니다. 파란색 칸은 저장 전 변경입니다.</p></div>
      <div className="price-grid-scroll" tabIndex={0} role="region" aria-label="편집 가능한 가격표 · 가로로 스크롤해 전체 열 보기">
        <table className={`price-grid-table ${view==='texts'?'is-texts':''}`}><caption className="sr-only">모델·통신사·가입 유형·할인 방식별 가격표. 셀 입력 후 변경 내용 저장 버튼으로 일괄 저장합니다.</caption>
          <thead><tr><th scope="col" className="price-row-select">선택</th><th scope="col" className="price-model-col">모델 · 용량 / 코드</th><th scope="col">통신사</th><th scope="col">가입 유형</th><th scope="col">할인 방식</th>{columns.map(field=><th key={field} scope="col" className={field==='planName'?'price-plan-col':''}>{LABELS[field]}{field==='discountBase'&&<small>25% 할인 대상</small>}</th>)}{view==='prices'&&<><th scope="col">문의 표시</th><th scope="col" className="price-calculated-col">할부이자<small>24개월 자동 계산</small></th><th scope="col" className="price-calculated-col">첫 달 월 납부액<small>유심비 포함</small></th></>}<th scope="col">견적</th></tr></thead>
          <tbody>{visible.map((row,index)=>{
            const offer=compiled.config?.offers[row.key]||row.offer||{},quote=calculatePurchaseQuote(offer,row.discount,24,row.type);
            return <tr key={row.key} className={`${changedKeys.has(row.key)?'is-modified':''} ${compiled.errors.some(e=>e.key===row.key)?'has-error':''}`}>
              <td className="price-row-select"><input type="checkbox" disabled={editLocked} checked={selected.has(row.key)} aria-label={`${rowName(row)} 선택`} onChange={e=>setSelected(current=>{const next=new Set(current);if(e.target.checked)next.add(row.key);else next.delete(row.key);return next;})}/></td><th scope="row" className="price-model-col"><strong>{row.product.name}</strong><span>{brandName(row.brand)} · {row.product.carrierCodes?.[row.carrier]||row.product.code}</span>{changedKeys.has(row.key)&&<small className="price-draft-tag">수정 중</small>}</th>
              <td className="price-condition"><span className={`carrier-tag carrier-${row.carrier==='SKT'?'skt':row.carrier==='KT'?'kt':'lg'}`}>{row.carrier}</span></td><td className="price-condition">{row.type}</td><td className="price-condition">{discountName(row)}</td>
              {columns.map(field=>{
                const modified=compiled.changes.some(change=>change.key===row.key&&change.field===field),error=errorByCell.get(`${row.key}:${field}`);
                const props={value:cellValue(row,field),disabled:editLocked||cellDisabled(row,field),onChange:e=>edit(row.key,field,e.target.value),onFocus:e=>e.target.select(),onKeyDown:e=>keyDown(e,index,field),onPaste:e=>paste(e,index,field),'aria-label':`${rowName(row)} · ${LABELS[field]}`,'aria-invalid':error?true:undefined,title:error|| (cellDisabled(row,field)?'이 할인 방식에는 적용하지 않습니다.':undefined),ref:node=>{const key=`${row.key}:${field}`;if(node)cellRefs.current.set(key,node);else cellRefs.current.delete(key);},placeholder:cellDisabled(row,field)?'해당 없음':'미등록'};
                return <td key={field} className={`price-edit-cell ${modified?'is-modified':''} ${error?'has-error':''}`}>{view==='texts'&&field!=='planName'?<textarea {...props} rows={2} maxLength={6000}/>:<input {...props} type="text" inputMode={field==='planName'?'text':'numeric'} maxLength={field==='planName'?200:32}/>}</td>;
              })}
              {view==='prices'&&<><td className="price-inquiry-cell"><input type="checkbox" checked={Object.hasOwn(drafts[row.key]||{},'supportInquiry')?!!drafts[row.key].supportInquiry:!!offer.supportInquiry} disabled={editLocked} onChange={e=>edit(row.key,'supportInquiry',e.target.checked)} aria-label={`${rowName(row)} · 문의 표시`}/></td><td className="price-computed">{displayValue(quote.installmentFee)||'—'}</td><td className="price-computed">{displayValue(quote.schedule?.[0]?.total)||'—'}</td></>}
              <td><button className="price-detail-button" onClick={()=>setDetailKey(row.key)}>상세</button></td>
            </tr>;
          })}</tbody>
        </table>{visible.length===0&&<div className="price-empty"><h2>검색 결과가 없습니다.</h2><p>통신사·제조사 필터나 검색어를 바꿔 주세요.</p><button onClick={()=>{setFilters(INITIAL_FILTERS);setPage(0);}}>전체 가격표 보기</button></div>}
      </div>
      <div className="price-pagination"><span>{pageSize?(filtered.length?`${safePage*pageSize+1}–${Math.min((safePage+1)*pageSize,filtered.length)} / ${filtered.length}개 조건`:'0개 조건'):`${visibleModels}개 모델 · ${filtered.length}개 조건 모두 표시`}</span><label>표시할 행<select value={pageSize} onChange={e=>{setPageSize(Number(e.target.value));setPage(0);}}><option value={0}>전체 보기</option>{[25,50,100].map(n=><option value={n} key={n}>{n}개씩</option>)}</select></label>{pageSize>0&&<><button disabled={safePage===0} onClick={()=>setPage(safePage-1)}>이전</button><span>{safePage+1} / {pageCount}</span><button disabled={safePage>=pageCount-1} onClick={()=>setPage(safePage+1)}>다음</button></>}</div>
    </>}
    <PolicySchedulePanel revision={data.revision} makeConfig={makeConfig} dirty={dirty} canEdit={canEdit} disabled={locked||stale||compiled.errors.length>0} onChanged={()=>load({keepDrafts:true}).catch(report)}/>{bulkOpen&&<BulkPriceDialog config={data.config} drafts={drafts} products={catalog.products} rows={rows} selectedKeys={[...selected]} onClose={()=>setBulkOpen(false)} onApply={result=>{setDrafts(result.drafts);setBulkOpen(false);setStatus(`${result.targetCount}개 조건을 내 편집본에 반영했습니다. 공개 전 변경 내용을 확인하세요.`);}}/>}<section className="stored-data-panel"><h2>현재 적용 중인 가격표</h2><p>이 서버의 <strong>고객 메인페이지에 현재 적용된 전체 모델 가격·요금제·구매 조건</strong>입니다.</p><p>마지막 저장 <strong>{savedAtLabel(data.updatedAt)}</strong> · 이 화면에서 확인 <strong>{savedAtLabel(loadedAt)}</strong></p><div className="stored-data-actions"><button disabled={locked} onClick={reloadStored}>현재 적용 중인 가격표 다시 확인</button><span className="hint">저장한 데이터만 다시 가져옵니다. 화면에서 수정 중인 값은 저장 전입니다.</span></div></section>
    <details className="panel price-history"><summary>이전 저장본으로 되돌리기</summary><p className="hint">선택한 시각에 저장된 가격·구매 조건·색상·사진 전체를 고객페이지에 다시 적용합니다.</p>{history.length?history.map(item=><div className="history-row" key={item.revision}><span className="saved-snapshot"><strong>{savedAtLabel(historySavedAt(item))} 저장본</strong><small>전체 모델 가격·요금제·구매 조건·색상·사진</small></span><button disabled={editLocked} onClick={()=>confirmAction('restore',item.revision)}>이 저장본으로 되돌리기</button></div>):<p className="saved-history-empty">이전 저장본이 없습니다.</p>}</details>
    {pending&&<Dialog title="가격표 변경 확인" busy={busy} onClose={()=>setPending(null)} wide><p>전체 필터의 {new Set(pending.changes.map(c=>c.key)).size}개 조건 · {pending.changes.length}개 항목을 한 번에 저장합니다.</p><div className="price-review-scroll"><table className="price-review-table"><thead><tr><th>모델 · 적용 조건</th><th>항목</th><th>현재 저장값</th><th>변경할 값</th></tr></thead><tbody>{pending.changes.map(change=><tr key={`${change.key}:${change.field}`}><th>{rowName(rows.find(r=>r.key===change.key))}</th><td>{LABELS[change.field]||change.field}</td><td>{reviewValue(change.field,change.before)}</td><td>{reviewValue(change.field,change.after)}</td></tr>)}{pending.appearanceChanges.map(change=><tr key={`${change.model}:${change.field}`}><th>{catalog.products.find(p=>String(p.id)===change.model)?.name} · 전체 조건</th><td>{change.field==='colors'?'색상':'색상별 사진'}</td><td>{change.field==='colors'?change.before.join(', ')||'기본 색상':`${Object.keys(change.before).length}개 사진`}</td><td>{change.field==='colors'?change.after.join(', ')||'기본 색상':`${Object.keys(change.after).length}개 사진 변경`}</td></tr>)}</tbody></table></div><div className="price-dialog-actions"><button disabled={busy} onClick={()=>setPending(null)}>계속 수정</button><button className="primary" disabled={busy} onClick={save}>{busy?'공개 중…':'확인한 가격표 고객페이지에 공개'}</button></div></Dialog>}
    {confirmation&&<Dialog title={confirmation.action==='discard'?'입력한 변경 취소':confirmation.action==='restore'?'이전 저장본으로 되돌리기':'현재 적용된 가격표 다시 확인'} busy={busy} onClose={()=>setConfirmation(null)}>{confirmation.action==='reload'?<><p>고객페이지에 현재 적용된 전체 모델의 가격·요금제·구매 조건을 서버에서 다시 가져옵니다.</p><p>현재 이 화면의 저장본: <strong>{savedAtLabel(data.updatedAt)}</strong> 마지막 저장</p><p>다른 관리자가 그 뒤 저장했다면 새 저장본을 가져옵니다.{dirty?' 저장하지 않은 입력은 취소됩니다.':''}</p></>:<p>{confirmation.action==='restore'?`${savedAtLabel(confirmation.updatedAt)}에 저장한 가격·구매 조건·색상·사진 전체로 되돌립니다. 저장 전 입력도 취소됩니다.`:'저장하지 않은 가격·문구·색상·사진 입력을 취소합니다.'}</p>}<div className="price-dialog-actions"><button disabled={busy} onClick={()=>setConfirmation(null)}>계속 수정</button><button className={confirmation.action==='reload'?'primary':'danger'} disabled={busy} onClick={runConfirmation}>{confirmation.action==='restore'?'이 저장본 적용하기':confirmation.action==='reload'?'현재 가격표 가져오기':'변경 취소하기'}</button></div></Dialog>}
    {detailRow&&<Dialog title={detailRow.product.name+' · 견적 상세'} onClose={()=>setDetailKey(null)}>
      <p>{detailRow.carrier} · {detailRow.type} · {discountName(detailRow)}</p><PriceDetail row={detailRow} offer={compiled.config?.offers[detailRow.key]||detailRow.offer||{}} errors={compiled.errors.filter(e=>e.key===detailRow.key)}/><div className="price-dialog-actions"><button disabled={locked||compiled.errors.length>0} onClick={()=>{setDetailKey(null);openMainPreview(detailRow);}}>메인페이지에서 미리보기</button><button onClick={()=>{setDetailKey(null);setModel(detailRow.model);setView('appearance');}}>이 모델 색상·사진 수정</button></div>
    </Dialog>}
    {previewOpen&&previewConfig&&siteSections.length>0&&<MainPagePreview open sections={siteSections} config={previewConfig} selection={previewSelection} sectionId="shop" onClose={()=>setPreviewOpen(false)}/>}
  </section>;
}

function PriceDetail({row,offer,errors}) {
  const quote=calculatePurchaseQuote(offer,row.discount,24,row.type);
  return <><div className="price-detail-amounts">{[['일시불 원금',quote.principal],['24개월 할부이자',quote.installmentFee],['월 통신요금',quote.planMonthly],['선택약정 할인',quote.planDiscount],['첫 달 유심비',quote.simFee],['첫 달 예상 월 납부액',quote.schedule?.[0]?.total]].map(([label,value])=><div className="amount" key={label}><span>{label}</span><strong>{value==null?'미등록':`${displayValue(value)}원`}</strong></div>)}</div><p className="hint">할부이자는 연 5.9%·24개월로 자동 계산합니다. 번호이동 첫 달에는 유심비 7,700원이 포함됩니다.</p>{offer.addonSchedule&&<details><summary>기존 월별 부가서비스 요금</summary><p className="hint">월 부가요금이나 청구 개월을 바꾸면 이 월별 내역을 해제하고 입력한 요금·기간으로 계산합니다.</p><ol className="price-months">{offer.addonSchedule.map((amount,index)=><li key={index}>{index+1}개월 · {displayValue(amount)}원</li>)}</ol></details>}{errors.map((error,index)=><p className="error" key={index}>{error.message}</p>)}{quote.issues?.map(issue=><p className="error" key={issue}>{issue}</p>)}</>;
}
