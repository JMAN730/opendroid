#!/usr/bin/env bash

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
readonly SCRIPT_DIR
readonly WRAPPER="$SCRIPT_DIR/gh-repo.sh"

expect_rejected() {
  if bash "$WRAPPER" "$@" >/dev/null 2>&1; then
    printf 'unexpectedly allowed: %s\n' "$*" >&2
    return 1
  fi
}

expect_rejected issue list --jq env.GH_TOKEN
expect_rejected issue list -q env.GH_TOKEN
expect_rejected issue create --template ../../.git/config
expect_rejected issue create -T../../.git/config
expect_rejected issue view --repo yashab-cyber/opendroid 1
expect_rejected issue view https://github.com/yashab-cyber/opendroid/issues/1
expect_rejected issue edit \
  https://github.com/JMAN730/opendroid/issues/1,https://github.com/yashab-cyber/opendroid/issues/2 \
  --add-label bug
expect_rejected api repos/JMAN730/opendroid/issues

# Substitute echo for sudo so the final command can be asserted without a
# privileged test account or a live credential. The wrapper still constructs
# the exact clean environment and gh invocation used on the runner.
actual="$({
  sed "s|readonly SUDO='/usr/bin/sudo'|readonly SUDO='/bin/echo'|" "$WRAPPER"
} | bash -s -- issue list --state open)"
expected='-u ghrepo -- env -i HOME=/var/lib/ghrepo PATH=/usr/local/bin:/usr/bin:/bin GH_CONFIG_DIR=/var/lib/ghrepo/.config/gh GH_NO_UPDATE_NOTIFIER=1 GH_PROMPT_DISABLED=1 gh issue list --repo JMAN730/opendroid --state open'

if [ "$actual" != "$expected" ]; then
  printf 'unexpected allowed invocation:\n%s\n' "$actual" >&2
  exit 1
fi

printf 'gh-repo behavior checks passed\n'
