# Download a saved technical report

Clinical renders one common PDF with text, optional frozen Visual Search, Standard Scanning, Sustained Fixation, Compensatory Saccade or Systematic Exploration results tables, saved heatmaps and estimated gaze-point trajectories from an existing immutable report copy. Downloading never creates a copy, updates identities, recalculates Analytics, or changes stored content.

## HTTP contract

```http
GET /api/v1/clinical-patients/{clinicalPatientId}/therapy-sessions/{therapySessionId}/report-snapshots/{reportSnapshotId}/pdf
Authorization: Bearer <DOCTOR access token>
```

Every request uses the existing Clinical snapshot reader: pure DOCTOR, session authorship and current ACTIVE assignment. PATIENT, ADMIN and mixed roles do not gain access. No new Profile-active or CMP lookup is introduced.

- Success: `200 application/pdf`, `Cache-Control: no-store`, `Content-Disposition: attachment; filename="session-report-{reportSnapshotId}.pdf"`.
- Application errors: JSON using existing `SESSION_REPORT_SNAPSHOT_*` codes; 400 invalid canonical UUID, 401 unauthenticated, 403 denied, 404 authorized missing copy, 503 unavailable stored content or rendering. Framework authentication responses remain framework-owned.
- Rendering finishes before the response starts. A rendering exception never becomes a successful partial PDF or missing-evidence report.
- Names, emails and document numbers never enter the filename. Stored names appear in the authorized document only.

## One fixed template

A4 pages contain saved-copy reference/time, frozen patient/professional identity availability, session execution/configuration/limits, technical progress, independent stream summaries/provenance/completeness, and interpretation limits. Timestamps retain their saved UTC representation. Field names retain the existing contract terminology and units.

Confirmed absence prints `Sin evidencia analítica disponible`; unknown, unavailable and NOT_APPLICABLE values are not zero. COMPLETED means technical fulfillment, not clinical efficacy or complete Telemetry persistence. Sample counts do not mean hits; geometric coverage does not prove prescribed exploration. No anatomical lateralization or physiological fixation/saccade claims are inferred.

Raw coordinate tables, newly calculated trial metrics, signatures, PDF/A certification, automatic revisions, binary storage, queues, new services and live peer queries remain outside this increment. The source copy is immutable; identical PDF binary bytes across template/library revisions are not promised. Existing copies and JSON routes remain intact.

## Frozen Visual Search results

The table uses only the optional eleven-field `visualSearch` summary already stored in the selected immutable copy. It does not query Analytics, re-run a calculator, refresh the copy or infer missing results. Saved-copy validation reuses the existing strict codec: session/configuration/version/mode/origin/calculation provenance and count/median consistency must remain valid. A malformed present section fails through generic `Session report PDF unavailable`, never silent omission, zero substitution or a partial successful PDF.

The section `Visual Search - reported final results` displays data origin, calculation version, reported attempts, hits, omissions, interruptions, eligible searches and median search duration in milliseconds. Numeric medians retain exact integer/half-millisecond values in plain decimal notation; baseline JSON numeric decoding is unchanged. No ratios, accuracy, error counts, dwell/fixation metrics or completeness guarantee are introduced.

| Frozen copy | PDF presentation |
| --- | --- |
| `SYNTHETIC` | Explicit origin and `SYNTHETIC: test data, not patient evidence.` |
| `PRODUCER_REPORTED` | Explicit origin and producer-reported-outcome warning: not algorithmically verified clinical success. |
| Zero reported results | Present table with zero counts and median `Not available`, not an omitted section. |
| No successful eligible searches | Saved non-success counts remain visible; median is `Not available`, never a fabricated zero. |
| No optional section in an older/omitted copy | `Visual Search results not included`, not zero outcomes; no backfill. |

These are reported final results, not the total number of presented targets. Baseline `Sin evidencia analítica disponible` remains independent: a valid Visual Search table can coexist with confirmed absence of baseline gaze evidence. The table and fixed source limitations reuse the existing two-column layout and stay together on one page; it is placed after existing analytical sections so heatmap/gaze drawing code and geometry remain unchanged. No new raw identifiers are added to this table beyond the existing report provenance.

