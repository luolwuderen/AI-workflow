FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY server ./server
RUN mvn -pl server/platform-app -am -DskipTests package

FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 workflow
WORKDIR /app
COPY --from=build /workspace/server/platform-app/target/platform-app-1.0.0-SNAPSHOT.jar app.jar
USER 10001
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
