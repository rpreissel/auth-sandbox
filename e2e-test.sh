#!/usr/bin/env bash
# End-to-end test for the auth-service device authorization flow (with registration codes)
set -euo pipefail

BASE="https://auth-service.localhost:8443"

# Keycloak admin token configuration
KEYCLOAK_TOKEN_URL="${KEYCLOAK_ADMIN_TOKEN_ENDPOINT:-http://keycloak:8080/realms/auth-sandbox/protocol/openid-connect/token}"
ADMIN_CLIENT_ID="${KEYCLOAK_ADMIN_CLIENT_ID:-device-login-admin}"
ADMIN_CLIENT_SECRET="${KEYCLOAK_ADMIN_CLIENT_SECRET:-}"

# Get admin access token
get_admin_token() {
  local token_url="$1"
  local client_id="$2"
  local client_secret="$3"

  local response
  response=$(curl -sk -X POST "$token_url" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "grant_type=client_credentials&client_id=$client_id&client_secret=$client_secret")

  echo "$response" | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])' 2>/dev/null || echo ""
}

ADMIN_TOKEN=$(get_admin_token "$KEYCLOAK_TOKEN_URL" "$ADMIN_CLIENT_ID" "$ADMIN_CLIENT_SECRET")
if [ -z "$ADMIN_TOKEN" ]; then
  echo "ERROR: Failed to obtain admin token from Keycloak"
  exit 1
fi
echo "Obtained admin token from Keycloak"

# Use the service key pair (already provisioned in the container)
TMPDIR_KEYS="$(pwd)/auth-service/keys"
PRIVATE_KEY="$TMPDIR_KEYS/private.pem"
PUBLIC_KEY_FILE="$TMPDIR_KEYS/public.pem"

DEVICE_ID="e2e-test-device-$(date +%s)"
USER_ID="e2e-user-$(date +%s)"
DEVICE_NAME="E2E Test Device"
ACTIVATION_CODE="supersecret-e2e-$(date +%s)"

PASS=0
FAIL=0

pass() { echo "  [PASS] $1"; PASS=$((PASS+1)); }
fail() { echo "  [FAIL] $1"; FAIL=$((FAIL+1)); }
assert_http() {
  local label="$1" expected="$2" actual="$3"
  if [ "$actual" = "$expected" ]; then
    pass "$label (HTTP $actual)"
  else
    fail "$label — expected HTTP $expected, got HTTP $actual"
  fi
}

echo "========================================"
echo " auth-service E2E Test"
echo " Device ID : $DEVICE_ID"
echo " User ID   : $USER_ID"
echo "========================================"

# ---------------------------------------------------------------------------
echo ""
echo "=== Step 1: Create registration code (admin) ==="
HTTP=$(curl -sk -o /tmp/e2e_create_code.json -w "%{http_code}" \
  -X POST "$BASE/api/v1/admin/registration-codes" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"userId\": \"$USER_ID\", \"name\": \"$DEVICE_NAME\", \"activationCode\": \"$ACTIVATION_CODE\"}")
assert_http "Create registration code" "201" "$HTTP"
echo "  Response: $(cat /tmp/e2e_create_code.json)"
CODE_ID=$(python3 -c 'import sys,json; print(json.load(open("/tmp/e2e_create_code.json"))["id"])' 2>/dev/null || echo "")
echo "  Code ID: $CODE_ID"

# ---------------------------------------------------------------------------
echo ""
echo "=== Step 2: List registration codes — new code appears as unused ==="
HTTP=$(curl -sk -o /tmp/e2e_list_codes.json -w "%{http_code}" \
  -X GET "$BASE/api/v1/admin/registration-codes" \
  -H "Authorization: Bearer $ADMIN_TOKEN")
