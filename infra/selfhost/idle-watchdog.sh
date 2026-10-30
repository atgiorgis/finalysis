#!/bin/bash
# Finalysis idle watchdog (docs/plans/phase-9.md §5.1). A systemd timer runs it every minute on the self-hosted
# server. It powers the machine off after 20 minutes without a model request, and never in the first 20 minutes
# after boot. The template sets the shutdown behaviour to "stop", so poweroff stops the instance: the disk is kept
# and no IAM permission is needed.
#
# A model request is a [GIN] line in Ollama's journal for POST /api/chat, /api/generate or /api/embed. The app's
# status checks (/api/version, /api/tags, /api/ps) don't count, or the Test button would keep the server running.
# Times are journald's own (short-unix), not the [GIN] line's local time.
#
# If the journal can't be read, it logs that and does nothing, so a broken journal never powers off a busy server;
# the billing alarm is the backstop then.
#
# The template carries this file byte for byte (SelfHostTemplateTest checks they match). The WATCHDOG_* variables
# exist for that test only.
set -euo pipefail

IDLE_SECONDS=1200

now=${WATCHDOG_NOW:-$(date +%s)}
uptime_file=${WATCHDOG_UPTIME_FILE:-/proc/uptime}

up=$(cut -d ' ' -f 1 "$uptime_file")
up=${up%%.*}
if [ "$up" -lt "$IDLE_SECONDS" ]; then
    exit 0
fi

since=$((now - IDLE_SECONDS))
if ! journal=$(journalctl --unit=ollama --since="@$since" --output=short-unix --no-pager --quiet); then
    logger -t finalysis-idle-watchdog "Could not read Ollama's journal; not powering off."
    exit 1
fi

requests=$(printf '%s\n' "$journal" | awk -v since="$since" '
    { t = $1; sub(/\..*/, "", t) }
    t + 0 >= since && /\[GIN\]/ && /POST +"\/api\/(chat|generate|embed)"/ { n++ }
    END { print n + 0 }')

if [ "$requests" -gt 0 ]; then
    exit 0
fi

logger -t finalysis-idle-watchdog "No model request for 20 minutes; powering off (the instance stops)."
systemctl poweroff
