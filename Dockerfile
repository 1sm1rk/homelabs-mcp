# syntax=docker/dockerfile:1

# Multi-stage build for the homelabs MCP gateway.
#
# BUILD CONTEXT = the parent `projects/` directory, dockerfile path
# `homelabs-mcp/Dockerfile`:
#   docker build -f homelabs-mcp/Dockerfile -t homelabs-mcp <projects-dir>
#
# The one non-Central dependency is the adapter artifact
# `de.homelabs:hippocampus-mcp`. Two modes, both handled by
# `docker/build-gateway.sh` (read its header for the details):
#
#   sibling mode  — the context contains `homelabs-hippocampus/`: the adapter
#                   module is built and installed into the .m2 cache first.
#                   Local developer / OSS builds look like this.
#   packages mode — no sibling checkout: the adapter is pulled from GitHub
#                   Packages using a build secret:
#                     docker build --secret id=github_token,src=<token-file> \
#                       -f homelabs-mcp/Dockerfile -t homelabs-mcp <projects-dir>
#                   This is what CI does. The token needs `read:packages`.
#
# Tests run on the host/CI before the image is built (they need no Docker daemon
# here, but publishing a red build must stay impossible). The image ships ONLY
# the packaged fat jar: no model artifacts, no source, no build caches.

# ---- Stage 1: build the jar (JDK 25, same Maven line as the host) ----
# Requires BuildKit/buildx (Docker's default builder): the .m2 cache mount and
# the build secret both need it.
FROM maven:3.10.0-eclipse-temurin-25-noble AS build
WORKDIR /build

COPY homelabs-mcp/docker ./docker
RUN --mount=type=bind,source=.,target=/context,ro \
    --mount=type=cache,target=/root/.m2 \
    --mount=type=secret,id=github_token,required=false \
    bash /build/docker/build-gateway.sh

# ---- Stage 2: runtime (JRE 25, glibc-based) ----
# The gateway has no native code, so no musl/glibc constraint applies today; the
# noble base keeps the runtime family uniform with the other homelab services.
FROM eclipse-temurin:25-jre-noble

# Non-root service user: the noble base image already ships an `ubuntu` user
# (uid/gid 1000) — reuse it instead of creating a duplicate-uid user.
WORKDIR /opt/homelabs-mcp

COPY --from=build --chown=ubuntu:ubuntu /build/gateway/target/homelabs-mcp-*.jar app.jar

USER ubuntu
EXPOSE 8090

# Healthcheck = the public Protected Resource Metadata document (the gateway's
# liveness signal: it only answers once the web layer and the security chain are
# up). Two deployment gotchas are baked in: the runtime image's /bin/sh is dash —
# so the probe runs under an explicit bash — and bash's /dev/tcp takes host and
# port with a SLASH (`host:port` makes bash treat the pair as a hostname, which
# fails with a confusing ENOENT).
HEALTHCHECK --interval=15s --timeout=5s --start-period=40s --retries=5 \
    CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/${SERVER_PORT:-8090} || exit 1; printf 'GET /.well-known/oauth-protected-resource HTTP/1.0\\r\\n\\r\\n' >&3; head -n 1 <&3 | grep -q ' 200 '"]

ENTRYPOINT ["java", "-jar", "/opt/homelabs-mcp/app.jar"]
