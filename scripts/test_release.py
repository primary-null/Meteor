"""Tests for scripts/release_apk.py.

The first group needs nothing but Python. The toolchain group builds a tiny APK
with the SDK's aapt2, signs it with throwaway keys made by keytool and tries to
break the release gate; it is skipped when the SDK build-tools or a JDK are absent.
"""
import base64
import contextlib
import datetime as dt
import io
import os
import re
import shutil
import struct
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest import mock

import release_apk as release

# Throwaway public test certificates (CN=Meteor Test Only, EC P-256), made with keytool.
LONG_PEM = """MIIBTjCB9aADAgECAghlQMVM/0egzzAKBggqhkjOPQQDAzAbMRkwFwYDVQQDExBN
ZXRlb3IgVGVzdCBPbmx5MCAXDTI2MDEwMTA2MDAwMFoYDzIwNTMwNTE5MDYwMDAw
WjAbMRkwFwYDVQQDExBNZXRlb3IgVGVzdCBPbmx5MFkwEwYHKoZIzj0CAQYIKoZI
zj0DAQcDQgAEC7KMmmhLtGKav9xQFiGXLQv2qKwvzxABvPEFwrlFQdwQ5JIwyq6S
+qmDjkhMDGt8vUdkVDvKRh9p4He35PByyaMhMB8wHQYDVR0OBBYEFLqz6lsEU92m
spD31nZuz6RSUxFCMAoGCCqGSM49BAMDA0gAMEUCIBae2BuqHpG4w5Ot9669KOaV
XuVjlCxFwwgs6aJHs8ZlAiEA3sztosmdCbP3WQY79XVOxRnpbxJh6O2V4nLu2XyB
+fw="""
SHORT_PEM = """MIIBTDCB9KADAgECAgkAuVGzZ5hCYB8wCgYIKoZIzj0EAwMwGzEZMBcGA1UEAxMQ
TWV0ZW9yIFRlc3QgT25seTAeFw0yNjAxMDEwNjAwMDBaFw0zNDAzMjAwNjAwMDBa
MBsxGTAXBgNVBAMTEE1ldGVvciBUZXN0IE9ubHkwWTATBgcqhkjOPQIBBggqhkjO
PQMBBwNCAARTNQoE7/iaJBv60eKzbxSEkEcrNrXKTvv3uAwVJOVMfJ+axT15++TO
1mWDimC/l9fvWAFhT183P/lGdC65mF+CoyEwHzAdBgNVHQ4EFgQUphdSC16bV0YH
PAzvclcfnB+CYDAwCgYIKoZIzj0EAwMDRwAwRAIgZ9dKNKYHkar3AoOwncyo/ldh
CW81u7WHWZGtJa25UIECIBX+kmNh/33M30L3p4dJ+1khLrUn7pGjXB2E+y0MLlZT"""

BADGING = """package: name='com.meteor.app' versionCode='17' versionName='1.10' platformBuildVersionName='16' compileSdkVersion='36'
minSdkVersion:'31'
targetSdkVersion:'36'
uses-permission: name='android.permission.INTERNET'
application: label='Meteor' icon='res/E4.xml'
"""
APKSIGNER = """Verifies
Verified using v1 scheme (JAR signing): false
Verified using v2 scheme (APK Signature Scheme v2): false
Verified using v3 scheme (APK Signature Scheme v3): true
Verified using v3.1 scheme (APK Signature Scheme v3.1): false
Verified using v4 scheme (APK Signature Scheme v4): false
Number of signers: 1
Signer #1 certificate DN: CN=Meteor Test Only
Signer #1 certificate SHA-256 digest: 21EF19BC4E4BF8EAE1B133AAC46D5298F92E32091E4BAAB884EFF7F34C3A70E7
-----BEGIN CERTIFICATE-----
{pem}
-----END CERTIFICATE-----
"""
PIN = "21ef19bc4e4bf8eae1b133aac46d5298f92e32091e4baab884eff7f34c3a70e7"
NOW = dt.datetime(2026, 11, 13)


