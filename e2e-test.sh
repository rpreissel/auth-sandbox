#!/usr/bin/env bash
# End-to-end test for the device-login auth flow
set -euo pipefail

BASE="https://device-login.localhost:8443"
PRIVATE_KEY="$(pwd)/device-login/keys/private.pem"
DEVICE_ID="e2e-test-device-$(date +%s)"

echo "=== Step 1: Register device (ID: $DEVICE_ID) ==="
PUBLIC_KEY_PEM=$(cat device-login/keys/public.pem)
REGISTER_RESP=$(curl -sk -X POST "$BASE/api/v1/devices/register" \
  -H "Content-Type: application/json" \
  -d "{
    \"deviceId\": $(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$DEVICE_ID"),
    \"publicKey\": $(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$PUBLIC_KEY_PEM")
  }")
echo "Register response: $REGISTER_RESP"

echo ""
echo "=== Step 2: Start login (get challenge) ==="
LOGIN_START_RESP=$(curl -sk -X POST "$BASE/api/v1/auth/login/start" \
  -H "Content-Type: application/json" \
  -d "{\"deviceId\": $(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$DEVICE_ID")}")
echo "Login start response: $LOGIN_START_RESP"
NONCE=$(echo "$LOGIN_START_RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["nonce"])')
CHALLENGE=$(echo "$LOGIN_START_RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin)["challenge"])')
echo "Nonce: $NONCE"
echo "Challenge: $CHALLENGE"

echo ""
echo "=== Step 3: Sign challenge ==="
SIGNATURE=$(echo -n "$CHALLENGE" | openssl dgst -sha256 -sign "$PRIVATE_KEY" | base64 | tr -d '\n' | tr '+/' '-_' | tr -d '=')
echo "Signature (base64url, first 40 chars): ${SIGNATURE:0:40}..."

echo ""
echo "=== Step 4: Verify challenge → get OIDC tokens ==="
VERIFY_RESP=$(curl -sk -X POST "$BASE/api/v1/auth/login/verify" \
  -H "Content-Type: application/json" \
  -d "{
    \"nonce\": $(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$NONCE"),
    \"signature\": $(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$SIGNATURE")
  }")
echo "Verify response keys: $(echo "$VERIFY_RESP" | python3 -c 'import sys,json; d=json.load(sys.stdin); print(list(d.keys()))' 2>/dev/null || echo "$VERIFY_RESP")"

echo ""
echo "=== Step 5: Decode access token ==="
ACCESS_TOKEN=$(echo "$VERIFY_RESP" | python3 -c 'import sys,json; print(json.load(sys.stdin).get("access_token",""))' 2>/dev/null || true)
if [ -n "$ACCESS_TOKEN" ]; then
  echo "Access token payload:"
  echo "$ACCESS_TOKEN" | cut -d. -f2 | python3 -c '
import sys, base64, json
data = sys.stdin.read().strip()
pad = 4 - len(data) % 4
print(json.dumps(json.loads(base64.b64decode(data + "=" * pad)), indent=2))
'
else
  echo "ERROR: No access_token in response"
  echo "Full response: $VERIFY_RESP"
  exit 1
fi
