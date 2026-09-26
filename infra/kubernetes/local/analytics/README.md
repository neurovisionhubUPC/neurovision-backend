# Rehearse Analytics in local k3d

This overlay prepares a **synthetic-data-only** Analytics workload. The Service is
ClusterIP; there is no public route. MongoDB uses a separate local PVC and the
existing cursor-signing Secret is referenced, not copied into a manifest.

## Quick path

1. Confirm `kubectl config current-context` is `k3d-neurovision-local`.
2. Apply the new MongoDB and Analytics DLQ resources from
   `../dependencies/analytics-mongodb.yaml` and
   `../dependencies/analytics-kafka-topics-job.yaml`. On an existing cluster,
   do **not** reapply the entire dependency overlay just to add the topic:
   Kubernetes Jobs have immutable pod templates. MongoDB uses the pinned
   `mongo:8.0.14-noble` tag with `IfNotPresent`: the k3d node uses its local
   copy when present, or pulls this public image from Docker Hub. A Docker
   Desktop cache does not prove that the k3d node has a usable image; the
   local import previously failed because containerd lacked a content digest.
3. Wait for `statefulset/analytics-mongodb` and `job/analytics-kafka-topics`.
   Verify that `analytics-telemetry.v1.dlq` exists with three partitions.
4. Verify `secret/analytics-gaze-plot-cursor-signing-key` exists. If missing,
   run `configure-local-secrets.ps1 -OnlyAnalyticsCursorSigningKey -SkipAws -SkipTls`.
   Never print or commit its value.
5. Confirm the native image is cached locally; import it into `neurovision-local`
   without pulling a replacement image.
6. Apply `../../config/analytics-configmap.yaml` and this overlay, then wait for
   `deployment/analytics-service` readiness.

```powershell
kubectl config current-context
kubectl apply -f .\infra\kubernetes\local\dependencies\analytics-mongodb.yaml
kubectl apply -f .\infra\kubernetes\local\dependencies\analytics-kafka-topics-job.yaml
kubectl rollout status statefulset/analytics-mongodb -n neurovision --timeout=180s
kubectl wait --for=condition=complete job/analytics-kafka-topics -n neurovision --timeout=180s
kubectl -n neurovision exec kafka-0 -c kafka -- /opt/kafka/bin/kafka-topics.sh `
  --bootstrap-server kafka:9092 --describe --topic analytics-telemetry.v1.dlq
kubectl get secret analytics-gaze-plot-cursor-signing-key -n neurovision -o name
docker image inspect neurovision/analytics-service:compose-native --format '{{.Id}}'
k3d image import neurovision/analytics-service:compose-native -c neurovision-local
kubectl apply -f .\infra\kubernetes\config\analytics-configmap.yaml
kubectl apply -k .\infra\kubernetes\local\analytics
kubectl rollout status deployment/analytics-service -n neurovision --timeout=180s
```

The current ConfigMap uses the same development Cognito issuer as Clinical.
This is not a strictly offline deployment: OIDC discovery may contact Cognito.
Do not disable OIDC to obtain a green smoke test. Only the approved development
pool and existing test accounts may be used; the smoke does not create or
modify Cognito users.

## Isolated repeat after the first admitted event

The original `neurovision` namespace is no longer an empty one-shot fixture.
Use `neurovision-analytics-smoke` to repeat the corrected synthetic scenario
without deleting or replaying existing data. The overlay creates dedicated
Kafka, MongoDB, Analytics, source topic, and DLQ. Only Clinical is shared for
read-only authorization. Analytics remains ClusterIP with no public route;
its cursor signing Secret is generated anew in the isolated namespace.

```powershell
kubectl config current-context # Must be k3d-neurovision-local
kubectl get namespace neurovision-analytics-smoke --ignore-not-found -o name
kubectl apply -f .\infra\kubernetes\local\analytics\isolated\namespace.yaml
.\infra\kubernetes\config\configure-local-secrets.ps1 `
  -Namespace neurovision-analytics-smoke -OnlyAnalyticsCursorSigningKey -SkipAws -SkipTls
kubectl kustomize --load-restrictor LoadRestrictionsNone `
  .\infra\kubernetes\local\analytics\isolated | kubectl apply -f -
kubectl rollout status statefulset/kafka -n neurovision-analytics-smoke --timeout=240s
kubectl rollout status statefulset/analytics-mongodb -n neurovision-analytics-smoke --timeout=180s
kubectl wait --for=condition=complete job/analytics-kafka-topics `
  -n neurovision-analytics-smoke --timeout=180s
kubectl rollout status deployment/analytics-service `
  -n neurovision-analytics-smoke --timeout=180s
.\infra\kubernetes\local\analytics\smoke-test-analytics-k3d.ps1 `
  -Namespace neurovision-analytics-smoke -PreflightOnly
```

