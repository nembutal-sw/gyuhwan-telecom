# 컨테이너 배포

기본 구성은 MySQL 8.4, 인증을 사용하는 Redis, 관리자 애플리케이션입니다. 앱의 DB 드라이버는 `DB_URL`의 `jdbc:mysql:` 또는 `jdbc:mariadb:` 형식에 따라 선택됩니다. DB와 Redis 포트는 앱 내부 네트워크에서 사용합니다.

## 비밀 값 생성

프로젝트 루트에서 실행합니다. 생성기는 암호와 JWT 키를 화면에 출력하지 않으며 기존 파일을 덮어쓰지 않습니다.

```sh
node deploy/generate-secrets.mjs --compose-env
```

`deploy/secrets/`에 파일 5개, 루트에 `.env`가 생성됩니다. JWT 키는 무작위 32바이트의 base64 표현입니다. 관리자 비밀번호도 무작위로 생성됩니다. 파일 내용을 관리자에게 안전하게 전달하고 `.env`와 `deploy/secrets/`는 버전 관리에 넣지 않습니다. 이미 `.env`가 있다면 생성기를 옵션 없이 실행하고 필요한 값을 기존 환경에 반영합니다.

| 환경 변수 | 용도 |
| --- | --- |
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | MySQL 또는 MariaDB 연결 |
| `REDIS_HOST`, `REDIS_PORT`, `REDIS_PASSWORD` | Redis 연결과 인증 |
| `REDIS_SSL` | TLS Redis이면 `true` |
| `REDIS_DATABASE`, `REDIS_PREFIX` | 기본 `0`, `gyuhwan:auth:` |
| `JWT_SECRET` | 모든 앱 인스턴스가 공유하는 base64 키, 원본 32바이트 이상 |
| `JWT_TTL_SECONDS` | 기본 1800초 |
| `COOKIE_SECURE` | HTTPS 배포에서 `true`, 로컬 HTTP 확인에서 `false` |
| `ADMIN_USERNAME`, `ADMIN_PASSWORD` | 최초 대표 관리자 계정 생성 |
| `MEDIA_PATH` | 기본 컨테이너 경로 `/app/data/media` |

DB·Redis·JWT 키와 Redis 접두사는 모든 앱 인스턴스에서 동일하게 설정합니다. Redis는 로그인 상태와 로그인 시도 제한을 공유합니다. JWT 키를 바꾸면 기존 토큰으로 로그인 상태를 이어갈 수 없습니다.

로그인 제한은 접속 IP별 10분에 20회입니다. HTTPS 프록시·Ingress 뒤에서는 `FORWARD_HEADERS_STRATEGY=native`, `TRUSTED_PROXY_REGEX`에 **실제 프록시 주소만** 일치하는 정규식을 설정하세요. 프록시가 전달하는 `X-Forwarded-For`를 이용합니다. 기본값 `none`은 전달 헤더를 신뢰하지 않아 프록시의 접속들이 한 IP로 묶입니다. 신뢰 범위를 전체 주소로 넓히지 말고 앱 접근도 해당 프록시로 제한하세요.

## Compose · MySQL

```sh
docker compose config --quiet
docker compose up --build -d
docker compose ps
```

로컬 확인 주소는 `http://127.0.0.1:8080/`, 로그인은 `/admin_login`입니다. 공개 HTTPS 프록시를 연결할 때 `.env`의 `COOKIE_SECURE=true`를 설정합니다. 프록시가 다른 장치에 있다면 `PUBLIC_BIND_ADDRESS`를 프록시가 접근할 주소로 설정합니다. 기본값은 `127.0.0.1`입니다.

## Cloudflare로 공개할 때

