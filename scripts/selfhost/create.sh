#!/bin/bash
# Creates the Finalysis self-hosted server (docs/plans/phase-9.md §5.3): the billing alarm stack in us-east-1, then the
# server's stack in the region you choose, from infra/selfhost/. Run it yourself, with your own admin credentials
# (the AWS CLI's usual profile or environment); the app's finalysis-dev identity can't create anything.
#
#   scripts/selfhost/create.sh           asks its questions on stdin, then creates
#   scripts/selfhost/create.sh --plan    runs only the read-only checks and prints every call that would change
#                                        something, without making it (and doesn't ask for the key)
#
# The Tailscale auth key is read from stdin with no echo, stored as an SSM SecureString through --cli-input-json
# from a mode-600 file in a private temp folder (never a command-line argument, so never in the process list or shell
# history; the folder is deleted right after the call and on any exit), read once by the server's first boot, and
# deleted here when the stack is done, or if anything fails.
#
# Safe to run again: an existing, complete server stack or billing stack is left as it is (no key asked for an existing
# server, no threshold or e-mail for an existing billing stack). A stack in a failed state needs destroy.sh first.
# Needs bash 3.2 (macOS) and the AWS CLI v2; no jq.
set -euo pipefail
set +x

STACK=finalysis-selfhost
BILLING_STACK=finalysis-selfhost-billing
BILLING_REGION=us-east-1
KEY_PARAMETER=/finalysis/selfhost/tailscale-auth-key
TAILSCALE_NAME=finalysis-selfhost
PLACEHOLDER_MODEL=finalysis-unset-chat-model
# "Running On-Demand G and VT instances", in vCPUs; a g6.xlarge or g5.xlarge needs 4.
QUOTA_CODE=L-DB2E81BA
QUOTA_NAME="Running On-Demand G and VT instances"
NEEDED_VCPUS=4
# The template's ImageId default; SelfHostScriptsTest checks they match.
AMI_PARAMETER=/aws/service/deeplearning/ami/x86_64/base-oss-nvidia-driver-gpu-ubuntu-24.04/latest/ami-id

INFRA=$(cd "$(dirname "$0")/../../infra/selfhost" && pwd) || { echo "create.sh: infra/selfhost not found" >&2; exit 1; }
TEMPLATE="$INFRA/finalysis-selfhost.yaml"
BILLING_TEMPLATE="$INFRA/finalysis-selfhost-billing.yaml"

REGION_PATTERN='^[a-z]{2}(-[a-z]+)+-[0-9]+$'
MODEL_PATTERN='^[A-Za-z0-9][A-Za-z0-9._:/-]{0,199}$'
EMAIL_PATTERN='^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$'
KEY_PATTERN='^tskey-[A-Za-z0-9-]+$'

PLAN=0
case "${1:-}" in
    "") ;;
    --plan) PLAN=1 ;;
    -h | --help)
        sed -n '2,17p' "$0" | sed 's/^# \{0,1\}//'
        exit 0
        ;;
    *)
        echo "Unknown option: $1 (use --plan or --help)" >&2
        exit 2
        ;;
esac

die() {
    echo "create.sh: $*" >&2
    exit 1
}

# Asks one question on stdin; $2 is the default for an empty answer.
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

command -v aws >/dev/null || die "the AWS CLI v2 is needed (https://aws.amazon.com/cli/)"
[ -f "$TEMPLATE" ] && [ -f "$BILLING_TEMPLATE" ] || die "templates not found under infra/selfhost"

[ "$PLAN" = 1 ] && echo "Plan only: read-only checks run; nothing is created, and no key is asked for."

ask "AWS region for the server [us-east-1]: " us-east-1
REGION=$REPLY
[[ $REGION =~ $REGION_PATTERN ]] || die "not an AWS region id: $REGION"

ACCOUNT=$(aws sts get-caller-identity --query Account --output text)
echo "Account $ACCOUNT, region $REGION."

STATUS=$(aws cloudformation describe-stacks --region "$REGION" --stack-name "$STACK" \
    --query 'Stacks[0].StackStatus' --output text 2>/dev/null) || STATUS=NONE
case "$STATUS" in
    NONE) NEW=1 ;;
    CREATE_COMPLETE | UPDATE_COMPLETE | UPDATE_ROLLBACK_COMPLETE)
        NEW=0
        echo "The stack $STACK already exists ($STATUS); it is left as it is. To rebuild it, run destroy.sh first."
        ;;
    *_IN_PROGRESS) die "the stack $STACK is busy ($STATUS); wait for it and run this again" ;;
    *) die "the stack $STACK is in state $STATUS; run scripts/selfhost/destroy.sh first" ;;
esac

