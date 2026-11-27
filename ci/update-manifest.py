import hashlib, json, re, subprocess, sys
from pathlib import Path
apk = Path(sys.argv[1])
aapt = sys.argv[2]
apksigner = sys.argv[3]
badging = subprocess.check_output([aapt, "dump", "badging", str(apk)], text=True)
m = re.search(r"package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging)
if not m or m.group(1) != "com.clipshelf.app":
    raise SystemExit("Unexpected APK identity")
with apk.open("rb") as f:
    digest = hashlib.file_digest(f, "sha256").hexdigest()
verified = subprocess.check_output([apksigner, "verify", "--print-certs", str(apk)], text=True)
certs = re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F]{64})", verified)
if len(certs) != 1:
    raise SystemExit("Expected one verified APK signer")
if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", m.group(3)):
    raise SystemExit("Unexpected release version")
info = {"signerSha256": certs[0].lower(), "releaseNotes": "Startup stability fixes and direct, verified in-app updates.", "packageName": m.group(1), "versionCode": int(m.group(2)), "versionName": m.group(3), "size": apk.stat().st_size, "sha256": digest}
(apk.parent / "update.json").write_text(json.dumps(info, indent=2) + "\n", encoding="utf-8")
print(json.dumps(info))
