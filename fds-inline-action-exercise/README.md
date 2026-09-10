# Inline Approve and Reject — Frontend Infrastructure Exercise

**Role:** Mid-level Software Engineer, Frontend Infrastructure
**Format:** Take-home against this scaffold, followed by a 45-minute live defense

## Context

You are joining the Frontend Infrastructure team. Finance reviews expense claims in a table. Today, approving one means opening it, changing a field, and coming back. They want to do it from the table.

A **cell renderer client extension** already renders the status column, and it already draws Approve and Reject buttons that send a `PATCH`. Press one and nothing visible happens. Press it on a large claim and nothing happens at all.

The interesting part of this exercise is not the buttons. It is what the server says in response, and what the table is supposed to do about it.

## Run It

```bash
./gradlew :server:run
```

Builds your client extension and starts the host. JDK 21 and Node 20 or later. `-Pport=8090` if something already owns 8080.

Then open http://localhost:8080/claims.

```bash
./gradlew :server:test                          # green on a fresh clone, two disabled
cd e2e && npm install && npx playwright test    # green on a fresh clone, three skipped
```

Twelve claims, five to a page, filtered to Pending by default. Claims over 1000 need a second approver and cannot be approved from the table.

## What You Are Given

| Piece | What it does |
|---|---|
| `static/fds.js` | A small frontend data set. It loads a page of items, renders a table, and for the status column hands off to your client extension. It reloads when something fires `fds-update-display` with its id, and fires `fds-display-updated` when it has. **Read this file.** You are not asked to change it. |
| `static/liferay.js` | The `Liferay.on` / `Liferay.fire` global, with the same names and semantics as the real one, so code you write against it works unchanged on a real server. |
| `cx/ClientExtensionRegistry` | Reads your `client-extension.yaml` the way the platform reads it and fails loudly at startup if the descriptor and the assembled output disagree. |
| `api/ClaimsCollectionServlet` | `GET /o/c/claims`, paged and filterable. Finished. |
| `api/ClaimServlet` | `PATCH /o/c/claims/{id}`. |
| `model/ClaimRepository` | The claims, in memory. |

Your client extension is a **real client extension**: `client-extension.yaml` declares `type: fdsCellRenderer`, and the bundle default-exports the `({itemData, value}) => HTMLElement` builder the platform calls. It should be deployable as-is.

### What Is Yours

| File | Task | State |
|---|---|---|
| `api/ClaimServlet` | T1 | enforces one rule, ignores the rest, and returns `204 No Content` on success |
| `client-extension/src/index.ts` | T2, T3 | sends the PATCH and forgets about it |
| `ClaimServletTest` | T1 | two `@Disabled` tests |
| `e2e/tests/decision.spec.ts` | T4 | three skipped tests |

## What to Build

**T1 — What the server says (Java).**
Three things are missing, and the third is a design decision rather than a bug.

- A claim that has already been approved or rejected should not quietly accept a second decision.
- Two reviewers looking at the same page, both pressing a button: the second one is acting on what the table showed before the first one landed. The server should be able to tell, and the claim already carries a `version` for that purpose.
- `204 No Content` on success is a decision about what the caller can do next. Decide what a successful PATCH should return, and be ready to say why.

**T2 — The button behaves (TypeScript).**
A decision should be sendable once, not three times. While it is in flight the row should say so. When the server refuses — and it will, on any claim over 1000 — the reviewer needs to find out, in the row, in words.

**T3 — The table reconciles (TypeScript).**
Make the table reflect what happened. There is more than one way and they are not equivalent:

- ask the data set to reload, which is one line and throws away nothing except everything the user was looking at;
- update the row in place from what the server returned, which keeps the page still and is only possible if T1 gave you something to update from.

Pick one, implement it, and in your write-up say what the other one would have cost. The data set reloads when it hears `fds-update-display` with its id; the id is on the container element.

**T4 — Tests and write-up.**
Enable the two disabled server tests and the three skipped browser tests, keep the existing ones green, and write a `SOLUTION.md` of at most a page and a half: what PATCH returns and why, how you detect a stale decision, which reconciliation strategy you chose and what it costs, and what you would do differently if the table showed ten thousand claims instead of twelve.

## Deliverables

- The repository, runnable with the same one-liner it has today.
- `SOLUTION.md`, at most a page and a half.

## Ground Rules

- **Take the time you need, and spend it on understanding rather than coverage.** Doing less and understanding all of it beats doing more and understanding some of it.
- **You may use AI tools. We assume you will.** The interview is a 45-minute defense: you will explain your choices, we will change your code in front of you, and we will change the requirements and ask what your design does about it.
- **`fds.js` is not yours.** It stands in for a platform component. Work with the contract it gives you.
- **The app runs when you receive it and must run when you return it.**
- **Not being assessed:** visual design, authentication, persistence, production infrastructure.
