"""Verify, sign and package the Meteor release APK.

Standard library only. It drives the Android SDK build-tools (apksigner, aapt2,
zipalign) and never takes a password on the command line: apksigner reads them
from an environment variable (env:NAME) or a file (file:PATH).

  check-tag TAG       the tag must be v + versionName from version.properties
  inspect APK         print what the APK says about itself, as JSON
  verify APK          fail unless the APK is a shippable release (--unsigned for the CI build)
  assemble            sign an unsigned build, verify the result, write checksums and notes

The same code path runs in GitHub Actions and on a maintainer's computer, so a
release signed offline is held to exactly the checks a CI-signed one is.
"""
import argparse
import base64
import datetime as dt
import hashlib
import json
import os
import re
import struct
import subprocess
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))  # sibling version.py, also under python -I
import version as product_version  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
PACKAGE = "com.meteor.app"
PIN_FILE = ROOT / "release-signer.sha256"
MIN_CERT_YEARS = 25
# Android's signing guide: a key that must outlive the app should be valid for
# 25 years or more, and Google Play wants validity ending after 22 October 2033.
PLAY_MIN_EXPIRY = dt.datetime(2033, 10, 23)
ELF_PAGE_ALIGN = 0x4000  # 16 KB pages: Android 15+ devices may use them
SECRET_REF = re.compile(r"^(env:[A-Za-z_][A-Za-z0-9_]*|file:.+)$")


class ReleaseError(Exception):
    """A condition that must stop the release; the message is safe to print."""


# ---------------------------------------------------------------- SDK tools

def find_tools(build_tools=None, environ=os.environ):
    """Locate apksigner, aapt2 and zipalign in the newest installed build-tools."""
    if build_tools:
        candidates = [Path(build_tools)]
    else:
        sdk = environ.get("ANDROID_HOME") or environ.get("ANDROID_SDK_ROOT")
        if not sdk:
            raise ReleaseError("Set ANDROID_HOME (or pass --build-tools) so apksigner can be found")
        base = Path(sdk) / "build-tools"
        found = [p for p in base.iterdir() if p.is_dir()] if base.is_dir() else []
        candidates = sorted(found, key=lambda p: tuple(int(n) for n in re.findall(r"\d+", p.name)), reverse=True)
    suffixes = {"apksigner": (".bat",), "aapt2": (".exe",), "zipalign": (".exe",)} if os.name == "nt" else {}
    for directory in candidates:
        tools = {}
        for name in ("apksigner", "aapt2", "zipalign"):
            for suffix in suffixes.get(name, ("",)):
                path = directory / (name + suffix)
                if path.is_file():
                    tools[name] = str(path)
        if len(tools) == 3:
            return tools
    raise ReleaseError("No Android build-tools directory with apksigner, aapt2 and zipalign was found")


def run(args):
    return subprocess.run([str(a) for a in args], capture_output=True, text=True, encoding="utf-8", errors="replace")


# ----------------------------------------------------------- output parsers

def parse_badging(text):
    package = re.search(r"^package: name='([^']*)' versionCode='([^']*)' versionName='([^']*)'", text, re.M)
    if not package:
        raise ReleaseError("aapt2 reported no package line; is this an APK?")

    def sdk(key):
        found = re.search(rf"^{key}:'(\d+)'", text, re.M)
        return int(found.group(1)) if found else None

    code = package.group(2)
    return {"package": package.group(1), "versionCode": int(code) if code.isdigit() else None,
            "versionName": package.group(3), "minSdk": sdk("minSdkVersion"), "targetSdk": sdk("targetSdkVersion")}


def manifest_flag(xmltree, attribute):
    """True when the manifest sets android:<attribute> to true (aapt2 prints it two ways)."""
    for found in re.finditer(rf"android:{attribute}\(0x[0-9a-fA-F]+\)=(.*)$", xmltree, re.M):
        value = found.group(1).strip()
        if value == "true" or value.lower().endswith("0xffffffff"):
            return True
    return False


