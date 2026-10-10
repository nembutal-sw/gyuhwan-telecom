// 가격 표의 순수 변환 계층. React 상태 없이 행 생성·초안 파싱·차이·오류·검색·붙여넣기를 맡는다.
import { keyFor, parseAmount, validateConfig } from './quote-core.mjs';
import { calculateQuoteWithInterest } from './installment-core.mjs';

export const PRICE_FIELDS = ['retail', 'common', 'support', 'planFee', 'discountBase', 'addonFee', 'addonMonths'];
export const TEXT_FIELDS = ['planName', 'planDetails', 'addonDetails', 'penaltyDetails', 'terms'];
const carriers = ['SKT', 'KT', 'LG U+'];
const types = ['번호이동', '기기변경', '신규가입'];
const discounts = ['common', 'choice'];
const editableFields = new Set([...PRICE_FIELDS, ...TEXT_FIELDS, 'supportInquiry', 'addonSchedule']);
const principalFields = new Set(['retail', 'common', 'support']);
const hasOwn = (value, key) => value != null && Object.hasOwn(value, key);
const isObject = value => value !== null && typeof value === 'object' && !Array.isArray(value);

// 등록 전 조건도 편집 가능하도록 모델마다 모든 통신사·가입 유형·할인 조합의 행을 만든다.
export function buildPriceRows(products, config) {
  const offers = config?.offers || {};
  return (products || []).flatMap(product => carriers.flatMap(carrier => types.flatMap(type => discounts.map(discount => {
    const key = keyFor(product.id, carrier, type, discount);
    return { key, model: String(product.id), product, brand: product.brand, carrier, type, discount, registered: hasOwn(offers, key), offer: offers[key] || {} };
  }))));
}

// 쉼표·원 단위 입력을 정수로 바꾸되 빈칸은 미등록(null)으로 유지한다.
function parsePrice(raw, field) {
  if (raw !== null && raw !== undefined && typeof raw !== 'string' && typeof raw !== 'number') throw Error('원 단위 정수로 입력해 주세요.');
  if (typeof raw === 'number') return parseAmount(raw, field === 'support');
  const original = String(raw ?? '').trim();
  let value = original.replace(/\s*원$/, '').trim();
  if (original && !value) throw Error('금액은 원 단위 정수로 입력해 주세요.');
  if (/^-?\d{1,3}(?:\.\d{3})+$/.test(value)) value = value.replaceAll('.', ',');
  const number = parseAmount(value, field === 'support');
  return number === 0 ? 0 : number;
}

function equalField(field, before, after) {
  if (before === undefined && (PRICE_FIELDS.includes(field) || field === 'installmentFee') && after === null) return true;
  if (before === undefined && TEXT_FIELDS.includes(field) && after === '') return true;
  if (before === undefined && field === 'supportInquiry' && after === false) return true;
  return JSON.stringify(before) === JSON.stringify(after);
}

function inferField(message, offer, draft = {}) {
  if (message.includes('문의 지원금')) return 'supportInquiry';
  if (message.includes('월별 금액')) return 'addonSchedule';
  if (message.includes('선택약정에 공통')) return 'common';
  if (message.includes('청구 개월') || message.includes('계산 기간')) return 'addonMonths';
  if (message.includes('선택약정 할인')) return 'discountBase';
  const named = /(?:^|\s)(retail|common|support|installmentFee|planFee|discountBase|addonFee|addonMonths):/.exec(message);
  if (named) return named[1];
  if (message.includes('금액은 원 단위')) {
    const invalid = [...PRICE_FIELDS, 'installmentFee'].find(field => hasOwn(offer, field) && offer[field] !== null && (!Number.isSafeInteger(offer[field]) || (field !== 'support' && offer[field] < 0) || Math.abs(offer[field]) > 100000000));
    if (invalid) return invalid;
  }
  if (message.includes('안내문 길이')) {
    const invalid = TEXT_FIELDS.find(field => hasOwn(offer, field) && (typeof offer[field] !== 'string' || offer[field].length > 6000));
    if (invalid) return invalid;
  }
  if (message.includes('구매 원금')) return [...principalFields].find(field => hasOwn(draft, field)) || 'row';
  return 'row';
}

