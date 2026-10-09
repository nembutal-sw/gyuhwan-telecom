// 서버의 관리자 수정 기록을 조회한다. 화면용 한글·금액 변환은 원본 기록을 변경하지 않는다.
import React, { useEffect, useRef, useState } from 'react';
import { api } from './api';
import './account-audit.css';

export const auditActions = {
  PRICE_UPDATE: '가격표·구매 조건 수정', PRICE_EXCEL_IMPORT: '엑셀 가격표 적용', PRICE_RESTORE: '가격표 이전 내용으로 되돌림',
  CONTENT_UPDATE: '메인 페이지 수정', CONTENT_RESTORE: '메인 페이지 복원', IMAGE_UPLOAD: '사진 업로드',
  ACCOUNT_CREATE: '관리자 계정 추가', ACCOUNT_UPDATE: '관리자 계정 수정',
};
const timestamp = value => {
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? String(value || '') : date.toLocaleString('ko-KR', {
    timeZone: 'Asia/Seoul', year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', second: '2-digit', hourCycle: 'h23',
  });
};
const valueLabels = {
  retail: '출고가', common: '공통·유통망 지원금', support: '추가 지원금', planFee: '월정액', planName: '요금제명',
  addonFee: '부가서비스 요금', addonMonths: '부가서비스 이용 기간', discountBase: '선택약정 할인 기준',
  installmentFee: '할부이자', supportInquiry: '가격 문의 표시', planDetails: '요금제·유지 조건 안내',
  addonDetails: '부가서비스 안내', penaltyDetails: '위약금 안내', terms: '구매 조건·유의사항',
  addonSchedule: '월별 부가서비스 요금', colors: '선택 색상', colorImages: '색상 사진',
  name: '이름', title: '제목', description: '설명', url: '이동 주소', image: '사진 주소', imageUrl: '사진 주소',
  src: '파일 주소', alt: '사진 설명', enabled: '사용 여부', username: '로그인 아이디', owner: '대표 관리자 여부',
  passwordChanged: '비밀번호 변경 여부', month: '개월차', months: '이용 기간', fee: '요금', amount: '금액',
};
const moneyFields = new Set(['retail', 'common', 'support', 'planFee', 'addonFee', 'discountBase', 'installmentFee', 'fee', 'amount']);
const monthFields = new Set(['addonMonths', 'months']);
const sensitive = field => /password|credential|secret/i.test(field);
const fieldKey = field => String(field).split(/\s*\/\s*|\./).at(-1);
function friendlyLabel(value) {
  return String(value || '변경 항목').split(/\s+·\s+/).map(part => valueLabels[part]
    || ({ skt: 'SKT', kt: 'KT', lg: 'LG U+', mnp: '번호이동', upgrade: '기기변경', new: '신규가입', choice: '선택약정', subsidy: '지원금 할인' })[part]
    || part.replace(/\[\d+\]/g, '').trim()).join(' · ');
}
// 금액·기간 단위를 붙이고 비밀번호·비밀값·이미지 데이터는 상세 화면에도 노출하지 않는다.
function display(value, field = '') {
  if (sensitive(field)) return value == null ? '해당 없음' : typeof value === 'boolean' ? value ? '변경함' : '변경 없음' : '변경 정보만 기록';
  if (value === null || value === undefined) return '미등록';
  if (value === '') return '빈칸';
  if (typeof value === 'boolean') return value ? '예' : '아니오';
  if (typeof value === 'string' && /^data:image\//i.test(value)) return '등록 이미지 데이터';
  if (typeof value === 'number') {
    const key = fieldKey(field);
    return `${value.toLocaleString('ko-KR')}${moneyFields.has(key) ? '원' : monthFields.has(key) ? '개월' : key === 'month' ? '개월차' : ''}`;
  }
  return String(value);
}
// 배열과 객체는 중첩된 변경값으로 펼쳐 보여 주되 민감 필드는 재귀 탐색 전에 가린다.
function AuditValue({ value, field = '' }) {
  if (!sensitive(field) && value && typeof value === 'object') {
    if (Array.isArray(value)) return value.length ? <ul className="audit-value-list">{value.map((item, index) => <li key={index}><AuditValue value={item} field={field}/></li>)}</ul> : <span className="audit-empty-value">등록 항목 없음</span>;
    const entries = Object.entries(value);
    return entries.length ? <dl className="audit-value-object">{entries.map(([key, item]) => <div key={key}><dt>{valueLabels[key] || key}</dt><dd><AuditValue value={item} field={key}/></dd></div>)}</dl> : <span className="audit-empty-value">등록 항목 없음</span>;
  }
  return <div className="audit-value-text">{display(value, field)}</div>;
}
function entryTitle(item) {
  if (item.action?.startsWith('PRICE_')) return '가격표';
  if (item.action?.startsWith('CONTENT_')) return '메인페이지 내용';
  if (item.action?.startsWith('ACCOUNT_')) {
    const account = String(item.target || '').match(/^관리자\s*계정\s*#?\s*[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?:\s*·\s*(.*))?\s*$/i);
    if (account) return account[1]?.trim() ? `관리자 계정 · ${account[1].trim()}` : '관리자 계정';
    if (!item.target) return '관리자 계정';
  }
  return item.target || '관리자 변경';
}

export default function AuditLogViewer({ onRequireLogin }) {
  const [items, setItems] = useState([]);
  const [search, setSearch] = useState('');
  const [action, setAction] = useState('');
  const [filters, setFilters] = useState({ q: '', action: '' });
  const [nextOffset, setNextOffset] = useState(0);
  const [hasMore, setHasMore] = useState(false);
  const [busy, setBusy] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState('');
  // 검색·더 보기 응답 순서가 바뀌어도 오래된 응답이 최신 목록을 덮지 않도록 요청 번호를 쓴다.
  const requestId = useRef(0);
  const mounted = useRef(true);
  // offset 기반 추가 조회는 ID로 중복을 제거하고, 새 검색은 첫 페이지부터 다시 읽는다.
  async function load(offset = 0, selected = filters) {
    const id = ++requestId.current;
    setBusy(true);
    setError('');
    const query = new URLSearchParams({ offset: String(offset), limit: '50' });
    if (selected.q) query.set('q', selected.q);
    if (selected.action) query.set('action', selected.action);
    try {
      const data = await api(`/api/admin/audit?${query}`);
      if (!mounted.current || id !== requestId.current) return;
      setItems(current => offset ? [...new Map([...current, ...(data.items || [])].map(item => [item.id, item])).values()] : data.items || []);
      setHasMore(!!data.hasMore);
      setNextOffset(Number.isInteger(data.nextOffset) ? data.nextOffset : offset + (data.items || []).length);
      setLoaded(true);
    } catch (problem) {
      if (!mounted.current || id !== requestId.current) return;
      setError(problem.message);
      if (problem.status === 401) { try { await onRequireLogin?.({ reason: 'expired' }); } catch { /* 로그인 화면에서 재인증을 처리한다. */ } }
    } finally { if (mounted.current && id === requestId.current) setBusy(false); }
  }
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; requestId.current++; }; }, []);
  useEffect(() => { load(0, filters); }, [filters]);
  const options = [...new Set([...Object.keys(auditActions), ...items.map(item => item.action)])];
  return <section className="audit-viewer">
    <div className="page-heading"><div><p className="eyebrow">CHANGE HISTORY</p><h1>수정 기록</h1><p>누가 어떤 내용을 수정했는지 최신 기록부터 확인합니다. 시간은 한국 시간입니다.</p></div><button type="button" disabled={busy} onClick={() => load()}>새로고침</button></div>
    <form className="audit-panel audit-filters" onSubmit={event => { event.preventDefault(); setLoaded(false); setFilters({ q: search.trim(), action }); }}>
      <label>관리자 아이디 · 대상 · 작업 검색<input type="search" maxLength={100} value={search} onChange={event => setSearch(event.target.value)} placeholder="아이디 또는 수정 대상"/></label>
      <label>작업 종류<select value={action} onChange={event => setAction(event.target.value)}><option value="">전체 작업</option>{options.map(value => <option key={value} value={value}>{auditActions[value] || value}</option>)}</select></label>
      <div className="audit-filter-actions"><button type="submit" className="primary" disabled={busy}>검색</button><button type="button" disabled={busy} onClick={() => { setSearch(''); setAction(''); setLoaded(false); setFilters({ q: '', action: '' }); }}>초기화</button></div>
    </form>
    {error && <p className="audit-error" role="alert">{error}</p>}
    <p className="audit-status" role="status">{busy ? '수정 기록을 불러오는 중…' : loaded ? `불러온 기록 ${items.length}건` : '수정 기록 연결 확인 중…'}</p>
    {loaded && !items.length && !busy && <p className="audit-empty">{filters.q || filters.action ? '검색 조건에 맞는 수정 기록이 없습니다.' : '수정 기록이 없습니다.'}</p>}
    <div className="audit-list">{items.map(item => <article key={item.id} className="audit-panel audit-entry"><div className="audit-entry-heading"><div><span className="audit-action">{auditActions[item.action] || item.action}</span><h2>{entryTitle(item)}</h2><p><strong>{item.actor || '알 수 없음'}</strong><span> · </span><time dateTime={item.createdAt}>{timestamp(item.createdAt)}</time></p></div><span className="audit-change-count">{item.changes?.length || 0}개 항목 수정</span></div><details className="audit-changes"><summary>무엇이 바뀌었나요? · 변경 전후 확인</summary>{item.changes?.length ? <div className="audit-table-wrap"><table><thead><tr><th scope="col">수정한 항목</th><th scope="col">수정 전</th><th scope="col">수정 후</th></tr></thead><tbody>{item.changes.map((change, index) => <tr key={`${change.field}-${index}`}><th scope="row">{friendlyLabel(change.label || change.field)}{change.field && <details className="audit-field-details"><summary>항목 코드</summary><code className="audit-field-path">{change.field}</code></details>}</th><td><AuditValue value={change.before} field={change.field}/></td><td><AuditValue value={change.after} field={change.field}/></td></tr>)}</tbody></table></div> : <p className="hint">상세 변경 항목이 없습니다.</p>}<details className="audit-record-details"><summary>기록 식별 정보</summary><p className="audit-record-id">기록 ID: {item.id}</p>{item.target && <p className="audit-record-id">저장된 대상: {item.target}</p>}</details></details></article>)}</div>
    {hasMore && <div className="audit-pagination"><button type="button" disabled={busy} onClick={() => load(nextOffset)}>{busy ? '불러오는 중…' : '기록 더 보기'}</button></div>}
  </section>;
}
