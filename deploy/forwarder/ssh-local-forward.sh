#!/bin/sh
# Mode 1 alternative without a persistent listener (#418 §C): SSH local
# forwarding from the remote device straight to the loopback backend.
#
# The remote device dials its own loopback port and SSH carries the bytes to
# 127.0.0.1:8765 on the host. Nothing new listens on the host, so there is no
# extra listen scope to audit. SSH admission/encryption is network transport
# only: the application owner session (#417) still authenticates every call.
set -eu

LOCAL_PORT="${SSH_FORWARD_LOCAL_PORT:-8766}"
REMOTE_USER="${SSH_REMOTE_USER:?set SSH_REMOTE_USER to the host login user}"
REMOTE_HOST="${SSH_REMOTE_HOST:?set SSH_REMOTE_HOST to the host private address}"

exec ssh -N -T -o ExitOnForwardFailure=yes \
  -L "${LOCAL_PORT}:127.0.0.1:8765" "${REMOTE_USER}@${REMOTE_HOST}"
