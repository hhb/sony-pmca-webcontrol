#!/usr/bin/env python3
"""Host companion server for Sony PMCA WebControl.

Provides:
  1. Static serving of web-client/dist/index.html
  2. Streaming HTTP reverse proxy for /api/* -> camera
  3. /local-api/* endpoints for backend Auto-Save and OpenFocus focus stacking
"""

from __future__ import annotations

import argparse
import http.client
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import threading
import urllib.error
import urllib.parse
import urllib.request
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

SAFE_ID_RE = re.compile(r"^[A-Za-z0-9._-]+$")
DEFAULT_OPENFOCUS_METHOD = "dtcwt"
DEFAULT_OPENFOCUS_ALIGN = "ecc"


def sanitize_filename(name: str, fallback: str = "capture.jpg") -> str:
    base = Path(name).name.strip()
    if not base or not SAFE_ID_RE.match(base):
        return fallback
    if not base.lower().endswith((".jpg", ".jpeg")):
        base = f"{Path(base).stem}.jpg"
    return base


def detect_openfocus(
    explicit_dir: str | None = None,
    explicit_python: str | None = None,
) -> dict[str, Any]:
    """Detect whether Xinzhe99/OpenFocus CLI is installed and runnable."""
    which_bin = shutil.which("openfocus")
    if which_bin:
        return {
            "available": True,
            "reason": f"OpenFocus CLI ready ({which_bin})",
            "cmd": [which_bin],
        }

    candidates: list[Path] = []
    if explicit_dir:
        candidates.append(Path(explicit_dir).expanduser())
    env_dir = os.environ.get("OPENFOCUS_DIR")
    if env_dir:
        candidates.append(Path(env_dir).expanduser())

    repo_root = Path(__file__).resolve().parent.parent
    candidates.extend([
        repo_root / "OpenFocus",
        repo_root.parent / "OpenFocus",
        Path.home() / "OpenFocus",
    ])

    for folder in candidates:
        main_py = folder / "main.py"
        if main_py.is_file():
            py_exec = explicit_python or os.environ.get("OPENFOCUS_PYTHON")
            if not py_exec:
                venv_py = folder / ".venv" / "bin" / "python"
                py_exec = str(venv_py) if venv_py.is_file() else sys.executable
            return {
                "available": True,
                "reason": f"OpenFocus ready ({main_py})",
                "cmd": [py_exec, str(main_py)],
                "cwd": str(folder),
            }

    return {
        "available": False,
        "reason": (
            "OpenFocus not detected on host backend. Install 'openfocus' in PATH "
            "or pass --openfocus-dir /path/to/OpenFocus."
        ),
        "cmd": None,
    }


class StackSession:
    def __init__(self, session_id: str, work_dir: Path, camera_url: str) -> None:
        self.session_id = session_id
        self.work_dir = work_dir
        self.input_dir = work_dir / "input"
        self.output_file = work_dir / "output.jpg"
        self.camera_url = camera_url
        self.input_dir.mkdir(parents=True, exist_ok=True)
        self.lock = threading.Lock()
        self.frame_count = 0
        self.status = "collecting"  # collecting | merging | done | error
        self.error: str | None = None
        self.saved_filename: str | None = None


class ServerState:
    def __init__(
        self,
        camera_url: str,
        web_index: Path,
        openfocus_info: dict[str, Any],
    ) -> None:
        self.camera_url = camera_url.rstrip("/")
        self.web_index = web_index
        self.openfocus_info = openfocus_info
        self.stack_root = Path(tempfile.gettempdir()) / "pmca-openfocus"
        self.stack_root.mkdir(parents=True, exist_ok=True)
        self.sessions: dict[str, StackSession] = {}
        self.sessions_lock = threading.Lock()

    def get_session(self, session_id: str) -> StackSession | None:
        with self.sessions_lock:
            return self.sessions.get(session_id)

    def create_session(self, session_id: str, camera_url: str | None = None) -> StackSession:
        if not SAFE_ID_RE.match(session_id):
            raise ValueError("Invalid sessionId")
        work_dir = self.stack_root / session_id
        if work_dir.exists():
            shutil.rmtree(work_dir, ignore_errors=True)
        session = StackSession(session_id, work_dir, (camera_url or self.camera_url).rstrip("/"))
        with self.sessions_lock:
            self.sessions[session_id] = session
        return session

    def delete_session(self, session_id: str) -> None:
        with self.sessions_lock:
            session = self.sessions.pop(session_id, None)
        work_dir = session.work_dir if session else (self.stack_root / session_id)
        if work_dir.exists():
            shutil.rmtree(work_dir, ignore_errors=True)


