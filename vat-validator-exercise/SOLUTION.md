# Solution

## 1. The `/o/vat/lookup` contract

`GET /o/vat/lookup?vatId=…`. The server trims and upper-cases the id once,
after the blank check; the cache and the registry only see that value. The
body is `{"status": …}` plus the fields below.

| HTTP | `status` | Fields | Meaning |
|----|--------------|----------------|------------------------------------------|
| 200 | `REGISTERED` | `name`, `address` | The registry knows the number |
| 200 | `NOT_REGISTERED` | — | The member state said no |
| 200 | `UNAVAILABLE` | `reason` | The registry could not check |
| 502 | `ERROR` | `reason` | We failed: timeout, network, registry 401/429/5xx, unreadable answer |
| 429 | `ERROR` | `reason`, `Retry-After` header | Our budget is spent; the registry was not called |

A missing `vatId` is a 400 `{"error":"MISSING_VAT_ID"}`: a malformed request,
not an outcome.

- **The browser reads `status` and nothing else.** `reason`
  (`MEMBER_STATE_UNAVAILABLE`, `UPSTREAM_TIMEOUT`, `OWN_RATE_LIMIT_EXCEEDED`…)
  is for logs and devtools. Finer detail never becomes a fifth state.
- **Only an explicit `"valid": false` is `NOT_REGISTERED`.** `valid: null` is
  `UNAVAILABLE`; anything else we cannot read is `ERROR`. Nothing we failed to
  learn reaches the customer as "your number is wrong".
- **Status codes.** `UNAVAILABLE` is a 200: the call worked and the answer is
  "unknown". 502 is reserved for failures we handled, so a bare 500 means a bug
  we did not plan for. Our own refusal is a 429, like the registry's.

**Bounded calls.** Connect 1.5 s, request 7.5 s: above the 5 s a slow but valid
member state takes. The JDK request timeout runs from `send()`, so 7.5 s is the
worst case.

**Budget.** A process-wide sliding-window log allows **9** calls per 60 s, one
under the registry's ten, since we count on send and it counts on arrival, by
calendar minute. Check and take share one lock and a monotonic clock. It never
queues, and a refusal says when the oldest slot frees. Slots are never
refunded: a call we gave up on may still have counted upstream.

## 2. Caching per outcome

One `ConcurrentHashMap<vatId, CompletableFuture>` does both jobs. Incomplete,
the future is a call in flight that other callers wait on; complete, it is the
cached answer. Only a new call spends budget.

| Status | TTL | Why |
|--------------|------|----------------------------------------------------------|
| `REGISTERED` | 24 h | Close to a fact |
| `NOT_REGISTERED` | 1 h | Can change, but not by the minute |
| `UNAVAILABLE` | 1 min | Transient; longer would turn a registry blip into an outage the customer sees |
| `ERROR` | 10 s | A cooldown, not a fact: a failed call is not repeated straight away |

The budget is checked inside the `compute()` that decides a new call is
needed, so a refusal installs nothing: an entry reserved first would leave an
empty future that its waiters would wait on forever. The registry call runs
outside `compute()`, and the future always completes normally (as `ERROR` even
if the loader throws). Refusals are not cached, and an expired answer is not
served even when there is no budget to refresh it.

## 3. Which answer the element shows

- **When it asks:** 600 ms after the last keystroke if the value looks like a
  VAT number (two letters, 8+ characters, the shortest EU length), and always
  on blur, cancelling the debounce. The heuristic decides when to ask, never
  what an answer means.
- **Not twice:** a value is not asked again while its answer is a verdict
  (`registered`, `not-registered`). `unavailable` and `error` say "try again",
  so they release the value; the server's cache absorbs the retry.
- **Which answer wins:** each question takes a per-instance sequence token,
  and an answer is shown only if its token is still the latest. Clearing the
  field or disconnecting moves the token on; reconnecting does not reset it.
- **Not hanging:** after 10 s the field shows `error`. The request is not
  cancelled, and a late answer still replaces the error if nothing newer was
  asked. `AbortController` would stop neither the server's call nor its
  budget, and locking the input would freeze the field.
- **States and instances:** `data-state` is the status in lower case, plus
  `idle` and `checking`; a response without a known `status` is `error`. All
  state lives on the instance. The module-level `activeStatusElement`, which
  sent every answer to the last instance connected, is gone, and that is what
  makes `instanceable: true` hold.

## 4. What I would monitor

- **Budget:** our 429s and slots used out of 9. The registry's own 429s
  (`UPSTREAM_RATE_LIMITED`) should be zero; if not, our count is wrong.
- **How far it goes:** cache hits per status, callers joining a call in
  flight, upstream calls per minute.
- **Outcomes:** `UNAVAILABLE` by country prefix, to spot a member state that
  is down. `ERROR` by `reason`, paging on `UPSTREAM_AUTH_FAILURE`.
- **Latency and health:** p50/p95/p99 of the endpoint and of the upstream
  call, upstream calls over 7.5 s (see mid-body stalls), bare 500s, cache
  size, and client timeouts, which point in between if server p99 is low.

## 5. Known limits and cuts

- **One process, no fairness.** The limiter and the unbounded cache live in
  memory, so two instances would allow 18 calls a minute. Under contention it
  is first come, first served: there is no customer identity to ration by.
- **Mid-body stalls.** The JDK timeout stops at the response headers. Closing
  that needs `sendAsync` with an overall deadline.
- **Smaller gaps.** A value that stops passing the heuristic keeps the previous
  answer until blur. Two widgets without `data-field` share an id. There is no
  server-side format check, since the registry has no "malformed" outcome.
- **A weak scaffold test.** "Not called invalid" passes as soon as the page
  loads. I checked the `UNAVAILABLE` rendering by hand.
