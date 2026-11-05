# Meteor

**A native Android client for XCP-ng.** Meteor connects directly to a pool's XAPI over certificate-pinned HTTPS and shows hosts, virtual machines, storage and per-VM details on your phone. You can start, gracefully shut down and reboot VMs, and every power action goes through a write-ahead journal that never repeats a command it cannot verify.

> **Status:** in active development. Direct XAPI inventory, VM details, appearance themes and journaled power actions work today. Xen Orchestra connections and a VM console are planned but not implemented. Meteor is not published to an app store, and release builds are unsigned.

<p align="center">
  <img src="design/assets/silver-hybrid.png" alt="Meteor icon artwork" width="160">
</p>

## Features

- **Direct XAPI connection.** Connect to the pool coordinator with no extra server or agent to install.
- **Trust you can review.** Before sending any credentials, Meteor shows the server certificate's SHA-256 fingerprint so you can compare it. Accepting it pins that exact certificate.
- **Fleet overview.** See pool, host, VM and storage capacity at a glance, with VMs grouped by the host they run on (or *Unassigned* when halted).
- **VM details.** View disks, network interfaces, guest-tools status, OS and guest addresses (hidden until you ask to see them), last boot time and the operations the server currently allows.
- **Safe power control.** Start, graceful shutdown and graceful reboot, each with a fresh confirmation. There are no forced power actions and nothing is resent automatically.
- **Make it yours.** Light, dark or system mode, four accent colours, an optional orbital texture, and a custom icon per VM that stays with the VM's identity as it migrates.

## Architecture highlights

Meteor is a single-activity Kotlin / Jetpack Compose app with a deliberately small dependency surface. Networking, XML-RPC and storage are implemented on platform APIs, with no third-party HTTP or crypto libraries.

### Pinned HTTPS to XAPI

XCP-ng hosts usually serve self-signed certificates, so Meteor replaces CA trust with an explicit, user-approved **leaf certificate pin** (`ServerTrust`, `XapiGateway.kt`):

- Certificate inspection completes a TLS handshake and reads the leaf certificate **without sending credentials**.
- Every later request requires the exact SHA-256 pin, a currently valid certificate and the expected hostname. A changed certificate fails with a distinct `CertificateChanged` error, so the UI can ask you to verify again rather than reporting a generic failure.
- Redirects are refused, responses are capped at 8 MiB, and only `https://host[:port]/` endpoints without paths, queries or embedded credentials are accepted. Cleartext traffic is disabled for the whole app.
- The XML-RPC parser (`XmlRpc.kt`) rejects DTDs and entities, limits nesting depth and node count, rejects duplicate struct fields, and turns XAPI failures into error codes without echoing server-supplied arguments.
- Each operation runs in a short-lived session. Once login has returned a session, Meteor attempts `session.logout` in a `finally` block, including when a later response is malformed. Logout is best-effort, and a failed logout is ignored.

### Encrypted connection profiles

Profiles (endpoint, username, password and pin) are stored in a single **AES-256-GCM** document. The key is generated in and never leaves the **Android Keystore** (`ProfileVault.kt`):

- Versioned file header, random IV per write, authenticated associated data, atomic writes (`AtomicFile`) and size-bounded reads.
- A corrupt or unreadable vault is left untouched. Meteor falls back to session-only connections instead of overwriting the data.
- App data is excluded from cloud backup and device transfer, and `Profile.toString()` redacts credentials.

### Journaled power actions with safe uncertain-outcome handling

Power control is designed around one rule: **a command whose outcome is unknown is never sent again automatically** (`Power.kt`, `OperationJournal.kt`).

1. **Confirm against fresh state.** Pressing an action re-reads the VM. The confirmation expires after 30 seconds and is rejected if the connection, pool, VM, power state or boot time changed in the meantime.
2. **Write ahead.** The operation is journaled as `PREPARED`, then `SUBMITTING`, *before* the request leaves the device. A single process-wide lock and a "no unresolved operation" check prevent concurrent or duplicate sends.
3. **Track the server task.** The returned task reference and UUID are journaled as soon as they arrive. If a disk write fails, the ticket is kept in memory so a later status check can still persist it.
4. **Verify by read-back.** Task success alone is not enough. Meteor re-reads the VM and requires the expected power state, and a reboot also requires a *changed*, valid boot timestamp.
5. **Classify failures honestly.** An explicit XAPI rejection before a task exists is a definite failure. A lost response, timeout or crash after sending is recorded as `UNKNOWN`.
6. **Recover by observing only.** *Check status* re-reads the original pool and VM through the original trusted connection and resumes watching the saved task. Recovery code has no path to `submit`.
7. **Manual review as a last resort.** If the task can't be found, you can review the record. Meteor reads the original VM again, refuses while any VM operation is active, asks you to confirm that you checked the server's task history, and re-reads once more before closing. The closed record keeps the original action and its **unverified** outcome. It is never reported as a success.

