FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn -q -DskipTests package

FROM apache/flink:1.18-java17
USER root
RUN mkdir -p /opt/job
COPY --from=build /app/target/flink-job.jar /opt/job/app.jar
USER flink
