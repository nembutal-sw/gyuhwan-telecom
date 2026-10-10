// 직접 편집기·색상 사진·모델 사진이 같은 보관함 선택창을 재사용한다.
import React,{useEffect,useRef} from 'react';
import MediaLibrary from './MediaLibrary.jsx';
export default function MediaPicker({onSelect,onClose,user,onRequireLogin}){
 const dialog=useRef();useEffect(()=>{const element=dialog.current;element.showModal();return()=>{if(element.open)element.close();};},[]);
 return <dialog ref={dialog} className="operations-photo-dialog" aria-labelledby="photo-picker-title" onCancel={e=>{e.preventDefault();onClose();}}><div className="media-picker-head"><h2 id="photo-picker-title">사진 보관함에서 선택</h2><button type="button" onClick={onClose} aria-label="사진 선택창 닫기">닫기 ×</button></div><MediaLibrary user={user} onSelect={url=>{onSelect(url);onClose();}} onRequireLogin={onRequireLogin}/></dialog>;
}