## Frozen Standard Scanning results

Render `Standard Scanning - reported final results` only when the immutable copy contains
its optional `standardScanning` summary. Reuse the Visual Search table layout with fixed
modality-specific labels, not a new renderer or calculator. Display data origin, calculation
version, reported attempts, detected targets, omissions, interruptions, eligible localizations
and median localization duration in milliseconds. Preserve exact integer/half-millisecond
medians in plain decimal notation; null is `Not available`.

Confirmed zero results remain a present table with zero counts and unavailable median.
Nonzero omissions/interruptions without detections retain their counts, not a zero duration.
Absent sections add no Standard Scanning heading, table, notice or invented values; older
copies and existing Visual Search output remain unchanged. The original Visual Search
not-included notice is retained as part of the previous template.

Validate present content with the existing strict saved-selection codec: version, origin,
count relationships, mode and frozen session/configuration binding. Malformed or contradictory
simultaneous modality sections fail through generic `Session report PDF unavailable`; do not
render partial results or silently omit invalid data. Never query peers, recalculate or mutate
stored JSON. Keep synthetic/producer-reported provenance and the non-diagnostic limitations.
The table follows existing graphics; heatmap and gaze plot drawing/geometry are unchanged.

Offline tests cover both origins, zeros/null/absence, exact large medians, malformed content,
source/peer/write immutability and existing chart geometry. Optional exports use the existing
`clinical.pdf.visual-output` base with `.standard-scanning-*` suffixes and relevant page PNGs.
Offline proof is not HTTP/CDI execution proof; no new harness or HTTP change is introduced.

## Frozen Sustained Fixation results

The optional `sustainedFixation` section uses the existing two-column table after the analytical evidence. It displays the saved data origin, calculation version, reported attempts, accumulated valid time inside the target in milliseconds, and completed targets. These are reported exercise outcomes, not continuous dwell, physiological fixations, or evidence of clinical efficacy. The source notice distinguishes synthetic data from producer-reported outcomes.

| Saved section | PDF behavior |
| --- | --- |
| Absent | Omit the table without changing historical copies. |
| Valid, including zero results | Display the saved values exactly, independently of baseline gaze availability. |
| Present but invalid or inconsistent with the saved session, frozen configuration or modality | Reuse strict saved-copy validation; the download returns the existing generic 503, never a partial PDF. |

The renderer does not recalculate or refresh these values, modify the copy, or change existing heatmap and gaze plot rendering.

## Frozen Compensatory Saccade results

When the saved copy contains a valid `compensatorySaccade` section, show the existing two-column table with the saved origin, calculation version, reported attempts, reached targets and exact median time to target in milliseconds. The median includes confirmation dwell and covers successful attempts only. It is not a physiological measurement or evidence of clinical efficacy. Keep the existing notice for each origin.

An absent section adds no table. A valid section shows saved zero counts as zero and a null median as `Not available`. The table is independent of baseline gaze evidence. An invalid present section fails via the existing generic PDF error and never produces a partial document.

No recalculation, peer calls, copy changes, regional breakdown or chart changes are part of this table.

## Frozen heatmaps

Each saved stream uses only its own `heatmap.grid`: 10 rows, 10 columns and 100 non-negative integer raw gaze-sample counts. Cells follow the existing row-major order, with x increasing rightward and y increasing downward. No rotation, mirroring, interpolation, smoothing or confidence filtering is applied. Independent streams are never merged.

The section is titled `Observed gaze-sample distribution`. Zero is neutral white; increasing counts use darker blue, with the largest saved cell count defining that chart's scale. The caption gives the 0-to-maximum count-per-cell legend, total gaze samples, stream reference, sequence-gap count and session-boundary limitation. Relative colors are not comparable across streams or reports. The title, grid, legend and warnings stay together on one page; existing calculation version/revision and completeness metadata remain in the stream's preceding provenance sections.

