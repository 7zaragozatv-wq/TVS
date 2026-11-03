#!/usr/bin/env python3
"""
Bucle TV · Estudio
Programa para el ordenador de realización: controla las TVs del plató.

- Las TVs lo encuentran solas en la red (UDP 47800) y se conectan por HTTP (puerto 8080).
- Panel de control en http://localhost:8080
- Órdenes por URL (para vMix, Companion, etc.):
    /api/control?tv=1&accion=reproducir&lista=Apertura
    /api/control?tv=todas&accion=sincronizar
    /api/control?tv=2&accion=negro
    /api/control?tv=3&accion=siguiente

Solo usa la librería estándar de Python.
"""
import json
import os
import re
import socket
import sys
import threading
import time
import urllib.parse
import uuid
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

VERSION = "1.0"
HTTP_PORT = int(os.environ.get("BUCLETV_PUERTO", "8080"))
UDP_PORT = 47800
LEAD_SINGLE_MS = 1500     # margen para preparar al lanzar una lista
LEAD_SYNC_MS = 2500       # margen al arrancar varias TVs a la vez
ONLINE_MS = 8000          # sin noticias de una TV en este tiempo = desconectada
DEFAULT_IMAGE_MS = 10000

VIDEO_EXT = {"mp4", "m4v", "mkv", "webm", "mov", "3gp", "ts", "m2ts", "mts", "mpg", "mpeg", "avi", "flv"}
IMAGE_EXT = {"jpg", "jpeg", "png", "webp", "bmp", "gif", "heic", "heif"}


def base_dir():
    if getattr(sys, "frozen", False):
        return os.path.dirname(sys.executable)
    return os.path.dirname(os.path.abspath(__file__))


def resource(name):
    base = getattr(sys, "_MEIPASS", os.path.dirname(os.path.abspath(__file__)))
    return os.path.join(base, name)


DATA_DIR = os.path.join(base_dir(), "BucleTV-datos")
MEDIA_DIR = os.path.join(DATA_DIR, "medios")
STATE_FILE = os.path.join(DATA_DIR, "estado.json")
os.makedirs(MEDIA_DIR, exist_ok=True)


def now_ms():
    return int(time.time() * 1000)


def ext_of(name):
    return name.rsplit(".", 1)[-1].lower() if "." in name else ""


def kind_of(name):
    e = ext_of(name)
    if e in VIDEO_EXT:
        return "v"
    if e in IMAGE_EXT:
        return "i"
    return None


def natural_key(s):
    return [int(t) if t.isdigit() else t.lower() for t in re.split(r"(\d+)", s)]


def safe_name(name):
    name = os.path.basename(name.replace("\\", "/")).strip()
    name = re.sub(r'[<>:"/\\|?*\x00-\x1f]', "_", name)
    return name if name and not name.startswith(".") else None


def log(msg):
    print(time.strftime("%H:%M:%S"), msg, flush=True)


def local_ips():
    ips = set()
    try:
        s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        s.connect(("10.255.255.255", 1))
        ips.add(s.getsockname()[0])
        s.close()
    except OSError:
        pass
    try:
        for ip in socket.gethostbyname_ex(socket.gethostname())[2]:
            if not ip.startswith("127."):
                ips.add(ip)
    except OSError:
        pass
    return sorted(ips)