def good_info(**changes):
    info = {"package": "com.meteor.app", "versionName": "1.10", "versionCode": 17, "debuggable": False,
            "testOnly": False, "zipAligned16k": True, "nativeLibsMisaligned": [],
            "signature": {"verified": True, "schemes": {"v2": False, "v3": True},
                          "signers": [{"dn": "CN=Meteor Release", "sha256": PIN, "notAfter": "2053-05-19T06:00:00"}]}}
    info.update(changes)
    return info


def check(info, **kwargs):
    kwargs.setdefault("signed", True)
    kwargs.setdefault("expected", ("1.10", 17))
    kwargs.setdefault("now", NOW)
    return release.evaluate(info, **kwargs)


class ParserTests(unittest.TestCase):
    def test_badging(self):
        self.assertEqual(
            {"package": "com.meteor.app", "versionCode": 17, "versionName": "1.10", "minSdk": 31, "targetSdk": 36},
            release.parse_badging(BADGING))
        with self.assertRaises(release.ReleaseError):
            release.parse_badging("not an apk")

    def test_manifest_flags(self):
        tree = ("A: http://schemas.android.com/apk/res/android:debuggable(0x0101000f)=true\n"
                "A: http://schemas.android.com/apk/res/android:testOnly(0x01010272)=(type 0x12)0xffffffff\n"
                "A: http://schemas.android.com/apk/res/android:allowBackup(0x01010280)=false\n")
        self.assertTrue(release.manifest_flag(tree, "debuggable"))
        self.assertTrue(release.manifest_flag(tree, "testOnly"))
        self.assertFalse(release.manifest_flag(tree, "allowBackup"))
        self.assertFalse(release.manifest_flag("A: package=\"com.meteor.app\"", "debuggable"))

    def test_certificate_expiry_both_time_encodings(self):
        self.assertEqual(dt.datetime(2053, 5, 19, 6), release.cert_not_after(base64.b64decode(LONG_PEM)))  # GeneralizedTime
        self.assertEqual(dt.datetime(2034, 3, 20, 6), release.cert_not_after(base64.b64decode(SHORT_PEM)))  # UTCTime

    def test_apksigner_output(self):
        parsed = release.parse_apksigner(APKSIGNER.format(pem=LONG_PEM))
        self.assertTrue(parsed["schemes"]["v3"])
        self.assertFalse(parsed["schemes"]["v2"])
        self.assertEqual([{"dn": "CN=Meteor Test Only", "sha256": PIN, "notAfter": "2053-05-19T06:00:00"}],
                         parsed["signers"])

    def test_unsigned_apksigner_output_has_no_signers(self):
        parsed = release.parse_apksigner("ERROR: Missing META-INF/MANIFEST.MF\n")
        self.assertEqual({"schemes": {}, "signers": []}, parsed)

    def test_apksigner_sdk_range_labels_and_repeated_certificate(self):
        text = APKSIGNER.format(pem=LONG_PEM)
        first = text.replace("Signer #1", "Signer (minSdkVersion=28, maxSdkVersion=2147483647)")
        second = text[text.index("Signer #1 certificate DN:"):].replace(
            "Signer #1", "Signer (minSdkVersion=33 (dev release=true), maxSdkVersion=2147483647)")
        parsed = release.parse_apksigner(first + second)
        self.assertEqual(release.parse_apksigner(text), parsed)

    def test_apksigner_build_tools_37_labels(self):
        text = APKSIGNER.format(pem=LONG_PEM)
        current = text.replace("Signer #1", "V3.0 Signer:")
        duplicate = text[text.index("Signer #1 certificate DN:"):].replace("Signer #1", "V3.1 Signer:")
        stamp = text[text.index("Signer #1 certificate DN:"):].replace("Signer #1", "Source Stamp Signer:")
        self.assertEqual(release.parse_apksigner(text), release.parse_apksigner(current + duplicate + stamp))

    def test_apksigner_different_sdk_range_signers_remain_distinct(self):
        first = APKSIGNER.format(pem=LONG_PEM).replace(
            "Signer #1", "Signer (minSdkVersion=28, maxSdkVersion=32)")
        second = APKSIGNER.format(pem=SHORT_PEM).replace(
            "Signer #1", "Signer (minSdkVersion=33, maxSdkVersion=2147483647)").replace(PIN.upper(), "A" * 64)
        self.assertEqual(2, len(release.parse_apksigner(first + second)["signers"]))

    def test_elf_alignment(self):
        def elf(align):
            header = bytearray(64)
            header[:6] = b"\x7fELF\x02\x01"
            struct.pack_into("<Q", header, 0x20, 64)          # program headers follow the header
            struct.pack_into("<HH", header, 0x36, 56, 1)      # one 56-byte entry
            entry = bytearray(56)
            struct.pack_into("<I", entry, 0, 1)               # PT_LOAD
            struct.pack_into("<Q", entry, 48, align)
            return bytes(header + entry)
        self.assertEqual(0x1000, release.elf_load_align(elf(0x1000)))
        self.assertEqual(0x4000, release.elf_load_align(elf(0x4000)))
        self.assertIsNone(release.elf_load_align(b"\x7fELF\x01\x01" + bytes(60)))  # 32-bit ELF is not checked
        self.assertIsNone(release.elf_load_align(b"not elf"))


