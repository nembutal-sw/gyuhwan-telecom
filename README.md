# 규환 통신 로컬 사이트와 관리자

Spring Boot 4.1.1 + React로 고객 메인 페이지와 관리자 화면을 함께 제공합니다. 고객 화면은 `/`, 관리자 로그인은 `/admin_login`, 로그인 후 관리는 `/admin`입니다. 기존 `chatgpt.site` 호스팅에 자동 반영되지는 않습니다.

## 기능

- 48개 용량별 모델·864개 가격 조건을 셀 편집 표에서 관리하고 통신사·제조사·가입 유형·할인 방식 필터와 직접 검색 제공; 처음에는 SKT·번호이동·공통·유통망 지원금 조건의 48개 모델을 모두 표시
- 금액·요금제, 안내 문구, 모델별 색상·사진을 편집하며 필터·페이지 이동에도 입력을 유지하고 전체 변경을 비교해 한 번에 저장
- 엑셀의 여러 셀을 현재 페이지 표에 붙여넣거나, 전체 가격 정책 XLSX 파일을 내려받아 수정·검토 후 업로드
- 원본 글씨체·글자 크기를 유지하며 22개 편집 항목 그룹을 실제 페이지의 14개 구역에 연결
- 실제 메인페이지에서 문구·사진을 더블클릭하고 메뉴·이동 링크를 한 번 클릭해 편집, 사진·영상 수정 버튼과 파일 업로드·URL 변경
- 여러 구역의 입력을 유지하며 PC·모바일 미리보기, 변경 전후를 검토한 뒤 한 번에 저장
- 구역 이동과 선택한 부분 수정 창, 화면에서 찾기 어려운 항목을 위한 구역별 전체 항목 목록
- 고객페이지에 적용된 저장본의 마지막 저장·확인 시각을 표시하고, 이전 저장본은 날짜·시각으로 선택해 되돌리기
- 관리자 아이디·비밀번호 변경, 대표 관리자의 관리자 추가·다른 계정 변경
- 누가 언제 무엇을 바꿨는지 변경 전후 기록과 검색; 금액·목록·안내 문구를 읽기 쉬운 형태로 표시
- 고객 상담은 전화·카카오톡으로 연결하며 관리자 상담 기록 메뉴 제거
- 서버 간 로그인 상태를 공유하는 JWT + Redis 인증
- 계정별 서버 자동 임시 저장·복구, 선택한 가격 행의 일괄 값 지정·증감·요금제 복사
- 한국 시간 기준 가격 정책 예약·기간 종료 복귀와 수동 변경 충돌 보호
- 신규 모델 등록, 모델·색상별 판매 상태와 고객 목록 숨김; 최대 300개 모델 관리
- 직원별 수정·조회 권한, 계정 사용 중지와 기존 로그인 무효화
- 사진 보관함의 검색·재사용·사용 위치 확인, 익명 모델 조회·전화·카카오톡 클릭 통계

공개 사이트의 이미지 58개(6,286,789바이트)와 Noto Sans KR 폰트 파일은 실행 파일에도 포함되어 있습니다. PNG/JPG/WebP 37개, 색상칩 SVG 20개, SVG 파비콘 1개입니다. `docs/site-images-manifest.json`에서 원본 URL과 SHA-256을 확인할 수 있습니다.

## 저장 구조

| 데이터 | 저장 위치·형식 |
|---|---|
| 관리자 계정 | MySQL/MariaDB `admin_accounts` 행; 비밀번호는 SHA-256 전처리 후 BCrypt 해시 |
| 가격·메인 내용 | `quote_config`, `site_content`의 LONGTEXT 컬럼 안에 JSON과 별도 버전·수정 시각 |
| 이전 버전 | `config_history`, `site_content_history` |
| 계정별 임시 작업·예약 가격표 | `admin_workspaces`, `policy_schedules`의 JSON·기준 버전·기간·처리 상태 |
| 신규 모델·판매 상태 | `catalog_model`의 JSON과 `catalog_state`의 모델 ID·버전 |
| 사진 보관함·관심 통계 | `media_asset` 메타데이터, `interest_daily` 일별 모델·이벤트 집계 |
| 변경 기록 | `audit_log` 행; 관리자·작업·대상·시각과 JSON 변경 전후 |
| 로그인 상태·로그인 시도 제한·업로드 제한 | Redis; JWT의 고유 ID·계정 ID·만료 TTL과 요청 제한 카운터 |
| 업로드 사진 | `MEDIA_PATH`, 기본 `data/media`; DB에는 사진 URL 저장 |
| 최초 원본 이미지·폰트·페이지 | JAR 안 정적 파일 |

API는 JSON으로 통신합니다. 가격과 페이지 내용은 **관계형 DB 내부의 JSON 문자열**로 저장되며 외부 JSON 파일이 운영 저장소가 아닙니다. 계정·수정 기록은 각각 별도 테이블입니다. H2는 자동 테스트에만 사용하고 실행 JAR에는 포함하지 않습니다.

## 이 PC에서 다시 실행

로컬 확인용 MySQL 8.4.11(3307)과 Redis 프로토콜을 제공하는 Memurai Developer(6380)를 `.runtime`에 준비했습니다. Windows 서비스나 방화벽 규칙은 등록하지 않고 127.0.0.1로만 실행합니다.

```powershell
./scripts/start-local.ps1
```