class Store:
    """Estado compartido: TVs, listas y versión (las TVs esperan cambios de versión)."""

    def __init__(self):
        self.cond = threading.Condition()
        self.version = 1
        self.tvs = {}
        self.listas = {}
        self._load()

    # ---------- persistencia ----------
    def _load(self):
        try:
            with open(STATE_FILE, "r", encoding="utf-8") as f:
                data = json.load(f)
            self.listas = data.get("listas", {})
            for tv in data.get("tvs", []):
                tv["visto"] = 0
                tv["reporte"] = {}
                tv.setdefault("salto", 0)
                self.tvs[tv["id"]] = tv
        except FileNotFoundError:
            pass
        except Exception as e:
            log(f"Aviso: no se pudo leer {STATE_FILE}: {e}")

    def _save(self):
        keep = ("id", "num", "nombre", "modelo", "lista", "modo", "items", "inicio", "salto")
        data = {
            "listas": self.listas,
            "tvs": [{k: tv.get(k) for k in keep} for tv in self.tvs.values()],
        }
        tmp = STATE_FILE + ".tmp"
        with open(tmp, "w", encoding="utf-8") as f:
            json.dump(data, f, ensure_ascii=False, indent=1)
        os.replace(tmp, STATE_FILE)

    def bump(self):
        self.version += 1
        self._save()
        self.cond.notify_all()

    # ---------- medios ----------
    def media_index(self):
        out = {}
        for name in os.listdir(MEDIA_DIR):
            p = os.path.join(MEDIA_DIR, name)
            if os.path.isfile(p) and kind_of(name) and not name.endswith(".subiendo") and os.path.getsize(p) > 0:
                out[name] = os.path.getsize(p)
        return out

    # ---------- TVs ----------
    def register(self, tv_id, modelo, ip):
        tv = self.tvs.get(tv_id)
        if tv is None:
            num = max([t["num"] for t in self.tvs.values()] + [0]) + 1
            tv = {
                "id": tv_id, "num": num, "nombre": f"TV {num}", "modelo": modelo,
                "lista": None, "modo": "nada", "items": [], "inicio": 0, "salto": 0,
                "visto": now_ms(), "reporte": {},
            }
            self.tvs[tv_id] = tv
            log(f"Nueva TV conectada: {tv['nombre']} ({modelo}, {ip})")
            self.bump()
        if modelo:
            tv["modelo"] = modelo
        tv["ip"] = ip
        if now_ms() - tv.get("visto", 0) > ONLINE_MS:
            log(f"{tv['nombre']} conectada ({ip})")
        tv["visto"] = now_ms()
        return tv

    def snapshot(self, lista):
        items = []
        for it in self.listas.get(lista, []):
            k = kind_of(it["f"])
            if not k:
                continue
            ms = int(it.get("ms") or DEFAULT_IMAGE_MS) if k == "i" else 0
            items.append({"f": it["f"], "t": k, "ms": ms})
        return items

    def state_for(self, tv):
        media = self.media_index()
        needed = set()
        for lst in self.listas.values():
            for it in lst:
                if it["f"] in media:
                    needed.add(it["f"])
        for it in tv.get("items", []):
            if it["f"] in media:
                needed.add(it["f"])
        return {
            "v": self.version,
            "num": tv["num"],
            "nombre": tv["nombre"],
            "modo": tv.get("modo", "nada"),
            "lista": tv.get("lista"),
            "items": [it for it in tv.get("items", []) if it["f"] in media],
            "inicio": tv.get("inicio", 0),
            "salto": tv.get("salto", 0),
            "archivos": [{"f": f, "s": media[f]} for f in sorted(needed, key=natural_key)],
            "ahora": now_ms(),
        }

    def select(self, sel):
        sel = (sel or "").strip().lower()
        tvs = sorted(self.tvs.values(), key=lambda t: t["num"])
        if sel in ("", "todas", "todos", "all", "*"):
            return tvs
        wanted = [s.strip() for s in sel.split(",") if s.strip()]
        out = []
        for t in tvs:
            for w in wanted:
                if w in (str(t["num"]), t["id"].lower(), t["nombre"].lower()):
                    out.append(t)
                    break
        return out

    def control(self, sel, accion, lista=None):
        accion = (accion or "").strip().lower()
        tvs = self.select(sel)
        if not tvs:
            return False, f"No hay ninguna TV que coincida con «{sel}»"
        if lista is not None and lista not in self.listas:
            # búsqueda sin distinguir mayúsculas
            match = [n for n in self.listas if n.lower() == lista.strip().lower()]
            if not match:
                return False, f"No existe la lista «{lista}»"
            lista = match[0]

        if accion in ("reproducir", "play", "lista", "sincronizar", "sync"):
            lead = LEAD_SYNC_MS if (len(tvs) > 1 or accion in ("sincronizar", "sync")) else LEAD_SINGLE_MS
            inicio = now_ms() + lead
            done = []
            for tv in tvs:
                nombre_lista = lista or tv.get("lista")
                if not nombre_lista or nombre_lista not in self.listas:
                    continue
                tv["lista"] = nombre_lista
                tv["items"] = self.snapshot(nombre_lista)
                tv["modo"] = "lista"
                tv["inicio"] = inicio
                tv["salto"] = 0
                done.append(tv["nombre"])
            if not done:
                return False, "Ninguna de esas TVs tiene lista asignada"
            self.bump()
            return True, "Reproduciendo en " + ", ".join(done)

        if accion in ("negro", "black", "parar", "stop"):
            for tv in tvs:
                tv["modo"] = "negro"
            self.bump()
            return True, "Negro en " + ", ".join(t["nombre"] for t in tvs)

        if accion in ("siguiente", "next"):
            for tv in tvs:
                tv["salto"] = int(tv.get("salto", 0)) + 1
            self.bump()
            return True, "Siguiente en " + ", ".join(t["nombre"] for t in tvs)

        if accion in ("usb", "liberar"):
            for tv in tvs:
                tv["modo"] = "nada"
            self.bump()
            return True, "Modo USB en " + ", ".join(t["nombre"] for t in tvs)

        return False, f"Acción desconocida: «{accion}»"

    def panel(self):
        media = self.media_index()
        now = now_ms()
        tvs = []
        for tv in sorted(self.tvs.values(), key=lambda t: t["num"]):
            snap = self.snapshot(tv["lista"]) if tv.get("lista") in self.listas else None
            tvs.append({
                "id": tv["id"], "num": tv["num"], "nombre": tv["nombre"],
                "modelo": tv.get("modelo", ""), "ip": tv.get("ip", ""),
                "online": now - tv.get("visto", 0) < ONLINE_MS,
                "lista": tv.get("lista"), "modo": tv.get("modo", "nada"),
                "cambios": snap is not None and snap != tv.get("items") and tv.get("modo") == "lista",
                "reporte": tv.get("reporte", {}),
            })
        return {
            "version": self.version,
            "ahora": now,
            "tvs": tvs,
            "listas": self.listas,
            "medios": [{"f": f, "s": s, "t": kind_of(f)} for f, s in sorted(media.items(), key=lambda x: natural_key(x[0]))],
            "direcciones": local_ips(),
            "puerto": HTTP_PORT,
            "carpeta": MEDIA_DIR,
            "app": VERSION,
        }


