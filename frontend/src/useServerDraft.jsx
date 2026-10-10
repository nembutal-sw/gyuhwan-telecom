import {useEffect,useRef,useState} from 'react';
import {api} from './api';
import {savedAtLabel} from './SavedDataInfo.jsx';
const activeSavers=new Set();
// 로그아웃 전에 두 편집기의 마지막 입력도 서버에 보낸다. 토큰/비밀번호는 여기에 보관하지 않는다.
export async function flushServerDrafts(){await Promise.all([...activeSavers].map(save=>save()));}

/** 계정별 서버 초안을 순서대로 저장한다. 두 창의 초안을 조용히 덮어쓰지 않는다. */
export function useServerDraft({kind,ready,baseRevision,payload,dirty,canEdit,onRestore,onDiscard}) {
 const [state,setState]=useState({status:'연결 확인 중',pending:null,error:'',updatedAt:null,initialized:false});
 const version=useRef(0),started=useRef(false),blocked=useRef(true),paused=useRef(false),queue=useRef(Promise.resolve());
 const latest=useRef(null),callbacks=useRef(null),mounted=useRef(true);
 latest.current={baseRevision,payload,dirty,canEdit};callbacks.current={onRestore,onDiscard};
 useEffect(()=>()=>{mounted.current=false;},[]);
 function update(patch){if(mounted.current)setState(value=>({...value,...patch}));}
 async function check(){
  blocked.current=true;
  update({initialized:false});
  try{const saved=await api(`/api/admin/workspaces/${kind}`);version.current=saved.revision||0;blocked.current=!!saved.exists;update({pending:saved.exists?saved:null,error:'',status:saved.exists?'이전에 작성한 임시 작업이 있습니다.':'임시 작업 없음',updatedAt:saved.updatedAt||null,initialized:true});}
  catch(e){update({error:e.message,status:'임시 저장 연결 실패'});}
 }
 useEffect(()=>{if(ready&&canEdit&&!started.current){started.current=true;check();}},[ready,canEdit]);
 // 성공한 공개/취소 작업이 진행 중이던 자동 저장 뒤에 초안을 지우도록 요청을 직렬화한다.
 function enqueue(task){const next=queue.current.catch(()=>{}).then(task);queue.current=next;return next;}
 function saveNow({strict=false}={}){
  return enqueue(async()=>{
   const snapshot=latest.current;
   if(!snapshot?.dirty)return;
   if(blocked.current||paused.current||!snapshot.canEdit){if(strict){const error=Error('임시 작업을 저장할 수 없습니다. 서버 임시 작업과 현재 입력을 확인하세요.');update({error:error.message});throw error;}return;}
   update({status:'임시 저장 중…',error:''});
   try{const saved=await api(`/api/admin/workspaces/${kind}`,{method:'PUT',body:{revision:version.current,baseRevision:snapshot.baseRevision,payload:snapshot.payload}});version.current=saved.revision;update({status:'서버에 임시 저장됨',updatedAt:saved.updatedAt,error:''});}
   catch(e){if(e.status===409)blocked.current=true;update({status:'임시 저장하지 못했습니다',error:e.message});throw e;}
  });
 }
 useEffect(()=>{const flush=()=>saveNow({strict:true});activeSavers.add(flush);return()=>activeSavers.delete(flush);},[kind]);
 useEffect(()=>{
  if(!dirty){paused.current=false;return;}
  if(!ready||!canEdit||blocked.current||paused.current)return;
  const timer=setTimeout(()=>saveNow().catch(()=>{}),1200);
  return()=>clearTimeout(timer);
 },[payload,dirty,ready,canEdit,baseRevision,state.pending,state.initialized]);
 async function clear(){
  paused.current=true;
  return enqueue(async()=>{
   try{await api(`/api/admin/workspaces/${kind}?revision=${version.current}`,{method:'DELETE'});version.current=0;blocked.current=false;update({pending:null,error:'',status:'임시 작업 없음',updatedAt:null,initialized:true});}
   catch(e){blocked.current=true;update({error:e.message,status:'서버 임시 작업 삭제 실패'});throw e;}
  });
 }
 function restore(){const saved=state.pending;if(!saved)return;blocked.current=false;update({pending:null,error:'',status:'임시 작업을 이어서 작성합니다'});callbacks.current.onRestore(saved.payload,saved.baseRevision);}
 async function discard(){await clear();callbacks.current.onDiscard?.();}
 return {...state,saveNow,clear,check,restore,discard,canEdit};
}

export default function DraftBar({workspace,dirty}) {
 if(!workspace.canEdit)return <p className="operations-readonly">조회 전용입니다. 수정 권한은 대표 관리자에게 요청하세요.</p>;
 return <section className="draft-bar" aria-label="임시 작업 저장 상태">
  <div><strong>{workspace.status}</strong>{workspace.updatedAt&&<span>마지막 임시 저장 {savedAtLabel(workspace.updatedAt)}</span>}<small>임시 작업은 내 계정에만 저장되며 고객페이지에 공개되지 않습니다.</small></div>
  {workspace.pending?<div className="operations-actions"><button className="primary" onClick={()=>{if(!dirty||confirm('현재 입력을 서버에 저장된 임시 작업으로 바꿀까요?'))workspace.restore();}}>저장된 작업 이어하기</button><button onClick={()=>{if(confirm('내 서버 임시 작업을 삭제하고 현재 공개본부터 시작할까요?'))workspace.discard().catch(()=>{});}}>임시 작업 삭제</button></div>:<button disabled={!dirty||!workspace.initialized} onClick={()=>workspace.saveNow({strict:true}).catch(()=>{})}>지금 임시 저장</button>}
  {workspace.error&&<p role="alert">{workspace.error} <button onClick={workspace.check}>서버 임시 작업 다시 확인</button></p>}
 </section>;
}
