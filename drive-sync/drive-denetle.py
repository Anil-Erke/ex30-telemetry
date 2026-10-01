"""Drive'daki EX30 dosyalarini masaustu istemcisiyle denetler (protokol 3).

Okuyan bir istemcinin (Viewer, telefon) gorecegi seyi gosterir:
  - appProperties ile bulunan butun EX30 dosyalari ve klasorleri
  - yolculuk ozetlerinin icerigi, izlerin satir sayisi
  - istenirse yerel bir dosyayla MD5 karsilastirmasi (--karsilastir <yol>)
  - istenirse HEPSINI siler (--sil) — emulator testinden kalan sahte veri icin

Calistirma (proje kokunden):
  py -3 drive-sync/drive-denetle.py [--karsilastir izdosyasi.csv.gz] [--sil]

Giris tarayicida (loopback + PKCE); token hicbir yere yazilmaz.
"""

from __future__ import annotations

import gzip
import hashlib
import importlib.util
import json
import sys
import urllib.parse
from pathlib import Path

HERE = Path(__file__).resolve().parent
spec = importlib.util.spec_from_file_location("oauth", HERE / "oauth-dogrulama.py")
oauth = importlib.util.module_from_spec(spec)
spec.loader.exec_module(oauth)  # type: ignore[union-attr]

DRIVE = oauth.DRIVE


def list_all(token: str, q: str) -> list[dict]:
    out, page = [], None
    while True:
        params = {
            "q": q, "spaces": "drive", "pageSize": "1000",
            "fields": "nextPageToken,files(id,name,mimeType,createdTime,size,md5Checksum,appProperties,parents)",
        }
        if page:
            params["pageToken"] = page
        code, raw = oauth.api(token, "GET", f"{DRIVE}/files?" + urllib.parse.urlencode(params))
        if code != 200:
            sys.exit(f"liste alinamadi: {code} {raw[:300]!r}")
        body = json.loads(raw)
        out += body.get("files", [])
        page = body.get("nextPageToken")
        if not page:
            return out


def main() -> None:
    args = sys.argv[1:]
    compare = Path(args[args.index("--karsilastir") + 1]) if "--karsilastir" in args else None
    delete = "--sil" in args

    p = oauth.load_props()
    tok = oauth.loopback_flow(p["desktopClientId"], p["desktopClientSecret"])
    oauth.require_drive(tok, "masaustu")
    t = tok["access_token"]
    oauth.say(f"hesap: {oauth.email_of(tok)}\n")

    files = list_all(t, "appProperties has { key='ex30' and value='trip' } and trashed=false")
    files += list_all(t, "appProperties has { key='ex30' and value='kayit' } and trashed=false")
    folders = list_all(t, "mimeType='application/vnd.google-apps.folder' and trashed=false")
    folders = [f for f in folders if "ex30path" in f.get("appProperties", {})]

    oauth.say(f"KLASORLER ({len(folders)}):")
    for f in sorted(folders, key=lambda f: f["appProperties"]["ex30path"]):
        oauth.say(f"  {f['appProperties']['ex30path']}")

    oauth.say(f"\nDOSYALAR ({len(files)}):")
    for f in sorted(files, key=lambda f: f["name"]):
        ap = f.get("appProperties", {})
        oauth.say(f"  {f['name']:32} {ap.get('tur', ap.get('ex30')):5} {f.get('size', '?'):>7} B  "
                  f"md5={f.get('md5Checksum', '?')[:10]}  olusturuldu={f['createdTime']}")
        code, raw = oauth.api(t, "GET", f"{DRIVE}/files/{f['id']}?alt=media")
        if code != 200:
            oauth.say(f"      INDIRILEMEDI: HTTP {code}")
            continue
        if hashlib.md5(raw).hexdigest() != f.get("md5Checksum"):
            oauth.say("      MD5 TUTMUYOR (indirilen icerik Drive'in bildirdigiyle ayni degil)")
        if ap.get("tur") == "ozet":
            o = json.loads(raw)
            oauth.say(f"      ozet: {o.get('distanceKm', 0):.3f} km, {o.get('durationSec')} sn, "
                      f"tirmanis {o.get('altGainM', 0):.0f} m, sema {o.get('schemaVersion')}")
        elif ap.get("tur") == "iz":
            lines = gzip.decompress(raw).decode("utf-8").splitlines()
            oauth.say(f"      iz: {lines[0]}  ·  {len(lines) - 2} nokta  ·  sutunlar: {lines[1]}")
            if compare:
                local = compare.read_bytes()
                same = hashlib.md5(local).hexdigest() == f.get("md5Checksum")
                oauth.say(f"      yerel {compare.name} ile: {'BIREBIR AYNI' if same else 'FARKLI'}")

    if delete:
        oauth.say("\nSILINIYOR (dosyalar, sonra klasorler en derinden)...")
        for f in files:
            oauth.api(t, "DELETE", f"{DRIVE}/files/{f['id']}")
        for f in sorted(folders, key=lambda f: -f["appProperties"]["ex30path"].count("/")):
            c, _ = oauth.api(t, "DELETE", f"{DRIVE}/files/{f['id']}")
            oauth.say(f"  {f['appProperties']['ex30path']}: {'silindi' if c in (200, 204) else f'HTTP {c}'}")
        left = list_all(t, "appProperties has { key='ex30' and value='trip' } and trashed=false")
        oauth.say(f"kalan yolculuk dosyasi: {len(left)}")


if __name__ == "__main__":
    main()