While an operation is unresolved, new power commands stay locked, but connections can still be repaired or added so you can investigate.

### Responsive and race-safe UI

- `MeteorViewModel` uses generation counters to discard stale inventory and detail responses. When you switch connections or VMs quickly, the latest request wins, and a detail response from another connection is never shown, even if both point at the same pool.
- The UI refreshes inventory and open VM details only while the app is visible (`repeatOnLifecycle`).

### Executable reference model

`meteor_logic/` is a dependency-free Python model of the same rules: connection-generation fencing, capability gates, confirmation plans, a SQLite journal, duplicate-action exclusion and read-back verification. It was written as an executable specification before the Android implementation, and it remains a fast way to explore edge cases. It covers both the direct-XAPI and the planned Xen Orchestra provider shapes, but it contains no network code.

More detail is in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Tech stack

Kotlin · Jetpack Compose (Material 3) · Kotlin coroutines & Flow · AndroidX Lifecycle / ViewModel · DataStore · Android Keystore · `HttpsURLConnection` + XmlPullParser · JUnit 4 · Python 3.11+ (reference model and repository checks) · GitHub Actions

Minimum Android 12 (API 31), target API 36.

## Repository layout

```
android-app/                Gradle project (single :app module)
  app/src/main/…/data/      Transport, XML-RPC, trust, vault, journal, power engine
  app/src/main/…/           Compose UI and ViewModel
  app/src/test/             JVM unit tests, including a real loopback HTTPS/XAPI fixture
  app/src/androidTest/      Offline instrumentation tests (vault, journal, UI journeys, races)
meteor_logic/               Python reference model and its acceptance tests
scripts/                    Repository checks: source guard and version helper
design/                     Interactive HTML design studies
docs/                       Architecture notes
```

## Build and test

Requirements: **JDK 21**, the **Android SDK with platform 36.1**, and **Python 3.11+**. Point Gradle at the SDK with `ANDROID_HOME` or an untracked `android-app/local.properties` (`sdk.dir=…`).

```sh
cd android-app
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug   # gradlew.bat on Windows
```

From the repository root:

```sh
python scripts/source_guard.py                         # blocks private-key headers, GitHub tokens, credential/keystore files and build artefacts
python -m unittest discover -s scripts -p test_safety.py
python -m unittest meteor_logic.test_core              # reference-model acceptance tests
```

The JVM unit tests need no device or network. The transport tests start a local HTTPS server with a temporary self-signed certificate. CI ([`.github/workflows/validate.yml`](.github/workflows/validate.yml)) runs all of the above on Ubuntu and needs no secrets.

The `candidate` build type produces a non-debuggable, release-like APK with its own application ID (`com.meteor.app.candidate`), signed with the local debug key so it can be installed side by side for testing. The instrumentation tests in `app/src/androidTest` target this build. They are fully offline and use isolated, synthetic storage, but some of them create and corrupt their own test files, so run them on a **disposable emulator**:

```sh
./gradlew -PmeteorTestBuildType=candidate :app:assembleCandidate :app:connectedCandidateAndroidTest
```

## Using it

1. Open **Connect**, enter a name, the pool coordinator's address (for example `https://xcp-pool.example`), a username and a password.
2. Tap **Review certificate** and compare the fingerprint with the one shown on the host's console or from a trusted source.
3. Tap **Trust and connect**. The profile is saved encrypted only after a successful connection.

Meteor is meant for private networks or a VPN. It does not expose any listening service.

## Roadmap

- Xen Orchestra (REST) connections alongside direct XAPI
- VM console
- Production release signing and store packaging

## License

No license has been chosen yet. See [LICENSE-TODO.md](LICENSE-TODO.md). Until a license is added, all rights are reserved.
