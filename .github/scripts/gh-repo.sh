#!/usr/bin/env bash
#
# The only command the @claude workflow allowlists beyond the action's own
# tools. The workflow installs this file to /usr/local/bin/gh-repo owned by
# root, so the copy that actually runs is not the workspace copy: Claude's file
# tools run as the runner user and cannot rewrite it, and neither can anything
# else that a pull request contributes.
#
# What it enforces that the environment cannot:
#
#  1. Repository pinning. GH_REPO is only a default - every `gh issue`/`gh pr`
#     subcommand still accepts -R/--repo, and several accept a positional URL
#     that outranks --repo. AGENTS.md scopes the tracker to this fork and
#     forbids touching the yashab-cyber/opendroid upstream, so both routes are
#     rejected here and the repository is appended by the wrapper itself.
#  2. Authentication. CLAUDE_CODE_SUBPROCESS_ENV_SCRUB strips the action's
#     GH_TOKEN from Bash subprocesses and the runner has no stored gh login, so
#     gh would fail with the `gh auth login` error. The credential lives in a
#     root-owned file that the runner user cannot read; this wrapper fetches it
#     with sudo for the single command it runs. Nothing on the allowlist can
#     invoke sudo, or read the file, on its own.
#  3. No file-reading flags. --body-file and friends would turn an allowed
#     comment into a way to publish any file the runner can read - the
#     credential git stores in .git/config, for instance.
#
# The subcommand allowlist below is defence in depth: the workflow allowlists
# each `gh-repo <subcommand>` prefix individually, and neither list admits
# `gh api` (arbitrary writes), `gh pr merge`, `gh workflow run`, or `gh secret`.

set -euo pipefail

# Hardcoded on purpose. Deriving it from GITHUB_REPOSITORY would let a mention
# that can influence the environment retarget the CLI.
readonly REPO='JMAN730/opendroid'

# Root-owned, mode 600. The path is fixed rather than read from the environment
# because the subprocess scrub removes the Actions variables that would name it.
readonly TOKEN_FILE='/etc/gh-repo.token'

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
  printf 'gh-repo: %s\n' "$1" >&2
  exit 1
}

[ "$#" -ge 2 ] || die 'usage: gh-repo <command> <subcommand> [args...]'

subcommand="$1 $2"
case "$ALLOWED_SUBCOMMANDS" in
  *"
$subcommand
"*) ;;
  *) die "subcommand not allowed: $subcommand" ;;
esac

for arg in "$@"; do
  # Long flags. pflag does not accept abbreviated long flags, so each spelling
  # is exactly two cases: bare and --flag=value.
  case "$arg" in
    --repo | --repo=*)
      die "the repository is fixed to $REPO; remove '$arg'"
      ;;
    --body-file | --body-file=* | --editor | --editor=* | --web | --web=*)
      die "flag not allowed: $arg"
      ;;
    # --jq evaluates its expression with jq's env/$ENV builtins in scope, and gh
    # inherits GH_TOKEN from the export below, so `--json number --jq
    # env.GH_TOKEN` prints the credential straight back to the caller - defeating
    # the root-owned file, which only stops the token being *read from disk*.
    # --json on its own is fine; parse the JSON rather than filtering it here.
    # --template is left alone: Go templates cannot reach the environment, and -t
    # is --title on `issue create`.
    --jq | --jq=*)
      die "flag not allowed: $arg (use --json and parse the output)"
      ;;
  esac

  # Short flags, including clusters (-qR) and attached values (-Rowner/name).
  # Only the leading run of letters is inspected, so a value that happens to
  # contain R, F, e, w or q is not mistaken for a flag.
  case "$arg" in
    -[!-]*)
      letters="${arg#-}"
      letters="${letters%%[!A-Za-z]*}"
      case "$letters" in
        *R*) die "the repository is fixed to $REPO; remove '$arg'" ;;
        *q*) die "flag not allowed: $arg (-q is --jq; use --json and parse the output)" ;;
        *[Few]*) die "flag not allowed: $arg" ;;
      esac
      ;;
  esac

  # Rejecting the repo flags is not enough. gh documents `issue view {<number> |
  # <url>}` (and the same for comment/edit/close and the pr commands), and a
  # positional URL wins over --repo - so a bare
  # `issue view https://github.com/yashab-cyber/opendroid/issues/1` would read
  # the upstream despite the --repo appended below. Any argument naming a
  # repository must therefore name this one. Checking every argument rather than
  # just the positional target also stops a URL smuggled through --body, at the
  # cost of refusing to quote an upstream link in a comment - which AGENTS.md
  # rules out anyway.
  #
  # Matched against a lowercased copy because hostnames are case-insensitive
  # (https://GITHUB.COM/... reaches the same host) and so are GitHub owner and
  # repository names.
  #
  # One argument can carry several URLs: gh's relationship flags take
  # comma-separated lists (--blocked-by 200,201 - and each element may be a URL),
  # and a body can quote more than one link. Stopping at the first github.com
  # would let a later one name the upstream, so every occurrence is walked.
  lower="${arg,,}"
  rest="$lower"
  while [ "$rest" != "${rest#*github.com}" ]; do
    rest="${rest#*github.com}"
    # Only a / or : after the host starts a repository path; github.community
    # and friends are not repository references.
    case "$rest" in
      [/:]*) ;;
      *) continue ;;
    esac
    target="${rest#[/:]}"
    owner="${target%%/*}"
    owner="${owner%%[?#,]*}"
    remainder="${target#*/}"
    name="${remainder%%/*}"
    name="${name%%[?#,]*}"
    name="${name%.git}"
    [ "$owner/$name" = "${REPO,,}" ] ||
      die "the repository is fixed to $REPO; '$arg' names $owner/$name"
  done
done

GH_TOKEN="$(sudo -n /bin/cat "$TOKEN_FILE" 2>/dev/null)" ||
  die "could not read the credential at $TOKEN_FILE"
[ -n "$GH_TOKEN" ] || die "the credential at $TOKEN_FILE is empty"
export GH_TOKEN
export GH_HOST=github.com

# --repo goes right after the subcommand rather than at the end so a trailing
# '--' or positional argument cannot swallow it.
exec gh "$1" "$2" --repo "$REPO" "${@:3}"
