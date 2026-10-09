// 표 편집의 순수 함수 계약을 검사한다: 필터, 다중 행 입력, 원복, 검증, 엑셀 붙여넣기.
// 기존 자료의 비편집 필드/월별 요금을 보존하는지 함께 확인해 전체 저장 시 손실을 막는다.
import test from 'node:test';
import assert from 'node:assert/strict';
import { PRICE_FIELDS, TEXT_FIELDS, buildPriceRows, filterPriceRows, compilePriceDrafts, parseGridClipboard } from '../frontend/src/core/price-grid.mjs';
import { calculateQuoteWithInterest } from '../frontend/src/core/installment-core.mjs';

const products = [
  { id: 1, name: '갤럭시 S26 256GB', code: 'SM-S942N', brand: '삼성', carrierCodes: { SKT: 'SM-S942S', KT: 'SM-S942K', 'LG U+': 'SM-S942L' } },
  { id: 2, name: '아이폰 18 PRO 512GB', code: 'A9999', brand: 'Apple', carrierCodes: { SKT: 'IPH18P-S', KT: 'IPH18P-K', 'LG U+': 'IPH18P-L' } },
];
const a = '1|SKT|번호이동|common', b = '2|LG U+|기기변경|choice', newKey = '2|KT|신규가입|common';
const base = {
  offers: {
    [a]: { retail: 1000000, common: 300000, support: 100000, supportInquiry: false, installmentFee: 777, planFee: 80000, discountBase: 40000, addonFee: 5000, addonMonths: 4, addonSchedule: Array.from({ length: 24 }, (_, i) => i < 4 ? 5000 : 0), planName: '프리미엄 80', planDetails: '데이터 안내\n통화 안내', addonDetails: '서비스 안내', penaltyDetails: '반환 안내', terms: '개통 확인' },
    [b]: { retail: 900000, common: null, support: 0, installmentFee: 1234, planFee: 90000, discountBase: 80000, addonFee: 0, addonMonths: 0, planName: '애플 요금제 90', planDetails: '선택약정 안내' },
  },
  colors: { 1: ['블랙', '화이트'], 2: ['실버'] },
  colorImages: { 1: { 블랙: 'data:image/png;base64,YQ==' } },
  addonRevision: 'keep-registered-schedule',
};

test('rows include every product, carrier, type and discount, with saved offer context', () => {
  assert.deepEqual(PRICE_FIELDS, ['retail','common','support','planFee','discountBase','addonFee','addonMonths']);
  assert.deepEqual(TEXT_FIELDS, ['planName','planDetails','addonDetails','penaltyDetails','terms']);
  const rows = buildPriceRows(products, base);
  assert.equal(rows.length, 36); assert.equal(new Set(rows.map(row => row.key)).size, 36);
  assert.equal(rows.filter(row => row.registered).length, 2);
  assert.equal(rows.find(row => row.key === a).offer, base.offers[a]);
  assert.equal(rows.find(row => row.key === b).model, '2');
});

test('mixed filters match aliases, all whitespace tokens, carrier codes and draft plan names', () => {
  const rows = buildPriceRows(products, base);
  assert.deepEqual(filterPriceRows(rows, { carrier: 'LGU+', brand: '애플', type: '기기변경', discount: 'choice', query: '애플 IPH18P-L 512 선택약정 요금제' }).map(row => row.key), [b]);
  assert.deepEqual(filterPriceRows(rows, { query: '삼성 SM-S942S 번호이동 공통지원금 프리미엄 80' }).map(row => row.key), [a]);
  assert.deepEqual(filterPriceRows(rows, { query: 'LG U+ 기기변경 선택약정 새 요금제' }, { [b]: { planName: '새 요금제' } }).map(row => row.key), [b]);
  assert.deepEqual(filterPriceRows(rows, { query: '애플 요금제 90' }, { [b]: { planName: '새 요금제' } }).map(row => row.key), [b]);
  assert.deepEqual(filterPriceRows(rows, { query: '애플 요금제 90' }, { [b]: { planName: '' } }).map(row => row.key), [b]);
  assert.equal(filterPriceRows(rows, { carrier: '전체', brand: 'all', query: '없는 모델' }).length, 0);
});

test('formatting and reverted drafts have no changes, preserve historical interest and schedules', () => {
  const drafts = { [a]: { retail: '1.000.000원', common: '300,000', support: '100000', addonFee: '5.000', addonMonths: '4', planDetails: base.offers[a].planDetails }, [newKey]: { retail: '', planName: '', supportInquiry: false } };
  const result = compilePriceDrafts(base, drafts, products);
  assert.deepEqual(result.errors, []); assert.deepEqual(result.changes, []); assert.deepEqual(result.config, base);
  assert.equal(Object.hasOwn(result.config.offers, newKey), false);
  assert.deepEqual(filterPriceRows(buildPriceRows(products, base), { changedOnly: true }, drafts), []);
});