STORE = Store()


class Handler(BaseHTTPRequestHandler):
    server_version = "BucleTV/" + VERSION
    protocol_version = "HTTP/1.0"

    def log_message(self, fmt, *args):
        pass  # silencioso

    # ---------- utilidades ----------
    def _send(self, code, body, ctype="application/json; charset=utf-8", extra=None):
        if isinstance(body, (dict, list)):
            body = json.dumps(body, ensure_ascii=False).encode("utf-8")
        elif isinstance(body, str):
            body = body.encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def _json_body(self):
        n = int(self.headers.get("Content-Length") or 0)
        if n <= 0:
            return {}
        return json.loads(self.rfile.read(n).decode("utf-8"))

    def _query(self):
        u = urllib.parse.urlsplit(self.path)
        q = {k: v[-1] for k, v in urllib.parse.parse_qs(u.query, keep_blank_values=True).items()}
        return urllib.parse.unquote(u.path), q

    # ---------- GET ----------
    def do_GET(self):
        path, q = self._query()
        try:
            if path in ("/", "/panel", "/index.html"):
                with open(resource("panel.html"), "rb") as f:
                    return self._send(200, f.read(), "text/html; charset=utf-8")
            if path == "/api/time":
                return self._send(200, {"t": now_ms()})
            if path == "/api/tv/state":
                return self._tv_state(q)
            if path == "/api/panel":
                with STORE.cond:
                    return self._send(200, STORE.panel())
            if path == "/api/control":
                return self._control(q)
            if path.startswith("/media/"):
                return self._media(path[len("/media/"):])
            return self._send(404, {"error": "no encontrado"})
        except ConnectionError:
            pass
        except Exception as e:
            log(f"Error en {path}: {e}")
            self._send(500, {"error": str(e)})

    def _tv_state(self, q):
        tv_id = q.get("id", "")
        if not tv_id:
            return self._send(400, {"error": "falta id"})
        try:
            v = int(q.get("v", "0"))
        except ValueError:
            v = 0
        ip = self.client_address[0]
        with STORE.cond:
            tv = STORE.register(tv_id, q.get("modelo", ""), ip)
            deadline = time.time() + 25
            while STORE.version == v and time.time() < deadline:
                STORE.cond.wait(max(0.05, deadline - time.time()))
            tv["visto"] = now_ms()
            state = STORE.state_for(tv)
        self._send(200, state)

    def _control(self, q):
        lista = q.get("lista")
        with STORE.cond:
            ok, msg = STORE.control(q.get("tv", "todas"), q.get("accion", ""), lista if lista else None)
        log(("OK  " if ok else "ERROR ") + msg)
        self._send(200 if ok else 400, {"ok": ok, "mensaje": msg})

    def _media(self, raw_name):
        name = safe_name(raw_name)
        path = os.path.join(MEDIA_DIR, name) if name else None
        if not path or not os.path.isfile(path):
            return self._send(404, {"error": "archivo no encontrado"})
        size = os.path.getsize(path)
        self.send_response(200)
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Content-Length", str(size))
        self.end_headers()
        with open(path, "rb") as f:
            while True:
                chunk = f.read(1024 * 1024)
                if not chunk:
                    break
                self.wfile.write(chunk)

    # ---------- POST ----------
    def do_POST(self):
        path, q = self._query()
        try:
            if path == "/api/tv/report":
                body = self._json_body()
                with STORE.cond:
                    tv = STORE.tvs.get(body.get("id", ""))
                    if tv:
                        tv["visto"] = now_ms()
                        tv["reporte"] = body
                return self._send(200, {"ok": True})
            if path == "/api/listas":
                body = self._json_body()
                listas = {}
                for name, items in body.get("listas", {}).items():
                    name = str(name).strip()[:60]
                    if not name:
                        continue
                    clean = []
                    for it in items:
                        f = safe_name(str(it.get("f", "")))
                        if f and kind_of(f):
                            ms = max(500, min(3600000, int(it.get("ms") or DEFAULT_IMAGE_MS)))
                            clean.append({"f": f, "ms": ms})
                    listas[name] = clean
                with STORE.cond:
                    # si se renombró una lista, actualizamos las TVs que la usaban
                    for old, new in body.get("renombradas", {}).items():
                        for tv in STORE.tvs.values():
                            if tv.get("lista") == old:
                                tv["lista"] = new
                    STORE.listas = listas
                    STORE.bump()
                return self._send(200, {"ok": True})
            if path == "/api/tv/config":
                body = self._json_body()
                with STORE.cond:
                    tv = STORE.tvs.get(body.get("id", ""))
                    if not tv:
                        return self._send(404, {"error": "TV no encontrada"})
                    if "nombre" in body and str(body["nombre"]).strip():
                        tv["nombre"] = str(body["nombre"]).strip()[:40]
                    if "lista" in body:
                        tv["lista"] = body["lista"] or None
                    STORE.bump()
                return self._send(200, {"ok": True})
            if path == "/api/tv/olvidar":
                body = self._json_body()
                with STORE.cond:
                    STORE.tvs.pop(body.get("id", ""), None)
                    STORE.bump()
                return self._send(200, {"ok": True})
            if path == "/api/control":
                return self._control(q)
            if path == "/api/abrir-carpeta":
                if hasattr(os, "startfile"):
                    os.startfile(MEDIA_DIR)
                    return self._send(200, {"ok": True})
                return self._send(200, {"ok": False, "carpeta": MEDIA_DIR})
            return self._send(404, {"error": "no encontrado"})
        except Exception as e:
            log(f"Error en {path}: {e}")
            self._send(500, {"error": str(e)})

    # ---------- PUT (subir archivo) ----------
    def do_PUT(self):
        path, q = self._query()
        if path != "/api/media":
            return self._send(404, {"error": "no encontrado"})
        name = safe_name(q.get("f", ""))
        if not name or not kind_of(name):
            return self._send(400, {"error": "tipo de archivo no admitido"})
        n = int(self.headers.get("Content-Length") or 0)
        tmp = os.path.join(MEDIA_DIR, name + ".subiendo")
        try:
            with open(tmp, "wb") as f:
                left = n
                while left > 0:
                    chunk = self.rfile.read(min(1024 * 1024, left))
                    if not chunk:
                        raise IOError("subida interrumpida")
                    f.write(chunk)
                    left -= len(chunk)
            os.replace(tmp, os.path.join(MEDIA_DIR, name))
        except Exception as e:
            try:
                os.remove(tmp)
            except OSError:
                pass
            return self._send(500, {"error": str(e)})
        log(f"Archivo subido: {name} ({n / 1e6:.1f} MB)")
        with STORE.cond:
            STORE.bump()
        self._send(200, {"ok": True, "f": name})

    # ---------- DELETE ----------
    def do_DELETE(self):
        path, q = self._query()
        if path != "/api/media":
            return self._send(404, {"error": "no encontrado"})
        name = safe_name(q.get("f", ""))
        p = os.path.join(MEDIA_DIR, name) if name else None
        if p and os.path.isfile(p):
            os.remove(p)
            with STORE.cond:
                for lst in STORE.listas.values():
                    lst[:] = [it for it in lst if it["f"] != name]
                STORE.bump()
            log(f"Archivo borrado: {name}")
        self._send(200, {"ok": True})

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()


