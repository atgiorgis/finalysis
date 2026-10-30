#!/usr/bin/env bash
#
# Loads the synthetic sample data (sample-data/, as statements-manifest.csv lists it) into a running dev backend
# through its API, the way the Import tab does: the four sample accounts, then every statement in manifest order. The
# seed of QA-06 (demo mode). Docs: README "Loading the sample data"; docs/plans/phase-9.md, side-step notes.
#
#   scripts/load-sample-data.sh [--port <port>] [--categorize]
#
# Talks to http://127.0.0.1 only, on the backend's dev port (8080) or --port. When the vault is locked, the password is
# read from stdin (never an argument, an environment variable or a file) and sent in the request body only; it never
# appears in a process's arguments or in the output. The script refuses a vault that has any account already, so
# sample data never mixes with real statements; there is no way to override that.
#
# JSON is read with macOS's plutil (the app is macOS-only; plutil reads JSON key paths, arrays and nulls), so the
# script needs curl and plutil and nothing else. Written for macOS's bash 3.2: no associative arrays.

set -euo pipefail

readonly DEFAULT_PORT=8080
readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly SAMPLE="$ROOT/sample-data"
readonly MANIFEST="$SAMPLE/statements-manifest.csv"

usage() {
  cat <<'EOF'
Usage: scripts/load-sample-data.sh [--port <port>] [--categorize]

Loads the synthetic sample data into a running dev backend on http://127.0.0.1, through its API: the four sample
accounts, then each account's statements in manifest order. The first CSV import of each account saves the suggested
mapping and sign, as the Import tab offers them. Each file is reported as imported, already imported, or in the review
queue with the reason; the fixture's deliberate review cases (the broken June file) are listed as expected.

  --port <port>   the backend's port on 127.0.0.1 (default 8080). Another host is never used.
  --categorize    after the import, run categorization with Corgi (POST /api/categorization/run?ai=true) and print
                  its summary. Off by default: when Corgi's path is Cloud, this sends merchant names and amounts to
                  Amazon Bedrock (paid calls); on Self-hosted or Local it uses that server.
  -h, --help      this text

A locked vault is unlocked with the password read from stdin (typed, or piped in); an unlocked one is used as it is.
The script refuses a vault that already has any account, so it never adds sample data next to real statements; run it
again and it stops there.
EOF
}

die() {
  printf 'load-sample-data: %s\n' "$*" >&2
  exit 1
}

