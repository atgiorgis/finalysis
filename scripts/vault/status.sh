#!/usr/bin/env bash
# Prints the state of the vault's image (docs/plans/phase-8.md §4.3).
#
#   status.sh <vault dir>
#
# One word: MOUNTED (this image at <vault dir>/mnt), ELSEWHERE (this image attached at another place), UNMOUNTED, or
# NO_IMAGE. Other images are never reported.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -eq 1 ]] || refuse "usage: status.sh <vault dir>"
dir="$(vault_dir "$1")"

if [[ ! -d "$dir/$VAULT_BUNDLE_NAME" ]]; then
    echo NO_IMAGE
    exit 0
fi
image_state "$dir"
