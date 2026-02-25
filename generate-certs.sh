#!/usr/bin/env bash
# Generates a local CA and a wildcard TLS certificate for *.localhost
# Run once before starting the stack: ./generate-certs.sh
# Then import certs/ca.crt as a trusted root CA in your browser/OS.
set -euo pipefail

CERTS_DIR="$(cd "$(dirname "$0")" && pwd)/certs"
mkdir -p "$CERTS_DIR"

echo "→ Generating CA key and certificate..."
openssl genrsa -out "$CERTS_DIR/ca.key" 4096
openssl req -new -x509 -days 3650 \
    -key "$CERTS_DIR/ca.key" \
    -out "$CERTS_DIR/ca.crt" \
    -subj "/CN=auth-sandbox Local CA/O=auth-sandbox/C=DE"

echo "→ Generating server key..."
openssl genrsa -out "$CERTS_DIR/server.key" 2048

EXT_CNF="$CERTS_DIR/server-ext.cnf"
cat > "$EXT_CNF" << 'EOF'
[req]
req_extensions = v3_req
distinguished_name = req_distinguished_name

[req_distinguished_name]

[v3_req]
subjectAltName = @alt_names
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth

[alt_names]
DNS.1 = localhost
DNS.2 = *.localhost
DNS.3 = keycloak.localhost
DNS.4 = auth-service.localhost
DNS.5 = app-mock.localhost
DNS.6 = admin.localhost
DNS.7 = home.localhost
DNS.8 = sso-proxy.localhost
DNS.9 = target-app.localhost
EOF

echo "→ Generating CSR and signing server certificate..."
openssl req -new \
    -key "$CERTS_DIR/server.key" \
    -out "$CERTS_DIR/server.csr" \
    -subj "/CN=*.localhost/O=auth-sandbox/C=DE" \
    -config "$EXT_CNF"

openssl x509 -req -days 825 \
    -in "$CERTS_DIR/server.csr" \
    -CA "$CERTS_DIR/ca.crt" \
    -CAkey "$CERTS_DIR/ca.key" \
    -CAcreateserial \
    -out "$CERTS_DIR/server.crt" \
    -extensions v3_req \
    -extfile "$EXT_CNF"

rm "$CERTS_DIR/server.csr" "$EXT_CNF"
chmod 644 "$CERTS_DIR/server.crt" "$CERTS_DIR/ca.crt"
chmod 600 "$CERTS_DIR/server.key" "$CERTS_DIR/ca.key"

echo ""
echo "✓ Certificates written to $CERTS_DIR/"
echo "  Import certs/ca.crt as a trusted root CA in your browser/OS."
