# Git Flow 운영

이 프로젝트의 GitHub 저장소는 **공개**이며 `main`과 `develop`을 유지합니다. 기본 브랜치는 `develop`입니다. 첫 `main` 커밋은 프로젝트를 시작하기 위한 기준본이며, 실제 운영 배포와 릴리스 태그는 배포를 확인한 뒤 만듭니다.

| 브랜치 | 역할 | 시작 위치 | 병합 위치 |
| --- | --- | --- | --- |
| `main` | 배포 가능한 운영 기준 | 최초 기준본 | release/hotfix를 받음 |
| `develop` | 다음 배포를 위한 개발 통합 | main | feature/release/hotfix를 받음 |
| `feature/설명` | 기능 개발과 일반 수정 | develop | develop |
| `release/버전` | 배포 후보 안정화·버전 정리 | develop | main과 develop |
| `hotfix/설명` | 운영 기준의 긴급 수정 | main | main과 develop; 진행 중인 release에도 반영 |

## 기능 개발

```sh
git switch develop
git pull --ff-only origin develop
git switch -c feature/price-grid-improvement
# 코드 수정과 검증 후
git add 수정한파일
git commit -m "feat: improve price grid editing"
git push -u origin feature/price-grid-improvement
```

GitHub에서 **feature → develop** PR을 열고 CI의 `build-and-test` 결과를 확인합니다. 병합은 merge commit을 사용해 작업 브랜치와 릴리스 이력을 유지합니다. `main`과 `develop`의 직접 push, force push, 공유 이력의 rebase는 피합니다. 브랜치는 필요한 병합이 모두 끝난 뒤 삭제합니다.

## 릴리스

`develop`에서 `release/버전`을 만들고 버전·배포 설정·발견된 오류를 정리합니다. CI와 운영 준비를 확인한 뒤 **release → main** PR을 병합합니다. 실제 배포를 확인한 main 커밋에 `v버전` 태그를 붙이고 태그를 push합니다. 같은 release 브랜치를 **develop에도 병합**해 안정화 수정이 다음 개발에서 빠지지 않게 합니다.

## 긴급 수정

`main`에서 `hotfix/설명`을 만듭니다. 검증 후 **hotfix → main** PR로 반영하고 배포 버전 태그를 만듭니다. 같은 수정을 **develop**과 진행 중인 release에도 반영합니다. develop의 미완성 기능을 긴급 운영 수정에 함께 넣지 않습니다.

## CI와 저장소 설정

`.github/workflows/ci.yml`은 Node 22·Java 17에서 화면 검사, React 빌드, 백엔드 검증과 JAR 패키징을 수행합니다. 백엔드 자동 검사는 H2·메모리 인증 저장소를 사용하므로 운영 DB/Redis 비밀 값을 GitHub Actions에 넣을 필요가 없습니다.

Git Flow용 브랜치/접두사 설정은 이 로컬 저장소의 Git 설정에도 기록합니다. `git flow` 확장 명령이 없는 개발 PC에서는 위의 일반 Git 명령으로 같은 방식을 사용할 수 있습니다.

GitHub의 활성 ruleset **Git Flow - PR and CI**는 `main`·`develop`·`release/**`에 PR 병합, `build-and-test` 상태 검사, 대화 해결, force push 금지를 적용하며 merge commit만 허용합니다. 현재 필수 승인 리뷰 수는 0입니다. **Git Flow - permanent branches**는 `main`·`develop` 삭제를 금지합니다. 전통적인 브랜치 보호 API가 404를 반환해도 이 ruleset은 활성 상태입니다.

공개 저장소에서 Dependabot 취약점 알림·자동 보안 업데이트, secret scanning·push protection을 켰습니다. 푸시 전에 비밀 값이 들어 있지 않은지 로컬에서도 확인하고, 보안 경고가 생기면 패치 버전과 실제 빌드 결과를 검토합니다.

로컬 DB·업로드 사진·비밀 값·JAR·빌드 결과는 `.gitignore`에서 제외합니다. 새 환경에서는 [개발 안내](DEVELOPER_GUIDE.md)와 [배포 안내](../deploy/DEPLOYMENT.md)를 따라 자료와 환경을 준비합니다. 실제 비밀 값은 PR·커밋·이슈 본문에 넣지 않습니다.
