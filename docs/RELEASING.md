# Releasing Meteor

How a version becomes a signed, installable APK on [GitHub Releases](https://github.com/primary-null/Meteor/releases). For maintainers; users want the README's [Download and install](../README.md#download-and-install).

## How it works

Pushing a tag such as `v1.10` (it must equal `v` + `versionName` in `version.properties`, on a commit that is on `main`) starts the [Release workflow](../.github/workflows/release.yml):

1. **Validate.** The same checks as every push: source guard, Python tests, Android unit tests, lint.
2. **Build.** `:app:assembleRelease` on a GitHub-hosted runner produces an **unsigned** APK. The signing key never enters Gradle. `scripts/release_apk.py verify --unsigned` checks the package name, that `versionName`/`versionCode` match `version.properties`, that it is neither debuggable nor `testOnly`, that it is zip-aligned for 16 KB pages and that 64-bit native libraries are 16 KB aligned. The APK goes to a run artifact, not to the Releases page.
3. **Sign**, by one of two paths chosen by the repository variable `METEOR_CI_SIGNING`:
   - **`true` (CI signing).** The job in the `release` environment signs with `apksigner`, re-verifies the result against the pinned fingerprint, attests it and creates a **draft pre-release** holding `Meteor-<version>.apk`, `SHA256SUMS.txt` and release notes taken from `CHANGELOG.md`.
   - **anything else (offline signing, the default).** The unsigned build is attested and left as the `meteor-unsigned` run artifact (kept 30 days; re-run the workflow to rebuild). You sign it on a trusted computer with the same script and create the draft yourself. The key never has to exist on GitHub.
4. **You publish** the draft after looking at it. Nothing is published automatically.

`workflow_dispatch` runs steps 1 and 2 on any branch and releases nothing; use it to rehearse.

## Decisions and why

| Decision | Reason |
|---|---|
| Release assets, not Actions artifacts | Artifacts need a signed-in GitHub account with read access and expire (90 days by default). Release assets have stable URLs that work without signing in, and they are what the README points to. |
| Unsigned build, then `apksigner` | Android requires a signature before install, and the same key on every update. Signing after the build keeps the key out of Gradle and its plugins, and lets the identical code run in CI and offline. Debug-signed builds (`candidate`) are never published. |
| Signer fingerprint pinned in `release-signer.sha256` | A wrong or swapped key (a debug key, a new secret) cannot ship by accident; the script refuses it. Users can compare the same value. |
| Draft, pre-release by default | Matches GitHub's guidance to attach every asset to a draft before publishing, and keeps `Latest` for a release you chose to promote. Live-server acceptance is tracked separately from signing. |
| Keep the application ID `com.meteor.app` | A separate "preview" ID would force everyone to reinstall (erasing saved connections, which are excluded from backup) when moving to the real ID. Debug and candidate builds already install beside it under `.debug` and `.candidate`. |
| Immutable releases (recommended) | Published assets and the tag cannot change, and GitHub records a release attestation that `gh release verify` checks. |
| Actions pinned to commit SHAs, least privilege per job | Only the signing job gets `contents: write`; the validate and build jobs are read-only. |

## The signing key

Android compares the signing certificate on every update. A release signed with a different key cannot update the installed app; the only way forward is a new application ID and a reinstall. So the key must be created once, kept for the life of the app, and backed up before the first release. If it is lost, updates stop. If you still hold an old key, `apksigner rotate` can build a certificate lineage that lets Android accept a new key.

Create a PKCS12 keystore (alias `meteor-release`, valid for more than 25 years; Android recommends 25 or more and Google Play requires a key valid past 22 October 2033):

```sh
keytool -genkeypair -storetype PKCS12 -keystore meteor-release.p12 -alias meteor-release \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=Meteor Release"
```

- Keep at least two offline copies in different places, and the password somewhere separate. Never commit the keystore or its password, paste them into an issue or chat, or print them in logs. `.gitignore` and `scripts/source_guard.py` block keystore files.
- Read the certificate's SHA-256 fingerprint and put it on a line of `release-signer.sha256` (colons and letter case are ignored). Commit that **before** the first release; releases are blocked until it is there.
  ```sh
  keytool -list -v -keystore meteor-release.p12 -alias meteor-release   # use the "SHA256:" line
  ```
- Android is rolling out [developer verification](https://developer.android.com/developer-verification/guides): apps installed on certified devices must be registered by a verified developer, which links the package name `com.meteor.app` to this signing key. Register it with the same key you publish with.

## One-time setup

1. Create the key and commit `release-signer.sha256` as above.
2. Repository Settings, Releases: enable release immutability (it applies to future releases only).
3. For **CI signing** only: create an environment named `release` (Settings, Environments) with required reviewers and a deployment tag rule `v*`; add the environment secrets `METEOR_KEYSTORE_B64` (`base64 -w0 meteor-release.p12`) and `METEOR_KEYSTORE_PASSWORD`; then set the repository variable `METEOR_CI_SIGNING` to `true`. Anyone with write access can read plain repository secrets, which is why the secrets belong to the gated environment. Prefer offline signing if you cannot restrict who can write to the repository.

## Cutting a release

1. Bump the version if needed (`python scripts/version.py bump minor`) and write a `## <versionName>` section in `CHANGELOG.md`; it becomes the release notes.
2. Merge to `main` and wait for **Validate** to pass.
3. Tag and push: `git tag -a v1.10 -m "Meteor 1.10" && git push origin v1.10`.
4. Open the **Release** run.
   - *CI signing:* it ends with a draft release. Go to step 5.
   - *Offline signing:* download and check the build, then sign it (the password stays in your shell, never in the command line):
     ```sh
     gh run download <run-id> --name meteor-unsigned --dir unsigned
     gh attestation verify unsigned/Meteor-1.10-unsigned.apk --repo primary-null/Meteor
     read -rs METEOR_KEYSTORE_PASSWORD; export METEOR_KEYSTORE_PASSWORD
     python scripts/release_apk.py assemble --unsigned unsigned/Meteor-1.10-unsigned.apk \
       --build-info unsigned/build-info.json --keystore /path/to/meteor-release.p12 \
       --alias meteor-release --ks-pass env:METEOR_KEYSTORE_PASSWORD --out-dir dist
     gh release create v1.10 dist/Meteor-1.10.apk dist/SHA256SUMS.txt --draft --prerelease --verify-tag \
       --title "Meteor 1.10" --notes-file dist/RELEASE-NOTES.md
     ```
     `assemble` needs the Android SDK build-tools (`ANDROID_HOME`) and refuses to write an APK unless the signer matches the pin, the contents are unchanged by signing and every check below passes. Run `unset METEOR_KEYSTORE_PASSWORD` afterwards.
5. Review the draft: the two assets, the notes, the signer fingerprint. Optionally install it over the previous release on a test phone to confirm the in-place update.
6. Publish (Releases, Edit, Publish release; or `gh release edit v1.10 --draft=false`). Promote it to **Latest** only when you mean it: clear *pre-release* and set it as the latest release.
7. Check as a user would: from a signed-out browser, open the release and download the APK; run `sha256sum -c SHA256SUMS.txt` and `apksigner verify --print-certs`. With immutable releases enabled, `gh release verify v1.10` and `gh release verify-asset v1.10 Meteor-1.10.apk` confirm the published asset.

## What `release_apk.py` enforces

`verify` and `assemble` refuse an APK unless: the package is `com.meteor.app`; `versionName` and `versionCode` equal `version.properties`; it is not debuggable and not `testOnly`; it is zip-aligned and 64-bit native libraries are 16 KB aligned; for a signed release, `apksigner` verifies it with exactly one signer, a v2 or v3 signature, a certificate that is not an Android debug certificate, that matches `release-signer.sha256`, and that is valid for 25 or more years and past October 2033. `assemble` also checks that signing left the APK's contents unchanged and that the unsigned file is the one the build recorded. Passwords are accepted only as `env:NAME` or `file:PATH`, never inline.

Run the tests with `python -m unittest discover -s scripts -p "test_*.py"`. The signing tests need the Android SDK build-tools and a JDK and are skipped without them.

## When something goes wrong

- **The workflow failed or the draft is wrong, nothing published:** delete the draft (`gh release delete v1.10`) and the tag (`git push --delete origin v1.10`), fix, and tag again.
- **A bad release was published:** with immutable releases the assets and tag cannot be changed. Mark it as a pre-release or delete it, raise `versionCode`, and release again.
- **The key is lost or exposed:** see [The signing key](#the-signing-key). A lost key means a new application ID; an exposed key you still hold can be rotated with `apksigner rotate`. Either way, tell users and update `release-signer.sha256` in the same commit as the first release signed by the new key.

## Sources

- GitHub: [Managing releases](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository), [Linking to releases](https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases), [Immutable releases](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases), [Verifying the integrity of a release](https://docs.github.com/en/code-security/supply-chain-security/understanding-your-software-supply-chain/verifying-the-integrity-of-a-release), [Workflow artifacts](https://docs.github.com/en/actions/concepts/workflows-and-actions/workflow-artifacts), [Artifact attestations](https://docs.github.com/en/actions/how-tos/secure-your-work/use-artifact-attestations/use-artifact-attestations), [Secure use of Actions](https://docs.github.com/en/actions/reference/security/secure-use), [Environments](https://docs.github.com/en/actions/how-tos/deploy/configure-and-manage-deployments/manage-environments).
- Android: [Sign your app](https://developer.android.com/studio/publish/app-signing), [apksigner](https://developer.android.com/tools/apksigner), [Android developer verification](https://developer.android.com/developer-verification/guides).