def fetch_latest_jpeg(camera_url: str, timeout: float = 15.0) -> bytes:
    url = f"{camera_url.rstrip('/')}/api/photos/latest?ts={int(threading.get_ident())}"
    req = urllib.request.Request(url, method="GET")
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        if resp.status != 200:
            raise RuntimeError(f"Camera returned HTTP {resp.status}")
        return resp.read()


def run_openfocus_merge(
    state: ServerState,
    session: StackSession,
) -> None:
    try:
        info = state.openfocus_info
        if not info.get("available") or not info.get("cmd"):
            raise RuntimeError(info.get("reason") or "OpenFocus is not available")

        cmd = [
            *info["cmd"],
            "--input",
            str(session.input_dir),
            "--output",
            str(session.output_file),
            "--method",
            DEFAULT_OPENFOCUS_METHOD,
            "--align",
            DEFAULT_OPENFOCUS_ALIGN,
        ]
        proc = subprocess.run(
            cmd,
            cwd=info.get("cwd"),
            capture_output=True,
            text=True,
            timeout=300,
        )
        if proc.returncode != 0:
            detail = (proc.stderr or proc.stdout or "").strip()
            tail = detail[-500:] if detail else f"exit code {proc.returncode}"
            raise RuntimeError(f"OpenFocus failed: {tail}")

        if not session.output_file.is_file():
            # Fallback if OpenFocus wrote output with a different extension or inside a dir
            jpgs = sorted(session.work_dir.glob("*.jpg")) + sorted(session.work_dir.glob("*.jpeg"))
            if jpgs:
                shutil.copyfile(jpgs[0], session.output_file)
            else:
                raise RuntimeError("OpenFocus completed without producing output.jpg")

        with session.lock:
            session.status = "done"
    except Exception as exc:
        with session.lock:
            session.status = "error"
            session.error = str(exc)


