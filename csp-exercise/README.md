# Content Security Policy — Frontend Infrastructure Exercise

**Role:** Mid-level Software Engineer, Frontend Infrastructure
**Format:** ~5h take-home against this scaffold, followed by a 75-minute live defense

## Context

You are joining the Frontend Infrastructure team. We own the platform that renders pages for hundreds of applications. A page is assembled by a **Java backend** and hydrated by a **React/TypeScript** client bundle. Most of the interesting logic on this team lives on the server: we compute, cache, and transform the things the browser eventually runs.

Our platform is extensible. Product teams and customers add functionality through **client extensions** — self-contained JavaScript bundles, authored outside our codebase, registered through configuration, and loaded at runtime from origins we may not know until a page is rendered. We do not control their code or their build.

Today every page ships this header:

```
Content-Security-Policy: default-src *; script-src * 'unsafe-inline' 'unsafe-eval'; style-src * 'unsafe-inline'
```

That is a policy in name only. A penetration test flagged it. **Your job is to make CSP real without breaking the extension model** — and the substance of that job is on the server.

## Run It

```bash
./gradlew :server:run
```

That builds the client bundle and starts two servers. Requires JDK 21 and Node 20 or later; nothing else.

| URL | What it is |
|---|---|
| http://localhost:8080/t/acme/dashboard | The page host. Three extensions placed. |
| http://localhost:8080/t/globex/dashboard | A second tenant, one extension placed. |
| http://localhost:8081/ | Third-party extension origins, and what stands in for a CDN. |

```bash
./gradlew :server:test                          # green on a fresh clone, two disabled
cd e2e && npm install && npx playwright test    # green on a fresh clone, two skipped
```

**The first page load takes about eight seconds.** That is not the network and it is not your machine. See `ClientExtensionRegistry`.

## What You Are Given

This repository **already runs**. Nothing you need is missing except the parts you are being asked to build. You are not being asked to invent an architecture — the scaffold gives you the seams, and your job is to fill them correctly.

### The Host Runtime

A small Java HTTP server that renders a page by composing fragments. These are the extension points you will work through:

| Seam | What it does |
|---|---|
| `request/RequestContextFilter` | Creates the `RequestContext` and tears it down, including on the error path. Runs before everything else. |
| `request/RequestContext` | Per-request state. Anything that must be unique per response belongs here. |
| `render/DynamicInclude` | Contributes markup at named insertion points — `top_head#pre`, `top_head#post`, `bottom#post`. This is how anything gets into the `<head>`. |
| `render/ScriptData` | A page-scoped buffer. Script contributed during rendering accumulates here and is flushed once, near the end of the document, after most of the body has been produced. |
| `render/PageRenderer` | Expands `${model}` values, `<!--#include:key-->` points, and `<!--#scriptData-->`. |
| `extension/ClientExtensions` | Resolves the extensions placed on this page, once per request. |
| `web/ShimDynamicInclude` | Inlines the feature-detection shim. Same bytes on every request, forever. |

Wiring lives in `HostServer`. You should not need to change it except to register something new.

### The Page

`/t/{tenant}/dashboard` renders for two tenants — `acme`, with three extensions placed, and `globex`, with one. It currently emits all of the following, deliberately:

- an inline bootstrap script carrying per-user data — `<script>window.__APP_CONFIG__ = { user, locale, features }</script>`
- a **static** inline shim that never varies across requests or users
- an inline `<style>` block, five `style="..."` attributes, and a stylesheet linked from a cross-origin CDN
- an inline event handler — `<button onclick="refreshWidgets()">Refresh</button>`
- an `<iframe>` embedding a third-party analytics widget, and an image from a cross-origin CDN
- a runtime module loader that injects `<script>` elements
- a CSS-in-JS runtime that injects `<style>` elements

### The Client Extensions

Served from port 8081 so that they are genuinely cross-origin. The server resolves which extensions are placed on a page, fetches their descriptors, and writes the result into `ScriptData` for the client loader to consume. A descriptor looks like this:

```json
{
	"name": "acme-charts",
	"type": "customElement",
	"htmlElementName": "acme-charts",
	"urls": ["http://localhost:8081/acme-charts/index.js"],
	"useESM": true,
	"csp": {
		"connect-src": ["http://localhost:8081"],
		"img-src": ["http://localhost:8081"]
	}
}
```

The `csp` block is the extension telling you what it needs in order to work. It is the author's claim about itself, not a fact.

- `acme-charts` — the well-behaved one.
- `legacy-ticker` — compiles a small expression language with `new Function(...)`. Its descriptor asks for `'unsafe-eval'`. The author is not going to rewrite it.
- `slow-widget` — the extension is fine; its origin takes eight seconds to answer, every time. This is not a bug.

## What to Build

### Core — All Required

**T1 — Policy model and assembly engine (Java).**
A CSP policy is not a string. Model it, and build an engine that composes one from four layers:

1. a **platform ceiling** — the most permissive policy any page may ever be granted,
2. **tenant** configuration,
3. **extension** requirements, taken from the descriptors of the extensions actually placed on the page being rendered,
4. a **per-route override**.

The ceiling is a maximum, not a default. Layers 2–4 may remove what it permits; they may never add anything it does not already permit. Make that property structural, not a code comment.

To keep the question decidable, the ceiling is **total**: it names every directive the platform governs, and a lower layer may not introduce a directive the ceiling does not name. Whatever it does name, a lower layer may narrow.

