# Specs

This project is **spec-driven** for anything non-trivial: we turn a *need* into a reviewed *design*
before writing code. It keeps intent explicit, makes review happen at the cheapest point (before
implementation), and gives whoever implements it — human or AI agent — a precise contract to build
against.

## The flow

```
① Issue        the need / problem (the "why") + rough acceptance          → GitHub Issues
② Brainstorm   options & trade-offs                                        → the issue thread / Discussions
③ Spec         the design (the "what & how"), reviewed before code         → docs/specs/NNNN-title.md (via PR)
④ Implement    code + tests that satisfy the spec's acceptance criteria    → a PR that links the spec + issue
```

## When to write a spec

- **Do** write one for: data-model or API changes, anything with security/privacy semantics,
  or anything with more than one reasonable approach. (Most `roadmap`-labelled issues qualify.)
- **Skip** it for: docs, copy, bug fixes, small additive changes. Don't let process outweigh output.

## Lifecycle

A spec has a **Status** in its header, updated over its life:

`Draft` → `Accepted` → `Implemented` → (`Superseded by NNNN` / `Withdrawn`)

- **Draft** — under discussion; design/open-questions not settled.
- **Accepted** — design agreed; ready to implement.
- **Implemented** — shipped; the spec now documents *what is*.

## How to propose one

1. Find or open the **issue** describing the need.
2. Copy [`TEMPLATE.md`](TEMPLATE.md) to `docs/specs/NNNN-short-title.md` (next zero-padded number).
3. Fill it in, `Status: Draft`, link the issue.
4. Open a **PR** — that's where the design is reviewed. Iterate until `Accepted`, then merge.
5. Implement in a follow-up PR that references the spec; flip the spec to `Implemented`.

## Index

| # | Title | Status | Issue |
|---|-------|--------|-------|
| [0001](0001-tamper-evident-storage.md) | Tamper-evident audit storage | Accepted | [#1](https://github.com/leofang2007-maker/prompt-audit/issues/1) |
