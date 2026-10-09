// sync-main-page.mjs가 internet.js 끝에 붙이는 코드 조각. 원본 모듈의 plans·선택 상태를 사용한다.
// 관리자 입력으로 자료 객체가 갱신됐을 때 선택값을 보존하며 다시 그리는 연결점이다.
export function refreshAdminInternetPreview(){const value=Number($('#net-tv').value);$('#net-tv').innerHTML=options(plans[$('#net-carrier').value],value);render();}
export function adminInternetPreviewContext(){return {carrier:$('#net-carrier').value,speed:Number($('#net-speed').value),selections:selected.slice(0,selected[0]===0?1:Number($('#net-tv-count').value))};}
