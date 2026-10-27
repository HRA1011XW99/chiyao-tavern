#!/usr/bin/env bash
# Build the SillyTavern package the app downloads: upstream source + production node_modules, smoke-tested.
#   bash tools/pack-st.sh <git ref, e.g. release> <build number> <output dir>
# Needs git, Node 24 (same major as the app's), curl and zip. Writes to <output dir>:
#   sillytavern-<version>-<build>.zip   the package (no .git, no node_modules/.bin, no symbolic links)
#   st.json                             the "st" section of latest.json, minus the download url
set -euo pipefail
REF="$1"
BUILD="$2"
OUT="$(mkdir -p "$3" && cd "$3" && pwd)"
REPO_URL="${ST_REPO:-https://github.com/SillyTavern/SillyTavern}"
MIN_SHELL="${MIN_SHELL:-1}"
WORK="$(mktemp -d)"
trap 'kill $(jobs -p) 2>/dev/null || true; rm -rf "$WORK"' EXIT

git clone -q --depth 1 --branch "$REF" "$REPO_URL" "$WORK/st"
cd "$WORK/st"
COMMIT="$(git rev-parse --short HEAD)"
VERSION="$(node -p "require('./package.json').version")"
echo "SillyTavern $VERSION ($COMMIT), build $BUILD"

npm ci --omit=dev --no-audit --no-fund --loglevel=error

# A native addon would not load on Android; the package must be pure JS
if find node_modules -name '*.node' -o -name binding.gyp | grep -q .; then
  echo "native addons found:"; find node_modules -name '*.node' -o -name binding.gyp; exit 1
fi

# Smoke test: the server must answer within 3 minutes
PORT=18000
node server.js --listen=false --port=$PORT --browserLaunchEnabled=false \
  --dataRoot="$WORK/data" --configPath="$WORK/config.yaml" > "$WORK/smoke.log" 2>&1 &
PID=$!
ok=0
for _ in $(seq 1 180); do
  if curl -fs -o /dev/null "http://127.0.0.1:$PORT/"; then ok=1; break; fi
  kill -0 $PID 2>/dev/null || break
  sleep 1
done
kill $PID 2>/dev/null || true
wait $PID 2>/dev/null || true
if [ $ok != 1 ]; then echo "smoke test failed"; tail -50 "$WORK/smoke.log"; exit 1; fi
echo "smoke test passed"

rm -rf .git node_modules/.bin
find node_modules -type d -name .bin -prune -exec rm -rf {} +
# leftovers of the smoke run, if any landed in the tree
rm -rf data backups config.yaml
if find . -type l | grep -q .; then echo "symbolic links left:"; find . -type l; exit 1; fi

NAME="sillytavern-$VERSION-$BUILD.zip"
rm -f "$OUT/$NAME"
zip -qr -X "$OUT/$NAME" .
SHA="$(sha256sum "$OUT/$NAME" | cut -d' ' -f1)"
SIZE="$(stat -c %s "$OUT/$NAME")"
cat > "$OUT/st.json" <<EOF
{"build":$BUILD,"version":"$VERSION","commit":"$COMMIT","file":"$NAME","sha256":"$SHA","size":$SIZE,"minShell":$MIN_SHELL}
EOF
cat "$OUT/st.json"
