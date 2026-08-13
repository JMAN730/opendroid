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
#  2. Authentication the caller cannot reach. CLAUDE_CODE_SUBPROCESS_ENV_SCRUB
#     strips the action's GH_TOKEN from Bash subprocesses and the runner has no
#     stored gh login, so gh would fail with the `gh auth login` error. Rather
#     than export a token - which put the credential in the environment gh's own
#     --jq expression could read back with jq's `env` builtin - gh runs as a
#     separate account whose config holds the credential, reached through sudo.
#     The caller is the runner user: it cannot read that config, cannot sudo
#     (nothing on the allowlist runs a shell), and the environment gh does get
#     is built from scratch by `env -i` below, so there is nothing in it to
#     print.
#  3. No file-reading flags. --body-file and friends would turn an allowed
#     comment into a way to publish any file the account running gh can read -
#     its own credential, or the one git stores in .git/config.
#
# The subcommand allowlist below is defence in depth: the workflow allowlists
# each `gh-repo <subcommand>` prefix individually, and neither list admits
# `gh api` (arbitrary writes), `gh pr merge`, `gh workflow run`, or `gh secret`.

set -euo pipefail

# Hardcoded on purpose. Deriving it from GITHUB_REPOSITORY would let a mention
# that can influence the environment retarget the CLI.
readonly REPO='JMAN730/opendroid'

# The account that holds the credential, and the environment gh is given. All
# fixed rather than read from the environment: the caller must not be able to
# redirect gh at a config directory of its own, and the subprocess scrub removes
# the Actions variables anyway. Every directory on that PATH is root-owned.
readonly GH_USER='ghrepo'
readonly GH_HOME='/var/lib/ghrepo'
readonly GH_CONFIG_DIR='/var/lib/ghrepo/.config/gh'
readonly GH_PATH='/usr/local/bin:/usr/bin:/bin'

# Absolute: the caller cannot put its own sudo earlier on PATH, but there is no
# reason to let PATH decide at all.
readonly SUDO='/usr/bin/sudo'

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
    --body-file | --body-file=* | --editor | --editor=* | --template | --template=* | --web | --web=*)
      die "flag not allowed: $arg"
      ;;
    # --jq evaluates its expression with jq's env/$ENV builtins in scope, which
    # read the environment of the gh process. gh no longer has a credential
    # there, so this is defence in depth rather than the fix: it holds even if
    # authentication ever moves back into the environment. --json on its own is
    # fine; parse the JSON rather than filtering it here. --template is blocked
    # above because on `issue create` it loads a template file; -T is blocked
    # below for the same reason. -t remains allowed because it is --title there.
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
        *[FTew]*) die "flag not allowed: $arg" ;;
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

# gh runs as the account that owns the credential, under an environment built
# from nothing by `env -i`: no token is passed, so there is nothing for a
# formatter to print, and no caller-supplied variable survives to redirect gh's
# config. sudo resolves `env` through the root-owned secure_path from
# /etc/sudoers, and `env` then resolves gh through GH_PATH, so neither binary is
# chosen by the caller.
#
# --repo goes right after the subcommand rather than at the end so a trailing
# '--' or positional argument cannot swallow it.
exec "$SUDO" -n -u "$GH_USER" -- env -i \
  "HOME=$GH_HOME" \
  "PATH=$GH_PATH" \
  "GH_CONFIG_DIR=$GH_CONFIG_DIR" \
  GH_NO_UPDATE_NOTIFIER=1 \
  GH_PROMPT_DISABLED=1 \
  gh "$1" "$2" --repo "$REPO" "${@:3}"