class GateTests(unittest.TestCase):
    def test_good_release_passes(self):
        self.assertEqual([], check(good_info(), pin=PIN, require_pin=True))

    def test_each_defect_blocks(self):
        cases = {
            "package": good_info(package="com.meteor.app.debug"),
            "version name": good_info(versionName="1.9"),
            "version code": good_info(versionCode=16),
            "debuggable": good_info(debuggable=True),
            "testOnly": good_info(testOnly=True),
            "alignment": good_info(zipAligned16k=False),
            "native libs": good_info(nativeLibsMisaligned=["lib/arm64-v8a/x.so"]),
        }
        for label, info in cases.items():
            with self.subTest(label):
                self.assertTrue(check(info, pin=PIN, require_pin=True), label)

    def test_signer_rules(self):
        def signer(**changes):
            info = good_info()
            info["signature"] = {**info["signature"], "signers": [{**info["signature"]["signers"][0], **changes}]}
            return info
        self.assertTrue(check(signer(sha256="0" * 64), pin=PIN))                       # pin mismatch
        self.assertTrue(check(good_info(), require_pin=True))                          # no pin configured
        self.assertEqual([], check(good_info()))                                       # pin optional when not required
        self.assertTrue(check(signer(dn="C=US, O=Android, CN=Android Debug")))         # debug certificate
        self.assertTrue(check(signer(notAfter="2030-01-01T00:00:00")))                 # expires before Play's cut-off
        self.assertTrue(check(signer(notAfter="2040-01-01T00:00:00")))                 # under 25 years
        self.assertEqual([], check(signer(notAfter="2040-01-01T00:00:00"), min_cert_years=0))
        two = good_info()
        two["signature"]["signers"] = two["signature"]["signers"] * 2
        self.assertTrue(check(two))
        unverified = good_info()
        unverified["signature"]["verified"] = False
        self.assertTrue(check(unverified))
        no_scheme = good_info()
        no_scheme["signature"]["schemes"] = {"v1": True}
        self.assertTrue(check(no_scheme))

    def test_unsigned_build_must_carry_no_signature(self):
        unsigned = good_info(signature={"verified": False, "schemes": {}, "signers": []})
        self.assertEqual([], check(unsigned, signed=False))
        self.assertTrue(check(good_info(), signed=False))


