# Live XCP-ng 8.3 VM settings answers (sanitized)

Setter calls sent to the disposable lab guest of the real XCP-ng 8.3.0 lab host (see `../live-xcpng-8.3/README.md` for the
lab and the sanitizing rules), with the answers the server gave. Recorded 2026-11-15 (UTC) with
`meteor_lab/record_fixtures.py guest-settings-writes --confirm-writes-to-disposable-guest`, which never changes the guest's
power state, records every original value first and restores it before it ends (the restore was proven by a read-back).
The guest was running; calls that need a halted VM were sent with the guest's current values and are recorded refusals.

The capture was sanitized with `meteor_lab/sanitize_fixtures.py` into a scratch folder (its leak check passed), and only the
`settings-*` exchanges were copied here; `index.json` lists them without request or response headers. The same real value
maps to the same fake value as in `../live-xcpng-8.3/`, so the guest's references resolve against those records.

`NAME.request.xml` is the exact request body, `NAME.response.xml` the answer. `settings-before.VM.get_record` is the guest
before any write and `settings-final.VM.get_record` after the restore. Design and results: `METEOR-VM-SETTINGS.md`.

Do not edit these files by hand and do not add captures that were not run through the sanitizer.
