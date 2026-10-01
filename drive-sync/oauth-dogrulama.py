"""OAuth + drive.file dogrulamasi — protokol 3'e gecmeden ONCE bir kez calistir.

Cevaplanan soru: araçtaki istemcinin ("TVs and Limited Input devices")
drive.file ile OLUSTURDUGU dosyayi, AYNI Cloud projesindeki baska bir istemci
(EX30 Trip Viewer, "Desktop app") drive.file ile GOREBILIYOR mu?

Belgeler bunu acikca soylemiyor ve butun tasarim buna bagli: gormuyorsa okuyan
uygulamalar klasoru bir kez "secmek" zorunda kalacak.

Adimlar:
  1. Arac istemcisi, cihaz akisiyla (google.com/device + kod) yetkilendirilir.
  2. Arac istemcisi bir test klasoru + test dosyasi olusturur.
  3. Masaustu istemcisi, tarayiciyla (loopback) yetkilendirilir.
  4. Masaustu istemcisi dosyayi ARAR ve ICERIGINI okumayi dener.
  5. Arac istemcisi test dosyasini ve klasoru siler.

Calistirma (proje kokunden):
  py -3 drive-sync/oauth-dogrulama.py

Istemci bilgileri `oauth.properties`'ten okunur. Token'lar hicbir yere
yazilmaz, ekrana basilmaz; betik bitince kaybolur.
"""

from __future__ import annotations

import base64
import hashlib
import http.server
import json
import secrets
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import webbrowser
from pathlib import Path

SCOPE = "https://www.googleapis.com/auth/drive.file openid email"
DEVICE_URL = "https://oauth2.googleapis.com/device/code"
TOKEN_URL = "https://oauth2.googleapis.com/token"
AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
DRIVE = "https://www.googleapis.com/drive/v3"
UPLOAD = "https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart"
MARK = "ex30-oauth-dogrulama"


def say(msg: str) -> None:
    print(msg, flush=True)


def load_props() -> dict[str, str]:
    path = Path(__file__).resolve().parent.parent / "oauth.properties"
    props: dict[str, str] = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    missing = [k for k in ("carClientId", "carClientSecret", "desktopClientId", "desktopClientSecret")
               if not props.get(k)]
    if missing:
        sys.exit(f"oauth.properties eksik: {', '.join(missing)}")
    return props