Cloudflare가 방문자에게 제공하는 HTTPS와 Cloudflare에서 원본 서버로 이어지는 연결은 별개입니다. 관리자 로그인이 있는 이 서비스에서는 방문자 쪽에 HTTPS를 강제하고, 원본까지도 암호화되거나 같은 호스트의 비공개 연결로 이어지도록 구성합니다. 일반 프록시 DNS 방식에서 **Flexible 모드**는 원본까지 HTTP 평문으로 연결하므로 로그인 페이지를 공개하는 구성에는 사용하지 않습니다. Tunnel 연결은 별도 암호화 채널이며, Cloudflare의 SSL 모드가 Tunnel의 로컬 서비스 URL 프로토콜을 바꾸지는 않습니다. [Cloudflare Flexible 모드](https://developers.cloudflare.com/ssl/origin-configuration/ssl-modes/flexible/), [Tunnel의 원본 프로토콜](https://developers.cloudflare.com/tunnel/troubleshooting/https-origins/)

이 프로젝트의 기본 Compose는 로컬 HTTP 확인을 위해 `127.0.0.1:8080`에만 바인딩하고 `COOKIE_SECURE=false`입니다. 같은 호스트에 `cloudflared`를 설치한다면 Cloudflare Tunnel의 공개 호스트 이름을 `http://127.0.0.1:8080`에 연결하고, 공개 배포에는 아래의 추가 설정을 사용합니다. Tunnel은 원본에서 Cloudflare로 나가는 연결을 만들므로 원본의 8080 인바운드 포트를 인터넷에 열지 않아도 됩니다. 브라우저 접속은 HTTPS이므로 앱과 Tunnel 사이가 로컬 HTTP여도 관리자 쿠키는 `Secure`로 설정합니다. [Cloudflare Tunnel 공개 애플리케이션](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/routing-to-tunnel/), [Tunnel 방화벽](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/configure-tunnels/tunnel-with-firewall/)

```sh
docker compose -f compose.yml -f deploy/compose.cloudflare.yml config --quiet
docker compose -f compose.yml -f deploy/compose.cloudflare.yml up --build -d
```

Cloudflare 대시보드에서 이 호스트 이름에 **Always Use HTTPS**를 적용하고 Tunnel의 서비스 URL을 위 로컬 주소로 설정합니다. 연결 후 `https://실제-도메인/admin_login`에서 로그인 응답의 `Set-Cookie`에 `Secure`가 붙는지 확인합니다. Tunnel을 다른 호스트나 컨테이너에 둘 경우, HTTP 구간이 공용망을 지나지 않도록 내부 통신을 보호하고 해당 프록시만 앱에 접근하게 제한합니다. [Cloudflare Always Use HTTPS](https://developers.cloudflare.com/ssl/edge-certificates/additional-options/always-use-https/)

Tunnel 대신 일반 Cloudflare 프록시 DNS로 공개한다면 원본 앞에 TLS 리버스 프록시와 유효한 인증서를 두고 Cloudflare **Full (strict)** 모드를 선택합니다. 앱 8080은 해당 프록시에서만 접근할 수 있게 방화벽으로 제한합니다. Cloudflare 인증서가 브라우저에 보인다는 사실만으로 원본 연결의 암호화 또는 직접 접속 차단이 보장되지는 않습니다. [Cloudflare Full (strict)](https://developers.cloudflare.com/ssl/origin-configuration/ssl-modes/full-strict/)

프록시 뒤 로그인 시도 제한이 방문자별로 동작하도록 `FORWARD_HEADERS_STRATEGY=native`와 **실제 원본이 관찰한 프록시 접속 주소만** 허용하는 `TRUSTED_PROXY_REGEX`를 설정합니다. 임의 인터넷 주소 전체를 신뢰하면 클라이언트가 전달 헤더를 위조할 수 있습니다. 프록시의 실제 IP를 배포 환경에서 확인한 뒤 정규식을 설정하고, 원본에 직접 들어오는 트래픽을 차단하세요.

MySQL은 `database_mysql`, Redis는 `redis`, 업로드 사진은 `media` 볼륨에 저장합니다. `docker compose down`은 볼륨을 보존합니다. DB와 사진 볼륨은 함께 백업하고 복구 후 `/actuator/health/readiness`와 저장된 사진을 확인합니다. 태그를 갱신할 때 실제 운영 이미지의 버전과 digest를 기록합니다.

## Compose · MariaDB

```sh
docker compose -f compose.yml -f compose.mariadb.yml config --quiet
docker compose -f compose.yml -f compose.mariadb.yml up --build -d
```

MariaDB 11.8과 `jdbc:mariadb:` 연결을 사용하며 `database_mariadb` 볼륨을 새로 사용합니다. 기존 MySQL 데이터 디렉터리를 MariaDB에 그대로 연결하지 않습니다. 기존 데이터가 필요하면 논리 백업을 새 DB에 복원하는 별도 이전 작업을 수행합니다. DB 초기화 환경 변수는 기존 DB가 있는 데이터 디렉터리의 계정 암호를 변경하지 않습니다. [MySQL 공식 이미지 설명](https://hub.docker.com/_/mysql), [MariaDB 환경 변수 설명](https://mariadb.com/docs/server/server-management/automated-mariadb-deployment-and-administration/docker-and-mariadb/mariadb-server-docker-official-image-environment-variables)

## Kubernetes 예시

아래 여러 줄 명령은 Bash 기준입니다. PowerShell에서는 이어쓰기 문자를 바꾸거나 명령을 한 줄로 실행합니다.

`kubernetes.example.yml`의 앱 이미지와 `rwx-storage`를 실제 레지스트리 이미지 및 RWX StorageClass로 바꿉니다. MySQL·Redis는 각각 1개 StatefulSet, 앱은 2개 Pod 예시입니다. DB·Redis는 운영 서비스나 관리형 서비스로 교체할 수 있습니다. 이 파일 자체는 DB·Redis 고가용성을 구성하지 않습니다.

```sh
node deploy/generate-secrets.mjs
kubectl create namespace gyuhwan --dry-run=client -o yaml | kubectl apply -f -
kubectl -n gyuhwan create secret generic gyuhwan-secrets \
  --from-file=admin_password=deploy/secrets/admin_password \
  --from-file=db_password=deploy/secrets/db_password \
  --from-file=db_root_password=deploy/secrets/db_root_password \
  --from-file=redis_password=deploy/secrets/redis_password \
  --from-file=jwt_secret=deploy/secrets/jwt_secret
kubectl apply -f deploy/kubernetes.example.yml
kubectl -n gyuhwan rollout status deployment/gyuhwan-admin
```

앱 Service 앞에 실제 도메인의 HTTPS Ingress와 TLS 인증서를 연결합니다. 예시는 `COOKIE_SECURE=true`이므로 HTTP 브라우저 접속을 시험할 때는 먼저 로컬 확인용 설정으로 바꿉니다. 준비 확인은 `/actuator/health/readiness`, 프로세스 생존 확인은 `/actuator/health/liveness`입니다.

사진 PVC는 여러 노드의 Pod가 같은 파일을 읽고 쓸 수 있는 `ReadWriteMany` 저장소가 필요합니다. 앱은 UID/GID 10001을 사용합니다. CSI의 그룹 권한 지원을 확인하고, NFS 등의 저장소에는 해당 UID/GID의 읽기·쓰기 권한을 준비합니다. 노드별 `hostPath`는 사진을 공유하지 못합니다. [Kubernetes 볼륨 접근 모드](https://kubernetes.io/docs/concepts/storage/persistent-volumes/)

Secret은 YAML 파일에 값을 넣지 않고 로컬 파일에서 생성합니다. Secret 조회 권한과 클러스터 저장 시 암호화 설정은 운영 클러스터에서 관리합니다. [Kubernetes Secret 문서](https://kubernetes.io/docs/concepts/configuration/secret/)

## Docker Swarm 예시

이 절의 명령은 Linux/Bash 배포 호스트 기준입니다.

이미지를 레지스트리에 빌드·업로드한 뒤 모든 노드가 그 이미지를 가져올 수 있게 준비합니다. DB·Redis의 로컬 볼륨을 유지할 노드 하나에 `gyuhwan-stateful=true` 라벨을 붙이고, 모든 앱 노드가 접근 가능한 NFS export를 준비합니다. `swarm.example.yml`은 암호화된 overlay 네트워크와 앱 2개 인스턴스를 사용합니다.

```sh
node deploy/generate-secrets.mjs
docker secret create gyuhwan_admin_password deploy/secrets/admin_password
docker secret create gyuhwan_db_password deploy/secrets/db_password
docker secret create gyuhwan_db_root_password deploy/secrets/db_root_password
docker secret create gyuhwan_redis_password deploy/secrets/redis_password
docker secret create gyuhwan_jwt_secret deploy/secrets/jwt_secret
docker node update --label-add gyuhwan-stateful=true STATEFUL_NODE_NAME
docker network create --driver overlay --attachable --opt encrypted gyuhwan_edge
export APP_IMAGE=REGISTRY/gyuhwan-admin:RELEASE_TAG
export MEDIA_NFS_SERVER=NFS_SERVER_ADDRESS
export MEDIA_NFS_EXPORT=/EXPORTED_MEDIA_DIRECTORY
docker stack config -c deploy/swarm.example.yml >/dev/null
docker stack deploy -c deploy/swarm.example.yml gyuhwan
docker stack services gyuhwan
```

대문자 예시 인자는 실제 이미지·노드·NFS 설정으로 바꿉니다. 앱 이미지의 진입 스크립트는 `/run/secrets/` 파일을 읽어 `ADMIN_PASSWORD`, `DB_PASSWORD`, `REDIS_PASSWORD`, `JWT_SECRET`을 JVM에 전달합니다. MySQL도 공식 이미지의 `_FILE` 입력을 사용합니다. Docker Secret에는 생성한 파일을 전달하며 값 자체를 명령문이나 저장된 YAML에 쓰지 않습니다. [Docker Secret 사용법](https://docs.docker.com/engine/swarm/secrets/)

Swarm 앱은 8080을 공개 포트로 게시하지 않습니다. TLS 리버스 프록시 또는 Tunnel 커넥터를 위에서 생성한 `gyuhwan_edge` 네트워크에 연결하고 내부 서비스 `gyuhwan_admin:8080`으로 전달합니다. Cloudflare 직접 프록시 방식이라면 TLS 리버스 프록시에서 인증서를 검증할 수 있도록 구성하고 **Full (strict)** 모드를 선택합니다. NFS export의 UID/GID 10001 권한과 두 앱 노드에서 사진 공유를 확인합니다. DB·Redis는 라벨이 붙은 단일 노드의 예시이므로 해당 노드의 복구·백업을 운영 절차에 포함합니다. 같은 데이터 볼륨을 쓰는 MySQL 또는 Redis 서비스를 단순히 여러 개로 늘리지는 않습니다.

외부 Redis 서비스를 사용할 경우 인증과 필요한 TLS를 제공하고 `REDIS_SSL=true`를 설정합니다. Redis 인증 명령 자체는 암호화되지 않으므로 별도 호스트 연결에서는 TLS 또는 보호된 네트워크를 사용합니다. [Redis 보안 문서](https://redis.io/docs/latest/operate/oss_and_stack/management/security/)
