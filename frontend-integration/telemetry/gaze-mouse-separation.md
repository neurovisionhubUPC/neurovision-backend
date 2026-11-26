# Keep mouse interaction separate from estimated gaze

**Audience:** Jair and the frontend integration assistant. Address R4-PROV-01 at the producer: stop putting mouse coordinates into `gazeSamples`, preserve interaction in the existing gameplay channel, and keep already queued requests unchanged. No backend contract change is required for this MVP correction.

## Verified baseline and scope

Source comparison: backend `b28373d`, frontend `025929c`, 2026-11-25. The current frontend mouse branch emits numeric gaze with a fixed confidence of `0.95`. Telemetry V1 has no input-device provenance field; Analytics cannot recover the physical source from those numbers. The frontend audit used simulated transport; this guide does not claim those samples were persisted in the deployed environment. No tests were executed for this documentation.

Read [integration.md](integration.md), [contracts.ts](contracts.ts) and the [full-body retry instructions](retry-queue-check.md) for the existing wire contract.

## Minimum producer correction

| Input / data | Existing channel and handling |
|---|---|
| New valid webcam estimate | `gazeSamples`, with its actual estimated confidence and existing freshness protections. This is estimated gaze, not validated physiological fixation detection. |
| Mouse fallback position | Existing `gameplaySamples` fields for interaction. Do not also emit a gaze sample or invent gaze confidence for the cursor. |
| No new valid webcam observation | No new gaze sample. Do not repeat a retained coordinate to simulate observation frequency or insert zeros as missing gaze. |
| Semantic trial produced using mouse / mixed input | Preserve the existing producer convention `dataOrigin: SYNTHETIC`; do not rewrite an already formed trial. |

Apply this distinction where samples are formed in `EyeTrackingGameCanvas.tsx`, before handing them to the existing Telemetry queue. Do not add `inputSource`, `dataOrigin` or another unrecognized field to the closed Telemetry V1 body. `PRODUCER_REPORTED` in the separate trial contract is a producer declaration, not proof of webcam measurement.

Send both arrays as required by V1: a gameplay-only batch has `gazeSamples: []` and a nonempty `gameplaySamples` array. Do not send a batch with both arrays empty. Retain current gameplay field meanings and validation; do not invent a cursor-specific DTO or fill unavailable fields with fabricated values just to satisfy the schema.

## Switching inputs and retrying

1. Preserve queue ownership for the same session when switching webcam → mouse → webcam. Keep pending/in-flight batches and trials attached to their original account/session, as in [queue-lifecycle-fixes.md](queue-lifecycle-fixes.md).
2. Apply the corrected source rule to **newly captured samples**. Never rewrite an already queued batch's samples, `sentAt`, `batchId`, stream or sequence and then resend it as the same logical batch.
3. Switching to mouse must stop new gaze emission from the cursor and stale webcam coordinates. Switching back must wait for a genuinely new valid webcam observation; retain the existing freshness/continuity protections.
4. If a batch contains genuine webcam gaze followed by mouse gameplay, V1 can carry both arrays, but it still has no per-sample device attestation. Do not label the whole stream/session as webcam-only merely because some valid gaze exists.

This change does not cleanse historical data. A previously formed or accepted batch may already contain ambiguous numeric samples. Do not silently relabel, delete, change its retry identity or claim that the correction retrospectively proves its origin. Surface known mixed-data limitations; any cleanup or pending-data disposition outside the existing policy is a separate decision.

## Display and reporting limits

- Existing gaze heatmap/gaze-plot results derive from gaze samples, not automatically from gameplay coordinates. Retaining mouse gameplay does **not** add a backend cursor heatmap endpoint.
- A mouse-only activity may have gameplay and semantic outcomes but no gaze visualization. Display unavailable/absent gaze evidence according to the existing response, not as successful eye tracking or a zero-valued measurement.
- Never show webcam evidence at 100% when the relevant denominator is zero or unknown. Do not derive physical provenance from confidence, `PRODUCER_REPORTED`, or the selected input mode alone.
- A session with mixed input is not automatically clean ocular evidence. Numeric V1 cannot retrospectively distinguish cursor gaze already submitted before this correction.
- Do not change backend projections, historical snapshots or PDF rendering as part of this handoff. New numeric provenance/versioning and cursor visualizations are deferred, not implied features.

## Minimum evidence for Jair to return

Use the current sample producer, queue and HTTP doubles with synthetic observations; do not substitute a new implementation just for the checks.

- [ ] Webcam-only: each emitted gaze sample comes from a new valid webcam observation with its actual confidence; held/invalid observations do not become new gaze.
- [ ] Mouse-only: no gaze samples are emitted from cursor movement, while valid gameplay interaction remains deliverable in a nonempty gameplay-only batch.
- [ ] Webcam → mouse → webcam: no stale gaze during mouse mode; gaze resumes only on a new valid webcam estimate; original pending/in-flight bodies remain unchanged.
- [ ] Full-body retry, including `sentAt`, remains stable; serialized drains and identity-based acknowledgement remain covered.
- [ ] Mouse/mixed semantic results preserve `SYNTHETIC` under the existing producer convention and trial identity rules.
- [ ] Empty/unknown gaze evidence is not presented as 100% webcam, a measured zero or a generated cursor heatmap; known earlier ambiguous data is not relabeled as verified gaze.

Return the frontend commit, exact command, observed results and limitations without tokens, real identifiers, coordinates or raw payload dumps. These checks do not establish physical eye-tracking accuracy, Java acceptance or deployed persistence; integrated checks require separate authorization.

## Source anchors

- Backend: `TelemetryBatchV1.GazeSampleV1/GameplaySampleV1`, `TelemetryBatchValidator` and the existing semantic trial `DataOrigin` contract.
- Frontend: `EyeTrackingGameCanvas.tsx` mouse confidence, `emitGaze` and gameplay emission (baseline around lines 2015 and 2160–2183).
