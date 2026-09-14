#!/bin/sh
# Raw-port negative exposure filter (#418 §B): the backend port answers on the
# loopback interface only. Any packet for 8765 arriving on another interface
# is dropped, so a proxy with TLS (or a misbound forwarder) can never
# silently coexist with a directly reachable raw backend.
#
# Apply after every boot/redeploy (persistent rules, not ad-hoc commands) and
# re-run the negative check from the runbook. Loopback stays first so the
# host-local forwarder path keeps working.
set -eu

PORT="${BACKEND_PORT:-8765}"

iptables -C INPUT -i lo -p tcp --dport "$PORT" -j ACCEPT 2>/dev/null \
  || iptables -A INPUT -i lo -p tcp --dport "$PORT" -j ACCEPT
iptables -C INPUT -p tcp --dport "$PORT" -j DROP 2>/dev/null \
  || iptables -A INPUT -p tcp --dport "$PORT" -j DROP

# nftables equivalent (pick one framework per host):
#   nft add rule inet filter input iifname "lo" tcp dport 8765 accept
#   nft add rule inet filter input tcp dport 8765 drop
# pf equivalent (macOS/BSD):
#   pass in on lo0 proto tcp to port 8765
#   block in proto tcp to port 8765
