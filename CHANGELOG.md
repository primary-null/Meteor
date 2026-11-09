# Change history

## 1.9 validation build — Android delivery 13

Minor increment for useful read-only observability: current VM/host performance and retained pool tasks/alerts. No new power command or persisted schema is introduced. Development signing and offline checks do not imply production or live-server acceptance.

Advance to delivery 12 after correcting keyboard overlap found during offline runtime validation of delivery 11.
Delivery 13 updates the display clock when observations arrive, so a new sample does not wait for the next UI tick to appear current. Center constant series in their chart range and verify fresh labels through the real navigation.

- Add bounded CPU, memory, disk and network sample charts using the existing pinned HTTPS transport and XAPI data sources. Preserve declared units and missing values; distinguish allocated memory from guest-reported free memory. Poll only on the visible performance page, with at most sixty observations over five minutes and no server recording changes.
- Add a pool activity snapshot with active/all-retained tasks and collapsed alert details. Keep progress estimates separate from completion, and server task status separate from verified local power history. Report unavailable sections explicitly without discarding readable sections.
- Fence samples and activity by the original profile, connection, pool, object and request generation. Clear them on disconnect or connection/pool changes; retain last-known values with explicit freshness after a read failure.
- Exercise identity races, bounded/missing samples, malformed timestamps and role-limited responses in JVM tests, including real pinned loopback HTTPS. Add offline presentation tests and select candidate instrumentation automatically when its assembly task is explicitly requested.
- Apply keyboard resizing to the activity, consume keyboard insets and clear focus when a connection form is submitted or cancelled. Keep fields, submit buttons and resulting notices reachable above the keyboard. Exercise host/VM performance and pool activity through the actual lazy-list navigation.