// 한 행의 초안만 복사본에 반영한다. 문의 표시·월별 부가요금·할부이자의 연동 규칙은 여기서 정한다.
function applyOfferDraft(before, draft, key, addError) {
  const offer = structuredClone(before);
  if (!isObject(draft)) { addError(key, 'row', '행 입력 형식을 확인해 주세요.'); return offer; }
  const parsed = {};
  for (const [field, raw] of Object.entries(draft)) {
    if (!editableFields.has(field)) { addError(key, field, '직접 수정할 수 없는 입력 항목입니다.'); continue; }
    try {
      if (PRICE_FIELDS.includes(field)) parsed[field] = parsePrice(raw, field);
      else if (field === 'supportInquiry') {
        if (typeof raw !== 'boolean') throw Error('문의 표시는 체크 항목으로 입력해 주세요.');
        parsed[field] = raw;
      } else if(field==='addonSchedule') {
        if(raw!==null&&(!Array.isArray(raw)||raw.length!==24||raw.some(value=>!Number.isSafeInteger(value)||value<0||value>100000000)))throw Error('부가서비스 월별 금액 오류');
        parsed[field]=raw===null?null:structuredClone(raw);
      } else {
        if (raw !== null && raw !== undefined && typeof raw !== 'string') throw Error('안내문은 문자열로 입력해 주세요.');
        const text = raw ?? '';
        if (text.length > 6000) throw Error('안내문은 6,000자 이내로 입력해 주세요.');
        parsed[field] = text;
      }
    } catch (error) { addError(key, field, error.message); }
  }
  const assign = (field, value) => {
    if (!equalField(field, before[field], value)) offer[field] = value;
    else if (hasOwn(before, field)) offer[field] = structuredClone(before[field]);
    else delete offer[field];
  };
  for (const [field, value] of Object.entries(parsed)) assign(field, value);
  if(hasOwn(parsed,'addonSchedule')&&parsed.addonSchedule===null)delete offer.addonSchedule;

  // 문의 체크를 명시한 값이 우선한다. 새로 입력한 음수 지원금만 문의로 바꾸고
  // 손대지 않은 과거 저장값은 유지한다.
  if (parsed.supportInquiry === true || (hasOwn(parsed, 'support') && parsed.support < 0)) {
    assign('supportInquiry', true); assign('support', 0);
  } else if (hasOwn(parsed, 'supportInquiry')) {
    assign('supportInquiry', parsed.supportInquiry);
  } else if (hasOwn(parsed, 'support') && !equalField('support', before.support, parsed.support) && before.supportInquiry === true) {
    assign('supportInquiry', false);
  }

  if (!hasOwn(parsed,'addonSchedule') && ['addonFee', 'addonMonths'].some(field => !equalField(field, before[field], offer[field])) && hasOwn(offer, 'addonSchedule')) delete offer.addonSchedule;
  if ([...principalFields].some(field => !equalField(field, before[field], offer[field]))) {
    const discount = key.split('|')[3];
    assign('installmentFee', calculateQuoteWithInterest(offer, discount, 24).installmentFee);
  }
  return offer;
}

// 실제 변경만 모아 다음 전체 설정을 만들고 셀별 오류를 돌려준다. 저장 전 서버 계약과 함께 검증한다.
export function compilePriceDrafts(config, offerDrafts, products) {
  const errors = [], changes = [];
  const addError = (key, field, message) => { if (!errors.some(error => error.key === key && error.field === field && error.message === message)) errors.push({ key, field, message }); };
  if (!isObject(config) || !isObject(config.offers) || !isObject(config.colors)) return { config: null, changes, errors: [{ key: '', field: 'row', message: '설정 형식을 확인해 주세요.' }] };
  if (!isObject(offerDrafts ?? {})) return { config: null, changes, errors: [{ key: '', field: 'row', message: '가격 입력 형식을 확인해 주세요.' }] };
  const next = structuredClone(config);
  const knownKeys = new Set(buildPriceRows(products, config).map(row => row.key));
  const ids = (products || []).map(product => Number(product.id));
  for (const [key, draft] of Object.entries(offerDrafts || {})) {
    if (!knownKeys.has(key)) { addError(key, 'row', '모델·통신사·가입 유형·할인 방법을 확인해 주세요.'); continue; }
    const before = hasOwn(config.offers, key) ? config.offers[key] : {};
    if (!isObject(before)) { addError(key, 'row', '저장된 견적 항목 형식을 확인해 주세요.'); continue; }
    const after = applyOfferDraft(before, draft, key, addError);
    const fields = [...new Set([...Object.keys(before), ...Object.keys(after)])];
    const rowChanges = fields.filter(field => !equalField(field, before[field], after[field])).map(field => ({ key, field, before: before[field], after: after[field] }));
    if (rowChanges.length) { next.offers[key] = after; changes.push(...rowChanges); }
    try { validateConfig({ offers: { [key]: after }, colors: {} }, ids); }
    catch (error) { addError(key, inferField(error.message, after, draft), error.message); }
  }
  try { validateConfig(next, ids); }
  catch (error) {
    let rowError = false;
    for (const [key, offer] of Object.entries(next.offers)) {
      try { validateConfig({ offers: { [key]: offer }, colors: {} }, ids); }
      catch (issue) { rowError = true; addError(key, inferField(issue.message, offer, offerDrafts?.[key]), issue.message); }
    }
    if (!rowError) addError('', 'row', error.message);
  }
  return { config: errors.length ? null : next, changes, errors };
}