test('multiple rows preserve unrelated fields, rows, colors and input objects; only principal changes recalculate interest', () => {
  const before = structuredClone(base), drafts = { [a]: { retail: '1,100,000' }, [b]: { planName: '새 요금제', terms: '새 안내' }, [newKey]: { planFee: '70,000', planName: '신규 요금제' } }, draftBefore = structuredClone(drafts);
  const result = compilePriceDrafts(base, drafts, products);
  assert.deepEqual(result.errors, []); assert(result.config);
  assert.equal(result.config.offers[a].installmentFee, calculateQuoteWithInterest({ ...base.offers[a], retail: 1100000 }, 'common', 24).installmentFee);
  assert.equal(result.config.offers[b].installmentFee, 1234);
  assert.equal(result.config.offers[a].discountBase, 40000); assert.equal(result.config.offers[b].common, null);
  assert.deepEqual(result.config.offers[a].addonSchedule, base.offers[a].addonSchedule);
  assert.equal(result.config.offers[b].planDetails, base.offers[b].planDetails);
  assert.deepEqual(result.config.colors, base.colors); assert.deepEqual(result.config.colorImages, base.colorImages); assert.equal(result.config.addonRevision, base.addonRevision);
  assert.deepEqual(base, before); assert.deepEqual(drafts, draftBefore);
  assert.deepEqual(new Set(result.changes.map(change => change.key)), new Set([a,b,newKey]));
  assert.deepEqual(filterPriceRows(buildPriceRows(products,base),{changedOnly:true,query:'기기변경' },drafts).map(row=>row.key),[b]);
});

test('negative support becomes inquiry/zero; explicit checkbox and zero remain distinct, with reversible derived changes', () => {
  let result = compilePriceDrafts(base, { [a]: { support: '-50,000', supportInquiry: false } }, products);
  assert.deepEqual(result.errors, []); assert.equal(result.config.offers[a].support, 0); assert.equal(result.config.offers[a].supportInquiry, true);
  assert(result.changes.some(change => change.field === 'supportInquiry')); assert(result.changes.some(change => change.field === 'installmentFee'));
  result = compilePriceDrafts(base, { [a]: { support: '25,000', supportInquiry: true } }, products);
  assert.equal(result.config.offers[a].support, 0); assert.equal(result.config.offers[a].supportInquiry, true);
  const inquiry = structuredClone(base); inquiry.offers[a].support = 0; inquiry.offers[a].supportInquiry = true;
  result = compilePriceDrafts(inquiry, { [a]: { supportInquiry: false, support: '0' } }, products);
  assert.equal(result.config.offers[a].support, 0); assert.equal(result.config.offers[a].supportInquiry, false); assert.equal(result.config.offers[a].installmentFee, 777);
  result = compilePriceDrafts(inquiry, { [a]: { support: '50,000' } }, products);
  assert.equal(result.config.offers[a].support, 50000); assert.equal(result.config.offers[a].supportInquiry, false);
  result = compilePriceDrafts(inquiry, { [a]: { support: '0' } }, products);
  assert.deepEqual(result.changes, []); assert.equal(result.config.offers[a].supportInquiry, true);
  result = compilePriceDrafts(base, { [b]: { support: '' } }, products);
  assert.equal(result.config.offers[b].support, null);
  const historical=structuredClone(base);historical.offers[a].support=-50000;
  result=compilePriceDrafts(historical,{[a]:{planName:'문구만 수정'}},products);
  assert.equal(result.config.offers[a].support,-50000);assert.equal(result.config.offers[a].supportInquiry,false);assert.equal(result.config.offers[a].installmentFee,777);
});

test('addon schedule survives text/unchanged amounts and is removed only when fee or months actually changes', () => {
  let result = compilePriceDrafts(base, { [a]: { addonDetails: '새 상세 안내', addonFee: '5,000' } }, products);
  assert.deepEqual(result.config.offers[a].addonSchedule, base.offers[a].addonSchedule); assert.equal(result.config.offers[a].installmentFee, 777);
  for (const draft of [{ addonFee: '6,000' }, { addonMonths: '5' }]) {
    result = compilePriceDrafts(base, { [a]: draft }, products);
    assert.deepEqual(result.errors, []); assert.equal(Object.hasOwn(result.config.offers[a], 'addonSchedule'), false);
    assert(result.changes.some(change => change.field === 'addonSchedule' && change.after === undefined));
    assert.equal(result.config.offers[a].addonDetails, '서비스 안내'); assert.equal(result.config.offers[a].installmentFee, 777);
  }
});

