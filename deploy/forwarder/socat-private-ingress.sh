#!/bin/sh
# Mode 1 host-local forwarder example (#418 §C, Browser ingress contract #422):
# private ingress -> loopback backend.
#
# Listens ONLY on the explicit private overlay/LAN address and forwards to the
# loopback application backend at 127.0.0.1:8765. Remote overlay traffic never
# addresses the loopback listener directly, so this hop is mandatory. Never
# change the listen address to a wildcard bind: that would bypass the private
# ingress and is rejected by the deployment profile validator.
#
# Transport profile: this forwarder is PLAIN TCP. Encryption MUST come from the
# private network / VPN / overlay itself (WireGuard / Tailscale / equivalent).
# The matching application profile is therefore the http-over-encrypted-tunnel
# one: DEPLOYMENT_BROWSER_ORIGIN=http://<listen-addr>:<listen-port> with
# OWNER_COOKIE_SECURE=false (a Secure cookie would never travel over remote
# plain http; the validator fails that combination closed). Never point a
# Browser at this forwarder over a public or untrusted LAN. When private TLS
# termination exists, terminate there instead and use the https profile with
# OWNER_COOKIE_SECURE=true (see deploy/systemd/owner.env.example).
set -eu

LISTEN_ADDR="${FORWARDER_LISTEN_ADDR:-100.64.0.5}"
LISTEN_PORT="${FORWARDER_LISTEN_PORT:-8766}"

exec socat "TCP-LISTEN:${LISTEN_PORT},bind=${LISTEN_ADDR},reuseaddr,fork" "TCP:127.0.0.1:8765"
