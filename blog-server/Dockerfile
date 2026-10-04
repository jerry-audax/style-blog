FROM maven:3.9-eclipse-temurin-17 AS builder
WORKDIR /build
COPY pom.xml .
RUN mvn dependency:go-offline -q
COPY src/ src/
RUN mvn package -DskipTests -q

FROM node:22-alpine AS music-builder
WORKDIR /music-runtime
COPY music-runtime/package*.json ./
RUN npm ci --ignore-scripts --no-audit --no-fund
COPY music-runtime/*.cjs ./
RUN npm test

FROM eclipse-temurin:17-jre-alpine
RUN apk add --no-cache libstdc++
WORKDIR /app
COPY --from=music-builder /usr/local/bin/node /usr/local/bin/node
COPY --from=music-builder /music-runtime /app/music-runtime
COPY --from=builder /build/target/*.jar app.jar
ENV MUSIC_STATE_DIRECTORY=/var/lib/blog-music
ENV MUSIC_RUNTIME_DIRECTORY=/app/music-runtime
ENTRYPOINT ["java", "-jar", "app.jar"]
