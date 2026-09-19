FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --system --uid 10001 --create-home --home-dir /home/app app

WORKDIR /app
COPY --chown=app:app wuzh-ai-gateway.jar /app/app.jar

USER app
EXPOSE 18080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
