#!/usr/bin/env bash
# 릴리스 순서: React 빌드 → Spring 정적 리소스 복사 → 백엔드 검증 → 실행 JAR 배치.
# backend/static과 release는 결과물이다. 화면 수정은 frontend/src 또는 frontend/public에서 한다.
set -euo pipefail
cd "$(dirname "$0")/.."
(cd frontend && npm ci && npm run build)
mkdir -p backend/src/main/resources/static
cp -R frontend/dist/. backend/src/main/resources/static/
(cd backend && mvn -B verify)
mkdir -p release
cp backend/target/gyuhwan-admin-1.0.0.jar release/gyuhwan-admin-1.0.0.jar
