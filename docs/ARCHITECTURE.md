# Architecture

Meteor is one Android application module (`android-app/app`) in a single package, `com.meteor.app`. The `data` package has no Compose dependency, and almost all of it runs on the JVM. Only the stores (vault, journal, appearance and alert state) need an Android `Context`.

```
┌──────────────────────────── UI (Compose) ────────────────────────────┐
│ MeteorApp.kt shell · Navigation (typed back stack) · Adaptive panes  │
│ Overview · VMs · Alerts · Connect · Settings · Console · Networks    │
│ Performance · History · Pool activity · dialogs and detail sections  │
└──────────────────────────────────┬───────────────────────────────────┘
                         StateFlow<MeteorState>
┌──────────────────────────────────┴───────────────────────────────────┐
│ MeteorViewModel and controllers: Connection, Details, Depth,         │
│ Observability, Alerts, Event, Console, StorageHost, PoolSummary      │
└──┬───────────────┬────────────────┬────────────────┬─────────────────┘
   │               │                │                │
ProfileVault   PowerService     MeteorGateway    AppearanceStore,
(Keystore      (journal +       (XapiGateway:    AlertSeenStore,
 AES-GCM)       operation        pinned HTTPS     BackgroundAlertStore
                engine)          + PinnedHttp)
                   │                │
            OperationJournal   XmlRpc · ServerTrust · InventoryMapper
            + JournalCodec     Rrd · Events · RfbProtocol · Framebuffer
            (AtomicFile JSON)
```

## Data layer

| File | Responsibility |
| --- | --- |
| `Domain.kt` | `Profile`, endpoint normalisation, `Host`/`Guest`/`Inventory`, and `InventoryMapper`, which turns raw XAPI records into a validated inventory (exactly one pool, unique host and VM identities, templates, snapshots and dom0 filtered out). |
| `XmlRpc.kt` | A minimal, hardened XML-RPC encoder and decoder covering only the types XAPI uses. |
| `XapiGateway.kt` | `ServerTrust` (fingerprinting, the pinned `SSLContext`, credential-free inspection) and `XapiSession` (login, record reads, VM details, async power calls, task reads, logout). |
| `SessionReuse.kt` | One shared XAPI session per connected profile: single-flight login, one re-login for a read answered `SESSION_INVALID`, logout on identity change or after two minutes in the background, and the rule that a write is never repeated or sent on a kept-alive connection. |
| `InventoryScale.kt` | The streamed reader for the inventory's filtered VM list (`VM.get_all_records_where`), which keeps only the fields the app shows, and the limits that bound it. |
| `ProfileVault.kt`, `VaultRecovery.kt` | Encrypted profile document: `MET1` magic, 12-byte IV, AES-256-GCM ciphertext with fixed AAD; classification of an unreadable vault and the quarantine used by Start over. |
| `Power.kt` | Power domain types, `PowerRules` (eligibility, confirmation freshness, read-back verification) and `PowerService` (execute, reconcile, review, resolve). |
| `Tracking.kt` | How long each operation kind is watched, how many transient observation failures are tolerated, and the window for the read-back after a task succeeds. |
| `VmSettings.kt` | VM settings as journaled synchronous setters: the plan and order of steps, the read-back proof, the error codes that count as a definite rejection, and reconciliation after a lost reply. |
| `VmOperations.kt`, `TargetOperations.kt` | The operation framework: VM power and placement kinds, snapshot, CD, host and storage operations, their eligibility from `allowed_operations`, typed-name confirmation for disruptive kinds and per-kind read-back proof. |
| `OperationJournal.kt`, `JournalCodec.kt` | A versioned JSON journal written with `AtomicFile`. It keeps every unresolved record and the 50 most recent resolved ones, and stores no credentials, endpoints or guest addresses. New fields are optional, so 1.9 journals decode unchanged and unknown kinds stay locked. |
| `PinnedHttp.kt`, `Rrd.kt`, `History.kt` | Pinned HTTPS GET for `/rrd_updates`, the XML and JSON parsers, and bounded history ranges. |
| `LiveMetrics.kt`, `MetricFormat.kt` | The fixed set of live sources per object kind, read with `query_data_source`, and one number format for live and history values. |
| `Alerts.kt`, `Events.kt`, `MasterFollow.kt` | Alert inbox state and alarm summaries, `event.from` refresh triggers and the reviewed pool-master follow. |
| `ConsoleSession.kt`, `RfbProtocol.kt`, `RfbPixelFormat.kt`, `Framebuffer.kt`, `MemberTrust.kt` | The console: RFB over an HTTP `CONNECT` through the pinned connection, encodings for any true-colour pixel format, input and member-host certificate trust. |
| `HostConsole.kt` | Finding a host's own console through its control domain, and the identity and address checks that precede any connection to it. |
| `ReconnectPolicy.kt`, `ConsoleReconnect.kt` | Bounded backoff for automatic reconnects, the failures that stop them until the user acts, and the default-network monitor interface. |
| `BackgroundAlerts.kt`, `PoolSummary.kt` | Opt-in WorkManager alert checks with watermarks, the All pools summary and the read-only host update check. |
| `AppearanceStore.kt` | Theme preferences, the per-VM icon map keyed by `poolUuid:vmUuid`, and imported icon images, which are downscaled to at most 512 px and re-encoded as PNG. |

