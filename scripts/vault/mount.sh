#!/usr/bin/env bash
# Mounts the vault's image at <vault dir>/mnt (docs/plans/phase-8.md §4.3).
#
#   mount.sh <vault dir>      the recovery key on stdin
#
# Exit 1 with "wrong key" when the image doesn't open. Nothing goes to the Keychain (no -agentpass) and the volume
# stays out of Finder (-nobrowse).
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -eq 1 ]] || refuse "usage: mount.sh <vault dir>"
dir="$(vault_dir "$1")"
bundle="$dir/$VAULT_BUNDLE_NAME"
mnt="$dir/mnt"

[[ -d "$bundle" ]] || refuse "there is no image in the vault directory"
case "$(image_state "$dir")" in
    MOUNTED) refuse "the image is already mounted" ;;
    ELSEWHERE) refuse "the image is attached somewhere else; detach it first" ;;
esac
[[ -d "$mnt" && -z "$(ls -A "$mnt")" ]] || refuse "the mount point is not an empty directory"

read_secret key
if ! printf '%s' "$key" | hdiutil attach -quiet -stdinpass -nobrowse -noautoopen -owners on -mountpoint "$mnt" \
        "$bundle" >/dev/null 2>&1; then
    key=""
    echo "mount: refused: wrong key, or the image could not be opened" >&2
    exit 1
fi
key=""

[[ "$(image_state "$dir")" == MOUNTED ]] || fail "the image did not appear at the mount point"
echo "mount: done"