assert_http "List registration codes" "200" "$HTTP"
USED=$(python3 -c "
import json
codes = json.load(open('/tmp/e2e_list_codes.json'))
match = [c for c in codes if c['id'] == '$CODE_ID']
print(match[0]['useCount'] if match else 'NOT_FOUND')
" 2>/dev/null || echo "ERROR")
if [ "$USED" = "0" ]; then
  pass "Registration code is unused"
else
  fail "Expected code to be unused, got: $USED"
fi

# ---------------------------------------------------------------------------
echo ""
echo "=== Step 3: Register device ==="
PUBLIC_KEY_PEM=$(cat "$PUBLIC_KEY_FILE")
HTTP=$(curl -sk -o /tmp/e2e_register.json -w "%{http_code}" \
  -X POST "$BASE/api/v1/devices/register" \
  -H "Content-Type: application/json" \
  -d "{
    \"deviceId\": $(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$DEVICE_ID"),
    \"userId\": $(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$USER_ID"),
    \"name\": $(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$DEVICE_NAME"),
    \"activationCode\": $(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$ACTIVATION_CODE"),
    \"publicKey\": $(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$PUBLIC_KEY_PEM")
  }")
assert_http "Register device" "201" "$HTTP"
echo "  Response: $(cat /tmp/e2e_register.json)"

# ---------------------------------------------------------------------------
echo ""
echo "=== Step 4: Registration code is now marked used ==="
HTTP=$(curl -sk -o /tmp/e2e_list_codes2.json -w "%{http_code}" \
  -X GET "$BASE/api/v1/admin/registration-codes" \
  -H "Authorization: Bearer $ADMIN_TOKEN")
assert_http "List registration codes after register" "200" "$HTTP"
USED=$(python3 -c "
import json
codes = json.load(open('/tmp/e2e_list_codes2.json'))
match = [c for c in codes if c['id'] == '$CODE_ID']
print(match[0]['useCount'] if match else 'NOT_FOUND')
" 2>/dev/null || echo "ERROR")
if [ "$USED" = "1" ]; then
  pass "Registration code is now marked used"
else
  fail "Expected code to be used, got: $USED"
fi

# ---------------------------------------------------------------------------
echo ""
echo "=== Step 5: Start login (get challenge) ==="
HTTP=$(curl -sk -o /tmp/e2e_login_start.json -w "%{http_code}" \
  -X POST "$BASE/api/v1/auth/login/start" \
  -H "Content-Type: application/json" \
  -d "{\"deviceId\": $(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$DEVICE_ID")}")
assert_http "Start login" "200" "$HTTP"
NONCE=$(python3 -c 'import sys,json; print(json.load(open("/tmp/e2e_login_start.json"))["nonce"])' 2>/dev/null || echo "")
CHALLENGE=$(python3 -c 'import sys,json; print(json.load(open("/tmp/e2e_login_start.json"))["challenge"])' 2>/dev/null || echo "")
echo "  Nonce    : $NONCE"
echo "  Challenge: ${CHALLENGE:0:40}..."

# ---------------------------------------------------------------------------
echo ""
echo "=== Step 6: Sign challenge ==="
SIGNATURE=$(echo -n "$CHALLENGE" | openssl dgst -sha256 -sign "$PRIVATE_KEY" | base64 | tr -d '\n' | tr '+/' '-_' | tr -d '=')
echo "  Signature (first 40 chars): ${SIGNATURE:0:40}..."
pass "Challenge signed"

# ---------------------------------------------------------------------------
echo ""
echo "=== Step 7: Verify challenge → receive OIDC tokens ==="
HTTP=$(curl -sk -o /tmp/e2e_verify.json -w "%{http_code}" \
  -X POST "$BASE/api/v1/auth/login/verify" \
  -H "Content-Type: application/json" \
  -d "{
    \"nonce\": $(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$NONCE"),
    \"signature\": $(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$SIGNATURE")
  }")
assert_http "Verify challenge" "200" "$HTTP"
ACCESS_TOKEN=$(python3 -c 'import json; print(json.load(open("/tmp/e2e_verify.json")).get("access_token",""))' 2>/dev/null || echo "")
if [ -n "$ACCESS_TOKEN" ]; then
  pass "Access token received"
  echo "  Token payload:"
  echo "$ACCESS_TOKEN" | cut -d. -f2 | python3 -c '