if [ "$NEW" = 1 ]; then
    ask "Instance type, g6.xlarge or g5.xlarge [g6.xlarge]: " g6.xlarge
    INSTANCE_TYPE=$REPLY
    case "$INSTANCE_TYPE" in
        g6.xlarge | g5.xlarge) ;;
        *) die "the instance type must be g6.xlarge or g5.xlarge" ;;
    esac

    ask "Ollama tag of the chat model (as in finalysis.ai.ollama.models.chat): "
    CHAT_MODEL=$REPLY
    [ "$CHAT_MODEL" != "$PLACEHOLDER_MODEL" ] || die "$PLACEHOLDER_MODEL is the placeholder; give the model's Ollama tag"
    [[ $CHAT_MODEL =~ $MODEL_PATTERN ]] || die "not an Ollama tag: $CHAT_MODEL"

    echo "GPU quota: $QUOTA_NAME ($QUOTA_CODE) in $REGION."
    QUOTA=$(aws service-quotas get-service-quota --region "$REGION" --service-code ec2 --quota-code "$QUOTA_CODE" \
        --query Quota.Value --output text 2>/dev/null) \
        || QUOTA=$(aws service-quotas get-aws-default-service-quota --region "$REGION" --service-code ec2 \
            --quota-code "$QUOTA_CODE" --query Quota.Value --output text)
    echo "  quota: ${QUOTA} vCPUs (needs $NEEDED_VCPUS)"
    [[ ${QUOTA%%.*} =~ ^[0-9]+$ ]] || die "couldn't read the quota ($QUOTA)"
    if [ "${QUOTA%%.*}" -lt "$NEEDED_VCPUS" ]; then
        echo "The quota is too low for one $INSTANCE_TYPE. Ask for at least $NEEDED_VCPUS vCPUs (approval can take days):" >&2
        echo "  https://$REGION.console.aws.amazon.com/servicequotas/home/services/ec2/quotas/$QUOTA_CODE" >&2
        die "GPU quota too low"
    fi

    echo "Image: $AMI_PARAMETER"
    AMI=$(aws ssm get-parameter --region "$REGION" --name "$AMI_PARAMETER" --query Parameter.Value --output text) \
        || die "the image parameter doesn't resolve in $REGION"
    echo "  resolves to $AMI"

    ZONE=$(aws ec2 describe-instance-type-offerings --region "$REGION" --location-type availability-zone \
        --filters "Name=instance-type,Values=$INSTANCE_TYPE" --query 'InstanceTypeOfferings[].Location' \
        --output text | tr '\t' '\n' | sed '/^$/d' | sort | head -n 1)
    [ -n "$ZONE" ] && [ "$ZONE" != None ] || die "no zone in $REGION offers $INSTANCE_TYPE"
    echo "Zone: $ZONE"
fi

BILLING_STATUS=$(aws cloudformation describe-stacks --region "$BILLING_REGION" --stack-name "$BILLING_STACK" \
    --query 'Stacks[0].StackStatus' --output text 2>/dev/null) || BILLING_STATUS=NONE
case "$BILLING_STATUS" in
    NONE) BILLING_NEW=1 ;;
    CREATE_COMPLETE | UPDATE_COMPLETE | UPDATE_ROLLBACK_COMPLETE)
        BILLING_NEW=0
        echo "The billing alarm stack $BILLING_STACK already exists in $BILLING_REGION ($BILLING_STATUS); it is left as"
        echo "it is. To change its threshold or e-mail, delete that stack and run this again."
        ;;
    *_IN_PROGRESS) die "the stack $BILLING_STACK is busy ($BILLING_STATUS); wait for it and run this again" ;;
    *) die "the stack $BILLING_STACK is in state $BILLING_STATUS; run scripts/selfhost/destroy.sh first" ;;
esac

ALARM_EMAIL=""
if [ "$BILLING_NEW" = 1 ]; then
    ask "Billing alarm threshold, whole US dollars this month [50]: " 50
    ALARM_USD=$REPLY
    [[ $ALARM_USD =~ ^[1-9][0-9]{0,5}$ ]] || die "the threshold must be a whole number of dollars, at least 1"
    ask "E-mail for the billing alarm: "
    ALARM_EMAIL=$REPLY
    [[ $ALARM_EMAIL =~ $EMAIL_PATTERN ]] || die "not an e-mail address: $ALARM_EMAIL"
fi

KEY=""
if [ "$NEW" = 1 ] && [ "$PLAN" = 0 ]; then
    printf 'Tailscale auth key (one-off, pre-approved, tagged; not shown): ' >&2
    IFS= read -rs KEY || [ -n "$KEY" ] || die "no key on stdin"
    printf '\n' >&2
    [[ $KEY =~ $KEY_PATTERN ]] || die "that doesn't look like a Tailscale auth key (tskey-...)"
fi

# The key parameter is deleted on any exit once it is stored, so a failed run never leaves it behind; so is the
# private temp folder that holds the put-parameter request.
KEY_STORED=0
KEY_DIR=""
delete_key() {
    if [ "$KEY_STORED" = 1 ]; then
        if aws ssm delete-parameter --region "$REGION" --name "$KEY_PARAMETER" >/dev/null; then
            echo "Deleted the key parameter $KEY_PARAMETER."
        else
            echo "WARNING: could not delete $KEY_PARAMETER in $REGION; run destroy.sh, which deletes it." >&2
        fi
        KEY_STORED=0
    fi
}
delete_key_file() {
    if [ -n "$KEY_DIR" ]; then
        rm -rf "$KEY_DIR"
        KEY_DIR=""
    fi
}
trap 'delete_key_file; delete_key' EXIT

