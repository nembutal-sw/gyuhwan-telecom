// 사진은 서버 보관함에 저장하고 이 화면에서는 썸네일·이름·사용 위치를 선택한다.
import React,{useEffect,useRef,useState} from 'react';
import {api} from './api';
import './operations-media.css';
const date=value=>value?new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',dateStyle:'medium',timeStyle:'short'}).format(new Date(value)):'배포에 포함된 원본 사진';
const bytes=value=>value>1048576?`${(value/1048576).toFixed(1)}MB`:`${Math.ceil(value/1024)}KB`;

// 기존 직접 편집과 같은 파일 한도에 맞춰 원본을 축소한다. EXIF 등 메타데이터는 서버에서도 제거한다.
export async function uploadLibraryImage(file){
 if(!file||!['image/png','image/jpeg','image/webp'].includes(file.type))throw Error('PNG·JPG·WebP 사진을 선택하세요.');
 if(file.size>25000000)throw Error('25MB 이하 사진을 선택하세요.');
 let bitmap;try{bitmap=await createImageBitmap(file);}catch{throw Error('사진 파일을 읽을 수 없습니다.');}
 const canvas=document.createElement('canvas'),scale=Math.min(1,2000/Math.max(bitmap.width,bitmap.height));canvas.width=Math.max(1,Math.round(bitmap.width*scale));canvas.height=Math.max(1,Math.round(bitmap.height*scale));canvas.getContext('2d').drawImage(bitmap,0,0,canvas.width,canvas.height);bitmap.close();
 let blob=await new Promise(resolve=>canvas.toBlob(resolve,file.type==='image/jpeg'?'image/jpeg':'image/webp',.88));
 if(!blob)throw Error('사진을 처리할 수 없습니다.');
 for(let quality=.7;blob.size>1500000&&quality>=.3;quality-=.2)blob=await new Promise(resolve=>canvas.toBlob(resolve,'image/webp',quality));
 if(!blob||blob.size>1500000)throw Error('사진을 축소한 뒤에도 1.5MB를 넘었습니다. 더 작은 사진을 선택하세요.');
 const base64=await new Promise((resolve,reject)=>{const reader=new FileReader();reader.onload=()=>resolve(String(reader.result).split(',')[1]);reader.onerror=()=>reject(Error('사진을 읽을 수 없습니다.'));reader.readAsDataURL(blob);});
 return api('/api/admin/content/images',{method:'POST',body:{fileName:file.name,base64}});
}

