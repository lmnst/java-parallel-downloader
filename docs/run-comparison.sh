#!/usr/bin/env bash
# Re-derive the numbers in docs/COMPARISON.md.
#
# Spins up an httpd:2.4 container on localhost, generates 10/100/1024 MiB
# random corpora, then runs `hyperfine --warmup 1 --runs 5` against three
# downloaders: this project's CLI, curl, and wget. Zero added network
# latency: the bottleneck is disk/memory, which keeps the comparison
# focused on per-tool overhead rather than RTT amortisation.
#
# Requires: docker, hyperfine, curl, wget. Run from the project root.
set -euo pipefail

PORT=${PORT:-8087}
NAME=pdl-cmp-httpd
CORPUS=$(mktemp -d -t pdl-cmp-corpus.XXXXXX)
TMP=$(mktemp -d -t pdl-cmp-out.XXXXXX)

cleanup() {
    docker rm -f "$NAME" >/dev/null 2>&1 || true
    rm -rf "$CORPUS" "$TMP"
}
trap cleanup EXIT

echo "[run-comparison] building CLI distribution..."
./gradlew installDist --quiet
DL="$(pwd)/build/install/parallel-downloader/bin/parallel-downloader"

# The CLI distribution's launcher resolves `java` via JAVA_HOME or $PATH.
# If neither points at JDK 21+, the class-file-65 binary fails with
# UnsupportedClassVersionError. Pin JAVA_HOME to whatever JDK Gradle's
# toolchain just compiled with so the comparison runs in the same JVM
# regardless of what's on the operator's PATH.
TOOLCHAIN_JDK=$(./gradlew --quiet -q javaToolchains 2>/dev/null \
    | awk '/Eclipse Temurin JDK 21/,/Detected by/' \
    | grep -E '^ +\| Location' \
    | head -1 \
    | sed -E 's/^ +\| Location: +//')
if [[ -n "${TOOLCHAIN_JDK}" && -x "${TOOLCHAIN_JDK}/bin/java" ]]; then
    export JAVA_HOME="${TOOLCHAIN_JDK}"
    echo "[run-comparison] using JAVA_HOME=${JAVA_HOME}"
fi

echo "[run-comparison] generating 10, 100, 1024 MiB corpora..."
for size in 10 100 1024; do
    head -c $((size * 1024 * 1024)) /dev/urandom > "$CORPUS/file-${size}.bin"
done

echo "[run-comparison] starting httpd:2.4 on localhost:${PORT}..."
docker run --rm -d -p "$PORT:80" \
    -v "$CORPUS":/usr/local/apache2/htdocs/ \
    --name "$NAME" httpd:2.4 >/dev/null

for _ in {1..30}; do
    if curl -sf -o /dev/null -I "http://localhost:${PORT}/file-10.bin"; then break; fi
    sleep 0.2
done

for size in 10 100 1024; do
    URL="http://localhost:${PORT}/file-${size}.bin"
    OUT_DL="$TMP/dl-${size}.bin"
    OUT_CURL="$TMP/curl-${size}.bin"
    OUT_WGET="$TMP/wget-${size}.bin"
    echo
    echo "## ${size} MiB"
    echo
    hyperfine --warmup 1 --runs 5 \
        --command-name "parallel-downloader" \
            "${DL} --url ${URL} --out ${OUT_DL}" \
        --command-name "curl --parallel-max 8" \
            "curl -sSL -Z --parallel-max 8 -o ${OUT_CURL} ${URL}" \
        --command-name "wget" \
            "wget -q -O ${OUT_WGET} ${URL}" \
        --export-markdown "$TMP/${size}.md"
    echo
    cat "$TMP/${size}.md"
done
