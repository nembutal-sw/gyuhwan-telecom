# 개발 인수인계 안내

## 먼저 읽을 코드

Spring Boot가 고객 정적 페이지와 React 관리자 앱을 같은 서버에서 제공합니다. 고객 화면은 `/`, 관리자 로그인은 `/admin_login`, 관리는 `/admin`입니다. 실행 시작점은 `backend/src/main/java/kr/gyuhwan/AdminApplication.java`, 관리자 화면 시작점은 `frontend/src/main.jsx`입니다.

| 작업 | 수정할 소스 |
| --- | --- |
| 로그인·JWT·CSRF·접근 권한 | `SecurityConfig`, `AuthController`, `JwtAuthenticationFilter`, `JwtSessionService` |
| 관리자 계정·암호·기존 로그인 무효화 | `AdminAccountService`, `AdminPasswordEncoder`, `AdminTokenCookies` |
| 공유 로그인 상태·요청 제한 | `TokenRegistry`, `RedisTokenRegistry`; 테스트에서는 `MemoryTokenRegistry` |
| 가격 저장·이전 저장본·검증 | `ConfigService`, `QuoteValidator` |
| 엑셀 미리보기·적용·내보내기 | `PolicyImportService`, `PolicyWorkbookExporter`, `frontend/src/PolicyTransfer.jsx` |
| 가격표 셀 편집·필터·붙여넣기 | `frontend/src/PriceGridEditor.jsx`, `frontend/src/core/price-grid.mjs` |
| 홈페이지 내용 저장·사진 업로드 | `SiteContentService`, `SiteMediaController` |
| 실제 페이지 직접 편집 | `frontend/src/MainContentEditor.jsx`, `MainPagePreview.jsx`, `frontend/public/preview.js`, `preview-bindings.js` |
| 고객 페이지 자료·임시 입력 반영 | `frontend/public/site-content.js`, `content-data.js`, `offline.js`, `internet.js` |
| 누가 무엇을 변경했는지 기록 | `AuditLogService`, `frontend/src/AuditLogViewer.jsx` |
| 배포 환경·비밀 값·공유 사진 저장소 | `application*.yml`, `Dockerfile`, `compose*.yml`, `deploy/` |

## 자료가 이동하는 순서

로그인은 CSRF 검사와 Redis의 시도 제한을 통과한 뒤 비밀번호를 검증합니다. JWT는 HttpOnly 쿠키로 전달하며, 각 인증 요청에서 서명·발급자·만료뿐 아니라 Redis 등록 상태와 DB 계정 revision도 검사합니다. 계정 변경은 revision을 올려 이미 발급한 JWT를 무효화합니다. Redis 장애 시 인증을 우회하지 않습니다.

가격과 메인 내용 저장 요청에는 읽을 때 받은 revision을 보냅니다. 서비스는 해당 revision에만 조건부 UPDATE를 수행하고 이전 값과 감사 기록을 같은 트랜잭션에 남깁니다. 오래된 입력은 409로 거부합니다. 이 숫자는 운영자에게 보여주는 파일 이름이 아니라 덮어쓰기 충돌을 막는 내부 값입니다. 화면은 저장 시각으로 저장본을 구분합니다.

메인 편집의 임시 값은 React 상태와 iframe 안의 메모리 객체에만 반영합니다. `MainPagePreview`가 같은 origin의 iframe에 메시지를 보내고 `preview.js`가 출처와 부모 창을 검사합니다. `content-data.js`는 기존 객체 참조를 유지하며 값을 바꿔 원본 고객 모듈이 같은 계산·렌더링 함수를 사용하게 합니다. 실제 저장은 변경 확인 후 관리자 API 호출에서만 일어납니다.

엑셀은 업로드 즉시 저장되지 않습니다. 파일을 읽고 모든 행을 검증한 미리보기 결과에서 변경 전후를 확인한 뒤 적용합니다. 가격 계산에서 빈 금액은 미등록(`null`)이며 0원과 의미가 다릅니다. 문의 표시, 음수 추가지원금 처리, 월별 부가요금과 자동 계산 할부이자 규칙은 표 편집·고객 견적·서버 검증을 함께 확인해야 합니다.

