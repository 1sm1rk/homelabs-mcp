#!/usr/bin/env bash
#
# Builds the gateway jar inside the image build (see ../Dockerfile).
#
# The non-Central dependencies are the adapter artifacts
# `de.homelabs:hippocampus-mcp` and `de.homelabs:projects-mcp`; each one lives in
# its own project repository and is released to GitHub Packages. Both resolve the
# same way, decided per sibling by what the build context carries:
#
#   sibling mode  — the build context contains the adapter's project checkout
#                   (`homelabs-hippocampus/hippocampus-mcp` or
#                   `homelabs-projects/projects-mcp`), the normal case for
#                   local/OSS builds from a projects checkout: that adapter
#                   module is built and installed into the shared .m2 cache
#                   first, then the gateway builds against it. The copies are
#                   ephemeral, so their versions are set to the version the
#                   gateway pom PINS before installing (the release workflow's
#                   own trick) — a released pin then resolves the sibling-built
#                   artifact, a SNAPSHOT pin the sibling as-is. Token-free.
#   packages mode — that sibling checkout is absent: the adapter comes from
#                   GitHub Packages with a build secret
#                   (`--secret id=github_token,...`). This is the CI path.
#
# The two modes are decided INDEPENDENTLY per adapter: a mixed context builds the
# siblings it carries from source and resolves only the missing ones from GitHub
# Packages. As soon as one adapter falls back to the packages mode the build needs
# the token; a context with both siblings needs no settings at all.
#
# Either way the gateway build is the last step and its failure is reported
# with the cause that matters (unresolvable adapter version).
set -euo pipefail

CONTEXT_DIR=${CONTEXT_DIR:-/context}
BUILD_DIR=${BUILD_DIR:-/build}
GATEWAY_DIR="$BUILD_DIR/gateway"
ADAPTER_DIR="$BUILD_DIR/adapter"
SECRET_FILE=${SECRET_FILE:-/run/secrets/github_token}

HIPPOCAMPUS_ADAPTER="$CONTEXT_DIR/homelabs-hippocampus/hippocampus-mcp"
PROJECTS_ADAPTER="$CONTEXT_DIR/homelabs-projects/projects-mcp"

mkdir -p "$GATEWAY_DIR"
# Copy (not build in place): the context bind mount is read-only and Maven needs
# to write target/ directories.
cp -a "$CONTEXT_DIR/homelabs-mcp/pom.xml" "$CONTEXT_DIR/homelabs-mcp/src" "$GATEWAY_DIR/"

mvn_settings=()
missing_adapters=()

# Reads the version the gateway pom pins for an adapter, from the property name.
pinned_version() {
    mvn -B -ntp -q -f "$GATEWAY_DIR/pom.xml" help:evaluate \
        -Dexpression="$1.version" -DforceStdout
}

# Copies a sibling repo (ephemeral), sets its versions to the gateway pin, and
# installs the adapter module + its parent into the shared .m2 cache.
build_sibling() {
    # $1 = sibling repo dir in the context, $2 = -pl module path, $3 = pin property
    local src="$1" module="$2" pin="$3" version
    rm -rf "$ADAPTER_DIR"
    cp -a "$src" "$ADAPTER_DIR"
    version="$(pinned_version "$pin")"
    echo "   pinned version: $version"
    ( cd "$ADAPTER_DIR" \
      && mvn -B -ntp versions:set -DnewVersion="$version" -DgenerateBackupPoms=false \
      && mvn -B -ntp -pl "$module" -am install -DskipTests )
}

if [ -d "$HIPPOCAMPUS_ADAPTER" ]; then
    echo "== hippocampus-mcp: sibling checkout found in the build context"
    build_sibling "$CONTEXT_DIR/homelabs-hippocampus" hippocampus-mcp hippocampus-mcp
else
    echo "== hippocampus-mcp: released artifact from GitHub Packages"
    missing_adapters+=("de.homelabs:hippocampus-mcp")
fi

if [ -d "$PROJECTS_ADAPTER" ]; then
    echo "== projects-mcp: sibling checkout found in the build context"
    build_sibling "$CONTEXT_DIR/homelabs-projects" projects-mcp projects-mcp
else
    echo "== projects-mcp: released artifact from GitHub Packages"
    missing_adapters+=("de.homelabs:projects-mcp")
fi

if [ ${#missing_adapters[@]} -gt 0 ]; then
    if [ ! -f "$SECRET_FILE" ]; then
        cat >&2 <<'EOF'
ERROR: the adapter artifacts cannot be resolved.
  The build context has no sibling checkout for
    de.homelabs:hippocampus-mcp  (expected at homelabs-hippocampus/hippocampus-mcp)
    de.homelabs:projects-mcp     (expected at homelabs-projects/projects-mcp)
  and no `github_token` build secret was supplied.

  Local/OSS build  -> run the build from a `projects/` checkout that carries
                      both project repositories next to homelabs-mcp/.
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

ERROR: the gateway build failed. The usual cause is an adapter artifact:
  `de.homelabs:hippocampus-mcp` or `de.homelabs:projects-mcp` (versions pinned in
  the gateway pom.xml) is not available locally and not published to GitHub
  Packages — or the supplied token lacks `read:packages`. Bump the version when
  the adapter is released.
EOF
    exit 1
}
