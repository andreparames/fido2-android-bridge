# Centrifugo relay (sandboxed)

Centrifugo is the untrusted WebSocket relay between the Linux daemon and the
Android client. It only ever sees sealed AES-256-GCM envelopes (PROTOCOL.md §3),
never plaintext or the session key.

It runs in a `systemd` `RootDirectory` cage: the process is chrooted into
`/srv/centrifugo` and can read nothing but its own binary and config (plus the
API filesystems systemd mounts for it). It has no capabilities, no writable
state, and no access to the rest of the host filesystem.

## Files

```
relay/
  config.json          Centrifugo config (in-memory, WebSocket-only, token auth)
  centrifugo.service   systemd unit with the RootDirectory cage
```

## Setup

### 1. Generate secrets

```bash
HMAC_SECRET=$(openssl rand -hex 32)
API_KEY=$(openssl rand -hex 32)
```

Edit `config.json`:
- `client.token.hmac_secret_key` -> `$HMAC_SECRET`
- `http_api.key` -> `$API_KEY` (Centrifugo's HTTP API cannot be disabled; a
  random unused key locks it so nobody can call it)

### 2. Build the jail

Centrifugo is a static Go binary, so the jail only needs the binary, the config,
and an empty `/tmp`.

```bash
install -d -m 0755 /srv/centrifugo/bin /srv/centrifugo/etc/centrifugo /srv/centrifugo/tmp

curl -L -o /tmp/centrifugo.tar.gz https://github.com/centrifugal/centrifugo/releases/download/v6.9.6/centrifugo_6.9.6_linux_amd64.tar.gz
tar -xzf /tmp/centrifugo.tar.gz -C /tmp
install -m 0755 /tmp/centrifugo /srv/centrifugo/bin/centrifugo

install -m 0440 -o root -g centrifugo config.json /srv/centrifugo/etc/centrifugo/config.json
```

For `arm64` use `centrifugo_6.9.6_linux_arm64.tar.gz`. A `centrifugo_6.9.6_checksums.txt`
asset is published alongside for verification.

### 3. Create the service user

```bash
useradd --system --no-create-home --shell /usr/sbin/nologin centrifugo
```

### 4. Install and start

```bash
install -m 0644 centrifugo.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now centrifugo
```

### 5. Issue the connection token

Centrifugo authenticates with a connection JWT signed by `hmac_secret_key`.
`gentoken` reads the secret from the config (`-c`) and takes the TTL in seconds
(`-t`). Generate one token with a long TTL and bake the resulting JWT string into
both the daemon and the Android app at build time:

```bash
/srv/centrifugo/bin/centrifugo gentoken \
  -c /srv/centrifugo/etc/centrifugo/config.json \
  -u bridge \
  -t 3153600000
```

`3153600000` seconds is ~100 years, keeping the single shared token valid
indefinitely. Rotate it by regenerating with a new `hmac_secret_key`.

Store the resulting JWT in `pass` before baking it into the clients:

```bash
/usr/sbin/centrifugo gentoken \
  -c /srv/centrifugo/etc/centrifugo/config.json \
  -u bridge -t 3153600000 | pass insert -m fidobridge/relay-token
```

Retrieve it with `pass show fidobridge/relay-token`.

The clients connect to:

```
ws://<host>:8000/connection/websocket
```

and pass the JWT as the connection `token`.

## Verify the cage

```bash
systemd-analyze security centrifugo     # aim for near-zero exposure
journalctl -u centrifugo -f             # logs still flow
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"method":"info","params":{}}' http://<host>:8000/api
# -> 401 unauthorized (HTTP API locked)
```

## TLS (not yet enabled)

`ws://` is cleartext. For `wss://`, either add `http_server.tls` to `config.json`
(and copy the cert/key into the jail), or terminate TLS at a reverse proxy in
front of Centrifugo.
