FROM maven:3.9.11-eclipse-temurin-21 AS build

WORKDIR /workspace/backend
COPY backend/ ./
# Retain downloaded dependencies when a source edit invalidates the build layer.
RUN --mount=type=cache,id=em-plus-local-maven-repository,target=/root/.m2,sharing=locked \
    mvn -B -ntp -pl ruoyi-admin -am -Pdev,gen -DskipTests verify

FROM eclipse-temurin:21.0.9_10-jre-noble

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --home /app --shell /usr/sbin/nologin app \
    && mkdir -p /app/config /app/logs \
    && chown -R app:app /app

WORKDIR /app
COPY --from=build --chown=app:app /workspace/backend/ruoyi-admin/target/ruoyi-admin.jar /app/app.jar
COPY --chown=app:app ops/application-local.properties /app/config/application-local.properties

USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar", "--spring.config.additional-location=file:/app/config/application-local.properties"]
