# Install and connect

**No signed APK has been published yet.** Once the first release is signed and published, its APK will appear on the **[Releases page](https://github.com/primary-null/Meteor/releases)**. Meteor needs Android 12 (API 31) or newer.

1. On your phone, open the [Releases page](https://github.com/primary-null/Meteor/releases) and the newest release.
2. Under **Assets**, download `Meteor-<version>.apk` (for example `Meteor-1.11.apk`). Ignore *Source code (zip)* and *(tar.gz)*: they are not installable. The GitHub Actions run artifacts are build output for developers and need a signed-in GitHub account.
3. Open the downloaded file. When Android asks, allow your browser or file manager to **install unknown apps**, then tap **Install**. You can switch that permission off again afterwards (Settings, Apps, Special app access, Install unknown apps).
4. Open Meteor and follow [Connect to a pool](#connect-to-a-pool).

If the install is blocked, check that the permission above is on for the app you opened the file from. On phones where Android's [developer verification](https://developer.android.com/developer-verification/guides) applies, an app from an unregistered developer needs the extra steps Android shows.

**Check the download (recommended).** Each release lists the APK's SHA-256 and the signing certificate's SHA-256 in its notes and attaches `SHA256SUMS.txt`. The signer's fingerprint is also committed in [`release-signer.sha256`](../release-signer.sha256).

- `sha256sum -c SHA256SUMS.txt` (Windows PowerShell: `Get-FileHash Meteor-<version>.apk`) must match the checksum.
- `apksigner verify --print-certs Meteor-<version>.apk` (`apksigner` is part of the Android SDK Build-Tools) must report one signer whose *certificate SHA-256 digest* equals that fingerprint.

If either differs, do not install the file.

**Update.** Install the newer APK over the old one. Every release has the application ID `com.meteor.app` and the same signing key, so Android updates it in place and keeps your saved connections, certificate pins and action history. **Do not uninstall first.** Android will not install an older version over a newer one.

If Android reports a conflict with an existing package, the installed copy was signed with a different key, for example a build you made yourself. Uninstalling it removes Meteor's saved connections (they are kept out of backups by design), so note your connection details first. Debug (`com.meteor.app.debug`) and candidate (`com.meteor.app.candidate`) builds use their own IDs and install beside the release.

Maintainers: [docs/RELEASING.md](RELEASING.md).

## Connect to a pool

1. Open **Connect**, enter a name, the pool coordinator's address (for example `https://xcp-pool.example`), a username and a password.
2. Tap **Review certificate** and compare the fingerprint with the one shown on the host's console or from a trusted source.
3. Tap **Trust and connect**. The profile is saved encrypted only after a successful connection.

Meteor is meant for private networks or a VPN. It does not expose any listening service.
