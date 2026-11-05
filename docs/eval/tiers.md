# Corgi's three paths, side by side

Where Corgi runs is its **path**: Cloud (Amazon Bedrock), Self-hosted (Ollama on your own GPU server, over Tailscale)
or Local (Ollama on this computer). This page puts the Phase 9 measurements side by side. Every number comes from a
committed report in this folder, named in each table. The model ladder that picked Ministral 3 14B (six open models on
Bedrock) is [run 7 and 7b in README.md](README.md#run-7-the-model-ladder-phase-9-step-9a) and isn't repeated here.

All runs are on the dev sets, which the prompts, rules and thresholds were tuned on, so the numbers are optimistic by
construction. They compare paths with each other, not against the world.

## Chat (dev set version 6, prompt `chat-10`)

Scored on the 58 questions outside IDENTITY. Turns, tool choice and first-try grounding count all 68 turns (67
answered on Self-hosted: one turn failed). Latency is the median turn.

| | Cloud: Sonnet 4.6 | Cloud: Ministral 3 14B (full precision) | Self-hosted: Ministral 3 14B (Q4) | Local |
|---|---:|---:|---:|---|
| Questions correct (58) | **57** | 49 | 48 | not yet measured |
| IDENTITY (2) | 2 | 1 | 2 | |
| Tool choice | 68 / 68 | 67 / 68 | 67 / 68 | |
| Invalid tool arguments | 0 | 15 | 0 | |
| Grounded on the first try | 67 / 68 | 52 / 68 ¹ | 66 / 67 | |
| Search questions | 8 / 8 | 7 / 8 | 8 / 8 | |
| Median turn | 2.7 s | 1.5 s | 3.4 s ² | |
| Cost of the run | $0.75 (cached) ³ | $0.26 (uncached) | $0 in tokens; the server's hourly rate ⁴ | $0 |
| Report | [`chat-dev-90b33b048420-20261026T211238Z`](chat-dev-90b33b048420-20261026T211238Z.md) (run 6) | [`chat-dev-ac0f1e0bc0ca-20261028T161943Z`](chat-dev-ac0f1e0bc0ca-20261028T161943Z.md) (run 7) | [`chat-dev-bb822aa6b00e-20261103T120333Z`](chat-dev-bb822aa6b00e-20261103T120333Z.md) | |

¹ Run 7 ran on `ac0f1e0`, before the figure extractor (9a-2) and citation markers (9a-3) read Unicode spaces, dashes
and brackets. The Self-hosted run, on `bb822aa`, had both fixes. So the grounding columns aren't like for like, and
part of the gap is the fix, not the path.
² Over Tailscale, on a g6.xlarge (one NVIDIA L4, 24 GB). The one ERROR, d-l01, was the cold model load running past
`max-turn-time` (60.2 s against 45 s): the warm-up after Start hadn't run (`docs/plans/phase-9.md`, 9c-11 notes).
³ Prompt caching on, at the earlier Global price, about 10% low ([README.md](README.md), known issues).
⁴ $0.8048 an hour for a g6.xlarge in us-east-1 while it runs (the app's `hourly-rates`); the run took 407.9 s of wall
time. The disk is billed while the server is stopped.

**Quantization cost one question.** The same model, full precision on Bedrock, scored 49; quantized on the server, 48.
The server's run also had no invalid tool arguments, against 15 on Bedrock (8 of them the invented `account` parameter).

## Categorization (dev set, 148 rows)

| | Cloud: Haiku 4.5 at 0.80 | Self-hosted: Ministral 3 14B at 0.80 | Self-hosted: Ministral 3 14B at 0.85 | Local |
|---|---:|---:|---:|---|
| Overall correct, with examples (C) | 95.9% | 96.6% | 95.3% | not yet measured |
| Wrong | 0 | 5 | 1 | |
| Left for review | 6 | 0 | 6 | |
| Without examples (B) | 95.9%, 0 wrong | 89.2%, 0 wrong, 16 review | — | |
| One batch (16 items) | 3.0 s | 11 s warm, 40 s cold | | |
| Embeddings, median | — | 16 ms | | |
| Report | [`dev-fab8a1cdac7a-20261019T160014Z`](dev-fab8a1cdac7a-20261019T160014Z.md) | [`dev-89e693bc1875-20261104T112142Z`](dev-89e693bc1875-20261104T112142Z.md) | the same run's threshold sweep | |

Rules alone categorize 87.2% on every path. The 0.85 column is the Self-hosted run's own sweep, from the confidences it
saved, not a second run.

- **The paths use different thresholds.** Ministral's 5 wrong answers at 0.80 were all confident, so Self-hosted and
  Local ask for 0.85 (`finalysis.ai.categorization.min-confidence-by-path`), and Cloud keeps 0.80.
- **Examples fired only with nomic.** 3 of the 16 items sent had a nomic example at or above the 0.65 floor. With
  examples, the 14B went from 89.2% to 96.6%.

## Search by meaning (the dev sweep, 22 queries, 25 targets)

| | Cloud: Titan Text Embeddings V2 | Self-hosted and Local: nomic-embed-text |
|---|---:|---:|
| Targets found at the lowest floor (0.30) | 8 / 25 | 24 / 25 |
| Candidates found by meaning (all candidates) | 1 (9) | 117 (217) |
| Questions with every target | 3 / 8 | 8 / 8 |
| Floor set | 0.40 (barely matters) | 0.50: 23 / 25 targets, 8 / 8 questions, 113 unrelated candidates |
| Report | [`chat-sweep-dev-0fb5414abce8-20261103T141355Z`](chat-sweep-dev-0fb5414abce8-20261103T141355Z.md) | [`chat-sweep-dev-89e693bc1875-20261104T111902Z`](chat-sweep-dev-89e693bc1875-20261104T111902Z.md) |

On merchant names, Titan finds almost nothing by meaning. Its hits are name and word matches, which the search makes
anyway. Nomic finds nearly every target.

## Local

**Not yet measured.** The 2019 Intel MacBook has no GPU Ollama can use (Ollama's Metal acceleration is Apple Silicon's),
and Phase 9 step 9d, the laptop measurements, is deferred (`docs/plans/phase-9.md`, 9d notes). The Local path is built
and tested against a stub Ollama in the test suite: the same providers, payload checks and settings as Self-hosted,
reached at `127.0.0.1`. Nothing has been measured on it, so its row stays empty until a run on hardware with a
supported GPU.
