FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /workspace
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp clean package -DskipTests=false

FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=build /workspace/target/blog-ai-0.0.1-SNAPSHOT.jar /app/blog-ai.jar
EXPOSE 8090
ENTRYPOINT ["java", "-jar", "/app/blog-ai.jar"]
