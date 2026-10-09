// 관리자 단일 페이지 진입점. 인증 상태에 따라 로그인과 네 가지 관리 메뉴를 전환한다.
import React,{useEffect,useState} from 'react';
import {createRoot} from 'react-dom/client';
import MainContentEditor from './MainContentEditor.jsx';
import PriceGridEditor from './PriceGridEditor.jsx';
import AccountManager from './AccountManager.jsx';
import AuditLogViewer from './AuditLogViewer.jsx';
import {api,login,logout,refreshCsrf} from './api';
import './style.css';
import './account-audit.css';
// URL은 현재 인증 상태를 보여 주는 주소이며, 권한 판단은 서버 API가 수행한다.
function syncAdminLocation(authenticated){const path=authenticated?'/admin':'/admin_login';if(window.location.pathname!==path)window.history.replaceState(null,'',path);}
// 가격·콘텐츠 탭은 숨겨도 마운트해 미저장 입력을 보존하고, 계정·기록 탭은 열 때 마운트한다.
function App(){const[user,setUser]=useState(null),[checking,setChecking]=useState(true),[tab,setTab]=useState('prices'),[message,setMessage]=useState('');
useEffect(()=>{refreshCsrf().then(()=>api('/api/auth/me')).then(setUser).catch(e=>{if(e.status!==401)setMessage(e.message)}).finally(()=>setChecking(false))},[]);
useEffect(()=>{if(checking)return;const syncRoute=()=>syncAdminLocation(!!user);syncRoute();window.addEventListener('popstate',syncRoute);return()=>window.removeEventListener('popstate',syncRoute)},[user,checking]);
function requireLogin({reason='expired'}={}){setUser(null);setTab('prices');setMessage(reason==='credentialsChanged'?'계정 정보가 변경되었습니다. 새 아이디와 비밀번호로 로그인하세요.':'로그인이 만료되었습니다. 다시 로그인하세요.');refreshCsrf().catch(()=>{});}
if(checking)return <main className="login-card"><p role="status">관리자 연결 확인 중…</p></main>;
if(!user)return <Login onLogin={setUser} initialMessage={message}/>;
return <><header><a className="brand" href="#"><img src="/gyuhwan-logo.png" alt="" className="brand-logo" onError={e=>{e.currentTarget.hidden=true}}/>규환 통신 <span>관리자</span></a><div className="account">{user.username}<button onClick={async()=>{try{if(!confirm('저장하지 않은 입력이 있다면 먼저 저장해 주세요. 로그아웃하시겠습니까?'))return;await logout();setUser(null)}catch(e){setMessage(e.message)}}}>로그아웃</button></div></header><div className="app-layout"><aside className="sidebar"><p className="eyebrow">STORE MANAGEMENT</p><button aria-current={tab==='prices'?'page':undefined} onClick={()=>setTab('prices')}>가격·구매 조건</button><button aria-current={tab==='content'?'page':undefined} onClick={()=>setTab('content')}>메인 페이지 관리</button><button aria-current={tab==='accounts'?'page':undefined} onClick={()=>setTab('accounts')}>관리자 계정</button><button aria-current={tab==='audit'?'page':undefined} onClick={()=>setTab('audit')}>수정 기록</button><a href="/" target="_blank" rel="noopener noreferrer">메인 페이지 보기</a><p>저장한 가격과 섹션 내용이 이 서버의 메인 페이지에 반영됩니다.</p></aside><main>{message&&<p role="alert" className="notice">{message}</p>}<div hidden={tab!=='prices'}><PriceGridEditor onRequireLogin={requireLogin}/></div><div hidden={tab!=='content'}><MainContentEditor/></div>{tab==='accounts'&&<AccountManager onRequireLogin={requireLogin} onAccountChanged={()=>api('/api/auth/me').then(setUser).catch(()=>requireLogin())}/ >}{tab==='audit'&&<AuditLogViewer onRequireLogin={requireLogin}/ >}</main></div></>;
}
// 로그인 폼은 성공 후 비밀번호 입력을 비우며 CSRF 갱신은 api.login이 처리한다.
function Login({onLogin,initialMessage}){const[name,setName]=useState('admin'),[password,setPassword]=useState(''),[error,setError]=useState(initialMessage),[busy,setBusy]=useState(false);return <main className="login-card"><p className="eyebrow">GYUHWAN TELECOM</p><h1>관리자 로그인</h1><p>고객 가입과 별도로 운영되는 관리자 전용 화면입니다.</p><form onSubmit={async e=>{e.preventDefault();setBusy(true);setError('');try{onLogin(await login(name,password));setPassword('')}catch(x){setError(x.message)}finally{setBusy(false)}}}><label>관리자 아이디<input autoComplete="username" required value={name} onChange={e=>setName(e.target.value)}/></label><label>비밀번호<input type="password" autoComplete="current-password" required value={password} onChange={e=>setPassword(e.target.value)}/></label><button className="primary" disabled={busy}>{busy?'로그인 중…':'로그인'}</button><p role="alert">{error}</p></form></main>}

createRoot(document.getElementById('root')).render(<App/>);
