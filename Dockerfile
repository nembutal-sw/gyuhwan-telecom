# 세 단계 빌드: 화면 생성 → 테스트를 통과한 JAR → JRE만 포함한 실행 이미지.
# .runtime/비밀 파일은 .dockerignore에서 제외한다. 최종 앱은 UID 10001로 실행한다.
FROM node:22-alpine AS ui
WORKDIR /app/frontend
COPY frontend/package*.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run build
FROM maven:3.9.11-eclipse-temurin-17 AS build
WORKDIR /app/backend
COPY backend/pom.xml ./
RUN mvn -B dependency:go-offline
COPY backend/src ./src
# Spring이 고객 페이지와 관리자 앱을 같은 origin에서 제공하도록 정적 화면을 포함한다.
COPY --from=ui /app/frontend/dist ./src/main/resources/static
RUN mvn -B verify
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
RUN addgroup -S -g 10001 app && adduser -S -u 10001 -G app app && mkdir -p /app/data/media && chown -R app:app /app/data
COPY --from=build /app/backend/target/gyuhwan-admin-1.0.0.jar app.jar
COPY deploy/container-entrypoint.sh /app/container-entrypoint.sh
RUN sed -i 's/\r$//' /app/container-entrypoint.sh && chmod 0555 /app/container-entrypoint.sh
USER app
ENV BIND_ADDRESS=0.0.0.0
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 CMD wget -q -O /dev/null http://127.0.0.1:8080/actuator/health/readiness || exit 1
ENTRYPOINT ["/app/container-entrypoint.sh"]