`MeteorGateway` and `PowerBackend` are interfaces, so tests replace the network with in-memory fakes. One JVM test (`XapiTransportIntegrationTest`) instead runs the production TLS, HTTP and XML-RPC path against a loopback `HttpsServer` with a throwaway certificate.

`ConnectionController` owns connection generations, explicit trust review and serialized profile commits, with injected `CertificateInspector` and `ProfileStorage` boundaries. `MeteorViewModel` coordinates that controller with VM details and power operations. The controller's cancellation, failed-write and session-only behavior is covered directly by JVM tests.

The remaining controllers follow the same pattern: `DetailsController`, `DepthController`, `ObservabilityController`, `AlertsController`, `EventController`, `ConsoleController`, `StorageHostController` and `PoolSummaryController` each fence their requests to the original connection and request generation. Most have their own JVM tests; storage and host operations are covered through `TargetServiceTest`, `TargetJournalTest` and a loopback `XapiStorageHostIntegrationTest`.

Host and storage details use the same validated pool snapshot as Overview. Their operations (host maintenance, reboot and shutdown when disabled and empty, storage repair) are submitted only through `PowerService.executeTarget`, sharing the journal, mutex and pending-action lock. Storage entries have unique identities; ISO repositories appear in details but do not inflate disk-capacity totals.

## Operation lifecycle

Every operation kind (VM power and placement, snapshots, CD media, host and storage operations) shares this lifecycle. Snapshot deletion journals each sub-step separately.

```
PREPARED ──► SUBMITTING ──► TRACKING ──► VERIFYING ──► SUCCEEDED
   │             │              │            │
   │             │              └──► FAILED (task failure / cancelled)
   │             └──► FAILED (explicit XAPI rejection, no task yet)
   │             └──► UNKNOWN (lost reply, timeout, crash after send)
   └──► FAILED on recovery ("app stopped before submission")

UNKNOWN ──(Check status: observe saved task)──► TRACKING / SUCCEEDED / FAILED / UNKNOWN
UNKNOWN ──(Manual review + acknowledgement)──► UNKNOWN, closed with ManualResolution
```

Invariants:

- `submit` is called from exactly one place, after the `SUBMITTING` record has been written. `reconcile` and `review` never call it.
- A new action requires the complete journal to contain no unresolved records. All unresolved records remain retained and block new submissions.
- Automatic recovery requires the original profile *and* its original origin, a hash of the normalised endpoint, the certificate pin and the username. A repaired connection (for example after a certificate renewal) can be used only for an explicit manual review.
- Success requires read-back. A reboot is verified only by a changed, valid boot timestamp, because the VM's power state is `Running` both before and after.
- A call is resent with the other XAPI signature only after an explicit `MESSAGE_PARAMETER_COUNT_MISMATCH`, which proves the first call never ran.
- Manual closure keeps `phase = UNKNOWN` and attaches a `ManualResolution`, so history never claims a success it did not observe.

## Concurrency

