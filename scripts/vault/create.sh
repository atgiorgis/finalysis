#!/usr/bin/env bash
# Creates the vault's encrypted disk image (docs/plans/phase-8.md §4.1, §4.3).
#
#   create.sh <vault dir> <max size>     the recovery key on stdin; <max size> like 50g or 64m
#
# Makes <dir>/finalysis.sparsebundle (APFS, AES-256, a sparse bundle that grows up to <max size>), mounts it once at
# <dir>/mnt to create its folders and keep Spotlight off it, and unmounts it. Refuses if an image is already there:
# it never creates a vault over an old one.
source "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

[[ $# -eq 2 ]] || refuse "usage: create.sh <vault dir> <max size>"
dir="$(vault_dir "$1")"
[[ "$2" =~ ^[1-9][0-9]{0,5}[mg]$ ]] || refuse "the size must look like 64m or 50g"
size="$2"
bundle="$dir/$VAULT_BUNDLE_NAME"
mnt="$dir/mnt"

[[ ! -e "$bundle" ]] || refuse "an image already exists in the vault directory"
if [[ -e "$mnt" ]]; then
    [[ -d "$mnt" && -z "$(ls -A "$mnt")" ]] || refuse "the mount point exists and is not an empty directory"
else
    mkdir -m 700 "$mnt"
fi

read_secret key

printf '%s' "$key" | hdiutil create -quiet -type SPARSEBUNDLE -fs APFS -size "$size" -encryption AES-256 \
    -stdinpass -volname Finalysis "$bundle" >/dev/null 2>&1 || fail "hdiutil could not create the image"

printf '%s' "$key" | hdiutil attach -quiet -stdinpass -nobrowse -noautoopen -owners on -mountpoint "$mnt" \
    "$bundle" >/dev/null 2>&1 || fail "the new image did not mount"
key=""

[[ "$(image_state "$dir")" == MOUNTED ]] || fail "the new image is not mounted at the mount point"
# Spotlight: -nobrowse already keeps the volume out of Finder; this file keeps the indexer away (mdutil needs root).
touch "$mnt/.metadata_never_index"
mkdir -m 700 "$mnt/pgdata" "$mnt/pglog" "$mnt/secrets" "$mnt/statements" "$mnt/tls"

hdiutil detach -quiet "$mnt" >/dev/null 2>&1 || hdiutil detach -quiet -force "$mnt" >/dev/null 2>&1 \
    || fail "the new image did not unmount"
echo "create: done"
