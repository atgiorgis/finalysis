#!/usr/bin/env bash
# Prints the masked layout skeleton of one PDF statement to stdout (docs/plans/phase-3b.md §6):
# every line's kind and x positions, letters masked as x and digits as 9, then a summary.
# Run it on your own statement and share the output, never the file.
#
#   ./scripts/pdf-skeleton.sh /absolute/path/to/statement.pdf [--show-layout-words]
#
# The path may be anywhere and may contain spaces. The tool reads that one file and writes no
# file of its own (Maven compiles the backend into backend/target first, as for any build).
# No Spring, no database, no network.
set -euo pipefail

usage() {
    echo "usage: ./scripts/pdf-skeleton.sh /path/to/statement.pdf [--show-layout-words]"
    exit 2
}

file=""
flag=""
for arg in "$@"; do
    case "$arg" in
        --show-layout-words)
            [[ -z "$flag" ]] || usage
            flag="$arg"
            ;;
        -*) usage ;;
        *)
            [[ -z "$file" ]] || usage
            file="$arg"
            ;;
    esac
done
[[ -n "$file" ]] || usage

# Maven runs from backend/, so a relative path is made absolute against where you ran this from.
case "$file" in
    /*) ;;
    *) file="$PWD/$file" ;;
esac
if [[ ! -f "$file" ]]; then
    echo "read: FAILED (not a file)"
    exit 1
fi

# exec:java splits exec.args like a shell command line, so the path is quoted for it.
case "$file" in
    *$'\n'*) echo "read: FAILED (the path contains a line break; rename or move the file)"; exit 1 ;;
    *\'*\"* | *\"*\'*) echo "read: FAILED (the path contains both quote characters; rename or move the file)"; exit 1 ;;
    *\'*) quoted="\"$file\"" ;;
    *) quoted="'$file'" ;;
esac

cd "$(dirname "${BASH_SOURCE[0]}")/../backend"
exec ./mvnw -q compile exec:java \
    -Dexec.mainClass=com.finalysis.ingestion.pdf.PdfSkeletonCli \
    "-Dexec.args=$quoted${flag:+ $flag}"