- `PowerService` holds a process-wide `Mutex`, so all execute, reconcile and review calls are serialised even across ViewModel instances.
- The ViewModel tags each inventory and detail request with a generation number and drops responses whose generation, profile or pool no longer match.
- Polling (inventory every 30 s when stale, open VM details every 5 s) runs inside `repeatOnLifecycle(STARTED)`, so it stops when the app is in the background. Foreground polls share one XAPI session; automatic reconnects back off and stop while the phone is offline or the server has rejected the saved credentials. Background inventory refreshes keep the connection, details and pending confirmations in place. Alert events use a separate session that also lives only while the app is visible. The one background path is the opt-in WorkManager alert check, which is read-only.

`DetailPolling` permits reuse of display-only disk/network topology for less than 60 seconds, bound to the original connection, pool and VM. Changed device references, power, boot or active operations force a full read. Every poll still reads current power, boot, allowed operations and active operations. The detail view identifies the topology observation time. Explicit detail refresh and `PowerConnection.details` always perform a full read.

Action history is read-only presentation over the existing journal. An atomic state revision prevents a delayed history read from replacing a newer operation callback. Manual closure remains an unknown original outcome, while successful task and VM verification is labelled separately. Informational, warning and error notices are typed rather than inferred from message text.

## Reference model (`meteor_logic/`)

A standard-library Python model of the connection and operation rules, with an SQLite-backed journal. Its tests (`python -m unittest meteor_logic.test_core`) document the intended behaviour independently of Android, including the planned Xen Orchestra provider. `python -m meteor_logic.scenarios` writes a JSON trace of eight scenarios (lost reply, reconnect during a task, VM migration and more) for inspection.

## Testing strategy

| Layer | Where | Runs on |
| --- | --- | --- |
| XML-RPC, endpoint, inventory mapping, power rules and engine | `app/src/test` | JVM, in CI |
| Real TLS + pinning + XML-RPC over loopback | `XapiTransportIntegrationTest` | JVM, in CI |
| Journal compatibility with 1.9 documents (fixtures in `app/src/test/resources`) | `JournalCodecTest`, `TargetJournalTest` | JVM, in CI |
| Sanitized recordings of a real XCP-ng 8.3 host replayed through the parsers, mappers and console decoders (`app/src/test/resources/live-xcpng-8.3`) | `Live83*` tests | JVM, in CI |
| Inventory at 300, 1,000 and 5,000 VM records, generated from the recorded records | `InventoryScaleTest` | JVM, in CI |
| Keystore vault, journal files, icon import, ViewModel races, Compose UI journeys, alerts and detail screens | `app/src/androidTest` | Android emulator (`candidate` build type) |
| Domain rules, independent of Android | `meteor_logic/test_core.py` | Python, in CI |

## Design notes

Meteor is a single-activity Kotlin / Jetpack Compose app with a deliberately small dependency surface. Networking, XML-RPC and storage are implemented on platform APIs, with no third-party HTTP or crypto libraries.

### Pinned HTTPS to XAPI

XCP-ng hosts usually serve self-signed certificates, so Meteor replaces CA trust with an explicit, user-approved **leaf certificate pin** (`ServerTrust`, `XapiGateway.kt`):