def post_form(url: str, data: dict[str, str]) -> dict:
    body = urllib.parse.urlencode(data).encode()
    req = urllib.request.Request(url, data=body, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            return json.loads(r.read())
    except urllib.error.HTTPError as e:
        return json.loads(e.read() or b"{}") | {"_http": e.code}


def api(token: str, method: str, url: str, body: bytes | None = None,
        ctype: str | None = None) -> tuple[int, bytes]:
    req = urllib.request.Request(url, data=body, method=method)
    req.add_header("Authorization", f"Bearer {token}")
    if ctype:
        req.add_header("Content-Type", ctype)
    try:
        with urllib.request.urlopen(req, timeout=60) as r:
            return r.status, r.read()
    except urllib.error.HTTPError as e:
        return e.code, e.read()


def require_drive(tokens: dict, who: str) -> None:
    """Verilen izinleri yazar; drive.file yoksa DURUR.

    Google'in izin ekrani her izne ayri onay kutusu koyuyor ve Drive kutusu
    bos birakilabiliyor. O zaman giris BASARILI gorunuyor ama her Drive cagrisi
    403 "insufficient authentication scopes" ile donuyor (ilk denemede oldu).
    """
    granted = tokens.get("scope", "").split()
    short = [s.rsplit("/", 1)[-1] for s in granted]
    say(f"      verilen izinler: {', '.join(short) or '(yok)'}")
    if "https://www.googleapis.com/auth/drive.file" not in granted:
        sys.exit(f"{who}: Drive izni VERILMEDI. Onay ekraninda Drive kutusunu da isaretle.")


def email_of(tokens: dict) -> str:
    """id_token'in govdesinden e-posta (imza dogrulamasi gerekmiyor: kendi istegimiz)."""
    try:
        payload = tokens["id_token"].split(".")[1]
        payload += "=" * (-len(payload) % 4)
        return json.loads(base64.urlsafe_b64decode(payload)).get("email", "?")
    except Exception:
        return "?"


# --- 1. Cihaz akisi (arac) --------------------------------------------------

def device_flow(client_id: str, secret: str) -> dict:
    r = post_form(DEVICE_URL, {"client_id": client_id, "scope": SCOPE})
    if "device_code" not in r:
        sys.exit(f"cihaz kodu alinamadi: {r}")
    say("")
    say("=" * 60)
    say(f"  TELEFONDAN AC:  {r['verification_url']}")
    say(f"  KODU YAZ:       {r['user_code']}")
    say("=" * 60)
    say(f"  (kod {r['expires_in'] // 60} dk gecerli; onay bekleniyor...)")
    interval = int(r.get("interval", 5))
    deadline = time.time() + int(r["expires_in"])
    while time.time() < deadline:
        time.sleep(interval)
        t = post_form(TOKEN_URL, {
            "client_id": client_id,
            "client_secret": secret,
            "device_code": r["device_code"],
            "grant_type": "urn:ietf:params:oauth:grant-type:device_code",
        })
        if "access_token" in t:
            return t
        err = t.get("error")
        if err == "authorization_pending":
            continue
        if err == "slow_down":
            interval += 5
            continue
        sys.exit(f"cihaz akisi durdu: {err} — {t.get('error_description', '')}")
    sys.exit("kodun suresi doldu")


# --- 3. Loopback akisi (masaustu) -------------------------------------------

def loopback_flow(client_id: str, secret: str) -> dict:
    verifier = secrets.token_urlsafe(48)
    challenge = base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest()).rstrip(b"=").decode()
    state = secrets.token_urlsafe(16)
    result: dict[str, str] = {}

    class Handler(http.server.BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802
            q = dict(urllib.parse.parse_qsl(urllib.parse.urlparse(self.path).query))
            if q.get("state") == state:
                result.update(q)
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.end_headers()
            self.wfile.write("Tamam — bu sekmeyi kapatabilirsin.".encode())

        def log_message(self, *a) -> None:
            pass

    server = http.server.HTTPServer(("127.0.0.1", 0), Handler)
    redirect = f"http://127.0.0.1:{server.server_port}"
    threading.Thread(target=server.handle_request, daemon=True).start()

    url = AUTH_URL + "?" + urllib.parse.urlencode({
        "client_id": client_id, "redirect_uri": redirect, "response_type": "code",
        "scope": SCOPE, "state": state, "code_challenge": challenge,
        "code_challenge_method": "S256", "prompt": "consent",
    })
    say("")
    say("Masaustu istemcisi icin tarayici aciliyor; ayni hesapla onayla.")
    webbrowser.open(url)
    for _ in range(600):
        if result:
            break
        time.sleep(0.5)
    server.server_close()
    if "code" not in result:
        sys.exit(f"tarayici onayi gelmedi: {result.get('error', 'zaman asimi')}")
    t = post_form(TOKEN_URL, {
        "client_id": client_id, "client_secret": secret, "code": result["code"],
        "code_verifier": verifier, "grant_type": "authorization_code", "redirect_uri": redirect,
    })
    if "access_token" not in t:
        sys.exit(f"masaustu token alinamadi: {t}")
    return t


# --- Akis ---------------------------------------------------------------------

def main() -> None:
    p = load_props()

    say("[1/5] Arac istemcisi — cihaz akisi")
    car = device_flow(p["carClientId"], p["carClientSecret"])
    ct = car["access_token"]
    say(f"      baglandi: {email_of(car)} · refresh token {'VAR' if car.get('refresh_token') else 'YOK'}")
    require_drive(car, "arac")

    say("[2/5] Arac istemcisi test klasoru ve dosyasi olusturuyor")
    code, raw = api(ct, "POST", f"{DRIVE}/files?fields=id", json.dumps({
        "name": "EX30 OAuth dogrulama (silinecek)",
        "mimeType": "application/vnd.google-apps.folder",
    }).encode(), "application/json")
    if code != 200:
        sys.exit(f"klasor olusturulamadi: {code} {raw[:300]!r}")
    folder = json.loads(raw)["id"]

    boundary = "ex30sinir"
    meta = {"name": "trip-test.json", "parents": [folder], "appProperties": {"ex30": MARK}}
    content = json.dumps({"merhaba": "arac"}).encode()
    body = (f"--{boundary}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n"
            f"{json.dumps(meta)}\r\n--{boundary}\r\nContent-Type: application/json\r\n\r\n").encode() \
        + content + f"\r\n--{boundary}--\r\n".encode()
    code, raw = api(ct, "POST", UPLOAD + "&fields=id", body, f"multipart/related; boundary={boundary}")
    if code != 200:
        sys.exit(f"dosya olusturulamadi: {code} {raw[:300]!r}")
    file_id = json.loads(raw)["id"]
    say("      olusturuldu")

    try:
        say("[3/5] Masaustu istemcisi — tarayici akisi")
        desk = loopback_flow(p["desktopClientId"], p["desktopClientSecret"])
        dt = desk["access_token"]
        say(f"      baglandi: {email_of(desk)}")
        require_drive(desk, "masaustu")

        say("[4/5] Masaustu istemcisi dosyayi ariyor ve okuyor")
        q = urllib.parse.quote(f"appProperties has {{ key='ex30' and value='{MARK}' }} and trashed=false")
        code, raw = api(dt, "GET", f"{DRIVE}/files?q={q}&fields=files(id,name)")
        found = json.loads(raw).get("files", []) if code == 200 else []
        code2, raw2 = api(dt, "GET", f"{DRIVE}/files/{file_id}?alt=media")
        say("")
        say("=" * 60)
        say(f"  ARAMA : {'GORDU' if any(f['id'] == file_id for f in found) else 'GORMEDI'}"
            f"  (HTTP {code}, {len(found)} sonuc)")
        say(f"  OKUMA : {'OKUDU' if code2 == 200 and b'arac' in raw2 else 'OKUYAMADI'}  (HTTP {code2})")
        say("=" * 60)
    finally:
        say("[5/5] Test dosyasi ve klasor siliniyor (arac istemcisiyle)")
        api(ct, "DELETE", f"{DRIVE}/files/{file_id}")
        c, _ = api(ct, "DELETE", f"{DRIVE}/files/{folder}")
        say(f"      {'silindi' if c in (200, 204) else f'silinemedi (HTTP {c}) — Drive kokunden elle sil'}")


if __name__ == "__main__":
    main()