| Saved evidence | PDF behavior |
| --- | --- |
| Valid grid with gaze samples | Render the saved counts, without recalculating analytical results. |
| Valid grid with zero gaze samples | Print `No gaze samples were observed in this saved stream.` without a fabricated grid or analytical-absence notice. |
| Confirmed absence of analytical evidence | Preserve `Sin evidencia analítica disponible`; no heatmap section. |
| Observations with sequence gaps or unknown session boundary | Show the observed grid and limitations; never claim whole-session completeness. |
| Invalid grid, unsupported coordinates/weighting, overflow or mismatch with its saved gaze count | Fail through the existing generic rendering error; never substitute analytical absence or a partial successful PDF. |

Heatmap intensity is a distribution of raw observed samples, not dwell/fixation time, target accuracy, anatomical hemifield assessment or therapeutic efficacy. No endpoint, snapshot schema, font, dependency or service is added.

## Frozen gaze plots

Each stream renders only its saved `gazePlot.points`, bounded by the existing page size (at most 500). The section is titled `Estimated gaze-point trajectory`. A normalized square uses x increasing rightward and y increasing downward, with fixed-size square markers and thin straight segments in saved sequence/sample-index order. Marker size does not represent duration or confidence. Points are not reordered by timestamp, filtered, smoothed, interpolated or joined across streams. `segmentBreakBefore` prevents connecting the preceding point; the first point never connects outside this selection.

| Saved selection | PDF behavior |
| --- | --- |
| Two or more points | Draw points and only the permitted connecting segments. |
| One point | Draw one marker without a fabricated path. |
| Empty points | Print `No points in this saved selection.`; this alone does not mean the session has no gaze samples or no analytical evidence. |
| `hasNext=true` or `startsAtBeginning=false` | Show `Partial saved selection`; never fetch more points or claim a complete trajectory. |
| Gaps or unknown session boundary | Retain limitations next to the chart; do not bridge marked breaks. |
| Invalid coordinates, point order, timestamps, required flags or page/count bounds | Fail through the existing generic rendering error, without silent omission, source mutation or a partial successful PDF. |

The title, chart and captions stay together on one page. Captions identify the stream, number of displayed points, coordinate orientation and selection/completeness limitations. Coordinates are drawn, not printed as a raw table. The graphic is not a physiological fixation/saccade analysis, target-acquisition result or assessment of therapeutic efficacy. No snapshot/API/schema, dependency or heatmap formula changes are required.

## Dependencies and supported text

- Apache PDFBox `3.0.8` via Maven Central; direct artifact `org.apache.pdfbox:pdfbox`, required transitive `pdfbox-io`, `fontbox` and `commons-logging`. The Quarkus BOM manages `org.bouncycastle:bcpkix-jdk18on` for PDFBox's native compilation dependencies; reports are not signed.
- The renderer embeds unmodified Source Sans 3 Regular `3.052R`. The original SIL OFL 1.1 notice is at `src/main/resources/fonts/SourceSans3-OFL.md`; the native-micro image copies it to `/usr/share/licenses/SourceSans3-OFL.md`. Native configuration also includes FontBox's `org/apache/fontbox/cmap/Identity-H` resource.
- Spanish glyph coverage was checked, and an isolated native download verified that the extracted report text preserved the accent in “Sin evidencia analítica disponible”. The font does not cover all Unicode: unsupported glyphs fail closed with generic 503 rather than silently replacing personal names. Complex-script shaping and arbitrary emoji are not certified.
- Each download owns and closes its own PDF document/font/streams. Only packaged resources are used; no client fonts, URLs or PDF templates are loaded.

