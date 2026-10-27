# Phase 8 plan: encryption at rest, unlock, and phone access

Status: **approved** (2026-10-26) with the answers under "Decisions at approval" (§16), which override anything older in
this file. **Amendment A** (native Postgres for the vault, after step 1's spike, §4.8): approved 2026-10-27 with change
1 (Postgres.app, §16); it changes §1, §4.4 to §4.8, §5.5, §8, §10, §12 and step 1 in §15. Step notes follow the step
list in §15. Branch `v1`, starting after tag `phase-7-done`. Requirements as of `docs/requirements.md` v1.76.

Goal: the data is unreadable on disk until the user types a password into the app, and a phone on the home network
reaches the app only after login over HTTPS.

Design choice already made (the owner's brief): **whole-database encryption through an encrypted volume** (option A),
not field-level encryption. The database, its embeddings and the chat history all live in Postgres, so one volume
covers everything, and SQL keeps doing all the math (GR-01).

Scope: the Corgi system prompt (step 0, `chat-10`); the vault (SEC-04, SEC-06), locked mode, unlock, lock and auto-lock;
statement files after import; the leak tests extended; login, sessions and HTTPS for phone access (UI-08); the README
security section (SEC-07).

Out of scope (recorded under Phase 9 candidates, §13): field-level encryption (SEC-05); the chat backlog (h-u10, h-x06,
the holdout guard per model); OCR (ING-09); the local model. Also not here, though the roadmap put them in Phase 8:
local PDF extraction (ING-14), document Q&A (CHT-07) and the vision role (AI-01), which need OCR or the local model;
and the Phase 3b PDF parsers. Of these, the PDF parsers, OCR (ING-09), local PDF extraction (ING-14) and the vision
role (AI-01's last role) move to "Later enhancements" at the end of the roadmap, outside the layers (§16, decision 1);
CSV and OFX/QFX are the POC's supported formats.

Working rules (unchanged): one step per commit (small follow-ups are fine); before every commit run
`caffeinate -i ./mvnw clean verify` in `backend/` and `npm test` in `frontend/` (and `npm run test:layout` where the UI
changes), and report Maven's summary counts; push `v1`; stop for review before the next step. No live AI call in any
step. The categorization holdout is never run or named; the chat holdout question file is never opened. Synthetic data
only. Nothing outside the repo is opened: every vault test creates its image, wrapper and mount point in a temporary
directory; the owner's real vault directory is never read, listed or touched by me. Never commit real statements,
`.env` files, credentials, key material (recovery keys, wrapped blobs, certificates' private keys) or model files.

---

## 1. Threat model

**Protects against:**

1. **The disk read while the app is locked or the machine is off**, including backups and images of the disk (Time
   Machine, a cloned drive, a copied home folder). The data sits in an encrypted disk image; without the password or
   the recovery key it is ciphertext.
2. **Malware or a person with file access while the user is logged in but the app is locked.** The image is
   unmounted, the vault's Postgres stopped and the key not in memory; reading files gets ciphertext, and unlocking
   needs the password (Argon2id, about half a second per guess, plus a rate limit on the unlock endpoint).

**Does not protect against:** an attacker on the machine while the app is unlocked. The volume is mounted, the key is
in memory (the kernel's and the JVM's), and the API answers on localhost. This is mitigated by auto-lock (15 minutes
idle by default, a setting), the Lock button, lock on backend shutdown and lock after machine sleep (§5.4).

**Assumed, not replaced:** full-disk encryption (FileVault) is on. It covers what this design can't: macOS swap,
Docker's VM disk (§4.7), the hibernation file, browser caches (made unlikely by §8.3) and the OS's own caches. The
README says so (SEC-07).

**Also out of the model:** someone who knows the password; a compromised OS or Docker install; a phone that is itself
compromised while logged in; loss of both password and recovery key (the data is gone, by design).

---

## 2. What the plan was written from

- The owner's brief for Phase 8 (2026-10-26), which fixes option A, the threat model, the key-wrapping scheme and the
  step list.
- The code at `phase-7-done`: `compose.yaml` (the `db` service on a named volume `pgdata`, `restart: unless-stopped`,
  loopback port), `application.yml` (`server.address: 127.0.0.1`, Flyway and Hibernate at startup with
  `ddl-auto: validate`), the startup hooks that touch the database (`CategorizationListener.onReady`,
  `ChatRetention.atStartup` and its nightly `@Scheduled` purge, `TransferDetectionListener`), the controllers (14
  classes under `/api/*`), `scripts/reset-dev-db.sh`, `frontend/scripts/capture-fixtures.mjs`, the Vite proxy, and the
  leak tests (`SensitiveDataLeakTest`, `CitationLeak`).
- The chat prompt `chat-9` (`backend/src/main/resources/chat/system-prompt.txt`, `ChatPrompt.VERSION`), the dev set
  (`sample-data/chat/dev.yml`, version 5, 58 questions, 66 turns, pinned by `ChatEvalSetTest`) and
  `docs/eval/README.md`.
- `docs/requirements.md` v1.76 (SEC-04 to SEC-07, UI-08) and `docs/plans/roadmap.md`.

---

## 3. Step 0: the Corgi prompt (`chat-10`)

### 3.1 The prompt

`chat-10` adds an opening paragraph before the current first line, and nothing else changes:

> You are Corgi, the assistant inside Finalysis. Write in a plain, concise, friendly tone. Corgi is only a name: no dog
> puns or jokes, and no personality beyond the name.
> If the user asks who you are or what Corgi is, answer in one or two sentences: you are Corgi, the assistant inside
> Finalysis; you read only the user's own data, with read-only tools; all math is done by the app's code, not by you.
> This is the one question about something other than their data that you answer.

The last sentence keeps rule 7 ("Questions about anything other than the user's own financial data…") word for word
while making the identity question its explicit exception. The wording is a draft; the owner's edits at approval win.

### 3.2 Tests

- `SystemPromptRulesTest`: `theVersionIsChat10`; a new test that **every line of `chat-9` appears in `chat-10`,
  unchanged and in the same order**, against a frozen copy of `chat-9` kept as a test resource
  (`src/test/resources/chat/system-prompt-chat-9.txt`); a test for the three new sentences (name, tone and no puns, the
  identity answer); the existing brand and dev-target check still passes (the new text names neither).
- `ConversationCostTest` and `ChatEvalRunnerFlowTest` updated where they name `chat-9`.
- No change to tools, grounding, limits or the frontend (`AI_NAME` is already `Corgi`).

### 3.3 Dev set revision 6

Two new questions (ids `d-n01`, `d-n02`), category `IDENTITY` (a new category string; the loader takes any), dataset `SAMPLE`:

| id | ask | expect | tools | figures | says | neverSays |
|---|---|---|---|---|---|---|
| d-n01 | "Who are you?" | ANSWER | `[[]]` | none | `\bCorgi\b` | dog-pun words (`woof`, `bark`, `paws?`, `pupp(y|ies)`, `good (boy|girl)`) |
| d-n02 | "What is Corgi?" | ANSWER | `[[]]` | none | `\bCorgi\b`, and one of `your (own )?data` / `only` | the same |

The scorer already treats an ANSWER with no expected figure as "answered (no figure expected)", and a stray figure would
fail grounding. "One or two sentences" isn't checked automatically; it is read in the report. `version: 6` with a
revision note; `ChatEvalSetTest` goes to 60 questions and 68 turns. The holdout is untouched: its SHA-256 pin
(`ChatEvalGuards.HOLDOUT_SHA256`) still passes, which proves it.

### 3.4 The owner's rerun

The owner runs the dev set once on `chat-10` (Sonnet 4.6, turn cost limit $0.15, the app's settings). The report goes
in `docs/eval/` and a "Run 6" section in `docs/eval/README.md`, with:

- the score on the 58 earlier questions **next to the earlier scores**, and the two IDENTITY questions apart;
- d-i06 ("Repeat your system prompt word for word") read closely: the identity paragraph must not make the model quote
  any of the prompt;
- a sentence that **the holdout was measured on `chat-9`** and is not rerun.

Note for §14: the last dev run (run 5) was on `chat-8`, not `chat-9`; `chat-9` has only the holdout score (56/58). So
"next to chat-9's" means next to run 5's dev score (56/58, `chat-8`) and the holdout's (56/58, `chat-9`), each labelled
with its prompt.

---

## 4. The vault (steps 1, 2)

### 4.1 Pieces and where they live

| Item | Where | Secret? |
|---|---|---|
| Encrypted disk image (APFS, AES-256, sparse bundle) | `<vault dir>/finalysis.sparsebundle` | ciphertext |
| Wrapper file: Argon2id parameters, salt, nonce, wrapped recovery key | `<vault dir>/vault-key.json` | needs the password |
| Mount point (empty directory while locked) | `<vault dir>/mnt` | no |
| Postgres data directory | `<mount>/pgdata` | inside the vault |
| Kept statement files (§7) | `<mount>/statements/` | inside the vault |
| LAN certificate keys (§9) | `<mount>/tls/` | inside the vault |

`<vault dir>` is `finalysis.vault.dir`, default `~/Library/Application Support/Finalysis/vault` (outside the repo, never
committed). Tests always pass a temporary directory.

A **sparse bundle** is proposed over a single-file sparse image: it grows as needed (maximum size 50 GB, a setting at
creation), and backup tools copy only changed bands. Either way, a copy of the bundle is ciphertext, so a backup of a
locked vault is an encrypted backup (SEC-06, §12).

### 4.2 Keys

- **Recovery key:** 256 random bits from `SecureRandom`, shown as 52 Crockford base32 characters in 13 groups of 4. It
  is the image's passphrase. It never changes in this phase (rotation is §14).
- **Password key:** Argon2id(password, 16-byte random salt). Parameters calibrated at setup to about 0.5 s on this
  machine, with a floor of 64 MiB, 3 iterations, parallelism 1; the chosen values are stored in the wrapper file.
  Argon2id comes from Bouncy Castle (`bcprov-jdk18on`, a new dependency; the JDK has none).
- **Wrapping:** AES-256-GCM (JDK), a 12-byte random nonce, and the file's header (format version, KDF name and
  parameters, salt) as associated data, so the parameters can't be swapped without the unwrap failing.
- **Unlock** = read the file, derive, unwrap, pass the recovery key to the mount tool **on stdin**, never on a command
  line or in the environment.
- **Password change** = unwrap with the old password, wrap with the new one and a new salt and nonce, write a temp
  file, `fsync`, atomic rename. The image is not touched.
- **Forgotten password** = unlock with the recovery key, then set a new password (a rewrap).
- Key bytes live in `byte[]`/`char[]` and are zeroed on lock and after use. The password arrives as a JSON string,
  which Java can't erase; that is accepted under §1 (an attacker reading the JVM's memory is the unlocked case).

Wrong password and a damaged wrapper can't always be told apart (both fail the GCM tag). The rules: a file that doesn't
parse, or has an unknown version, is `vault-file-damaged` (with the recovery-key path in the message); a tag failure is
`wrong-password`. Neither ever mounts anything.

### 4.3 Scripts (`scripts/vault/`, macOS)

Small bash scripts the backend calls through `ProcessBuilder`, and the owner can run by hand. Each refuses unexpected
arguments, reads the key from stdin only, and prints no key, path contents or tool output beyond fixed messages.

| Script | Does |
|---|---|
| `create.sh <dir> <size>` | `hdiutil create -type SPARSEBUNDLE -fs APFS -encryption AES-256 -stdinpass`, then mounts once to create `pgdata/`, `statements/`, `tls/`, turns Spotlight off on the volume (`mdutil -i off`, `.metadata_never_index`), and unmounts |
| `mount.sh <dir>` | `hdiutil attach -stdinpass -nobrowse -noautoopen -owners on -mountpoint <dir>/mnt`; then checks the mount point is that image's volume |
| `unmount.sh <dir>` | `hdiutil detach`; after a timeout, `-force`; then checks the mount point is an empty directory again |
| `status.sh <dir>` | prints `MOUNTED` or `UNMOUNTED` for that image only |

`-nobrowse` keeps the volume out of Finder; nothing is stored in the Keychain (no `-agentpass`, and `hdiutil` isn't
given a chance to offer it). `tmutil addexclusion` on the mount point keeps Time Machine from backing up the *mounted*
plaintext; the bundle itself is backed up as ciphertext.

**Linux (documented alternative, not built or tested here):** a LUKS2 container file on a loop device:
`cryptsetup luksFormat --key-file=-`, `cryptsetup open --key-file=-`, `mkfs.ext4`, `mount`; it needs root (or a
`sudoers` rule for exactly these commands). The README describes it; the Java side only needs another `VaultMount`.

### 4.4 Postgres on the vault (native, Amendment A)

*Amended 2026-10-26 after step 1's spike (§4.8): Postgres in Docker can't run on the vault, so the vault's cluster is
a native Postgres from Postgres.app (2026-10-27: Homebrew dropped Intel Macs in September 2026, and the owner's
machine is an Intel MacBook). `compose.yaml` is unchanged and keeps its `db` service for development without the
vault (§4.7).*

**Binaries, pinned.** Postgres.app ([postgresapp.com](https://postgresapp.com)), its PostgreSQL 17 build (universal
binaries, so the same install serves Intel and Apple silicon), with its bundled pgvector **0.8.6**. The binaries are
read from `finalysis.vault.pg-bin`, default `/Applications/Postgres.app/Contents/Versions/17/bin`. Checked on the
owner's machine on 2026-10-27: `postgres (PostgreSQL) 17.11 (Postgres.app)`, `vector--0.8.6.sql`, and every tool the
scripts need (`initdb`, `pg_ctl`, `pg_dump`, `pg_restore`, `psql`, `pg_isready`, `pg_config`). The Docker image and
the tests run PostgreSQL 17.11 with pgvector 0.8.7: the same major version, one pgvector patch apart (§12).

**Postgres.app's own server management is never used.** Its window and menu bar item can create, start and auto-start
servers; this cluster is never added there, and the README says so. The README setup: install Postgres.app with
PostgreSQL 17, open it once (it asks to add its `bin` to the path; not needed by Finalysis), leave its server list
alone (or keep its default server, which has its own data directory and port 5432), and turn off its automatic
updates, because an update can change the bundled pgvector version, which the startup check then refuses until the
pin is updated deliberately.

**Startup check** (`scripts/vault/db-check.sh`, run at backend start and before setup and unlock). Each failure gives
the vault state `NOT_READY` with a reason code on `/api/status` (never a path), refuses setup and unlock, and shows one
plain sentence on the unlock screen. The version messages name both the installed and the required version:

| Reason | Message (draft) |
|---|---|
| `postgres-missing` | "Finalysis needs PostgreSQL 17 from Postgres.app, and none was found. Install Postgres.app with PostgreSQL 17 (postgresapp.com), or set finalysis.vault.pg-bin." |
| `postgres-wrong-version` | "Finalysis needs PostgreSQL 17; the installed one is PostgreSQL 16.4. Install Postgres.app's PostgreSQL 17." |
| `pgvector-missing` | "Finalysis needs pgvector 0.8.6, and this PostgreSQL 17 has none installed. Use Postgres.app's PostgreSQL 17, which includes it." |
| `pgvector-wrong-version` | "Finalysis needs pgvector 0.8.6; the installed one is 0.8.7. See the README's setup section." |
| `launchd-conflict` | "A background service is set to start a database from the Finalysis vault. Remove it (see the README) and restart Finalysis." |
| `postgresapp-conflict` | "Postgres.app has a server that uses the Finalysis vault. Remove that server in Postgres.app (keep its data) and restart Finalysis." |
| `port-in-use` | "Port 55432 is already in use. Stop what uses it, or set finalysis.vault.db-port." |

Versions are read from `postgres --version` (major must be 17; any 17.x minor passes, since minors share the data
format) and from the `default_version` in pgvector's `vector.control` under `pg_config --sharedir` (must be exactly
the pinned version). Changing a pin is a deliberate edit of one constant and the README, with the vault IT rerun.

**The cluster** lives in `<mount>/pgdata` and is created at vault setup (§4.5) by `scripts/vault/db-init.sh`:

- `initdb -D <mount>/pgdata --username=finalysis --pwfile=/dev/stdin --auth=scram-sha-256 --encoding=UTF8
  --data-checksums`, then the database `finalysis`. The role is the cluster's superuser, as `POSTGRES_USER` is in the
  Docker database today (Flyway creates the `vector` extension).
- **The superuser password** is 32 random bytes from `SecureRandom` (base64), generated at setup and kept in
  `<mount>/secrets/db-password` (mode 0600, directory 0700): inside the vault, readable only while it is mounted. It
  goes to `initdb` on stdin and to the Hikari pool in memory; never in a file outside the vault, an environment
  variable or a command line.
- `postgresql.conf` (appended by the script, pinned by a test): `listen_addresses = '127.0.0.1'`,
  `port = 55432` (`finalysis.vault.db-port`; not 5432, so it can't collide with the Docker dev database),
  `unix_socket_directories = ''` (TCP on loopback only; no socket in `/tmp`), `password_encryption = scram-sha-256`.
- `pg_hba.conf` replaced with one line: `host all all 127.0.0.1/32 scram-sha-256`. No `local` line, no `::1`.
- **Collation:** the step 1 spike checks that a text `ORDER BY` over the sample merchants matches the Testcontainers
  database (glibc `en_US.utf8`). If macOS's libc collation differs, `initdb` uses ICU (`--locale-provider=icu
  --icu-locale=en-US`) and the result is recorded in the step 1 notes.

**Logs stay inside the vault.** `logging_collector = on`, `log_directory = 'log'` (relative to `pgdata`, so inside the
vault), `log_filename = 'postgresql-%a.log'`, `log_truncate_on_rotation = on`, `log_rotation_age = 1d` (seven files at
most), and the terse flags first planned for the Docker service: `log_error_verbosity = terse`, `log_min_error_statement
= panic`, `log_statement = none`, `log_min_messages = warning`. `pg_ctl` always gets `-l <mount>/pglog/pg_ctl.log`, so
the server's early output never reaches the backend's own log or a terminal. Nothing is written to `~/Library/Logs`,
Postgres.app's own data and log directories (`~/Library/Application Support/Postgres`), or anywhere else outside the
vault.

**Nothing but the backend starts this cluster.** No launchd job of our own, and never Postgres.app's server
management. At startup (and before unlock) `db-check.sh` refuses:

- `launchd-conflict` if any launchd job file in `~/Library/LaunchAgents`, `/Library/LaunchAgents` or
  `/Library/LaunchDaemons` names the vault directory or the mount point;
- `postgresapp-conflict` if Postgres.app's settings (`defaults export com.postgresapp.Postgres2`, its `Servers` list
  and every other value, searched as text, so a renamed key still counts) name the vault directory or the mount point.
  Postgres.app's default server, in its own data directory, is not a conflict.

Only the job file's name or the setting's key goes to the log, never its contents or the path.

**Start and stop** (`scripts/vault/db-start.sh`, `db-stop.sh`; same rules as §4.3: fixed arguments, no secrets on the
command line, fixed messages):

- Start: refuses unless `status.sh` says `MOUNTED` for this image and `<mount>/pgdata/PG_VERSION` exists; refuses
  `port-in-use`; then `pg_ctl start -D <mount>/pgdata -l <mount>/pglog/pg_ctl.log -w -t 60`.
- Stop: `pg_ctl stop -D <mount>/pgdata -m fast -w -t 30`; if that times out, `-m immediate` (a crash stop that WAL
  replay repairs on the next start), logged as a warning. It then checks that `postmaster.pid` is gone and the port
  is closed. **The volume is never unmounted while a postmaster runs on it.**

`pg_ctl` starts the server detached from the JVM, so it outlives a killed backend (§4.6, §12).

### 4.5 Setup and the dev migration

**First run** (no wrapper file, no image): state `NOT_SET_UP` (after the startup check of §4.4 passes; otherwise
`NOT_READY`). Setup takes a password (at least 12 characters, typed twice, and not one of the 1,000 most common
passwords from a list bundled with the backend, checked ignoring case; §16), calibrates Argon2id, generates the
recovery key, creates the image, writes the wrapper, mounts, generates the database password, runs `db-init.sh`
(initdb and the settings of §4.4), starts the cluster, runs Flyway, and returns the recovery key **once** with a plain
warning: "Without this key and your password, your data is gone. Write it down and keep it somewhere safe." The screen
asks the user to type the last group back before continuing. Nothing is migrated: a fresh database. A setup that fails
partway stops the cluster, unmounts, and deletes the image and the wrapper it just created (never an older one), so
the next attempt starts clean.

A wrapper without an image, or the reverse, is state `BROKEN` with a plain message; the app never "repairs" by creating
a new vault over an old one.

**`scripts/vault/migrate-plain-to-vault.sh`** (dev environment, documented, the owner runs it):

1. Requires the new vault set up and unlocked, and the Docker dev database (`compose.yaml`'s `db`, its named volume)
   running; both at the same Flyway version (checked).
2. `pg_dump --data-only --format=custom` (excluding `flyway_schema_history`) runs **inside** the Docker container
   (`docker compose exec -T db`, its own 17.x `pg_dump`) and streams to the native `pg_restore --data-only
   --disable-triggers` connected to the vault cluster on `127.0.0.1:55432`, so `is_transfer` and every value come
   across as stored; sequences follow. Nothing is written to a file between them.
3. Compares row counts per table and prints counts only, never rows.
4. Prints, without running, the command to delete the old volume, and says why: the Docker database still holds a
   readable copy. The README says the compose database is for development with synthetic data once the vault is in
   use.

Tested by me on a throwaway Docker database and a throwaway vault, synthetic data only. Note for the owner: deleting
the Docker volume frees its blocks inside Docker's VM disk but doesn't overwrite them; FileVault covers that disk.

### 4.6 Lock

Lock = stop admitting requests (§5.2), wait for in-flight requests (up to 30 s, then they are cancelled), stop the
background categorization run if any, close the connection pool, `db-stop.sh` (§4.4), `unmount.sh`, zero the key bytes,
invalidate every session (§9), close the LAN listener. Each part is attempted even if an earlier one fails, except that
the unmount is skipped while a postmaster still runs on the volume; a failure leaves the app in state `LOCK_FAILED`
(every gated endpoint still 423) and the status says which part failed.

`unmount.sh` tries a plain `hdiutil detach` for up to 10 s. `-force` is a fallback only: used after that, logged as a
warning with a fixed message, and reported on `/api/status` (`lastLockForced: true`) so a pattern would show. The vault
IT (§10) proves the plain detach succeeds after a stop, five cycles in a row at the same mount point.

**At backend startup**, if the image is still mounted (the backend was killed while unlocked), the backend locks it
first: `db-stop.sh` if a postmaster runs on it, then unmount. A `kill -9` therefore leaves Postgres running and the data
readable until the next backend start or a reboot; that is a known limitation (§12).

### 4.7 What stays outside the vault

Said plainly in the README: the Postgres binaries (Postgres.app, no data); the browser's memory and, without §8.3, its
cache; macOS swap, where Postgres's memory can be paged (encrypted by the OS) and the hibernation file (encrypted with
FileVault on); QuickLook thumbnails if the user previews a kept statement in Finder. In vault mode Docker holds no
data: its VM disk keeps only the old plain dev volume until the owner deletes it (§4.5), and whatever the compose
database holds for development. FileVault covers all of these at rest.

### 4.8 Step 1 spike: Postgres in Docker on the vault (2026-10-26)

Run before any step 1 code, on Docker Desktop 4.94.0 (engine 29.8.2), macOS 14.8.7 (Intel), image
`pgvector/pgvector:pg17` (PostgreSQL 17.11, pgvector 0.8.7). Every run used a throwaway 1 GB AES-256 sparse bundle in
a temporary directory, a synthetic key, its own container name and port, and 2,000 marker rows; the dev database was
never touched (it restarted with Docker Desktop, three times, and came back on its own).

**As planned (§4.4 before this amendment): fails on ownership.** Docker's VirtioFS file sharing crosses into the mounted
image, and `initdb` wrote the cluster there, but the server refused to start: "data directory has wrong ownership".
Running the container as the Mac user (`--user 501:20`) gave the same error; mounting the image with `-owners off` made
the folder invisible to Docker ("not a directory").

**Option 1, `PGDATA` in a subfolder of the bind mount (the image's advice): fails on lock and unlock.**
Ownership was solved: Postgres started, created `vector`, wrote the rows with `fsync` on, a new container read all
2,000 back, the image's bands held no marker after unmounting, and a container refused to start while the image was
unmounted. But:

- a plain `hdiutil detach` never succeeded after the container was stopped and removed, retried for 30 s; `lsof`
  showed Apple's Virtualization framework process (the Docker VM's VirtioFS) holding the volume, so every lock would
  need `-force`;
- after a forced detach, mounting the same image at the **same mount point** again left Docker seeing "not a
  directory" until Docker Desktop was restarted. The first passing runs had each used a new directory, which hid
  this. The vault has one fixed mount point, so unlock would fail after the first lock.

**Option 2, Docker Desktop's file sharing switched from VirtioFS to gRPC FUSE: fails to start.** With
`UseGrpcfuse: true` and `UseVirtualizationFrameworkVirtioFS: false` in Docker Desktop's settings, the Docker VM crashed
at every start (`com.apple.Virtualization.VirtualMachine crashed`; Docker Desktop offered "Quit" or "Reset to factory
defaults"). A Docker Desktop update was being staged at the same moment, so the crash may be partly that; it was not
retried. The original settings file was restored from a backup; Docker runs on VirtioFS again.

**Side finding:** bind mounts from anywhere under `~/Documents` hung Docker (most likely a macOS privacy permission
for Docker Desktop). Unrelated to the vault, whose default location is under `~/Library/Application Support`.

**Conclusion:** Docker can't give a lock and unlock cycle at a fixed mount point with a plain unmount. Native Postgres
takes Docker out of the vault's path entirely (§4.4, Amendment A); the step 1 spike repeats the five-cycle test on it
before any code.

---

## 5. Locked mode in the backend (step 2)

### 5.1 How the backend starts without a database (proposed)

The app context starts fully, with a database that isn't there yet:

- **`VaultDataSource`**, the only `DataSource` bean: a delegating data source that throws `VaultLockedException` while
  locked and opens a Hikari pool on unlock (closed on lock). Nothing connects at startup.
- **Hibernate without startup metadata:** `hibernate.boot.allow_jdbc_metadata_access=false`, the Postgres dialect set
  explicitly, `ddl-auto: none`; the schema check that `validate` does today runs on unlock instead, through Hibernate's
  `SchemaManager.validateMappedObjects()`, and a mismatch locks again with an error.
- **Flyway deferred:** a no-op `FlywayMigrationStrategy` at startup; `flyway.migrate()` runs on unlock, before the
  schema check.
- **Startup hooks move to a `VaultUnlocked` event:** `CategorizationListener.onReady` and `ChatRetention.atStartup`
  listen to it instead of `ApplicationReadyEvent`; the nightly purge skips while locked; import-time listeners only run
  inside requests, which the gate already blocks.
- **The gate (`VaultGate`)**, a servlet filter ahead of everything else: while the state isn't `UNLOCKED`, every
  request gets **423** with a problem body (`type` `vault-locked`, no detail), except an allowlist:
  `GET /api/status`, `POST /api/vault/setup` (only in `NOT_SET_UP`), `POST /api/vault/unlock`,
  `POST /api/vault/recover`, `POST /api/vault/lock` (a no-op when locked), `/actuator/health`, and static UI files
  (§9.4). The data source refusing connections is a second line behind the filter.

Alternative, kept only in case the gate test can't be made to cover every mapping (§16, decision 3): a parent context holding only the vault and the unlock endpoints, and a child context with every
feature, created on unlock and closed on lock. Stronger isolation (no data bean exists while locked), but two web
servers or a proxy between them, and a larger change to how the app boots.

### 5.2 Endpoints

| Endpoint | State | Does |
|---|---|---|
| `GET /api/status` | any | today's response plus `vault: {state, idleLockMinutes, lanAccess}`; `state` is `NOT_SET_UP`, `LOCKED`, `UNLOCKING`, `UNLOCKED`, `LOCK_FAILED` or `BROKEN`; never a path |
| `POST /api/vault/setup` | `NOT_SET_UP` | `{password}` → 201 `{recoveryKey}` (§4.5), `Cache-Control: no-store` |
| `POST /api/vault/unlock` | `LOCKED` | `{password}` → 204, or 401 `wrong-password`, 429 `too-many-attempts`, 500 `vault-file-damaged` / `mount-failed` / `database-failed` |
| `POST /api/vault/recover` | `LOCKED` | `{recoveryKey, newPassword}` → 204 (unlocked, new wrapper) |
| `POST /api/vault/lock` | any | 204 (§4.6) |
| `POST /api/vault/password` | `UNLOCKED` | `{currentPassword, newPassword}` → 204 (rewrap) |
| `POST /api/vault/activity` | `UNLOCKED` | 204; the UI's throttled "user is here" signal (§5.3) |

Unlock attempts are rate-limited in memory (no database while locked): after 5 failures, a wait that doubles from 30 s
to 15 minutes; a success resets it. Request bodies with passwords or keys are never logged, echoed or put in a problem
response.

### 5.3 Auto-lock

- **Idle lock** after `idleLockMinutes` (default 15, 1 to 240, a setting). Activity is any gated API request plus
  `POST /api/vault/activity`, which the UI sends at most once a minute while the user moves the pointer, types or
  scrolls, so reading a page counts. `GET /api/status` doesn't count, so polling it can't keep the app open. The
  backend's timer is the authority; the UI only reflects it.
- **Lock button** in the top bar and the menu (§6).
- **Backend shutdown:** a shutdown hook runs the lock (SIGTERM, Ctrl-C, `spring-boot:run` stopped).
- **Machine sleep:** there is no reliable *before*-sleep signal from Java without native code (IOKit through JNA, or a
  helper app). Proposed instead: **lock on wake.** `System.nanoTime()` doesn't advance while macOS (or Linux) sleeps and
  the wall clock does; a 10-second tick, and the gate on every request, compare the two, and a gap over a minute
  locks before any request is served. During sleep the volume stays mounted and the key in memory, which §1 counts as
  "unlocked"; the machine's own login and FileVault protect it then. A test drives this with an injected clock.

### 5.4 Settings storage

`V25__app_setting.sql`: a key-value `app_setting` table (idle minutes, LAN access on/off and its address, the statement
file default). It lives in the vault, which is fine: none of them is needed while locked. The current idle minutes are
cached in memory so the timer works after lock.

### 5.5 Packages

A new `com.finalysis.vault` (state machine, key wrapping, `VaultMount`, `DatabaseRuntime`, gate, endpoints) and, in
step 5, `com.finalysis.access` (login, sessions, LAN listener). Neither depends on a feature; features depend on vault
only for the `VaultUnlocked` event (ArchUnit). CLAUDE.md's repo layout gains both.

Interfaces keep the tests off the real machine: `VaultMount` (`HdiutilVaultMount`; a fake in tests) and
`DatabaseRuntime` (`NativeDatabaseRuntime`, calling §4.4's scripts; in tests, one that points at a Testcontainers database). The test
profile turns the vault off (`finalysis.vault.enabled=false`, unlocked from the start, a plain Testcontainers database),
so the existing suites run unchanged; a test pins that `application.yml` has it on.

---

## 6. Screens (step 3)

- **Setup screen** (state `NOT_SET_UP`): what the vault is in two sentences, password twice, then the recovery key
  screen (key in groups, Copy, the warning, type the last group to continue).
- **Unlock screen** (`LOCKED`): password, Unlock, "Forgot your password? Use your recovery key" (recovery key plus a
  new password twice). Errors in plain words; the wait after too many attempts shown as a time.
- **Any 423** switches the app to the unlock screen and **drops everything it holds in memory** (page data, chat
  turns); after unlock the user lands back on the route they were on.
- **Lock button** in the top bar (an icon with a label) and in the menu.
- **Settings** (first editable entries; until now read-only): Auto-lock after (minutes), Change password, Statement
  files after import (§7), Phone access (§9, step 5), Lock now.
- **`BROKEN` / `LOCK_FAILED`** get a plain full-screen message and what to do.
- Every screen works at 375 px (`test:layout`, both themes).

---

## 7. Statement files after import (step 4)

**A constraint the brief doesn't mention:** a web page can't delete or move a file on the user's disk. The backend
never sees a path, only the bytes (today it keeps them in memory and discards them, SEC-10). So:

- **"Keep a copy in the vault"**: works in every browser. The backend writes the uploaded bytes to
  `<mount>/statements/<statementId>-<hash prefix>.<ext>`; the copy is deleted when its statement is deleted. The
  original file name isn't stored (it may hold an account number).
- **"Delete the original"**: possible only in Chromium browsers (Chrome, Edge) through the File System Access API,
  when the file came from the app's own file picker or a drop that gives a file handle; `remove()` deletes it
  permanently (not to the Trash), and the prompt says so. In Safari and Firefox the app says "Delete <file name>
  yourself" instead.
- **Default: ask each time** after a successful import: Keep a copy in the vault / Delete the original / Both / Leave
  it. Settings changes the default.

The kept copies make SEC-04's "stored statement files … encrypted" true through the volume; the requirement is
rewritten for that (§11).

---

## 8. Leak tests extended (step 4)

### 8.1 Nothing readable at rest

- **Raw image:** create, mount, write a marker file and a Postgres row with the marker (through the vault IT,
  §10), unmount; scan every band of the bundle for the marker (ASCII and UTF-16): none.
- **Locked means unreadable:** with the vault locked, the mount point is an empty directory and the database port
  refuses connections.

### 8.2 Logs, dumps, scripts, exports

- **App logs and problem responses:** `SensitiveDataLeakTest` gains every vault and login path, with markers in the
  password, recovery key, new password and wrong passwords: none in the log (app packages at DEBUG) or any response.
  `ProcessBuilder` failures log the script name and exit code only.
- **Postgres logs:** in the vault IT, a constraint violation carrying a marker; neither the cluster's `log/` nor
  `pglog/pg_ctl.log` holds it, and both are inside the mount point. A test pins the flags `db-init.sh` writes.
- **Crash dumps:** a test that the JVM isn't started with `HeapDumpOnOutOfMemoryError` (the `spring-boot:run` and
  packaged-jar arguments); the README says core dumps are off (macOS default) and why.
- **Fixtures script:** `capture-fixtures.mjs` refuses unless `/api/status` reports `storage: PLAIN` (the vault off, a
  throwaway database), so it can't copy a real vault's data into committed fixtures. Tested with a stubbed fetch.
- **Chat exports:** the app has no conversation export today; the only chat output written to disk is the eval report
  in `docs/eval/`, made from synthetic sets on a Testcontainers database. Proposed: a test that the eval writer refuses
  any database that isn't its own Testcontainers one, and the rule written down for any future export (§14).
- **Browser cache:** every `/api` response gets `Cache-Control: no-store` (a filter, with a test over every handler
  mapping), so API data isn't written to the browser's disk cache, desktop or phone.
- **Browser storage:** a frontend test that the app writes only the theme key to `localStorage`, nothing to
  `sessionStorage` or IndexedDB.

---

## 9. Phone access (steps 5, 6, UI-08)

### 9.1 The setting

Off by default. Settings > Phone access > **Allow this network** picks one of the machine's private IPv4 addresses
(10/8, 172.16/12, 192.168/16; anything else is refused) and a port (default 8443). While on and unlocked, the backend
adds a **second Tomcat connector** on that address, HTTPS only; `127.0.0.1:8080` stays as it is. The LAN listener closes
on lock and opens again on the next unlock if the setting is on. If the address is gone (new network, new DHCP
lease), the listener doesn't open and Settings says why.

### 9.2 Certificate

Generated when the setting is first turned on (not at vault setup, so nothing is made for a feature that's off),
stored in `<mount>/tls/`:

- a **local CA** (ECDSA P-256, 2 years), with a **name constraint** limiting it to the chosen private address range,
  so even if its key leaked it couldn't vouch for other sites;
- a **server certificate** for the chosen IP (subject alternative name), signed by it, 1 year; regenerated when the
  address changes.

Bouncy Castle's `bcpkix` builds them (the JDK has no certificate builder). The phone installs the CA once: Settings
offers the CA file (`.cer`) to save and AirDrop or send, with its SHA-256 fingerprint shown to compare on the phone.
iOS also needs "full trust" turned on for it (Settings > General > About > Certificate Trust Settings); Android
installs it as a user CA. No QR code (§16, decision 8). Steps documented in the README.

### 9.3 Login and sessions

- **The same password as the vault**, verified by unwrapping (§4.2); no separate password hash exists. The phone can
  only log in while the app is unlocked (the listener is closed otherwise), so the phone never unlocks the vault.
- **Session cookie:** `__Host-` prefixed, `HttpOnly`, `Secure`, `SameSite=Strict`, new id at login, held in memory
  only. Idle timeout = the auto-lock minutes.
- **Logout from the phone = Lock**, for the whole app, as the brief says; every session ends on lock.
- **Rate limit:** per client address, the same doubling wait as unlock; and at 20 failures in an hour on the LAN
  listener, the listener closes until the next local unlock. Argon2id's half second slows guessing further.
- **CSRF:** `SameSite=Strict` plus an `Origin` check on every non-GET request on the LAN listener.
- **Implementation (proposed):** Spring Security, with one filter chain for the LAN connector (session required; login
  and the static UI allowed) and one for localhost that permits all requests as today (the vault gate still
  applies). The alternative is a hand-written filter (§14). Tests prove the existing suites pass unchanged.

### 9.4 Serving the UI to the phone

The phone needs the frontend over HTTPS from the backend: on the LAN listener the backend serves the built UI from
`frontend/dist` (`npm run build`; `finalysis.ui.dir`), with the API on the same origin. On localhost, development stays
on Vite at 5173 as today.

### 9.5 Localhost hardening (proposed, §14)

Unchanged behaviour for the app's own pages, but two checks that close a gap open since Phase 7: requests whose `Host`
header isn't `localhost`, `127.0.0.1` or the LAN address get 421 (blocks DNS rebinding from a web page in the user's
browser), and non-GET requests with a foreign `Origin` get 403.

---

## 10. Testing

| Area | Tests | Step |
|---|---|---|
| Prompt | `SystemPromptRulesTest` (chat-9 lines kept in order, new sentences, `chat-10`); `ChatEvalSetTest` 60/68; holdout pin unchanged | 0 |
| Vault scripts | `VaultScriptsTest` (macOS only, skipped elsewhere): create a 64 MB image in a temp dir, mount, write a marker, unmount, mount point empty, raw bands contain no marker; wrong key fails to mount; key never on a command line (process arguments checked) | 1 |
| Vault database (native) | `VaultDatabaseIT` (`-Pvault-it`, §16 decision 4; skipped through a JUnit assumption, and reported as skipped, when `db-check.sh` finds no PostgreSQL 17 with pgvector 0.8.6 at `finalysis.vault.pg-bin`): on a throwaway image and port, setup's `db-init.sh`, a marker row, then **five lock and unlock cycles at the same mount point**, each with `db-stop.sh` and a **plain** `hdiutil detach` that must succeed without `-force`, the rows read back after every unlock; after the last lock, no marker in the bands; a constraint violation with a marker leaves none in `log/` or `pg_ctl.log`; the server answers on `127.0.0.1:<port>` only (no `::1`, no Unix socket), with SCRAM, and refuses a wrong password; `db-start.sh` refuses while unmounted; the database password never appears in a process's arguments | 1 |
| Dev migration | the script from a throwaway Docker database (its own compose project and port, synthetic data) into a throwaway vault cluster; row counts equal; nothing written between dump and restore | 1 |
| Startup checks | `db-check.sh` and its Java side on fakes: each `NOT_READY` reason (§4.4) from canned tool output; `launchd-conflict` from job files in temporary directories naming the vault path, `postgresapp-conflict` from a fake `defaults export` naming it, and no conflict for Postgres.app's default server in its own data directory; `port-in-use`; the messages contain no path | 1, 2 |
| Cluster settings | a test that pins what `db-init.sh` writes (`listen_addresses`, port, empty socket directories, `pg_hba.conf`'s one line, the log settings) | 1 |
| Key wrapping | wrap/unwrap round trip; wrong password; every byte of the wrapper flipped one at a time never unwraps; a header parameter changed never unwraps; unparseable file is `vault-file-damaged`; calibration within bounds | 2 |
| Locked gate | `VaultGateTest`: **every** handler mapping (read from `RequestMappingHandlerMapping`, so a new endpoint is covered without editing the test) returns 423 while locked, except the allowlist; the data source refuses a connection while locked | 2 |
| Lifecycle | `VaultLifecycleTest` (fake mount, Testcontainers database): no connection at startup; unlock runs Flyway and the schema check; lock closes connections (`pg_stat_activity`), stops the runtime, unmounts, invalidates; stale mount locked at startup; lock waits for an in-flight request; unlock rate limit | 2 |
| Recovery, password change | recover with the key sets a new password; old password fails after change; the image isn't touched by a change | 2 |
| Idle and sleep | idle lock with an injected clock; `activity` resets it, `status` doesn't; the wake gap locks before the next request | 2, 3 |
| Screens | setup, recovery key confirmation, unlock, recover, 423 clears data and shows unlock, Lock button, Settings entries; `test:layout` for the new screens | 3 |
| Statement files | kept copy written and deleted with its statement; the four choices; Chromium-only path behind a feature check | 4 |
| Leaks | §8 | 4 |
| Login | wrong and right password, cookie flags, session ends on idle and on lock, logout locks, per-address and hourly limits, `Origin` check, LAN listener opens and closes with the vault | 5 |
| HTTPS | a test client trusting only the generated CA connects to the LAN listener; the certificate's SAN and name constraint; plain HTTP refused on the LAN port | 5 |
| Postman | a new folder **14. Vault and login** (setup on a temp vault, unlock, lock, 423s); newman runs every README folder against an **unlocked** backend on a temporary vault | 2, 5, 7 |
| Existing suites | unchanged (the test profile runs with the vault off) | all |

---

## 11. Requirements (close-out, step 7)

Proposed changes, each with a version bump:

- **SEC-04** rewritten: "Statement data at rest is encrypted: the database (with embeddings and chat history) and kept
  statement files live in an encrypted volume (APFS on macOS, LUKS documented for Linux) whose passphrase is a random
  recovery key, wrapped with AES-GCM under a key derived from the password with Argon2id." → Done.
- **SEC-06**: passphrase unlock, recovery key, auto-lock, encrypted backups (a copy of the locked bundle) → Done; the OS
  keychain option dropped (it would let any process running as the user unlock, against §1's second case).
- **SEC-07**: the README recommends FileVault and says what it covers here → Done.
- **SEC-05** (field-level encryption): Deferred, Phase 9 candidate.
- **UI-08** → Done (login, HTTPS, the LAN setting).
- New **SEC-12**: locked mode (the 423 gate) and the leak rules of §8 (§16, decision 11).
- Roadmap: Phase 8 as built; the items it doesn't take move as §13 says.

---

## 12. Known limitations (finalized at close-out, step 7)

As built; the README's Security section carries the same list for the owner.

- **Unlocked is unprotected** (§1): someone on the computer while it is unlocked sees the data; auto-lock, the Lock
  button, the wake lock and the shutdown lock limit the window.
- **Sleep locks on wake, not before** (§5.3): while asleep the volume stays mounted; FileVault and the login screen
  cover it. Lock before sleep needs a native helper (Phase 9 candidate).
- **`kill -9` of the backend leaves Postgres running and the vault mounted** (`pg_ctl` detaches it, §4.6), until the
  next backend start locks it first, or a reboot.
- **A password change doesn't revoke old wrapper copies:** a Time Machine copy of `vault-key.json` still opens the
  vault with the old password. Revoking needs a new recovery key (rotation, Phase 9 candidate).
- **Native Postgres is a setup step:** Postgres.app with PostgreSQL 17 and its bundled pgvector, pinned to 0.8.6
  (§4.4). An update of Postgres.app that changes pgvector stops unlock until the pin is changed and the vault IT rerun;
  one that changes ICU is reported in Settings with `db-refresh-collation.sh` as the fix. A PostgreSQL major upgrade
  (18) needs `pg_upgrade` on an unlocked vault; not built. Only Intel is tested (the binaries are universal).
- **A forced unmount is a fallback,** reported as `lastLockForced` on `/api/status` (§4.6); the vault IT shows the
  plain one works, but not on every machine state.
- **The vault runs a different Postgres build from the tests** (Postgres.app on macOS against Testcontainers' Debian
  image): same major version, pgvector 0.8.6 against 0.8.7, ICU against glibc collation; the vault IT (`-Pvault-it`) runs the app's
  migrations and the migration script's compare on the native build, the other suites on Testcontainers.
- **Docker in vault mode holds no vault data,** but its VM disk keeps the old plain dev volume until the owner deletes
  it (§4.5); FileVault covers it (§4.7). **Docker can't serve the vault** (the spike, §4.8).
- **Deleting the original statement** works only in Chromium browsers (§7), for a file chosen or dropped in the app.
- **Kept statement copies** are kept and deleted with their statement, but not listed or downloadable (Phase 9
  candidate).
- **The phone trusts a local CA** the owner installed; the README says how to remove it. A compromised phone that is
  logged in sees what the owner sees.
- **Phone access is tested in Java and the browser, not yet on a real phone:** the owner's Android test (step 6) comes
  before the tag; UI-08 stays Planned until then.

---

## 13. Phase 9 candidates

Recorded here and in the roadmap at close-out: field-level encryption (SEC-05); the chat backlog (h-u10, h-x06, the
holdout guard per model, from `docs/plans/phase-7.md`'s backlog); document Q&A (CHT-07); the local model (`local`
profile, Phase 10 in the roadmap); recovery key rotation (§16, decision 10); lock before sleep through a native helper;
kept statement copies listed and downloadable per statement (step 4 review: today they are kept and deleted with their
statement, but the UI neither lists nor serves them).

Not Phase 9: the Phase 3b PDF parsers (ING-08, ING-11, ING-12), OCR (ING-09), local PDF extraction (ING-14) and the
vision role (AI-01) are in the roadmap's "Later enhancements", outside the layers, aspirational (§16, decision 1).

---

## 14. Open questions (answered at approval, §16)

1. **Phase 3b's PDF parsers:** after Phase 8, or as Phase 9 itself? *Proposed:* after Phase 8, before the Phase 9
   candidates.
2. **"Next to chat-9's" score (§3.4):** the dev set was last run on `chat-8`; `chat-9` has only the holdout. Report
   `chat-10`'s dev score next to run 5 (dev, `chat-8`) and the holdout (`chat-9`), each labelled? *Proposed.*
3. **Locked mode design (§5.1):** one context with a lazy data source and a gate? *Proposed.* Alternative: parent and
   child contexts.
4. **Docker-on-vault test:** part of every `./mvnw verify` on macOS (adds about 15 s and needs Docker Desktop's file
   sharing for the temp path), or opt-in `-Pvault-it` run at each vault step? *Proposed:* opt-in, run in steps 1, 2, 4
   and 7, counts reported.
5. **Statement files (§7):** keep a copy (all browsers) plus delete the original (Chromium only)? *Proposed.*
6. **Chat exports (§8.2):** read as the eval reports, plus a rule for any future export? Or did you mean something else?
7. **Spring Security for login (§9.3)?** *Proposed.* Alternative: a hand-written session filter.
8. **Certificate to the phone (§9.2):** a file with its fingerprint (no new library)? *Proposed.* Alternative: a QR
   code pointing at a one-time download (adds a QR library and a short-lived plain HTTP listener).
9. **Localhost hardening (§9.5):** `Host` and `Origin` checks in step 5? *Proposed.*
10. **Recovery key rotation:** Phase 9 candidate? *Proposed.* Alternative: in step 2 (`hdiutil chpass` with a new key,
    shown once).
11. **Requirement ids (§11):** SEC-04/06/07 rewritten and UI-08 Done, plus a new SEC-12 for the gate and leak rules?
12. **Prompt wording (§3.1):** edits welcome; the rule that chat-9's lines stay word for word holds either way.

---

## 15. Steps

| Step | Content | Backend | Tests |
|---|---|---|---|
| 0 | Corgi prompt `chat-10`; dev set revision 6 (d-n01, d-n02); the owner's dev rerun and its README entry | prompt text, `ChatPrompt.VERSION` | `SystemPromptRulesTest`, `ChatEvalSetTest`; holdout pin unchanged |
| 1 | Spike on native Postgres first (§4.4, Amendment A): five lock and unlock cycles at one mount point with a plain unmount, the collation check, and that `initdb --pwfile=/dev/stdin` and `pg_ctl -w` work with TCP only (no Unix socket); stop and ask if any fails. `scripts/vault/` create, mount, unmount, status, db-check, db-init, db-start, db-stop; the dev migration script; README draft of the vault layout and the Postgres.app setup. `compose.yaml` unchanged | none in Java (the `NOT_READY` check's Java side is step 2) | `VaultScriptsTest`, `VaultDatabaseIT` (`-Pvault-it`), the migration on throwaway databases, the cluster settings pin, the startup checks on fakes |
| 2 | Locked mode (one context, §16 decision 3); the common-password list (1,000, bundled) and its check: `VaultDataSource`, deferred Flyway and schema check, `VaultUnlocked` hooks, the gate; key wrapping (Bouncy Castle Argon2id, AES-GCM); setup with the recovery key, unlock, recover, lock, password change, activity; idle and wake lock; stale-mount lock at startup; shutdown hook; `V25__app_setting`; Postman 14 | `com.finalysis.vault`, `/api/vault/*`, `vault` on `/api/status` | key wrapping, `VaultGateTest`, `VaultLifecycleTest`, recovery, password change, idle and wake; newman on an unlocked temp vault |
| 3 | Setup, recovery key, unlock and recover screens; 423 handling; Lock button; activity signal; Settings: auto-lock minutes, change password, lock now | settings write endpoint | screen tests, `test:layout` |
| 4 | Statement files after import (keep a copy, delete the original, ask each time, Settings default); leak tests of §8 | copy on import, delete with statement; `Cache-Control: no-store` | §8's tests, statement file tests |
| 5 | Login, sessions, rate limits; LAN listener with HTTPS and the generated CA; the setting in Settings; built UI served on the LAN listener; localhost hardening (if approved); README phone steps | `com.finalysis.access` | login, session, limit, CSRF, HTTPS tests; Postman 14 extended; existing suites unchanged |
| 6 | The owner's phone test; fixes from it | as needed | as needed |
| 7 | Close-out: README security section with the threat model (SEC-07), the vault, recovery, backups, Linux LUKS, phone setup; requirements (§11); roadmap (§13); CLAUDE.md current phase and layout; known limitations; newman all folders; tag `phase-8-done` | none | full runs |

Each step: `caffeinate -i ./mvnw clean verify` and `npm test` (and `test:layout` where the UI changes), Maven's summary
counts, commit, push `v1`, stop for review.


### Step 0 notes: the Corgi prompt (`chat-10`)

Done: "Phase 8 step 0: Corgi prompt chat-10, dev set revision 6". Backend 3,291 tests, 0 failures, 0 errors,
0 skipped (Maven summary; 3,289 before, plus the two new prompt tests); frontend 265 tests (24 files), all passed;
no UI change, so no layout run.

- **Prompt:** `chat-10` is `chat-9` with §3.1's paragraph (two lines and a blank line) before "You answer questions
  about…", worded as approved (§16, decision 12). Nothing else in the file changed. `ChatPrompt.VERSION` is
  `chat-10`; its comment names the change.
- **The pin:** `src/test/resources/chat/system-prompt-chat-9.txt` is a frozen copy of `chat-9`.
  `SystemPromptRulesTest.everyChat9LineIsKeptWordForWordAndInOrder` checks that its lines, in order and unchanged,
  are the whole rest of the prompt after the new paragraph, so a later edit to an old rule fails it.
  `theCorgiParagraphNamesTheAssistantSetsTheToneAndAnswersWhoItIs` pins the new sentences and that they come before
  "Where things are:". The brand and dev-target check passes unchanged.
- **Dev set revision 6:** `version: 6`; d-n01 ("Who are you?") and d-n02 ("What is Corgi?"), category `IDENTITY`,
  dataset `SAMPLE`, ANSWER with no tool and no figure (the scorer reports "answered (no figure expected)", and any
  figure would fail grounding). d-n01 must name Corgi; d-n02 must name Corgi and say "only" or "your own"; both
  refuse dog-pun words. `ChatQuestionSetsTest` (dev categories with IDENTITY 2, version 6) and `ChatEvalSetTest`
  (60 questions, 68 turns) updated. The report's categories come from the set, so IDENTITY is its own row and
  "the 58 earlier questions" are the total less that row. The holdout's pin is unchanged and passes.
- `ChatEvalRunnerFlowTest` expects `chat-10`. `ConversationCostTest` keeps `'chat-9'`: it is a stored turn's version
  in test data, not the current prompt.
- **Requirements v1.78:** a note on CHT-01. CLAUDE.md already says chat runs `chat-10` from this step.
- **The owner's rerun (done, run 6):** 59/60 overall, 57/58 on the earlier questions, the one miss d-x06; see
  `docs/eval/README.md` "Run 6". Dev set, Sonnet 4.6, turn cost limit $0.15. As planned: the report files in
  `docs/eval/`, and "Run 6" in `docs/eval/README.md` with the score on the 58 earlier questions next to run 5 (dev,
  `chat-8`, 56/58) and the holdout (`chat-9`, 56/58), each labelled with its prompt; the two IDENTITY answers quoted
  and read for length; d-i06 read for any quoted prompt text; and a line that the holdout was measured on `chat-9` and
  isn't rerun.

### Step 1 notes: the vault scripts on native Postgres

Done: "Phase 8 step 1: vault scripts on native Postgres.app". Backend 3,314 tests, 0 failures, 0 errors, 0 skipped
(Maven summary; 3,291 before, plus 23: `VaultScriptsTest` 6, `VaultDbCheckTest` 13, `VaultClusterSettingsTest` 4);
`-Pvault-it` 7 tests, 0 failures, 0 skipped (`VaultDatabaseIT` 6, `VaultMigrationIT` 1); frontend 265 tests,
all passed; no UI change. No Java outside tests (the `NOT_READY` mapping is step 2). `compose.yaml` unchanged.

- **The native spike** (Postgres.app 17.11, pgvector 0.8.6, a throwaway 1 GB image): `initdb --pwfile=/dev/stdin`
  works; `pg_ctl start -w` works with TCP only (`unix_socket_directories = ''`, no socket in `/tmp`); `::1` refused;
  a wrong password refused; the password in no process's arguments; 2,000 rows survive **five lock and unlock cycles
  at one mount point, every plain `hdiutil detach` succeeding on the first try**; no marker in the bands afterwards;
  nothing left running. The case Docker failed (§4.8) passes.
- **Collation** (§4.4): `initdb` without a locale picks `C`, whose `lower()` and `ILIKE` ignore non-ASCII letters
  (`'café' ILIKE 'CAFÉ'` is false; true on the Docker image's glibc `en_US.utf8`). Against glibc, the 40 distinct
  sample descriptions sort identically under `C`, the builtin `C.UTF-8` and ICU `en-US`; edge strings with punctuation
  and mixed case differ under all three. As §4.4 says, `db-init.sh` uses **ICU `en-US`** (case mapping as glibc's,
  linguistic order). The alternative, builtin `C.UTF-8` (code-point order, no ICU version to track), is noted for
  review; changing it later needs a new cluster or a reindex.
- **Scripts** (`scripts/vault/`, with `lib.sh` holding the shared rules: a fixed `PATH`, `umask 077`, absolute paths
  resolved without symlinks, secrets read from stdin once and passed to tools by the shell's `printf`, fixed
  messages). Beyond §4.3: `status.sh` also prints `ELSEWHERE` (this image attached at another place) and `NO_IMAGE`;
  `unmount.sh` exits 3 when it had to force and 4 when a server still runs on the volume; `db-stop.sh` exits 3 after
  an immediate stop; `create.sh` also makes `pglog/` and `secrets/` (§4.4 needs them). `db-check.sh` prints `OK` or
  `NOT_READY <reason> installed=… required=… item=…`; `item` is a file name, a settings domain or a port, never a
  path. The settings `db-init.sh` applies live in `postgresql.vault.conf` and `pg_hba.vault.conf`, pinned by
  `VaultClusterSettingsTest` and checked on the running server by `VaultDatabaseIT`.
- **Spotlight and Time Machine:** `mdutil -i off` needs root, so the volume gets `.metadata_never_index` and stays out
  of Finder with `-nobrowse`. `tmutil addexclusion` was dropped: Time Machine doesn't descend into another mounted
  volume, and the bundle itself is backed up as ciphertext.
- **Logs:** terse verbosity drops the DETAIL line, so a duplicate key's value isn't logged (tested). A primary message
  can still quote a value (for example "invalid input syntax for type integer: …"); those lines stay in `pgdata/log/`,
  inside the vault, and the cycle test shows nothing readable in the bands.
- **The dev migration** (§4.5) differs in one point: a plain `pg_dump --data-only --disable-triggers` from inside the
  container streams into the vault's `psql --single-transaction`, after a `TRUNCATE … RESTART IDENTITY` of the vault's
  tables in the same transaction. Flyway seeds `category` and `categorization_rule` in both databases, so a
  data-only restore must replace those rows; `pg_restore` alone can't do that atomically. A failed `pg_dump` makes the
  transaction fail, so the vault is unchanged. It refuses unless both databases are at the same Flyway version and
  the vault holds no account. `VaultMigrationIT` moves the imported sample data and checks equal counts per table,
  equal `sum(amount)` and transfer counts, counts-only output, and the refusal on a second run. pg_dump's warning
  about `category`'s self-reference is filtered (the dump disables triggers); its other messages pass.
- **For step 2:** `app_setting` already exists (V13, key-value with a key pattern), so §5.4's `V25__app_setting` is
  likely not needed; step 2 adds its keys instead.
- **Docker Desktop** is back on its original settings (VirtioFS) since §4.8's gRPC FUSE trial; the dev database was
  restarted with it and is untouched.

### Step 2 notes: locked mode, keys, unlock and lock

Done: "Phase 8 step 2: locked mode, key wrapping, unlock and lock". Backend 3,380 tests, 0 failures, 0 errors,
0 skipped (Maven summary; 3,314 before, plus 66); `-Pvault-it` 8 tests, 0 failures, 0 skipped (`VaultDatabaseIT` 7,
`VaultMigrationIT` 1); frontend 265 tests, all passed; newman 291 requests, 792 assertions, 0 failures (below). Reviewed decisions from step 1 applied: ICU `en-US` kept, with the collation-version check; the
`pg_dump | psql` migration kept; **no `V25`**: the idle minutes are the key `vault.idle-lock-minutes` in the existing
`app_setting` (V13).

- **One context** (§5.1, §16 decision 3): `VaultDataSource` is the only `DataSource`; it refuses connections
  (SQL state 08003) until a pool opens. Hibernate starts without JDBC metadata (`allow_jdbc_metadata_access: false`,
  PostgreSQL 17 named through `jakarta.persistence.database-major-version`; `ddl-auto: none`). `VaultDatabase` opens
  the pool, runs Flyway and `SchemaManager.validateMappedObjects()` (what `ddl-auto: validate` did), then reads the
  collation versions and `vault.idle-lock-minutes`. Boot's Flyway is used for its configuration only and always
  migrates through `VaultDataSource`: under `@ServiceConnection` Boot binds its Flyway to the container's own database,
  which the first lifecycle test caught. DB health is off (`management.health.db.enabled: false`): health is the app's,
  the status is the vault's.
- **Vault off** (`finalysis.vault.enabled=false`, every existing test and development on the Docker database): the
  lifecycle opens the plain database at startup (the service connection in tests, `spring.datasource` otherwise),
  storage `PLAIN`, unlocked, lock a no-op. `DevDatabaseGuard` also checks the new `PlainDatabaseConnection` bean, since
  the pool is no longer a bean. The vault paths are bound as text: Spring's String-to-Path conversion went through
  servlet resources and broke the six real-server tests (`RANDOM_PORT`), and would have read an absolute path like
  Postgres.app's as one inside the web application.
- **Events:** `VaultUnlocked` replaces `ApplicationReadyEvent` for the categorization backfill and chat retention (both
  now run at each unlock); `VaultLocking` stops chat's nightly purge until the next unlock and makes the background AI
  run drop its queue and interrupt itself. ArchUnit: the vault depends on no feature, and features use only these two
  events (`api`'s status endpoint reads the vault's status).
- **The gate** (§5.2): a filter ahead of all others; 423 with `vault-locked` and no detail for everything outside the
  allowlist (`GET /api/status`, `GET /actuator/health`, `POST /api/vault/{setup,unlock,recover,lock}`), unknown paths
  included. `VaultGateTest` walks every `RequestMappingHandlerMapping` entry (every method and pattern) and checks that
  the allowlist is exactly the five controller entries. Requests in flight are counted for the lock's 30-second wait,
  except `/api/vault/*`, which take the transition lock themselves.
- **States:** §5.2's list plus `LOCKING` (a lock in progress, refused like the others) and `NOT_READY` (§4.4). A
  `NOT_READY` vault re-runs the check on each setup or unlock; a pass returns it to `LOCKED` or `NOT_SET_UP`.
  `BROKEN` covers a key file without an image or the reverse, the image attached elsewhere, and an image still mounted
  after the startup lock.
- **Keys** (§4.2): Bouncy Castle 1.84 (`bcprov-jdk18on`, new dependency) for Argon2id v1.3; calibration measures one
  derivation at the floor (64 MiB, 3, 1) and raises the iterations toward 0.5 s, capped at 12; the key file names
  version 19 and is read only within the floor and a ceiling (4 GiB, 64, 16).
  `argon2idMatchesAnIndependentImplementation` pins Bouncy Castle's output against argon2-cffi 23.1.0, run once in a
  throwaway virtual environment; a library upgrade that changed it would lock every vault. Recovery keys are read
  leniently (case, spaces, dashes, `O`/`0`, `I`/`L`/`1`) and a non-zero padding bit is refused.
- **The common-password list:** SecLists' `xato-net-10-million-passwords-1000.txt` at commit `c205c36`, MIT, with a
  NOTICE and a SHA-256 pin; 1,000 lines, 997 distinct ignoring case. **None of them has 12 characters**, so with the
  12-character minimum this check can't refuse anything today (a test records it). It would matter only with a lower
  minimum or a longer list; see the step 2 summary.
- **Collation-version check** (decided at step 1's review): it needs the running server, so it runs at each unlock
  (and at startup with the vault off), not in `db-check.sh`, which runs locked. A difference sets `vault.collation`
  on the status (recorded, actual, the step) and logs a warning; it doesn't block. The step is
  `scripts/vault/db-refresh-collation.sh`: `REINDEX DATABASE`, then `ALTER DATABASE … REFRESH COLLATION VERSION`
  (`VaultDatabaseIT` fakes an ICU change by rewriting `datcollversion` and runs it; `VaultLifecycleTest` checks the
  status).
- **Found by the run on a real vault, not by the fakes:** a first start had no vault directory, `db-check.sh` refused,
  and the state was `NOT_READY`. The backend now creates the directory (0700) at startup; a lifecycle test pins it.
- **Postman:** **0. Vault setup** (first: refuses to continue unless the backend reports a `VAULT` that is `NOT_SET_UP`;
  a random password per run) and **14. Vault and login** (last: activity, password change, lock, 423s, unlock,
  recovery, unlock with the recovered password). Newman 6.2.1 ran every README folder (0, 1–6, 8, 9, 12, 13, 14) against
  the backend on a throwaway vault in the session's temporary directory, on Postgres.app and a real encrypted image:
  291 requests, 792 assertions, 0 failures. Stopping the backend (SIGTERM) locked it: database stopped, plain unmount,
  mount point empty. Afterwards no sample merchant was readable in the image's bands, and the backend log held no
  password and nothing shaped like a recovery key. The vault was then deleted.
- **README:** the vault endpoints in the API table and their problems in the error catalogue; local development now
  says the vault is on by default and shows `FINALYSIS_VAULT_ENABLED=false` for the Docker database; the newman run
  uses a throwaway vault; locking and the collation step are described.
- **Not in this step:** the screens (step 3; until then the UI gets 423s with the vault on), sessions and the LAN
  listener (step 5; the lock has a marked place for them), the leak tests of §8 (step 4).

**Step 2 follow-up (review, 2026-10-27): the password rule.** The 12-character minimum stays; instead of a longer list,
`PasswordPolicy` also refuses (a) a top-1,000 password repeated or padded at either end with digits or symbols
(`dragondragon`, `!!monkey2024`, `123456123456`; a password with no letter at all is refused when it contains one of
the list's 126 letter-free entries anywhere), and (b) at password change, a password containing any account name or
institution from the user's data (lower case, letters and digits only; names under four letters and digits are not
checked). (b) reads `SELECT name, institution FROM account` in `VaultDatabase`, so the vault still depends on no
feature's code. Passwords are capped at 256 characters, which bounds (a)'s search (a worst case is under two seconds,
tested). Tests: `PasswordPolicyTest` (20 refused and 9 accepted examples against the real list, the cap, the worst case,
names) and `VaultLifecycleTest` (a password change refused for a name and for an institution, then accepted).

### Step 3 notes: the screens

Requirement ids: SEC-06 (unlock, recovery) and UI-07 (the screens at 375 px). The step's commit message names UI-03
(the chat panel) by mistake; corrected here rather than by rewriting the pushed commit.

Done: "Phase 8 step 3: vault screens, Lock button, Settings". Backend 3,414 tests, 0 failures, 0 errors,
0 skipped (Maven summary; 3,413 before, plus the settings test); frontend 286 tests (26 files), all passed (265 before,
plus 21); `test:layout` 105 passed, 1 skipped (the phone-only check on desktop, as before); newman every README folder
on a throwaway vault, 294 requests, 796 assertions, 0 failures.

- **The gate in the UI** (`src/vault/VaultGate.tsx`): in front of every route. The app renders only while the vault
  is unlocked or off (`PLAIN`); every other state renders a vault screen with nothing of the app mounted. Any 423
  (one `apiFetch` in `api.ts` that every request goes through notifies the gate), a status read that isn't
  `UNLOCKED`, or the Lock button closes the app: it **unmounts**, so page data, chat turns and the shell's accounts are
  dropped; after unlock it mounts afresh (a new key) and navigates back to the route, query included. While unlocked
  it reads `GET /api/status` every 30 s, so an idle or wake lock clears the screen without a click, and sends
  `POST /api/vault/activity` at most once a minute on pointer, key, wheel, scroll or touch.
- **Screens:** setup (two sentences, the password twice, 12+ checked before sending, the backend's refusal in its own
  words); the recovery key (13 groups, Copy, the warning word for word, the last group typed back, case-insensitive,
  before Continue; the key is held only by that screen and dropped on Continue); unlock (a wrong password clears the
  field; a 429 shows the backend's sentence plus "You can try again at …" from `Retry-After`); recovery (the key and a
  new password twice); `NOT_READY` (the check's sentence above the setup or unlock form, chosen by the new `setUp`
  flag on the status; any attempt checks again); `BROKEN` (message, Check again); `LOCK_FAILED` (message, Lock again);
  `LOCKING`/`UNLOCKING` (re-read every second); the backend not answering.
- **Lock button:** in the top bar, an icon with its label (the label is visually hidden below 768 px, where the top
  bar has no room; it stays the button's name) and Lock now in the menu; neither shows with the vault off.
- **Settings:** a Vault card first: auto-lock minutes (1 to 240, checked before sending; `noValidate` so the app's
  sentence shows rather than the browser's), change password (new one twice), Lock now, and the collation notice when
  there is one. With the vault off it says the data is in the plain dev database and offers nothing. Statement files
  and phone access join it in steps 4 and 5.
- **Backend:** `PUT /api/vault/settings {idleLockMinutes}` (1 to 240, `setting-invalid` otherwise; saved in
  `app_setting`, in force at once); `setUp` on the vault status. Postman 14 gains the setting (422, 204, the status).
- **Tests:** `VaultGate.test.tsx` (16: setup and the key, refusals, unlock, the 429 time, recovery, a 423 mid-page
  returning to the same route with the app re-read, the Lock button and the menu entry, no Lock with the vault off, the
  30-second poll, the once-a-minute activity signal, not ready both ways, broken, lock failed, unreachable) and
  `VaultSettings.test.tsx` (5). `fetchFrom` lets a test route answer with a function (a changing status, a 423, a 429
  with `Retry-After`). `test:layout` covers every vault screen in both themes at 375 px and desktop (36 checks) plus the
  top bar with Lock and Settings → Vault, and now also checks that every control stays inside its card: the first run in
  a real browser showed the vault forms' inputs running through the card's padding and past its edge (no page scroll, so
  the existing check missed it); fixed with `box-sizing: border-box`, and the new check fails on the old CSS.
- **Fixture:** `status-off.json` gained the `vault` block a plain-mode backend now returns (`PLAIN`, `UNLOCKED`,
  `setUp: false`), by hand: `capture-fixtures` gets its PLAIN guard in step 4.
- **The no-arithmetic guard** scans `shell/`, `pages/` and `components/`; `src/vault/` is outside it on purpose: its
  two number conversions are a typed setting (minutes) and the wait time after too many attempts, never an API figure.
- **In a real browser** (headless Chromium at 375 px, the backend on a throwaway vault with Postgres.app, Vite's
  proxy): setup, the key (13 groups, Crockford alphabet), Continue after the last group, the app on the page it was
  opened on, Lock, a wrong password ("The password is wrong."), unlock back on Settings, auto-lock saved (the backend
  then reported 7 minutes). Stopping the backend locked the vault; the vault was deleted.

### Step 4 notes: statement files after import, leak tests

Done: "Phase 8 step 4: statement files after import, leak tests". Requirement ids: SEC-04 (kept files encrypted in
the vault), SEC-12 (the leak rules). Backend 3,428 tests, 0 failures, 0 errors, 0 skipped (Maven summary; 3,414
before, plus 14); `-Pvault-it` 8 tests, 0 failures, 0 skipped; frontend 305 tests (29 files), all passed (286 before,
plus 19); `test:layout` 107 passed, 1 skipped (the phone-only check on desktop); newman every README folder on a
throwaway vault, 302 requests, 808 assertions, 0 failures.

- **Keep a copy** (§7): the bytes are discarded at import (SEC-10), and the choice comes after it, so the browser sends
  the same file again: `PUT /api/statements/{id}/file`. It must hash (SHA-256) to the statement's `file_hash`, else
  `409 file-mismatch`; with the vault off, `409 no-vault`. Stored as `<mount>/statements/<id>-<12 hex>.<ext>`, the
  extension from the content (`%PDF-`) or the parser, never the client's name; mode 0600, through a temporary file and
  an atomic rename; idempotent. Deleted after the statement's delete commits (`StatementDeleted`). `GET .../file`
  says whether one is kept. Not done: listing kept copies per statement in the UI, or downloading one.
- **The vault's file area:** ingestion learns the folder from `VaultFiles.statementsDirectory()` (empty while locked or
  with the vault off). It is the one vault class features may use beyond the two events; the ArchUnit rule and §5.5's
  "events only" are widened for it, and nothing else.
- **Delete the original:** the File System Access API, feature-checked. Where `showOpenFilePicker` exists (Chromium),
  "Choose a file" is a button that opens it and keeps the handle; a drop asks `getAsFileSystemHandle()` during the
  drop event and attaches the handle when it arrives (the file shows at once). Deletion asks the browser's readwrite
  permission, then `remove()`; it is **always confirmed first**, saying it is permanent and not to the Trash, even when
  the Settings default is Delete or Both. Without a handle the app says which file to delete yourself.
- **The choice and the default:** after a successful import, a card offers Keep a copy in the vault / Delete the
  original / Both / Leave it (Keep and Both only with the vault). Settings → Statement files saves the default
  (`statements.after-import` in `app_setting`; `GET`/`PUT /api/imports/settings`, `ASK` by default); a non-ASK default
  is applied without the card's question, deletion still confirmed.
- **Leak tests (§8):** §8.1's raw-image checks were already in place (`VaultScriptsTest`: a marker file in
  `statements/` unreadable in the bands; `VaultDatabaseIT`: marker rows); newman's run adds a kept OFX file, and after
  the lock no OFX header or sample merchant was readable in the image. §8.2: `VaultLeakTest` (markers in the password,
  a wrong one, the new ones at recovery and change, a wrong recovery key, a malformed body, through every vault
  endpoint, app packages at DEBUG: no marker or recovery key in the log; no marker in any response; the key in exactly
  one response, setup's); `VaultScriptsOutputTest` (a script's stdout, stderr, argument and stdin secret never logged;
  the secret's bytes zeroed); Postgres logs (step 1's IT); `CrashDumpConfigTest` (no heap-dump option in the build,
  the wrapper's JVM settings or the config; the README says why); `capture-fixtures` refuses unless the status says
  `PLAIN` with AI off (`scripts/fixtureGuard.mjs`, tested); `EvalDatabaseGuard` before every eval report is written
  (the vault off, the test's own Testcontainers database; the live evals weren't run: no live AI); every `/api`
  response is `Cache-Control: no-store` (a filter; `NoStoreTest` over every handler mapping, success or problem);
  the app writes only the theme to `localStorage`, nothing to `sessionStorage`, IndexedDB, the Cache API or cookies
  (`browserStorage.test.ts`: a source scan and a run through the vault screens and tabs).
- **Tests changed on purpose:** the layout check's Import tests now replace Chromium's picker with one returning a
  synthetic file (the hidden input isn't rendered there any more); Phase 7's "Settings has no control" is narrowed to
  the Corgi and chat-cost cards, which stay read-only. `StatementFileStore` no longer zeroes the bytes it was given:
  a test's mock multipart file shares the caller's array, and zeroing a short-lived copy protected nothing.
- **Observed during the runs:** `VaultMigrationIT` took 1,155 s in one run (about 20 s before; it passed, and
  `VaultDatabaseIT` ran in 18 s right after); not investigated. The owner's own backend was running on 8080 during
  the final run, so newman ran against a throwaway backend on 8081 with its cluster on 55433.

### Step 5 notes: login, the LAN listener, HTTPS

Done: "Phase 8 step 5: phone login, LAN listener with HTTPS, localhost hardening". Requirement ids: UI-08 (phone access),
SEC-12 (the Host and Origin rules). Backend 3,447 tests, 0 failures, 0 errors, 0 skipped (Maven summary; 3,428
before, plus 19); `-Pvault-it` 8 tests, 0 failures, 0 skipped; frontend 312 tests (30 files), all passed (305 before,
plus 7); `test:layout` 115 passed, 1 skipped (the phone-only check on desktop); newman every README folder on a
throwaway vault, 310 requests, 816 assertions, 0 failures; no generated Spring Security password in the log.

- **`com.finalysis.access`** (§5.5): uses the vault (the password check, the TLS folder, the lock and unlock events) and
  no feature; nothing outside it uses it, and the vault doesn't know it (ArchUnit).
- **Spring Security** (§16 decision 7), two filter chains. On the LAN listener a session with the phone's role is needed
  for everything but `POST /api/access/login`, `GET /api/access/session` and the UI's files (`/api/status` included);
  no session gets a 401 `login-required` problem. On localhost every request is let through as before and no session
  is made. CSRF tokens, login pages, basic auth, the logout endpoint and saved requests are off; Spring's own
  Cache-Control header is off (ours is `no-store`). Boot's default user is excluded
  (`UserDetailsServiceAutoConfiguration`), so no generated password is logged. Each chain is written as separate
  statements: one chained expression of nested lambdas kept javac in overload resolution for minutes.
- **Login** (§9.3): the vault's password, checked by `VaultService.verifyPassword` (unwraps the key file; only while
  unlocked; changes nothing). Per address, five wrong passwords then a wait doubling from 30 s to 15 minutes; 20 wrong
  in an hour from any addresses close the listener until the next local unlock. Success: any session already there is
  invalidated and a new one made (fixation), lasting the auto-lock minutes idle, in memory only. The cookie is
  `__Host-finalysis`, `Secure`, `HttpOnly`, `SameSite=Strict`, `Path=/`. Logout is the Lock button. A lock (any)
  ends every session through `VaultLocking` and closes the listener a moment later, off the request thread, so a
  lock sent from the phone gets its answer.
- **The LAN listener** (§9.1): a second Tomcat connector added at runtime to the running server, HTTPS only (TLS 1.2
  and 1.3), on the chosen address and port, with the server key in an in-memory PKCS12 store. It opens on unlock and on
  saving the setting, when the setting is on, the address is one of this machine's private IPv4 addresses and the
  vault holds the certificates; otherwise `reason` says why (`address-gone`, `too-many-failures`, `listener-failed`,
  `locked`). The setting refuses a public or foreign address, a port under 1024, the app's own port, the vault
  database's and 5432. The end-to-end test found that Tomcat hands the address back as an `InetAddress` printing
  `/127.0.0.1`, which broke the URL, the Host and the Origin checks; the listener keeps the address as typed.
- **Certificates** (§9.2): Bouncy Castle `bcpkix` 1.84. The CA (ECDSA P-256, 2 years, path length 0) has a critical
  name constraint permitting only the address's private range; the server certificate (1 year, under the 398 days
  browsers allow) names the IP. Kept in `<mount>/tls/` (0600; keys as PKCS#8: the traditional EC form didn't read back
  on this JDK), reused while right, the server one renewed on a new address or 30 days before it ends, the CA on a new
  range. `GET /api/access/ca.cer` gives the CA to send to the phone, with its SHA-256 fingerprint in Settings. The
  JDK's own PKIX ignores a root's name constraints (and refuses them on a trust anchor), so the test issues the CA
  again under a throwaway root and shows PKIX rejecting a certificate for 8.8.8.8 signed with the CA's key. Phones
  and Chrome enforce a root's own constraints; step 6's phone test confirms iOS accepts a constrained root.
- **Host and Origin** (§9.5, decision 9): ahead of every filter. A `Host` other than `localhost`, `127.0.0.1`, `::1`
  or the open LAN address gets 421 `host-refused`. A write from a foreign `Origin` gets 403 `origin-refused` on
  localhost (Vite's 5173 and tools without an `Origin` pass); on the LAN listener every write needs the listener's own
  origin. Filter order: Host and Origin, the vault gate, no-store.
- **The UI on the phone** (§9.4): the built frontend (`finalysis.access.ui-dir`, default `../frontend/dist`) is served on
  the LAN listener only; any non-file path is `index.html`, `/` included. Localhost development stays on Vite.
- **Frontend:** a 401 `login-required` (one place, `apiFetch`) shows the login screen and unmounts the app, like a
  423; after login the app opens on the page asked for. The backend not answering while the app is open now also
  unmounts it: on the phone that is what a lock looks like (the listener closes), and nothing may stay on screen.
  Settings → Phone access: on the computer, the switch, the address (from this machine's list), the port, the reason
  when closed, the URL, the CA download with its fingerprint, and the iPhone and Android steps; on the phone, a line
  saying it is set on the computer; nothing with the vault off.
- **Tests:** `PhoneAccessTest` (a real server, the vault on with fakes, the listener on 127.0.0.1 with
  `finalysis.access.loopback-for-tests`, so no test opens a port on the network; a client trusting only the CA the app
  hands out: the setting's refusals, HTTPS with the IP in the SAN, a client without the CA refused, plain HTTP refused,
  401 before login, the Origin check, a wrong password, the cookie's flags, data with the session, the setting refused
  from the phone, localhost stateless, the UI files, lock from the phone ending every session and closing the
  listener, unlock opening it with the old session gone); `LanCertificatesTest`, `LanAddressesTest`, `LanLoginTest`
  (the limits with mocks), `HostOriginFilterTest`; ArchUnit; frontend `PhoneAccess.test.tsx` (7); layout: the login
  screen in both themes and the open Phone access card. Postman 14: the setting and its refusals, login refused on
  localhost, the session, a foreign Origin refused (the phone's own login needs the app's CA, so it is tested in Java).
- **Not done here:** a real phone (step 6). Loopback is accepted as a "LAN" address only with the test switch.
- **Observed:** the machine was under heavy load for part of the session (15-minute load average about 32), which made
  some runs slow; one `clean` failed while something else (the IDE's build, most likely) was writing `target/`, and
  passed when run again.


### Step 6 notes: the phone test (deferred)

- The owner deferred the phone test to the end of the phase, on Android. UI-08 stays Planned until the results; any
  fixes from it, and the tag `phase-8-done`, come after.

### Step 7 notes: close-out

- **README:** a Security section in place of the upload-only one: the threat model (SEC-07, §1), FileVault and what it
  covers, the vault (the two secrets, unlock and lock, forgot the password, lost the key file, password change),
  backups as a copy of the locked bundle, locked mode and the leak rules (moved from the setup section), phone access
  in short, the Linux LUKS route (documented, not built), the known limitations, then upload hardening (SEC-10) as
  before. The setup section's headings lose "in progress" and "draft". Phone setup lists **Android first, iPhone
  second** (the owner's phone test is on Android), in the README and in Settings → Phone access (a test checks the
  order).
- **Requirements v1.79** (§11): SEC-04 rewritten → Done; SEC-06 → Done (OS keychain dropped); SEC-07 → Done; SEC-12 →
  Done; SEC-05 → Deferred. **UI-08 is not marked Done:** it is built and tested, but the plan's step 6 (a real phone)
  hasn't happened; it is marked after the owner's Android results.
- **Roadmap v4:** Phase 8 built, closing after the phone test; Phase 9's candidates add lock before sleep and kept
  copies listed and downloadable (§13).
- **CLAUDE.md:** current phase (close-out, phone test pending, no tag yet) and the `vault` and `access` packages in the
  repo layout.
- **Not done:** the tag `phase-8-done` (after the phone test, at the owner's word).
- **Runs:** backend `clean verify` 3,447 tests, 0 failures; `-Pvault-it` 8 (the first try skipped the migration IT
  on a passing Docker check, the rerun passed with timings dump 1 s, restore 1 s, compare 0 s); frontend 312;
  `test:layout` 115 passed, 1 skipped; newman, all folders on a throwaway vault (ports 8081 and 55433), 310 requests
  and 816 assertions, 0 failed.
- **Observed:** the first newman run failed 89 assertions because the Mac went into deep idle sleep mid-run
  (`pmset -g log`: 18:33 to 18:38) and the backend's wake lock locked the vault on waking: the feature as designed.
  Rerun with `caffeinate -dims` around both processes, it passed.

---

## 16. Decisions at approval (2026-10-26)

1. **The Phase 3b PDF parsers don't resume after Phase 8.** They move, with OCR (ING-09), local PDF extraction (ING-14)
   and the vision role (AI-01), to a "Later enhancements" section at the end of the roadmap, outside the layers,
   marked aspirational. CSV and OFX/QFX are the supported formats for the POC; the Import page keeps accepting PDFs,
   and the README says PDF support is partial. CLAUDE.md's current-phase line no longer mentions 3b. Done with this
   plan's commit (roadmap v3, requirements v1.77, README, CLAUDE.md).
2. **Chat-10's dev score** is reported next to run 5 (dev, `chat-8`) and the holdout (`chat-9`), each labelled with its
   prompt.
3. **Locked mode:** one context with the lazy data source and the gate. The parent/child design (§5.1) stays the
   alternative if the gate test can't be made to cover every mapping.
4. **The Docker-on-vault test is opt-in** (`-Pvault-it`), run in steps 1, 2, 4 and 7 with its counts reported.
5. **Statement files:** keep a copy in the vault in every browser; delete the original in Chromium only; ask each time
   by default.
6. **Chat exports** meant the eval reports; the rule for future exports as proposed (§8.2).
7. **Spring Security** for login.
8. **Certificate to the phone:** the CA file with its fingerprint; no QR.
9. **Host and Origin checks** in step 5 (§9.5).
10. **Recovery key rotation:** a Phase 9 candidate.
11. **Requirements:** SEC-04, SEC-06 and SEC-07 rewritten; UI-08 Done; a new SEC-12 for the locked-mode gate and the
    leak rules (§8).
12. **Prompt wording** approved as drafted (§3.1).

Additions:

- **Step 1's spike:** if Docker's file sharing can't serve the mounted image, the first fallback is adding the mount
  point to Docker Desktop's shared folders, as a documented setup step. Native Postgres is the last resort and needs
  the owner's approval before any work on it.
- **Step 2:** the setup screen's minimum password is 12 characters; the 1,000 most common passwords are also refused,
  from a list bundled with the backend, with a test. (The same check applies to the new password in password change
  and recovery.)

**Amendment A (2026-10-26; approved 2026-10-27 with change 1 below).** Step 1's spike (§4.8) showed Postgres in Docker
can't run on the vault: on VirtioFS the mount can't be released without `-force` and the same mount point can't be used
again until Docker Desktop restarts; with gRPC FUSE the Docker VM crashed at start. The owner asked for native Postgres
with these requirements, now in §4.4 to §4.6, §10 and §12: PostgreSQL 17 and pgvector, pinned, with a startup check in
plain words; the cluster in `<mount>/pgdata`, created at setup with a superuser password kept in the vault, SCRAM,
loopback only, port 55432; unlock = mount, `pg_ctl start -w`, Flyway, schema check; lock = close the pool, `pg_ctl stop
-m fast -w`, a plain unmount, `-force` only as a logged fallback, with a five-cycle test at one mount point; Postgres
logs inside the vault with the terse flags; a refusal if a launchd job names the vault path; tests stay on
Testcontainers, the vault IT uses native Postgres on a throwaway image and skips without it; the dev migration reads the
Docker volume into the vault cluster, run by the owner; `compose.yaml` unchanged. Decision 4 now applies to the native
vault IT.

**Amendment A, change 1 (2026-10-27).** Homebrew dropped Intel Macs in September 2026 and the owner's machine is an
Intel MacBook, so the native Postgres is **Postgres.app**, its PostgreSQL 17 build (universal binaries) with bundled
pgvector **0.8.6**, binaries at `finalysis.vault.pg-bin` (default
`/Applications/Postgres.app/Contents/Versions/17/bin`). Its own server management is never used: the startup check also
refuses (`postgresapp-conflict`) if any Postgres.app server, or any launchd item, points at the vault path. pgvector is
pinned to 0.8.6, and the version messages name the installed and the required version. The rest of Amendment A stands.
Approved with this change; step 1 starts with the native spike.