def parse_apksigner(text):
    schemes = {m.group(1): m.group(2) == "true"
               for m in re.finditer(r"^Verified using (v[\d.]+) scheme[^:]*: (true|false)", text, re.M)}
    signers = {}
    # Build-tools 37 uses "V3.0 Signer:"; other versions use SDK ranges
    # or "Signer #1". Source Stamp Signer is deliberately excluded.
    # Preserve output order so each PEM remains paired with its own certificate.
    for found in re.finditer(
            r"^((?:Signer (?:#\d+|\(minSdkVersion=[^\r\n]+\))|V\d+(?:\.\d+)? Signer(?: #\d+)?):?) certificate (DN|SHA-256 digest): (.*)$",
            text, re.M):
        signers.setdefault(found.group(1), {})[found.group(2)] = found.group(3).strip()
    pems = re.findall(r"-----BEGIN CERTIFICATE-----(.*?)-----END CERTIFICATE-----", text, re.S)
    ordered = []
    for row in signers.values():
        ordered.append({"dn": row.get("DN", ""), "sha256": row.get("SHA-256 digest", "").lower().replace(":", "")})
    for row, pem in zip(ordered, pems):
        row["notAfter"] = cert_not_after(base64.b64decode("".join(pem.split()))).isoformat()
    # v3 and v3.1 can report the same certificate for separate SDK ranges.
    unique = []
    for row in ordered:
        if row not in unique:
            unique.append(row)
    return {"schemes": schemes, "signers": unique}


def _der_children(buf, start, end):
    """Yield (tag, value_start, value_end) for each DER element between start and end."""
    pos = start
    while pos < end:
        tag, first = buf[pos], buf[pos + 1]
        pos += 2
        if first & 0x80:
            count = first & 0x7F
            length = int.from_bytes(buf[pos:pos + count], "big")
            pos += count
        else:
            length = first
        yield tag, pos, pos + length
        pos += length


def cert_not_after(der):
    """Expiry (UTC, naive) of an X.509 certificate, read from its DER encoding.

    Certificate = SEQUENCE { TBSCertificate = SEQUENCE { [0] version?, serial,
    signature algorithm, issuer, validity = SEQUENCE { notBefore, notAfter }, ... }, ... }
    """
    _, start, end = next(_der_children(der, 0, len(der)))
    _, start, end = next(_der_children(der, start, end))
    fields = list(_der_children(der, start, end))
    if fields and fields[0][0] == 0xA0:
        fields = fields[1:]
    if len(fields) < 4:
        raise ReleaseError("Could not read the signer certificate")
    _, start, end = fields[3]
    times = list(_der_children(der, start, end))
    tag, start, end = times[1]
    text = der[start:end].decode("ascii")
    if tag == 0x17:  # UTCTime YYMMDDHHMMSSZ
        return dt.datetime.strptime(("20" if int(text[:2]) < 50 else "19") + text, "%Y%m%d%H%M%SZ")
    return dt.datetime.strptime(text, "%Y%m%d%H%M%SZ")  # GeneralizedTime


def elf_load_align(data):
    """Smallest p_align among PT_LOAD segments of a 64-bit ELF, or None if not 64-bit ELF."""
    if data[:4] != b"\x7fELF" or data[4] != 2:
        return None
    endian = "<" if data[5] == 1 else ">"
    (phoff,) = struct.unpack_from(endian + "Q", data, 0x20)
    phentsize, phnum = struct.unpack_from(endian + "HH", data, 0x36)
    smallest = None
    for index in range(phnum):
        entry = phoff + index * phentsize
        (kind,) = struct.unpack_from(endian + "I", data, entry)
        if kind == 1:
            (align,) = struct.unpack_from(endian + "Q", data, entry + 48)
            smallest = align if smallest is None else min(smallest, align)
    return smallest


# ----------------------------------------------------------- APK inspection

