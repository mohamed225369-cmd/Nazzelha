"""Prepare and verify patches against recent signed public releases."""
import importlib.util
import json
import os
import re
import subprocess
import sys
from pathlib import Path

spec = importlib.util.spec_from_file_location("apk_delta", Path(__file__).with_name("apk-delta.py"))
delta = importlib.util.module_from_spec(spec)
spec.loader.exec_module(delta)

target = Path(sys.argv[1]).resolve()
fixtures = Path(sys.argv[2]).resolve()
fixtures.mkdir(parents=True, exist_ok=True)
manifest = target.with_name("update.json")
meta = json.loads(manifest.read_text())
repo = os.environ["GITHUB_REPOSITORY"]
signer = sys.argv[3]
out = target.parent
meta["deltas"] = []
test_rows = []

def gh(*args):
    return subprocess.check_output(["gh", *args], text=True)

def verify_signer(apk, expected):
    log = subprocess.check_output([signer, "verify", "--print-certs", str(apk)], text=True)
    certs = re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]{64})", log)
    if [c.lower() for c in certs] != [expected]:
        raise ValueError("APK signing certificate mismatch")

verify_signer(target, meta["signerSha256"])
releases = json.loads(gh("api", "repos/" + repo + "/releases?per_page=10"))
target_version = tuple(map(int, meta["versionName"].split(".")))
prior = []
for release in releases:
    tag = release.get("tag_name", "")
    if release.get("draft") or release.get("prerelease") or not re.fullmatch(r"v\d+\.\d+\.\d+", tag):
        continue
    version = tuple(map(int, tag[1:].split(".")))
    if version < target_version:
        prior.append((version, tag))
prior.sort(reverse=True)
new_libs = delta.runtime_fingerprints(target)
for _, tag in prior[:3]:
    folder = fixtures / tag
    folder.mkdir(exist_ok=True)
    gh("release", "download", tag, "--repo", repo, "--pattern", "update.json",
       "--pattern", "Nazzelha-arm64.apk", "--dir", str(folder))
    base = folder / "Nazzelha-arm64.apk"
    previous = json.loads((folder / "update.json").read_text())
    code = previous["versionCode"]
    if (previous["packageName"] != meta["packageName"] or previous["signerSha256"] != meta["signerSha256"]
            or not isinstance(code, int) or not 0 < code < meta["versionCode"]
            or base.stat().st_size != previous["size"] or delta.digest(base) != previous["sha256"]):
        raise ValueError("Invalid previous release: " + tag)
    verify_signer(base, meta["signerSha256"])
    if delta.runtime_fingerprints(base) != new_libs:
        print(tag + ": native runtime changed; full update only")
        continue
    name = f'delta-{code}-{meta["versionCode"]}.nzd.gz'
    patch = out / name
    delta.create(base, target, patch)
    if patch.stat().st_size >= target.stat().st_size * 0.7:
        patch.unlink()
        print(tag + ": patch does not save enough; full update only")
        continue
    reconstructed = folder / "reconstructed.apk"
    delta.reconstruct(base, patch, reconstructed, meta["size"])
    if delta.digest(reconstructed) != meta["sha256"]:
        raise ValueError("Reconstruction hash mismatch")
    verify_signer(reconstructed, meta["signerSha256"])
    reconstructed.unlink()
    meta["deltas"].append({"fromVersionCode": code, "baseSha256": previous["sha256"],
        "baseSize": previous["size"], "asset": name, "size": patch.stat().st_size, "sha256": delta.digest(patch)})
    test_rows.append("\t".join(map(str, [base, patch, target, meta["size"], meta["sha256"]])))
    print(f'{tag}: {patch.stat().st_size:,} bytes versus {meta["size"]:,} bytes full')
manifest.write_text(json.dumps(meta, indent=2) + "\n")
(fixtures / "cases.tsv").write_text("\n".join(test_rows) + ("\n" if test_rows else ""))
print("Verified delta count:", len(test_rows))
