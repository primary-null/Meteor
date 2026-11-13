# Change history

## 1.10 — Android delivery 17

Minor increment: useful new capabilities on the existing direct-XAPI product. Journal and profile documents keep version 1 and gain only optional keys, so 1.9 data loads unchanged; an older build that meets a new operation kind keeps it locked instead of discarding it. Offline checks do not imply live-server acceptance. No Xen Orchestra adapter in this release.

- Add typed navigation with system Back, a navigation rail and list-detail panes for medium and expanded widths such as foldable inner displays, and state that survives folding and unfolding. Split the UI into per-screen files.
- Generalize the journaled power model into an operation framework and add forced shutdown and reboot, suspend and resume, pause and unpause, start on a chosen host and live migration within the pool. Disruptive kinds require typing the VM name; role limits are explained before sending.
- Add snapshot take, revert and delete, CD/ISO insert and eject, host disable, evacuate and enable, host reboot and shutdown when disabled and empty, and storage repair, on the same framework. Snapshot deletion journals each sub-step. A call is resent with the other XAPI signature only after an explicit `MESSAGE_PARAMETER_COUNT_MISMATCH`.
- Add performance history from `/rrd_updates` (XML and JSON) with 10 minute, 2 hour and 1 week ranges, richer VM, host, network and storage detail, and a read-only Networks view.
- Add the Alerts inbox with readable alarm summaries and confirmed dismissal, `event.from` refresh on a separate foreground session, explicit pool-master follow with certificate review, and XAPI error codes in notices.
- Add the VM console as a full-screen destination with compressed encodings, zoom, special keys, keyboard and pointer input, view-only mode and member-host certificate trust.
- Add opt-in background alert notifications (WorkManager, read-only), an All pools summary and a manual, read-only host update check.
- Device testing on a foldable phone against a simulated pool led to these corrections: background inventory refreshes no longer drop the connection, clear open confirmations or rewrite saved profiles; detail polling no longer disables controls; closed operation cards stop pinning above every screen; timestamps are localized; long names ellipsize; the console toolbar respects display insets; disabled buttons look disabled and carry a reason; returning from VM performance reopens the VM; VM actions keep a stable position when the VM's state changes.
- Distribute signed APKs on GitHub Releases. The release build stays unsigned; a tag-triggered workflow builds it on GitHub's runners and `scripts/release_apk.py` signs and checks it (package, version, debuggable and test-only flags, 16 KB alignment, pinned signer fingerprint) outside Gradle. Releases are drafted for review and never published automatically. The release APK no longer carries repository metadata. The README documents download, verification and in-place updates; see `docs/RELEASING.md`.
- Checks: source guard, Python reference tests, debug build, lint, instrumentation test compilation and 599 JVM tests, including loopback pinned-HTTPS XAPI fixtures.

## 1.9 validation build — Android delivery 13

Minor increment for useful read-only observability: current VM/host performance and retained pool tasks/alerts. No new power command or persisted schema is introduced. Development signing and offline checks do not imply production or live-server acceptance.

Advance to delivery 12 after correcting keyboard overlap found during offline runtime validation of delivery 11.
Delivery 13 updates the display clock when observations arrive, so a new sample does not wait for the next UI tick to appear current. Center constant series in their chart range and verify fresh labels through the real navigation.

- Add bounded CPU, memory, disk and network sample charts using the existing pinned HTTPS transport and XAPI data sources. Preserve declared units and missing values; distinguish allocated memory from guest-reported free memory. Poll only on the visible performance page, with at most sixty observations over five minutes and no server recording changes.
- Add a pool activity snapshot with active/all-retained tasks and collapsed alert details. Keep progress estimates separate from completion, and server task status separate from verified local power history. Report unavailable sections explicitly without discarding readable sections.
- Fence samples and activity by the original profile, connection, pool, object and request generation. Clear them on disconnect or connection/pool changes; retain last-known values with explicit freshness after a read failure.
- Exercise identity races, bounded/missing samples, malformed timestamps and role-limited responses in JVM tests, including real pinned loopback HTTPS. Add offline presentation tests and select candidate instrumentation automatically when its assembly task is explicitly requested.
- Apply keyboard resizing to the activity, consume keyboard insets and clear focus when a connection form is submitted or cancelled. Keep fields, submit buttons and resulting notices reachable above the keyboard. Exercise host/VM performance and pool activity through the actual lazy-list navigation.
