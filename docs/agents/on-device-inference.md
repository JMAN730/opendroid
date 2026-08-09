# On-device inference contract

The on-device planner is the primary provider selected by the user. The app
does not infer permission to send a prompt to another vendor from the presence
of an API key.

## Latency profiles

`OnDeviceLatencyProfile` records successful planning response times for the
current manufacturer/model/SDK, backend, and model tier. A profile is
unprofiled until it has three measurements. Its accepted budget is the measured
95th percentile of the retained sample window; there are no thresholds derived
from model specifications. When a later local planning response exceeds that
budget, the agent surfaces a notice through the existing speech/status channel.

## Fallbacks

`LLMConfig.fallbackProviders` is an explicit user allowlist configured in
Settings. `LLMProviderFactory` filters it by provider identity, configuration,
and live availability before `ReEvaluationEngine` can use it. Malformed local
plans may try only those candidates. Network, authentication, quota, and server
errors retain their existing error/retry behavior and do not trigger a vendor
switch.

Plans containing sensitive, advanced-control, or irreversible actions never
cross a provider boundary automatically. This routing policy is separate from
`AutoApprovalPolicy`: approval still controls whether a valid plan may execute.