주소: `http://127.0.0.1:8080/admin_login`. 초기 로컬 로그인 정보는 `.runtime/admin-login.txt`에 있으며 계정 변경 후에는 DB의 새 아이디·비밀번호를 사용합니다. 대표 관리자 최초 생성 후 환경 변수를 바꾸어도 기존 계정이 덮어써지지 않습니다.

MySQL 데이터는 `.runtime/mysql-data`, 인증 데이터는 `.runtime`의 Redis AOF, 사진은 `data/media`에 남습니다. `.runtime`의 비밀 값과 로그인 파일은 버전 관리에서 제외되며 현재 Windows 사용자에게만 파일 권한을 줬습니다. 기존 H2 파일과 `.runtime/local-preview-before-mysql.mv.db` 백업은 보존했습니다. 가격·메인 현재 버전 4와 각 이전 버전 3개를 MySQL에 이전했습니다.

Memurai Developer는 개발·테스트용이며 10일 연속 실행 후 재시작이 필요합니다. 운영 배포는 아래 Redis 컨테이너 구성을 사용합니다. [Memurai 공식 안내](https://www.memurai.com/faq)

## 새 환경·운영 배포

MySQL/MariaDB와 Redis 연결, 최초 관리자 비밀번호, 공유 JWT 키를 설정해야 합니다. Java 17 이상이 필요하며 단일 실행 파일은 `release/gyuhwan-admin-1.0.0.jar`입니다.

[배포 안내](deploy/DEPLOYMENT.md)에는 MySQL Compose, MariaDB override, Kubernetes·Docker Swarm 예시와 비밀 값 생성 방법이 있습니다.

```sh
node deploy/generate-secrets.mjs --compose-env
docker compose up --build -d
# MariaDB:
docker compose -f compose.yml -f compose.mariadb.yml up --build -d
```

모든 앱 인스턴스가 같은 DB·Redis·JWT 키를 사용합니다. 사진은 RWX/NFS 등 공유 저장소에 연결합니다. 준비 확인 `/actuator/health/readiness`는 DB와 Redis 연결도 검사합니다. 공개 도메인에서는 `COOKIE_SECURE=true`를 설정하고 관리자 계정의 임시 암호를 변경하세요. 같은 호스트의 Cloudflare Tunnel은 비공개 루프백 HTTP를 원본으로 사용할 수 있습니다. 일반 Cloudflare 프록시는 원본 TLS와 **Full (strict)** 모드로 구성합니다. 프록시의 로그인 시도 제한을 위한 신뢰 IP 설정은 [배포 안내](deploy/DEPLOYMENT.md)를 참고하세요.

Kubernetes/Swarm 예시는 문법을 확인했으며 실제 클러스터 배포는 수행하지 않았습니다. 예시 DB·Redis는 각 1개로, 해당 저장소의 고가용성은 운영 인프라에서 별도로 구성해야 합니다.

## JWT 인증

HS256 JWT를 HttpOnly·SameSite Strict 쿠키로 전달하며 기본 만료는 30분입니다. localStorage에는 토큰을 저장하지 않습니다. JWT 서명·발급자·만료, Redis 등록 상태, DB 계정 버전을 모두 확인합니다. 로그아웃은 Redis에서 토큰을 제거하고, 아이디·비밀번호 변경은 계정 버전을 올려 이전 토큰을 모든 서버에서 무효화합니다. Redis 연결 실패 시 관리자 인증은 503으로 차단합니다.

쿠키 인증이므로 로그인·로그아웃·모든 변경 API에는 CSRF 토큰을 요구합니다. 최초 계정은 대표 관리자이며 다른 계정 추가·변경 권한이 있습니다. 일반 관리자는 자신의 계정을 변경할 수 있습니다. 비밀번호는 응답이나 변경 기록에 포함하지 않습니다.

## 사용 안내·개발

- [Git Flow 브랜치·PR 운영](docs/GIT_FLOW.md)
- [매장 운영 기능 7개 사용 안내](docs/OPERATIONS.md)
- [개발 인수인계·코드 흐름](docs/DEVELOPER_GUIDE.md)
- [가격표 셀 편집·엑셀 관리](docs/POLICY_FILES.md)
- [메인 페이지 직접 편집·미리보기](docs/MAIN_CONTENT.md)
- [계정·변경 기록](docs/ADMIN_ACCOUNTS.md)
- [검증 결과](docs/VALIDATION.md)

빌드: Java 17+, Maven, Node.js 22.12+에서 `bash scripts/build.sh`. Windows에서는 frontend의 `npm ci`, `npm run build` 후 `frontend/dist`를 backend 정적 폴더로 복사하고 `mvn verify`를 실행합니다. Vite 개발 화면은 API를 8080 서버로 전달합니다. 백엔드에는 같은 MySQL/MariaDB·Redis 환경 변수가 필요합니다.

사이트 이미지 갱신: `node scripts/sync-site-images.mjs`. 원본 메인 구조 갱신: `node scripts/sync-main-page.mjs`. 변경 후 실행 파일을 다시 빌드하세요.

기존 Sites 데이터 이전 도구 `scripts/normalize-config.mjs`, `scripts/export-legacy.py`, `IMPORT_PATH`는 유지합니다. 기존 H2 로컬 데이터를 새 DB로 옮기는 일회성 `scripts/MigrateLocalData.java`는 대상 DB가 새 초기 데이터만 갖고 있을 때만 작동하고 원본은 보존합니다. 이미 운영 중인 DB에는 실행하지 않습니다.