port=$DEFAULT_PORT
categorize=false
while (($#)); do
  case $1 in
    --port)
      (($# >= 2)) || die "--port needs a number"
      port=$2
      shift 2
      ;;
    --categorize)
      categorize=true
      shift
      ;;
    -h | --help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      die "unknown option: $1"
      ;;
  esac
done
[[ $port =~ ^[0-9]{1,5}$ ]] && ((10#$port >= 1 && 10#$port <= 65535)) || die "--port must be 1 to 65535"
readonly BASE="http://127.0.0.1:$((10#$port))"

command -v curl >/dev/null || die "curl is needed"
command -v plutil >/dev/null || die "plutil is needed (it comes with macOS)"
[[ -f $MANIFEST ]] || die "no manifest at $MANIFEST"

work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
readonly BODY="$work/body.json"

# ---------------------------------------------------------------------------- helpers

# Sends one request; the answer's body goes to $BODY, its HTTP status to stdout. Extra arguments go to curl.
api() {
  local method=$1 path=$2
  shift 2
  curl -sS --max-time 120 -o "$BODY" -w '%{http_code}' -X "$method" "$@" "$BASE$path" \
    || die "no backend answering at $BASE; start it first (cd backend && ./mvnw spring-boot:run)"
}

# A value from the last answer: json_get <key path> [raw|json]; fails when the path isn't there.
json_get() {
  plutil -extract "$1" "${2:-raw}" -o - "$BODY" 2>/dev/null
}

# The problem's detail of the last answer, or its status alone.
problem() {
  local detail
  detail=$(json_get detail) || detail=""
  printf 'HTTP %s%s' "$1" "${detail:+: $detail}"
}

# A JSON string's contents for $1: backslash, quote and control characters escaped. A shell function, so its argument
# never reaches a process's argument list.
json_escape() {
  local s=$1 out='' c code i
  for ((i = 0; i < ${#s}; i++)); do
    c=${s:i:1}
    case $c in
      '"') out+='\"' ;;
      '\') out+='\\' ;;
      *)
        printf -v code '%d' "'$c"
        if ((code >= 0 && code < 32)); then
          printf -v c '\\u%04x' "$code"
        fi
        out+=$c
        ;;
    esac
  done
  printf '%s' "$out"
}

# ---------------------------------------------------------------------------- the vault

status=$(api GET /api/status)
[[ $status == 200 ]] || die "GET /api/status: $(problem "$status")"
state=$(json_get vault.state) || die "the status has no vault state"
case $state in
  UNLOCKED)
    printf 'Vault: unlocked\n'
    ;;
  LOCKED)
    password=""
    if [[ -t 0 ]]; then
      IFS= read -rs -p 'Vault password: ' password || true
      printf '\n' >&2
    else
      IFS= read -r password || true
    fi
    [[ -n $password ]] || die "no password on stdin; the vault stays locked"
    # The body is built by printf (a shell builtin) and read by curl from stdin: the password is never an argument.
    status=$(printf '{"password":"%s"}' "$(json_escape "$password")" \
      | api POST /api/vault/unlock -H 'Content-Type: application/json' --data-binary @-)
    password=""
    [[ $status == 204 || $status == 200 ]] || die "unlock refused: $(problem "$status")"
    printf 'Vault: unlocked\n'
    ;;
  NOT_SET_UP)
    die "the vault isn't set up yet; set it up in the app first"
    ;;
  *)
    die "the vault is $state; try again when it is locked or unlocked"
    ;;
esac

# ---------------------------------------------------------------------------- an empty vault only

status=$(api GET /api/accounts)
[[ $status == 200 ]] || die "GET /api/accounts: $(problem "$status")"
if json_get 0 >/dev/null; then
  die "this vault already has accounts. The sample data is loaded only into a vault with none, so it never mixes \
with real statements; nothing was changed."
fi

# ---------------------------------------------------------------------------- the four accounts

# slug|name|institution|type|last four: the answer key's accounts (sample-data/answer-key.csv), as Postman's setup
# creates them.
readonly ACCOUNTS=(
  "northfield-checking|Northfield Bank Checking|Northfield Bank|CHECKING|1001"
  "northfield-savings|Northfield Bank Savings|Northfield Bank|SAVINGS|1002"
  "apex-visa|Apex Rewards Visa|Apex|CREDIT_CARD|2001"
  "summit-card|Summit Cash Card|Summit|CREDIT_CARD|3001"
)

# Per account, by its place in ACCOUNTS: its slug, the id the backend gave it, and whether its CSV mapping is saved.
slugs=()
ids=()
mapped=()

# The place of a slug in ACCOUNTS; fails for a slug that isn't there.
account_index() {
  local i
  for ((i = 0; i < ${#slugs[@]}; i++)); do
    [[ ${slugs[i]} == "$1" ]] && printf '%s' "$i" && return 0
  done
  return 1
}

for entry in "${ACCOUNTS[@]}"; do
  IFS='|' read -r slug name institution type last_four <<<"$entry"
  status=$(printf '{"name":"%s","institution":"%s","type":"%s","lastFour":"%s"}' \
      "$name" "$institution" "$type" "$last_four" \
    | api POST /api/accounts -H 'Content-Type: application/json' --data-binary @-)
  [[ $status == 201 ]] || die "creating $name: $(problem "$status")"
  new_id=$(json_get id) || die "creating $name: no id in the answer"
  slugs+=("$slug")
  ids+=("$new_id")
  mapped+=("no")
  printf 'Account: %s (%s, %s ending %s)\n' "$name" "$institution" "$type" "$last_four"
done

# ---------------------------------------------------------------------------- the statements, in manifest order

imported=0
already=0
review=0
failed=0

# The manifest's own deliberate review cases: listed as expected, never counted as failures.
expected_review() {
  [[ $1 == broken/* ]]
}

while IFS=, read -r file slug period_start period_end opening closing; do
  [[ $file == file ]] && continue
  [[ -n $file ]] || continue
  index=$(account_index "$slug") || die "the manifest names an unknown account: $slug"
  id=${ids[index]}
  path="$SAMPLE/$file"
  [[ -f $path ]] || die "the manifest lists $file, which isn't in sample-data/"

  # The first CSV import of an account: the suggested mapping and sign, as the Import tab offers them.
  if [[ $file == *.csv && ${mapped[index]} == no ]]; then
    status=$(api POST "/api/accounts/$id/imports/preview" -F "file=@$path")
    [[ $status == 200 ]] || die "previewing $file: $(problem "$status")"
    mapping=$(json_get suggestedMapping.mapping json) || die "previewing $file: no suggested mapping"
    sign=$(json_get suggestedMapping.mapping.sign) || sign="as suggested"
    status=$(printf '%s' "$mapping" \
      | api PUT "/api/accounts/$id/csv-mapping" -H 'Content-Type: application/json' --data-binary @-)
    [[ $status == 201 || $status == 200 ]] || die "saving the mapping for $slug: $(problem "$status")"
    mapped[index]=yes
    printf '  %s: the suggested CSV mapping saved (sign %s)\n' "$slug" "$sign"
  fi

  # The file's period, and the balances of the cards, which print none (as the tests import them).
  query="periodStart=$period_start&periodEnd=$period_end"
  if [[ $slug != northfield-* ]]; then
    query+="&openingBalance=$opening&closingBalance=$closing"
  fi
  status=$(api POST "/api/accounts/$id/imports?$query" -F "file=@$path")
  note=""
  if expected_review "$file"; then
    note=" (expected: the deliberately broken June file)"
  fi
  case $status in
    201)
      result=$(json_get status) || result=IMPORTED
      inserted=$(json_get inserted) || inserted='?'
      duplicates=$(json_get duplicates) || duplicates='?'
      check=$(json_get balanceCheck) || check='?'
      if [[ $result == NEEDS_REVIEW ]]; then
        review=$((review + 1))
        printf '  %-44s review queue: the statement needs review (balance check %s)%s\n' "$file" "$check" "$note"
      else
        imported=$((imported + 1))
        printf '  %-44s imported: %s transactions, %s duplicates, balance check %s\n' \
          "$file" "$inserted" "$duplicates" "$check"
      fi
      ;;
    202)
      review=$((review + 1))
      reason=$(json_get reason) || reason='unrecognized'
      printf '  %-44s review queue: held as an unparsed upload (%s)%s\n' "$file" "$reason" "$note"
      ;;
    409)
      if [[ $(json_get type || true) == *already-imported ]]; then
        already=$((already + 1))
        printf '  %-44s already imported%s\n' "$file" "$note"
      else
        failed=$((failed + 1))
        printf '  %-44s refused: %s\n' "$file" "$(problem "$status")"
      fi
      ;;
    *)
      if expected_review "$file"; then
        review=$((review + 1))
        printf '  %-44s review queue: %s%s\n' "$file" "$(problem "$status")" "$note"
      else
        failed=$((failed + 1))
        printf '  %-44s refused: %s\n' "$file" "$(problem "$status")"
      fi
      ;;
  esac
done <"$MANIFEST"

printf '\nFiles: %d imported, %d already imported, %d in the review queue, %d refused\n' \
  "$imported" "$already" "$review" "$failed"

# ---------------------------------------------------------------------------- categorization, on request only

if [[ $categorize == true ]]; then
  printf '\nCategorizing with Corgi on its current path (the rules first, then the AI)…\n'
  status=$(api POST '/api/categorization/run?ai=true')
  [[ $status == 200 ]] || die "categorization: $(problem "$status")"
  printf 'Categorization: AI %s; %s categorized, %s to review, %s held back, %s model calls, %s merchants indexed\n' \
    "$(json_get ai.status || printf '?')" "$(json_get summary.categorized || printf '?')" \
    "$(json_get summary.toReview || printf '?')" "$(json_get summary.heldBack || printf '?')" \
    "$(json_get summary.modelCalls || printf '?')" "$(json_get summary.merchantsIndexed || printf '?')"
fi

((failed == 0)) || exit 1