"Narrow" is the part we want you to think about, because it is not subtraction between sets of strings. Decide how you handle each of these, and say so in your write-up:

- `https://cdn.example.com` offered against a ceiling that permits `https:` — already covered, or rejected?
- `*`, and what it does and does not cover. It is not the universal set.
- `'none'`, which is not the same thing as saying nothing.
- `'strict-dynamic'` and `'unsafe-inline'`, which are not sources and cannot be intersected with one. They change what the sources beside them mean.
- nonces and hashes, which are per-response and therefore cannot appear in a static ceiling at all.

There is no single right answer to those. There is a wrong one, which is to treat a source list as a set of strings and take the intersection.

Requirements:

- Every incoming source expression is untrusted input. Validate it before it reaches a header value.
- Serialization must be **canonical and deterministic**: same inputs, same bytes, every time. Directives ordered, sources deduplicated.
- Composition is on the request hot path. Make it cheap, and say in your write-up what you cached, what the cache key is, and what invalidates it.

**T2 — Extension registry (Java, stateful).**
The server owns a registry of extension descriptors. It fetches them from the extensions' own origins, validates them, and holds them in memory with a refresh policy. Rendering a page must not depend on a live network call to a third party. Decide and defend: TTL, refresh strategy, what a page renders when a descriptor is stale, unreachable, slow, or malformed, and what stops one bad extension origin from degrading every request in the pool.

**T3 — Render pipeline (Java, transformation).**
Produce the final HTML and the final header together:

- Generate a per-response nonce and stamp it onto the script and style elements the platform emits — and only those.
- Move the per-user bootstrap data out of an executable inline script.
- Deal with the inline event handler and the inline styles.
- **The static inline shim must be allowed by hash, not by nonce.** Compute the hash and fold it into the policy.
- Do not use a regular expression to rewrite arbitrary HTML. If you post-process the document, say what parses it and why that is safe.

**T4 — Frontend integration (TypeScript/React).**
Make the page actually work under your strict policy: the runtime module loader that injects `<script>` elements, the CSS-in-JS runtime that injects `<style>` elements, the custom-element extensions, and the bootstrap data. In your write-up, name the mechanism that makes each one work — one line each. Note that the module loader and the style runtime do not survive for the same reason.

### Choose One of the Two

**T5 — Enforcement state machine (Java, stateful).**
Report-Only and Enforce, resolved per tenant and per route, changeable **at runtime without a deploy**, with a kill switch. Support a staged percentage rollout: a given user must not flip between modes from one request to the next, and must land in the same bucket on every server instance.

**T6 — Report ingestion service (Java, stateful).**
`POST /o/csp-report` accepting both the legacy `application/csp-report` body and the modern `application/reports+json` body, normalized into one internal model. It must survive real traffic: bounded memory, aggregation rather than raw retention, a deduplication key you can justify, rate limiting, and noise filtering. Expose an aggregate view. Assume it is unauthenticated and publicly reachable, because it is.

### Written and Tested — Required

**T7 — Rollout plan.** How we get from today's `*` policy to enforcement across production without breaking customers. Include how you decide it is safe to flip, and what you do when it goes wrong.

**T8 — Tests.** At minimum: a server-side test covering the layer-composition rules from T1 (including a source expression that must be rejected), a test asserting nonce uniqueness across responses, and one browser test proving an injected inline script is genuinely blocked while the legitimate path still works.

## What Is Yours

Each of these carries a `TODO` naming the task it belongs to. Some are empty; the rest work and are indefensible, which is not the same thing.

| Class | Task | State |
|---|---|---|
| `csp/CspPolicy`, `csp/CspPolicyBuilder` | T1 | empty |
| `csp/CspFilter` | T1, T3 | emits the policy the pentest flagged |
| `extension/ClientExtensionRegistry` | T2 | holds no state; re-fetches every descriptor on every render |
| `extension/DescriptorFetcher` | T2 | no timeouts, no body cap |
| `rollout/EnforcementResolver` | T5 | empty |
| `report/CspReportServlet` | T6 | returns 204 and forgets |
| `client/src/config.ts`, `client/src/main.tsx` | T4 | reads the global; style cache has an unfilled nonce slot |
| `CspPolicyTest` | T1, T3 | two `@Disabled` tests |
| `e2e/tests/csp.spec.ts` | T8 | two `test.skip` tests |

## Deliverables

- The repository, runnable with the same one-liner it has today.
- A `SOLUTION.md` of **at most two pages**: the final policy and why each directive is there, your T1 caching decision, your T2 failure-handling decisions, the T4 mechanism list, and the T7 rollout plan.

## Ground Rules

- **Time-box: ~5 hours.** Doing less and understanding all of it beats doing more and understanding some of it. If you cut scope, say so and say why.
- **You may use AI tools. We assume you will.** The interview is a 75-minute defense: you will explain your choices, we will change your code in front of you, and we will change the requirements and ask what your design does about it. Code you cannot explain is worse than code you did not write.
- **Work with the scaffold, not around it.** If you find yourself replacing a seam rather than filling it, stop and write down why — that is a legitimate answer, but we will ask you to defend it.
- **The app runs when you receive it and must run when you return it.**
- **Not being assessed:** visual design, authentication, durable persistence, production infrastructure, ORM or data-layer work, and the scaffold's own architecture.
