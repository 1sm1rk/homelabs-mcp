#!/usr/bin/env bash
#
# Builds the gateway jar inside the image build (see ../Dockerfile).
#
# The only non-Central dependency is the adapter artifact
# `de.homelabs:hippocampus-mcp`, which lives in the hippocampus repo and is
# released to GitHub Packages. Two resolution modes exist, chosen by what the
# build context carries:
#
#   sibling mode  — the build context contains `homelabs-hippocampus/` (the
#                   normal case for local/OSS builds from a projects checkout):
#                   the adapter module is built and installed into the shared
#                   .m2 cache first, then the gateway builds against it.
#   packages mode — no sibling checkout: the adapter comes from GitHub Packages
#                   with a build secret (`--secret id=github_token,...`).
#                   This is the CI path.
#
# Either way the gateway build is the last step and its failure is reported
# with the cause that matters (unresolvable adapter version).
set -euo pipefail

CONTEXT_DIR=${CONTEXT_DIR:-/context}
BUILD_DIR=${BUILD_DIR:-/build}
GATEWAY_DIR="$BUILD_DIR/gateway"
ADAPTER_DIR="$BUILD_DIR/adapter"
SECRET_FILE=${SECRET_FILE:-/run/secrets/github_token}

mkdir -p "$GATEWAY_DIR"
# Copy (not build in place): the context bind mount is read-only and Maven needs
# to write target/ directories.
cp -a "$CONTEXT_DIR/homelabs-mcp/pom.xml" "$CONTEXT_DIR/homelabs-mcp/src" "$GATEWAY_DIR/"

mvn_settings=()

if [ -d "$CONTEXT_DIR/homelabs-hippocampus/hippocampus-mcp" ]; then
    echo "== adapter mode: sibling checkout found in the build context"
    rm -rf "$ADAPTER_DIR"
    cp -a "$CONTEXT_DIR/homelabs-hippocampus" "$ADAPTER_DIR"
    ( cd "$ADAPTER_DIR" && mvn -B -ntp -pl hippocampus-mcp -am install -DskipTests )
else
    echo "== adapter mode: published artifact from GitHub Packages"
    if [ ! -f "$SECRET_FILE" ]; then
        cat >&2 <<'EOF'
ERROR: the adapter cannot be resolved.
  The build context has no sibling homelabs-hippocampus checkout (packages mode)
  and no `github_token` build secret was supplied.

  Local/OSS build  -> run the build from a `projects/` checkout that contains
                      both homelabs-mcp/ and homelabs-hippocampus/.
  Packages build   -> pass a GitHub token with `read:packages`:
                      docker build --secret id=github_token,src=token.txt ...
EOF
        exit 1
    fi
    export GITHUB_TOKEN="$(cat "$SECRET_FILE")"
    # GitHub Packages accepts any user name with a token; the CI actor is the
    # natural choice, the repo owner is the local fallback.
    export GITHUB_ACTOR="${GITHUB_ACTOR:-1sm1rk}"
    mvn_settings=(-s "$BUILD_DIR/docker/settings-packages.xml")
fi

cd "$GATEWAY_DIR"
mvn -B -ntp "${mvn_settings[@]}" package -DskipTests || {
    cat >&2 <<'EOF'

ERROR: the gateway build failed. The usual cause is the adapter artifact:
  `de.homelabs:hippocampus-mcp` (version pinned in the gateway pom.xml) is not
  available locally and not published to GitHub Packages — or the supplied
  token lacks `read:packages`. Bump the version when the adapter is released.
EOF
    exit 1
}