- Certificate inspection completes a TLS handshake and reads the leaf certificate **without sending credentials**.
- Every later request requires the exact SHA-256 pin, a currently valid certificate and the expected hostname. A changed certificate fails with a distinct `CertificateChanged` error, so the UI can ask you to verify again rather than reporting a generic failure.
- Redirects are refused, responses are capped at 8 MiB (the inventory's VM list is instead parsed as a stream, with its own limits sized from real record costs), and only `https://host[:port]/` endpoints without paths, queries or embedded credentials are accepted. Cleartext traffic is disabled for the whole app.
- The XML-RPC parser (`XmlRpc.kt`) rejects DTDs and entities, limits nesting depth and node count, rejects duplicate struct fields, and turns XAPI failures into error codes without echoing server-supplied arguments.
- Foreground calls share one XAPI session per connected profile (`SessionReuse.kt`), bound to that profile's endpoint, username, password and pin, and logged out on disconnect, on any identity change and after two minutes in the background. A read answered `SESSION_INVALID` logs in once and is repeated once. A write is never repeated, never sent on a kept-alive connection, and not started if its caller was already cancelled. Background checks, the event long-poll and consoles keep their own sessions.

### Encrypted connection profiles

Profiles (endpoint, username, password and pin) are stored in a single **AES-256-GCM** document. The key is generated in and never leaves the **Android Keystore** (`ProfileVault.kt`):

- Versioned file header, random IV per write, authenticated associated data, atomic writes (`AtomicFile`) and size-bounded reads.
- A corrupt or unreadable vault is left untouched. Meteor falls back to session-only connections instead of overwriting the data.
- App data is excluded from cloud backup and device transfer, and `Profile.toString()` redacts credentials.
- Renaming preserves the profile ID, credentials and pin. Leave the password empty when editing to keep it; credential changes use the saved pin. A different server address or renewed certificate requires explicit trust review.
- Disconnect clears the active selection so polling stops and the next launch stays disconnected when that selection can be saved. Forgetting checks the entire operation journal, blocks removal of an unresolved action's original profile and retains closed history. Storage failures preserve existing data and are reported explicitly.

### Journaled operations with safe uncertain-outcome handling

Every state-changing operation (VM power and placement, VM settings, snapshots, CD media, host maintenance and storage repair) is designed around one rule: **a command whose outcome is unknown is never sent again automatically** (`Power.kt`, `VmOperations.kt`, `TargetOperations.kt`, `OperationJournal.kt`).

1. **Confirm against fresh state.** Pressing an action re-reads the VM. The confirmation expires after 30 seconds and is rejected if the connection, pool, VM, power state or boot time changed in the meantime.
2. **Write ahead.** The operation is journaled as `PREPARED`, then `SUBMITTING`, *before* the request leaves the device. A single process-wide lock and a "no unresolved operation" check prevent concurrent or duplicate sends.
3. **Track the server task.** The returned task reference and UUID are journaled as soon as they arrive. If a disk write fails, the ticket is kept in memory so a later status check can still persist it.
4. **Verify by read-back.** Task success alone is not enough. Meteor re-reads the target and requires the expected result: the expected power state, the VM on the chosen host, the new snapshot present, the host disabled. A reboot also requires a *changed*, valid boot timestamp.
5. **Classify failures honestly.** An explicit XAPI rejection before a task exists is a definite failure. A lost response, timeout or crash after sending is recorded as `UNKNOWN`. The one retry is for calls whose signature differs between XAPI versions: only an explicit `MESSAGE_PARAMETER_COUNT_MISMATCH`, which proves the call never ran, permits sending it once with the other signature.
6. **Recover by observing only.** *Check status* re-reads the original pool and VM through the original trusted connection and resumes watching the saved task. Recovery code has no path to `submit`.
7. **Manual review as a last resort.** If the task can't be found, you can review the record. Meteor reads the original VM again, refuses while any VM operation is active, asks you to confirm that you checked the server's task history, and re-reads once more before closing. The closed record keeps the original action and its **unverified** outcome. It is never reported as a success.

VM settings use synchronous calls that return no task, so each setter is its own journaled step: the value is read just before sending, the single call is sent, and success is the requested value read back (`VmSettings.kt`). A lost reply is never resent. Only error codes the server raises before it writes anything count as a definite rejection; everything else is decided by the read-back.

While an operation is unresolved, new operations stay locked, but connections can still be repaired or added so you can investigate. A record whose VM was deleted on the server, or that an older build cannot interpret, can be closed after manual review instead of locking the app. Journal and profile documents remain version 1 and only gained optional keys, so data from earlier releases loads unchanged; an older build that meets an operation kind it does not know keeps it locked instead of discarding it.

### Responsive and race-safe UI

- Connection forms account for the software keyboard, keep lower fields and buttons reachable, and dismiss it when a form is submitted or cancelled.
- Typed destinations and a back stack (`Navigation.kt`) drive system Back. Width classes choose between the phone layout and a navigation rail with list-detail panes (`Adaptive.kt`).
- Background inventory refreshes keep the connection, open details and pending confirmations in place, and are fenced like every other request.
- `MeteorViewModel` uses generation counters to discard stale inventory and detail responses. When you switch connections or VMs quickly, the latest request wins, and a detail response from another connection is never shown, even if both point at the same pool.
- The UI refreshes inventory and open VM details only while the app is visible (`repeatOnLifecycle`).
- Automatic detail polling reuses display-only disk/network topology for up to 60 seconds. Power, boot time, allowed operations and active operations are always read anew. Explicit refresh and every power confirmation/verification read bypass this reuse.
- Connection coordination and certificate inspection are independently testable on the JVM. Informational notices, warnings and errors have separate presentation.

### Read-only observability

`ObservabilityController` fences requests by profile, connection origin, pool, object and generation. Switching connection/pool or disconnecting clears observations; read failures mark displayed data as last known. `MetricWindow` bounds memory, preserves gaps and resets on clock rollback or target change. The active page polls only while the app is visible.

The gateway uses XAPI's [VM](https://xapi-project.github.io/xen-api/classes/vm.html) and [host](https://xapi-project.github.io/xen-api/classes/host.html) `get_data_sources` as a catalogue and `query_data_source` for current values (a real server reports no values in the catalogue), plus read-only [task](https://xapi-project.github.io/xen-api/classes/task.html) and [message](https://xapi-project.github.io/xen-api/classes/message.html) snapshots through the same pinned HTTPS sessions. History comes from `/rrd_updates` (XML or JSON) through `PinnedHttp`, which applies the same pin, hostname, redirect and size rules as XML-RPC. That URL carries a session reference, so errors never include it. Task results, backtraces and error arguments are excluded, and no task cancellation is offered. Alert refresh uses `event.from` on a separate foreground session, and dismissal is confirmed by reading the alert back.

### Executable reference model

`meteor_logic/` is a dependency-free Python reference model of the core safety rules: connection-generation fencing, capability gates, confirmation plans, a SQLite journal, duplicate-action exclusion and read-back verification. It predates the Android implementation and is useful for exploring edge cases; Android-specific reboot, encrypted-storage and manual-recovery behavior is covered by the Kotlin tests. The model covers both the direct-XAPI and planned Xen Orchestra provider shapes, but contains no network code.

## Repository layout

```
android-app/                Gradle project (single :app module)
  app/src/main/…/data/      Transport, XML-RPC, trust, vault, journal, power engine
  app/src/main/…/           Compose UI and ViewModel
  app/src/test/             JVM unit tests, a real loopback HTTPS/XAPI fixture, sanitized recordings of a real XCP-ng 8.3 host
  app/src/androidTest/      Offline instrumentation tests (vault, journal, UI journeys, races)
meteor_logic/               Python reference model and its tests
scripts/                    Repository checks: source guard, version helper and release signing/verification
docs/                       Install, features, architecture, release guide and screenshots
```

## Build and test in detail

Requirements: **JDK 21**, the **Android SDK with platform 36.1**, and **Python 3.11+**. Point Gradle at the SDK with `ANDROID_HOME` or an untracked `android-app/local.properties` (`sdk.dir=…`).

```sh
cd android-app
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug   # gradlew.bat on Windows
```

From the repository root:

```sh
python scripts/source_guard.py                         # blocks private-key headers, GitHub tokens, credential/keystore files and build artefacts
python -m unittest discover -s scripts -p "test_*.py"  # safety and release-script tests (signing tests need the SDK build-tools and a JDK)
python -m unittest meteor_logic.test_core              # reference-model tests
```

The JVM unit tests need no device or network. The transport tests start a local HTTPS server with a temporary self-signed certificate. CI ([`.github/workflows/validate.yml`](../.github/workflows/validate.yml)) runs all of the above on Ubuntu and needs no secrets.

`./gradlew :app:assembleRelease` produces an **unsigned** APK, which Android will not install. Signed releases come from the [Release workflow](../.github/workflows/release.yml), which builds on GitHub's runners and signs outside Gradle with a pinned key; see [docs/RELEASING.md](RELEASING.md).

Instrumentation tests in `app/src/androidTest` run offline with synthetic storage against a release-like build type (`candidate`, application ID `com.meteor.app.candidate`) that installs alongside a debug build. Some tests deliberately corrupt their own files, so run them on an emulator:

```sh
./gradlew -PmeteorTestBuildType=candidate :app:assembleCandidate :app:connectedCandidateAndroidTest
```

## Tech stack

Kotlin · Jetpack Compose (Material 3) · Kotlin coroutines & Flow · AndroidX Lifecycle / ViewModel · DataStore · WorkManager · Android Keystore · `HttpsURLConnection` + XmlPullParser · JUnit 4 · Python 3.11+ (reference model and repository checks) · GitHub Actions

Minimum Android 12 (API 31), target API 36.