`LoadRestrictionsNone` is needed because this local overlay references named
sibling manifests; it does not fetch remote resources. Do not rerun setup
blindly if the namespace already exists. Its one-shot preflight requires
zero retained source records, inbox documents, summaries, and DLQ records.
After a successful preflight and separate authorization, the user may run
`-Namespace neurovision-analytics-smoke -AuthorizedSyntheticRun`, with
PATIENT login first and DOCTOR login second. Preflight itself performs
neither login nor event publication.

## One-shot synthetic smoke

Run the read-only preflight first. It requires exactly one active Clinical
session, one plan, one active assignment, an empty Analytics inbox/summary and
DLQ, and no retained source Kafka record. It verifies loopback-only
port-forwards and an anonymous `401`, but performs no login or publication.

```powershell
.\infra\kubernetes\local\analytics\smoke-test-analytics-k3d.ps1 -PreflightOnly
```

Only after reviewing that result, start the interactive one-shot run. The
script announces **PATIENT** immediately before the first browser login and
**DOCTOR** immediately before the second. Sign in with those existing test
accounts in that order. No password or authorization URL is printed by the
script. **Both** accounts must match the sole active Clinical session and
assignment, and both must receive Clinical authorization `200`, before the
script rechecks the empty pipeline and publishes. A wrong doctor therefore
stops the run without an event.

```powershell
.\infra\kubernetes\local\analytics\smoke-test-analytics-k3d.ps1 -AuthorizedSyntheticRun
```

The run publishes exactly one fresh V1 gaze-and-gameplay event, waits at most
20 seconds for its projection, and checks Clinical authorization plus all four
Analytics queries for both roles on the **same** session and stream. The
heatmap, gaze plot, and replay JSON are checked against the published fixture:
the occupied heatmap cell, observed gaze point, and both replay lanes must
match its gaze/gameplay samples, not merely return HTTP `200`. It also
checks unrelated-session `403`, anonymous `401`, one Mongo inbox/summary,
one source-topic offset increase, and no Analytics DLQ record. It never
replays the expired prior Kafka record, creates a session, or retries a failed
publication. After any partial failure, inspect aggregate counts and offsets
before considering another run; the empty-baseline guard intentionally blocks
blind repetition. Port-forwards are terminated in a `finally` block.

## Observed local result

The original authorized one-shot run exited `0` after user-operated PATIENT and DOCTOR
PKCE against the development Cognito pool. It reported Clinical `200` and all
four Analytics queries `200` for each role on the same session and stream;
the PATIENT checks also returned unrelated-session `403` and anonymous `401`.
Exactly one fresh synthetic event was published; no second event or DLQ replay
was attempted. No password, token, session identifier, or event payload was
printed by the harness.

That run preceded the separate advisory correction above. Its evidence proves
HTTP status, authorization, one admitted event, and aggregate persistence;
it does **not** retroactively prove the new response-content assertions or
doctor-before-publication ordering. The existing admitted event prevents
safely repeating the one-shot run in the original namespace.

The corrected smoke was subsequently run once in the dedicated
`neurovision-analytics-smoke` namespace, after an empty-baseline preflight.
Both user-operated test-account logins received Clinical `200` before the
single synthetic Kafka publication. The run exited `0` with fixture-matched
heatmap, gaze-plot, and replay responses for PATIENT and DOCTOR, unrelated
session `403`, anonymous `401`, one inbox record, one summary, and zero DLQ
records. A read-only post-run check found one inbox and one summary in each
namespace; the original namespace's aggregates were unchanged. This isolated
namespace is now populated too, so its one-shot smoke must not be repeated
without creating a new isolated baseline.

Independent read-only checks after the run found Mongo inbox `1`, stream
summaries `1`, source Kafka earliest-offset total `1` and latest-offset total
`2` across three partitions, Analytics DLQ latest-offset total `0`, and
Analytics Deployment `1/1`. The two smoke loopback ports had no listeners or
remaining port-forward processes. The default `-PreflightOnly` command is now
**intentionally non-repeatable** against the original `neurovision` namespace: its empty-baseline guard
must reject the admitted event. Do not reset data or publish another event
merely to obtain a second green result.

The isolated namespace rollout completed for Kafka, MongoDB, the topic Job,
and Analytics. Its read-only preflight reported workloads ready, the sole
active synthetic Clinical fixture, zero retained source records, inbox,
summaries and DLQ, plus anonymous `401`. The authorized run then supplied
the end-to-end evidence described above. Neither namespace should be treated
as an empty fixture for another run.

## Verification boundary

For a local synthetic smoke, publish a contract-valid V1 event to
`telemetry-batch-accepted.v1`, confirm the Analytics inbox/summary in MongoDB,
and query through the private Service with a valid patient or doctor token.
An unauthorized or unrelated token must fail closed. Preflight or deployment
readiness alone does **not** prove an authorized end-to-end path. Do not reuse
patient data or replay quarantined events to make this test pass.

This single-node Kafka and unauthenticated local MongoDB are development
fixtures. They provide no production-grade DLQ confidentiality, retention, or
disaster recovery controls.
