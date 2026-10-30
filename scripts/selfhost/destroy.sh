#!/bin/bash
# Destroys the Finalysis self-hosted server (docs/plans/phase-9.md §5.3): the server's stack (instance, disk, role,
# security group), the Tailscale key parameter if it is still there, and the billing alarm stack in us-east-1 (alarm,
# topic, e-mail subscription). Run it yourself, with your own admin credentials.
#
#   scripts/selfhost/destroy.sh           asks for the region and a confirmation on stdin, then deletes
#   scripts/selfhost/destroy.sh --plan    runs only the read-only checks and prints every delete without making it
#
# Safe to run again: what is already gone is skipped. It never reads the key parameter's value, only whether the
# parameter exists. Removing the node from the tailnet is yours (the script says how).
# Needs bash 3.2 (macOS) and the AWS CLI v2; no jq.
set -euo pipefail
set +x

STACK=finalysis-selfhost
BILLING_STACK=finalysis-selfhost-billing
BILLING_REGION=us-east-1
KEY_PARAMETER=/finalysis/selfhost/tailscale-auth-key
TAILSCALE_NAME=finalysis-selfhost
REGION_PATTERN='^[a-z]{2}(-[a-z]+)+-[0-9]+$'

PLAN=0
case "${1:-}" in
    "") ;;
    --plan) PLAN=1 ;;
    -h | --help)
        sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'
        exit 0
        ;;
    *)
        echo "Unknown option: $1 (use --plan or --help)" >&2
        exit 2
        ;;
esac

die() {
    echo "destroy.sh: $*" >&2
    exit 1
}

ask() {
    printf '%s' "$1" >&2
    REPLY=""
    IFS= read -r REPLY || [ -n "$REPLY" ] || die "no answer on stdin"
    if [ -z "$REPLY" ] && [ -n "${2:-}" ]; then
        REPLY=$2
    fi
}

# A call that changes something: made, or with --plan printed and not made.
change() {
    if [ "$PLAN" = 1 ]; then
        printf '[plan] aws'
        printf ' %s' "$@"
        printf '\n'
    else
        aws "$@"
    fi
}

stack_status() {
    aws cloudformation describe-stacks --region "$1" --stack-name "$2" --query 'Stacks[0].StackStatus' \
        --output text 2>/dev/null || echo NONE
}

# Deletes a stack and waits for it, or says it is already gone.
delete_stack() {
    local region=$1 stack=$2 status
    status=$(stack_status "$region" "$stack")
    if [ "$status" = NONE ]; then
        echo "The stack $stack ($region) is already gone."
        return
    fi
    echo "Deleting the stack $stack ($region, $status)."
    if [ "$status" != DELETE_IN_PROGRESS ]; then
        change cloudformation delete-stack --region "$region" --stack-name "$stack"
    fi
    change cloudformation wait stack-delete-complete --region "$region" --stack-name "$stack"
}

command -v aws >/dev/null || die "the AWS CLI v2 is needed (https://aws.amazon.com/cli/)"

[ "$PLAN" = 1 ] && echo "Plan only: read-only checks run; nothing is deleted."

ask "AWS region of the server [us-east-1]: " us-east-1
REGION=$REPLY
[[ $REGION =~ $REGION_PATTERN ]] || die "not an AWS region id: $REGION"

if [ "$PLAN" = 0 ]; then
    echo "This deletes the server and its disk (the models on it), its role, the key parameter if left, and the" >&2
    echo "billing alarm. It can't be undone." >&2
    ask "Type destroy to go on: "
    [ "$REPLY" = destroy ] || die "not confirmed; nothing deleted"
fi

delete_stack "$REGION" "$STACK"

FOUND=$(aws ssm describe-parameters --region "$REGION" --parameter-filters "Key=Name,Values=$KEY_PARAMETER" \
    --query 'Parameters[0].Name' --output text)
if [ "$FOUND" = "$KEY_PARAMETER" ]; then
    echo "Deleting the key parameter $KEY_PARAMETER (left by a create.sh that didn't finish)."
    change ssm delete-parameter --region "$REGION" --name "$KEY_PARAMETER"
else
    echo "The key parameter is already gone."
fi

delete_stack "$BILLING_REGION" "$BILLING_STACK"

DONE="Done."
[ "$PLAN" = 0 ] || DONE="Plan done: nothing was deleted."

cat <<EOF

$DONE

Left for you:
  - Remove $TAILSCALE_NAME from the tailnet (Tailscale admin console → Machines → $TAILSCALE_NAME → Remove).
  - If no server will come back, remove the FinalysisSelfHost statements from finalysis-dev-policy.
  - In Finalysis, choose another path in Settings, Corgi.
EOF
