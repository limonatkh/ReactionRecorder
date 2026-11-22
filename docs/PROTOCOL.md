# Hub ↔ device protocol (version 1)

Read from `SatNet.kt`, `SatHub.kt`, `SatelliteService.kt`. The desktop hub must speak exactly this so the existing Android app connects to it unchanged.

## Transport
- TCP, default port **47600** (`SatNet.PORT`). Each message is one JSON object on one line (UTF-8, `\n`). Field `"t"` is the type.
- Pairing QR (`SatNet.qrPayload`) carries the hub addresses (`host:port`, several IPs), the 6-digit PIN, hub id and hub name. Phones without the app get it from the hub's small web page (`AppShare`).
- `"pong"` uses `sendStamped` (`t2` receive time, `t3` send time, device `elapsedRealtimeNanos`) for clock sync (NTP-style with the hub's `ping` times).

## Control connection (device → hub first)
| dir | t | fields | meaning |
|---|---|---|---|
| dev→hub | `hello` | `pin, id, name, role (mic/both), ver, lid, lleft, own` | join; wrong PIN → `deny {why}` |
| hub→dev | `welcome` | `name, active` | accepted; `active` = running session id or "" |
| hub→dev | `ping` | `id` | clock sync / keepalive |
| dev→hub | `pong` | `id, t2, t3` | stamped answer |
| dev→hub | `lvl` | `l` (0–32767), `rec`, `bat` (%), `free` (MB), `ll` | ~4×/s status |
| dev→hub | `cfgs` | `front, q, portrait, orient, rot, mirror, zoom, exp, torch, gain, aSrc, role, rec` | current settings |
| dev→hub | `caps` | `zmin, zmax, emin, emax, flash` | camera abilities |
| hub→dev | `pv` | `video, audio, level, iv (ms), px` | what preview to send and how often |
| dev→hub | `pvf` | `w, h, snap, j` (base64 JPEG) | preview frame |
| dev→hub | `au` | `d` (base64 PCM) | live audio preview (only when asked) |
| hub→dev | `cfg` | any of `zoom, exp, torch, gain, aSrc (0 natural, 1 surroundings, 2 voice-only), role, q, stab, afx/afy (tap focus 0..1)` | change settings (most only when not recording) |
| hub→dev | `flip` | — | switch front/back camera |
| hub→dev | `start` | `session` | start recording now |
| hub→dev | `stop` | `session` | stop |
| hub→dev | `send` | `session` | upload this session's files |
| dev→hub | `have` | `s` (array) | sessions stored on the device |
| dev→hub | `sent` / `nosession` | `session` | upload finished / not on this device |
| hub→dev | `lic` / `unlic` | `l` | licence grant / revoke; device answers `licok {ll}` / `licerr {why}` |

## Upload connection (separate socket per file)
1. dev→hub `upload {pin, dev, session, name, size}`
2. hub→dev `resume {offset}` (bytes already stored) — or `done` if complete
3. device streams raw bytes from `offset` to the end on the same socket
4. hub→dev `done`

Files per device: `audio.wav`, `video.mp4` (cameras), `meta.json` (`startBoot`, video `first` = first camera frame ns, `flips`, clock info).