class FileTests(unittest.TestCase):
    CHANGELOG = "# Change history\n\n## 1.10 — Android delivery 17\n\nBody ten.\n\n## 1.1\n\nBody one.\n\n## 1.0\n\nOld.\n"

    def test_changelog_section_is_exact(self):
        self.assertEqual("Body ten.", release.changelog_section(self.CHANGELOG, "1.10"))
        self.assertEqual("Body one.", release.changelog_section(self.CHANGELOG, "1.1"))
        self.assertEqual("Old.", release.changelog_section(self.CHANGELOG, "1.0"))
        with self.assertRaises(release.ReleaseError):
            release.changelog_section(self.CHANGELOG, "1.2")
        with self.assertRaises(release.ReleaseError):
            release.changelog_section("## 2.0\n\n## 1.9\nx\n", "2.0")

    def test_pin_file(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "pin"
            self.assertIsNone(release.read_pin(path))
            path.write_text("# only comments\n\n", encoding="utf-8")
            self.assertIsNone(release.read_pin(path))
            path.write_text(f"# note\n{PIN.upper()}  # inline\n", encoding="utf-8")
            self.assertEqual(PIN, release.read_pin(path))
            path.write_text(":".join(PIN[i:i + 2] for i in range(0, 64, 2)) + "\n", encoding="utf-8")
            self.assertEqual(PIN, release.read_pin(path))
            path.write_text("deadbeef\n", encoding="utf-8")
            with self.assertRaises(release.ReleaseError):
                release.read_pin(path)

    def test_tracked_pin_file_is_well_formed(self):
        release.read_pin(release.PIN_FILE)  # raises if someone commits a malformed fingerprint

    def test_tag_must_match_version_properties(self):
        name, _ = release.expected_version()
        with contextlib.redirect_stdout(io.StringIO()):
            release.cmd_check_tag(mock.Mock(tag=f"v{name}"))
        for bad in (name, f"v{name}.1", "v0.0", f"V{name}"):
            with self.assertRaises(release.ReleaseError):
                release.cmd_check_tag(mock.Mock(tag=bad))

    def test_notes_carry_the_facts_a_user_needs(self):
        notes = release.render_notes("Changes.", name="1.10", code=17, apk_name="Meteor-1.10.apk", apk_sha256="a" * 64,
                                     signer=PIN, commit="c0ffee", repo="owner/Meteor")
        for needle in ("Changes.", "Meteor-1.10.apk", "a" * 64, PIN, "c0ffee", "17",
                       "https://github.com/owner/Meteor#download-and-install", "Do not uninstall first"):
            self.assertIn(needle, notes)

    def test_passwords_are_never_accepted_inline(self):
        tools = {"apksigner": "unused"}
        for bad in ("pass:secret", "secret", "stdin"):
            with self.assertRaises(release.ReleaseError):
                release.sign_apk(tools, "in.apk", "out.apk", "ks.p12", "alias", bad)
        with self.assertRaises(release.ReleaseError):
            release.sign_apk(tools, "in.apk", "out.apk", "ks.p12", "alias", "env:OK", "pass:secret")


class AssembleGuardTests(unittest.TestCase):
    """The checks inside 'assemble' that a well-behaved apksigner never trips, driven with stand-ins."""

    def run_assemble(self, after_changes):
        name, code = release.expected_version()
        unsigned = good_info(versionName=name, versionCode=code, sha256="a" * 64, entries={"AndroidManifest.xml": 1},
                             signature={"verified": False, "schemes": {}, "signers": []})
        signed = good_info(versionName=name, versionCode=code, sha256="b" * 64, entries={"AndroidManifest.xml": 1})
        signed.update(after_changes)
        with tempfile.TemporaryDirectory() as folder:
            out = Path(folder) / "out"
            argv = ["assemble", "--unsigned", "unsigned.apk", "--keystore", "ks", "--alias", "key",
                    "--ks-pass", "env:X", "--out-dir", str(out), "--allow-unpinned"]
            sign = mock.Mock(side_effect=lambda tools, src, dst, *rest: Path(dst).write_bytes(b"signed"))
            err = io.StringIO()
            with mock.patch.object(release, "find_tools", return_value={}), \
                    mock.patch.object(release, "describe", side_effect=[unsigned, signed]), \
                    mock.patch.object(release, "sign_apk", sign), \
                    contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(err):
                code = release.main(argv)
            return code, err.getvalue(), sorted(p.name for p in out.glob("*")) if out.exists() else []

    def test_untouched_contents_are_released(self):
        code, _, files = self.run_assemble({})
        self.assertEqual(0, code)
        self.assertIn("SHA256SUMS.txt", files)

    def test_signing_that_changes_contents_is_refused_and_leaves_nothing(self):
        code, err, files = self.run_assemble({"entries": {"AndroidManifest.xml": 2}})
        self.assertEqual(1, code)
        self.assertIn("changed the APK's contents", err)
        self.assertEqual([], files)


def sdk_tools():
    try:
        return release.find_tools()
    except release.ReleaseError:
        return None


@unittest.skipUnless(sdk_tools() and shutil.which("keytool"), "needs Android build-tools and a JDK")
class ToolchainTests(unittest.TestCase):
    """Drive the real apksigner, aapt2 and zipalign against a synthetic APK."""

    @classmethod
    def setUpClass(cls):
        cls.tools = sdk_tools()
        cls.tmp = Path(tempfile.mkdtemp(prefix="meteor-release-test-"))
        cls.addClassCleanup(shutil.rmtree, cls.tmp, ignore_errors=True)
        sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
        jars = sorted((jar for jar in sdk.glob("platforms/android-*/android.jar")
                       if re.fullmatch(r"android-\d+(\.\d+)?", jar.parent.name)),
                      key=lambda jar: tuple(int(n) for n in re.findall(r"\d+", jar.parent.name)))
        if not jars:
            raise unittest.SkipTest("no released Android platform android.jar installed")
        cls.android_jar = jars[-1]
        cls.name, cls.code = release.expected_version()
        cls.good = cls.make_apk("good")
        cls.env = {"METEOR_TEST_STORE_PW": "test-store-password"}
        cls.release_key = cls.make_key("release", "CN=Meteor Test Only", 10000)

    @classmethod
    def make_apk(cls, label, *, package="com.meteor.app", attributes=""):
        manifest = cls.tmp / f"{label}.xml"
        manifest.write_text(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            f'<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="{package}">'
            f'<application {attributes}/></manifest>', encoding="utf-8")
        raw = cls.tmp / f"{label}-raw.apk"
        done = subprocess.run(
            [cls.tools["aapt2"], "link", "-o", str(raw), "--manifest", str(manifest), "-I", str(cls.android_jar),
             "--min-sdk-version", "31", "--target-sdk-version", "36",
             "--version-code", str(cls.code), "--version-name", cls.name], capture_output=True, text=True)
        assert done.returncode == 0, done.stderr
        aligned = cls.tmp / f"{label}.apk"
        done = subprocess.run([cls.tools["zipalign"], "-f", "-P", "16", "4", str(raw), str(aligned)],
                              capture_output=True, text=True)
        assert done.returncode == 0, done.stderr
        return aligned

    @classmethod
    def make_key(cls, label, dn, days):
        path = cls.tmp / f"{label}.p12"
        done = subprocess.run(
            ["keytool", "-genkeypair", "-storetype", "PKCS12", "-keystore", str(path), "-alias", "key",
             "-keyalg", "EC", "-groupname", "secp256r1", "-dname", dn, "-validity", str(days),
             "-storepass", "test-store-password", "-keypass", "test-store-password"], capture_output=True, text=True)
        assert done.returncode == 0, done.stderr
        return path

    def run_cli(self, *argv):
        out, err = io.StringIO(), io.StringIO()
        with mock.patch.dict(os.environ, self.env), contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = release.main(["--build-tools", str(Path(self.tools["aapt2"]).parent), *map(str, argv)])
        return code, out.getvalue(), err.getvalue()

    def assemble(self, key, out_dir, *extra, unsigned=None):
        return self.run_cli("assemble", "--unsigned", unsigned or self.good, "--keystore", key, "--alias", "key",
                            "--ks-pass", "env:METEOR_TEST_STORE_PW", "--out-dir", out_dir,
                            "--pin-file", self.tmp / "no-such-pin", *extra)

    def test_real_apksigner_output_parses(self):
        signed = self.tmp / "parser-probe.apk"
        with mock.patch.dict(os.environ, self.env):
            release.sign_apk(self.tools, self.good, signed, self.release_key, "key", "env:METEOR_TEST_STORE_PW")
        verdict = release.run([self.tools["apksigner"], "verify", "--verbose", "--print-certs-pem", signed])
        self.assertEqual(0, verdict.returncode, verdict.stderr)
        self.assertEqual(1, len(release.parse_apksigner(verdict.stdout)["signers"]),
                         f"{self.tools['apksigner']} output:\n{verdict.stdout}\n{verdict.stderr}")

    def test_unsigned_build_is_accepted_and_defects_are_not(self):
        self.assertEqual(0, self.run_cli("verify", "--unsigned", self.good)[0])
        for label, kwargs in {"debuggable": {"attributes": 'android:debuggable="true"'},
                              "testonly": {"attributes": 'android:testOnly="true"'},
                              "package": {"package": "com.meteor.app.debug"}}.items():
            with self.subTest(label):
                code, _, err = self.run_cli("verify", "--unsigned", self.make_apk(label, **kwargs))
                self.assertEqual(1, code)
                self.assertIn("RELEASE BLOCKED", err)

    def test_assemble_signs_pins_and_packages(self):
        out = self.tmp / "ok"
        self.assertEqual(1, self.assemble(self.release_key, out)[0], "an unpinned signer must not be released")
        self.assertEqual([], list(out.glob("*.apk")), "a rejected release leaves no APK behind")
        code, stdout, err = self.assemble(self.release_key, out, "--allow-unpinned")
        self.assertEqual(0, code, err)
        apk = out / f"Meteor-{self.name}.apk"
        self.assertTrue(apk.is_file())
        sums = (out / "SHA256SUMS.txt").read_text(encoding="utf-8")
        self.assertEqual(f"{release.sha256_file(apk)}  {apk.name}\n", sums)
        info = release.describe(apk, self.tools)
        signer = info["signature"]["signers"][0]["sha256"]
        self.assertIn(signer, (out / "RELEASE-NOTES.md").read_text(encoding="utf-8"))
        self.assertEqual(self.good_entries(), info["entries"], "signing must not change the APK's contents")
        pin = self.tmp / "pin"
        pin.write_text(f"# test pin\n{signer}\n", encoding="utf-8")
        self.assertEqual(0, self.run_cli("verify", apk, "--pin-file", pin, "--require-pin")[0])
        pin.write_text("0" * 64 + "\n", encoding="utf-8")
        self.assertEqual(1, self.run_cli("verify", apk, "--pin-file", pin, "--require-pin")[0])
        self.assertEqual(1, self.run_cli("verify", apk, "--unsigned")[0], "a signed file is not the unsigned build")

    def good_entries(self):
        with zipfile.ZipFile(self.good) as archive:
            return {entry.filename: entry.CRC for entry in archive.infolist()}

    def test_wrong_keys_are_refused(self):
        debug = self.make_key("debugish", "C=US, O=Android, CN=Android Debug", 10000)
        short = self.make_key("short", "CN=Meteor Test Only", 3000)
        for label, key in {"debug": debug, "short": short}.items():
            with self.subTest(label):
                out = self.tmp / f"bad-{label}"
                code, _, err = self.assemble(key, out, "--allow-unpinned")
                self.assertEqual(1, code)
                self.assertIn("RELEASE BLOCKED", err)
                self.assertEqual([], list(out.glob("*.apk")))

    def test_tampered_signed_apk_is_refused(self):
        out = self.tmp / "tamper"
        self.assertEqual(0, self.assemble(self.release_key, out, "--allow-unpinned")[0])
        apk = out / f"Meteor-{self.name}.apk"
        data = bytearray(apk.read_bytes())
        data[len(data) // 4] ^= 0xFF
        apk.write_bytes(bytes(data))
        code, _, err = self.run_cli("verify", apk)
        self.assertEqual(1, code)
        self.assertIn("RELEASE BLOCKED", err)

    def test_build_info_must_match_the_unsigned_file(self):
        info = self.tmp / "build-info.json"
        self.assertEqual(0, self.run_cli("inspect", self.good, "--commit", "c0ffee", "--out", info)[0])
        out = self.tmp / "bound"
        self.assertEqual(0, self.assemble(self.release_key, out, "--allow-unpinned", "--build-info", info)[0])
        self.assertIn("c0ffee", (out / "RELEASE-NOTES.md").read_text(encoding="utf-8"))
        other = self.make_apk("other", attributes='android:label="x"')
        code, _, err = self.assemble(self.release_key, self.tmp / "unbound", "--allow-unpinned",
                                     "--build-info", info, unsigned=other)
        self.assertEqual(1, code)
        self.assertIn("does not match the digest", err)


if __name__ == "__main__":
    unittest.main()
