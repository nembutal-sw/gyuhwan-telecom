// 현재 공개된 예약 가격표의 적용 기간만 보여 주며 임시 작업이나 예정 가격은 공개하지 않는다.
if(location.pathname!=='/admin_preview'){
 fetch('/api/public/policy-period',{cache:'no-store',credentials:'same-origin'}).then(response=>response.ok?response.json():null).then(period=>{
  if(!period?.startsAt)return;const anchor=document.querySelector('#shop .shop-heading,#shop .section-heading,#shop h2');if(!anchor)return;
  const note=document.createElement('p');note.className='field-hint';
  const date=value=>new Date(value).toLocaleString('ko-KR',{timeZone:'Asia/Seoul'});
  note.textContent=period.endsAt?`${period.title} · ${date(period.startsAt)}부터 ${date(period.endsAt)}까지 적용`: `${period.title} · ${date(period.startsAt)}부터 적용`;
  anchor.insertAdjacentElement('afterend',note);
 }).catch(()=>{});
}
