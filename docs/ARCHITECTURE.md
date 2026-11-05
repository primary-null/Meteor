# Architecture

Meteor is one Android application module (`android-app/app`) in a single package, `com.meteor.app`. The `data` package has no Compose dependency, and almost all of it runs on the JVM. Only the vault, journal and appearance store need an Android `Context`.

```
┌──────────────────────── UI (Compose) ────────────────────────┐
│ MeteorApp.kt   Overview · VMs · Connect · Settings · dialogs │
└──────────────────────────────┬───────────────────────────────┘
                     StateFlow<MeteorState>
┌──────────────────────────────┴───────────────────────────────┐
│ MeteorViewModel   request generations, busy/lock flags       │
└───┬──────────────┬──────────────┬──────────────┬─────────────┘
    │              │              │              │
 ProfileVault   PowerService   MeteorGateway   AppearanceStore
 (Keystore      (journal +     (XapiGateway:   (DataStore) +
  AES-GCM)       task engine)   pinned HTTPS)   CustomIcons
                    │              │
              OperationJournal   XmlRpc / ServerTrust / InventoryMapper
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
| `OperationJournal.kt` | A versioned JSON journal written with `AtomicFile`. It keeps every unresolved record and the 50 most recent resolved ones, and stores no credentials, endpoints or guest addresses. |
| `AppearanceStore.kt` | Theme preferences, the per-VM icon map keyed by `poolUuid:vmUuid`, and imported icon images, which are downscaled to at most 512 px and re-encoded as PNG. |

`MeteorGateway` and `PowerBackend` are interfaces, so tests replace the network with in-memory fakes. One JVM test (`XapiTransportIntegrationTest`) instead runs the production TLS, HTTP and XML-RPC path against a loopback `HttpsServer` with a throwaway certificate.

## Power operation lifecycle

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
- At most one unresolved record exists, and while it does, no new action can be confirmed.
- Automatic recovery requires the original profile *and* its original origin, a hash of the normalised endpoint, the certificate pin and the username. A repaired connection (for example after a certificate renewal) can be used only for an explicit manual review.
- Success requires read-back. A reboot is verified only by a changed, valid boot timestamp, because the VM's power state is `Running` both before and after.
- Manual closure keeps `phase = UNKNOWN` and attaches a `ManualResolution`, so history never claims a success it did not observe.

## Concurrency

- `PowerService` holds a process-wide `Mutex`, so all execute, reconcile and review calls are serialised even across ViewModel instances.
- The ViewModel tags each inventory and detail request with a generation number and drops responses whose generation, profile or pool no longer match.
- Polling (inventory every 30 s when stale, open VM details every 5 s) runs inside `repeatOnLifecycle(STARTED)`, so it stops when the app is in the background.

## Reference model (`meteor_logic/`)

A standard-library Python model of the connection and operation rules, with an SQLite-backed journal. Its acceptance tests (`python -m unittest meteor_logic.test_core`) document the intended behaviour independently of Android, including the planned Xen Orchestra provider. `python -m meteor_logic.scenarios` writes a JSON trace of eight scenarios (lost reply, reconnect during a task, VM migration and more) for inspection.

## Testing strategy

| Layer | Where | Runs on |
| --- | --- | --- |
| XML-RPC, endpoint, inventory mapping, power rules and engine | `app/src/test` | JVM, in CI |
| Real TLS + pinning + XML-RPC over loopback | `XapiTransportIntegrationTest` | JVM, in CI |
| Keystore vault, journal files, icon import, ViewModel races, Compose UI journeys | `app/src/androidTest` | Disposable emulator (`candidate` build) |
| Domain rules, independent of Android | `meteor_logic/test_core.py` | Python, in CI |