class CompanionHandler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "PMCAHostBackend/1.0"

    @property
    def app_state(self) -> ServerState:
        return self.server.app_state  # type: ignore[attr-defined]

    def log_message(self, format: str, *args: Any) -> None:
        sys.stderr.write(f"[host-server] {self.address_string()} - {format % args}\n")

    def _send_json(self, status: int, payload: Any) -> None:
        raw = json.dumps(payload).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(raw)

    def _drain_request_body(self) -> None:
        length = int(self.headers.get("Content-Length") or 0)
        if length > 0:
            self.rfile.read(length)

    def _read_json_body(self) -> dict[str, Any]:
        length = int(self.headers.get("Content-Length") or 0)
        if length <= 0:
            return {}
        raw = self.rfile.read(length)
        if not raw:
            return {}
        return json.loads(raw.decode("utf-8"))

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path == "/local-api/capabilities":
            self._send_json(200, {
                "backend": True,
                "cameraUrl": self.app_state.camera_url,
                "openfocus": {
                    "available": bool(self.app_state.openfocus_info.get("available")),
                    "reason": self.app_state.openfocus_info.get("reason", ""),
                },
            })
            return

        m_res = re.match(r"^/local-api/stacks/([A-Za-z0-9._-]+)/result$", path)
        if m_res:
            session = self.app_state.get_session(m_res.group(1))
            if not session or not session.output_file.is_file():
                self._send_json(404, {"error": "Merged result not found"})
                return
            data = session.output_file.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "image/jpeg")
            self.send_header("Content-Length", str(len(data)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Access-Control-Allow-Origin", "*")
            self.end_headers()
            self.wfile.write(data)
            return

        m_stat = re.match(r"^/local-api/stacks/([A-Za-z0-9._-]+)$", path)
        if m_stat:
            session = self.app_state.get_session(m_stat.group(1))
            if not session:
                self._send_json(404, {"error": "Stack session not found"})
                return
            with session.lock:
                self._send_json(200, {
                    "sessionId": session.session_id,
                    "status": session.status,
                    "frameCount": session.frame_count,
                    "error": session.error,
                    "savedFilename": session.saved_filename,
                })
            return

        if path.startswith("/api/"):
            self._proxy_camera("GET")
            return

        if path in ("/", "/index.html"):
            if not self.app_state.web_index.is_file():
                msg = b"web-client/dist/index.html not found. Run 'npm run build' in web-client/."
                self.send_response(404)
                self.send_header("Content-Type", "text/plain; charset=utf-8")
                self.send_header("Content-Length", str(len(msg)))
                self.end_headers()
                self.wfile.write(msg)
                return
            data = self.app_state.web_index.read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)
            return

        self._send_json(404, {"error": "Not found"})

    def do_POST(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path

        if path == "/local-api/stacks":
            try:
                body = self._read_json_body()
                session_id = str(body.get("sessionId") or "").strip()
                if not session_id:
                    self._send_json(400, {"error": "sessionId is required"})
                    return
                cam_url = body.get("cameraUrl")
                session = self.app_state.create_session(session_id, str(cam_url) if cam_url else None)
                self._send_json(200, {"ok": True, "sessionId": session.session_id})
            except Exception as exc:
                self._send_json(400, {"error": str(exc)})
            return

        m_grab = re.match(r"^/local-api/stacks/([A-Za-z0-9._-]+)/grab-latest$", path)
        if m_grab:
            session = self.app_state.get_session(m_grab.group(1))
            if not session:
                self._drain_request_body()
                self._send_json(404, {"error": "Stack session not found"})
                return
            try:
                body = self._read_json_body()
                cam_url = str(body.get("cameraUrl") or session.camera_url)
                jpeg_bytes = fetch_latest_jpeg(cam_url)
                with session.lock:
                    session.frame_count += 1
                    idx = session.frame_count
                    frame_path = session.input_dir / f"{idx:03d}.jpg"
                frame_path.write_bytes(jpeg_bytes)
                self._send_json(200, {"ok": True, "frameCount": idx, "bytes": len(jpeg_bytes)})
            except Exception as exc:
                self._send_json(500, {"error": str(exc)})
            return

        m_merge = re.match(r"^/local-api/stacks/([A-Za-z0-9._-]+)/merge$", path)
        if m_merge:
            self._drain_request_body()
            session = self.app_state.get_session(m_merge.group(1))
            if not session:
                self._send_json(404, {"error": "Stack session not found"})
                return
            if not self.app_state.openfocus_info.get("available"):
                self._send_json(400, {"error": self.app_state.openfocus_info.get("reason") or "OpenFocus unavailable"})
                return
            try:
                with session.lock:
                    if session.frame_count < 2:
                        self._send_json(400, {"error": f"Need at least 2 frames to merge (got {session.frame_count})"})
                        return
                    if session.status == "merging":
                        self._send_json(202, {"ok": True, "status": "merging"})
                        return
                    session.status = "merging"
                    session.error = None
                worker = threading.Thread(
                    target=run_openfocus_merge,
                    args=(self.app_state, session),
                    daemon=True,
                )
                worker.start()
                self._send_json(202, {"ok": True, "status": "merging"})
            except Exception as exc:
                self._send_json(500, {"error": str(exc)})
            return

        if path.startswith("/api/"):
            self._proxy_camera("POST")
            return

        self._drain_request_body()
        self._send_json(404, {"error": "Not found"})

    def do_DELETE(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        m_del = re.match(r"^/local-api/stacks/([A-Za-z0-9._-]+)$", parsed.path)
        if m_del:
            self._drain_request_body()
            self.app_state.delete_session(m_del.group(1))
            self._send_json(200, {"ok": True})
            return

        if parsed.path.startswith("/api/"):
            self._proxy_camera("DELETE")
            return

        self._drain_request_body()
        self._send_json(404, {"error": "Not found"})

    def _proxy_camera(self, method: str) -> None:
        cam = urllib.parse.urlparse(self.app_state.camera_url)
        host = cam.hostname or "127.0.0.1"
        port = cam.port or (443 if cam.scheme == "https" else 80)

        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length) if length > 0 else None

        fwd_headers: dict[str, str] = {}
        for k in ("Content-Type", "Accept", "Cache-Control"):
            v = self.headers.get(k)
            if v:
                fwd_headers[k] = v

        conn: http.client.HTTPConnection | None = None
        try:
            conn_cls = http.client.HTTPSConnection if cam.scheme == "https" else http.client.HTTPConnection
            conn = conn_cls(host, port, timeout=30)
            conn.request(method, self.path, body=body, headers=fwd_headers)
            resp = conn.getresponse()

            self.send_response(resp.status, resp.reason)
            hop_by_hop = {"transfer-encoding", "connection", "keep-alive", "proxy-authenticate", "proxy-authorization", "te", "trailers", "upgrade"}
            for k, v in resp.getheaders():
                if k.lower() in hop_by_hop:
                    continue
                self.send_header(k, v)
            self.end_headers()

            while True:
                chunk = resp.read(16384)
                if not chunk:
                    break
                self.wfile.write(chunk)
                self.wfile.flush()
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception as exc:
            try:
                self._send_json(502, {"error": f"Camera proxy error: {exc}"})
            except Exception:
                pass
        finally:
            if conn:
                try:
                    conn.close()
                except Exception:
                    pass


def main() -> None:
    repo_root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description="Sony PMCA WebControl host companion server")
    parser.add_argument("--host", default="0.0.0.0", help="Bind host (default: 0.0.0.0)")
    parser.add_argument("--port", type=int, default=8000, help="Bind port (default: 8000)")
    parser.add_argument(
        "--camera",
        default=os.environ.get("CAMERA_URL", "http://192.168.0.250:8080"),
        help="Camera base URL (default: http://192.168.0.250:8080)",
    )
    parser.add_argument(
        "--openfocus-dir",
        default=os.environ.get("OPENFOCUS_DIR"),
        help="Path to Xinzhe99/OpenFocus repository (optional if 'openfocus' is in PATH)",
    )
    parser.add_argument(
        "--openfocus-python",
        default=os.environ.get("OPENFOCUS_PYTHON"),
        help="Python executable to run OpenFocus (optional)",
    )
    parser.add_argument(
        "--web-dist",
        default=str(repo_root / "web-client" / "dist" / "index.html"),
        help="Path to built web-client/dist/index.html",
    )
    args = parser.parse_args()

    openfocus_info = detect_openfocus(args.openfocus_dir, args.openfocus_python)
    state = ServerState(
        camera_url=args.camera,
        web_index=Path(args.web_dist).expanduser().resolve(),
        openfocus_info=openfocus_info,
    )

    ThreadingHTTPServer.daemon_threads = True
    ThreadingHTTPServer.allow_reuse_address = True
    server = ThreadingHTTPServer((args.host, args.port), CompanionHandler)
    server.app_state = state  # type: ignore[attr-defined]

    print(f"PMCA Host Backend listening on http://{args.host}:{args.port}")
    print(f"Camera target : {state.camera_url}")
    print(f"OpenFocus     : {openfocus_info['available']} - {openfocus_info['reason']}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down...")
        server.shutdown()


if __name__ == "__main__":
    main()
