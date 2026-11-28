#!/usr/bin/env python3
import json, pathlib, sys, zipfile
from collections import defaultdict
apk=pathlib.Path(sys.argv[1])
baseline=66118891
with zipfile.ZipFile(apk) as z:
    files={i.filename:i for i in z.infolist()}
    for name in ("libffmpeg.zip.so","libpython.zip.so","libffmpeg.so","libffprobe.so","libpython.so","libqjs.so"):
        path="lib/arm64-v8a/"+name
        assert path in files and files[path].file_size>0, "Missing engine payload: "+path
    assert any(n.startswith("res/") and "ytdlp" in n for n in files), "Missing bundled yt-dlp"
    groups=defaultdict(int)
    for i in files.values():
        key="dex" if i.filename.startswith("classes") and i.filename.endswith(".dex") else i.filename.split("/")[0]
        groups[key]+=i.compress_size
    print(json.dumps({"previous_apk_bytes":baseline,"current_apk_bytes":apk.stat().st_size,"saved_bytes":baseline-apk.stat().st_size,"groups":dict(groups)},indent=2))
    assert apk.stat().st_size < baseline, "APK did not shrink"
