# Secure NIO Chat

A learning project for building a multi-client Java chat application with TCP,
UDP, Java NIO, multithreading, TLS, and network addressing.

## Current status

The JavaFX client supports login, connected-user search, public/private
messages, logout, and live presence. The NIO server uses TLS-protected TCP for
reliable chat traffic and authenticated UDP heartbeats for presence.

## TLS security

- The server performs non-blocking TLS with one `SSLEngine` per NIO client.
- The JavaFX client uses `SSLSocket`, validates the certificate chain, and
  verifies that the certificate name matches the address entered at login.
- TLS 1.3 and TLS 1.2 are enabled; the JVM negotiates the strongest shared
  protocol and cipher suite.
- The heartbeat session secret is issued only after the TLS connection is
  established.

For local development, generate a self-signed server identity and the matching
client trust store:

```bash
./scripts/generate-certificates.sh localhost 127.0.0.1
```

On Windows:

```bat
scripts\generate-certificates.bat localhost 127.0.0.1
```

The generated private key is local development material and must not be
committed or used as a production certificate. The default store password is
`changeit`; it can be replaced through `SECURECHAT_STORE_PASSWORD`, with the
same value then placed in the client and server property files.

## Presence flow

1. A successful TCP login returns a random heartbeat session ID and secret.
2. The client sends a signed UDP heartbeat every five seconds.
3. The server verifies the HMAC-SHA256 signature and increasing sequence number.
4. The server publishes presence changes to all clients over TCP.

The GUI displays `Online`, `Away`, `Unreachable`, or `Offline`. The default
timeouts are 15 seconds for Away and 30 seconds for Unreachable.

## Requirements

- JDK 21 or newer
- Maven 3.9 or newer

## Verify the project

```bash
mvn test
```

## Run the application

Generate the certificates once, then start `NioRegistrationServerMain` from
the IDE and leave it running.

Start each GUI client in a separate terminal:

```bash
mvn javafx:run
```

Use `localhost`, TCP port `5000`, and a different username in each window. The
server also listens on UDP port `5001`, as configured in
`config/server.properties`.

You can test multiple clients on one computer by opening multiple GUI processes;
separate computers are optional. When using separate computers, enter the
server computer's LAN IP address and allow both TCP `5000` and UDP `5001`
through its firewall. Generate the certificate with that same hostname and IP,
for example:

```bash
./scripts/generate-certificates.sh chat-server 192.168.1.20
```

Copy only `client-truststore.p12` to each client computer and update its
`config/client.properties`. Never copy the server keystore to clients.