export default function MediaLibrary({user,onSelect,onRequireLogin}){
 const[items,setItems]=useState([]),[query,setQuery]=useState(''),[source,setSource]=useState('all'),[loading,setLoading]=useState(true),[error,setError]=useState(''),[notice,setNotice]=useState(''),[busy,setBusy]=useState(false),[renaming,setRenaming]=useState(null),[name,setName]=useState('');const input=useRef();
 const edit=user?.permissions?.includes('MEDIA_EDIT');
 function failed(err){setError(err.message);if(err.status===401)onRequireLogin?.();}
 async function load(){setLoading(true);try{const data=await api(`/api/admin/media?search=${encodeURIComponent(query)}&source=${source}`);setItems(data.items||[]);setError('');}catch(err){failed(err);}finally{setLoading(false);}}
 useEffect(()=>{let alive=true;const timer=setTimeout(async()=>{setLoading(true);try{const data=await api(`/api/admin/media?search=${encodeURIComponent(query)}&source=${source}`);if(alive){setItems(data.items||[]);setError('');}}catch(err){if(alive)failed(err);}finally{if(alive)setLoading(false);}},200);return()=>{alive=false;clearTimeout(timer);};},[query,source]);
 async function upload(file){if(!file)return;setBusy(true);setError('');try{const result=await uploadLibraryImage(file);setNotice('사진을 보관함에 올렸습니다. 선택하면 편집 중인 항목에 반영됩니다.');await load();if(onSelect)onSelect(result.url);}catch(err){failed(err);}finally{setBusy(false);if(input.current)input.current.value='';}}
 async function rename(e){e.preventDefault();setBusy(true);try{await api(`/api/admin/media/${renaming}`,{method:'PATCH',body:{title:name}});setRenaming(null);setNotice('사진 이름을 변경했습니다.');await load();}catch(err){failed(err);}finally{setBusy(false);}}
 async function remove(item){if(!confirm(`“${item.title}” 사진을 보관함과 서버에서 삭제하시겠습니까? 사용 이력이나 초안에 있는 사진은 삭제할 수 없습니다.`))return;setBusy(true);try{await api(`/api/admin/media/${item.id}`,{method:'DELETE'});setNotice('사용하지 않는 사진을 삭제했습니다.');await load();}catch(err){failed(err);}finally{setBusy(false);}}
 return <section className="operations-media"><div className="operations-heading"><div><h2>{onSelect?'사용할 사진 선택':'사진 보관함'}</h2><p>기존 사진을 찾아 재사용하고, 어디에 사용 중인지 확인하세요. 업로드한 사진은 직접 삭제할 때까지 보관됩니다.</p></div>{edit&&<label className="media-upload-button">{busy?'처리 중…':'사진 올리기'}<input ref={input} type="file" accept="image/png,image/jpeg,image/webp" disabled={busy} onChange={e=>upload(e.target.files[0])}/></label>}</div>
 <div className="operations-filters"><label>사진 찾기<input type="search" value={query} placeholder="사진 이름·원본 파일명 검색" onChange={e=>setQuery(e.target.value)}/></label><label>사진 종류<select value={source} onChange={e=>setSource(e.target.value)}><option value="all">모든 사진</option><option value="uploaded">업로드한 사진</option><option value="source">페이지 원본 사진</option></select></label><button type="button" disabled={loading} onClick={load}>새로고침</button></div>
 {error&&<p role="alert" className="notice">{error}</p>}{notice&&<p role="status">{notice}</p>}{loading?<p role="status">사진을 불러오는 중…</p>:<p>{items.length}개 사진{!items.length?' · 검색 조건에 맞는 사진이 없습니다.':''}</p>}
 <div className="media-library-grid">{items.map(item=><article key={item.id} className="media-library-item"><button type="button" className="media-thumbnail" disabled={!onSelect||busy} onClick={()=>onSelect?.(item.url)} aria-label={`${item.title} 사진 선택`}><img src={item.url} alt={item.title} loading="lazy" onError={e=>{e.currentTarget.style.opacity='.2';}}/></button><div className="media-item-content"><h3>{item.title}</h3><p className="media-original-name">{item.originalName}</p><p>{bytes(item.bytes)} · {item.source==='uploaded'?'업로드 사진':'원본 사진'}</p><p className="media-item-date">{date(item.createdAt)}</p><details><summary>{item.references.length?`사용 위치 ${item.references.length}곳`:'사용 위치 없음'}</summary>{item.references.length?<ul>{item.references.map((ref,i)=><li key={i}>{ref.label}</li>)}</ul>:<p>현재 내용·저장 이력·임시 저장·예약에 사용되지 않았습니다.</p>}</details><div className="media-item-actions">{onSelect&&<button type="button" className="primary" disabled={busy} onClick={()=>onSelect(item.url)}>이 사진 사용</button>}<button type="button" onClick={async()=>{try{await navigator.clipboard.writeText(item.url);setNotice('사진 주소를 복사했습니다.');}catch{setNotice(`사진 주소: ${item.url}`);}}}>주소 복사</button>{edit&&item.source==='uploaded'&&<><button type="button" disabled={busy} onClick={()=>{setRenaming(item.id);setName(item.title);}}>이름 변경</button><button type="button" disabled={busy||!item.canDelete} onClick={()=>remove(item)} title={!item.canDelete?'사용 중인 사진은 복원을 위해 보존합니다.':undefined}>삭제</button></>}</div></div></article>)}</div>
 {renaming&&<form className="media-rename" onSubmit={rename}><label>사진 이름<input autoFocus maxLength={200} value={name} onChange={e=>setName(e.target.value)} required/></label><button className="primary" disabled={busy}>이름 저장</button><button type="button" onClick={()=>setRenaming(null)}>취소</button></form>}
 </section>;
}
