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
| `ProfileVault.kt` | Encrypted profile document: `MET1` magic, 12-byte IV, AES-256-GCM ciphertext with fixed AAD. |
| `Power.kt` | Power domain types, `PowerRules` (eligibility, confirmation freshness, read-back verification) and `PowerService` (execute, reconcile, review, resolve). |
| `VmOperations.kt`, `TargetOperations.kt` | The operation framework: VM power and placement kinds, snapshot, CD, host and storage operations, their eligibility from `allowed_operations`, typed-name confirmation for disruptive kinds and per-kind read-back proof. |
| `OperationJournal.kt`, `JournalCodec.kt` | A versioned JSON journal written with `AtomicFile`. It keeps every unresolved record and the 50 most recent resolved ones, and stores no credentials, endpoints or guest addresses. New fields are optional, so 1.9 journals decode unchanged and unknown kinds stay locked. |
| `PinnedHttp.kt`, `Rrd.kt`, `History.kt` | Pinned HTTPS GET for `/rrd_updates`, the XML and JSON parsers, and bounded history ranges. |
| `Alerts.kt`, `Events.kt`, `MasterFollow.kt` | Alert inbox state and alarm summaries, `event.from` refresh triggers and the reviewed pool-master follow. |
| `ConsoleSession.kt`, `RfbProtocol.kt`, `Framebuffer.kt`, `MemberTrust.kt` | The VM console: RFB over an HTTP `CONNECT` through the pinned connection, encodings, input and member-host certificate trust. |
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
- Polling (inventory every 30 s when stale, open VM details every 5 s) runs inside `repeatOnLifecycle(STARTED)`, so it stops when the app is in the background. Background inventory refreshes keep the connection, details and pending confirmations in place. Alert events use a separate session that also lives only while the app is visible. The one background path is the opt-in WorkManager alert check, which is read-only.

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
| Keystore vault, journal files, icon import, ViewModel races, Compose UI journeys, alerts and detail screens | `app/src/androidTest` | Android emulator (`candidate` build type) |
| Domain rules, independent of Android | `meteor_logic/test_core.py` | Python, in CI |
