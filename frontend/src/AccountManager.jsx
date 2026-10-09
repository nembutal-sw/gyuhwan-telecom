// 관리자 계정 화면. 대표 관리자만 목록 조회·계정 추가·타인 수정이 가능하며 서버가 최종 권한을 검사한다.
import React, { useEffect, useRef, useState } from 'react';
import { api } from './api';
import './account-audit.css';

const emptyForm = () => ({ username: '', currentPassword: '', newPassword: '', confirmPassword: '' });
const usernameValid = value => /^[A-Za-z0-9._-]{3,50}$/.test(value);
const passwordValid = value => value.length >= 16 && value.length <= 128;

export default function AccountManager({ onRequireLogin, onAccountChanged }) {
  const [me, setMe] = useState(null);
  const [accounts, setAccounts] = useState([]);
  const [targetId, setTargetId] = useState('me');
  const [mode, setMode] = useState('edit');
  const [form, setForm] = useState(emptyForm);
  const [review, setReview] = useState(null);
  const [busy, setBusy] = useState(false);
  const [status, setStatus] = useState('계정 정보를 불러오는 중…');
  const [error, setError] = useState('');
  const reviewRef = useRef(null);
  const mounted = useRef(true);
  // 내 계정과 목록에서 선택한 계정을 같은 수정 폼으로 처리한다.
  const target = targetId === 'me' ? me : accounts.find(account => account.id === targetId);

  // 실패·성공 뒤 비밀번호 입력을 지워 화면 메모리에 불필요하게 남기지 않는다.
  function clearPasswords() {
    setForm(value => ({ ...value, currentPassword: '', newPassword: '', confirmPassword: '' }));
  }
  async function failure(problem) {
    if (!mounted.current) return;
    clearPasswords();
    setReview(null);
    if (problem.status === 401) {
      setError('로그인이 만료되었습니다. 다시 로그인해 주세요.');
      try { await onRequireLogin?.({ reason: 'expired' }); } catch { /* 로그인 화면에서 재인증을 처리한다. */ }
    } else setError(problem.message);
  }
  // 일반 관리자는 자기 계정만 가져온다. 목록 조회 가능 여부는 서버가 돌려준 owner 값에 따른다.
  async function load(preserveDraft = false) {
    const current = await api('/api/admin/accounts/me');
    const list = current.owner ? await api('/api/admin/accounts') : [current];
    if (!mounted.current) return;
    setMe(current);
    setAccounts(Array.isArray(list) ? list : list.items || []);
    if (!preserveDraft) {
      setTargetId('me');
      setMode('edit');
      setForm({ ...emptyForm(), username: current.username });
    }
    setStatus('현재 로그인한 계정의 아이디와 비밀번호를 변경할 수 있습니다.');
  }
  useEffect(() => {
    mounted.current = true;
    setBusy(true);
    load().catch(failure).finally(() => { if (mounted.current) setBusy(false); });
    return () => { mounted.current = false; };
  }, []);
  useEffect(() => { if (review) reviewRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' }); }, [review]);

  function choose(id) {
    const account = id === 'me' ? me : accounts.find(item => item.id === id);
    setMode('edit');
    setTargetId(id);
    setReview(null);
    setForm({ ...emptyForm(), username: account?.username || '' });
    setError('');
  }
  function createMode() {
    setMode('create');
    setReview(null);
    setForm(emptyForm());
    setError('');
  }
  // 입력 검사는 즉시 피드백용이며, 저장 전 변경 내용을 review에 고정해 다시 확인받는다.
  function prepare(event) {
    event.preventDefault();
    setError('');
    const username = form.username.trim();
    if (!usernameValid(username)) return setError('아이디는 영문·숫자·점·밑줄·하이픈으로 3~50자 입력해 주세요.');
    if (mode === 'edit' && !form.currentPassword) return setError('현재 로그인한 관리자 비밀번호를 입력해 주세요.');
    if (mode === 'create' && !form.newPassword) return setError('새 관리자 비밀번호를 입력해 주세요.');
    if (form.newPassword && !passwordValid(form.newPassword)) return setError('새 비밀번호는 16~128자로 입력해 주세요.');
    if (form.newPassword !== form.confirmPassword) return setError('새 비밀번호와 확인 입력이 일치하지 않습니다.');
    if (mode === 'edit' && !target) return setError('수정할 계정을 다시 선택해 주세요.');
    setReview({ mode, targetId: target?.id, revision: target?.revision,
      before: mode === 'create' ? '새 계정' : target.username, after: username,
      passwordChanged: !!form.newPassword, self: mode === 'edit' && target.id === me.id });
  }
  // 수정에는 계정 revision을 보내 충돌을 검출한다. 자기 로그인 정보가 바뀌면 재로그인 흐름으로 보낸다.
  async function save() {
    if (!review || busy) return;
    setBusy(true);
    setError('');
    try {
      const result = review.mode === 'create'
        ? await api('/api/admin/accounts', { method: 'POST', body: { username: review.after, password: form.newPassword } })
        : await api(review.self ? '/api/admin/accounts/me' : `/api/admin/accounts/${encodeURIComponent(review.targetId)}`, {
          method: 'PATCH', body: { revision: review.revision, username: review.after, currentPassword: form.currentPassword,
            ...(form.newPassword ? { newPassword: form.newPassword } : {}) },
        });
      clearPasswords();
      setReview(null);
      if (result.requiresLogin) {
        setStatus('계정 정보를 변경했습니다. 새 아이디와 비밀번호로 다시 로그인해 주세요.');
        try { await onRequireLogin?.({ reason: 'credentialsChanged', username: result.username }); } catch { /* 변경은 저장되었으므로 다시 로그인해야 한다. */ }
        return;
      }
      await load();
      onAccountChanged?.(result);
      setStatus(review.mode === 'create' ? `관리자 ${result.username} 계정을 추가했습니다.` : '계정 정보를 저장했습니다.');
    } catch (problem) {
      await failure(problem);
      if (problem.status === 409) {
        try { await load(true); } catch (refreshProblem) { await failure(refreshProblem); }
      }
    } finally { if (mounted.current) setBusy(false); }
  }

  if (!me) return <section className="account-manager"><h1>관리자 계정</h1><p role={error ? 'alert' : 'status'} className={error ? 'account-error' : 'account-status'}>{error || status}</p></section>;
  const blocked = busy || !!review;
  return <section className="account-manager">
    <div className="page-heading"><div><p className="eyebrow">ADMIN ACCOUNTS</p><h1>관리자 계정</h1><p>로그인 아이디와 비밀번호를 관리합니다.</p></div></div>
    <section className="account-panel account-current" aria-labelledby="account-current-title">
      <div><h2 id="account-current-title">현재 로그인한 계정</h2><strong>{me.username}</strong><span className="account-role">{me.owner ? '대표 관리자' : '일반 관리자'}</span><details className="account-record-details"><summary>계정 식별 정보</summary><p className="account-record-id">계정 ID: {me.id}</p></details></div>
      <p className="hint">{me.owner ? '대표 관리자는 관리자 계정을 추가하고 다른 관리자의 정보를 수정할 수 있습니다.' : '본인의 로그인 아이디와 비밀번호를 수정할 수 있습니다.'}</p>
    </section>
    <p role="status" className="account-status">{status}</p>
    {error && <p role="alert" className="account-error">{error}</p>}
    {me.owner && <section className="account-panel" aria-labelledby="account-list-title"><div className="account-section-heading"><h2 id="account-list-title">등록된 관리자</h2><button type="button" disabled={blocked} onClick={createMode}>관리자 추가</button></div><div className="account-list">{accounts.map(account => <button type="button" disabled={blocked} key={account.id} onClick={() => choose(account.id === me.id ? 'me' : account.id)} aria-pressed={mode === 'edit' && target?.id === account.id}><strong>{account.username}</strong><span>{account.owner ? '대표 관리자' : '일반 관리자'}{account.id === me.id ? ' · 내 계정' : ''}</span></button>)}</div></section>}
    <form className="account-panel account-form" onSubmit={prepare}>
      <h2>{mode === 'create' ? '새 관리자 추가' : target?.id === me.id ? '내 계정 수정' : `${target?.username || ''} 계정 수정`}</h2>
      <fieldset disabled={blocked}>
        <label>로그인 아이디<input autoComplete={mode === 'create' ? 'off' : 'username'} autoCapitalize="none" spellCheck={false} required minLength={3} maxLength={50} pattern={'[A-Za-z0-9._\\-]{3,50}'} value={form.username} onChange={event => setForm(value => ({ ...value, username: event.target.value }))}/><span className="hint">영문·숫자·점·밑줄·하이픈 3~50자. 대소문자를 구분해 중복 계정을 만들 수는 없습니다.</span></label>
        {mode === 'edit' && <label>현재 로그인한 관리자 비밀번호<input type="password" autoComplete="current-password" required maxLength={128} value={form.currentPassword} onChange={event => setForm(value => ({ ...value, currentPassword: event.target.value }))}/></label>}
        <div className="account-password-fields"><label><span className="account-field-label">{mode === 'create' ? '비밀번호' : '새 비밀번호 · 변경할 때 입력'}</span><input type="password" autoComplete="new-password" aria-describedby="account-password-help" required={mode === 'create'} minLength={16} maxLength={128} value={form.newPassword} onChange={event => setForm(value => ({ ...value, newPassword: event.target.value }))}/><span id="account-password-help" className="hint">16~128자{mode === 'edit' ? ' · 빈칸이면 현재 비밀번호 유지' : ''}</span></label><label><span className="account-field-label">{mode === 'create' ? '비밀번호 확인' : '새 비밀번호 확인'}</span><input type="password" autoComplete="new-password" aria-describedby="account-password-confirm-help" required={mode === 'create' || !!form.newPassword} minLength={form.newPassword ? 16 : undefined} maxLength={128} value={form.confirmPassword} onChange={event => setForm(value => ({ ...value, confirmPassword: event.target.value }))}/><span id="account-password-confirm-help" className="hint">위에 입력한 비밀번호를 한 번 더 입력해 주세요.</span></label></div>
        <div className="account-actions"><button className="primary" type="submit">{mode === 'create' ? '추가 전 확인' : '변경 전 확인'}</button><button type="button" onClick={() => choose('me')}>입력 초기화</button></div>
      </fieldset>
    </form>
    {review && <section className="account-panel account-review" ref={reviewRef} aria-labelledby="account-review-title"><h2 id="account-review-title">{review.mode === 'create' ? '추가할 계정 확인' : '변경할 계정 확인'}</h2><div className="account-review-values"><div><span>변경 전 아이디</span><strong>{review.before}</strong></div><div><span>{review.mode === 'create' ? '추가할 아이디' : '변경 후 아이디'}</span><strong>{review.after}</strong></div></div><p>비밀번호: {review.mode === 'create' ? '입력한 비밀번호로 등록' : review.passwordChanged ? '새 비밀번호로 변경' : '현재 비밀번호 유지'}</p>{review.self && <p className="hint">아이디나 비밀번호를 변경하면 다시 로그인합니다.</p>}<div className="account-actions"><button type="button" className="primary" disabled={busy} onClick={save}>{busy ? '저장 중…' : review.mode === 'create' ? '관리자 추가' : '계정 정보 저장'}</button><button type="button" disabled={busy} onClick={() => { setReview(null); clearPasswords(); }}>입력으로 돌아가기</button></div></section>}
  </section>;
}
