"""Create an NZD1 patch by reusing unchanged compressed ZIP entry bytes.
No APK re-signing or ZIP rewriting occurs: reconstruction is byte-for-byte exact.
"""
import gzip
import hashlib
import io
import struct
import zipfile
from pathlib import Path

MAGIC = b"NZD1"
MAX_SIZE = 250 * 1024 * 1024
MAX_OPS = 100_000

def digest(path):
    with open(path, "rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()

def runtime_fingerprints(path):
    with zipfile.ZipFile(path) as z:
        return {entry.filename: hashlib.sha256(z.read(entry)).hexdigest()
                for entry in z.infolist()
                if not entry.is_dir() and entry.filename.startswith("lib/")}

def payloads(z, data):
    result = {}
    for entry in z.infolist():
        offset = entry.header_offset
        if offset < 0 or offset + 30 > len(data) or data[offset:offset+4] != b"PK\x03\x04":
            raise ValueError("Invalid ZIP local header")
        name_len, extra_len = struct.unpack_from("<HH", data, offset + 26)
        start = offset + 30 + name_len + extra_len
        end = start + entry.compress_size
        if not (0 <= start <= end <= len(data)) or entry.filename in result:
            raise ValueError("Invalid or duplicate ZIP entry")
        result[entry.filename] = (start, end)
    return result

def create(base, target, patch):
    old = Path(base).read_bytes()
    new = Path(target).read_bytes()
    if not (0 < len(old) <= MAX_SIZE and 0 < len(new) <= MAX_SIZE):
        raise ValueError("APK exceeds size limit")
    with zipfile.ZipFile(io.BytesIO(old)) as a, zipfile.ZipFile(io.BytesIO(new)) as b:
        old_ranges = payloads(a, old)
        new_ranges = payloads(b, new)
    copies = []
    for name, (start, end) in new_ranges.items():
        if name not in old_ranges or end - start < 32:
            continue
        x, y = old_ranges[name]
        if end - start == y - x and memoryview(new)[start:end] == memoryview(old)[x:y]:
            copies.append((start, end, x))
    copies.sort()
    with open(patch, "wb") as raw, gzip.GzipFile(fileobj=raw, mode="wb", compresslevel=6, mtime=0, filename="") as out:
        out.write(MAGIC)
        pos = 0
        for start, end, source in copies:
            if start < pos:
                raise ValueError("Overlapping target entries")
            if start > pos:
                out.write(struct.pack(">BI", 2, start - pos))
                out.write(memoryview(new)[pos:start])
            out.write(struct.pack(">BQI", 1, source, end - start))
            pos = end
        if pos < len(new):
            out.write(struct.pack(">BI", 2, len(new) - pos))
            out.write(memoryview(new)[pos:])
        out.write(b"\0")

def reconstruct(base, patch, output, expected):
    if not 0 < expected <= MAX_SIZE:
        raise ValueError("Invalid expected size")
    def exact(f, n):
        data = f.read(n)
        if len(data) != n:
            raise ValueError("Truncated patch")
        return data
    with open(base, "rb") as old, gzip.open(patch, "rb") as p, open(output, "wb") as out:
        if exact(p, 4) != MAGIC:
            raise ValueError("Bad patch magic")
        total = 0
        base_size = Path(base).stat().st_size
        ops = 0
        while True:
            kind = exact(p, 1)[0]
            if kind == 0:
                break
            ops += 1
            if ops > MAX_OPS or kind not in (1, 2):
                raise ValueError("Bad patch operation")
            offset = struct.unpack(">Q", exact(p, 8))[0] if kind == 1 else 0
            length = struct.unpack(">I", exact(p, 4))[0]
            if not 0 < length <= expected - total:
                raise ValueError("Bad output length")
            if kind == 1:
                if offset > base_size or length > base_size - offset:
                    raise ValueError("Bad source range")
                old.seek(offset)
            left = length
            while left:
                n = min(left, 64 * 1024)
                out.write(exact(old if kind == 1 else p, n))
                left -= n
            total += length
        if total != expected or p.read(1):
            raise ValueError("Invalid patch ending")