if [ "$BILLING_NEW" = 1 ]; then
    echo "Billing alarm stack $BILLING_STACK in $BILLING_REGION (billing metrics live only there)."
    change cloudformation deploy --region "$BILLING_REGION" --stack-name "$BILLING_STACK" \
        --template-file "$BILLING_TEMPLATE" --no-fail-on-empty-changeset \
        --parameter-overrides "BillingAlarmUsd=$ALARM_USD" "AlarmEmail=$ALARM_EMAIL"
fi

if [ "$NEW" = 1 ]; then
    if [ "$PLAN" = 1 ]; then
        echo "[plan] aws ssm put-parameter --region $REGION --cli-input-json file://<a mode-600 temp file>" \
            "(contents: {\"Name\":\"$KEY_PARAMETER\",\"Type\":\"SecureString\",\"Value\":<the key>,\"Overwrite\":true})"
    else
        # The AWS CLI (2.38) can't read --cli-input-json from a pipe (file:///dev/stdin, /dev/fd/N: "Invalid JSON
        # received"), so the request goes through a regular file, readable only by you, in a folder only you can open,
        # deleted right after the call and on any exit. printf is a shell builtin: the key goes from this shell's memory
        # into the file, never into an argument; only the file's path is.
        KEY_DIR=$(umask 077 && mktemp -d "${TMPDIR:-/tmp}/finalysis-key.XXXXXX")
        KEY_FILE="$KEY_DIR/put-parameter.json"
        (
            umask 077
            printf '{"Name":"%s","Type":"SecureString","Value":"%s","Overwrite":true,"Description":"%s"}' \
                "$KEY_PARAMETER" "$KEY" "Finalysis: Tailscale key for the first boot; create.sh deletes it" \
                >"$KEY_FILE"
        )
        KEY=""
        KEY_STORED=1
        aws ssm put-parameter --region "$REGION" --cli-input-json "file://$KEY_FILE" >/dev/null
        delete_key_file
        echo "Stored the key as a SecureString ($KEY_PARAMETER) for the first boot."
    fi

    echo "Server stack $STACK in $REGION. The first boot installs Ollama and Tailscale and pulls the models; the"
    echo "stack completes only when all of that worked (up to 30 minutes)."
    change cloudformation deploy --region "$REGION" --stack-name "$STACK" --template-file "$TEMPLATE" \
        --capabilities CAPABILITY_IAM --no-fail-on-empty-changeset \
        --parameter-overrides "InstanceType=$INSTANCE_TYPE" "ChatModel=$CHAT_MODEL" "AvailabilityZone=$ZONE" \
        "TailscaleKeyParameter=$KEY_PARAMETER"
    if [ "$PLAN" = 1 ]; then
        echo "[plan] aws ssm delete-parameter --region $REGION --name $KEY_PARAMETER"
    else
        delete_key
    fi
fi

if [ "$PLAN" = 1 ]; then
    INSTANCE="<the instance id, once created>"
else
    INSTANCE=$(aws cloudformation describe-stacks --region "$REGION" --stack-name "$STACK" \
        --query "Stacks[0].Outputs[?OutputKey=='InstanceId'].OutputValue" --output text)
fi

DONE="Done."
ALARM_TO=${ALARM_EMAIL:-"the alarm's address"}
[ "$PLAN" = 0 ] || DONE="Plan done: nothing was created."

cat <<EOF

$DONE

Tailscale name: $TAILSCALE_NAME
Instance id:    $INSTANCE
Region:         $REGION

In Finalysis: Settings, Corgi, Self-hosted: Tailscale name $TAILSCALE_NAME, region $REGION.

Add these statements to the customer-managed policy finalysis-dev-policy (attached to finalysis-dev). Start and
Stop only for instances tagged finalysis:role=selfhost; DescribeInstances can't be limited to a resource or tag
(EC2 has no resource-level permissions for Describe calls), so it is on "*", read-only. No RunInstances, no
TerminateInstances: creating and destroying stay these scripts'.

    {
      "Sid": "FinalysisSelfHostStartStop",
      "Effect": "Allow",
      "Action": ["ec2:StartInstances", "ec2:StopInstances"],
      "Resource": "arn:aws:ec2:$REGION:$ACCOUNT:instance/*",
      "Condition": {"StringEquals": {"aws:ResourceTag/finalysis:role": "selfhost"}}
    },
    {
      "Sid": "FinalysisSelfHostDescribe",
      "Effect": "Allow",
      "Action": "ec2:DescribeInstances",
      "Resource": "*"
    }

Manual steps (no script does these):
  - Turn on Receive Billing Alerts (Billing → Billing preferences); billing metrics live in us-east-1, so the
    alarm is there.
  - Confirm the SNS subscription: AWS e-mails $ALARM_TO a link; the alarm reaches no one until
    it is used.
  - In Tailscale: an ACL that lets only your devices reach $TAILSCALE_NAME on port 11434.
EOF
