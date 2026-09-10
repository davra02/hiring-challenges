# Frontend Resource Caching — Frontend Infrastructure Exercise

**Role:** Mid-level Software Engineer, Frontend Infrastructure
**Format:** Take-home against this scaffold, followed by a 60-minute live defense

## Context

You are joining the Frontend Infrastructure team. We own the platform that serves every JavaScript module, stylesheet, and translated-label bundle for hundreds of applications. Pages are rendered by a **Java backend**; the client is **React and TypeScript**.

Right now the platform serves all of it with no caching headers at all. Every navigation re-downloads every module. The team has agreed on the shape of the fix — content-hashed URLs, so that a file whose bytes never change can be cached forever and a file that changes gets a new URL — but nobody has built it.

That is the work.

## Run It

```bash
./gradlew :server:run
```

That builds the client bundle and starts the server. Requires JDK 21 and Node 20 or later; nothing else. If something already owns port 8080, use `./gradlew :server:run -Pport=8090`.

| URL | What it is |
|---|---|
| http://localhost:8080/t/acme/dashboard | Tenant `acme`, configured for `use-one-hash-per-file`. |
| http://localhost:8080/t/globex/dashboard | Tenant `globex`, configured for `use-one-hash-per-web-context`. |
| `?locale=es_ES` | Switch the user's locale. It is stored in a cookie, standing in for a profile preference. |

```bash
./gradlew :server:test                          # green on a fresh clone, three disabled
cd e2e && npm install && npx playwright test    # green on a fresh clone
```

**Open the network panel and reload.** Every asset is a fresh 200, every time, forever. That is the starting point.

## What You Are Given

This repository **already runs**. You are not being asked to invent an architecture — the scaffold gives you the seams, and your job is to fill them correctly.

| Seam | What it does |
|---|---|
| `resource/FrontendResource` | What a servable resource exposes: content type, bytes, and its caching intent — `getETag`, `getMaxAge`, `isImmutable`, `isPrivate`, `isSendNoCache`. |
| `resource/handler/FrontendResourceRequestHandler` | The SPI. `canHandleRequest`, then `handleRequest`. The filter walks the registered handlers in order; the first to claim a request serves it, and if none does the request carries on down the chain. |
| `hashed/HashedFilesUtil` | `addHash("app.js", "aB3$xY7@")` gives `app.(aB3$xY7@).js`; `getHash` and `removeHash` go the other way; `computeHash` digests content. |
| `web/WebContext` | A named bundle of files — `app-web`, `widget-web` — mirroring how modules are grouped and deployed. |
| `configuration/FrontendCachingConfiguration` | Per-tenant: `cachingStrategy`, `jsFilesMaxAge`, `labelsModulesMaxAge`, `sendNoCacheForJSFiles`, `sendNoCacheForLabelsModules`. |
| `request/RequestContext` | The tenant and the user's locale for this request. |

`HostServer` does the wiring. You should not need to change it except to register something new.

### The Three Caching Strategies

`FrontendCachingConfiguration.cachingStrategy()` is per tenant and takes one of:

- `do-not-use-hashes` — URIs are the plain file names. Nothing can be cached indefinitely.
- `use-one-hash-per-file` — each file's URI carries a hash of that file's own content.
- `use-one-hash-per-web-context` — every file in a web context carries the same hash, derived from the context as a whole.

All three must work.

### What Is Yours

Each carries a `TODO` naming its task. Some work and are indefensible, which is not the same as being empty.

| Class | Task | State |
|---|---|---|
| `hashed/HashedFilesRegistry` | T1 | publishes every file under its plain name and reads it off the classpath on every request |
| `servlet/filter/FrontendResourceFilter` | T2, T3 | writes the bytes and the content type, and no caching header at all |
| `resource/handler/JavaScriptFrontendResourceRequestHandler` | T1, T2 | one caching intent for every file, whatever it is |
| `resource/handler/LanguageFrontendResourceRequestHandler` | T4 | correct for one user at a time |
| `client/src/modules.ts` | T5 | a hardcoded map of unhashed URLs |
| `FrontendResourceFilterTest` | T2, T3 | three `@Disabled` tests |

## What to Build

**T1 — Hashed files registry (Java, stateful).**
At startup, walk the web contexts, work out what each file's public URI should be, and hold the mapping. Serve two lookups: unhashed URI to public URI, which the page renderer needs to build its manifest, and public URI to bytes, which the handler needs to serve a request. Implement all three caching strategies. The strategy is per tenant, so two tenants may be looking at different URIs for the same file at the same time.

**T2 — Caching headers (Java, protocol).**
`FrontendResourceFilter.send` currently sends the bytes and stops. Give each response the `Cache-Control` its `FrontendResource` is asking for. A URI that carries a content hash and one that does not are not the same case, and `must-revalidate` and `no-cache` do not mean the same thing. In `SOLUTION.md`, state what you emit for each kind of resource and why.

**T3 — Conditional requests (Java, protocol).**
Support `ETag` and `If-None-Match`, returning `304 Not Modified` when the client already holds the bytes. Decide which resources get an ETag and which do not — `getETag()` is allowed to return null, and there is a reason for that. Say what a 304 response has to carry.

**T4 — The labels module (Java, correctness).**
`/o/js/language/{context}/all.js` returns a JavaScript module of translated UI labels for the current user's locale. It is correct for a single user and wrong the moment anything caches it. Find the problem, fix it, and write the test that would have caught it.

**T5 — Frontend (TypeScript/React).**
`client/src/modules.ts` hardcodes unhashed URLs, so the client cannot benefit from anything you built. The server already publishes a manifest on the page — `window.__MODULES__` — saying where each module actually lives. Resolve through it. A redeploy that changes one module must invalidate that module and nothing else.

**T6 — Tests and write-up.**
Enable and pass the three disabled filter tests, add the T4 test, and keep the existing tests green. Write a `SOLUTION.md` of at most two pages: the header matrix you settled on and why, how you handled the three strategies, your T4 diagnosis, and what you would want to verify before turning this on in production.

## Deliverables

- The repository, runnable with the same one-liner it has today.
- `SOLUTION.md`, at most two pages.

## Ground Rules

- **Take the time you need, and spend it on understanding rather than coverage.** Doing less and understanding all of it beats doing more and understanding some of it. If you cut scope, say so and say why.
- **You may use AI tools. We assume you will.** The interview is a 60-minute defense: you will explain your choices, we will change your code in front of you, and we will change the requirements and ask what your design does about it. Code you cannot explain is worse than code you did not write.
- **Work with the scaffold, not around it.** If you replace a seam rather than fill it, say why.
- **The app runs when you receive it and must run when you return it.**
- **Not being assessed:** visual design, authentication, persistence, production infrastructure.
