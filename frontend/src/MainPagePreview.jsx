// 관리자 인증이 필요한 실제 고객 페이지를 iframe에 띄워 저장 전 초안을 postMessage로 주고받는다.
import React, { useEffect, useRef, useState } from 'react';
import { regionForSection } from '../content-regions.js';

export default function MainPagePreview({ open, embedded = false, editMode = false, editingDisabled = false, sections, config, selection, sectionId = 'shop', focusFieldId, selectedFieldId, modifiedFieldIds = [], locateTick, onClose, onSelectSection, onEditContent, apiRef }) {
  const frame = useRef(null);
  const viewport = useRef(null);
  const payload = useRef(null);
  const wasOpen = useRef(false);
  const ready = useRef(false);
  const [src, setSrc] = useState('');
  const [device, setDevice] = useState(() => window.innerWidth <= 760 ? 'mobile' : 'desktop');
  const [actualSize, setActualSize] = useState(false);
  const [size, setSize] = useState({ width: 500, height: 650 });
  const [loading, setLoading] = useState(true);
  const [problem, setProblem] = useState('');
  const [refresh, setRefresh] = useState(0);
  // 최신 입력값은 ref에 보관한다. iframe 재생성 없이 메시지만 보내 스크롤·선택 상태를 유지한다.
  payload.current = { type: 'gyuhwan-preview', sections, config, selection, sectionId, focusFieldId, editMode, editingDisabled, modifiedFieldIds, selectedFieldId, visualEditor: embedded };
  const region = regionForSection(sectionId);
  const liveEditor = embedded;
  const reloadSections = liveEditor ? null : sections;
  const reloadFocus = liveEditor ? null : focusFieldId;
  const reloadSection = liveEditor ? null : sectionId;

  // 수신 대상은 현재 출처로 고정한다. iframe은 준비 완료 신호를 보낸 뒤 이 초안을 받는다.
  function postDraft() { frame.current?.contentWindow?.postMessage(payload.current, window.location.origin); }
  function postLocation() { frame.current?.contentWindow?.postMessage({ type: 'gyuhwan-preview-locate', sectionId, focusFieldId }, window.location.origin); }
  useEffect(() => {
    if (!apiRef) return;
    apiRef.current = { focusSelected: () => frame.current?.contentWindow?.postMessage({ type: 'gyuhwan-preview-focus' }, window.location.origin) };
    return () => { apiRef.current = null; };
  }, [apiRef]);
  useEffect(() => {
    // 독립 미리보기는 조건 변경 시 새 페이지를 만들지만, 상시 직접 편집 화면은 섹션 초안으로 새로고침하지 않는다.
    if (!open) { wasOpen.current = false; ready.current = false; return; }
    const first = !wasOpen.current;
    wasOpen.current = true;
    const timer = setTimeout(() => {
      ready.current = false; setLoading(true); setProblem('');
      setSrc(`/admin_preview?draft=${Date.now()}-${refresh}`);
    }, first ? 0 : liveEditor ? 0 : 450);
    return () => clearTimeout(timer);
  }, [open, reloadSections, config, selection, reloadSection, reloadFocus, refresh]);

  useEffect(() => {
    if (!open) return;
    // 같은 출처와 현재 iframe 창에서 온 메시지만 수락한다. 섹션 ID도 현재 데이터와 대조한다.
    function receive(event) {
      if (event.origin !== window.location.origin || event.source !== frame.current?.contentWindow) return;
      if (event.data?.type === 'gyuhwan-preview-ready') postDraft();
      if (event.data?.type === 'gyuhwan-preview-rendered') {
        const firstRender = !ready.current;
        ready.current = true; setLoading(false); setProblem(event.data.draft ? '' : '수정 내용을 전달하지 못했습니다. 다시 보기를 눌러 주세요.');
        if (firstRender && embedded) frame.current?.contentWindow?.postMessage({ ...payload.current, type: 'gyuhwan-preview-patch' }, window.location.origin);
      }
      if (event.data?.type === 'gyuhwan-preview-error') setProblem(String(event.data.message || '화면에 수정 내용을 반영하지 못했습니다. 입력값을 확인해 주세요.'));
      if (event.data?.type === 'gyuhwan-select-section' && sections.some(section => section.id === event.data.sectionId)) onSelectSection?.(event.data.sectionId);
      if (event.data?.type === 'gyuhwan-edit-content' && editMode && !editingDisabled && sections.some(section => section.id === event.data.sectionId)) onEditContent?.(event.data);
    }
    window.addEventListener('message', receive);
    return () => window.removeEventListener('message', receive);
  }, [open, sections, onSelectSection, onEditContent, editMode, editingDisabled, embedded]);

  useEffect(() => {
    // 직접 편집 중에는 변경된 초안만 patch하여 iframe의 위치와 열린 고객 UI를 보존한다.
    if (!open || !liveEditor) return;
    const timer = setTimeout(() => {
      if (ready.current) frame.current?.contentWindow?.postMessage({ ...payload.current, type: 'gyuhwan-preview-patch' }, window.location.origin);
    }, 140);
    return () => clearTimeout(timer);
  }, [open, liveEditor, sections, editMode, editingDisabled, modifiedFieldIds, selectedFieldId, sectionId]);

  useEffect(() => {
    if (!open || !viewport.current) return;
    const resize = () => setSize({ width: viewport.current.clientWidth, height: viewport.current.clientHeight });
    const observer = new ResizeObserver(resize); observer.observe(viewport.current); resize();
    return () => observer.disconnect();
  }, [open]);
  useEffect(() => { if (open && locateTick) postLocation(); }, [open, locateTick]);
  useEffect(() => {
    if (!open || !loading || !src) return;
    const timer = setTimeout(() => { setLoading(false); setProblem('페이지를 불러오지 못했습니다. 다시 보기 또는 관리자 로그인을 확인해 주세요.'); }, 15000);
    return () => clearTimeout(timer);
  }, [open, loading, src]);

  if (!open) return null;
  const width = device === 'mobile' ? 390 : embedded ? Math.max(1024, Math.min(1440, size.width)) : 1280;
  const scale = actualSize ? 1 : Math.min(1, size.width / width);
  const height = Math.max(400, Math.round(size.height / Math.max(scale, .1)));
  return <aside className={`draft-main-preview panel${embedded ? ' visual-page-canvas' : ''}`} aria-label={embedded ? '메인페이지 직접 편집 화면' : '저장 전 메인페이지 미리보기'}>
    {!embedded && <div className="draft-preview-heading"><div><h2>저장 전 메인페이지 미리보기</h2><p>아직 저장하지 않은 수정값을 보여줍니다.</p></div><button type="button" onClick={onClose} aria-label="수정 내용 미리보기 닫기">닫기</button></div>}
    <div className="draft-preview-tools"><div className="draft-preview-devices" aria-label="미리보기 화면 크기"><button type="button" aria-pressed={device === 'desktop'} onClick={() => setDevice('desktop')}>PC</button><button type="button" aria-pressed={device === 'mobile'} onClick={() => setDevice('mobile')}>모바일</button></div><button type="button" aria-pressed={actualSize} title="원래 글자 크기로 보고, 가로로 이동할 수 있습니다." onClick={() => setActualSize(value => !value)}>{actualSize ? '화면에 맞춤' : '실제 크기'}</button>{!embedded && <button type="button" onClick={postLocation}>이 구역 위치 보기</button>}<button type="button" onClick={() => setRefresh(value => value + 1)}>다시 보기</button>{embedded && <span className="visual-canvas-hint">{loading ? '페이지를 불러오는 중…' : editMode ? '문구·사진은 더블클릭 · 메뉴·링크는 한 번 클릭 · 영상은 수정 버튼' : '저장 전 모습을 확인하세요.'}</span>}</div>
    {!embedded && <p className="draft-preview-region"><strong>{region.number} {region.title}</strong><span>{loading ? '수정 내용 반영 중…' : onSelectSection ? '구역 이름표를 누르면 해당 편집 항목으로 이동합니다.' : '선택한 모델·통신사·가입 유형·할인 방법의 견적을 표시합니다.'}</span></p>}
    {problem && <p role="alert" className="error">{problem}</p>}
    <div className="draft-preview-viewport" ref={viewport} aria-busy={loading} style={{ overflow: actualSize ? 'auto' : 'hidden' }}>{src && <iframe ref={frame} key={src} src={src} title={embedded ? '더블클릭하여 문구 사진 동영상을 수정하는 메인페이지' : '저장 전 수정 내용을 적용한 실제 메인페이지'} style={{ width, height, transform: `scale(${scale})`, left: Math.max(0, (size.width - width * scale) / 2) }} onLoad={() => { try { if (frame.current?.contentWindow?.location.pathname === '/admin_login') { setLoading(false); setProblem('관리자 로그인 후 다시 열어 주세요.'); } else postDraft(); } catch {} }} />}</div>
  </aside>;
}

