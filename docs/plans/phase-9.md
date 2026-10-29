# Phase 9 plan: Corgi on three tiers

Status: **approved** (2026-10-28) with the answers under "Decisions at approval" (§16), which override anything older
in this file; the body below has been brought in line with them. Phase 9 starts from tag `phase-8-done`, which comes
after the owner's Android test (Phase 8 step 6); **9a doesn't start until the owner confirms that tag.** Branch `v1`.
Requirements as of `docs/requirements.md` v1.79.

Goal: the user picks where Corgi runs.

| Tier | Where | Reached through | Models |
|---|---|---|---|
| **Cloud** | Amazon Bedrock (built, Phases 4 and 6) | the AWS SDK, the `finalysis` CLI profile | Sonnet 4.6 (chat), Haiku 4.5 (categorization), Titan V2 (embeddings) |
| **Self-hosted** | an EC2 GPU instance the user owns | Tailscale only (no inbound rule, no public port) | 9a's model and nomic-embed-text, through Ollama |
| **Local** | this computer | Ollama on `127.0.0.1` | 9a's model (its smallest usable size) and nomic-embed-text |

Everything else in the app is identical across tiers: redaction, the outbound guard, the tools, grounding, citations,
the turn limits other than cost, the prompt (`chat-10`), the eval and its scoring. Only the badge, the banner wording
and the cost lines differ. The model never does arithmetic on any tier (GR-01).

**The word is "tier" everywhere; "path" is retired** (§4.5). "Tier" already means something else in AI-02, UI-06, QA-05
and the roadmap's Phase 10 ("standard tier", "deep tier", "hardware tiers": model sizes); those become **"size
class"** (§13, §16 decision 1), so "tier" means only where Corgi runs.

Out of scope: field-level encryption (SEC-05), goals, the chat backlog (h-u10, h-x06, the holdout guard per model;
Phase 10), Windows, model size choices beyond 9a's pick (AI-02), `num_ctx` and KV cache settings (AI-05's Phase 10 part),
keeping models loaded (AI-04).

Working rules: one step per commit (small follow-ups are fine); before every commit `caffeinate -i ./mvnw clean
verify` in `backend/` and `npm test` in `frontend/` (and `npm run test:layout` where the UI changes), Maven's summary
counts reported; push `v1`; stop for review. **No live AI call by me, and no EC2 or Tailscale action by me:** the owner
runs every eval, every `create.sh`/`destroy.sh`, every Start and Stop. The categorization holdout is never run or
named; the chat holdout's question file is never opened. Nothing outside the repo is opened; `~/.aws/credentials`, any
key and any Tailscale auth key are never read, printed or copied. Synthetic data only. Every change ships with tests:
the Ollama provider against a stub server on loopback, the CloudFormation template with `cfn-lint` and a unit test on
its parameters and resources.

---

## 1. What the plan was written from

- The owner's Phase 9 brief (2026-10-28).
- The code at `991330b` (Phase 8 step 7): `AiMode` (`OFF`, `CLOUD`, `LOCAL`), `AiProperties`, `AiStatus` (the `ai`
  block of `/api/status`), `AiStartupCheck`, `AiPrices`, `ChatRoleOptions` (built on the model's own options, no
  provider class), `TextEmbedder`/`SpringAiTextEmbedder`, `com.finalysis.ai.bedrock` (the only package allowed to touch
  Bedrock classes, `PackageDependencyTest`), `application-aws.yml` (Bedrock chosen by profile at startup through
  `spring.ai.model.chat=bedrock-converse`), `application.yml`'s chat limits (`max-turn-cost` 0.15, `max-daily-cost`
  1.00, `max-turn-time` 45 s).
- The embedding tables: `merchant_embedding` (V17) and `search_embedding` (V23), both `vector(1024)` with an HNSW
  index, keyed by model. The next migration is **V25**.
- The eval: `ChatLiveEvalIT` (`@ActiveProfiles("aws")`, `-Deval.model`, `-Deval.maxTurnCost`), the price file
  `src/test/resources/eval/model-prices.json`, the report (tool choice, invalid arguments, first-try grounding are
  columns already), `docs/eval/README.md` (runs 1 to 6). The dev set is **version 6: 60 questions**, the 58 earlier
  ones plus two IDENTITY questions.
- The frontend: `corgi.ts` (`pathBadge`, `LOCAL_COST`, `costText`), `corgi.test.ts`, `CloudAiBanner.tsx`,
  `Shell.test.tsx`, `SettingsPage.tsx` (the Corgi section, read-only today), the `.path-badge` styles in `index.css`.
- The IAM policy as written in `docs/plans/phase-4.md` §11.2 and `docs/plans/phase-6.md` §2.4 (the policy itself lives in
  the owner's AWS account, not in the repo).
- `docs/requirements.md` v1.79 (AI-01 to AI-07, SEC-01, UI-05, UI-06) and `docs/plans/roadmap.md` v4.

---

## 2. Step 9a: small open models on Bedrock

### 2.1 What runs

The same harness, the same dev set (version 6), the same prompt (`chat-10`), once per model, by the owner:

| Model | Why it's here | Bedrock id |
|---|---|---|
| Ministral 3 8B | smallest that may still call tools well; fits the laptop | from the catalog |
| Ministral 3 14B | the same family one size up | from the catalog |
| Gemma 3 12B | US-origin (AI-07) | from the catalog |
| gpt-oss-20b | US-origin (AI-07), trained for tool use | from the catalog |

I don't guess ids. The owner lists them (read-only commands, nothing sent to a model):

```sh
aws bedrock list-foundation-models --region us-east-1 --profile finalysis \
  --query "modelSummaries[?contains(modelId,'ministral') || contains(modelId,'gemma') || contains(modelId,'gpt-oss')].[modelId,modelName,inferenceTypesSupported]" \
  --output table
aws bedrock list-inference-profiles --region us-east-1 --profile finalysis \
  --query "inferenceProfileSummaries[].[inferenceProfileId,status]" --output table
```

Any model the account can't access, or whose Converse API doesn't support tool use, is skipped and recorded as such in
the README with the reason; I don't work around it. The chat loop needs tool calling through Converse, so the owner's
smoke test per model is the existing `LiveChatSmokeTest` (one synthetic question, one tool call) before the dev run.

### 2.2 What I change (code, no live call)

- **Price file** (`model-prices.json`) and the `aws` profile's `finalysis.ai.prices`: one entry per model, input and
  output per million, **no cache prices** (so chat runs uncached for them, as it already does for any model without
  them). The owner enters the prices from the Bedrock pricing page; I add the entries with placeholders the owner fills,
  and `ChatPricesTest` keeps the two files in agreement.
- **IAM additions**, written in this plan's step notes as exact text: `bedrock:InvokeModel` on each model's
  `foundation-model` ARN (and its inference profile ARN if it is served through one). Nothing else.
- **`-Deval.maxTurnCost` per model**, sized the way Sonnet's is: the startup check's largest round
  (12,000 input tokens at the input price plus 1,500 output tokens at the output price) times the same factor Sonnet has
  (0.15 / 0.0675 ≈ 2.2), rounded up to the cent. The step notes give the four values once the prices are in, so the
  limit allows the same number of worst-case rounds on every model.
- **The report**: no change needed for the columns; I add one line to the header, "Model family: open (no prompt
  caching)", so the README table can't confuse uncached and cached costs.

