FROM maven:3.9.11-eclipse-temurin-21

RUN apt-get update \
    && apt-get install -y --no-install-recommends python3 curl \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /app/logs /workspace/backend

WORKDIR /app
COPY ops/backend_watch.py /opt/dev/backend_watch.py

EXPOSE 8080
ENTRYPOINT ["python3", "/opt/dev/backend_watch.py"]
