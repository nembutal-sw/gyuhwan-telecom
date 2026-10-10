// 익명 관심 이벤트만 보낸다. 사용자/기기 식별자를 만들지 않고 임시 중복 방지값은 탭 메모리에만 둔다.
import {isAdminPreview} from './content-data.js';
let selectedModel=null,csrfPromise;const recent=new Map();
export function setInterestModel(modelId){selectedModel=modelId!==null&&modelId!==undefined&&Number.isInteger(Number(modelId))&&Number(modelId)>=0?Number(modelId):null;}
function csrf(){return csrfPromise??=(fetch('/api/auth/csrf',{credentials:'same-origin',cache:'no-store'}).then(response=>response.ok?response.json():Promise.reject()).catch(()=>{csrfPromise=null;throw Error();}));}
async function track(event,modelId=null){
 if(isAdminPreview||location.pathname.startsWith('/admin')||document.visibilityState==='hidden')return;
 const key=`${event}:${modelId??'none'}`,now=Date.now();if(now-(recent.get(key)||0)<30000)return;recent.set(key,now);
 try{const token=await csrf();const response=await fetch('/api/public/interest',{method:'POST',credentials:'same-origin',keepalive:true,headers:{'Content-Type':'application/json',[token.headerName]:token.token},body:JSON.stringify({event,modelId})});if(response.status===403)csrfPromise=null;}catch{/* 관심 집계 실패는 상담/모델 이용을 방해하지 않는다. */}
}
export function trackModelView(modelId){setInterestModel(modelId);if(selectedModel!==null)track('model_view',selectedModel);}
if(!isAdminPreview&&!location.pathname.startsWith('/admin'))document.addEventListener('click',event=>{
 const anchor=event.target.closest?.('a[href]');if(!anchor||event.defaultPrevented)return;
 const href=anchor.getAttribute('href')||'';const inQuote=!!anchor.closest('#purchase-dialog,#consultation-dialog');
 if(/^tel:/i.test(href))track('phone_click',inQuote?selectedModel:null);
 else try{const url=new URL(href,location.origin);if(url.protocol==='https:'&&['open.kakao.com','pf.kakao.com'].includes(url.hostname))track('kakao_click',inQuote?selectedModel:null);}catch{}
});
