FROM node:24-alpine AS frontend
WORKDIR /build/frontend
RUN npm install -g pnpm@11.25.0
COPY frontend/package.json frontend/pnpm-lock.yaml frontend/pnpm-workspace.yaml ./
RUN pnpm install --frozen-lockfile
COPY frontend/ ./
RUN pnpm test && pnpm build

FROM maven:3.9.11-eclipse-temurin-21 AS backend
WORKDIR /build/backend
COPY backend/ ./
COPY --from=frontend /build/frontend/dist/ bootstart/src/main/resources/static/
RUN mvn -B verify

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S studio && adduser -S studio -G studio && mkdir /data && chown studio:studio /data
WORKDIR /app
COPY --chmod=644 --from=backend /build/backend/bootstart/target/bootstart-0.0.1-SNAPSHOT.jar app.jar
USER studio
EXPOSE 8080
ENV SPRING_PROFILES_ACTIVE=prod
ENTRYPOINT ["java","-jar","/app/app.jar"]