const normalize = value => String(value ?? '').toLocaleLowerCase('ko-KR').replace(/\s+/g, '');
const brandAliases = brand => brand === 'Apple' ? 'Apple 애플' : brand === '삼성' ? '삼성 Samsung' : brand;
const isAll = value => value == null || value === '' || value === '전체' || value === 'all';

// 제조사·통신사·가입 조건과 모델명·코드 검색은 원본 행을 지우지 않고 표시만 좁힌다.
export function filterPriceRows(rows, filters = {}, offerDrafts = {}) {
  const query = String(filters.query ?? '').trim().split(/\s+/).filter(Boolean).map(normalize);
  return rows.filter(row => {
    for (const field of ['carrier', 'type', 'discount']) if (!isAll(filters[field]) && normalize(row[field]) !== normalize(filters[field])) return false;
    if (!isAll(filters.brand) && !normalize(brandAliases(row.brand)).includes(normalize(filters.brand))) return false;
    const draft = offerDrafts?.[row.key];
    if (filters.changedOnly) {
      if (!isObject(draft) || !Object.keys(draft).length) return false;
      const result = compilePriceDrafts({ offers: row.registered ? { [row.key]: row.offer || {} } : {}, colors: {} }, { [row.key]: draft }, [row.product]);
      if (!result.changes.length && !result.errors.length) return false;
    }
    const planName = hasOwn(draft, 'planName') ? draft.planName : row.offer?.planName;
    const discountLabel = row.discount === 'choice' ? '선택약정' : `공통 지원금${row.carrier === 'LG U+' ? ' 유통망 지원금' : ''}`;
    const searchable = normalize([row.product.name, row.product.code, ...Object.values(row.product.carrierCodes || {}), row.model, row.carrier, brandAliases(row.brand), row.offer?.planName, planName, row.type, row.discount, discountLabel].join(' '));
    return query.every(token => searchable.includes(token));
  });
}

// Excel TSV의 따옴표 안 줄바꿈·탭과 빈 셀을 보존한다.
// 따옴표 바깥에서만 행·열을 나눠 현재 표의 정확한 위치에 붙인다.
export function parseGridClipboard(text) {
  if (typeof text !== 'string') throw Error('붙여넣을 내용을 확인해 주세요.');
  if (text === '') return [];
  const rows = [];
  let row = [], cell = '', state = 'start', endedWithRowBreak = false;
  const finishCell = () => { row.push(cell); cell = ''; state = 'start'; };
  for (let index = 0; index < text.length; index++) {
    const character = text[index];
    if (state === 'quoted') {
      if (character === '"') {
        if (text[index + 1] === '"') { cell += '"'; index++; }
        else state = 'closed';
      } else cell += character;
      endedWithRowBreak = false;
      continue;
    }
    if (character === '\t' || character === '\n' || character === '\r') {
      finishCell();
      if (character !== '\t') {
        rows.push(row); row = []; endedWithRowBreak = true;
        if (character === '\r' && text[index + 1] === '\n') index++;
      } else endedWithRowBreak = false;
      continue;
    }
    if (state === 'closed') throw Error('셀의 닫는 따옴표 뒤에 탭이나 줄바꿈만 올 수 있습니다. 엑셀에서 다시 복사해 주세요.');
    if (character === '"') {
      if (state !== 'start') throw Error('셀의 따옴표 형식을 확인해 주세요. 엑셀에서 다시 복사해 주세요.');
      state = 'quoted';
    } else { cell += character; state = 'unquoted'; }
    endedWithRowBreak = false;
  }
  if (state === 'quoted') throw Error('셀의 따옴표가 닫히지 않았습니다. 엑셀에서 다시 복사해 주세요.');
  if (!endedWithRowBreak) { finishCell(); rows.push(row); }
  return rows;
}
