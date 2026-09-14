# Remote Personal Deployment artifacts (#418)
#
# Single user, single instance, single writer. The raw application backend
# always stays on the loopback listener (127.0.0.1:8765); remote traffic only
# arrives via bounded host-local forwarding. See
# docs/development/issue-418-remote-deployment-operations.md for the full
# operations contract and runbook.
#
# Layout:
#   systemd/       primary supported packaging (JAR + system service)
#   container/     container mechanism with loopback + single-instance constraints
#   forwarder/     Mode 1 host-local forwarding examples (private ingress)
#   reverse-proxy/ Mode 2 HTTPS candidate example (never SUPPORTED by #418)
#   firewall/      raw-port negative-exposure packet filter examples
#   backup/        WAL-safe backup / restore operator scripts
