// 가격표와 메인 콘텐츠에 공통으로 쓰는 저장 시각 표시. 내부 revision 대신 운영자가 이해하기 쉬운 시각을 보여 준다.
import React from 'react';
import './saved-data-info.css';

// 서버 타임스탬프는 한국 시간으로 표시하고 누락·형식 오류는 별도 문구로 구분한다.
export function savedAtLabel(value) {
  if(!value)return '저장 시각 확인 중';
  const date=new Date(value);
  if(Number.isNaN(date.getTime()))return '저장 시각 확인 필요';
  return new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'}).format(date);
}
// 이전 API 응답의 updated_at 이름도 읽어 과거 저장본의 시각이 비지 않게 한다.
export const historySavedAt = item => item?.updatedAt || item?.updated_at;

// updatedAt은 고객페이지에 저장된 시각, loadedAt은 이 관리자 화면이 다시 읽은 시각이다.
export default function SavedDataInfo({updatedAt,loadedAt,dirty=false,label='마지막 저장'}) {
  return <div className="saved-data-info"><strong>{dirty?'저장하지 않은 변경이 있습니다':'고객페이지에 적용된 저장본'}</strong><span>{label} <time dateTime={updatedAt}>{savedAtLabel(updatedAt)}</time></span>{loadedAt&&<small>이 화면에서 확인한 시각 {savedAtLabel(loadedAt)}</small>}</div>;
}
