#!/usr/bin/env bash
# Unmounts the vault's image (docs/plans/phase-8.md §4.3, §4.6).
#
#   unmount.sh <vault dir>
#
# Never while a Postgres server runs on the volume (exit 4; stop it with db-stop.sh first). Tries a plain detach for
# up to 10 seconds; -force is only a fallback, reported with exit 3 and a warning. Then checks that the mount point is
# an empty directory again.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -eq 1 ]] || refuse "usage: unmount.sh <vault dir>"
dir="$(vault_dir "$1")"
mnt="$dir/mnt"

case "$(image_state "$dir")" in
    UNMOUNTED) echo "unmount: not mounted"; exit 0 ;;
    ELSEWHERE) refuse "the image is attached somewhere else" ;;
esac

if postmaster_running "$mnt/pgdata"; then
    echo "unmount: refused: a Postgres server still runs on the volume" >&2
    exit 4
fi

forced=false
for _ in 1 2 3 4 5 6 7 8 9 10; do
    if hdiutil detach -quiet "$mnt" >/dev/null 2>&1; then
        break
    fi
    sleep 1
done
if [[ "$(image_state "$dir")" != UNMOUNTED ]]; then
    echo "unmount: warning: the volume was busy for 10 seconds; forcing the unmount" >&2
    hdiutil detach -quiet -force "$mnt" >/dev/null 2>&1 || fail "the image did not unmount, even forced"
    forced=true
fi

[[ "$(image_state "$dir")" == UNMOUNTED ]] || fail "the image is still attached"
[[ -d "$mnt" && -z "$(ls -A "$mnt")" ]] || fail "the mount point is not an empty directory after unmounting"

if [[ "$forced" == true ]]; then
    echo "unmount: done (forced)"
    exit 3
fi
echo "unmount: done"