Sources: [PDFBox dependencies](https://pdfbox.apache.org/3.0/dependencies.html), [PDFBox font/layout limitations](https://pdfbox.apache.org/3.0/faq.html), [Adobe Source Sans 3 release](https://github.com/adobe-fonts/source-sans/releases/tag/3.052R), and [Adobe's license for this release](https://github.com/adobe-fonts/source-sans/blob/3.052R/LICENSE.md).

## Offline verification

Use a scratch copy outside the repository so Maven output and generated synthetic documents do not alter the working tree. Resolve the approved dependencies first, then run Maven `-o` with plain JUnit selectors:

```text
-Dtest=PdfBoxSessionReportRendererTest,DoctorSessionReportPdfControllerTest,DoctorSessionReportSnapshotControllerTest,GetSessionReportSnapshotServiceTest,SessionReportServicesConfigurationTest
```

Add `-DargLine= -Dquarkus.analytics.disabled=true`. The renderer test optionally writes synthetic PDFs using the explicit `clinical.pdf.visual-output` base path: long-name, `.normal.pdf`, `.evidence.pdf`, `.heatmap.pdf`, `.zero.pdf` and `.gaze.pdf`. Visual Search exports add `.visual-search-synthetic.pdf`, `.visual-search-producer.pdf`, `.visual-search-zero.pdf`, `.visual-search-no-hits.pdf`, `.visual-search-legacy.pdf` and `.visual-search-combined.pdf`, with relevant page PNGs rendered using the existing PDFBox dependency. Tests inspect stored-order cell rectangles and rasterized cell-center colors, per-chart scales, same-page legend/warnings, absence/zero distinctions, malformed-grid rejection and unchanged source/peer/write counts. Gaze checks inspect marker/segment geometry, orientation, segment breaks, independent streams, same-page warnings, empty/single-point/partial selections and invalid saved inputs. Inspect rendered pages as well as extracted text: successful extraction alone does not prove visual legibility.

Visual Search checks cover both origins, exact fractional/large medians, zero/null/omitted distinctions, strict malformed-section rejection, frozen JSON and unchanged peer/write counts. A combined fixture checks that saved heatmap cells and gaze markers/segments retain their geometry. A baseline-versus-correction text comparison permits only the new legacy not-included notice and associated pagination; it does not require identical PDF binary bytes.

Offline proof does not establish the updated PDF over HTTP/CDI runtime, native-image resource support, Docker deployment, accessibility compliance, or clinical validation. Those are not claimed here.

## Frozen Systematic Exploration results

Render `Systematic Exploration - reported final results` only when the immutable copy contains
its optional `systematicExploration` summary. Reuse the existing two-column layout, showing
saved data origin, calculation version and reported traversal count. In saved order, show each
traversal reference, final outcome, visited regions out of four, coverage percentage and elapsed
duration in milliseconds. Never sum durations, average coverage, reconstruct visits or query peers.

- Absent section: no heading, table, notice or backfill; previous PDF content remains unchanged.
- Valid empty list: retain origin/version/count zero and `No reported traversals`; no invented
  traversal or zero duration.
- Present invalid/null/inconsistent content: reuse the strict saved-selection validator and
  generic rendering failure, never silent omission or a partial successful download.
- Keep the existing saved-summary maximum of 1,000 traversals, A4 margins, fonts, wrapping and
  automatic page continuation. Render all saved rows without silent truncation. The current
  layout has no explicit hard maximum page count; this increment does not introduce one.

Synthetic data remains explicitly marked as test data, not patient evidence. Producer-reported
outcomes are not algorithmically verified clinical success; region visits and coverage do not
establish visual-field recovery or therapeutic efficacy. Existing heatmap/gaze drawing and other
modality tables are unchanged. No snapshot/schema/API changes or new dependencies are required.

Offline renderer tests cover both origins, all outcomes, absence versus empty, invalid content,
maximum-list pagination, frozen source and unchanged peer/write counts. Visual exports use
`.systematic-exploration-synthetic`, `.systematic-exploration-producer_reported` and
`.systematic-exploration-empty` suffixes with the existing `clinical.pdf.visual-output` setting.
HTTP/CDI download validation remains separate; offline rendering does not prove that boundary.
