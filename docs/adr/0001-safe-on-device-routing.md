# ADR-0001: Safe on-device routing, measured latency, and malformed-plan fallback

- Status: Accepted
- Date: 2026-08-09
- Issue: #172

## Context

The app already records `LLMResponse.latencyMs`, but it had no consumer, no
device-specific profile, and no routing policy connected to action risk. Its
auto-approval policy is a user-confirmation policy, not a model-trust policy.
`ReEvaluationEngine` also treated a malformed JSON response as a non-retryable
terminal error, even when the response came from the local model.

The supported local catalog contains AI Core and LiteRT-LM model tiers. Model
specifications and artifact sizes are not performance measurements, so they are
not used as latency thresholds.

## Decision

### Measured latency profile

Every successful local planning response records a `LatencyProfile` keyed by
provider, selected model, and the actual device reference string (manufacturer,
model, and Android API level). The profile retains a bounded recent sample set
and derives p50/p95 from those samples. The model tier is recorded from the
catalog (`sizeLabel`) when available; unknown/custom imports are recorded as
`unknown` rather than assigned an inferred tier.

The first observation is `UNMEASURED`. There are no pre-populated numeric
budgets, model-spec-derived constants, timeouts, or synthetic reference-device
measurements in this implementation. After a device has observations, its own
measured p95 is the accepted profile for a performance signal. A later planning
response above that measured p95 adds a user-visible chat notice. This notice is
observability only: it does not abort, retry, authorize, or reroute a request.

This is intentionally a measured profile, not a universal promise. Benchmark
results should be reported with the model tier, sample count, p50, p95,
reference hardware string, Android API level, and app build.

### Action risk and provider selection

`ActionDefinition.risk` is independent from `neverAutoApprove` at the policy
boundary, although the catalog derives conservative defaults for legacy
declarations:

- `LOW`: simple, non-sensitive actions;
- `MEDIUM`: compound actions without destructive or advanced-control behavior;
- `HIGH`: every `ADVANCED` action and every irreversible/approval-gated action;
- `CRITICAL`: non-simple financial actions such as `PAY_UPI`.

Unknown action names are `CRITICAL`, which fails closed. `ActionSchema` can
infer known risky actions from the user goal before planning and the completed
plan/re-evaluation path passes concrete action names to the factory. If the
active provider is on-device and any action is `HIGH` or `CRITICAL`, the factory
uses only the explicitly configured fallback provider when it is distinct,
known, credential-authorized, endpoint-complete, and currently available.
Otherwise it returns `NO_SAFE_FALLBACK`. A cloud provider already selected by
the user is not silently replaced by another provider.

`AutoApprovalPolicy` remains unchanged and still controls execution consent;
it is not reused as routing authorization.

### Malformed local-plan fallback

`ReEvaluationEngine` invokes the active provider through the existing wrapped
provider, so normal same-provider retry and cancellation behavior remains
unchanged. When decoding or validating a result produces the existing
non-retryable `MALFORMED_RESPONSE` from an on-device provider, the engine asks
the factory for the one explicit, distinct, authorized, available fallback and
tries it once. It validates the fallback response as well:

- JSON must decode to a supported decision;
- `MODIFY` must contain a non-empty plan;
- every replacement action must be in `ActionSchema`.

The engine never retries malformed content on the same provider, never follows
the on-device backend fallback into an unrelated vendor, and never falls back
to a provider whose credentials are empty or whose endpoint is incomplete. A
missing or unusable safe fallback becomes a clear, non-retryable
`NO_SAFE_FALLBACK` error. Errors from a cloud provider, including malformed
responses, keep the existing error contract and do not cascade to another
provider.

The fallback setting is stored as a provider name only. Credentials remain in
the direct credential store and are resolved by `WrappedLLMProvider` for the
selected provider; no credential is copied into routing metadata or plan data.

## Consequences

The app now gives users a device-specific latency signal without claiming a
threshold that has not been measured. Users who want safe escalation must
select and authorize a fallback in Settings; absent that explicit choice,
high-risk local planning and malformed local re-evaluation fail closed. Existing
AI Core/LiteRT-LM backend fallback and remote provider retry behavior remain
unchanged.

## Validation

JVM tests cover measured-profile classification, model-tier and hardware
identity, high-risk routing, unknown-action fail-closed behavior, empty
credential authorization, malformed local escalation, malformed remote
non-escalation, and invalid replacement plans.