def discovery_loop():
    """Responde a las TVs que buscan el servidor en la red."""
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    try:
        s.bind(("", UDP_PORT))
    except OSError as e:
        log(f"Aviso: no se pudo abrir el puerto de descubrimiento {UDP_PORT}: {e}")
        return
    while True:
        try:
            data, addr = s.recvfrom(1024)
            if data.startswith(b"BUCLETV?"):
                s.sendto(f"BUCLETV:{HTTP_PORT}".encode(), addr)
        except OSError:
            time.sleep(0.5)


def main():
    print("=" * 60)
    print(f"  BUCLE TV · ESTUDIO  v{VERSION}")
    print("=" * 60)
    try:
        httpd = ThreadingHTTPServer(("0.0.0.0", HTTP_PORT), Handler)
    except OSError as e:
        print(f"\nNo se puede usar el puerto {HTTP_PORT}: {e}")
        print("¿Está ya abierto Bucle TV Estudio en otra ventana?")
        input("\nPulsa Enter para cerrar…")
        return
    httpd.daemon_threads = True
    threading.Thread(target=discovery_loop, daemon=True).start()

    url = f"http://localhost:{HTTP_PORT}"
    print(f"\n  Panel de control:  {url}")
    for ip in local_ips():
        print(f"  Desde otro equipo: http://{ip}:{HTTP_PORT}")
    print(f"\n  Carpeta de vídeos e imágenes:\n  {MEDIA_DIR}")
    print("\n  NO CIERRES ESTA VENTANA mientras uses las TVs.")
    print("  Si Windows pregunta por el firewall, pulsa «Permitir» (redes privadas).\n")
    print("-" * 60)
    if "--sin-navegador" not in sys.argv:
        threading.Timer(1.0, lambda: webbrowser.open(url)).start()
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
