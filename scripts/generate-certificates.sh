#!/usr/bin/env sh
set -eu

# Usage: ./scripts/generate-certificates.sh [server-dns-name] [server-ip]
# For LAN testing, pass the server computer's hostname and LAN address.
SERVER_NAME="${1:-localhost}"
SERVER_IP="${2:-127.0.0.1}"
STORE_PASSWORD="${SECURECHAT_STORE_PASSWORD:-changeit}"
CERT_DIR="config/certs"
KEYSTORE="$CERT_DIR/server-keystore.p12"
TRUSTSTORE="$CERT_DIR/client-truststore.p12"
CERTIFICATE="$CERT_DIR/server-certificate.pem"

mkdir -p "$CERT_DIR"

if [ -e "$KEYSTORE" ] || [ -e "$TRUSTSTORE" ] || [ -e "$CERTIFICATE" ]; then
    echo "Certificate files already exist in $CERT_DIR."
    echo "Move or delete those three generated files before creating a new identity."
    exit 1
fi

keytool -genkeypair \
    -alias securechat-server \
    -keyalg RSA \
    -keysize 3072 \
    -sigalg SHA256withRSA \
    -validity 3650 \
    -dname "CN=$SERVER_NAME, OU=SecureChat, O=University Project, C=LK" \
    -ext "SAN=dns:$SERVER_NAME,dns:localhost,ip:$SERVER_IP,ip:127.0.0.1" \
    -ext "EKU=serverAuth" \
    -storetype PKCS12 \
    -keystore "$KEYSTORE" \
    -storepass "$STORE_PASSWORD" \
    -keypass "$STORE_PASSWORD" \
    -noprompt

keytool -exportcert \
    -alias securechat-server \
    -keystore "$KEYSTORE" \
    -storepass "$STORE_PASSWORD" \
    -rfc \
    -file "$CERTIFICATE"

keytool -importcert \
    -alias securechat-server \
    -file "$CERTIFICATE" \
    -keystore "$TRUSTSTORE" \
    -storetype PKCS12 \
    -storepass "$STORE_PASSWORD" \
    -noprompt

echo "Created $KEYSTORE"
echo "Created $TRUSTSTORE"
echo "Certificate names: $SERVER_NAME and $SERVER_IP"
