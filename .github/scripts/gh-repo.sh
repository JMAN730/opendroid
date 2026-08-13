#!/usr/bin/env bash
#
# The only `gh` entry point the @claude workflow allowlists.
#
# Two invariants it enforces that the environment alone cannot:
#
#  1. Repository pinning. GH_REPO is only a default - every `gh issue`/`gh pr`
#     subcommand still accepts -R/--repo and would happily target
#     yashab-cyber/opendroid, which AGENTS.md forbids. This wrapper rejects any
#     caller-supplied repo selector and appends its own -R.
#  2. Authentication. CLAUDE_CODE_SUBPROCESS_ENV_SCRUB strips the action's
#     GH_TOKEN from Bash subprocesses, and the runner has no stored gh login, so
#     `gh` would fail with "gh auth login". The workflow stages the job's own
#     GITHUB_TOKEN in a 0600 file beforehand and this wrapper feeds it to gh for
#     the single command it runs - the credential never lives in the scrubbed
#     environment Gradle sees.
#
# The subcommand allowlist below is defence in depth: the workflow already
# allowlists each `<wrapper> <subcommand>` prefix individually, and neither list
# admits `gh api`, `gh pr merge`, `gh workflow run`, or `gh secret`.

set -euo pipefail

# Hardcoded on purpose. Deriving it from GITHUB_REPOSITORY would let a mention
# that can influence the environment retarget the CLI.
readonly REPO='JMAN730/opendroid'

# HOME rather than RUNNER_TEMP: Actions-provided variables are among those the
# subprocess scrub removes.
readonly TOKEN_FILE="${HOME:-/home/runner}/.claude-gh-token"

readonly ALLOWED_SUBCOMMANDS='
issue view
issue list
issue create
issue comment
issue edit
issue close
pr view
pr list
pr diff
pr comment
'

die() {
  printf 'gh-repo.sh: %s\n' "$1" >&2
  exit 1
}

[ "$#" -ge 2 ] || die 'usage: gh-repo.sh <command> <subcommand> [args...]'

subcommand="$1 $2"
case "$ALLOWED_SUBCOMMANDS" in
  *"
$subcommand
"*) ;;
  *) die "subcommand not allowed: $subcommand" ;;
esac

# Reject every spelling of the repo selector: --repo, --repo=x, -R, -Rx, and
# clustered short flags such as -qR. pflag does not accept abbreviated long
# flags, so --repo and --repo= are the only long forms to worry about.
for arg in "$@"; do
  case "$arg" in
    --repo | --repo=* | -[!-]*R* | -R*)
      die "the repository is fixed to $REPO; remove '$arg'"
      ;;
  esac
done

[ -r "$TOKEN_FILE" ] || die "no credential at $TOKEN_FILE"

GH_TOKEN="$(<"$TOKEN_FILE")"
export GH_TOKEN
export GH_HOST=github.com

# --repo goes right after the subcommand rather than at the end so a trailing
# '--' or positional argument cannot swallow it.
exec gh "$1" "$2" --repo "$REPO" "${@:3}"
