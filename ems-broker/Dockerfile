# Build from the repository root:  docker build -f ems-broker/Dockerfile -t tb-ems-broker .
# (Jib is the primary image build in CI; this Dockerfile exists for docker-compose and local use.)

FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
COPY catalog.json ./
COPY legacy-oms-soap/pom.xml legacy-oms-soap/
COPY ems-broker/pom.xml ems-broker/
COPY erp-mq-consumer/pom.xml erp-mq-consumer/
RUN mvn -B -ntp -q -pl ems-broker -am dependency:go-offline || true
COPY ems-broker ems-broker
RUN mvn -B -ntp -DskipTests -pl ems-broker -am package

FROM eclipse-temurin:21-jre
RUN apt-get update \
 && apt-get install -y --no-install-recommends curl \
 && rm -rf /var/lib/apt/lists/* \
 && groupadd -r -g 1000 app \
 && useradd -r -u 1000 -g app -d /app -s /usr/sbin/nologin app
WORKDIR /app
COPY --from=build /workspace/ems-broker/target/ems-broker-*.jar /app/app.jar
RUN chown -R app:app /app
USER app
EXPOSE 8086 61616
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Djava.security.egd=file:/dev/./urandom"
HEALTHCHECK --interval=30s --timeout=5s --start-period=60s --retries=3 \
  CMD curl -fsS http://localhost:8086/actuator/health/readiness || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
