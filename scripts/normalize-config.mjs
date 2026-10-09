/** 기존 /api/config 내보내기를 현재 가격 스키마로 검증하는 일회성 이전 도구. 서버에 저장하지 않는다. */
import fs from 'node:fs';import {validateConfig}from '../frontend/src/core/quote-core.mjs';
const [input,output]=process.argv.slice(2);if(!input||!output)throw Error('Usage: node scripts/normalize-config.mjs effective-config.json import.json');
const value=JSON.parse(fs.readFileSync(input)),raw=value.config||value;
const config={offers:raw.offers,colors:raw.colors,...(raw.colorImages?{colorImages:raw.colorImages}:{})};
const products=JSON.parse(fs.readFileSync(new URL('../backend/src/main/resources/products.json',import.meta.url)));
// wx로 기존 내보내기 파일의 덮어쓰기를 막고 생성 파일의 접근 권한을 제한한다.
validateConfig(config,products.map(p=>p.id));fs.writeFileSync(output,JSON.stringify({config},null,2),{flag:'wx',mode:0o600});console.log('Validated config import created.');