### 2.3 How the model for 9c is picked

- Ranked by **questions correct on the 58 non-IDENTITY questions** (so the threshold below reads against the same 58
  the brief names); IDENTITY reported apart, as in run 6 (§16 decision 3).
- **Tiebreak: tool choice**, then invalid arguments (fewer is better), then first-try grounding.
- **The floor is 45/58.** If none reaches it, the README says so in one sentence, and 9c still ships with the best one,
  labelled with its score everywhere the model is named (Settings, README, comparison doc).
- All four runs go into `docs/eval/README.md` as "Run 7: small open models (Phase 9 step 9a)", with one table next to run
  6 (Sonnet 4.6, `chat-10`): questions, turns, tool choice, invalid arguments, first-try grounding, fallbacks shown,
  cost, median latency.

**Caveat recorded with the pick:** Bedrock serves these models at full precision; Ollama serves a quantized build
(usually Q4). 9c's rerun on the instance measures the gap; the pick isn't revisited unless the owner asks.

---

## 3. The tier model (design for 9b-1 and 9b-2)

### 3.1 From profiles to a setting

Today the mode is fixed at startup by the Spring profile (`aws` → `CLOUD`, none → `OFF`). The brief needs a switch at
runtime without restart. Approved (§16):

- **`AiTier`** replaces `AiMode`: `OFF`, `CLOUD`, `SELF_HOSTED`, `LOCAL`. **A fresh install selects Local** ("local AI
  is the default", §16 decision 2). The selected tier and whether it is usable are separate: if Ollama isn't
  reachable on the selected tier, Corgi shows as off with "Install Ollama or choose a tier in Settings", AI features
  are skipped (categorization falls back to rules, as with AI off today), and the rest of the app works (import,
  analytics, rules). Reachability is checked at unlock, on switch and before each AI run (`GET /api/version`, short
  timeout), never by a background poll that would keep a Self-hosted instance awake. `OFF` remains selectable.
- **Which tiers are available** is fixed at startup: Cloud only with the `aws` profile (it holds the Bedrock and EC2
  credentials' profile name; GR rule "Bedrock must be enabled explicitly" holds); Self-hosted and Local always.
- **Which tier is selected** is the user's: `app_setting` key `ai.tier`, plus `ai.selfhost.name` (the instance's
  Tailscale name) and `ai.selfhost.region`. Inside the vault, so read at unlock (`VaultUnlocked`), never while locked.
- **Switching** is `PUT /api/settings/ai` (vault unlocked, as every settings write; 423 otherwise). It refuses a tier
  that isn't available, and Self-hosted without a name. The switch waits for in-flight AI work to finish
  (categorization run, chat turn: a read-write lock, the same idea as the vault's lock waiting for a request), then swaps
  the providers. A categorization run or chat turn starts on one tier and finishes on it.
- **The `local` profile is not needed any more**: Local is a setting. AI-03's text is rewritten (§9). `application-aws.yml`
  stops setting the mode; it makes Cloud available.

### 3.2 Providers

```
com.finalysis.ai
  AiTier, AiTierSettings (app_setting), AiProviders (holds the active three, swaps them)
  ChatProvider            -> ChatModel + options for one call (model id, tools, output cap)
  CategorizationProvider  -> ChatModel + options for structured output
  EmbeddingProvider       -> TextEmbedder + the model id recorded per vector
com.finalysis.ai.bedrock   Bedrock implementations: today's beans, unchanged behaviour
com.finalysis.ai.ollama    Ollama implementations: one class per role, built for a base URL and a tier label
com.finalysis.ai.selfhost  EC2 status, start, stop (AWS SDK ec2), the hourly rate (9c)
```

- **Bedrock implementations are today's code moved behind the interfaces**, with the existing tests unchanged apart from
  imports. Prompt caching stays Bedrock's (`BedrockPromptCache`); the Ollama providers report "no cache".
- **One Ollama implementation for Self-hosted and Local**: Spring AI's Ollama module (`spring-ai-ollama`, the same
  2.0.1 BOM), built programmatically from an `OllamaApi` with the tier's base URL, so a switch makes a new client
  without touching Spring's auto-configuration (`spring.ai.model.chat` and `embedding` stay `none` outside the `aws`
  profile). Local's base URL is `http://127.0.0.1:11434`, fixed; Self-hosted's is `http://<tailscale name>:11434`.
- **Framework tool execution stays off** on Ollama as on Bedrock (`internalToolExecutionEnabled(false)`); the loop and
  its validation are the app's.
- **Categorization's structured output** on Ollama uses its JSON schema `format`; the app validates every answer as it
  does today (closed schema, keys and categories checked), so a malformed answer is `INVALID_OUTPUT`, never trusted.
- **Timeouts per tier**: Bedrock's 30 s stays. Ollama's read timeout is a setting per tier, proposed 60 s Self-hosted and
  300 s Local (a CPU laptop). The turn's `max-turn-time` (45 s) is unchanged on every tier, as the brief says limits
  are; 9d records how often the laptop reaches it.
- **ArchUnit**: `PackageDependencyTest` already forbids `org.springframework.ai.ollama..` outside an allowed package; the
  rule gains `com.finalysis.ai.ollama` and `com.finalysis.ai.selfhost` as the only exceptions, each touching only its
  own provider's classes, and nothing outside `com.finalysis.ai` depends on them.
- **Redaction applies on every tier**, Local included. Nothing changes in `com.finalysis.ai.outbound`; the eval stays
  comparable and a model swap can't widen what the model sees.