def sha256_file(path):
    digest = hashlib.sha256()
    with open(path, "rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def describe(apk, tools):
    """Collect everything the checks need. Does not judge; see evaluate()."""
    apk = Path(apk)
    if not apk.is_file():
        raise ReleaseError(f"{apk.name} does not exist")
    badging = run([tools["aapt2"], "dump", "badging", apk])
    if badging.returncode:
        raise ReleaseError("aapt2 could not read the APK")
    tree = run([tools["aapt2"], "dump", "xmltree", "--file", "AndroidManifest.xml", apk]).stdout
    info = parse_badging(badging.stdout)
    info.update({
        "file": apk.name,
        "bytes": apk.stat().st_size,
        "sha256": sha256_file(apk),
        "debuggable": "application-debuggable" in badging.stdout or manifest_flag(tree, "debuggable"),
        "testOnly": manifest_flag(tree, "testOnly"),
        "zipAligned16k": run([tools["zipalign"], "-c", "-P", "16", "4", apk]).returncode == 0,
    })
    misaligned = []
    with zipfile.ZipFile(apk) as archive:
        if archive.testzip() is not None:
            raise ReleaseError("The APK is a damaged zip archive")
        info["entries"] = {entry.filename: entry.CRC for entry in archive.infolist()}
        for entry in archive.infolist():
            if re.fullmatch(r"lib/(arm64-v8a|x86_64)/[^/]+\.so", entry.filename):
                align = elf_load_align(archive.read(entry))
                if align is not None and align < ELF_PAGE_ALIGN:
                    misaligned.append(entry.filename)
    info["nativeLibsMisaligned"] = misaligned
    verdict = run([tools["apksigner"], "verify", "--verbose", "--print-certs-pem", apk])
    signature = parse_apksigner(verdict.stdout)
    signature["verified"] = verdict.returncode == 0
    info["signature"] = signature
    return info


def expected_version(root=ROOT):
    return product_version.parse((root / "version.properties").read_text(encoding="utf-8"))


def read_pin(path):
    """The pinned signer SHA-256 (lowercase hex), or None while the file holds only comments."""
    path = Path(path)
    if not path.is_file():
        return None
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.split("#", 1)[0].strip().replace(":", "").lower()
        if not line:
            continue
        if not re.fullmatch(r"[0-9a-f]{64}", line):
            raise ReleaseError(f"{path.name} must hold one SHA-256 fingerprint (64 hex digits) and comments")
        return line
    return None


def evaluate(info, *, signed, expected, package=PACKAGE, pin=None, require_pin=False,
             min_cert_years=MIN_CERT_YEARS, now=None):
    """Return the list of reasons this APK must not ship (empty means it may)."""
    problems = []
    name, code = expected
    if info["package"] != package:
        problems.append(f"package is {info['package']}, expected {package}")
    if info["versionName"] != name or info["versionCode"] != code:
        problems.append(f"version is {info['versionName']} ({info['versionCode']}), "
                        f"version.properties says {name} ({code})")
    if info["debuggable"]:
        problems.append("the APK is debuggable")
    if info["testOnly"]:
        problems.append("the APK is marked testOnly")
    if not info["zipAligned16k"]:
        problems.append("the APK is not zip-aligned for 16 KB pages")
    if info["nativeLibsMisaligned"]:
        problems.append("64-bit native libraries not aligned to 16 KB: " + ", ".join(info["nativeLibsMisaligned"]))
    signature = info["signature"]
    if not signed:
        if signature["verified"] or signature["signers"]:
            problems.append("the build output is already signed; the release key must be the only signature")
        return problems
    if not signature["verified"]:
        problems.append("apksigner does not verify the signature")
        return problems
    if not (signature["schemes"].get("v2") or signature["schemes"].get("v3")):
        problems.append("the APK has no v2 or v3 signature")
    if len(signature["signers"]) != 1:
        problems.append(f"expected exactly one signer, found {len(signature['signers'])}")
        return problems
    signer = signature["signers"][0]
    if "CN=Android Debug" in signer["dn"]:
        problems.append("the APK is signed with an Android debug certificate")
    if pin:
        if signer["sha256"] != pin:
            problems.append("the signer certificate does not match the pinned fingerprint")
    elif require_pin:
        problems.append("no signer fingerprint is pinned (fill release-signer.sha256 first)")
    now = now or dt.datetime.now(dt.timezone.utc).replace(tzinfo=None)
    not_after = dt.datetime.fromisoformat(signer["notAfter"]) if signer.get("notAfter") else None
    if not_after is None:
        problems.append("could not read the signer certificate's expiry")
    else:
        if not_after < PLAY_MIN_EXPIRY:
            problems.append(f"the signer certificate expires {not_after:%Y-%m-%d}, before 2033-10-23")
        if min_cert_years and not_after < now + dt.timedelta(days=365 * min_cert_years):
            problems.append(f"the signer certificate is valid for under {min_cert_years} years (expires {not_after:%Y-%m-%d})")
    return problems


# ------------------------------------------------------------------ signing

def sign_apk(tools, unsigned, out, keystore, alias, ks_pass, key_pass=None):
    """Sign with the release key. key_pass=None (or equal to ks_pass) reuses the keystore password.

    The two references are not both passed when they are equal: apksigner reads a
    file: reference line by line, so naming one file twice would ask for two lines.
    """
    references = [ks_pass] + ([key_pass] if key_pass and key_pass != ks_pass else [])
    for reference in references:
        if not SECRET_REF.match(reference):
            raise ReleaseError("Passwords must be given as env:NAME or file:PATH, never inline")
    command = [tools["apksigner"], "sign", "--ks", keystore, "--ks-key-alias", alias, "--ks-pass", ks_pass]
    if len(references) == 2:
        command += ["--key-pass", key_pass]
    command += ["--v1-signing-enabled", "false", "--v2-signing-enabled", "true",
                "--v3-signing-enabled", "true", "--v4-signing-enabled", "false", "--out", out, unsigned]
    result = run(command)
    if result.returncode:
        detail = (result.stderr.strip().splitlines() or ["no detail"])[0]
        raise ReleaseError(f"apksigner could not sign the APK: {detail}")


# ------------------------------------------------------------ release notes

def changelog_section(text, name):
    found = re.search(rf"^## {re.escape(name)}(?![0-9.])[^\n]*\n", text, re.M)
    if not found:
        raise ReleaseError(f"CHANGELOG.md has no '## {name}' section; write the release notes first")
    rest = text[found.end():]
    following = re.search(r"^## ", rest, re.M)
    body = (rest[:following.start()] if following else rest).strip()
    if not body:
        raise ReleaseError(f"The CHANGELOG.md section for {name} is empty")
    return body


def render_notes(changelog, *, name, code, apk_name, apk_sha256, signer, unsigned_sha256=None,
                 commit=None, run_url=None, repo=None):
    readme = f"https://github.com/{repo}#download-and-install" if repo else "the README"
    rows = [("Version", f"{name} (Android versionCode {code})"), ("Package", f"`{PACKAGE}`"),
            ("Signer certificate SHA-256", f"`{signer}`"), (f"`{apk_name}` SHA-256", f"`{apk_sha256}`")]
    if commit:
        rows.append(("Source commit", f"`{commit}`"))
    if run_url:
        rows.append(("Build", run_url))
    if unsigned_sha256:
        rows.append(("Unsigned build SHA-256", f"`{unsigned_sha256}`"))
    table = "\n".join(["| | |", "|---|---|"] + [f"| {key} | {value} |" for key, value in rows])
    return (f"{changelog}\n\n## Install\n\n"
            f"Download `{apk_name}` from the assets below on an Android 12 or newer phone and open it. "
            "Allow *Install unknown apps* for your browser or file manager when Android asks. "
            "To update, install the new APK over the old one: it carries the same signature, so your "
            "saved connections stay. Do not uninstall first. "
            f"Step-by-step instructions are in {readme}.\n\n"
            f"## Verify\n\n{table}\n\n"
            "Check the file with `sha256sum -c SHA256SUMS.txt` and the signature with "
            f"`apksigner verify --print-certs {apk_name}`; the certificate digest must equal the value above.\n")


# ----------------------------------------------------------------- commands

def cmd_check_tag(args):
    name, _ = expected_version()
    if args.tag != f"v{name}":
        raise ReleaseError(f"Tag {args.tag} does not match version.properties (expected v{name})")
    changelog_section((ROOT / "CHANGELOG.md").read_text(encoding="utf-8"), name)
    print(json.dumps({"tag": args.tag, "versionName": name}))


def _summary(info, **extra):
    keys = ("file", "bytes", "sha256", "package", "versionName", "versionCode", "minSdk", "targetSdk",
            "debuggable", "testOnly", "zipAligned16k")
    out = {key: info[key] for key in keys}
    out["signers"] = [{"sha256": s["sha256"], "dn": s["dn"], "notAfter": s.get("notAfter")}
                      for s in info["signature"]["signers"]]
    out["signatureVerified"] = info["signature"]["verified"]
    out.update({k: v for k, v in extra.items() if v})
    return out


def cmd_inspect(args):
    info = describe(args.apk, find_tools(args.build_tools))
    text = json.dumps(_summary(info, commit=args.commit, runUrl=args.run_url), indent=2)
    if args.out:
        Path(args.out).write_text(text + "\n", encoding="utf-8")
    print(text)


def cmd_verify(args):
    tools = find_tools(args.build_tools)
    info = describe(args.apk, tools)
    problems = evaluate(info, signed=not args.unsigned, expected=expected_version(), package=args.package,
                        pin=read_pin(args.pin_file), require_pin=args.require_pin,
                        min_cert_years=args.min_cert_years)
    print(json.dumps(_summary(info), indent=2))
    if problems:
        raise ReleaseError("; ".join(problems))


def cmd_assemble(args):
    tools = find_tools(args.build_tools)
    expected = expected_version()
    name, code = expected
    unsigned = Path(args.unsigned)
    before = describe(unsigned, tools)
    problems = evaluate(before, signed=False, expected=expected)
    if problems:
        raise ReleaseError("Unsigned build rejected: " + "; ".join(problems))
    build = json.loads(Path(args.build_info).read_text(encoding="utf-8")) if args.build_info else {}
    if build and build.get("sha256") != before["sha256"]:
        raise ReleaseError("The unsigned APK does not match the digest recorded when it was built")
    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    apk = out_dir / f"Meteor-{name}.apk"
    if apk.exists():
        raise ReleaseError(f"{apk.name} already exists in {out_dir}; use an empty folder")
    sign_apk(tools, unsigned, apk, args.keystore, args.alias, args.ks_pass, args.key_pass)
    try:
        after = describe(apk, tools)
        problems = evaluate(after, signed=True, expected=expected, pin=read_pin(args.pin_file),
                            require_pin=not args.allow_unpinned, min_cert_years=args.min_cert_years)
        if after["entries"] != before["entries"]:
            problems.append("signing changed the APK's contents")
    except Exception:
        apk.unlink(missing_ok=True)
        raise
    if problems:
        apk.unlink(missing_ok=True)
        raise ReleaseError("Signed APK rejected: " + "; ".join(problems))
    signer = after["signature"]["signers"][0]["sha256"]
    (out_dir / "SHA256SUMS.txt").write_text(f"{after['sha256']}  {apk.name}\n", encoding="utf-8", newline="\n")
    notes = render_notes(changelog_section((ROOT / "CHANGELOG.md").read_text(encoding="utf-8"), name),
                         name=name, code=code, apk_name=apk.name, apk_sha256=after["sha256"], signer=signer,
                         unsigned_sha256=before["sha256"], commit=build.get("commit"),
                         run_url=build.get("runUrl"), repo=args.repo)
    (out_dir / "RELEASE-NOTES.md").write_text(notes, encoding="utf-8", newline="\n")
    result = {"apk": str(apk), "version": name, "sha256": after["sha256"], "signer": signer,
              "pinned": bool(read_pin(args.pin_file))}
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as handle:
            for key, value in result.items():
                handle.write(f"{key}={str(value).lower() if isinstance(value, bool) else value}\n")
    print(json.dumps(result, indent=2))


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--build-tools", help="Android build-tools directory (default: newest under ANDROID_HOME)")
    sub = parser.add_subparsers(dest="command", required=True)
    tag = sub.add_parser("check-tag")
    tag.add_argument("tag")
    tag.set_defaults(func=cmd_check_tag)
    inspect = sub.add_parser("inspect")
    inspect.add_argument("apk")
    inspect.add_argument("--commit")
    inspect.add_argument("--run-url")
    inspect.add_argument("--out")
    inspect.set_defaults(func=cmd_inspect)
    verify = sub.add_parser("verify")
    verify.add_argument("apk")
    verify.add_argument("--unsigned", action="store_true", help="expect the unsigned CI build, not a release")
    verify.add_argument("--package", default=PACKAGE)
    verify.add_argument("--pin-file", default=str(PIN_FILE))
    verify.add_argument("--require-pin", action="store_true")
    verify.add_argument("--min-cert-years", type=int, default=MIN_CERT_YEARS)
    verify.set_defaults(func=cmd_verify)
    assemble = sub.add_parser("assemble")
    assemble.add_argument("--unsigned", required=True)
    assemble.add_argument("--keystore", required=True)
    assemble.add_argument("--alias", required=True)
    assemble.add_argument("--ks-pass", required=True, help="env:NAME or file:PATH")
    assemble.add_argument("--key-pass", help="env:NAME or file:PATH (default: reuse the keystore password)")
    assemble.add_argument("--out-dir", required=True)
    assemble.add_argument("--build-info", help="JSON written by 'inspect' when the unsigned APK was built")
    assemble.add_argument("--pin-file", default=str(PIN_FILE))
    assemble.add_argument("--allow-unpinned", action="store_true",
                          help="only for the very first release, to learn the fingerprint to pin")
    assemble.add_argument("--min-cert-years", type=int, default=MIN_CERT_YEARS)
    assemble.add_argument("--repo", default=os.environ.get("GITHUB_REPOSITORY"), help="OWNER/NAME for links in the notes")
    assemble.set_defaults(func=cmd_assemble)
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        args.func(args)
    except ReleaseError as error:
        print(f"RELEASE BLOCKED: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
