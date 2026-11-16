# Live XCP-ng 8.3 fixtures (sanitized)

Answers recorded from a **real XCP-ng 8.3.0 host** (a nested lab host with one small Debian 12 guest), not from the
simulator and not written by hand. They exist so the app's parsers and the simulator stop relying on guesses.

| | |
| --- | --- |
| Recorded | 2026-11-15 (UTC), several passes within about 25 minutes |
| Server | XCP-ng 8.3.0 (Xen 4.17.6), one host, one pool, API over HTTPS on port 443 |
| Client | the shapes of Meteor 1.10 (Android delivery 17): `XmlRpc.kt`, `XapiGateway.kt`, `PinnedHttp.kt`, `ConsoleSession.kt`, `RfbProtocol.kt` |
| Tool | `meteor_lab/record_fixtures.py capture`, then `... sanitize` (`meteor_lab/sanitize_fixtures.py`) |
| State | **sanitized**; the raw captures stay in the owner's private runtime directory, outside Git |

## What was sanitized

Every UUID and opaque reference (session references included), IP address, MAC address, host name, pool and guest name,
iSCSI initiator name, certificate fingerprint and lab-specific description text was replaced, **deterministically**: the
same real value always becomes the same fake value, so cross-references (a VM's `resident_on`, a legend's UUID, an event's
`ref`) still resolve. IP addresses became documentation-range addresses (`192.0.2.0/24`, `198.51.100.0/24`,
`203.0.113.0/24`; IPv6 `2001:db8::/32`). Netmasks, `0.0.0.0` and loopback stay. E-mail addresses in package changelogs
became `maintainer@example.test`. The host's time zone and CPU model name became the placeholders `Example/Nowhere` and `Example CPU (model removed)`, each exactly as long as the value it replaced so recorded byte counts stay true. Per-repository data sources, which a server names after the first eight characters of the repository's UUID, are named after the sanitized UUID. The root password is never stored: the login request carries `REDACTED`. Nothing else
changed: field order, types, whitespace, number formatting and spelling are the server's.

After writing, the sanitizer searches every file for every real value it ever replaced (and for the lab address, the
certificate fingerprint, the password and lab words) and refuses to finish if one is left. `python scripts/source_guard.py`
and a separate grep were run before committing.

## Layout

`index.json` lists every exchange: name, kind (`xmlrpc`, `http`, `rfb`, `stream`), request line and headers, response
status and headers, timing, and the files below. For an exchange `NAME`:

* `NAME.request.xml` – the exact XML-RPC request body the app's `XmlRpc.request` produces;
* `NAME.response.xml` / `NAME.response.json` – the response body, byte for byte after sanitizing;
* console sessions: `NAME.handshake.s2c.bin.gz` (server bytes up to and including ServerInit), `NAME.c2s.bin.gz`
  (everything the client sent after `CONNECT`), and for Raw and Hextile `NAME.handshake-and-black-probe-updates.s2c.bin.gz`.

`entries` named `rrd-applike-*` reproduce the app's OLD history request (start aligned down; the server answered from the next
coarser archive), `rrd-fixed-*` the request the app sends since the archive fix (`HistoryRange.query`: start two steps inside the
span; `-host-` with `host=true`, `-vm-` with `vm_uuid`, `-incremental` two steps before the newest row; recorded on 2026-11-15
with `capture --part fixed`, sanitized into a scratch folder and added without touching the other files), `rrd-edge-*` start a few
steps inside the range, `rrd-interval-*`/`rrd-nointerval-*` probe the `interval` parameter, `event.from.*` are the event
calls in the order they were made (`during-start-*` happened while the guest was started), `arity-*` and `error-*` are
error replies, `introspect*` are `system.methodSignature`/`methodHelp` attempts.

## What is deliberately not here

* **Framebuffer data.** The guest's screen shows its host name and the host's console shows the host's address, so no
  rendered screen is committed. The console fixtures hold the handshake and, for Raw/Hextile, the console's tiny probe
  answers that carry no text. The full-screen replays run only where the private captures exist
  (`Live83RfbPrivateReplayTest`, skipped everywhere else).
* `role.get_all_records` (1.8 MB, not used by the app) and anything that would need a write.

Do not edit these files by hand and do not add captures that were not run through the sanitizer.
