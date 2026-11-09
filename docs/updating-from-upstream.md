# Updating from upstream

You derived your project from Madrileno months ago. Upstream has moved: new conventions, fixed bugs, refactored infrastructure. Your project has moved too — renamed the package, customised modules, deleted what you didn't need. This doc is the loop for pulling upstream improvements into a project that has genuinely diverged.

The premise: after real divergence, a mechanical merge (cherry-pick, template-update tooling, three-way file merge) mostly produces conflict soup, because your files and upstream's files stopped being the same files. What works instead is **judgment-based porting** — read what upstream changed and why, decide per change whether it applies to you, and express it in your code. That's slow by hand and exactly the shape of work an AI assistant with the [MCP server](mcp.md) does well. This doc is written to be readable by both of you; it's served via `madrileno_doc("updating-from-upstream")` so the assistant can follow it directly.

## What the pin means

`.madrileno-ref`'s `ref=` line means: **"I have triaged upstream up to this commit."** Not "I have adopted everything up to it."

That distinction is what makes incremental updates work. When you review a batch of upstream changes and adopt some, skip others, bump `ref=` to the target sha anyway — skipping a change is a decision, and the pin records that the decision was made. If the pin only moved when you adopted everything, `madrileno_changes` would re-report your deliberate skips forever, and the noise would eventually drown the signal.

If you want a record of *why* something was skipped, put it in the commit message of the pin bump.

## The loop

Run the MCP server, then work through these steps. Steps 1–2 are cheap; do them across the whole repo. Steps 3–5 are per-area; do them only for areas that matter to you.

1. **Narrative first: `madrileno_changes()`.** Commit subjects between your pin and `origin/main`. This is the "what happened" pass — read it for intent, not content. Path-filter (`paths=["src/main/scala/madrileno/auth"]`) if you already know what you care about.
2. **Scope the surface: `madrileno_diff(format="stat")`.** Per-file change sizes. Cross-reference with step 1: a one-line stat under a commit titled "fix connection leak in scheduler" is a different priority than a 400-line refactor of a module you deleted.
3. **Pull content per area: `madrileno_diff(paths=[...], format="patch")`.** The actual patch, package-rewritten to your project's package so `-`/`+` lines compare directly against your files. Keep `paths` tight — an unfiltered patch across months of drift can be enormous.
4. **When the patch isn't enough, read whole files.** A patch shows deltas against upstream's baseline, which may no longer resemble yours. `madrileno_module(name, at="origin/main")` / `madrileno_source(path, at="origin/main")` give you upstream-latest in full; the same calls without `at` give you the baseline you derived from. Baseline vs upstream-latest vs your working tree is the three-way view that porting decisions actually need.
5. **Port with judgment.** For each change: does it apply, given your customisations? If yes, express it in your code — which may be a verbatim copy, or may be an adaptation. If no, note why. This is the step where the assistant reads *your* files alongside the MCP output.
6. **Bump the pin.** Set `ref=` in `.madrileno-ref` to the sha you triaged up to (the current `origin/main` tip from step 1), restart the MCP server (it reads the pin once at startup), and commit — collaborators inherit the new anchor.

## A worked prompt

Something like this, pasted into a fresh assistant session with the MCP server running:

> This project was derived from the madrileno template; `.madrileno-ref` pins where we're anchored. Use the `madrileno` MCP server to review what changed upstream since then — `madrileno_doc("updating-from-upstream")` describes the workflow. I'm mainly interested in the auth and database layers; we deleted the auction module, so skip anything auction-only. Propose which changes to port, apply the ones I approve, then bump `.madrileno-ref`.

## When not to port

- **You diverged on purpose.** If you replaced upstream's approach (different auth provider, different persistence idiom), upstream churn in that area is background noise. Skip it and let the pin bump record that.
- **The change is auction/example content.** The showcase modules exist to demonstrate patterns. If you deleted them, only port changes that alter the *pattern* they demonstrate, and only if you use that pattern.
- **You're weeks from a deadline.** The loop is resumable — the pin makes partial progress durable. Triage the cheap wins now, bump the pin, come back for the refactors.

## Database migrations

Upstream sometimes edits a migration file that has already shipped. Don't copy the edited file into your project: your databases have already applied the old version, so Flyway will either reject the edited file with a checksum mismatch or never run the change. Port the difference as a **new** migration (`V<next>__<description>.sql`) instead.

## Wrinkles

- The package rewrite in `patch` output is textual (`madrileno.` → your package), same as `madrileno_source` — occasionally it touches a string literal or URL in a diff hunk. Harmless for review; don't apply patches mechanically anyway.
- The shadow clone fetches on server startup. If upstream landed something mid-session, restart the server.
- `madrileno_changes` and `madrileno_diff` accept explicit `since`/`target` refs, so you can review any slice (e.g. between two upstream tags), not just pin→main.
