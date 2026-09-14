# Container mechanism for llm-wiki-km (#418 packaging decision).
#
# Pinned Java 21 runtime, non-root execution, and mounted persistent state.
# The container is a deployment mechanism only: it never changes the
# domain/storage authority, and the application inside still binds the
# loopback backend. Remote access uses the host network namespace plus a
# host-local forwarder (see ../forwarder/); do NOT publish the backend port
# on a wildcard host interface, and do NOT run a second replica against the
# same writable volumes (single-instance / single-writer).
FROM eclipse-temurin:21-jre-jammy

# sqlite-vec native library (pinned v0.1.9, Linux) is mounted at runtime via
# VECTOR_EXTENSION_PATH, e.g. -v /opt/llm-wiki-km/native:/opt/native:ro with
# VECTOR_EXTENSION_PATH=/opt/native/vec0.so. The image never bakes in
# provider keys, owner secrets, or workspace content.
RUN groupadd --gid 10001 llmwiki \
  && useradd --uid 10001 --gid llmwiki --create-home --shell /usr/sbin/nologin llmwiki

WORKDIR /app
COPY target/llm-wiki-km-0.1.0.jar /app/llm-wiki-km.jar
RUN chown -R llmwiki:llmwiki /app

# Canonical state lives in mounted volumes, never in the writable layer:
# vault (irreplaceable), archive, data (knowledge.db + graph projection),
# config (required config, no secrets).
VOLUME ["/app/vault", "/app/archive", "/app/data", "/app/config"]

USER llmwiki
EXPOSE 8765

ENV DEPLOYMENT_MODE=LOCAL_ONLY \
    DEPLOYMENT_FORWARDER_TARGET=127.0.0.1:8765 \
    DEPLOYMENT_MAX_INSTANCES=1

ENTRYPOINT ["java", "-jar", "/app/llm-wiki-km.jar"]