## 원본과 빌드 결과물

- 관리자 소스는 `frontend/src`, 고객 페이지 소스는 `frontend/public`입니다. `frontend/dist`, `backend/src/main/resources/static`, `release/*.jar`는 빌드/배포 결과이므로 기능 변경은 소스에서 시작합니다.
- `quote-core.mjs`, `installment-core.mjs`는 관리자용 `frontend/src/core`와 고객용 `frontend/public`에 같은 구현을 유지합니다. 수정할 때 두 위치를 함께 갱신합니다. `frontend/content-regions.js`와 `frontend/public/content-regions.js`도 동일한 구역 지도를 사용합니다.
- `site-content-seed.json`의 정적 필드는 `site.html`의 `data-content-node`와 TextNode 순서에 연결됩니다. 기존 노드 ID를 바꾸거나 편집 대상 내부에 주석/태그를 삽입하면 연결이 달라질 수 있으므로 미리보기 검사를 실행합니다.
- `scripts/sync-main-page.mjs`, `sync-site-images.mjs`는 원본 사이트를 다시 수집해 로컬 파일을 갱신하는 도구입니다. 일반 빌드에서 자동 실행하지 않습니다. 재수집 전 현재 수정과 편집 필드 ID 대응을 검토합니다.
- `.runtime`에는 로컬 DB·인증 자료·비밀 값이 있습니다. 개발 인수인계에 필요한 소스와 운영 비밀 값의 전달은 별도로 관리합니다.

## DB 변경

운영 저장소는 MySQL/MariaDB입니다. 가격과 메인 내용은 LONGTEXT 안의 JSON과 별도 revision·시각으로, 계정과 감사 기록은 별도 행으로 저장합니다. H2는 자동 검사 전용입니다. 업로드 사진은 파일 저장소에 있고 DB는 URL을 참조하므로 DB와 사진을 함께 백업합니다.

적용된 Flyway SQL은 주석 변경도 체크섬을 바꿉니다. 기존 파일을 수정하지 않고 새 `V번호__설명.sql`을 추가합니다. 현재 마이그레이션의 목적은 다음과 같습니다.

| 마이그레이션 | 목적 |
| --- | --- |
| V1 | 가격 현재값·이력, 이전 문의·상담 테이블 |
| V2 | 홈페이지 내용 현재값·이력 |
| V3 | 관리자 계정, 대소문자 무시 아이디 키, 대표 관리자 제약, 계정 revision |
| V4 | 감사 기록과 조회 인덱스 |
| V5 | 가격·내용 JSON 컬럼을 LONGTEXT로 확장 |

이전 문의/상담 조회 코드는 과거 데이터 이전·보존기간 정리를 위해 남아 있습니다. 현재 고객 화면은 전화·카카오톡 상담을 사용합니다. `IMPORT_PATH`를 지정하면 `ImportRunner`가 시작 시 가져오기를 수행하므로 이전 완료 후 값을 비웁니다. `MigrateLocalData.java`는 새 seed DB만 대상으로 하는 일회성 H2 이전 도구입니다.

## 실행과 확인

이 PC의 로컬 실행은 `./scripts/start-local.ps1`입니다. 실제 운영 환경의 DB·Redis·HTTPS·사진 공유 설정은 [배포 안내](../deploy/DEPLOYMENT.md)를 참고합니다.

화면 수정 후 `frontend`에서 `npm run build`, `frontend/dist`를 백엔드 정적 폴더에 복사한 뒤 `backend`에서 `mvn verify`를 실행합니다. Bash 환경은 `bash scripts/build.sh`로 같은 순서를 수행합니다. 주요 추가 검사는 프로젝트 루트에서 실행합니다.

```sh
node scripts/test-price-grid.mjs
node scripts/test-main-preview.mjs
node scripts/test-video-urls.mjs
```

주석은 파일 역할, 입력/출력 계약, 보안 경계와 설계 이유를 설명하도록 작성했습니다. 로직을 바꿀 때는 해당 주석과 관련 검증도 함께 갱신합니다. 현재 검증 기록은 [VALIDATION.md](VALIDATION.md)에 있습니다.
