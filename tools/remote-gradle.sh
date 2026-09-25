#!/usr/bin/env bash
# remote-gradle.sh — run Gradle on a build host and bring the outputs back here.
#
# For a dev machine whose memory belongs to something else (a local model server, say): a Gradle
# build is two JVMs and ~4-5 GB at peak. The build runs on the host named in
# ~/.config/charon/build-host (user@host), which needs an Android SDK env script at
# $CHARON_ANDROID_ENV (default ~/android-buildenv/env.sh):
#
#   1. rsync the working tree (uncommitted changes included) to <host>:~/workspace/<checkout dir
#      name> — so parallel checkouts (git worktrees) never share a remote tree — and the
#      charon-obol checkout to <host>:~/workspace/charon-obol, the sibling the obol flavor reads
#   2. ssh <host> ./gradlew <args>                       (streams the same output)
#   3. rsync back the APK outputs and test reports
#   4. exit with Gradle's own code
#
#   tools/remote-gradle.sh :terminal-core:test :app:testFossDebugUnitTest
#   tools/remote-gradle.sh :app:assembleObolRelease && tools/sign-release.sh obol
#
# The release key never leaves this machine: local.properties is not synced, so a release APK
# comes back signed with the host's debug key and tools/sign-release.sh re-signs it here.
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
REMOTE="workspace/${CHARON_REMOTE_DIR:-$(basename "$REPO_ROOT")}"
OBOL_ROOT="${CHARON_OBOL_ROOT:-$HOME/workspace/charon-obol}"
HOST_FILE="${CHARON_BUILD_HOST_FILE:-$HOME/.config/charon/build-host}"
ENV_SH="${CHARON_ANDROID_ENV:-~/android-buildenv/env.sh}"

HOST="$(tr -d '[:space:]' < "$HOST_FILE" 2>/dev/null)"
if [ -z "$HOST" ]; then echo "no build host named in $HOST_FILE" >&2; exit 2; fi
[ $# -gt 0 ] || { echo "usage: $0 <gradle tasks/args…>" >&2; exit 2; }

SSH=(ssh -o BatchMode=yes -o ConnectTimeout=8 "$HOST")
RSYNC_EX=(--exclude=build/ --exclude=.gradle/ --exclude=.kotlin/ --exclude=dist/
          --exclude='*.apk' --exclude=local.properties --exclude=.claude/)

echo "── sync → $HOST:~/$REMOTE"
"${SSH[@]}" "mkdir -p ~/$REMOTE" || { echo "build host $HOST unreachable" >&2; exit 2; }
rsync -a --delete "${RSYNC_EX[@]}" "$REPO_ROOT/" "$HOST:$REMOTE/" || exit 2
if [ -d "$OBOL_ROOT" ]; then
  rsync -a --delete "${RSYNC_EX[@]}" "$OBOL_ROOT/" "$HOST:workspace/charon-obol/" || exit 2
fi

"${SSH[@]}" "source $ENV_SH && cd ~/$REMOTE && ./gradlew --console=plain $(printf '%q ' "$@")"
RC=$?

echo "── fetch results ← $HOST"
rsync -a --include='*/' --include='*.apk' --include='*.xml' --include='*.html' --include='*.css' \
  --include='*.js' --exclude='*' --prune-empty-dirs \
  "$HOST:$REMOTE/app/build/outputs/" "$REPO_ROOT/app/build/outputs/" 2>/dev/null
for m in app terminal-core; do
  mkdir -p "$REPO_ROOT/$m/build"
  rsync -a --delete "$HOST:$REMOTE/$m/build/test-results/" "$REPO_ROOT/$m/build/test-results/" 2>/dev/null
done
exit $RC
