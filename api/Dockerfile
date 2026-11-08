FROM maven:3.9.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B -ntp dependency:go-offline
COPY src ./src
RUN mvn -B -ntp -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd -r kinship && useradd -r -g kinship kinship
COPY --from=build /app/target/kinship-api-0.1.0-SNAPSHOT.jar /app/app.jar
USER kinship
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
