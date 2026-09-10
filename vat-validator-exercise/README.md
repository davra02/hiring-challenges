# VAT Number Validation — Frontend Infrastructure Exercise

**Role:** Mid-level Software Engineer, Frontend Infrastructure
**Format:** Take-home against this scaffold, followed by a 60-minute live defense

## Context

You are joining the Frontend Infrastructure team. Checkout has a VAT number field. It has to tell the customer, while they type, whether the number they entered is registered.

The answer comes from an external VAT registry that we do not own and cannot change. It is slow, it is rate limited to **ten lookups a minute**, it needs an API key that must never reach a browser, and it sometimes cannot reach the member state that owns the number — in which case it says so, and says nothing about whether the number is good.

Between the browser and that registry sits a microservice we do own. Most of this exercise is what that microservice has to do.

## Run It

```bash
./gradlew :server:run
```

Builds your client extension and starts the host, the microservice, and a stand-in for the external registry. JDK 21 and Node 20 or later. `-Pport=8090` if something already owns 8080.

| URL | What it is |
|---|---|
| http://localhost:8080/checkout | The checkout page, with the extension placed twice. |
| http://localhost:8081/vat/check?vatId=ESB12345678 | The registry. Needs an `X-Api-Key` header. |

```bash
./gradlew :server:test                          # green on a fresh clone, three disabled
cd e2e && npm install && npx playwright test    # green on a fresh clone, three skipped
```

**Open the page, open the network panel, and type a VAT number.** What you see in the first thirty seconds is the exercise.

### The Registry's Behavior

Fixed and reproducible, not random. Everything it does, a real registry does too.

| Input | What comes back |
|---|---|
| `ESB12345678`, `NL004495445B01`, `FR40303265045`, `IE6388047V` | registered |
| `DE811907980` | registered, but its member state takes five seconds |
| anything ending in `9` | `{"valid": null, "reason": "MEMBER_STATE_UNAVAILABLE"}` |
| anything else | not registered |
| more than ten lookups in a minute | `429`, with `Retry-After` |
| no or wrong `X-Api-Key` | `401` |

## What You Are Given

| Piece | What it does |
|---|---|
| `cx/ClientExtensionRegistry` | Reads your `client-extension.yaml` the way the platform reads it, resolves the `index.*.js` pattern against `build/static`, and fails loudly at startup if the descriptor and the assembled output disagree. |
| `cx/ClientExtensionServlet` | Serves the assembled files. |
| `web/CheckoutServlet` | Renders the page and places your element on it, twice. |
| `upstream/UpstreamVatServlet` | The external registry. Do not change it; you do not own it. |
| `proxy/UpstreamVatClient` | Talks to the registry, holding the API key. |
| `proxy/VatLookupServlet` | The microservice endpoint at `/o/vat/lookup`. |

Your client extension is a **real client extension**. `client-extension.yaml` is the same descriptor a deployed extension carries, and the bundle is a real ESM module registering a real custom element. It should be deployable as-is.

### What Is Yours

| File | Task | State |
|---|---|---|
| `proxy/VatLookupServlet` | T1, T2, T3 | forwards every call and hands back the registry's answer verbatim |
| `proxy/UpstreamVatClient` | T2 | no connect timeout, no read timeout, nothing that gives up |
| `client-extension/src/index.ts` | T4 | one request per keystroke, and it has opinions it has not earned |
| `client-extension/client-extension.yaml` | T4 | declares `instanceable: true` |
| `VatLookupServletTest` | T1, T2, T3 | three `@Disabled` tests |
| `e2e/tests/behavior.spec.ts` | T5 | three skipped tests |

## What to Build

**T1 — The contract (Java).**
Decide what `/o/vat/lookup` returns and make it yours rather than the registry's. There are four distinct outcomes — registered, not registered, could not check, and our own failure — and the browser has to be able to tell them apart without parsing somebody else's error format. Whatever else you do, an outcome that is not "the registry told us this number is unregistered" must never reach the customer as "your VAT number is wrong."

**T2 — Timeouts and the upstream budget (Java).**
Bound every call to the registry, and decide what the caller gets when the bound is hit. Separately: ten lookups a minute is the whole budget for every customer on the site at once. Make sure the microservice cannot spend it, and decide what it does when it has.

**T3 — Caching and coalescing (Java, stateful).**
Two people checking the same number should not be two lookups. Neither should one person checking the same number twice. Cache what is worth caching — and note that the four outcomes from T1 are not all worth caching for the same length of time, if at all. Concurrent identical lookups should share one call rather than race.

**T4 — The client extension (TypeScript).**
Make the field behave. That means: not asking on every keystroke; not letting an answer to a question the customer has already moved on from overwrite a newer one; not hanging forever when nothing comes back; and showing a state for each outcome from T1 rather than collapsing them into valid and invalid.

The descriptor declares `instanceable: true`, which is a promise about what happens when the widget is placed more than once on a page. The page places it twice. Make the promise true.

**T5 — Tests and write-up.**
Enable the three disabled server tests and the three skipped browser tests, keep the existing ones green, and write a `SOLUTION.md` of at most two pages: your `/o/vat/lookup` contract and the reasoning behind it, your caching decisions per outcome, how the element decides which answer is still relevant, and what you would want to monitor once this is live.

## Deliverables

- The repository, runnable with the same one-liner it has today.
- `SOLUTION.md`, at most two pages.

## Ground Rules

- **Take the time you need, and spend it on understanding rather than coverage.** Doing less and understanding all of it beats doing more and understanding some of it. If you cut scope, say so and say why.
- **You may use AI tools. We assume you will.** The interview is a 60-minute defense: you will explain your choices, we will change your code in front of you, and we will change the requirements and ask what your design does about it. Code you cannot explain is worse than code you did not write.
- **You do not own the registry.** `UpstreamVatServlet` stands in for a third party. Changing it is not a solution.
- **The app runs when you receive it and must run when you return it.**
- **Not being assessed:** visual design, authentication, persistence, production infrastructure.
