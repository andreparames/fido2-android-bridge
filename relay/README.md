# Centrifugo relay (sandboxed)

Centrifugo is the untrusted WebSocket relay between the Linux daemon and the
Android client. It only ever sees sealed AES-256-GCM envelopes (PROTOCOL.md §3),
never plaintext or the session key.

It runs in a `systemd` `RootDirectory` cage: the process is chrooted into
`/srv/centrifugo` and can read nothing but its own binary and config (plus the
API filesystems systemd mounts for it). It has no capabilities, no writable
state, and no access to the rest of the host filesystem.

> **TODO (security):** `relay/config.json` — which holds `hmac_secret_key` and
> the `http_api.key` — was tracked in git from the initial commit. It is now
> `.gitignore`d and removed from the current branch, but the secrets remain in
> the repo's history and the relay is now public (`wss://gary.andreparames.com`).
> **Do later:** (1) rotate `hmac_secret_key` + `http_api.key`, regenerate the
> connection JWT and re-bake into `pass`/clients, and (2) purge the file from
> history with `git filter-repo`.

## Files

```
relay/
  config.json          Centrifugo config (secrets, TLS, gitignored)
  centrifugo.service   systemd unit with the RootDirectory cage
  Dockerfile           Centrifugo container for Fly.io
  fly.toml             Fly.io app config
  fly-config.json      Centrifugo config with env var placeholders (no TLS)
  setup.sh             One-command Fly.io deployment
```

## Deploy to Fly.io (free tier)

Fastest path — no server to manage, TLS handled automatically:

```bash
# Prerequisites: fly auth login
cd relay
./setup.sh
```

The script generates secrets, deploys Centrifugo, and prints everything you
need to configure the daemon and Android app (relay URL, connection JWT,
session key instructions).

See also `android-fido-client/plans/plan.md` §12 and `linux-fido-daemon/plan.md` for the full client setup.

## Self-hosted setup

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

### Channels & namespace

Only the `fidobridge` channel namespace is enabled; the unnamed namespace is
fully denied, so the relay accepts exactly the topics `fidobridge:<channel_id>`
(the pairing channel, PROTOCOL.md §3.3) and `fidobridge:log:<channel_id>`
(diagnostic sink). Any other topic is rejected with `102: unknown channel`.

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
wss://<host>:8000/connection/websocket
```

and pass the JWT as the connection `token`. Plaintext `ws://` is refused —
TLS is always on (see [TLS](#tls)).

## Verify the cage

```bash
systemd-analyze security centrifugo     # aim for near-zero exposure
journalctl -u centrifugo -f             # logs still flow
curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"method":"info","params":{}}' https://<host>:8000/api
# -> 401 unauthorized (HTTP API locked)
```

## TLS

TLS is enabled via `http_server.tls` in `config.json` (Centrifugo v6 unified TLS
config): the cert and key live inside the jail at
`/etc/centrifugo/gary.andreparames.com.fullchain.pem` and
`gary.andreparames.com.privkey.pem` (0440, root:centrifugo). With TLS enabled the
HTTP server no longer serves plaintext, so the WebSocket endpoint is
`wss://<host>:8000/connection/websocket` and the HTTP API check above uses
`https`.

### 6. Issue the certificate

The certificate comes from Let's Encrypt via `certbot` using the webroot
authenticator. A host-level nginx vhost (`/etc/nginx/sites-available/gary`)
serves the ACME challenge from `/var/www/challenges` for the domain:

```bash
certbot certonly --webroot -w /var/www/challenges \
  -d gary.andreparames.com --agree-tos
```

The resulting files are at `/etc/letsencrypt/live/gary.andreparames.com/`
(`fullchain.pem`, `privkey.pem`).

### 7. Copy the cert into the jail

The cage is a `RootDirectory` jail, so the cert must be copied inside it (the
service cannot read `/etc/letsencrypt`):

```bash
install -m 0440 -o root -g centrifugo \
  /etc/letsencrypt/live/gary.andreparames.com/fullchain.pem \
  /srv/centrifugo/etc/centrifugo/gary.andreparames.com.fullchain.pem
install -m 0440 -o root -g centrifugo \
  /etc/letsencrypt/live/gary.andreparames.com/privkey.pem \
  /srv/centrifugo/etc/centrifugo/gary.andreparames.com.privkey.pem
```

`config.json` references them as `http_server.tls.cert_pem` and
`http_server.tls.key_pem`. Restart with `systemctl restart centrifugo`.

### 8. Keep it fresh

certbot renews automatically, but the jail copy would go stale. A deploy hook at
`/etc/letsencrypt/renewal-hooks/deploy/centrifugo` copies the renewed cert and
key into the jail and restarts the service after every successful renewal:

```bash
sudo tee /etc/letsencrypt/renewal-hooks/deploy/centrifugo > /dev/null <<'EOF'
#!/bin/sh
set -eu

cert=/etc/letsencrypt/live/gary.andreparames.com
jail=/srv/centrifugo/etc/centrifugo

install -m 0440 -o root -g centrifugo "$cert/fullchain.pem" "$jail/gary.andreparames.com.fullchain.pem"
install -m 0440 -o root -g centrifugo "$cert/privkey.pem" "$jail/gary.andreparames.com.privkey.pem"
systemctl restart centrifugo
EOF
sudo chmod 0755 /etc/letsencrypt/renewal-hooks/deploy/centrifugo
```

Check the live cert with:

```bash
echo | openssl s_client -connect <host>:8000 -servername gary.andreparames.com 2>/dev/null \
  | grep -E 'subject=|Verify return code'
```
