# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

This repo is **single-context**: one `CONTEXT.md` at the root, ADRs in `docs/adr/`.

## Before exploring, read these

- **`CONTEXT.md`** at the repo root.
- **`docs/adr/`** — read ADRs that touch the area you're about to work in.

If any of these files don't exist, **proceed silently**. Don't flag their absence; don't suggest creating them upfront. The `/domain-modeling` skill (reached via `/grill-with-docs` and `/improve-codebase-architecture`) creates them lazily when terms or decisions actually get resolved.

Neither exists yet as of this setup.

## File structure

```
/
├── CONTEXT.md
├── docs/adr/
│   ├── 0001-....md
│   └── 0002-....md
└── app/
```

If this ever becomes a multi-context repo, add `CONTEXT-MAP.md` at the root pointing at one `CONTEXT.md` per context, keep system-wide decisions in `docs/adr/`, and give each context its own `docs/adr/`.

## Not domain docs

`docs/` already holds product and engineering prose predating this setup — `architecture.md`, `prd.md`, `trd.md`, `security.md`, `error_handling.md`, `development_guide.md`, `USAGE.md`, `prompts.md`. Useful background, but they are **not** the glossary and **not** ADRs. Don't treat them as authoritative on domain vocabulary, and don't extend them in place of `CONTEXT.md`.

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `CONTEXT.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal — either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag ADR conflicts

If your output contradicts an existing ADR, surface it explicitly rather than silently overriding:

> _Contradicts ADR-0007 (event-sourced orders) — but worth reopening because…_
