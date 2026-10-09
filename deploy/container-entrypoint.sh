#!/bin/sh
# 컨테이너의 Secret 파일 인터페이스를 Spring이 읽는 환경 변수로 연결한다.
# exec로 JVM을 PID 1로 실행해 종료 신호가 셸에 머물지 않고 앱에 전달되게 한다.
set -eu
# Swarm and mounted secret files are read only inside the running app container.
if [ -n "${ADMIN_PASSWORD_FILE:-}" ]; then export ADMIN_PASSWORD="$(cat "$ADMIN_PASSWORD_FILE")"; fi
if [ -n "${DB_PASSWORD_FILE:-}" ]; then export DB_PASSWORD="$(cat "$DB_PASSWORD_FILE")"; fi
if [ -n "${REDIS_PASSWORD_FILE:-}" ]; then export REDIS_PASSWORD="$(cat "$REDIS_PASSWORD_FILE")"; fi
if [ -n "${JWT_SECRET_FILE:-}" ]; then export JWT_SECRET="$(cat "$JWT_SECRET_FILE")"; fi
exec java -jar /app/app.jar
