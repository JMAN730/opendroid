# AGENTS.md

Guidance for Claude Code and other agents working in this repository.

## Attribution

Never include Claude session links (`claude.ai/code/session_...`) in commit messages,
PR titles or descriptions, PR or issue comments, or committed files. The
`Co-Authored-By` trailer and a plain "Generated with Claude Code" line are fine.

## Agent skills

### Issue tracker

GitHub Issues on this fork, `JMAN730/opendroid`, via the `gh` CLI. Never file or read
issues on the `yashab-cyber/opendroid` upstream. External PRs are not a triage surface.
See `docs/agents/issue-tracker.md`.

### Triage labels

Canonical defaults — `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`,
`wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` at the root, ADRs in `docs/adr/`. See `docs/agents/domain.md`.
