# Frontend

Madrileno is a backend template, but the routes it exposes only earn their keep once something consumes them. The **reference frontend** — a separate repo, [`madrileno-dev/madrileno-frontend`](https://github.com/madrileno-dev/madrileno-frontend) — is that something: a React app built entirely against this backend's **generated contract**, so the Scala router specs stay the single source of truth and any drift is a compile error on the client.

It is deliberately a **sibling repo, not a subdirectory**, and the backend stays completely unaware of it — no file here depends on, imports, or names the frontend. The only coupling is the contract the backend already generates for its OpenAPI docs.

## The contract loop

Baklava turns the router specs — the same test-driven specs that produce the OpenAPI surface (see [`http.md`](http.md)) — into a typed TypeScript client package on `sbt testFull`. The frontend vendors that output and builds its API layer on top:

```
router specs ──sbt testFull──▶ target/baklava/orpc/src/*.ts  (oRPC contract + zod schemas + client)
                                    │  the frontend's `sync-contracts` copies them in
                                    ▼
                    madrileno-frontend/src/contracts/        (committed, so the app builds standalone)
                                    │  typed client + TanStack Query hooks
                                    ▼
                              tsc  ◀── a renamed backend DTO field is a compile error at the call site
```

Rename a field in a router spec, run `sbt testFull`, resync on the frontend, and its typecheck fails at the exact call site that read the old shape. That drift-as-compile-error is the whole point of the pairing — the wire boundary is checked by two compilers, not by hope.

The emitted package name and format are configured by the baklava generate config in [`build.sbt`](../build.sbt) (the `orpc-package-contract-json` block, published as `@madrileno-dev/<project>-orpc-contracts`). Nothing else here knows the frontend exists.

## What the frontend is

At a glance — details live in the frontend repo's own README:

- **React + Vite + TypeScript (strict)**, SPA by default with **SSR as a working opt-in** for the public browse pages (the SEO / first-paint set that needs no token at render time).
- **oRPC client** over the generated contract, with **typed errors end to end** — the backend's RFC 9457 problem envelope (see [`error-handling.md`](error-handling.md)) is decoded into discriminated errors the UI dispatches on by code, never by matching display text.
- **TanStack Query** for server state; **react-hook-form + zod** for forms; **Temporal** for time at the wire boundary (JS `Date` is confined to a single mapper module).
- Auth against this backend's dev login + JWT/refresh flow (see [`auth.md`](auth.md)), with a single-flight 401-refresh at the fetch layer.

## Design system

The UI layer is **[shadcn/ui](https://ui.shadcn.com) on Tailwind v4** — where "shadcn" means the *approach*, not a dependency: the CLI copies component source into `src/components/ui/`, and the frontend **owns and vendors** those files. There is no design-system package to upgrade around; you edit the components in place. That mirrors the backend's own bias — own your code, no framework you can't see into.

The specific choices, and why:

| Choice | Why |
| ------ | --- |
| **shadcn `base-nova` style, built on Base UI** (not the Radix default) | Base UI is the headless primitives library the newer shadcn styles sit on — accessible focus/keyboard/ARIA behaviour for free, with full control of the look. |
| **Kept shadcn's default file locations & aliases** (`components.json`) | The vendored components stay diffable against the upstream registry, so re-running the CLI to pull a fix is a clean diff rather than a merge conflict. The template has no component conventions of its own to impose yet, so it invents none. |
| **Neutral base + a single brand accent** (`--primary` / `--ring` = `oklch(0.4 0.11 12)`) | A CSS-variable token palette with light / dark / system theming; one accent color is the deliberate touch of identity over an otherwise neutral base — enough to not look like every other starter, and cheap to retheme by editing the tokens in `src/styles/tailwind.css`. |
| **lucide icons + sonner toasts** | shadcn's own defaults. sonner surfaces typed rejection feedback — a specific problem code from the RFC 9457 envelope becomes a specific toast, not a generic error. |

Theming is a three-way light / dark / system toggle, SSR-safe: a pre-paint inline script sets the class before first paint, so there's no flash. Retheming is a token edit, not a component change.

## Why a separate repo (and not a subdirectory or a fullstack framework)

| Option | Verdict |
| ------ | ------- |
| **Separate sibling repo** (chosen) | The backend template stays a backend template — no Node toolchain, no frontend build in its CI, no coupling past the generated contract. Either repo can be adopted, replaced, or deleted without touching the other. |
| **Frontend subdirectory in this repo** | Skipped. Drags a Node/pnpm toolchain and a second CI surface into a Scala template, muddies [`init-project`](scripts.md), and forces one release cadence onto two very different stacks. |
| **Fullstack framework** (Next-style, shared types) | Skipped. Couples the two stacks at the hip and throws away the "backend is the source of truth, everything else consumes its documented contract" property the whole template is built around. |

## Using it

Clone it next to this repo, point its `sync-contracts` at `../madrileno/target/baklava/orpc/src`, and follow its README. The backend needs no changes:

- **In dev**, the frontend's Vite proxy makes the API same-origin, so there is no CORS to configure and nothing to change here.
- **In production**, if the frontend is served from a different origin, set this backend's `CORS_ALLOWED_ORIGINS` (see [`configuration.md`](configuration.md)) — its existing env contract, not a new frontend coupling.

## Starting a real project

The frontend ships the same "delete the demo" escape hatch as the backend's [`init-project`](scripts.md):

```bash
pnpm run init-project my-project
```

It deletes the bundled demo feature (`src/features/auctions/` and every `frontend:auction-block-*` marker block, mirroring the backend's `scripts:auction-block-*` markers), renames the package to `my-project-frontend`, and leaves a runnable shell — login, the typed client, routing, tests, and the SSR opt-in all intact. Run it alongside the backend's own `init-project.scala`, then regenerate and resync the contract (`sbt testFull` → `pnpm run sync-contracts`) so the fresh project starts from your own routes.
