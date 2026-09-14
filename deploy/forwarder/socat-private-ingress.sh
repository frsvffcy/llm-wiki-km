#!/bin/sh
# Mode 1 host-local forwarder example (#418 §C): private ingress -> loopback backend.
#
# Listens ONLY on the explicit private overlay/LAN address and forwards to the
# loopback application backend at 127.0.0.1:8765. Remote overlay traffic never
# addresses the loopback listener directly, so this hop is mandatory. Never
# change the listen address to a wildcard bind: that would bypass the private
# ingress and is rejected by the deployment profile validator.
set -eu

LISTEN_ADDR="${FORWARDER_LISTEN_ADDR:-100.64.0.5}"
LISTEN_PORT="${FORWARDER_LISTEN_PORT:-8766}"

exec socat "TCP-LISTEN:${LISTEN_PORT},bind=${LISTEN_ADDR},reuseaddr,fork" "TCP:127.0.0.1:8765"
