# AFP Fidelity Program Plan

## Target
Deliver near-IBM parity rendering for AFP input by implementing true AFP object/font/overlay behavior across the full solution (`afp-engine`, `afp-api`, `afp-cli`, metadata/diagnostics, build/test/CI).

## Quality Gates
- Visual fidelity:
  - `fidelityScore >= 0.95` on baseline corpus.
  - `averagePixelDiffRatio <= 0.03` on baseline corpus.
- Text fidelity:
  - `tokenRecall >= 0.98`
  - `tokenPrecision >= 0.98`
- Structural fidelity:
  - page count parity for all baseline files.
  - object count parity (text/image/graphics/overlay-region objects).
- Determinism:
  - stable output checksums for repeated runs with identical inputs/options.

## Scope Across Solution
### `afp-engine`
- Implement full AFP render pipeline:
  - logical page model
  - resource/overlay scope model
  - object graph + paint order
- Complete IOCA/image decoding paths (standard + non-standard encodings).
- Complete graphics/vector primitive rendering.
- Complete coded-font/code-page fidelity (SBCS/DBCS mixed runs).
- Add renderer diagnostics for unresolved resources, font substitutions, and decode fallbacks.

### `afp-api`
- Add explicit rendering policy surface:
  - render mode (`native-fidelity`, `styled-semantic`, `debug`)
  - fidelity/strictness level
  - resource resolution policy and fallback behavior
- Add structured diagnostics model for:
  - font resolution decisions
  - resource lookup events
  - object decode outcomes

### `afp-cli`
- Expose new fidelity controls and diagnostics verbosity:
  - `--renderMode`
  - `--fidelityLevel`
  - `--resourcePolicy`
  - `--diagVerbosity`
- Keep strict defaults aligned with production fidelity mode.

### Metadata/Diagnostics
- Extend `meta.json` and `diag.json` to include:
  - per-page object render summary (text/image/vector/overlay).
  - resource resolution trace (resolved/missing/substituted).
  - font/code-page mapping table and fallback decisions.

### Build/Test/CI/Docs
- Expand corpus and baseline comparator.
- Add regression suites by feature area.
- Enforce quality gates in CI.
- Keep plan/changelog/docs as build artifacts (`documentationManifest` + plan-next-step).

## Workstreams
### Workstream A: Object & Overlay Composition (highest impact)
- Objectives:
  - correct layering and scope semantics for overlays/resources.
  - correct placement/size/orientation for all major object classes.
- Tasks:
  - formalize page object graph with scope stack (`BMO/EMO`, resource begin/end scopes).
  - implement deterministic paint order per AFP semantics.
  - complete graphics object rendering beyond placeholders (rules, boxes, strokes/fills).
  - finalize image object placement with clipping and orientation transforms.
- Exit criteria:
  - overlays visibly match IBM layering behavior on baseline corpus.
  - non-text elements present and correctly positioned.

### Workstream B: Font & Text Fidelity
- Objectives:
  - glyph, spacing, and line flow parity.
- Tasks:
  - coded font resolution from AFP resources (`CFI` + resource objects).
  - code page mapping matrix with SBCS/DBCS mixed-run handling.
  - metric-compatible substitutions and kerning/advance tuning.
  - remove broad semantic replacement; keep low-signal run-level fallback only.
- Exit criteria:
  - reduced spacing drift and substitution artifacts.
  - line breaks and block flow materially closer to IBM output.

### Workstream C: Image/IOCA Decode Completeness
- Objectives:
  - decode and render non-standard IOCA payloads robustly.
- Tasks:
  - implement IOCA decoding adapters for common AFP image variants.
  - add validation/fallback pipeline with confidence scoring and diagnostics.
  - add per-format decode tests from real sample corpus.
- Exit criteria:
  - major image payload classes decode without fallback placeholders.

### Workstream D: API/CLI/Diagnostics Integration
- Objectives:
  - expose fidelity controls and make rendering decisions auditable.
- Tasks:
  - API additions for render/fidelity/resource policies.
  - CLI flags and help/docs.
  - richer `diag.json` and metadata sections for render traceability.
- Exit criteria:
  - users can explicitly select/render policies and inspect outcomes.

### Workstream E: Hardening, Corpus, and CI
- Objectives:
  - prevent regressions and track progress objectively.
- Tasks:
  - build corpus matrix (forms, statements, mixed-language, heavy overlays).
  - per-feature integration tests.
  - CI gates with artifact publishing (`fidelity-report.json`, diff images, diagnostics).
- Exit criteria:
  - repeatable gate pass on full corpus with published artifacts.

### Workstream F: Print-Centric Reverse Engineering (pivot)
- Objectives:
  - model AFP interpretation as a printer-like sequential byte stream.
  - learn undocumented control-code behavior by correlating AFP stream tokens with output operator tokens.
- Tasks:
  - implement a dedicated print-centric byte-walk engine (separate from native layout renderer).
  - emit sequential token trace: structured fields, PTX control codes, and per-byte evidence.
  - add cross-examination report between AFP stream tokens and output operator/token stream.
  - use trace findings to back-port proven control-code handling into the native fidelity renderer.
- Exit criteria:
  - reproducible trace reports for corpus files.
  - at least one concrete renderer improvement derived from trace evidence.

### Workstream G: SQL Decision Intelligence (dedicated)
- Objectives:
  - make prioritization drivers explicit, queryable, and realm-local.
  - containerize decision context at granular scope (including sub-boilerplate level).
- Tasks:
  - add `decision_log` and `decision_dependency` tables to each realm database.
  - add commands to upsert/link decisions and export a cross-realm decision priority queue.
  - wire decision-priority reporting into PM refresh/report pipeline and artifacts.
  - seed active workstream decisions with smallest-change/biggest-reward impact scoring.
- Exit criteria:
  - each realm has live decision records with scope granularity.
  - PM reports include ranked cross-realm decision queue that explains current execution drive.

## Milestones
### Milestone 1: Composition Correctness
- Deliver:
  - completed overlay/resource paint order model.
  - graphics object rendering replacing placeholders for core primitives.
- Success:
  - visible non-text parity improvement on baseline corpus.

### Milestone 2: Text/Font Parity
- Deliver:
  - coded-font/code-page fidelity path with diagnostics.
  - mixed SBCS/DBCS run handling.
- Success:
  - measurable reduction in text spacing/glyph regressions.

### Milestone 3: IOCA Coverage
- Deliver:
  - non-standard IOCA decode support with robust fallback diagnostics.
- Success:
  - image-heavy corpus pages render without generic placeholders.

### Milestone 4: Production Gates
- Deliver:
  - CI fidelity gates + expanded corpus + docs automation.
- Success:
  - `fidelityScore >= 0.95` and `averagePixelDiffRatio <= 0.03` on agreed corpus.

## Proposed Immediate Next Execution Sequence
1. stand up and use SQL decision intelligence (`decision_log` per realm + cross-realm priority report) as the default execution driver.
2. keep SQLite-backed project/boilerplate state stores current (`projectStateDb`, `boilerplateStateDb`) and route workflow/reporting outputs through them by default.
3. strengthen per-image resource binding in `sample.afp`: pair each `BIM` object with candidate embedded resources using token overlap + placement/size heuristics, then only promote page-level fallback when object-level binding confidence is below threshold.
4. extend print-centric cross-exam output to include `BIM`/`BOC` token timeline slices and inferred binding decisions so renderer back-ports are directly evidence-linked.
