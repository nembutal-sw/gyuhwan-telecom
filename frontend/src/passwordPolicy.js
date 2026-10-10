export const passwordHelp = '8~128자 · 영문 대문자, 소문자, 숫자, 영문 키보드 특수문자를 각각 1자 이상 포함';

export function passwordValid(value) {
  if (typeof value !== 'string' || value.length < 8 || value.length > 128) return false;
  let upper = false, lower = false, digit = false, special = false;
  for (let i = 0; i < value.length; i++) {
    const code = value.charCodeAt(i);
    upper ||= code >= 65 && code <= 90;
    lower ||= code >= 97 && code <= 122;
    digit ||= code >= 48 && code <= 57;
    special ||= (code >= 33 && code <= 47) || (code >= 58 && code <= 64) || (code >= 91 && code <= 96) || (code >= 123 && code <= 126);
  }
  return upper && lower && digit && special;
}
