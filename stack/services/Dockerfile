# One Dockerfile for all five services: the build stage compiles every module
# once (shared via BuildKit layer cache), the runtime stage picks one jar.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY . .
RUN mvn -B -q package -DskipTests

FROM eclipse-temurin:21-jre
ARG SERVICE
ARG OTEL_AGENT_VERSION=2.29.0
ADD https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v${OTEL_AGENT_VERSION}/opentelemetry-javaagent.jar /otel/opentelemetry-javaagent.jar
COPY --from=build /src/${SERVICE}/target/${SERVICE}-*.jar /app/app.jar
ENV JAVA_TOOL_OPTIONS="-javaagent:/otel/opentelemetry-javaagent.jar"
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
