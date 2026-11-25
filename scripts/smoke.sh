#!/usr/bin/env bash
# An end-to-end check of the running system, through the gateway only, the way a
# client sees it: sign up two users, open wallets, deposit, transfer, and check
# the balances and the emails. Used by CI after `docker compose --profile app up`.
#
#   ./scripts/smoke.sh [gateway-url] [mailpit-url]
set -euo pipefail

GW=${1:-http://localhost:8080}
MAIL=${2:-http://localhost:8025}
PASSWORD="correct horse battery staple"
RUN=$(date +%s)-$RANDOM

json() { python3 -c "import sys, json; print(json.load(sys.stdin)$1)"; }
fail() { echo "SMOKE FAILED: $*" >&2; exit 1; }

user() {
  curl -sf -X POST "$GW/v1/auth/register" -H 'Content-Type: application/json' \
    -d "{\"email\": \"$1\", \"password\": \"$PASSWORD\"}" > /dev/null
  curl -sf -X POST "$GW/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\": \"$1\", \"password\": \"$PASSWORD\"}" | json '["access_token"]'
}

wallet() {
  curl -sf -X POST "$GW/v1/wallets" -H "Authorization: Bearer $1" -H 'Content-Type: application/json' \
    -d '{"currency": "USD"}' | json '["id"]'
}

balance() {
  curl -sf "$GW/v1/wallets/$2/balance" -H "Authorization: Bearer $1" | json '["balance"]["amount"]'
}

ALICE_EMAIL="alice-$RUN@example.com"
BOB_EMAIL="bob-$RUN@example.com"
echo "1. two users sign up and log in"
ALICE=$(user "$ALICE_EMAIL")
BOB=$(user "$BOB_EMAIL")

echo "2. each opens a USD wallet"
ALICE_WALLET=$(wallet "$ALICE")
BOB_WALLET=$(wallet "$BOB")

echo "3. alice deposits 10.50"
curl -sf -X POST "$GW/v1/wallets/$ALICE_WALLET/deposits" -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-deposit-$RUN" \
  -d '{"amount": 1050, "currency": "USD"}' > /dev/null

echo "4. alice sends bob 3.00"
TRANSFER=$(curl -sf -X POST "$GW/v1/transfers" -H "Authorization: Bearer $ALICE" \
  -H 'Content-Type: application/json' -H "Idempotency-Key: smoke-transfer-$RUN" \
  -d "{\"from\": \"$ALICE_WALLET\", \"to\": \"$BOB_WALLET\", \"amount\": 300, \"currency\": \"USD\"}" | json '["id"]')

echo "5. the saga completes"
for _ in $(seq 1 30); do
  STATE=$(curl -sf "$GW/v1/transfers/$TRANSFER" -H "Authorization: Bearer $ALICE" | json '["state"]')
  [ "$STATE" = completed ] && break
  sleep 1
done
[ "$STATE" = completed ] || fail "transfer is $STATE, not completed"

echo "6. the balances are right"
[ "$(balance "$ALICE" "$ALICE_WALLET")" = 750 ] || fail "alice's balance is wrong"
[ "$(balance "$BOB" "$BOB_WALLET")" = 300 ] || fail "bob's balance is wrong"

echo "7. three emails arrive"
for _ in $(seq 1 30); do
  COUNT=$(curl -sf "$MAIL/api/v1/messages?limit=500" | python3 -c "
import sys, json
mine = ['$ALICE_EMAIL', '$BOB_EMAIL']
print(sum(1 for m in json.load(sys.stdin)['messages'] if m['To'][0]['Address'] in mine))")
  [ "$COUNT" -ge 3 ] && break
  sleep 1
done
[ "$COUNT" -ge 3 ] || fail "only $COUNT of 3 emails arrived"

echo "8. no route reaches an internal endpoint"
STATUS=$(curl -s -o /dev/null -w '%{http_code}' "$GW/internal/wallets/$ALICE_WALLET" -H "Authorization: Bearer $ALICE")
[ "$STATUS" = 404 ] || fail "/internal answered $STATUS through the gateway"

echo "SMOKE PASSED"