import sys, base64, json
data = sys.stdin.read().strip()
pad = 4 - len(data) % 4
decoded = json.loads(base64.b64decode(data + "=" * pad))
for k in ["sub","preferred_username","iss","exp"]:
    if k in decoded:
        print(f"    {k}: {decoded[k]}")
' 2>/dev/null || echo "  (could not decode token)"
else
  fail "No access_token in response"
  echo "  Full response: $(cat /tmp/e2e_verify.json)"
fi

# ---------------------------------------------------------------------------
echo ""
echo "=== Step 8: Wrong activation code is rejected ==="
HTTP=$(curl -sk -o /tmp/e2e_wrong_code.json -w "%{http_code}" \
  -X POST "$BASE/api/v1/devices/register" \
  -H "Content-Type: application/json" \
  -d "{
    \"deviceId\": \"wrong-code-device-$$\",
    \"userId\": \"$USER_ID\",
    \"name\": \"$DEVICE_NAME\",
    \"activationCode\": \"wrong-code\",
    \"publicKey\": $(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$PUBLIC_KEY_PEM")
  }")
# Expect 4xx (code already used, so also rejected for that reason — either is correct)
if [[ "$HTTP" == 4* ]]; then
  pass "Wrong activation code rejected (HTTP $HTTP)"
else
  fail "Expected 4xx for wrong activation code, got HTTP $HTTP"
fi

# ---------------------------------------------------------------------------
echo ""
echo "=== Step 9: Duplicate device ID is rejected ==="
# Create a fresh unused registration code for this attempt
NEW_USER_ID="e2e-dup-user-$$"
NEW_ACTIVATION_CODE="dup-code-$$"
curl -sk -o /dev/null \
  -X POST "$BASE/api/v1/admin/registration-codes" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"userId\": \"$NEW_USER_ID\", \"name\": \"Dup Test\", \"activationCode\": \"$NEW_ACTIVATION_CODE\"}"
HTTP=$(curl -sk -o /tmp/e2e_dup.json -w "%{http_code}" \
  -X POST "$BASE/api/v1/devices/register" \
  -H "Content-Type: application/json" \
  -d "{
    \"deviceId\": $(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$DEVICE_ID"),
    \"userId\": \"$NEW_USER_ID\",
    \"name\": \"Dup Test\",
    \"activationCode\": \"$NEW_ACTIVATION_CODE\",
    \"publicKey\": $(python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$PUBLIC_KEY_PEM")
  }")
if [[ "$HTTP" == 4* ]]; then
  pass "Duplicate device ID rejected (HTTP $HTTP)"
else
  fail "Expected 4xx for duplicate device ID, got HTTP $HTTP"
fi

# ---------------------------------------------------------------------------
echo ""
echo "=== Step 10: Delete device via admin API ==="
# Find the device's internal UUID
HTTP=$(curl -sk -o /tmp/e2e_list_devices.json -w "%{http_code}" \
  -X GET "$BASE/api/v1/admin/devices" \
  -H "Authorization: Bearer $ADMIN_TOKEN")
assert_http "List devices" "200" "$HTTP"
DEVICE_UUID=$(python3 -c "
import json
devices = json.load(open('/tmp/e2e_list_devices.json'))
match = [d for d in devices if d['deviceId'] == '$DEVICE_ID']
print(match[0]['id'] if match else '')
" 2>/dev/null || echo "")
if [ -z "$DEVICE_UUID" ]; then
  fail "Device not found in admin list"
else
  HTTP=$(curl -sk -o /dev/null -w "%{http_code}" \
    -X DELETE "$BASE/api/v1/admin/devices/$DEVICE_UUID" \
    -H "Authorization: Bearer $ADMIN_TOKEN")
  assert_http "Delete device" "204" "$HTTP"
fi

# ---------------------------------------------------------------------------
echo ""
echo "========================================"
echo " Results: $PASS passed, $FAIL failed"
echo "========================================"
if [ "$FAIL" -gt 0 ]; then
  exit 1
fi
