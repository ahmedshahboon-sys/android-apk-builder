# Shahboun Router — E5785Lh-22c Edition

Clean rebuild targeting Huawei E5785Lh-22c / HiLink only.

Current diagnostic build: `v1.6.0` (`versionCode 7`).

## Architecture
- `HilinkSessionManager`: session + CSRF token lifecycle; never reuses consumed write tokens.
- `E5785CapabilityProbe`: probes endpoints after authentication and gates UI features by actual firmware support.
- `E5785Repository`: read/write router operations with XML parsing and typed error mapping.
- `LiveMonitor`: 2-second status polling; instantaneous throughput derived from traffic counter deltas.
- `BandManager`: read current net-mode before writes; supported-band presets; snapshot + rollback.
- `BenchmarkEngine`: records band, signal, latency, loss, download/upload and ranks best configuration.
- Feature modules: Dashboard, Network/Bands, SMS, APN, Wi-Fi/Hosts, SIM/PIN, Traffic/Battery, Diagnostics.

## Safety / reliability rules
1. Target gateway is `192.168.8.1` and device identity must resolve to E5785Lh-22c before advanced writes are enabled.
2. Every POST obtains a fresh verification token/session state first.
3. Never expose a feature as supported solely because another HiLink model implements the endpoint.
4. Before band/APN/network writes, capture current settings; provide rollback and auto-recovery for connection-loss scenarios where feasible.
5. Map HiLink errors (wrong token/session, no rights, busy, unsupported, SIM/PIN errors) to explicit Arabic UI messages.
6. No Generic/Universal driver in this edition.
7. Firmware modification, debug-shell enabling, SIM-lock bypass, or unverified cell-lock commands are not part of normal app operations.

## Initial endpoint probe set
- `/api/webserver/SesTokInfo`
- `/api/device/information`
- `/api/device/signal`
- `/api/monitoring/status`
- `/api/monitoring/traffic-statistics`
- `/api/net/current-plmn`
- `/api/net/net-mode`
- `/api/pin/status`
- `/api/pin/simlock`
- `/api/sms/sms-count`
- `/api/wlan/host-list`
- `/api/wlan/basic-settings`

Implementation must treat probe results as firmware-specific capabilities.
