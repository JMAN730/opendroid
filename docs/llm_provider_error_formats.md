# LLM provider error formats — raw material for a shared error taxonomy

Research asset for [issue #27](https://github.com/JMAN730/opendroid/issues/27), part of the
[API reliability & error surfacing map (#25)](https://github.com/JMAN730/opendroid/issues/25).

Scope: the 11 cloud REST providers OpenDroid ships (OpenAI, Claude, Gemini, Groq, Mistral,
Cohere, DeepSeek, OpenRouter, TogetherAI, CustomOpenAI-compatible, Copilot). On-device providers
(Gemma, LiteRT, Hybrid, Ollama-as-local) are out of scope for the map, though Ollama's
OpenAI-compat layer appears below as evidence about *arbitrary* custom endpoints.

Compiled 2026-07-28 from primary sources only — official docs, official OpenAPI specs, official
SDK source. Every claim carries an inline source URL. Anything the docs do not state is marked
**not documented** rather than guessed. A few facts came from live unauthenticated probes against
the real endpoints (bogus key, no prompt content sent); those are marked `LIVE PROBE`.

Two caveats on source trust:

- The Anthropic docs now resolve to `platform.claude.com` and reference model names the
  researching agent could not corroborate offline. Cited as served.
- Google now serves the **Interactions API** at the obvious `/gemini-api/docs/api-errors` URL;
  OpenDroid calls `generateContent`, whose errors page is a *different* URL with a *different*
  envelope. Both are documented below — do not cite the wrong one.

---

## What this means for OpenDroid — synthesis

### 1. There is no single error envelope. Five incompatible shapes across 11 providers.

| Shape | Providers |
|---|---|
| `{"error":{message,type,param,code}}` (OpenAI canonical) | OpenAI, Groq (no `param`), DeepSeek (`type`/`code` swapped), TogetherAI |
| `{"error":{code:int,message,metadata?}}` — `code` is the HTTP status, no `type`/`param` | OpenRouter |
| `{"type":"error","error":{type,message},"request_id"}` | Claude |
| `{"error":{code:int,message,status:SCREAMING_CASE,details[]}}` | Gemini `generateContent` |
| **flat, no envelope** | Mistral (`{"object":"error",...}`), Cohere (`{message,id}`) |

Plus shapes that are not JSON at all, or not the provider's own shape:
Mistral 401 → `{"detail":"Unauthorized"}`; Mistral 422 → FastAPI `{"detail":[ValidationError]}`;
DeepSeek with no `Authorization` header → **plain text** `Authentication Fails (governor)`;
TogetherAI 524 → Cloudflare **HTML**; vLLM 401 → `{"error":"Unauthorized"}` where `error` is a
**string**, not an object.

**Consequence for the taxonomy (#28):** a parser must branch on HTTP status *first* and treat the
body as best-effort enrichment. It must tolerate `error` being an object, a string, or absent; a
`code` that is an int or a string or always-null; and a body that is not JSON.

### 2. The same condition maps to different status codes.

| Condition | Divergence that will bite |
|---|---|
| invalid key | 401 everywhere **except Gemini, which returns 400 `INVALID_ARGUMENT`** — same status as bad JSON |
| out of credit | DeepSeek/Claude/Cohere/OpenRouter/TogetherAI use **402**; OpenAI and Mistral fold it into **429** alongside rate limiting, indistinguishable by status |
| overloaded | Claude uses non-standard **529**; generic retry middleware often does not classify it as retryable |
| context too long | Gemini reports it as **500 `INTERNAL` / 504 `DEADLINE_EXCEEDED`** — a naive "retry all 5xx" policy retries it forever |
| unknown model | 404 on most; Claude has no dedicated type; Gemini `generateContent` does not document it at all |

**Consequence for retry (#30):** "retry 429 and 5xx" is wrong on at least three providers.
Retryability has to be a property of the *classified* error, decided per provider, not read off
the status code.

### 3. A 200 does not mean success. Every streaming provider can fail after the headers flush.

- Claude: named SSE frame `event: error` / `data: {"type":"error","error":{...}}`.
- OpenAI: `ErrorEvent`, *and* the official SDK raises on **any** data frame containing an `error`
  key with no `event:` line — so inspect every frame.
- OpenRouter: top-level `error` on a `chat.completion.chunk`, **and** non-streaming failures
  nested inside `choices[0].error` with `finish_reason: "error"`.
- Cohere: no error event at all — signalled by `finish_reason ∈ {ERROR, TIMEOUT}` on a 200.
- Groq, DeepSeek, Mistral, Gemini `generateContent`, TogetherAI: **not documented**.

**Consequence for the streaming contract (#33):** the current code emits caught exceptions as chat
content (`OpenAIProvider.kt:99`), and additionally cannot even see these post-200 failures. Both
have to be fixed together.

### 4. Redaction is the client's job. No provider guarantees it.

Confirmed key echo in error bodies:

- **OpenAI** — 401 body echoes the submitted key's real **first 8 and last 4 characters**:
  `"Incorrect API key provided: sk-inval**********6789."` (LIVE PROBE)
- **DeepSeek** — 401 body echoes the **last 4 characters**: `"Your api key: ****6789 is invalid"` (LIVE PROBE)

Confirmed request-content echo:

- **Cohere** — the official error catalogue itself publishes messages echoing raw JSON fragments,
  caller-supplied labels, document ids, tool names, model ids, and exact prompt token counts.
- **OpenRouter** — moderation errors carry `metadata.flagged_input`, up to **100 characters of
  the user's prompt**; guardrail errors carry the matched `patterns`.
- **Gemini** (Interactions surface) — messages echo request values verbatim.
- **Arbitrary OpenAI-compat endpoints** — Ollama echoes `err.Error()` and the model name; vLLM
  echoes `str(exc)`; LiteLLM nests upstream provider text verbatim.

Structural risk specific to OpenDroid: **Gemini's key travels in the URL query string**
(`GeminiProvider.kt:85`, `?key=$apiKey`). It is not in the documented error bodies, but it lands
in any log, crash report, or exception message that captures the request URL. Sending it as the
`x-goog-api-key` header removes the vector entirely.

**Consequence for #31:** the current code passes the entire raw body into an exception message
that is then shown in chat. On OpenAI and DeepSeek that displays key material to the user and to
any bug report they paste. Redaction must be enforced at the classification boundary, before the
message reaches chat, logcat, or a crash sink — not per call site.

### 5. Retry hints are thin and inconsistently formatted.

| Provider | Documented signal |
|---|---|
| Claude | `retry-after` + 18 `anthropic-ratelimit-*` / `anthropic-priority-*` headers; reset in RFC 3339 |
| Groq | `retry-after` (integer seconds), `x-ratelimit-*`; reset as Go duration **with fractions** (`2m59.56s`) |
| OpenAI | `x-ratelimit-*`; reset as Go duration (`6m0s`) — **no** `Retry-After` documented |
| OpenRouter | `Retry-After` on 429/503, but documented as "**may** include" |
| TogetherAI | `x-ratelimit-reset` only |
| Mistral | `X-RateLimit-Remaining` mentioned once in a help article; nothing else |
| Gemini, Cohere, DeepSeek, Copilot | **none documented** |

Reset values are Go duration strings on OpenAI and Groq, not integers — an integer parser breaks.

### 6. Copilot has no public error contract at all.

`api.githubcopilot.com` is undocumented and unsupported for third-party clients; it appears only
in a network-allowlist community post. The Copilot REST API is management-only. GitHub Models
(`models.github.ai`) is the supported inference path and documents only 200 and a single 422 —
no error schema, no rate-limit headers. `CopilotProvider.kt` targets a user-configured server URL,
so in practice it is an arbitrary OpenAI-compatible endpoint and should be classified as one.

### 7. What OpenDroid does today

All 11 cloud providers throw the same shape, byte for byte modulo the provider name:

```kotlin
throw IOException("<Name> request failed: Code ${response.code} - ${response.body?.string()}")
```

- Status code survives only as text inside a message string; the body is never parsed.
- Missing key is a *different* exception type: `IllegalStateException("API Key for <name> is not set.")`.
- `CustomOpenAIProvider` and `CopilotProvider` use the same shape against arbitrary URLs, where
  the body may be HTML or plain text.
- Nothing anywhere inspects `type`, `code`, `status`, `finish_reason`, or any header.

So every distinction documented in this file is currently discarded at the throw site and
reconstructed nowhere.

---

# Per-provider detail

The three sections below are the full research output, retained verbatim with sources.


---

## Error semantics: OpenAI, Groq, DeepSeek, Mistral

Primary sources only: official docs pages, official OpenAPI spec, official SDK source. Where the docs are silent, I ran **live unauthenticated probes** against the real endpoints (`curl` with a bogus key, 2026-07-28) and marked those as `LIVE PROBE` — that is first-party response data, not a blog post. Anything neither documented nor observed is marked **not documented**.

---

### OpenAI

#### Error envelope

Canonical schema, from the official OpenAPI spec (`components/schemas/ErrorResponse` → `Error`), <https://raw.githubusercontent.com/openai/openai-openapi/master/openapi.yaml>:

```yaml
Error:
  type: object
  properties:
    code:    { anyOf: [ {type: string}, {type: "null"} ] }
    message: { type: string }
    param:   { anyOf: [ {type: string}, {type: "null"} ] }
    type:    { type: string }
  required: [type, message, param, code]
ErrorResponse:
  type: object
  properties:
    error: { $ref: '#/components/schemas/Error' }
  required: [error]
```

So: top-level envelope key `error`; fields `type`, `message`, `param`, `code`. All four are *required* per spec (nullable, but present). This is the shape the other three providers are measured against.

#### Status codes (documented)

From <https://developers.openai.com/api/docs/guides/error-codes> (the old `platform.openai.com/docs/guides/error-codes` 301-redirects here):

| Status | Documented cause (verbatim message text from the page) |
|---|---|
| 401 | "Invalid Authentication"; "Incorrect API key provided" — "The requesting API key is not correct."; "You must be a member of an organization to use the API"; "IP not authorized" — "Your request IP does not match the configured IP allowlist" |
| 403 | "Country, region, or territory not supported" |
| 429 | "Rate limit reached for requests" — "You are sending requests too quickly."; "You exceeded your current quota, please check your plan and billing details" — "You have run out of credits or hit your maximum monthly spend." |
| 500 | "The server had an error while processing your request" — "Issue on our servers." |
| 503 | "The engine is currently overloaded, please try again later" — "Our servers are experiencing high traffic."; "Slow Down" — "A sudden increase in your request rate is impacting service reliability." |

Notes per condition:

- **invalid / malformed key** → 401. LIVE PROBE (`POST https://api.openai.com/v1/chat/completions`, `Authorization: Bearer sk-invalidkey123456789`) returned HTTP 401, `content-type: text/plain` (sic), body:
  ```json
  {
    "error": {
      "message": "Incorrect API key provided: sk-inval**********6789. You can find your API key at https://platform.openai.com/account/api-keys.",
      "type": "invalid_request_error",
      "code": "invalid_api_key",
      "param": null
    },
    "status": 401
  }
  ```
  Note two deviations from the spec'd shape: an **extra top-level `status` field**, and `content-type: text/plain` on this path. With no `Authorization` header at all, LIVE PROBE returned 401 with `code: null` and the "You didn't provide an API key…" message, and *no* top-level `status` key — i.e. the envelope is not byte-stable across 401 variants.
- **expired / revoked key** → **not documented** as a distinct status/code; the docs page only lists the 401 rows above. `AuthenticationError` in the SDK is described as "Your API key or token was invalid, expired, or revoked" (<https://developers.openai.com/api/docs/guides/error-codes>), i.e. collapsed into 401.
- **insufficient quota / no credit** → 429 with message "You exceeded your current quota, please check your plan and billing details" (same page). The `code` value `insufficient_quota` is **not documented** on that page (widely observed, but no primary doc states it).
- **rate limited** → 429, "Rate limit reached for requests" (same page). Same status as quota exhaustion — the two are distinguishable only by `message`/`code`, and the doc gives no `code` for either. This is a real integration hazard.
- **unknown / unavailable model** → the docs page does not list a model-not-found row; SDK `NotFoundError` = "Requested resource does not exist", `PermissionDeniedError` = "You don't have access to the requested resource" (same page). Exact status/`code` for a bad model id: **not documented**.
- **malformed request / bad params / context length** → SDK `BadRequestError` "Your request was malformed or missing some required parameters" and `UnprocessableEntityError` "Unable to process the request despite correct format" (same page). `context_length_exceeded` as a `code` string: **not documented** in current docs. When a parameter is at fault, `param` carries the offending **parameter name** (spec field description, openapi.yaml).
- **server error / overloaded** → 500 and 503 rows above.

#### Rate-limit / retry headers

From <https://developers.openai.com/api/docs/guides/rate-limits> (verbatim table):

| Header | Sample | Meaning |
|---|---|---|
| `x-ratelimit-limit-requests` | `60` | max requests before exhausting the limit |
| `x-ratelimit-limit-tokens` | `150000` | max tokens |
| `x-ratelimit-remaining-requests` | `59` | |
| `x-ratelimit-remaining-tokens` | `149984` | |
| `x-ratelimit-reset-requests` | `1s` | time until request limit resets |
| `x-ratelimit-reset-tokens` | `6m0s` | time until token limit resets |
| `x-ratelimit-limit-project-tokens` | `60000` | project-scoped token limit |
| `x-ratelimit-remaining-project-tokens` | `57000` | |
| `x-ratelimit-reset-project-tokens` | `3s` | |

**Reset format is a Go-style duration string** (`1s`, `6m0s`), *not* a unix timestamp and *not* seconds-as-integer — parsers must handle `h/m/s` suffixes and fractional values. `Retry-After` is **not documented** on the rate-limits page.

#### REDACTION RISK

**Confirmed key echo.** LIVE PROBE: the 401 body contains a partially-masked copy of the submitted key — `"Incorrect API key provided: sk-inval**********6789."` — i.e. the first 8 characters and last 4 characters of whatever was sent are echoed verbatim. If a caller logs raw error bodies, they log a key prefix/suffix. Masking is server-side and fixed-width, but the **prefix is real key material**. Org id echo: **not documented** and not observed in probes. Prompt/message echo in error bodies: **not documented**; the `param` field is specified to carry a parameter *name*, not a value (openapi.yaml). No primary source states that message content is echoed.

#### Streaming (SSE) errors

- **Before the stream opens**: the failure is an ordinary HTTP status + `ErrorResponse` JSON body (no SSE at all) — that is the only path the status-code table describes.
- **Mid-stream**: the spec defines `ErrorEvent` (openapi.yaml):
  ```yaml
  ErrorEvent:
    properties:
      event: { enum: [error] }
      data:  { $ref: '#/components/schemas/Error' }
    description: Occurs when an error occurs. This can happen due to an internal server error or a timeout.
  ```
  So the HTTP status is already 200 and the error arrives as an SSE frame with `event: error` and a `data:` payload that is the `Error` object.
- Responses API has its own flattened mid-stream event, `ResponseErrorEvent` (openapi.yaml), with the example given verbatim in the spec:
  ```json
  { "type": "error", "code": "ERR_SOMETHING", "message": "Something went wrong", "param": null, "sequence_number": 1 }
  ```
  Note: **no `error` wrapper** here — `type`/`code`/`message`/`param` are top-level on the event, and `type` is the *event* type (`"error"`), not the OpenAI error class. Different parse path from the HTTP body.
- A finished-but-failed Response carries `ResponseError` with a closed enum `ResponseErrorCode` (openapi.yaml): `server_error`, `rate_limit_exceeded`, `invalid_prompt`, `data_residency_mismatch`, `bio_policy`, `vector_store_timeout`, `invalid_image`, `invalid_image_format`, `invalid_base64_image`, `invalid_image_url`, `image_too_large`, `image_too_small`, `image_parse_error`, `image_content_policy_violation`, `invalid_image_mode`, `image_file_too_large`, `unsupported_image_media_type`, `empty_image_file`, `failed_to_download_image`, `image_file_not_found`.
- SDK confirmation of the wire contract, `openai-python/src/openai/_streaming.py` lines 62–99 (<https://raw.githubusercontent.com/openai/openai-python/main/src/openai/_streaming.py>): the stream iterator raises `APIError` **both** when `sse.event == "error"` and when *any* data frame is a mapping containing an `error` key — i.e. mid-stream errors also arrive as a plain `data: {"error": {...}}` frame with no `event:` line, on Chat Completions. Practical rule: **check every SSE data frame for an `error` key**, don't rely on the event name.

---

### Groq

#### Error envelope

Documented shape, <https://console.groq.com/docs/errors> (verbatim):

```json
{
  "error": {
    "message": "String - description of the specific error",
    "type": "invalid_request_error"
  }
}
```

The docs list only `error.message` and `error.type` — **no `param`, no `code`**. But the live API does emit `code`. LIVE PROBE (`GET https://api.groq.com/openapi.json`, HTTP 404):

```json
{"error":{"message":"Unknown request URL: GET /openapi.json. Please check the URL for typos, or see the docs at https://console.groq.com/docs/","type":"invalid_request_error","code":"unknown_url"}}
```

So treat Groq as OpenAI-shaped minus `param`: `error.{message,type,code}`, `code` present but **undocumented** — do not rely on its presence or its value set.

#### Status codes

From <https://console.groq.com/docs/errors> (verbatim descriptions):

| Status | Verbatim |
|---|---|
| 400 Bad Request | "The server could not understand the request due to invalid syntax." |
| 401 Unauthorized | "The request lacks valid authentication credentials for the requested resource." |
| 403 Forbidden | "The request is not allowed due to permission restrictions." |
| 404 Not Found | "The requested resource could not be found." |
| 413 Request Entity Too Large | "The request body is too large." |
| 422 Unprocessable Entity | "The request was well-formed, but could not be followed due to semantic errors." |
| 424 Failed Dependency | "The request failed because the dependent request failed." |
| 429 Too Many Requests | "Too many requests were sent in a given timeframe." |
| 498 Flex Tier Capacity Exceeded | Flex tier at capacity; retry later. (Groq-specific, non-standard code) |
| 499 Request Cancelled | Request cancelled by the caller. (Groq-specific) |
| 500 Internal Server Error | Generic server error; retry later or contact support. |
| 502 Bad Gateway | "The server received an invalid response from the upstream server." |
| 503 Service Unavailable | "The server is not ready to handle the request." |

The same page states server-error responses are not billed: "No charges apply to server error responses."

Per condition:

- **invalid / malformed key** → 401. LIVE PROBE (bad bearer): HTTP 401, `content-type: application/json`, body exactly:
  ```json
  {"error":{"message":"Invalid API Key","type":"invalid_request_error","code":"invalid_api_key"}}
  ```
  Identical body when the `Authorization` header is omitted entirely (LIVE PROBE) — Groq does **not** distinguish missing from invalid.
- **expired / revoked key** → **not documented**; no distinct status or code listed. Presumed folded into 401.
- **insufficient quota / no credit / billing not active** → **not documented**. The errors page has no quota/billing row and the rate-limits page (<https://console.groq.com/docs/rate-limits>) says nothing about free-tier exhaustion or on-demand billing failures. 498 covers *flex tier capacity*, which is a capacity condition, not a billing one.
- **rate limited** → 429, "Too many requests were sent in a given timeframe." No example body is given; **429 body shape not documented**.
- **unknown / unavailable model** → 404 "The requested resource could not be found." is the only candidate row; a model-specific `code` is **not documented**.
- **malformed request / bad params** → 400. Documented concretely in <https://console.groq.com/docs/openai>: "The following fields are currently not supported and will result in a 400 error (yikes) if they are supplied" — `logprobs`, `logit_bias`, `top_logprobs`, `messages[].name`, and `N` (must equal 1 if supplied). Context-length-exceeded status/code: **not documented** (413 covers oversized *request body*, which is not the same thing).
- **server error / overloaded** → 500 / 502 / 503 rows above.

#### Rate-limit / retry headers

From <https://console.groq.com/docs/rate-limits> (verbatim header names and sample values):

| Header | Sample |
|---|---|
| `retry-after` | `2` |
| `x-ratelimit-limit-requests` | `14400` |
| `x-ratelimit-limit-tokens` | `18000` |
| `x-ratelimit-remaining-requests` | `14370` |
| `x-ratelimit-remaining-tokens` | `17997` |
| `x-ratelimit-reset-requests` | `2m59.56s` |
| `x-ratelimit-reset-tokens` | `7.66s` |

`retry-after` is in **seconds** (integer); the same page states it "is only provided when you hit the rate limit and receive a 429". Reset values are **Go-style duration strings with fractional seconds** (`2m59.56s`, `7.66s`) — same family as OpenAI's but with decimals, so an integer parser will break.

#### REDACTION RISK

Groq's 401 message is the constant string `"Invalid API Key"` — **no key material echoed** (LIVE PROBE, verified against both a bogus key and a missing header). This is materially safer than OpenAI and DeepSeek. Echo of prompt/message content in error bodies: **not documented**; the 404 body does echo the **request method and URL path** (`"Unknown request URL: GET /openapi.json"`), so URLs are reflected — relevant if you ever put identifiers in a path. Org id echo: **not documented**.

#### Streaming (SSE) errors

**Not documented.** Neither <https://console.groq.com/docs/errors> nor <https://console.groq.com/docs/openai> describes how an error surfaces mid-stream. Since Groq is wire-compatible with the OpenAI Chat Completions SSE format and the official OpenAI SDK's `_streaming.py` raises on any data frame containing an `error` key, the defensive assumption is a `data: {"error": {...}}` frame — but that is inference, not a documented Groq guarantee.

---

### DeepSeek

#### Error envelope

**Not documented.** <https://api-docs.deepseek.com/quick_start/error_codes> gives a status/cause/solution table only and contains **no JSON body examples at all**.

LIVE PROBE (`POST https://api.deepseek.com/chat/completions`, bogus bearer) → HTTP 401, `content-type: application/json`:

```json
{"error":{"message":"Authentication Fails, Your api key: ****6789 is invalid","type":"authentication_error","param":null,"code":"invalid_request_error"}}
```

Envelope key `error`, fields `message`/`type`/`param`/`code` — OpenAI-shaped. **But the `type` and `code` values are semantically swapped relative to OpenAI convention**: `type` holds `authentication_error` (OpenAI would put `invalid_request_error` there) and `code` holds `invalid_request_error` (OpenAI would put `invalid_api_key`). Do not switch on DeepSeek's `code` expecting OpenAI code strings.

Worse, LIVE PROBE with **no `Authorization` header at all** returns HTTP 401 with a **non-JSON plain-text body**:

```
Authentication Fails (governor)
```

A parser that assumes JSON on every DeepSeek error will throw on this path. This is undocumented.

#### Status codes

From <https://api-docs.deepseek.com/quick_start/error_codes> (verbatim Description / Cause):

| Status | Description | Cause (verbatim) |
|---|---|---|
| 400 | Invalid Format | "Invalid request body format" |
| 401 | Authentication Fails | "Authentication fails due to the wrong API key" |
| 402 | Insufficient Balance | "You have run out of balance" |
| 422 | Invalid Parameters | "Your request contains invalid parameters" |
| 429 | Rate Limit Reached | "You are sending requests too quickly" |
| 500 | Server Error | "Our server encounters an issue" |
| 503 | Server Overloaded | "The server is overloaded due to high traffic" |

Per condition:

- **invalid / malformed key** → 401 (see body above).
- **expired / revoked key** → **not documented** as distinct; 401 only.
- **insufficient quota / no credit** → **402 Insufficient Balance**, "You have run out of balance". This is the biggest divergence in the set: DeepSeek uses a dedicated 402, where OpenAI uses 429 and Groq/Mistral document nothing. Body shape for 402: **not documented**.
- **rate limited** → 429. <https://api-docs.deepseek.com/quick_start/rate_limit> documents this as a **concurrency** limit, not RPM/TPM: "A request counts as one concurrent connection from the time it is sent until the model response is complete", limits are per *account* "regardless of which API key is used", and exceeding them means "you will receive an HTTP 429 error code". Also applies per `user_id` for expanded-quota accounts.
- **unknown / unavailable model** → **not documented**. No model-not-found row exists; presumably folded into 400/422.
- **malformed request** → 400 (bad body format) vs 422 (invalid parameters) — two distinct statuses where OpenAI mostly uses 400. Context-length-exceeded status: **not documented**.
- **server error / overloaded** → 500 / 503.

#### Rate-limit / retry headers

**Not documented.** Neither the error-codes page nor the rate-limit page names any `x-ratelimit-*` header or `Retry-After`. <https://api-docs.deepseek.com/quick_start/rate_limit> instead documents keep-alive padding (below) and a hard cutoff: "If inference hasn't begun within 10 minutes, the server will close the connection." Assume no rate-limit headers and back off blind.

#### REDACTION RISK

**Confirmed key echo.** LIVE PROBE 401 message: `"Authentication Fails, Your api key: ****6789 is invalid"` — the **last 4 characters** of the submitted key are echoed. Less exposure than OpenAI (suffix only, no prefix), but still key-derived material in the body. Prompt/message echo: **not documented**. Org id: **not documented**.

#### Streaming (SSE) errors

- **Before the stream opens**: ordinary HTTP status + body per the table above. If the failure is a missing auth header, the body is **plain text, not JSON** (LIVE PROBE) — handle that before attempting SSE parse.
- **Mid-stream**: how an error is delivered after a 200 is **not documented**.
- Documented streaming quirk that is easy to mistake for an error, <https://api-docs.deepseek.com/quick_start/rate_limit>: while waiting, the server "continuously return[s] SSE keep-alive comments (`: keep-alive`)" on streaming requests, and "continuously return[ed] empty lines" on **non-streaming** requests. The page notes these "don't affect JSON parsing" / are benign — but a strict non-streaming JSON reader that chokes on leading blank lines will misfire, and a hand-rolled SSE reader must skip `:` comment lines.

---

### Mistral

#### Error envelope

Documented shape, <https://docs.mistral.ai/resources/error-glossary> (verbatim):

```json
{
  "object": "error",
  "message": "A human-readable description of the error.",
  "type": "invalid_request_error",
  "param": "model",
  "code": "unknown_model"
}
```

**This is the key divergence from OpenAI: the fields are FLAT — there is no `error` wrapper object.** Instead there is an `object: "error"` discriminator alongside `message`/`type`/`param`/`code`. Code written against `body["error"]["message"]` gets a `KeyError` on Mistral.

Documented field meanings (same page): `message` = "Human-readable error description"; `type` ∈ `invalid_request_error`, `authentication_error`, `rate_limit_error`, `server_error`; `param` = "The parameter causing the error (if applicable)"; `code` = "Machine-readable error code (if applicable)". The only `code` value given anywhere on the page is `unknown_model`; a full code list is **not documented**.

**Third shape, undocumented:** LIVE PROBE (`POST https://api.mistral.ai/v1/chat/completions`, bogus bearer *and* with no auth header) → HTTP 401, `content-type: application/json`, body exactly:

```json
{"detail":"Unauthorized"}
```

This matches neither the documented flat shape nor OpenAI's — no `object`, no `type`, no `code`. Auth failures use a FastAPI-style `detail` envelope.

**Fourth shape:** 422 validation errors use FastAPI's `HTTPValidationError`. From the official SDK, `mistralai/client/errors/httpvalidationerror.py` and `models/validationerror.py` (<https://raw.githubusercontent.com/mistralai/client-python/main/src/mistralai/client/errors/httpvalidationerror.py>, <https://raw.githubusercontent.com/mistralai/client-python/main/src/mistralai/client/models/validationerror.py>):

```python
class HTTPValidationErrorData(BaseModel):
    detail: Optional[List[models_validationerror.ValidationError]] = None

class ValidationError(BaseModel):
    loc:   List[Union[str, int]]
    msg:   str
    type:  str
    input: Optional[Any] = None
    ctx:   Optional[Context] = None
```

i.e. `{"detail": [{"loc": [...], "msg": "...", "type": "...", "input": ..., "ctx": {...}}]}`. The SDK README confirms `HTTPValidationError` is "Validation Error. Status code `422`. Applicable to 159 of 256 methods." (<https://raw.githubusercontent.com/mistralai/client-python/main/README.md>). **So Mistral has at least three distinct error body shapes** (flat `object:"error"`, `{"detail": "string"}`, `{"detail": [ValidationError]}`) and a client must sniff all three.

#### Status codes

From <https://docs.mistral.ai/resources/error-glossary>: 400 Bad request, 401 Unauthorized, 403 Forbidden, 404 Not found, 422 Validation error, 429 Too many requests, 500 Internal server error, 502 Bad gateway, 503 Service unavailable, 504 Gateway timeout. The same page advises exponential backoff for the transient set (429, 500, 502, 503, 504) and notes the official Python/TypeScript SDKs have built-in retry with backoff.

Per condition:

- **invalid / malformed key** → 401, body `{"detail":"Unauthorized"}` (LIVE PROBE). The docs say only "401: Unauthorized".
- **expired / revoked key** → **not documented** as distinct; 401 only.
- **insufficient quota / no credit / billing not active** → **not documented** as a status code. <https://docs.mistral.ai/admin/billing-usage/usage-limits> says only: "If the Organization reaches its monthly spending limit, API access can be suspended until the next month begins or an admin increases the limit." No status code or body is given for the suspended state.
- **rate limited** → 429. <https://help.mistral.ai/en/articles/698531-why-am-i-hitting-api-rate-limits-and-how-do-i-increase-them> (official help center) documents three independent limit types: "Requests per second (RPS) — concurrent requests", "Tokens per minute — input + output throughput", "Tokens per month — overall consumption cap", enforced "at the organization level across all workspaces". **429 body shape: not documented.** Note the tokens-per-month cap means a 429 can mean "quota exhausted for the month", not "slow down" — retrying is futile in that case and the error body gives no documented way to tell.
- **unknown / unavailable model** → the documented example body *is* this case: `"code": "unknown_model"`, `"param": "model"`, `"type": "invalid_request_error"` (error glossary). Status is not stated alongside the example; the glossary's plausible rows are 400 or 404. **Status for `unknown_model`: not documented.**
- **malformed request / bad params** → 400 (bad request) and 422 (validation error, FastAPI `detail` array shape above). Context-length-exceeded status/code: **not documented**.
- **server error / overloaded** → 500 / 502 / 503 / 504.

#### Rate-limit / retry headers

Partially documented and thin. Mistral's official rate-limit help article references checking `X-RateLimit-Remaining` to "monitor your usage before hitting the limit" (<https://help.mistral.ai/en/articles/698531-why-am-i-hitting-api-rate-limits-and-how-do-i-increase-them>). Note the capitalisation and that there is **no documented `-requests`/`-tokens` suffix split** the way OpenAI and Groq have. `Retry-After`, reset headers, and reset value format: **not documented**. The docs' guidance is client-side exponential backoff instead (<https://docs.mistral.ai/resources/error-glossary>).

#### REDACTION RISK

**No key echo observed.** LIVE PROBE 401 body is the constant `{"detail":"Unauthorized"}` — safest of the four. Prompt/message content echo: **not documented** for the flat error shape. **However**: the 422 `ValidationError` model has an `input: Optional[Any]` field (official SDK, `models/validationerror.py`), which is FastAPI/pydantic's echo of *the offending input value*. On a validation failure inside `messages`, that field can structurally contain **request content**. The SDK also carries the raw body into exception messages — `SDKError.__init__` builds `message += f". Body: {body_display}"` and only truncates at `MAX_MESSAGE_LEN = 10_000` (<https://raw.githubusercontent.com/mistralai/client-python/main/src/mistralai/client/errors/sdkerror.py>) — so up to 10 000 characters of server body land in the exception string and thus in any log that captures it. Whether `input` is actually populated with prompt text by the server is **not documented**; the field's existence is the documented risk.

#### Streaming (SSE) errors

**Not documented.** The error glossary does not mention streaming, SSE, or mid-stream error delivery at all, and no Mistral primary source found describes it. Pre-stream failures are ordinary HTTP status + one of the three body shapes above. Mid-stream behaviour: **not documented** — do not assume the OpenAI `data: {"error": ...}` convention without verifying against the live API.

---

### Cross-provider summary

| | OpenAI | Groq | DeepSeek | Mistral |
|---|---|---|---|---|
| Envelope | `{"error":{...}}` (+ stray top-level `status` on some 401s) | `{"error":{...}}` | `{"error":{...}}`, but **plain text** when auth header missing | **flat** `{"object":"error",...}` / `{"detail":"..."}` / `{"detail":[...]}` |
| `type` | documented, required | documented | present, **swapped with `code`** | documented, 4 values |
| `param` | documented, required | **absent** | present (null) | documented |
| `code` | documented, required | **undocumented but present** | present, holds OpenAI's `type` values | documented, only `unknown_model` named |
| No-credit status | 429 | not documented | **402** | not documented |
| Retry-After | not documented | `retry-after`, integer seconds | not documented | not documented |
| Reset format | Go duration `6m0s` | Go duration w/ fraction `2m59.56s` | n/a | not documented |
| Key echoed in error body | **yes — prefix+suffix** `sk-inval**********6789` | no | **yes — suffix** `****6789` | no |
| Mid-stream error | documented (`ErrorEvent`, `ResponseErrorEvent`) | not documented | not documented | not documented |

Practical takeaways for a client library: (1) never log raw 401 bodies from OpenAI or DeepSeek; (2) never assume the response body is JSON (DeepSeek no-auth path) or that `body["error"]` exists (Mistral, all paths); (3) treat 429 as ambiguous between "slow down" and "out of money/quota" on OpenAI and Mistral; (4) parse reset headers as duration strings with optional fractions, not integers; (5) on SSE, inspect every data frame for an `error` key regardless of the `event:` line.

---

## LLM provider error surfaces: Anthropic, Google Gemini, Cohere

Primary sources only. Every claim carries an inline source URL. Anything the docs do not
state is marked **not documented** rather than inferred.

Retrieved 2026-07-28. Google pages self-report "Last updated 2026-07-27 UTC".

---

### Anthropic Claude (Messages API)

Canonical source: <https://platform.claude.com/docs/en/api/errors>
(`https://docs.anthropic.com/en/api/errors` 301-redirects here.)

#### 1. Conditions → status code + body

The envelope is identical for every condition; only `error.type` and `error.message` change.
The docs give one full verbatim body (a 404) and several verbatim 400 bodies; other rows below
give the documented status + `type` pairing, with the body shape being the same envelope.

| Condition | HTTP | `error.type` | Source |
|---|---|---|---|
| invalid API key / malformed key | 401 | `authentication_error` | [errors](https://platform.claude.com/docs/en/api/errors) |
| expired or revoked key | 401 | `authentication_error` | same — docs explicitly enumerate "malformed, revoked, or expired" under 401 |
| insufficient quota / no credit / billing not active | 402 | `billing_error` | same — "There's an issue with your billing or payment information" |
| no permission for the resource (wrong workspace/org) | 403 | `permission_error` | same |
| unknown/unavailable model, bad endpoint path | 404 | `not_found_error` | same — "The requested resource was not found. Check the endpoint path and any resource IDs in the request URL." |
| malformed request (bad params, bad JSON, max_tokens/context problems) | 400 | `invalid_request_error` | same |
| request body too large (>32 MB Messages API) | 413 | `request_too_large` | same |
| rate limited | 429 | `rate_limit_error` | same |
| server error | 500 | `api_error` | same |
| request timed out server-side | 504 | `timeout_error` | same |
| overloaded | 529 | `overloaded_error` | same |
| resource state conflict | 409 | `conflict_error` | same |

Notable: **there is no distinct "model not found" type.** An unknown model id falls under the
generic 404 `not_found_error` (or 400 `invalid_request_error`, since the docs say 400
`invalid_request_error` "may also be used for other 4XX status codes not listed in this
section"). Source: <https://platform.claude.com/docs/en/api/errors>

Anthropic separates **quota/billing (402)** from **rate limiting (429)**, which is unusual —
Gemini and Cohere both fold at least part of quota into 429/402 differently (see below).

##### Verbatim body — 404

Source: <https://platform.claude.com/docs/en/api/errors>

```json
{
  "type": "error",
  "error": {
    "type": "not_found_error",
    "message": "The requested resource could not be found."
  },
  "request_id": "req_011CSHoEeqs5C35K2UUqR7Fy"
}
```

##### Verbatim body — 400 malformed request (assistant prefill unsupported)

Source: <https://platform.claude.com/docs/en/api/errors> ("Prefill not supported")

```json
{
  "type": "error",
  "error": {
    "type": "invalid_request_error",
    "message": "This model does not support assistant message prefill. The conversation must end with a user message."
  }
}
```

Other verbatim 400 `invalid_request_error` messages documented on the same page:

- `` `thinking` or `redacted_thinking` blocks in the latest assistant message cannot be modified. These blocks must remain as they were in the original response. `` — prefixed by the offending block position, e.g. `messages.1.content.0`
- `"thinking.type.enabled" is not supported for this model. Use "thinking.type.adaptive" and "output_config.effort" to control thinking behavior.`
- `adaptive thinking is not supported on this model`
- `"thinking.type.disabled" is not supported for this model. Thinking defaults to adaptive mode when not specified; use "thinking.type.enabled" with "budget_tokens" for extended thinking.`

A verbatim 401 / 402 / 429 / 500 / 529 **body** is not printed on the errors page — only the
status↔type mapping plus the universal envelope. The 529 body *is* given verbatim in the
streaming SSE example (see §5). Treat non-listed bodies as: **shape documented, exact message
string not documented.**

#### 2. Envelope / field structure

Source: <https://platform.claude.com/docs/en/api/errors> — "Error shapes"

> The API always returns errors as JSON, with a top-level `error` object that always includes a
> `type` and `message` value. The response also includes a `request_id` field for easier tracking
> and debugging.

```
{
  "type": "error",              // constant discriminator, always the string "error"
  "error": {
    "type":    <string>,        // machine-readable, snake_case
    "message": <string>         // human-readable
  },
  "request_id": <string>        // mirrors the `request-id` response header
}
```

There is no numeric `code` field and no `status` field. The HTTP status *is* the code.

**Full documented `type` enum** (from the HTTP errors list on the same page):
`invalid_request_error`, `authentication_error`, `billing_error`, `permission_error`,
`not_found_error`, `conflict_error`, `request_too_large`, `rate_limit_error`, `api_error`,
`timeout_error`, `overloaded_error`.

The docs explicitly warn this enum is open:

> In accordance with the versioning policy, the values within these objects may expand, and it is
> possible that the `type` values will grow over time.

So: switch on `type` with a default branch; do not assume closed-world.

#### 3. Rate-limit / retry headers

Source: <https://platform.claude.com/docs/en/api/rate-limits> — "Response headers"

| Header | Description (verbatim) |
|---|---|
| `retry-after` | The number of seconds to wait until you can retry the request. Earlier retries will fail. |
| `anthropic-ratelimit-requests-limit` | The maximum number of requests allowed within any rate limit period. |
| `anthropic-ratelimit-requests-remaining` | The number of requests remaining before being rate limited. |
| `anthropic-ratelimit-requests-reset` | The time when the request rate limit will be fully replenished, provided in RFC 3339 format. |
| `anthropic-ratelimit-tokens-limit` | The maximum number of tokens allowed within any rate limit period. |
| `anthropic-ratelimit-tokens-remaining` | The number of tokens remaining (rounded to the nearest thousand) before being rate limited. |
| `anthropic-ratelimit-tokens-reset` | The time when the token rate limit will be fully replenished, provided in RFC 3339 format. |
| `anthropic-ratelimit-input-tokens-limit` / `-remaining` / `-reset` | Same three semantics, scoped to input tokens. |
| `anthropic-ratelimit-output-tokens-limit` / `-remaining` / `-reset` | Same three semantics, scoped to output tokens. |
| `anthropic-priority-input-tokens-limit` / `-remaining` / `-reset` | Priority Tier only. |
| `anthropic-priority-output-tokens-limit` / `-remaining` / `-reset` | Priority Tier only. |

Also documented on that page: `anthropic-fast-*` headers exist for fast mode
(`speed: "fast"` on Opus 5 / Opus 4.8); the individual header names are deferred to
<https://platform.claude.com/docs/en/build-with-claude/fast-mode> and are **not documented on the
rate-limits page**.

`retry-after` is honored automatically by the official SDKs:

> The official SDKs automatically retry transient failures (such as connection errors, rate
> limits, and 5xx server errors) with exponential backoff, twice by default, honoring the
> `retry-after` header when present.
> — <https://platform.claude.com/docs/en/api/errors>

The `anthropic-ratelimit-tokens-*` triple reports **the most restrictive limit currently in
effect**, not a fixed bucket — so a client that caches these will mis-model the limit when a
workspace-level cap kicks in. Source: <https://platform.claude.com/docs/en/api/rate-limits>.

Request correlation: every response carries a `request-id` header (e.g.
`req_018EeWyXxfu5pfWkrYcMdjWG`), duplicated as `request_id` in error bodies.
Source: <https://platform.claude.com/docs/en/api/errors> — "Request ID".

#### 4. REDACTION RISK

**Prompt/message content echoed back: no evidence in the docs.** Every documented error message
is a static or near-static string. The closest thing to an echo is a *structural path*, not
content — the thinking-block error is documented as being "prefixed by the position of the
offending block (for example, `messages.1.content.0`)". That leaks the *shape* of the request
(which message index, which content block index), not the text.
Source: <https://platform.claude.com/docs/en/api/errors>

**Key-shaped material: no evidence.** The 401 `authentication_error` description is
"There's an issue with your API key (for example, it's malformed, revoked, or expired)" — no
documented echo of the key or a prefix. The key travels in the `x-api-key` **header**, never in
the URL, so there is no URL-echo vector equivalent to Gemini's.
Source: <https://platform.claude.com/docs/en/api/errors>,
<https://platform.claude.com/docs/en/build-with-claude/streaming> (cURL examples use
`-H "x-api-key: $ANTHROPIC_API_KEY"`).

**Org / workspace / project id: not documented.** The 403 `permission_error` text is
"Your API key does not have permission to use the specified resource" — no id in the documented
message. Whether a real 403 embeds a workspace id is **not documented**.

**`request_id` is always present in error bodies** and is a stable per-request identifier. It is
not secret (Anthropic asks you to send it to support) but it *is* a correlator — if you forward
raw error bodies to an untrusted sink, you are forwarding request correlators.

Net: Anthropic is the **lowest-risk of the three** to log verbatim. Still, because the `type`
enum is explicitly open-ended and `message` is free text, a defensive integration should log
`error.type` + `request_id` and treat `error.message` as untrusted/possibly-echoing.

#### 5. Streaming (SSE) errors

Source: <https://platform.claude.com/docs/en/build-with-claude/streaming> — "Error events",
cross-referenced from <https://platform.claude.com/docs/en/api/errors>.

The key statement, verbatim from the errors page:

> When receiving a streaming response over server-sent events (SSE), an error can occur **after
> the API returns a 200 response**. In that case, error handling doesn't follow these standard
> mechanisms.

So there are two distinct regimes:

**Before the stream opens** — the request fails with an ordinary non-2xx HTTP status and the
standard JSON envelope from §2. Normal error handling applies.

**Mid-stream (after HTTP 200 + headers already flushed)** — the error arrives as a named SSE
event. Verbatim from the streaming docs:

```
event: error
data: {"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}
```

Note the mid-stream shape is the *same inner envelope* as the HTTP body — `{"type":"error",
"error":{"type","message"}}` — but with **no `request_id` field**. The docs describe this event
as carrying an error "which would normally correspond to an HTTP 529 in a non-streaming context",
i.e. the SSE `error.type` values are drawn from the same enum as the HTTP ones.

Practical consequence: a client that only checks `response.status` will treat an overloaded
stream as a success and silently return a truncated message. You must parse `event: error`
inside the stream loop.

The docs also instruct handling unknown event types gracefully:

> In accordance with the versioning policy, new event types may be added, and your code should
> handle unknown event types gracefully.

Recovery guidance (same page, "Error recovery"): capture the partial response, then for Claude
4.5 and earlier resume by putting the partial text in an assistant message; for Claude 4.6+ send
a **user** message instructing continuation instead. Tool-use and thinking blocks "cannot be
partially recovered" — only the most recent text block can be resumed from.

---

### Google Gemini (generativelanguage.googleapis.com REST)

**Important disambiguation.** As of 2026-07, `https://ai.google.dev/gemini-api/docs/api-errors`
serves the **new Interactions API** version of the page, which uses a *different* error format
(string `code`, no `status`, no `details`). The classic `generateContent` REST surface that this
task asks about is documented at a separate URL:

- generateContent (asked-about surface): <https://ai.google.dev/gemini-api/docs/generate-content/api-errors>
- Interactions API (newer, different shape): <https://ai.google.dev/gemini-api/docs/api-errors>

The generateContent page carries the banner: "Note: This version of the page covers the previous
generateContent API. We recommend using the new Interactions API…". Both are documented below
because a real client may hit either. **Do not write a parser that assumes `error.code` is an
integer** — it is an integer on generateContent and a snake_case string on Interactions.

#### 1. Conditions → status code + body (generateContent)

Verbatim table from <https://ai.google.dev/gemini-api/docs/generate-content/api-errors>:

| HTTP | Status | Description (verbatim) | Example cause (verbatim) |
|---|---|---|---|
| 400 | `INVALID_ARGUMENT` | The request body is malformed. | There is a typo, or a missing required field in your request. |
| 400 | `FAILED_PRECONDITION` | Gemini API free tier is not available in your country. Please enable billing on your project in Google AI Studio. | You are making a request in a region where the free tier is not supported, and you have not enabled billing on your project in Google AI Studio. |
| 403 | `PERMISSION_DENIED` | Your API key doesn't have the required permissions. | You are using the wrong API key; you are trying to use a tuned model without going through proper authentication. |
| 404 | `NOT_FOUND` | The requested resource wasn't found. | An image, audio, or video file referenced in your request was not found. |
| 429 | `RESOURCE_EXHAUSTED` | You've exceeded one of the API's rate limits (RPM, TPM, RPD, spend, etc.). | You are sending too many requests, using too many tokens, or exceeding spend-based limits for your account's billing history and tier. |
| 499 | `CANCELLED` | The operation was cancelled, typically by the caller. | The client closed the connection before the API could finish responding. |
| 500 | `INTERNAL` | An unexpected error occurred on Google's side. | Your input context is too long. |
| 503 | `UNAVAILABLE` | The service may be temporarily overloaded or down. | The service is temporarily running out of capacity. |
| 504 | `DEADLINE_EXCEEDED` | The service is unable to finish processing within the deadline. | Your prompt (or context) is too large to be processed in time. |

Mapping onto the requested conditions:

- **invalid API key / malformed key** → **400 `INVALID_ARGUMENT`** with `details[].reason ==
  "API_KEY_INVALID"`. This is the surprising one: Gemini returns **400, not 401**, for a bad key.
  Verbatim body below. Source: <https://ai.google.dev/gemini-api/docs/generate-content/api-errors>
- **expired or revoked key** → **not documented as a distinct status on the generateContent
  errors page.** The closest documented case is a *leaked/blocked* key, which returns the message
  `Your API key was reported as leaked. Please use another API key.` Source:
  <https://ai.google.dev/gemini-api/docs/troubleshooting> ("Blocked or non-working API keys").
  The HTTP status and `status` enum value for that message are **not documented**.
- **insufficient quota / no credit / billing not active** → split across two:
  **400 `FAILED_PRECONDITION`** for "free tier not available in your country, enable billing",
  and **429 `RESOURCE_EXHAUSTED`** for exceeding RPD/spend limits. The rate-limits page confirms
  spend caps also surface as 429: "If you hit a spend-based rate limit, the API returns a 429
  RESOURCE_EXHAUSTED error." Source: <https://ai.google.dev/gemini-api/docs/rate-limits>
- **rate limited** → **429 `RESOURCE_EXHAUSTED`**. Note Gemini does **not** separate rate limit
  from quota: RPM, TPM, RPD *and* spend limits all collapse into this one code. Source:
  <https://ai.google.dev/gemini-api/docs/generate-content/api-errors>,
  <https://ai.google.dev/gemini-api/docs/rate-limits>
- **unknown or unavailable model** → **not called out as its own row.** The documented 404
  `NOT_FOUND` example is about a missing media file, and 403 `PERMISSION_DENIED`'s example
  covers "trying to use a tuned model without going through proper authentication". A bad model
  id most plausibly lands on 404 `NOT_FOUND` (model is a resource in the URL path), but the
  generateContent errors page **does not document this explicitly**. The newer Interactions API
  *does* have a dedicated `model_not_found` code (see below), which is indirect evidence the
  concept exists but was previously unnamed.
- **malformed request (bad params, bad JSON)** → **400 `INVALID_ARGUMENT`**.
- **context length / max tokens exceeded** → **not a dedicated code.** Documented as a *cause*
  of two different codes: 500 `INTERNAL` ("Your input context is too long") and 504
  `DEADLINE_EXCEEDED` ("Your prompt (or context) is too large to be processed in time"). This is
  a real trap: an over-long prompt surfaces as a **5xx that looks retryable but is not**.
  Source: <https://ai.google.dev/gemini-api/docs/generate-content/api-errors>
- **server error / overloaded** → 500 `INTERNAL` and 503 `UNAVAILABLE` respectively.

##### Verbatim body — invalid API key (generateContent)

Source: <https://ai.google.dev/gemini-api/docs/generate-content/api-errors> — "Error response format"

```json
{
  "error": {
    "code": 400,
    "message": "API key not valid. Please pass a valid API key.",
    "status": "INVALID_ARGUMENT",
    "details": [
      {
        "@type": "type.googleapis.com/google.rpc.ErrorInfo",
        "reason": "API_KEY_INVALID",
        "domain": "googleapis.com",
        "metadata": {
          "service": "generativelanguage.googleapis.com"
        }
      },
      {
        "@type": "type.googleapis.com/google.rpc.LocalizedMessage",
        "locale": "en-US",
        "message": "API key not valid. Please pass a valid API key."
      }
    ]
  }
}
```

This is the only verbatim generateContent error body in the docs. Bodies for 429 / 503 / 500 are
**not documented verbatim**; only the envelope schema is.

#### 2. Envelope / field structure

Source: <https://ai.google.dev/gemini-api/docs/generate-content/api-errors>

> When a GenerateContent request fails, the API sets the HTTP status code (such as 400 Bad
> Request, 403 Forbidden, or 429 Too Many Requests) and returns a JSON response body containing
> gRPC status details

| Field | Type | Description (verbatim) |
|---|---|---|
| `code` | integer | The HTTP status code. |
| `message` | string | A human-readable description of the error. |
| `status` | string | The gRPC status code in SCREAMING_CASE. |
| `details` | array | Additional error context, such as `ErrorInfo` or `LocalizedMessage`. |

Confirmed: top-level envelope key is `error`; there is no `{"type":"error"}` wrapper.

Critically, `error.code` is the **HTTP** status, not the gRPC numeric code. Google's own AIP-193
states this explicitly: "the `code` field in the JSON is an HTTP status code, _not_ the direct
value of `google.rpc.Status.code`." Source: <https://google.aip.dev/193>. So `code: 400` +
`status: "INVALID_ARGUMENT"` is consistent (gRPC INVALID_ARGUMENT is numeric 3, which never
appears in the JSON).

##### Full documented enum of `status` values

`status` is the name of a `google.rpc.Code` member. AIP-193 defers the enumeration to the proto:
"The `code` field is the status code, which **must** be the numeric value of one of the elements
of the `google.rpc.Code` enum" (<https://google.aip.dev/193>). Complete enum, verbatim from the
canonical proto <https://github.com/googleapis/googleapis/blob/master/google/rpc/code.proto>:

```
OK = 0
CANCELLED = 1
UNKNOWN = 2
INVALID_ARGUMENT = 3
DEADLINE_EXCEEDED = 4
NOT_FOUND = 5
ALREADY_EXISTS = 6
PERMISSION_DENIED = 7
UNAUTHENTICATED = 16
RESOURCE_EXHAUSTED = 8
FAILED_PRECONDITION = 9
ABORTED = 10
OUT_OF_RANGE = 11
UNIMPLEMENTED = 12
INTERNAL = 13
UNAVAILABLE = 14
DATA_LOSS = 15
```

Of these 17, the Gemini generateContent errors page documents only 9 as reachable
(`INVALID_ARGUMENT`, `FAILED_PRECONDITION`, `PERMISSION_DENIED`, `NOT_FOUND`,
`RESOURCE_EXHAUSTED`, `CANCELLED`, `INTERNAL`, `UNAVAILABLE`, `DEADLINE_EXCEEDED`). The other 8
are **not documented as reachable on this API** but are structurally possible since the field is
typed as the full enum. `UNAUTHENTICATED` notably does **not** appear in Gemini's table — bad
keys go to `INVALID_ARGUMENT` instead.

##### `details[]` payload types

`details` is a list of `@type`-tagged Any values. Canonical set, verbatim message names from
<https://github.com/googleapis/googleapis/blob/master/google/rpc/error_details.proto>:
`ErrorInfo`, `RetryInfo`, `DebugInfo`, `QuotaFailure`, `PreconditionFailure`, `BadRequest`,
`RequestInfo`, `ResourceInfo`, `Help`, `LocalizedMessage`.

- `ErrorInfo` fields: `reason` (string), `domain` (string), `metadata` (map<string,string>).
- `RetryInfo` field: `retry_delay` (google.protobuf.Duration). Its proto comment: "Clients should
  wait until `retry_delay` amount of time has passed since [the error was received] … the delay
  between retries based on `retry_delay`, until either a maximum [number of retries is reached]".

AIP-193 lists `ErrorInfo` as required on all errors, plus `LocalizedMessage`, `Help`,
`BadRequest`, `PreconditionFailure` as standard payloads (<https://google.aip.dev/193>).

**Whether Gemini specifically emits `RetryInfo` or `QuotaFailure` on a 429 is not documented.**
The Gemini errors page names only `ErrorInfo` and `LocalizedMessage` as examples, and neither the
errors page nor the rate-limits page shows a 429 body. Do not depend on `RetryInfo` being present.

#### 3. Rate-limit / retry headers

**Gemini documents no rate-limit response headers at all.** Neither
<https://ai.google.dev/gemini-api/docs/rate-limits> nor
<https://ai.google.dev/gemini-api/docs/troubleshooting> nor
<https://ai.google.dev/gemini-api/docs/generate-content/api-errors> mentions `Retry-After`,
`X-RateLimit-*`, or any quota header. **Not documented.**

This is a genuine gap versus Anthropic: there is no documented way to read remaining quota, and
no documented server-supplied backoff hint. The docs instead prescribe **client-side** backoff:

> If you receive an error indicating that you should retry your request (such as a 429
> RESOURCE_EXHAUSTED or 503 UNAVAILABLE), we recommend implementing an exponential backoff
> strategy.
> — <https://ai.google.dev/gemini-api/docs/troubleshooting>

Documented client behavior and best practices from the same page:

- "the Python SDK automatically retries transient errors up to four times with an initial delay
  of approximately 1 second and a maximum delay of 60 seconds"
- "Use exponential backoff: Wait a short time before the first retry (for example, 1 second),
  then increase the delay exponentially (for example, 2s, 4s, 8s)."
- "Add jitter"
- "Retry on specific errors: Only retry on transient errors (like 429, 408, or 5xx). Do not retry
  on client errors (like 400 or 403) as they indicate issues like invalid API keys or bad syntax."
- "Set maximum retries"

Quota model, from <https://ai.google.dev/gemini-api/docs/rate-limits>: limits are RPM, TPM
(input), RPD; "Rate limits are applied **per project, not per API key**"; "Requests per day (RPD)
quotas reset at midnight Pacific time." Spend-based limits are evaluated on a rolling 10-minute
window (Free: N/A, Tier 1: $10, Tier 2: $200, Tier 3: $200).

Note the interaction with the retry advice: **a bad API key returns 400, and the advice says
never retry a 400.** That is correct here but only by accident of the mapping — a client that
retries 400s will hammer the API with a dead key.

#### 4. REDACTION RISK

This is the provider with the highest structural risk, because the API key travels in the URL.

**Does any documented error surface the URL or the `key=` query param?**
No. The one verbatim error body (above) contains no URL, no `key=`, and no key prefix. The only
identifier in `details[].metadata` is `"service": "generativelanguage.googleapis.com"` — a
service name, not a project or key. Source:
<https://ai.google.dev/gemini-api/docs/generate-content/api-errors>

**However — and this is the load-bearing caveat — the docs never state that the URL is
*excluded*.** The `details[]` array is typed as an open list of `Any`, and the canonical set
includes `RequestInfo` and `DebugInfo`
(<https://github.com/googleapis/googleapis/blob/master/google/rpc/error_details.proto>), either
of which could in principle carry request-identifying material. Whether Gemini ever emits those
is **not documented**. So: absence of a URL in the single documented example is *evidence*, not
a *guarantee*. Treat "Gemini never echoes the key" as unverified.

The realistic leak vector is not the response body but the **request URL itself** — with
`?key=AIza...`, the secret lands in client-side exception messages, HTTP client logs, proxy access
logs, and any crash reporter that captures the request URL alongside the error. The docs do not
address this. Mitigation supported by the docs: send the key in the `x-goog-api-key` header
instead of the query string — though note the API-key-setup guidance is on a separate page and
the errors page itself is silent. **Not documented** on the error pages.

**Prompt / message content echoed back:** no evidence in the documented bodies. But the
Interactions API sibling page *does* show a `message` that echoes a request value verbatim
(`"The value 'invalid_tool_type_xyz' is not supported for 'type' at 'tools[0]'"`) — see below.
That is a request-supplied string reflected into the error message. If the generateContent
surface behaves similarly for `INVALID_ARGUMENT`, **error messages can contain request-derived
strings**, which for a validation failure on a user-controlled field can be user text. Assume
`error.message` is potentially-echoing.

**Project / org id:** not present in the documented example. **Not documented** whether real 403
`PERMISSION_DENIED` bodies embed a project number (Google APIs commonly do, e.g.
"project 123456789012 has not enabled…", but that is not documented for this API — do not rely
on either behavior).

**Blocked/leaked keys:** Google states it proactively blocks known-leaked keys, and returns the
message `Your API key was reported as leaked. Please use another API key.`
(<https://ai.google.dev/gemini-api/docs/troubleshooting>). The message itself contains no key
material. This confirms Google *does* fingerprint keys server-side, which is only meaningful
because keys leak via URLs.

#### 5. Streaming (SSE) errors

**For generateContent / streamGenerateContent: not documented.** The generateContent errors page
has no streaming section; it documents only "the API sets the HTTP status code … and returns a
JSON response body". There is no documented mid-stream error event format for `alt=sse`
streaming on this surface.

**For the newer Interactions API it *is* documented** —
<https://ai.google.dev/gemini-api/docs/api-errors>, "How errors are delivered":

> For streaming requests (`stream: true`), the API sends error events over the Server-Sent Events
> (SSE) stream with `event_type` set to `"error"`. The error field contains the same code and
> message structure

```json
{
  "event_type": "error",
  "error": {
    "code": "not_found",
    "message": "Failed to get completed interaction: Result not found."
  }
}
```

Note this uses the **Interactions envelope** (string `code`), not the gRPC one.

Pre-stream failures on both surfaces are ordinary non-2xx HTTP responses with the JSON body.

#### Appendix — Interactions API error format (the *other* Gemini shape)

Source: <https://ai.google.dev/gemini-api/docs/api-errors>. Included because the default
`/gemini-api/docs/api-errors` URL now serves this, and it is easy to cite by mistake.

Envelope — flat, string code, **no `status`, no `details`, no numeric code**:

```json
{
  "error": {
    "code": "invalid_request",
    "message": "The value 'invalid_tool_type_xyz' is not supported for 'type' at 'tools[0]'. Supported values: 'function', 'code_execution', 'mcp_server', 'filesystem', 'google_maps', 'google_search', 'bash', 'computer_use', 'file_search', 'url_context'."
  }
}
```

Fields (verbatim): `code` — "A machine-readable error code in snake_case"; `message` — "A
human-readable description of what went wrong."

Full documented standard code table:

| Code | HTTP | Description (verbatim) |
|---|---|---|
| `invalid_request` | 400 | The request is malformed or contains invalid parameters. |
| `parameter_unknown` | 400 | The request contains an unknown parameter. |
| `authentication` | 401 | The API key is missing or invalid. |
| `permission_denied` | 403 | Your API key does not have permission for this resource. |
| `not_found` | 404 | The requested resource was not found. |
| `model_not_found` | 404 | The specified model was not found. |
| `rate_limit_exceeded` | 429 | You have exceeded the per-minute or per-second request or token limit. |
| `quota_exceeded` | 429 | You have exceeded your daily quota. |
| `cancelled` | 499 | The client cancelled the request before it completed. |
| `api_error` | 500 | An unexpected error occurred on the server. |
| `service_unavailable` | 503 | The service is temporarily overloaded or down. |

Explicit open-world note: "The API returns any error code not listed above as a snake_case form
of the standard HTTP status text."

This surface fixes several generateContent oddities: bad key is **401 `authentication`** (not
400), unknown model has a **dedicated `model_not_found`**, and rate-limit is **separated from
daily quota** (`rate_limit_exceeded` vs `quota_exceeded`, both 429).

Two additional enum families documented only here — these are *not* HTTP errors but generation
outcomes:

- **Generation blocked codes** ("policy, safety, or content restrictions blocked the model's
  output"): `safety`, `recitation`, `language`, `prohibited_content`, `spii`, `blocklist`,
  `image_safety`, `image_prohibited_content`, `image_recitation`, `image_other`,
  `content_blocked`.
- **Generation error codes** ("a structural issue with the model's generated output"):
  `malformed_function_call`, `malformed_tool_call`, `unexpected_tool_call`, `no_image`,
  `too_many_tool_calls`, `missing_thought_signature`.

**Redaction note for this surface:** the documented example message echoes a request-supplied
string verbatim (`'invalid_tool_type_xyz'`). Confirmed echo of request content into
`error.message`.

---

### Cohere

Canonical sources:
- <https://docs.cohere.com/reference/errors> (status code catalogue, with real example messages)
- <https://docs.cohere.com/reference/chat> — the OpenAPI spec, available as machine-readable
  markdown at <https://docs.cohere.com/reference/chat.md>, which defines the error schemas

Cohere is the **least structured** of the three: there is no error code enum at all. The body is
a flat `{message, id}` and every distinction is carried in free-text English prose.

#### 1. Conditions → status code + body

From the OpenAPI `responses` block for `POST https://api.cohere.com/v2/chat`
(<https://docs.cohere.com/reference/chat.md>), the full documented status set is:
**400, 401, 403, 404, 422, 429, 498, 499, 500, 501, 503, 504.**

Verbatim OpenAPI descriptions:

| HTTP | Schema | Description (verbatim) |
|---|---|---|
| 400 | `Chatv2RequestBadRequestError` | "This error is returned when the request is not well formed. This could be because: - JSON is invalid - The request is missing required fields - The request contains an invalid combination of fields" |
| 401 | `Chatv2RequestUnauthorizedError` | "This error indicates that the operation attempted to be performed is not allowed. This could be because: - The api token is invalid - The user does not have the necessary permissions" |
| 403 | `Chatv2RequestForbiddenError` | (same text as 401) |
| 404 | `Chatv2RequestNotFoundError` | "This error is returned when a resource is not found. This could be because: - The endpoint does not exist - The resource does not exist eg model id, dataset id" |
| 422 | `Chatv2RequestUnprocessableEntityError` | (same text as 400) |
| 429 | `Chatv2RequestTooManyRequestsError` | "Too many requests" |
| 498 | `Chatv2RequestInvalidTokenError` | "This error is returned when a request or response contains a deny-listed token." |
| 499 | `Chatv2RequestClientClosedRequestError` | "This error is returned when a request is cancelled by the user." |
| 500 | `Chatv2RequestInternalServerError` | "This error is returned when an uncategorised internal server error occurs." |
| 501 | `Chatv2RequestNotImplementedError` | "This error is returned when the requested feature is not implemented." |
| 503 | `Chatv2RequestServiceUnavailableError` | "This error is returned when the service is unavailable. This could be due to: - Too many users trying to access the service at the same time" |
| 504 | `Chatv2RequestGatewayTimeoutError` | "This error is returned when a request to the server times out. This could be due to: - An internal services taking too long to respond" |

Note **402 is documented on the errors page but absent from the chat OpenAPI responses list** —
the two primary sources disagree on coverage. Trust the errors page for 402's existence.

Mapping onto the requested conditions, with **verbatim example messages** from
<https://docs.cohere.com/reference/errors>:

**invalid API key / malformed key → 401.** Page text: "401 responses are sent when the API key is
missing, invalid or has expired." Verbatim example messages:
- `no api key supplied`
- `invalid api token`

**expired or revoked key → 401**, same bucket, but with a distinct message. Verbatim:
- `Your API key has expired. Please create a production key at dashboard.cohere.com or reach out to your contact at Cohere to continue usage.`

Cohere is the only one of the three with an explicitly documented *expired-key* message string.
Because there is no code field, **distinguishing expired from invalid requires substring
matching on English prose** — brittle, but there is no alternative.

**insufficient quota / no credit / billing not active → 402.** Page text: "402 responses are sent
when the account has reached its billing limit. To resolve these errors, add or update a payment
method." Verbatim examples:
- `Please add or update your payment method at https://dashboard.cohere.com/billing?tab=payment to continue`
- `Maximum billing reached for this API key as set in your dashboard, please go to https://dashboard.cohere.com/billing?tab=payment to increase your maximum amount to continue using this API key. Your billing capacity will reset at the beginning of next month.`

**rate limited → 429.** Verbatim examples:
- `You are past the per minute request limit, please wait and try again later.`
- `You are using a Trial key, which is limited to 40 API calls / minute. You can continue to use the Trial key for free or upgrade to a Production key with higher rate limits at 'https://dashboard.cohere.com/api-keys'. Contact us on 'https://discord.gg/XW44jPfYJu' or email us at support@cohere.com with any questions`
- `Please wait and try again later`
- `trial token rate limit exceeded, limit is 100000 tokens per minute`

**unknown or unavailable model / no access → 404.** Verbatim examples:
- `model 'xyz' not found, make sure the correct model ID was used and that you have access to the model.`
- `finetuned model xyz not found`
- `404 page not found`
- `connector 'web-search' not found.`
- `dataset with id my-dataset-id not found`
- `resource not found: no messages found with conversation id models`
- `failed to find org by org id`

Note the model-not-found message **conflates "wrong id" and "no access"** into one string, so a
client cannot distinguish a typo from an entitlement problem. Also note a *different* class of
model error is a **400**, not 404: `invalid request: model 'command-r' is not supported by the
generate API`.

**malformed request (bad params, bad JSON, context/max_tokens exceeded) → 400.** All three
sub-cases collapse here. Verbatim examples of each flavor:

*Bad JSON:*
- `invalid json syntax: invalid character '\a' in string literal`

*Bad params:*
- `invalid request: temperature must be between 0 and 1.0 inclusive.`
- `invalid request: presence_penalty must be between 0 and 1 inclusive.`
- `invalid request: cannot specify both frequency_penalty and presence_penalty.`
- `invalid request: Invalid role in chat_history at index 2. Role must be one of the following: User, Chatbot, System, Tool`
- `invalid request: total number of texts must be at most 96 - received 104`
- `invalid request: return_top_n is invalid, value must be between 1 and 4`
- `invalid request: list of documents must not be empty`
- `embedding_types parameter is required`
- `a model parameter is required for this endpoint.`

*Context length / max tokens exceeded — note the distinctive `too many tokens:` prefix:*
- `too many tokens: total number of tokens in the prompt cannot exceed 4081 - received 4292. Try using a shorter prompt, or enabling prompt truncating. See https://docs.cohere.com/reference/generate for more details.`
- `too many tokens: max tokens must be less than or equal to 4096, the maximum output for this model - received 8192.`
- `too many tokens: size limit exceeded by 11326 tokens. Try using shorter or fewer inputs, or setting prompt_truncation='AUTO'.`
- `too many tokens: minimal context could not be added to prompt (size limit exceeded by 280 tokens)`
- `too many tokens: multi-hop prompt is too long even after truncation`

The `too many tokens:` prefix is the closest thing Cohere has to a machine-readable context-length
code. Similarly `invalid request:` prefixes most validation failures. Both are conventions
observable across the documented examples, **not a documented contract** — Cohere never states
these prefixes are stable.

**server error / overloaded → 500 / 503.** Errors page for 500: "500 responses are sent when there
is an unexpected internal server error. To resolve these errors, please contact support via email
or discord with details about your request and use case." **No example messages are given for
500.** 503 exists in the OpenAPI ("Too many users trying to access the service at the same time")
but has **no section on the errors page and no example messages** — not documented.

**request cancelled → 499.** Verbatim examples: `request cancelled`, `request cancelled by user`,
`failed to get rerank inference: request cancelled`, and notably `streaming error - scroll down
for more streaming errors`.

#### 2. Envelope / field structure

Verbatim from the OpenAPI components in <https://docs.cohere.com/reference/chat.md> — every one
of the twelve `Chatv2Request*Error` schemas is byte-identical in shape:

```yaml
Chatv2RequestUnauthorizedError:
  type: object
  properties:
    message:
      type: string
    id:
      type: string
  title: Chatv2RequestUnauthorizedError
```

So the wire body is:

```json
{
  "message": "invalid api token",
  "id": "<request id>"
}
```

**There is no top-level envelope key.** No `error` wrapper, no `type`, no `code`, no `status`.
This is the sharpest structural difference from both Anthropic and Gemini: a single generic
JSON parser cannot handle all three.

Neither property is marked `required` in the schema, so `id` may be absent. The schema gives no
description for either field.

**Full documented enum of code/type values: none exists.** There is no code field to enumerate.
Discrimination is: HTTP status first, then substring match on `message`.

Corroboration from the official SDK — the generated Python client types the error body as
untyped `Any`, confirming there is no parsed error model:

```python
class ApiError(Exception):
    headers: Optional[Dict[str, str]]
    status_code: Optional[int]
    body: Any
```
— <https://github.com/cohere-ai/cohere-python/blob/main/src/cohere/core/api_error.py>

with per-status subclasses that add nothing but the status code:

```python
class TooManyRequestsError(ApiError):
    def __init__(self, body: typing.Any, headers: typing.Optional[typing.Dict[str, str]] = None):
        super().__init__(status_code=429, headers=headers, body=body)
```
— <https://github.com/cohere-ai/cohere-python/blob/main/src/cohere/errors/too_many_requests_error.py>

(Both files are marked "auto-generated by Fern from our API Definition", i.e. they are a faithful
projection of the OpenAPI spec, not hand-written.)

The one adjacent enum that *is* documented is `ChatFinishReason`, verbatim from
<https://docs.cohere.com/reference/chat.md>:

```yaml
ChatFinishReason:
  type: string
  enum:
    - COMPLETE
    - STOP_SEQUENCE
    - MAX_TOKENS
    - TOOL_CALL
    - ERROR
    - TIMEOUT
```

with descriptions: "**error**: The generation failed due to an internal error"; "**timeout**: The
generation was stopped because it exceeded the allowed time limit"; "**max_tokens**: The number of
generated tokens exceeded the model's context length or the value specified via the `max_tokens`
parameter." `finish_reason` is a **required** field of the chat response.

This matters: a Cohere call can return **HTTP 200 with `finish_reason: "ERROR"`**. Status-code-only
error handling will miss it.

#### 3. Rate-limit / retry headers

**Not documented.** Neither <https://docs.cohere.com/reference/errors> nor
<https://docs.cohere.com/docs/rate-limits> nor the chat OpenAPI documents any `Retry-After` or
`X-RateLimit-*` response header. The rate-limits page documents only the *limits themselves*
(trial vs production keys, per-model and per-endpoint tables) and directs users to
`support@cohere.com` for increases.

The SDK's `ApiError` does capture `headers: Optional[Dict[str, str]]`
(<https://github.com/cohere-ai/cohere-python/blob/main/src/cohere/core/api_error.py>), so headers
are surfaced to callers — but which headers exist is **not documented**.

Retry guidance on the errors page is prose only: 429 → "Please consult the rate limit
documentation to understand the limits and how to avoid these errors"; 499 → "try the request
again"; 500 → "contact support". No backoff algorithm is specified, unlike Gemini and Anthropic.

The documented limits themselves are the only quantitative signal available:
"Trial keys (and prod keys on newer Chat model variants) are limited to 1,000 API calls a month",
and the 429 message text discloses `40 API calls / minute` for trial keys and `100000 tokens per
minute` for trial token limits (<https://docs.cohere.com/docs/rate-limits>,
<https://docs.cohere.com/reference/errors>).

#### 4. REDACTION RISK

**Cohere is the highest-confirmed-risk of the three for content echo.** Unlike the other two,
this is not speculative — the official error catalogue itself publishes example messages that
contain request-derived values.

**Confirmed echo of request content into `message`** — all verbatim from
<https://docs.cohere.com/reference/errors>:

- `invalid json syntax: invalid character '\a' in string literal` — echoes a fragment of the
  raw request body, i.e. **potentially prompt bytes**, on a parse failure.
- `invalid request: tool names can only contain certain characters (A-Za-z0-9_) and can't begin with a digit (provided name: 'xyz').` — echoes a caller-supplied identifier verbatim.
- `invalid request: duplicate document ID adfasd at index 1 and 0` — echoes a caller-supplied document id.
- `invalid request: model 'command-r' is not supported by the generate API` and `model 'xyz' not found…` — echo the model id.
- `finetuneID is not a valid UUID: ''` — echoes the supplied value.
- `invalid request: each unique label must have at least 2 examples. Not enough examples for: awr_report, create_user, tablespace_usage` — echoes **caller-supplied label strings**, which are user data.
- `connectors failed with continue on failure disabled: connector xyz failed with message 'failed to get auth token: user is not authenticated for connector xyz'` — echoes a nested upstream error, including a connector name.
- `dataset with id my-dataset-id not found`, `finetuned model with name xyz is not ready for serving` — echo caller-supplied resource names.

Several messages also **echo exact token counts derived from the prompt** (`received 4292`,
`size limit exceeded by 11326 tokens`, `size limit exceeded by 280 tokens`). Token counts are not
content, but they are a side channel on prompt size.

Whether the *prompt text itself* is ever echoed is **not documented** — no documented example
contains prompt prose. But given that raw-JSON fragments and arbitrary caller-supplied strings
(labels, ids, tool names) do appear, the safe assumption is that `message` may contain arbitrary
request-derived data. **Do not forward Cohere `message` values to untrusted sinks unredacted.**

**Key-shaped material:** no documented example contains an API key, a key prefix, or an org id in
the message. The 401 messages are the fixed strings `no api key supplied` / `invalid api token`.
The key travels in the `Authorization: bearer` **header** (per the chat OpenAPI: `name:
Authorization, in: header, description: Bearer authentication, required: true` —
<https://docs.cohere.com/reference/chat.md>), so there is no URL-echo vector.

One caveat: `failed to find org by org id` (a 404 example) implies org ids flow through error
paths, though the documented string does not interpolate the id. Whether a real response
interpolates it is **not documented**.

The 402 messages embed dashboard URLs (`https://dashboard.cohere.com/billing?tab=payment`) and the
429 trial message embeds a Discord invite and support email. These are static Cohere URLs, not
tenant-specific — low risk, but they mean error strings are not safe to treat as opaque codes.

#### 5. Streaming (SSE) errors

Cohere documents streaming event types but **does not document a dedicated error event** in
either API version.

**v2 (current)** — <https://docs.cohere.com/docs/streaming>. Documented event types:
`message-start`, `content-start`, `content-delta`, `content-end`, `message-end`, plus
`citation-start` / `citation-end` for RAG and `tool-plan-delta` / `tool-call-start` /
`tool-call-delta` for tool use. **No `error` event type is documented.** The error signal is
instead the `finish_reason` field on the response (`ERROR` or `TIMEOUT` — see the
`ChatFinishReason` enum in §2), which is a **required** field of `v2_chat_Response_stream`
(<https://docs.cohere.com/reference/chat.md>).

**v1 (legacy)** — <https://docs.cohere.com/v1/docs/streaming>. Verbatim:

> A `stream-end` event is the final event of the stream, and is returned only when streaming is
> finished. This event contains aggregated data from all the other events such as the complete
> `text`, as well as a `finish_reason` to indicate why the stream ended (i.e. **either because it
> finished or due to an error**).

So in v1 the mid-stream error also arrives as a terminal `stream-end` event carrying a
`finish_reason`, not as a distinct error event.

**Before the stream opens:** ordinary non-2xx HTTP with the flat `{message, id}` body. The chat
endpoint returns `Content-Type: text/event-stream` only on success; the OpenAPI declares the
error responses as `application/json` (<https://docs.cohere.com/reference/chat.md>).

**Mid-stream, verbatim event shape: not documented.** No primary source shows an SSE frame
carrying an error payload. The only hint is a 499 example message on the errors page reading
`streaming error - scroll down for more streaming errors`
(<https://docs.cohere.com/reference/errors>) — which appears to be a docs-site artifact rather
than a real API string, and the referenced "more streaming errors" section does not exist on the
page as served. Treat mid-stream error detail as **undocumented**; detect via
`finish_reason in {ERROR, TIMEOUT}` and via stream truncation.

---

### Cross-provider summary

#### Envelope shapes — mutually incompatible

| Provider | Top-level key | Code field | Message field | Status field | Correlator |
|---|---|---|---|---|---|
| Anthropic | `error` (plus constant `type: "error"` sibling) | `error.type` (string enum, open) | `error.message` | — (HTTP status only) | `request_id` (body + `request-id` header) |
| Gemini generateContent | `error` | `error.code` (**integer**, = HTTP status) | `error.message` | `error.status` (SCREAMING_CASE `google.rpc.Code`) | — (**not documented**) |
| Gemini Interactions | `error` | `error.code` (**string**, snake_case) | `error.message` | — | — |
| Cohere | **none — flat object** | — (**no code field at all**) | `message` | — | `id` |

#### Same condition, different codes

| Condition | Anthropic | Gemini generateContent | Gemini Interactions | Cohere |
|---|---|---|---|---|
| invalid key | 401 `authentication_error` | **400** `INVALID_ARGUMENT` / `API_KEY_INVALID` | 401 `authentication` | 401 (prose) |
| expired/revoked key | 401 `authentication_error` | not documented | not documented | 401 (distinct prose string) |
| billing / no credit | **402** `billing_error` | 400 `FAILED_PRECONDITION` | not documented | **402** (prose) |
| rate limit | 429 `rate_limit_error` | 429 `RESOURCE_EXHAUSTED` | 429 `rate_limit_exceeded` | 429 (prose) |
| daily quota | 429 `rate_limit_error` | 429 `RESOURCE_EXHAUSTED` | 429 `quota_exceeded` | 429 / 402 |
| unknown model | 404 `not_found_error` (no dedicated type) | not documented (likely 404 `NOT_FOUND`) | 404 `model_not_found` | 404 (prose) |
| context too long | 400 `invalid_request_error` | **500 `INTERNAL` / 504 `DEADLINE_EXCEEDED`** | not documented | 400 (`too many tokens:` prefix) |
| overloaded | **529** `overloaded_error` | 503 `UNAVAILABLE` | 503 `service_unavailable` | 503 |

Three traps worth calling out:

1. **Anthropic's 529** is non-standard; generic HTTP clients and retry middleware often do not
   classify it as retryable.
2. **Gemini returns 400 for a bad API key**, colliding with its own documented advice to never
   retry a 400 — correct here, but it also means bad-key and bad-JSON are the same status.
3. **Gemini reports an over-long prompt as 5xx**, which will be retried forever by a naive
   exponential-backoff client that treats 5xx as transient.

#### Retry signalling

- **Anthropic**: rich. `retry-after` plus 18 `anthropic-ratelimit-*` / `anthropic-priority-*`
  headers. SDKs auto-retry twice honoring `retry-after`.
- **Gemini**: no headers documented at all. Client-side exponential backoff + jitter prescribed
  in prose; Python SDK retries 4x, ~1s initial, 60s max. `RetryInfo` in `details[]` is
  structurally possible but **not documented as emitted**.
- **Cohere**: no headers documented, no backoff algorithm documented. Prose only.

#### Streaming error delivery

- **Anthropic**: fully specified. Named SSE event, verbatim shape
  `event: error` / `data: {"type": "error", "error": {"type": "overloaded_error", "message": "Overloaded"}}`.
  Explicit warning that mid-stream errors arrive **after** a 200.
- **Gemini**: documented for Interactions (`{"event_type": "error", "error": {...}}`),
  **not documented** for generateContent/streamGenerateContent.
- **Cohere**: **no error event documented** in either version. Signalled out-of-band via
  `finish_reason ∈ {ERROR, TIMEOUT}` on an otherwise-200 response.

All three share the same hazard: **a streaming request can fail after HTTP 200**. Checking only
`response.status` yields silently truncated output on every one of them.

#### Redaction risk ranking

1. **Cohere — highest, and confirmed.** The official error catalogue publishes example messages
   echoing raw JSON fragments, caller-supplied labels, document ids, tool names, model ids, and
   exact prompt token counts. Assume `message` may contain arbitrary request-derived data.
2. **Gemini — highest structural risk, unconfirmed body echo.** No documented error body contains
   a URL, key, or key prefix; the only `details[].metadata` value is the service name. But the key
   travels in `?key=`, so it lands in client logs, proxy logs, and crash reports via the request
   URL — a vector the docs never address. Additionally the Interactions-API example message
   verbatim echoes a request value (`'invalid_tool_type_xyz'`), so message echo is confirmed on at
   least one Gemini surface. Mitigate by sending the key as `x-goog-api-key`.
3. **Anthropic — lowest.** Documented messages are static strings; the only request-derived
   content is a *structural path* (`messages.1.content.0`), not text. Key is header-only. Bodies
   do carry `request_id`, a request correlator.

For all three, none of the docs contain an explicit guarantee that error messages exclude prompt
content or credentials. Google's own AIP-193 (<https://google.aip.dev/193>) — the design guide
governing the Gemini envelope — contains **no guidance about excluding sensitive data or PII from
error messages**. The correct posture is to log `(HTTP status, provider, code/type if any,
correlator)` and to redact or truncate `message` before it leaves your trust boundary.

---

## Error contracts: OpenRouter, Together AI, GitHub Copilot, arbitrary OpenAI-compatible endpoints

Research date: 2026-07-28. Primary sources only (official provider docs, official SDK/server source on GitHub).
Anything not found in a primary source is marked **not documented** rather than guessed.

### Baseline: the OpenAI shape everything is measured against

OpenAI's canonical error envelope is:

```json
{"error": {"message": "...", "type": "...", "param": null, "code": null}}
```

- `type` is a string enum-ish value (`invalid_request_error`, `insufficient_quota`, …), `code` is a **string** or null, `param` is the offending request field or null. Source: <https://platform.openai.com/docs/guides/error-codes>
- The two deviations that break naive clients most often, seen below, are (a) `code` being an **integer** instead of a string, and (b) `error` being a **string** instead of an object.

---

### OpenRouter

Primary source for this whole section: <https://openrouter.ai/docs/api-reference/errors> (fetched as `https://openrouter.ai/docs/api-reference/errors.md`).

#### 1. Body shape and status codes

OpenRouter's own documented envelope — note there is **no `type` and no `param`**, and `code` is a **number**:

```typescript
type ErrorResponse = {
  error: {
    code: number;
    message: string;
    metadata?: Record<string, unknown>;
  };
};
```

"The HTTP Response will have the same status code as `error.code`" — but only "forming a request error if: Your original request is invalid / Your API key/account is out of credits. Otherwise, the returned HTTP response status will be `200 OK` and any error occurred while the LLM is producing the output will be emitted in the response body or as an SSE data event." (same page). **This is the single most important OpenRouter behaviour for a client: a 200 OK does not mean success.**

Documented code list (verbatim from the "Error codes" section):

| Status | Documented meaning | Maps to which of your cases |
|---|---|---|
| 400 | "Bad Request (invalid or missing params, CORS)" | malformed request |
| 401 | "Invalid credentials (OAuth session expired, disabled/invalid API key)" | invalid key **and** expired/revoked key — OpenRouter does **not** distinguish these; both are 401 / `error_type: authentication`, described as "The API key is missing, invalid, or revoked." |
| 402 | "Your account or API key has insufficient credits. Add more credits and retry the request." | insufficient quota/credit |
| 403 | "Forbidden (insufficient permissions, guardrail block, or moderation flag)" | policy |
| 408 | "Your request timed out" | — |
| 429 | "You are being rate limited" | rate limited |
| 502 | "Your chosen model is down or we received an invalid response from it" | upstream failure |
| 503 | "There is no available model provider that meets your routing requirements" | no provider |

Unknown/unavailable model: the typed code is `not_found` → **404** ("The requested resource (model, file, etc.) does not exist"), per the Request validation table on the same page. Server error: `server` → **500**, and `unmapped` → **500**.

Concrete documented 403 body (guardrail block):

```json
{
  "error": {
    "code": 403,
    "message": "Request blocked: prompt injection patterns detected",
    "metadata": { "patterns": ["ignore all previous instructions"] }
  }
}
```

#### 1b. Layer two — upstream provider errors passed through

OpenRouter documents that it "normalizes every upstream provider error into the stable, typed `error_type` vocabulary". The upstream raw code lands in **`error.metadata.provider_code`**; the normalized code lands in **`error.metadata.error_type`** (Chat Completions skin). Documented example:

```json
{
  "error": {
    "code": 429,
    "message": "Rate limit exceeded",
    "metadata": {
      "error_type": "rate_limit_exceeded",
      "provider_code": "rate_limited"
    }
  }
}
```

Masking rule, verbatim: "When a request fails with a `500`, the `message` is replaced with a generic string and `provider_code` and `openrouter_metadata` are omitted, but `error_type` is still present (`server`)." For non-500s, "the upstream provider's own error code is surfaced in `error.metadata.provider_code` when available."

Full typed `error_type` vocabulary documented on that page (use this, not the HTTP status, to branch):

- Token/length: `context_length_exceeded` (400), `max_tokens_exceeded` (400), `token_limit_exceeded` (400), `string_too_long` (400)
- Auth: `authentication` (401), `permission_denied` (403), `payment_required` (402)
- Availability: `rate_limit_exceeded` (429), `provider_overloaded` (503), `provider_unavailable` (502)
- Validation: `invalid_request` (400), `invalid_prompt` (400), `not_found` (404), `precondition_failed` (412), `payload_too_large` (413), `unprocessable` (422)
- Content policy: `content_policy_violation` (400), `refusal` (400)
- Image: `invalid_image`, `image_too_large`, `image_too_small`, `unsupported_image_format` (400), `image_not_found` (404), `image_download_failed` (400)
- Generic: `server` (500), `timeout` (504), `unmapped` (500)

Where the error lands per API skin (all documented on the same page):

- **Chat Completions**: `error.metadata.error_type`.
- **Anthropic Messages** (`/api/v1/messages`): `error.error_type`, alongside a lossy native `error.type` — e.g. `{"type":"error","error":{"type":"authentication_error","message":"Invalid credentials","error_type":"authentication"}}`.
- **Responses** (`/api/v1/responses`): **top-level** `error_type`, outside the native `error` object — e.g. `{"id":"resp_abc123","status":"failed","error":{"code":"server_error","message":"Invalid credentials"},"error_type":"authentication"}`.

#### 1c. Errors inside a 200 response

Non-streaming provider failure — the error is nested **inside `choices[0]`**, not at the top level:

```json
{"choices": [{
  "message": {"role": "assistant", "content": "partial output..."},
  "finish_reason": "error",
  "error": {"code": 502, "message": "Provider disconnected mid-stream",
            "metadata": {"error_type": "provider_unavailable"}}
}]}
```

Mid-stream SSE failure — top-level `error` on a `chat.completion.chunk`, HTTP status already committed as 200:

```text
data: {"id":"gen-abc123","object":"chat.completion.chunk","created":1234567890,"model":"openai/gpt-4o","provider":"OpenAI","error":{"code":429,"message":"Rate limit exceeded","metadata":{"error_type":"rate_limit_exceeded"}},"choices":[{"index":0,"delta":{"content":""},"finish_reason":"error"}]}
```

Note the Responses-skin transformation table: `context_length_exceeded`, `max_tokens_exceeded`, `token_limit_exceeded` and `string_too_long` are **converted into successful completions** with `finish_reason: "length"` rather than errors.

#### 2. Deviation from OpenAI

Substantial. `type` and `param` are **absent** from OpenRouter's own envelope; `code` is an **integer HTTP status**, not an OpenAI string code. A client written against `error.code === "insufficient_quota"` will silently fail. The OpenAI-shaped fields only reappear on the Responses skin (`error.code` string) and Anthropic skin (`error.type`).

#### 3. Rate-limit / retry headers

Only `Retry-After` is documented: "On `429` and `503` responses, OpenRouter **may** include a standard HTTP `Retry-After` response header indicating how many seconds to wait before retrying." Documented example: `HTTP/1.1 429 Too Many Requests` + `Retry-After: 60`. No `x-ratelimit-*` headers are documented on the errors page — **not documented**. Note the "may": presence is not guaranteed, so a client must have a fallback backoff.

#### 4. Redaction risk — HIGH, and it is documented

- **Moderation errors echo your prompt.** `error.metadata` on a moderation block contains `flagged_input: string` — "The text segment that was flagged, limited to 100 characters. If the flagged input is longer than 100 characters, it will be truncated in the middle and replaced with `...`". Plus `reasons: string[]`, `provider_name`, `model_slug`. So up to 100 chars of **user prompt content** are inside the error body. If a user pastes a key into a prompt and it trips moderation, that key fragment can come back in the error.
- **Guardrail errors echo matched patterns.** `error.metadata.patterns` contains the matched strings, e.g. `["ignore all previous instructions"]`.
- **Upstream metadata: yes.** A passed-through upstream error carries `error.metadata.provider_code` (upstream's native code) and, on moderation, `provider_name` + `model_slug`. With the opt-in `X-OpenRouter-Experimental-Metadata: enabled` header, the error body additionally carries a full `openrouter_metadata` object naming the requested model, `strategy`, `region`, `is_byok`, and an `endpoints.available[]` array listing provider names — i.e. **routing topology leaks into error bodies when that header is set**. Do not set it in production logging paths.
- **`debug.echo_upstream_body` echoes the entire transformed request** (system prompt, messages) back in the stream. OpenRouter's own warning: "The debug flag should **not** be used in production environments… it may potentially return sensitive information included in the request." It says only that it "will make a best effort to automatically redact potentially sensitive or noisy data" — best-effort, not guaranteed.
- **Key-shaped content**: the docs do not state that OpenRouter redacts API keys from error messages — **not documented**. Assume it does not.

---

### Together AI

#### 1. Status codes and body shape

Documented error code list: <https://docs.together.ai/docs/error-codes> — 400 Invalid Request, 401 Authentication Error, 402 Payment Required, 403 Bad Request, 404 Not Found, 429 Rate limit, 500 Server Error, 503 Engine Overloaded, 504 Timeout, 524 Cloudflare Timeout, 529 Server Error.

That page is a **prose troubleshooting table only — it contains no JSON examples and no body schema**. The body shape has to come from the API reference instead.

The chat-completions API reference (<https://docs.together.ai/reference/chat-completions-1>) documents a single shared `ErrorData` schema across every error status, and it is **the exact OpenAI shape**:

```json
{"error": {"message": "string", "type": "string", "param": "string|null", "code": "string|null"}}
```

`type` and `message` are required; `param` and `code` are nullable, defaulting to null. The error responses documented on that endpoint are **400, 401, 404, 429, 503, 504**. **403 and 500 are not documented on the endpoint** even though they appear in the error-codes table — so a client must treat the endpoint schema as incomplete and handle unlisted statuses defensively.

Mapping to your cases:
- invalid key → 401 "Authentication Error"
- expired/revoked key → **not documented as distinct**; Together documents only a single 401 Authentication Error condition, with no separate revoked/expired code.
- insufficient quota/credit → 402 Payment Required (error-codes page). Note 402 is **not** among the statuses documented on the chat-completions endpoint reference.
- rate limited → 429
- unknown/unavailable model → 404 Not Found
- malformed request → 400 Invalid Request
- server error → 500 / 529 Server Error, 503 Engine Overloaded, 504 + 524 timeouts. The 524 is a **Cloudflare** timeout, i.e. generated by the edge, not by Together's application — see redaction note below.

The 524 entry is meaningful for clients: Cloudflare-originated 5xx responses are **HTML error pages, not JSON**, so a Together client must not assume `response.json()` will parse on 5xx. Together does not document the 524 body — **not documented** — but its presence in the list is direct evidence Cloudflare sits in front of the API.

#### 2. Deviation from OpenAI

Minimal for the documented 4xx path — Together reproduces `{message, type, param, code}` exactly. The deviations are at the edges: extra statuses OpenAI does not use (402, 503, 524, 529), an incomplete per-endpoint schema (no 403/500), and the non-JSON Cloudflare tier.

#### 3. Rate-limit / retry headers

<https://docs.together.ai/docs/rate-limits> documents exactly one header: **`x-ratelimit-reset`**, which "reports the suggested retry interval for the model" in seconds. Together recommends exponential backoff rather than immediate retry.

On 429, Together documents two `error_type` values distinguishing the limit hit:
- `dynamic_request_limited` — request-rate limiting
- `dynamic_token_limited` — token-rate limiting

Note this field is called `error_type`, which is **not** in the `ErrorData` schema (`message`/`type`/`param`/`code`) documented on the endpoint reference. The two docs pages are inconsistent about the 429 body; a client should read both `error.type` and `error_type`. Standard `Retry-After`, `x-ratelimit-limit`, and `x-ratelimit-remaining` are **not documented**.

#### 4. Redaction risk

Together does **not document** whether error `message` strings echo request content. The `param` field by design names a request **field** (not its value), which is low risk. There is no documented moderation-echo mechanism equivalent to OpenRouter's `flagged_input`. Because nothing is documented either way, treat `error.message` as untrusted for logging: **not documented** ≠ safe.

---

### GitHub Copilot (models / chat completions endpoint used by third-party clients)

**Plainly: there is no public, documented GitHub Copilot chat-completions API for third-party clients. The endpoint third-party clients use (`https://api.githubcopilot.com/chat/completions`) is undocumented, unsupported, and its error contract is entirely unspecified by GitHub.** Everything below is what *does* exist.

#### What GitHub actually documents

1. **Copilot REST API = management only.** <https://docs.github.com/en/rest/copilot> documents Copilot cloud agent repository management, cloud agent management, content exclusion management, custom agents, usage metrics, and user (seat) management. **No inference, chat, or completions endpoint appears anywhere in the Copilot REST API reference.**

2. **Supported models reference is client-facing, not API-facing.** <https://docs.github.com/en/copilot/reference/ai-models/supported-models> lists which models are available in which Copilot *clients* (GitHub.com, VS Code, Visual Studio, JetBrains). It documents no endpoint, no request format, and no error responses.

3. **GitHub Models is the documented inference surface — and it is a different product.** <https://docs.github.com/en/rest/models/inference> documents:
   - `POST /inference/chat/completions` and `POST /orgs/{org}/inference/chat/completions`
   - base URL `https://models.github.ai/`
   - auth `Authorization: Bearer <TOKEN>`, `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2026-03-10`
   - token needs the `models: read` permission (fine-grained PAT or GitHub App token)

   Its documented response codes are: **200 OK**, plus a single mentioned error condition — an unsupported modality combination yields **422**. **No error body schema, and no 401/402/429/500 documentation exists on that page — not documented.**

4. **GitHub Models rate limiting is described only in prose.** <https://docs.github.com/en/github-models/use-github-models/prototyping-with-ai-models> says free API usage is "rate limited by requests per minute, requests per day, tokens per request, and concurrent requests" and that you must "wait for the rate limit that you hit to reset". It gives per-tier numeric limits but **documents no HTTP status code, no `x-ratelimit-*` headers, no `Retry-After`, and no error body shape — all not documented.** For paid usage it defers to Azure: <https://learn.microsoft.com/en-us/azure/ai-foundry/model-inference/quotas-limits>.

#### What is known about `api.githubcopilot.com`

GitHub's own community announcement <https://github.com/orgs/community/discussions/101438> ("Important Updates: Copilot Chat API endpoints and Copilot Content Exclusions") records that Copilot Chat requests moved off `copilot-proxy.githubusercontent.com` to `https://api.githubcopilot.com`. This is a firewall/allowlist announcement for enterprise network admins — **it is not an API contract.** It documents no request schema, no response schema, and no error codes.

GitHub also asks about programmatic Copilot Chat use in <https://github.com/orgs/community/discussions/112339>, where the position is that Copilot has no public API for direct programmatic chat access.

#### Implications for a client

- **Status codes: not documented.** Errors observed by third-party clients against `api.githubcopilot.com` come from an internal service and can change without notice or deprecation window.
- **Body shape: not documented.** Do not assume OpenAI shape; do not assume JSON.
- **Rate-limit headers: not documented.**
- **Redaction risk: not documented, and therefore must be assumed maximal.** With no published contract, there is no guarantee about what an error body contains. Additionally, Copilot auth uses a **short-lived token exchanged from a GitHub OAuth token**, so a client's error-logging path handles two distinct key-shaped secrets; neither is covered by any redaction guarantee.
- Practical warning that GitHub does document in its terms/abuse posture and that unofficial proxy projects repeat: automated/scripted Copilot use can trip abuse detection and suspend Copilot access. Building against this endpoint is a product risk, not just a technical one.

**Recommendation for any client that wants a supported GitHub inference path: use GitHub Models (`models.github.ai`), not `api.githubcopilot.com` — accepting that even GitHub Models publishes essentially no error schema.**

---

### Arbitrary OpenAI-compatible custom endpoints (vLLM, LM Studio, Ollama, LiteLLM proxy)

#### The short answer: what a client may ASSUME

**Guaranteed: nothing beyond the HTTP status line.** Everything else is best-effort. Concretely:

| Property | Status |
|---|---|
| A non-2xx status arrives | Effectively safe (it's HTTP) |
| The body is JSON | **Not safe** — proxies/edges return HTML or plain text |
| The body has an `error` key | **Not safe** |
| `error` is an *object* | **Not safe** — vLLM returns `{"error": "Unauthorized"}`, a string |
| `error.code` is a string | **Not safe** — vLLM types it as `int` |
| `error.type` matches OpenAI's vocabulary | **Not safe** — vLLM emits `"Not Found"` / `"BadRequestError"` |
| `error.message` is free of request content | **Not safe** — Ollama and vLLM both stringify exceptions into it |
| 401 exists at all | **Not safe** — Ollama has no auth; vLLM only if `--api-key` is set |
| 402 / quota errors exist | **Not safe** — no local server has a billing concept |
| 429 exists | **Not safe** — no local server documents rate limiting |
| Any rate-limit header | **Not documented anywhere** in these projects |

A correct client must therefore: read the status first, attempt JSON parse defensively, tolerate `error` being a string *or* an object *or* absent, coerce `code` from int-or-string, and fall back to the raw body text (truncated) when parsing fails.

#### Evidence: vLLM

Schema — `vllm/entrypoints/openai/engine/protocol.py`, <https://github.com/vllm-project/vllm/blob/main/vllm/entrypoints/openai/engine/protocol.py>:

```python
class ErrorInfo(OpenAIBaseModel):
    message: str
    type: str
    param: str | None = None
    code: int          # <-- int, not OpenAI's string

class ErrorResponse(OpenAIBaseModel):
    error: ErrorInfo
```

**`code` is an `int` (the HTTP status), diverging from OpenAI's nullable string.**

**The 401 does not use this schema at all.** `AuthenticationMiddleware` in `vllm/entrypoints/serve/utils/server_utils.py` (<https://github.com/vllm-project/vllm/blob/main/vllm/entrypoints/serve/utils/server_utils.py>) returns:

```python
response = JSONResponse(content={"error": "Unauthorized"}, status_code=401)
```

i.e. **`{"error": "Unauthorized"}` — `error` is a bare string, not an object.** Any client doing `body["error"]["message"]` throws on vLLM's invalid-key path. This is the single strongest piece of evidence that the `error`-object assumption is unsafe. (Auth is only enabled when `--api-key` / `VLLM_API_KEY` is set, and only guards paths under `/v1`, `/v2`, `/inference` — `/health` is unauthenticated.)

`type` values are inconsistent between vLLM's two error paths, and neither matches OpenAI:
- `http_exception_handler` sets `type=HTTPStatus(exc.status_code).phrase` — i.e. literally `"Not Found"`, `"Bad Request"` (server_utils.py).
- `create_error_response` in `vllm/entrypoints/serve/utils/error_response.py` (<https://github.com/vllm-project/vllm/blob/main/vllm/entrypoints/serve/utils/error_response.py>) sets Python-exception-style names: `"BadRequestError"` (400), `"UnprocessableEntityError"` (422), `"NotFoundError"` (404), `"NotImplementedError"` (501), `"InternalServerError"` (500). OpenAI's `invalid_request_error` never appears.

Mapping in vLLM (from `create_error_response`): unknown model → `VLLMNotFoundError` → **404 / `NotFoundError`**; malformed request → `ValueError`/`TypeError`/`OverflowError`/`VLLMValidationError` → **400 / `BadRequestError`**; generation failure → `GenerationError` → **`InternalServerError`** with the exception's own status code; everything else → **500 / `InternalServerError`**. There is **no 429 and no 402 path** — vLLM implements neither rate limiting nor quota.

**Redaction, vLLM:** `create_error_response` does `message = str(exc)` — the raw exception text, which for validation errors includes the offending request values. vLLM does run `sanitize_message` (`vllm/entrypoints/serve/utils/api_utils.py`), whose docstring is "Strip memory addresses, tracebacks, and file paths from error messages." It regex-strips `at 0x...`, `File "...", line N, in ...` traceback frames, and absolute paths under `/home|/usr|/opt|/var|/tmp|/root|/lib|/mnt|/srv`. **It does not strip prompt content and does not strip anything key-shaped.** Separately, `VLLM_DEBUG_LOG_API_SERVER_RESPONSE` triggers vLLM's own warning: "CAUTION: Enabling log response in the API Server. This can include sensitive information and should be avoided in production."

#### Evidence: Ollama's OpenAI-compat layer

Ollama has **two different error shapes on the same server**:

- **Native `/api/*` endpoints**: `server/routes.go` (<https://github.com/ollama/ollama/blob/main/server/routes.go>) returns `gin.H{"error": err.Error()}` throughout — e.g. `c.JSON(http.StatusNotFound, gin.H{"error": fmt.Sprintf("model '%s' not found", req.Model)})`. **`error` is a plain string.** Not OpenAI-shaped at all.
- **`/v1/*` OpenAI-compat endpoints**: `openai/openai.go` (<https://github.com/ollama/ollama/blob/main/openai/openai.go>) defines the OpenAI shape:

```go
type Error struct {
    Message string  `json:"message"`
    Type    string  `json:"type"`
    Param   any     `json:"param"`
    Code    *string `json:"code"`
}
type ErrorResponse struct { Error Error `json:"error"` }

func NewError(code int, message string) ErrorResponse {
    var etype string
    switch code {
    case http.StatusBadRequest:  etype = "invalid_request_error"
    case http.StatusNotFound:    etype = "not_found_error"
    default:                     etype = "api_error"
    }
    return ErrorResponse{Error{Type: etype, Message: message}}
}
```

Note `Code` is `*string` and `NewError` **never sets it** — `code` is always `null` on Ollama, and `param` is always null too. Only three `type` values exist: `invalid_request_error`, `not_found_error`, `api_error`. There is **no 401, no 402, no 429** anywhere in the compat layer.

The middleware that calls it, `middleware/openai.go` (<https://github.com/ollama/ollama/blob/main/middleware/openai.go>), overwhelmingly passes raw error text through: `openai.NewError(http.StatusBadRequest, err.Error())`, `openai.NewError(http.StatusInternalServerError, err.Error())`, etc.

**Redaction, Ollama: HIGH.** `err.Error()` is echoed verbatim into `error.message`, so JSON-decode errors quote the offending request fragment. Some messages echo request values by construction — e.g. `fmt.Sprintf("Invalid value for 'encoding_format' = %s. Supported values: ['float', 'base64'].", req.EncodingFormat)` and `fmt.Sprintf("model '%s' not found", req.Model)`. **The requested model name is echoed into the 404 body**, which matters if a client encodes anything sensitive in a model string. No redaction of any kind is applied.

#### Evidence: LM Studio

<https://lmstudio.ai/docs/app/api/endpoints/openai> documents the five OpenAI-compatible endpoints (`GET /v1/models`, `POST /v1/responses`, `/v1/chat/completions`, `/v1/embeddings`, `/v1/completions`) and the "swap the base URL" pattern. **It documents no HTTP status codes, no error response format, and no error-handling conventions — entirely not documented.** LM Studio is the clearest case of an OpenAI-compat surface where the client has zero contractual basis for parsing errors.

#### Evidence: LiteLLM proxy

<https://docs.litellm.ai/docs/exception_mapping>: "All LiteLLM exceptions inherit from OpenAI's exception types, so any error-handling you have for that should work out of the box", with three extra attributes including `status_code`. <https://docs.litellm.ai/docs/proxy/user_keys>: "Input, Output, Exceptions are mapped to the OpenAI format for all supported models."

<https://docs.litellm.ai/docs/proxy/error_diagnosis> documents the returned body as the OpenAI shape `{"error": {"message", "type", "param", "code"}}`, and — importantly for redaction — documents that **the upstream provider's error text is embedded inside `error.message`**. The documented way to tell gateway from provider errors is string-sniffing the message: "If the error contains `<Provider>Exception`, it's from the provider" (e.g. `BedrockException`, `OpenAIException`, `AnthropicException`, `AzureException`, `VertexAIException`). Errors without a provider name originate in the LiteLLM gateway itself.

**Redaction, LiteLLM: HIGH, by design.** Because the upstream message is nested verbatim into `error.message`, any content the upstream provider echoed (including OpenAI-style messages that quote request fields, or Azure content-filter payloads exposed via `provider_specific_fields`) propagates through to the client. Note also that distinguishing gateway from provider errors requires **substring-matching the message**, which is a fragile contract, and it means the message field is guaranteed to contain provider-identifying metadata — an information leak about the proxy's backend topology.

#### The failure mode none of these projects document: non-JSON

None of vLLM, Ollama, LM Studio, or LiteLLM documents what happens when something *in front of* them fails. In practice a custom endpoint is usually behind nginx/Cloudflare/a load balancer, and those emit HTML or plain-text bodies with no `error` key at all. Together AI's own documented **524 "Cloudflare Timeout"** (<https://docs.together.ai/docs/error-codes>) is primary-source proof that this tier exists and surfaces to clients even on a first-party commercial API. A client hitting an arbitrary base URL must assume a 502/503/504 may carry `Content-Type: text/html`.

Related: vLLM's `/health` endpoint is explicitly *outside* the authenticated `GUARDED_PREFIX` (`/v1`, `/v2`, `/inference`) — so a 401 on `/v1/models` and a 200 on `/health` from the same host is expected behaviour, not a misconfiguration.

#### Recommended client contract for arbitrary endpoints

1. Branch on **HTTP status** first; never require a parsed body to classify.
2. Parse JSON defensively; on failure, keep a truncated snippet of the raw body as the message.
3. Accept `error` as string OR object OR absent; also check a bare top-level `message`/`detail` (FastAPI's default is `{"detail": ...}`, which is what any un-handled route on a vLLM-like server produces).
4. Coerce `code` from int or string; never compare it to an OpenAI string constant without normalizing.
5. Never treat `type` as a reliable enum across servers — vLLM alone emits three different vocabularies.
6. **Redact before logging.** For this class of endpoint, `error.message` should be treated as potentially containing verbatim request content (proven for Ollama and vLLM) and verbatim upstream provider text (proven for LiteLLM).

---

### Cross-provider summary

| | OpenRouter | Together AI | GitHub Copilot | Arbitrary OpenAI-compat |
|---|---|---|---|---|
| Envelope | `{error:{code:int, message, metadata?}}` | `{error:{message,type,param,code}}` | not documented | varies; may be non-JSON |
| `type`/`param` present | No (own skin) | Yes | not documented | Inconsistent |
| `code` type | **int** | string/null | not documented | **int (vLLM) / always-null (Ollama)** |
| Invalid key | 401 | 401 | not documented | vLLM `{"error":"Unauthorized"}`; Ollama none |
| Revoked/expired key distinct | **No** | **No** | not documented | N/A |
| Quota/credit | 402 | 402 | not documented | none |
| Rate limited | 429 + `Retry-After` (may) | 429 + `x-ratelimit-reset` | not documented | none |
| Unknown model | 404 `not_found` | 404 | not documented | 404 (vLLM `NotFoundError`, Ollama `not_found_error`) |
| Malformed | 400 | 400 | not documented | 400 |
| Server error | 500 `server`, msg masked | 500/503/504/524/529 | not documented | 500 |
| Errors inside HTTP 200 | **Yes** (mid-stream + `choices[].error`) | not documented | not documented | not documented |
| Echoes request content | **Yes** — `flagged_input` (≤100 chars), guardrail `patterns`, `debug.echo_upstream_body` | not documented | not documented | **Yes** — Ollama `err.Error()`/model name; vLLM `str(exc)`; LiteLLM upstream text |
| Upstream metadata in errors | **Yes** — `provider_code`, `provider_name`, `model_slug`, opt-in `openrouter_metadata` topology | not documented | not documented | LiteLLM `<Provider>Exception` in message |
| Documented key redaction | **None** | **None** | **None** | **None** (vLLM strips paths/tracebacks only) |

**The one conclusion that holds across all four: no provider in this set documents any guarantee that API keys or prompt content are redacted from error bodies.** OpenRouter documents mechanisms that positively *do* echo prompt content. A client must redact at its own logging boundary, not rely on the provider.