### 3.3 The `ai` block of `/api/status`

`{"tier": "SELF_HOSTED", "available": ["CLOUD","SELF_HOSTED","LOCAL"], "reachable": true, "provider": "ollama",
"server": "<tailscale name>", "models": {...}}`. `reachable: false` is what the UI shows as "Corgi is off" with the
install-or-choose message (§3.1). `server` only for Self-hosted, the Tailscale name only (never an IP or URL). Never a region,
account, instance id or credential (as today). `mode` is renamed `tier`; the frontend and Postman change with it.

### 3.4 Settings → Corgi

Three tiers as radio cards, one selected; an unavailable tier is shown disabled with the reason ("needs the `aws`
profile"). Each card: the model id per role in use, the badge in its colour, and the banner wording it will show.
Switching asks once ("Switch Corgi to Local?"), then the badge and banner change without a reload (the status is
refetched). The Self-hosted card's controls are 9c's (§5.5).

### 3.5 Badge and banner

| Tier | Badge | Banner (SEC-01, pinned by tests word for word) |
|---|---|---|
| Cloud | amber, "Cloud · Bedrock" | as today: "Cloud AI is on (Amazon Bedrock): merchant names and amounts are sent for categorization, and chat questions with the figures they need are sent for answers." |
| Self-hosted | blue, "Self-hosted · <name>" | "Corgi runs on your own server (<name>): merchant names and amounts are sent to it for categorization, and chat questions with the figures they need are sent for answers." |
| Local | green, "Local" | "Corgi runs on this computer: nothing is sent anywhere." |

The banner shows on every tab for every tier except Off (today it shows only for Cloud). The colours get light and dark
tokens with a contrast check in `test:layout`.

---

## 4. Steps 9b-1 and 9b-2: the rest

### 4.1 Embeddings across tiers

nomic-embed-text has **768 dimensions**; Titan V2 has 1024; both tables are `vector(1024)` with an HNSW index. Approved
(§16):

- **V25**: both `embedding` columns become `vector` (no fixed dimension), each row's dimension checked against its
  model's by a constraint on `vector_dims(embedding)` matching a new `dims` column; the two HNSW indexes are dropped.
  Search becomes an exact scan filtered by model. A household has hundreds to a few thousand merchants; an exact cosine
  scan over that is milliseconds, and it is exact. A test seeds 5,000 vectors and pins the query under 50 ms on
  Testcontainers.
- **Rebuild on model change**: the index is already per model. After a switch, the merchant index refresh runs for the
  new embedding model (the existing `MerchantIndexRefresh`, capped per run, the Settings line shows progress). Vectors
  of the other model are kept, so switching back costs nothing; only same-model vectors are compared (unchanged).
- Cloud keeps Titan V2; Self-hosted and Local use `nomic-embed-text` (Ollama's `/api/embed`), with the search floor
  (0.30 today, tuned on Titan) a setting per embedding model. The floor for nomic is measured once with the existing
  dev-only retrieval sweep (`chat-sweep-dev`) run by the owner on Local or Self-hosted, and set from it in 9c.

### 4.2 Costs per tier

| Tier | Per-turn line | Chat costs (Settings) | Limits |
|---|---|---|---|
| Cloud | as today (token cost) | as today | per-turn and daily caps as today |
| Self-hosted | "running 42 min · ~$0.70" | the instance's running time and its estimate | no per-turn or daily cap; its controls are the hourly rate, minutes running, the idle watchdog and the billing alarm (9c) |
| Local | "$0.00 · local" (today's `LOCAL_COST`) | $0.00 | no cost caps |

Cost caps apply on Cloud only (§16 decision 5).

- **V26** adds `tier` to `chat_turn` (and to the categorization run's log line). Stored turn cost: the token cost on
  Cloud, `0` on Local, `NULL` on Self-hosted (its cost is time, not tokens). The daily cap and "this month" sum only
  Cloud turns, in SQL. Rows before V26 are `CLOUD`.
- **The Self-hosted line is computed in Java**: minutes since the instance's last start (EC2's `LaunchTime`, which EC2
  resets on every start) times the hourly rate / 60, `BigDecimal`, half-even to the cent. The frontend shows the string
  fields it gets (it never multiplies; `noArithmetic.test.ts` already enforces that).
- **Hourly rates** live in a price file per tier: `finalysis.ai.selfhost.hourly-rates` (per instance type, owner-entered
  from the EC2 pricing page) beside the token prices. A test checks every instance type the template allows has a rate.
- **The cost startup check** (refuse a model without a price while a cost limit is on) applies to Cloud only.

### 4.3 The eval on Ollama

- `-Deval.provider=ollama -Deval.baseUrl=http://<host>:11434 -Deval.model=<ollama tag>`; `bedrock` stays the default.
  On Ollama the run's turn cost limit is off and the run budget doesn't apply (no token cost); the report says so.
- The report header gains: tier (Self-hosted or Local, from the base URL being loopback or not), the base URL's **host
  only** (scheme, port, path, user info and query dropped by a parser with tests; a URL with user info or a query is
  refused before the run), the model tag and Ollama's version (`GET /api/version`).
- `-Deval.questions=d-l01,d-c01,...` runs a named subset (for 9d); a subset run is marked as such in its file name and
  header and is never comparable as a score.
- `NoCloudAiGuard` gains: in every suite but the eval, any Ollama client whose host isn't the test's stub on loopback
  fails the test.

### 4.4 Tests (9b-1, 9b-2)

- **Ollama providers against a stub server**: the JDK's `com.sun.net.httpserver` on a random loopback port (no new
  dependency), serving canned `/api/chat`, `/api/embed`, `/api/version` answers: a tool call, a malformed tool call, a
  structured categorization answer, an invalid one, a timeout, a 500, a missing model (404). The request bodies the stub
  receives are checked field by field, as `ChatOutboundPayloadTest` does for Bedrock: same redaction, same guard.
- Tier switching: unavailable tier refused; switch waits for an in-flight chat turn; locked vault 423; the status
  follows the switch; the providers are rebuilt with the new base URL.
- V25/V26 on Testcontainers: mixed 768 and 1024 vectors, a wrong dimension refused, search compares one model only;
  cost sums by tier.
- Frontend: Settings → Corgi (three cards, unavailable tier, switch, confirm), the three banners word for word, the
  three badges, cost lines per tier; `test:layout` for the cards and badges at 375 and 1280 px in both themes.
- Existing suites unchanged apart from `mode` → `tier`.

### 4.5 "Path" → "tier"

Every occurrence from Phase 7 goes: `pathBadge` → `tierBadge`, `.path-badge*` → `.tier-badge*`, `corgi.test.ts`'s
"the path badge…" test names, the `index.css` comment, `phase-7.md`'s references are left as history but README, UI and
code comments use "tier". A frontend test greps `src/` for `\bpath badge\b|pathBadge|path-badge` and fails on any.

---

## 5. Step 9c: the self-hosted tier

### 5.1 The template (`infra/selfhost/finalysis-selfhost.yaml`)

| Part | What |
|---|---|
| Parameters | `InstanceType` (`g6.xlarge` default, `g5.xlarge` allowed, nothing else), `ChatModel` (9a's Ollama tag), `RootVolumeGb` (default 80: drivers, CUDA, the model, nomic, headroom), `TailscaleKeyParameter` (the **name** of an SSM parameter, never the key, §5.2), `BillingAlarmUsd`, `AlarmEmail` |
| Instance | the **AWS Deep Learning Base GPU AMI (Ubuntu)**, NVIDIA drivers preinstalled, resolved per region from its public SSM parameter (`AWS::SSM::Parameter::Value<AWS::EC2::Image::Id>`, no hard-coded AMI id; §16 decision 8); `InstanceInitiatedShutdownBehavior: stop`; IMDSv2 required; tag `finalysis:role=selfhost` |
| Volume | gp3 root, `Encrypted: true` (the account's default EBS key), `DeleteOnTermination: true` |
| Network | the default VPC's subnet; a security group with **no inbound rules** and all outbound (Tailscale, packages, model pulls) |
| Role | `AmazonSSMManagedInstanceCore` plus `ssm:GetParameter` on the one key parameter; nothing else |
| User data | first boot only: a check that `nvidia-smi` sees the GPU (stop with a clear signal failure if not); Ollama; `ollama pull` the chat model and `nomic-embed-text`; Tailscale, `tailscale up` with the key read from SSM; Ollama bound to the Tailscale address; the watchdog; a creation signal so the stack completes only when all of that worked (30-minute timeout) |
| Billing | a CloudWatch alarm on `EstimatedCharges` above `BillingAlarmUsd`, an SNS topic with the e-mail subscription |

- **Ollama only on the Tailscale interface**: a systemd drop-in sets `OLLAMA_HOST=<tailscale IPv4>:11434`, written by a
  one-shot unit ordered after `tailscaled` that reads `tailscale ip -4`; a host firewall (`ufw`) allows 11434 on
  `tailscale0` only, as a second layer behind the empty security group. No SSH: admin through SSM Session Manager.
- **The idle watchdog**: a systemd timer every minute reads Ollama's request log (journald `[GIN]` lines) and counts only
  `POST /api/chat`, `/api/generate` and `/api/embed`; the app's status checks (`/api/version`, `/api/ps`) don't count,
  or the Test button would keep it alive. After **20 minutes** without one (and at least 20 minutes after boot), it
  runs `systemctl poweroff`; with the shutdown behaviour above, that **stops** the instance (no IAM permission needed,
  disk kept).
- **The billing alarm's region**: AWS publishes billing metrics only in `us-east-1`, and only after "Receive Billing
  Alerts" is turned on in the account. That is a **manual setup step**, printed by `create.sh` and written in the
  README (§12); the script doesn't change account settings. If the owner picks another region for the instance,
  `create.sh` deploys the alarm as a second, small stack in `us-east-1`.

### 5.2 The Tailscale key never sits in the template, user data or logs

A `NoEcho` CloudFormation parameter would end up in user data, which stays readable on the instance and through
`DescribeInstanceAttribute`. Instead (§16 decision 6):

1. `create.sh` reads the key from stdin (`read -rs`, no echo, `set +x`) and stores it as an SSM `SecureString` under a
   fixed name, passing it to `aws ssm put-parameter --cli-input-json file:///dev/stdin`, so the key is never on a
   command line (process arguments) or in shell history.
2. User data reads it once with `aws ssm get-parameter --with-decryption` into a mode-600 file under `/run` (memory,
   gone at reboot), runs `tailscale up --auth-key=file:<that file>` so the key is never an argument, and deletes the
   file; cloud-init output excludes the key (those commands run with tracing off).
3. After the stack completes, `create.sh` deletes the parameter. The instance stays in the tailnet across stops (a
   non-ephemeral node), so the key is never needed again.

The README recommends a **one-off, pre-approved, tagged** auth key with the shortest expiry Tailscale offers, and a
tailnet ACL that lets only the owner's devices reach the instance on 11434. Tests check: no parameter or output carries a
key; the key's name pattern never appears in the template; the scripts never pass the key as an argument.

### 5.3 Scripts (`scripts/selfhost/`)

- `create.sh`: asks for the region and the Tailscale auth key on stdin, the billing threshold and alarm e-mail;
  checks the GPU quota (`service-quotas get-service-quota` for "Running On-Demand G and VT instances", vCPUs ≥ 4) and
  stops with the request link if it's short; deploys the stack; deletes the key parameter; prints the instance's
  Tailscale name (`finalysis-selfhost`, set by `tailscale up --hostname`), the instance id, the IAM additions for
  `finalysis-dev` with the account and instance filled in, and the manual step: "Turn on Receive Billing Alerts
  (Billing → Billing preferences); billing metrics live in us-east-1, so the alarm is there."
- `destroy.sh`: asks for the region, deletes the stack(s) (instance, volume, role, alarm, topic) and the key parameter if
  it is still there; reminds the owner to remove the node from the tailnet.
- Both: `set -euo pipefail`, no key in any argument, idempotent on a second run, dry run with `--plan` that prints the
  calls without making them (what the tests drive).

### 5.4 IAM for the app (`finalysis-dev`)

`ec2:StartInstances` and `ec2:StopInstances` on `arn:aws:ec2:<region>:<account>:instance/*` with the condition
`aws:ResourceTag/finalysis:role = selfhost`. **`ec2:DescribeInstances` can't be scoped to a resource or tag** (EC2
doesn't support resource-level permissions for Describe calls), so it is `Resource: "*"`; it is read-only. The app finds
the instance by the tag filter. No `RunInstances`, no `TerminateInstances`: creating and destroying stay the scripts',
run by the owner with their own admin credentials.

### 5.5 Settings → Corgi → Self-hosted

- **Tailscale name** field with a **Test** button: `GET /api/version` on the name through the backend (Tailscale on this
  Mac resolves it); shows "Reachable, Ollama <version>" or the reason (not resolvable, refused, timeout). Names are
  checked against a pattern (letters, digits, hyphens, dots); never a URL.
- **Start** and **Stop** (Cloud credentials needed; shown disabled with the reason without the `aws` profile). They
  work from the phone too, behind an unlocked vault and a logged-in session like every other endpoint (§16 decision 9).
- **Status**: stopped, starting (with a progress line: "instance starting" → "waiting for Tailscale" → "loading the
  model", the last from a one-token warm-up call), running, stopping; polled every 5 s while it changes.
- **Minutes running this session and the estimate** at the hourly rate (§4.2).
- The app never holds the Tailscale key, an AWS key or the instance's address; it holds the name, the region and the
  tag.
- Stopped storage still costs (gp3 per GB-month); the Self-hosted card says so in one line with the volume size.

### 5.6 Tests (9c)

- `cfn-lint` on the template, run by a test (skipped with a JUnit assumption, and reported as skipped, if `cfn-lint`
  isn't installed; the README says how to install it).
- `SelfHostTemplateTest` parses the YAML: parameters and their allowed values; the security group has no ingress; the
  root volume is encrypted gp3; the role has exactly SSM core plus the one `GetParameter`; IMDSv2 required; shutdown
  behaviour `stop`; no output or default contains a key; user data binds Ollama to the Tailscale address and installs
  the watchdog with 20 minutes.
- The watchdog script against canned journal lines: status-check-only traffic stops after 20 minutes, a chat request
  resets it, nothing stops in the first 20 minutes after boot.
- `create.sh`/`destroy.sh` in `--plan` mode with a fake `aws` on `PATH`: the calls in order; the key only on stdin
  (process arguments recorded by the fake are checked); the quota refusal.
- `SelfHostControl` against a fake EC2 client: start, stop, the states, the tag filter, minutes and cost from a given
  `LaunchTime` and clock; no instance or two instances with the tag are errors in plain words.
- Settings screen: Test, Start, Stop, progress line, cost line; `test:layout`.

### 5.7 The owner's runs after 9c

The dev set against the instance (`-Deval.provider=ollama -Deval.baseUrl=http://finalysis-selfhost:11434`) for the
comparison row; the nomic search-floor sweep; and a categorization run on the sample data with timing (calls, items, ms
per 25-merchant batch, from `AiCallLog`'s lines).

---

## 6. Step 9d: the laptop, for the record

Ollama on the 2019 Intel MacBook (CPU only: Ollama's Metal acceleration is Apple Silicon's), with 9a's model at its
smallest usable size (the owner picks the tag; if it doesn't fit in memory, that is the result) and nomic-embed-text.
No tuning. Measured by the owner, written by me into the step notes and the comparison doc:

- categorization time per 25-merchant batch (sample data, `AiCallLog`'s `ms`);
- embedding time per 100 merchants (the index refresh's log line);
- chat time on five dev questions, one per kind: **d-l01** (lookup), **d-c01** (comparison), **d-t01** (trend),
  **d-a01** (alerts and recurring), **d-s01** (search), through `-Deval.questions`; whether each was correct and
  whether `max-turn-time` stopped it.

The only code in 9d is what the notes need (none expected; `-Deval.questions` is 9b's).

---

## 7. Step 9e: close-out

- `docs/eval/tiers.md` (the comparison doc): one table per tier and model (score, tool choice, invalid arguments,
  first-try grounding, latency), the categorization timings, and a **cost table**: per question on Cloud (the dev
  runs' cost / questions), per hour on Self-hosted (the rate, and the dev run's minutes), $0 on Local.
- README: a three-tier guide (what leaves the machine on each, how to switch, how to install Ollama for Local) and the
  **setup floor for Self-hosted stated plainly**: an AWS account, a GPU quota request (new accounts start at 0 vCPUs for
  G instances and approval can take days), "Receive Billing Alerts" turned on, a Tailscale account with this Mac in the
  tailnet, and **hourly billing while it runs** plus storage while it's stopped.
- Requirements (§9), roadmap, CLAUDE.md (current phase, layout: `infra/`, `scripts/selfhost/`, `com.finalysis.ai.ollama`,
  `com.finalysis.ai.selfhost`), Postman (`mode` → `tier`, a folder **15. Corgi tiers** for the settings endpoints with
  AI off and Local against nothing running), newman on every README folder, tag `phase-9-done` after the owner says so.

---

## 8. Testing summary

| Area | Tests | Step |
|---|---|---|
| Prices, IAM text | `ChatPricesTest` covers the four open models; the IAM additions as text in the notes | 9a |
| Providers | Ollama chat, categorization, embedding against the loopback stub; Bedrock tests unchanged | 9b |
| Tiers | switching, availability, waits for in-flight work, 423 while locked, status block | 9b |
| Payloads | the stub's received bodies checked like `ChatOutboundPayloadTest` and `OutboundPayloadTest` | 9b |
| Schema | V25 mixed dimensions, exact search per model, 5,000-vector timing; V26 cost by tier | 9b |
| Eval | `-Deval.provider` parsing, base URL host only, refused URLs, subset runs, header lines | 9b |
| UI | three cards, banners word for word, badges, cost lines, no "path" left; `test:layout` | 9b, 9c |
| Template | `cfn-lint`; `SelfHostTemplateTest` | 9c |
| Scripts | `--plan` runs with a fake `aws`; key only on stdin; watchdog on canned journals | 9c |
| EC2 control | `SelfHostControl` on a fake client; cost from `LaunchTime` | 9c |
| Architecture | Ollama and EC2 classes only in their packages; `NoCloudAiGuard` covers Ollama hosts | 9b, 9c |
| Postman | folder 15, newman on all README folders | 9e |

---

## 9. Requirements (proposed, close-out 9e)

- **AI-03** rewritten: "Corgi runs on one of three tiers, chosen in Settings and switched without restart: Cloud
  (Bedrock, available only with the `aws` profile), Self-hosted (Ollama on the user's own server through Tailscale) and
  Local (Ollama on this computer). Local is the default; when the selected tier's Ollama isn't reachable, Corgi is off
  and the rest of the app works." → Done.
- **AI-02, UI-06, QA-05**: "tier" in the model-size sense becomes "size class" (§16 decision 1).
- **AI-08** (new): the Self-hosted tier: the CloudFormation template and scripts, Tailscale-only access, the idle
  watchdog, the billing alarm, Start and Stop in Settings with running time and cost. → Done.
- **AI-07** (benchmark a US-origin alternative): Done by 9a (Gemma 3 12B and gpt-oss-20b).
- **SEC-01**: the three banner wordings, and "redaction applies on every tier". UI-05 notes the banner on every tier.
- **AI-01**: a note that each tier names its own model per role.
- Roadmap: Phase 9 as built; Phase 10 becomes the chat backlog plus what remains of local AI (AI-02, AI-04, AI-05's
  `num_ctx`, UI-06), §16 decision 7.

---

## 10. Known limitations (draft, finalized at 9e)

- **Quantization**: Self-hosted and Local run quantized builds; 9a scored full-precision ones on Bedrock.
- **Self-hosted cost is an estimate** from the on-demand rate and `LaunchTime`; storage while stopped, data transfer
  and the minimum billing increment aren't in it. The billing alarm is the backstop, and it lags by hours.
- **The watchdog stops the instance; nothing stops it from being started and forgotten outside the app** except the
  watchdog itself.
- **`DescribeInstances` is account-wide** for the app's identity (§5.4).
- **The first boot takes a while** (Ollama, Tailscale, model downloads); `create.sh` waits up to 30 minutes. The
  Deep Learning AMI follows AWS's update cadence; a stack created later may get a newer image than the one tested.
- **The laptop is slow**: 9d records it; no tuning.

---

## 11. Phase 10 candidates (recorded at 9e)

The chat backlog (h-u10, h-x06, the holdout guard per model); model size choices on Local and Self-hosted (AI-02,
UI-06); `num_ctx`, KV cache, keep-alive (AI-04, AI-05); field-level encryption (SEC-05); document Q&A (CHT-07);
recovery key rotation; lock before sleep; kept statement copies listed and downloadable; spot instances for Self-hosted.

---

## 12. The owner's manual setup steps

Steps no script or app does for the owner; `create.sh` prints the AWS ones and the README lists all of them.

- **Local:** install Ollama, `ollama pull` 9a's model (its smallest usable size) and `nomic-embed-text`.
- **Self-hosted:** an AWS account; a quota request for "Running On-Demand G and VT instances" (at least 4 vCPUs; new
  accounts start at 0 and approval can take days); **turn on Receive Billing Alerts** (Billing → Billing preferences;
  billing metrics live in `us-east-1`, so the alarm is there whatever the instance's region); confirm the SNS e-mail
  subscription; a Tailscale account with this Mac in the tailnet, a one-off pre-approved auth key, and an ACL limiting
  port 11434 to the owner's devices; the IAM additions `create.sh` prints, applied to `finalysis-dev`.
- **Cloud:** unchanged (Phase 4 §11.2, Phase 6 §2.4), plus 9a's IAM additions.
- **Tests:** `cfn-lint` installed for `SelfHostTemplateTest` (skipped and reported as skipped without it).

## 13. Terms

- **Tier:** where Corgi runs: Cloud, Self-hosted or Local. Used for nothing else, in the UI, docs, code and comments.
- **Size class:** a model's size on Self-hosted or Local (AI-02's "standard" and "deep", UI-06's notice, QA-05's
  hardware guide, the roadmap's Phase 10). Renamed in 9b-1's commit (requirements, roadmap) and 9b-2's (any UI text).
- **Path:** retired (§4.5).

---

## 14. Open questions (answered at approval, §16)

1. Rename the model-size uses of "tier"? → "size class".
2. Default on a fresh install? → Local, with Corgi shown off when Ollama isn't reachable.
3. 45/58 on the 58 non-IDENTITY questions? → Yes.
4. Two embedding sizes, exact scan? → Yes.
5. Daily cost cap on Self-hosted? → Off.
6. The Tailscale key through SSM? → Yes.
7. Roadmap: Phase 9 takes AI-03, AI-07, AI-08; Phase 10 the chat backlog and the rest of local AI? → Yes.
8. Ubuntu with driver install, or the Deep Learning AMI? → The Deep Learning Base GPU AMI (Ubuntu).
9. Start and Stop from the phone? → Yes.

---

## 15. Steps

| Step | Content | Code | Tests |
|---|---|---|---|
| 9a | **Starts only after the owner confirms tag `phase-8-done`.** Four open models in the price file and `aws` prices (owner fills prices); IAM additions as text; `maxTurnCost` per model; report header line; then the owner's four dev runs, run 7 in `docs/eval/README.md`, the pick | price entries, one report line | `ChatPricesTest`, report header test |
| 9b-1 | Backend: `AiTier` (Local the default, reachability), the three provider interfaces, Bedrock moved behind them, Ollama providers; the tier setting and switch without restart, waiting for running work; the status block (`tier`, `available`, `reachable`, `server`); V25 embeddings (both sizes, exact scan), V26 cost by tier, caps on Cloud only; cost lines computed per tier; eval `-Deval.provider/baseUrl/questions`; `NoCloudAiGuard` for Ollama; ArchUnit; "size class" in requirements and roadmap; Postman `mode` → `tier` | `com.finalysis.ai`, `.ai.ollama`, settings endpoint, migrations, eval | §4.4's backend tests |
| 9b-2 | UI: Settings → Corgi cards; badge and banner per tier with SEC-01's wordings; "Corgi is off: install Ollama or choose a tier in Settings"; cost lines per tier; "path" → "tier" with the guard test | frontend | §4.4's frontend tests, `test:layout` |
| 9c | `infra/selfhost/` template (Deep Learning Base GPU AMI); `scripts/selfhost/create.sh`, `destroy.sh` with the printed manual steps; watchdog; billing alarm; `com.finalysis.ai.selfhost` (EC2 start, stop, status, cost); Settings → Self-hosted controls (phone included); IAM text; then the owner's create, dev run, sweep and categorization timing | template, scripts, EC2 control, UI | §5.6 |
| 9d | The owner's laptop measurements (d-l01, d-c01, d-t01, d-a01, d-s01); notes and comparison rows | none expected | none expected |
| 9e | `docs/eval/tiers.md`, README guide and §12's setup steps, requirements, roadmap, CLAUDE.md, Postman 15, newman, tag `phase-9-done` when the owner says | docs | full runs |

Each step: `caffeinate -i ./mvnw clean verify` and `npm test` (and `test:layout` where the UI changes), Maven's summary
counts, commit, push `v1`, stop for review.

### Step 9a notes: small open models on Bedrock (code)

Done in code: "Phase 9 step 9a: four open models priced for the dev runs, maxTurnCost sized per model". Backend
3,456 tests, 0 failures, 0 errors, 0 skipped (Maven summary; 3,447 at `phase-8-done` plus 6 in `ChatEvalTurnCostTest`
and 3 in `ChatEvalGuardsTest`); frontend 312 tests (30 files), all passed; no UI change, so no layout run. No live
call. The four dev runs are the owner's, and run 7 in `docs/eval/README.md` is written from their results.

**The models' ids**, from AWS's Bedrock model cards (Models at a glance, read 2026-10-28). All four are served
on-demand **in-region** in us-east-1, with **no US inference profile** (no `us.` id), Converse supported:

| Model | Bedrock id | Note from the card |
|---|---|---|
| Ministral 3 8B | `mistral.ministral-3-8b-instruct` | Active |
| Ministral 3 14B | `mistral.ministral-3-14b-instruct` | the card's title is "Ministral 14B 3.0"; Active |
| Gemma 3 12B | `google.gemma-3-12b-it` | **Legacy**, end of life 2027-03-30 |
| gpt-oss-20b | `openai.gpt-oss-20b-1:0` | Active; **reasoning on by default** (see below) |

**Price file** (`backend/src/test/resources/eval/model-prices.json`): the four ids with `inputPerMillionUsd` and
`outputPerMillionUsd` **null** until the owner enters them (on-demand, us-east-1, from the Bedrock pricing page), and
no cache prices. A run, or the smoke test, refuses a model whose prices are null with "its prices aren't entered yet";
an entry with only one of the two prices, or cache prices without them, fails to load. The `aws` profile's prices are
**not** changed (unlike §2.2's draft): the app's main model stays Sonnet 4.6, and the eval gives the app the file's
price for the model under evaluation, as it always has. `ChatSettingsTest` skips entries without prices when it
compares the two files.

**IAM**: add one statement to the `finalysis-dev` policy, nothing else (no `bedrock:*`, no streaming):

```json
{
  "Sid": "Phase9aOpenModels",
  "Effect": "Allow",
  "Action": "bedrock:InvokeModel",
  "Resource": [
    "arn:aws:bedrock:us-east-1::foundation-model/mistral.ministral-3-8b-instruct",
    "arn:aws:bedrock:us-east-1::foundation-model/mistral.ministral-3-14b-instruct",
    "arn:aws:bedrock:us-east-1::foundation-model/google.gemma-3-12b-it",
    "arn:aws:bedrock:us-east-1::foundation-model/openai.gpt-oss-20b-1:0"
  ]
}
```

Converse uses `bedrock:InvokeModel`. In-region models need no inference-profile ARN and no other region. If a first
call fails with an access or Marketplace subscription error, open the model once in the console's Model catalog (as in
Phase 4) rather than widening the policy, and record it here; a model the account can't get is skipped.

**Turn cost limit per model: `-Deval.maxTurnCost=sized`** (`ChatEvalTurnCost`). It takes the app's worst-case round
(12,000 input tokens at the input price, or the cache-write price for a caching model, plus the 1,500-token output
cap), multiplies it by Sonnet 4.6's ratio (0.15 / 0.0675) and rounds up to the cent. Every model gets the same number
of worst-case rounds per turn as Sonnet has at 0.15. For an uncached model:

  limit = ceil_cent((12,000 × input + 1,500 × output) / 1,000,000 × 0.15 / 0.0675)

Checks: Sonnet 4.6 sizes to 0.15 and Haiku 4.5 to 0.05, the app's two documented settings (`ChatEvalTurnCostTest`,
which also pins the constants to `application.yml`). The run prints "Turn cost limit sized from the price of <id>: $…",
and the report's header records the value as set by `-Deval.maxTurnCost`. The four values can't be computed until the
prices are in, so `sized` computes them at run time from the file. Very cheap models round up to $0.01 at least.

**Report**: no header change. The existing line "Prompt caching: off (the price file lists no cache prices for this
model)" already marks an uncached run, so §2.2's extra "Model family" line would only repeat it.

**The owner's runs**, from `backend/`, per model, after the prices are entered and the IAM statement is added:

```sh
./mvnw test -Pai-smoke -Dtest=LiveChatSmokeTest -Deval.model=<id>
caffeinate -i ./mvnw test -Pchat-eval -Deval.set=dev -Deval.model=<id> -Deval.maxTurnCost=sized
```

The smoke test first. If it fails on tool use, the model is skipped and the reason recorded. The dev run's run budget
stays `-Deval.max-cost` 5.00 by default.

**Caveat for gpt-oss-20b:** its card says reasoning is on by default. Reasoning tokens count as output, so they count
against the 1,500-token output cap per call and against the cost. The smoke test checks that reasoning content goes
back to the model unchanged. If answers come back cut and ungrounded, that is recorded as the model's result; nothing
is tuned for it (the brief: same harness, same limits).

### Step 9a-2 notes: Unicode spaces and dashes in the figure extractor (code)

A fix found in the owner's runs, before 9b-1 (whose plan is unchanged). Done in code: "Phase 9 step 9a-2: the figure
extractor reads Unicode spaces and dashes as ASCII". Backend 3,502 tests, 0 failures, 0 errors, 0 skipped (Maven summary; 3,456 at step 9a plus 46 in the four grounding test classes and `SummaryFigureStripperTest`); frontend 312 tests (30 files), all passed; no UI change, so no layout run. One earlier frontend run, made while the backend suite ran at the same time, timed out once in `VaultGate.test.tsx` ("setup: two sentences, the password twice, then the key once; the app opens only after the last group"), as in Phase 8 step 7; run alone, it passed. No live call.

**Evidence.** In the uncommitted reports `chat-dev-ac0f1e0bc0ca-20261029T124740Z` (gpt-oss-120b) and
`-20261028T162423Z` (gpt-oss-20b), every retried answer has a Unicode space or dash: 39 of 39 on 120b, 20 of 20 on
20b. 30 of 120b's 39 retries and 13 of 20b's 20 list a bare "2026", from answers like "You spent $302.17 on utilities in
May 2026" (d-l02), written with U+202F narrow no-break spaces. Read at the character level, the models write:

- **U+202F** (narrow no-break space) between words, before `%` and between digit groups: 39 of 39 rejected answers on
  120b, 18 of 20 on 20b;
- **U+2011** (non-breaking hyphen) in ISO dates (`2026‑06‑13`), months (`2026‑10`), aliases (`CARD‑1`) and as a sign
  (`(‑46 %`): 11 on 120b, 10 on 20b;
- less often U+00A0, U+2013 and U+200B (a zero-width space inside a marker, `[c1]` with U+200B after the `[`).

**Cause.** Java's `\s` and the extractor's `-` match only ASCII. So `May 2026` with U+202F didn't mask the year,
`53.5 %` lost its `%` and was read as a bare 53.5 (d-l05, d-c05), `2026‑06‑13` was read as 2026, 06 and 13, and
`$3 638.53` was read as `$3` and `638.53` (20b, d-m02 turn 2). The hypothesis held; nothing else was found.

**Fix** (`FigureExtractor.asciiForms`). Before any rule runs, the extractor reads a copy of the answer made ASCII one
character for one, so a figure's place and its text as written still refer to the answer itself:

- a no-break, figure, thin or narrow no-break space between digit groups (`3 638.53`, 1–3 digits then groups of
  exactly 3) becomes a comma;
- any other Unicode space (`Character.isSpaceChar`), and U+200B, U+2060 and U+FEFF, becomes a space; U+2028 and U+2029
  become a line break;
- U+2010, U+2011, U+2012, U+2013, U+FE63 and U+FF0D become `-`. The minus sign U+2212 was already a sign and stays as
  it is; the em dash is left alone (it is punctuation).

ASCII text is not changed, so every existing case reads exactly as before. In particular, an ASCII space is never a
thousands mark: `$3 638.53` written with an ASCII space is still `$3` and `638.53`. `SummaryFigureStripper` reads
through the same extractor and needed no change. Labels are read the same way, so a label written with a no-break space
masks where the answer has an ASCII one. The `%` rule already allowed one space before `%`, so the Unicode space
before `%` needed only the normalization.

**Offline replay** (no model call). All 143 retried answers of the six ac0f1e0 dev runs were run through the fixed
extractor: of the 43 gpt-oss retries that listed "2026", 42 no longer extract it. The one left is 20b's d-x08 ("your
spending for 2026"), a bare year in ASCII too (§15). 53.5, 49.8, 30.8 and 23.5 are read as percents, `$3 638.53` as
one amount, and the dates and aliases are masked. The other models' answers have only en dashes between months or in
ranges (`March–October 2026`, `2–3 times`, `$79–$80`), where a sign changes no magnitude, and one thin space (Ministral
3 8B's d-l05, grounded either way).

**Tests.** `FigureExtractorTest`: each Unicode form masked like its ASCII form (month-year, ISO date and month, day and
month, quarter, alias, marker), each Unicode figure as one figure (percent with U+202F, U+00A0 and U+2009, the word
percent, a sign written as U+2011, thin-space and no-break-space thousands), an ASCII space never a thousands mark, a
year beside a day never a group, U+2011 and U+2212 as signs, labels with a no-break space. `AnswerGroundingTest`: the
worked example in its gpt-oss form grounds; the canary 999.99 fails in eight Unicode forms (U+202F, U+00A0, a
thin-space `$1 000`, a percent, the word percent, a U+2011 sign, after a Unicode month-year, inside the hostile merchant
name). `SummaryFigureStripperTest`: Unicode forms are stripped like their ASCII forms. `GroundingPropertiesTest`
(jqwik): an answer built from grounded, ungrounded and non-figure pieces grounds exactly like its ASCII twin when its
spaces are replaced by Unicode spaces, and when its hyphens are replaced by Unicode dashes (same status, same figures:
kind, form, magnitude, place and number of matches). Every earlier case of the four classes is unchanged and green.

**The check.** The eval's bare-year retry counters stay. On the owner's reruns of gpt-oss-20b and gpt-oss-120b they
should drop to near zero; the two earlier reports stay uncommitted as the before.

**Not in this step**, found on the way, for the owner to decide: gpt-oss writes some citation markers in lenticular
brackets (`【c1】`, 10 rejected answers on 120b, 3 on 20b), and one with a zero-width space inside (`[c1]` with U+200B after the `[`).
`CitationMarkers` doesn't parse either form, so those citations are not linked; the extractor masks the bracketed form
but reads the 2 of `【c2】` as a count. A fix belongs in `CitationMarkers`, not the extractor.

---

## 16. Decisions at approval (2026-10-28)

On the design points raised with the draft:

- **Embedding size:** both sizes allowed in dimensionless columns, exact scan, HNSW dropped, the 5,000-vector test
  (§4.1).
- **Tier switching:** a vault setting; a switch waits for running work (§3.1).
- **The Tailscale key:** a short-lived encrypted SSM parameter, read once at boot, deleted by `create.sh` (§5.2).
- **IAM:** `ec2:DescribeInstances` on all resources; Start and Stop limited by tag (§5.4).
- **Watchdog:** shutdown-to-stop; the instance role stays SSM-only (plus the one key parameter) (§5.1).
- **Billing alarm:** "turn on Receive Billing Alerts; metrics live in us-east-1" is a manual setup step, in
  `create.sh`'s output and the README (§5.1, §5.3, §12).
- **45/58** is read against the 58 non-IDENTITY questions (§2.3).

On §14's questions:

1. The model-size uses of "tier" (AI-02, UI-06, QA-05, roadmap Phase 10) become **"size class"**. Tier means only
   where Corgi runs (§13).
2. **A fresh install defaults to Local**, per "local AI is the default". If Ollama isn't reachable, Corgi shows as off
   with "install Ollama or choose a tier in Settings"; the app otherwise works (import, analytics, rules) (§3.1).
3. Yes (as above).
4. Yes (as above).
5. **The daily cost cap is off on Self-hosted**; the hourly rate, minutes running and the billing alarm are its
   controls. Caps stay on Cloud only (§4.2).
6. Yes (as above).
7. Yes: Phase 9 takes AI-03, AI-07 and AI-08; Phase 10 becomes the chat backlog plus the rest of local AI.
8. **The AWS Deep Learning Base GPU AMI (Ubuntu, drivers preinstalled)**: fewer first-boot failure modes; the template
   pins it by its public SSM parameter so it resolves per region (§5.1).
9. **Start and Stop work from the phone**; they need an unlocked vault and a logged-in session like everything else
   (§5.5).

Also: **9b splits into 9b-1 (backend) and 9b-2 (UI)**, each its own commit and review. 9d's five questions are
approved. **9a doesn't start until the owner confirms the `phase-8-done` tag.**
