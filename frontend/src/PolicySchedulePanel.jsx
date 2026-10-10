import {useEffect,useState} from 'react';
import {api} from './api';
import {savedAtLabel} from './SavedDataInfo.jsx';
const labels={pending:'적용 예정',active:'적용 중',completed:'기간 종료',cancelled:'취소됨',conflict:'운영자 확인 필요',expired:'기간 지나 적용 안 함'};
// 날짜 입력은 한국 시간으로 보여 주고 서버에는 UTC ISO 시각을 보낸다.
function localInput(date){return new Date(date.getTime()+9*3600000).toISOString().slice(0,16);}
function instant(value){return value?new Date(`${value}:00+09:00`).toISOString():null;}

export default function PolicySchedulePanel({revision,makeConfig,dirty,canEdit,disabled,onChanged}) {
 const [items,setItems]=useState([]),[title,setTitle]=useState(''),[start,setStart]=useState(()=>localInput(new Date(Date.now()+3600000))),[end,setEnd]=useState(''),[busy,setBusy]=useState(false),[message,setMessage]=useState(''),[review,setReview]=useState(null);
 async function load(){setItems(await api('/api/admin/schedules'));}
 useEffect(()=>{load().catch(e=>setMessage(e.message));const timer=setInterval(()=>load().catch(()=>{}),30000);return()=>clearInterval(timer);},[]);
 async function prepare(event){
  event.preventDefault();setBusy(true);setMessage('');
  try{const startsAt=instant(start),endsAt=instant(end);if(!startsAt||new Date(startsAt)<=new Date())throw Error('시작 시간을 현재 이후로 선택하세요.');if(endsAt&&endsAt<=startsAt)throw Error('종료 시간은 시작 이후로 선택하세요.');setReview({title:title.trim(),revision,config:await makeConfig(),startsAt,endsAt});}catch(e){setMessage(e.message);}finally{setBusy(false);}
 }
 async function create(){setBusy(true);try{await api('/api/admin/schedules',{method:'POST',body:review});setReview(null);setTitle('');await load();setMessage('예약했습니다. 시작 시 최신 공개본이 바뀌었으면 자동 적용하지 않고 알려드립니다.');}catch(e){setMessage(e.message);}finally{setBusy(false);}}
 async function cancel(item){if(!confirm(item.status==='active'?'이 예약을 지금 종료하고 예약 당시 가격표로 복귀할까요? 수동 변경이 있으면 복귀를 중지합니다.':'이 가격표 예약을 취소할까요?'))return;setBusy(true);try{await api(`/api/admin/schedules/${item.id}/cancel`,{method:'POST'});await load();onChanged?.();}catch(e){setMessage(e.message);}finally{setBusy(false);}}
 return <details className="panel schedule-panel"><summary>가격표 적용 기간·예약 관리</summary><p>한국 시간 기준입니다. 종료일을 지정하면 <strong>예약 당시 고객페이지에 공개된 가격표</strong>로 복귀합니다. 예약 후 수동 수정이 있으면 자동 덮어쓰기를 중지합니다.</p>
  {canEdit&&<form className="schedule-form" onSubmit={prepare}><label>예약 이름<input value={title} maxLength={120} required disabled={busy||disabled} onChange={e=>setTitle(e.target.value)} placeholder="예: 주말 삼성 특별 가격"/></label><label>시작 · 한국 시간<input type="datetime-local" required value={start} disabled={busy||disabled} onChange={e=>setStart(e.target.value)}/></label><label>종료 · 비우면 계속 적용<input type="datetime-local" value={end} disabled={busy||disabled} onChange={e=>setEnd(e.target.value)}/></label><button disabled={busy||disabled} className="primary">{dirty?'편집 중인 가격표 예약 검토':'현재 공개 가격표 예약 검토'}</button></form>}
  {review&&<section className="schedule-review" aria-label="예약 확인"><h3>예약 내용을 확인하세요</h3><p><strong>{review.title}</strong> · {savedAtLabel(review.startsAt)} 시작 · {review.endsAt?savedAtLabel(review.endsAt)+' 종료':'종료 없이 계속 적용'}</p><p>전체 모델의 가격·조건·색상·사진을 이 시점의 편집본으로 예약합니다. 지금 고객페이지는 바뀌지 않습니다.</p><div className="operations-actions"><button disabled={busy} onClick={()=>setReview(null)}>계속 수정</button><button disabled={busy} className="primary" onClick={create}>이 가격표 예약 확정</button></div></section>}
  {message&&<p role="status">{message}</p>}
  <div className="operations-table-wrap"><table><thead><tr><th>예약</th><th>적용 기간 · 한국 시간</th><th>상태</th><th>안내</th><th>작업</th></tr></thead><tbody>{items.map(item=><tr key={item.id}><td><strong>{item.title}</strong><small>예약자 {item.createdBy}</small></td><td>{savedAtLabel(item.startsAt)}<br/>{item.endsAt?savedAtLabel(item.endsAt):'종료 없음'}</td><td><span className={`schedule-status ${item.status}`}>{labels[item.status]||item.status}</span></td><td>{item.note}</td><td>{canEdit&&['pending','active'].includes(item.status)&&<button disabled={busy} onClick={()=>cancel(item)}>{item.status==='active'?'지금 종료':'예약 취소'}</button>}</td></tr>)}</tbody></table>{!items.length&&<p>등록된 가격표 예약이 없습니다.</p>}</div>
 </details>;
}
