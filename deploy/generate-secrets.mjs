/**
 * 새 환경의 비밀 파일을 생성하고 필요하면 Compose용 .env를 만든다.
 * 기존 파일은 재사용하고 값을 출력하지 않는다. 재실행은 운영 암호를 자동 교체하지 않는다.
 */
import { randomBytes } from 'node:crypto';
import { mkdir, readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const directory = path.join(path.dirname(fileURLToPath(import.meta.url)), 'secrets');
const project = path.dirname(path.dirname(fileURLToPath(import.meta.url)));
await mkdir(directory, { recursive: true, mode: 0o700 });
const files = { ADMIN_PASSWORD: 'admin_password', DB_ROOT_PASSWORD: 'db_root_password', DB_PASSWORD: 'db_password', REDIS_PASSWORD: 'redis_password', JWT_SECRET: 'jwt_secret' };
const values = {};
for (const [name, filename] of Object.entries(files)) {
  const destination = path.join(directory, filename);
  try { values[name] = (await readFile(destination, 'utf8')).trim(); }
  catch (error) {
    if (error.code !== 'ENOENT') throw error;
    values[name] = name === 'ADMIN_PASSWORD' ? `${randomBytes(32).toString('base64url')}Aa1!` : randomBytes(32).toString('base64');
    await writeFile(destination, values[name], { flag: 'wx', mode: 0o600 });
  }
  if (!values[name]) throw new Error(`Empty existing secret file: ${filename}`);
}
if (!/^[A-Za-z0-9+/]+={0,2}$/.test(values.JWT_SECRET) || Buffer.from(values.JWT_SECRET, 'base64').length < 32) throw new Error('JWT secret must contain at least 32 decoded bytes as base64.');
const adminPassword = values.ADMIN_PASSWORD;
const hasSymbol = [...adminPassword].some(char => { const code = char.charCodeAt(0); return (code >= 33 && code <= 47) || (code >= 58 && code <= 64) || (code >= 91 && code <= 96) || (code >= 123 && code <= 126); });
if (adminPassword.length < 8 || adminPassword.length > 128 || !/[A-Z]/.test(adminPassword) || !/[a-z]/.test(adminPassword) || !/[0-9]/.test(adminPassword) || !hasSymbol) throw new Error('Admin password must be 8–128 characters and include uppercase and lowercase Latin letters, a digit, and an ASCII symbol.');
// 환경 파일은 템플릿의 빈 비밀 항목만 채우며 wx로 기존 .env 덮어쓰기를 막는다.
if (process.argv.includes('--compose-env')) {
  const template = await readFile(path.join(project, 'deploy', 'compose.env.example'), 'utf8');
  const content = template.replace(/^(ADMIN_PASSWORD|DB_ROOT_PASSWORD|DB_PASSWORD|REDIS_PASSWORD|JWT_SECRET)=$/gm, (_, name) => `${name}=${values[name]}`);
  await writeFile(path.join(project, '.env'), content, { flag: 'wx', mode: 0o600 });
  console.log('Created .env without overwriting an existing file.');
}
console.log('Secret files are ready under deploy/secrets/. Values were not printed.');
