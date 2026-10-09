// 고객이 직접 저장할 상담 견적서 JPG를 브라우저 캔버스에서 만든다. 서버에는 이미지를 업로드하지 않는다.
export const IMAGE_WIDTH=1588,IMAGE_HEIGHT=2246;
export const FONT_SIZE=15;
export function wrapLines(text,measure,maxWidth){const result=[];for(const raw of String(text).split('\n')){if(!raw){result.push('');continue;}let line='';for(const char of raw){if(line&&measure(line+char)>maxWidth){result.push(line);line='';}line+=char;}result.push(line);}return result;}
export async function createQuoteImages(text){
 // 글꼴 로드를 기다려 줄바꿈과 실제 JPG의 글자 폭을 일치시킨다.
 await document.fonts.ready;
 const canvas=document.createElement('canvas');canvas.width=IMAGE_WIDTH;canvas.height=IMAGE_HEIGHT;
 const ctx=canvas.getContext('2d');if(!ctx)throw Error('이미지 생성이 지원되지 않는 브라우저입니다.');
 ctx.scale(2,2);
 const font=`600 ${FONT_SIZE}px system-ui, "Noto Sans KR", sans-serif`;
 ctx.font=font;const lines=wrapLines(text,t=>ctx.measureText(t).width,698);
 // 글자 크기와 모든 금액을 유지하기 위해 한 페이지를 넘으면 자르거나 축소하지 않고 오류를 반환한다.
 if(lines.length>43)throw Error('한 페이지 분량을 초과했습니다. 요금제명을 짧게 입력한 후 다시 만들어 주세요.');
 ctx.fillStyle='#ffffff';ctx.fillRect(0,0,794,1123);
 ctx.fillStyle='#111111';ctx.font='bold 26px system-ui, "Noto Sans KR", sans-serif';ctx.fillText('규환 통신 · 상담 견적서',48,64);
 ctx.fillStyle='#111111';ctx.fillRect(48,84,698,2);ctx.font=font;
 lines.forEach((line,n)=>ctx.fillText(line,48,119+n*21));
 ctx.fillRect(48,1060,698,1);ctx.font=font;ctx.fillText('규환 통신 010-8531-8058  ·  개인정보 가림  ·  1 / 1',48,1092);
 const blob=await new Promise((resolve,reject)=>canvas.toBlob(b=>b?resolve(b):reject(Error('이미지를 만들지 못했습니다. 다시 시도해 주세요.')),'image/jpeg',.94));
 return [new File([blob],'규환 통신_상담견적서_1페이지.jpg',{type:'image/jpeg'})];
}