test('invalid drafts block the compiled config, report field errors, and retain valid rows and original drafts', () => {
  for (const [field, raw] of [['retail','1.5'],['retail','1,00'],['retail','원'],['retail','100000001'],['retail','-1'],['addonMonths','25'],['addonMonths','0'],['planFee',true],['planName','x'.repeat(6001)],['addonSchedule',Array(24).fill(1)],['installmentFee','99'],['unknown','x'],['supportInquiry','false']]) {
    const drafts = { [a]: { [field]: raw }, [b]: { planName: '정상 수정 유지' } }, prior=structuredClone(drafts);
    const result=compilePriceDrafts(base,drafts,products);
    assert.equal(result.config,null,`${field}=${String(raw)}`); assert(result.errors.some(error=>error.key===a&&error.field===field),`${field}: ${JSON.stringify(result.errors)}`);
    assert(result.changes.some(change=>change.key===b&&change.field==='planName'&&change.after==='정상 수정 유지')); assert.deepEqual(drafts,prior);
    assert(filterPriceRows(buildPriceRows(products,base),{changedOnly:true},drafts).some(row=>row.key===a));
  }
  const unknown=compilePriceDrafts(base,{'999|SKT|번호이동|common':{retail:'1'}},products);
  assert.equal(unknown.config,null);assert.equal(unknown.errors[0].field,'row');
  assert.equal(compilePriceDrafts(base,{[a]:null},products).config,null);
});

test('existing validator rules permit subsidy above retail but reject choice/base and unchanged invalid data', () => {
  let result=compilePriceDrafts(base,{[a]:{support:'1,500,000'}},products);
  assert.deepEqual(result.errors,[]);assert.equal(result.config.offers[a].support,1500000);assert.equal(result.config.offers[a].installmentFee,null);
  result=compilePriceDrafts(base,{[b]:{discountBase:'90,004'}},products);
  assert.equal(result.config,null);assert(result.errors.some(error=>error.field==='discountBase'));
  const invalid=structuredClone(base);invalid.offers[b].common=50000;
  result=compilePriceDrafts(invalid,{[a]:{planName:'유효한 수정'}},products);
  assert.equal(result.config,null);assert(result.errors.some(error=>error.key===b&&error.field==='common'));
  const badColors=structuredClone(base);badColors.colors[1]=['블랙','블랙'];
  result=compilePriceDrafts(badColors,{},products);
  assert.equal(result.config,null);assert(result.errors.some(error=>error.key===''&&error.field==='row'));
});

test('clipboard TSV preserves empty columns and real blank rows, excluding only the final row separator', () => {
  assert.deepEqual(parseGridClipboard('A\t\tB\t\r\n\tC\t\t\r\n'), [['A','','B',''],['','C','','']]);
  assert.deepEqual(parseGridClipboard('A\n\n'), [['A'],['']]);
  assert.deepEqual(parseGridClipboard('\t'), [['','']]);
  assert.deepEqual(parseGridClipboard('\n'), [['']]);
  assert.deepEqual(parseGridClipboard(''), []);
  assert.deepEqual(parseGridClipboard(' A \t B '), [[' A ',' B ']]);
});

test('clipboard quoted cells preserve multiline/tab content, escaped quotes and empty cells', () => {
  assert.deepEqual(parseGridClipboard('"안내 첫줄\n둘째줄"\t"문구 ""예"""\r\n"a\tb"\t""'), [['안내 첫줄\n둘째줄','문구 "예"'],['a\tb','']]);
  assert.deepEqual(parseGridClipboard('"첫줄\r\n둘째줄"\r\n'), [['첫줄\r\n둘째줄']]);
  assert.deepEqual(parseGridClipboard('"끝에 따옴표"""'), [['끝에 따옴표"']]);
});

test('malformed quoted clipboard cells throw instead of returning a partial paste', () => {
  for (const value of ['정상\t"닫히지 않음', '"닫힘"뒤의문자\t다음셀', '중간"따옴표', '"첫줄\n둘째줄']) assert.throws(()=>parseGridClipboard(value), /따옴표/);
  assert.throws(()=>parseGridClipboard(null), /붙여넣을 내용/);
});

test('parsed multiline text reaches a draft intact while a formula amount fails validation without changing input', () => {
  const pasted=parseGridClipboard('"안내 첫줄\n둘째줄"\t=1+2\r\n');
  const drafts={ [a]:{planDetails:pasted[0][0],retail:pasted[0][1]}, [b]:{planName:'정상 수정'} }, prior=structuredClone(drafts);
  const result=compilePriceDrafts(base,drafts,products);
  assert.equal(result.config,null);assert(result.errors.some(error=>error.key===a&&error.field==='retail'));
  assert(result.changes.some(change=>change.key===a&&change.field==='planDetails'&&change.after==='안내 첫줄\n둘째줄'));
  assert.deepEqual(drafts,prior);
});
