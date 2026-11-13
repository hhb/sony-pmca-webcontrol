"""Zero-dependency Python client SDK for Sony PMCA WebControl.

Built on top of the thin camera REST API:
- /api/state, /api/events (long-polling)
- /api/camera/<action> (CameraEx commands & queries)
- /api/modifier (reflection proxy for CameraEx.ParametersModifier)
- /api/params (raw android.hardware.Camera.Parameters)
- /api/liveview.jpg, /api/photos*
"""

import json
import os
import time
import urllib.parse
import urllib.request
from typing import Any, Dict, List, Optional


class SonyPmcaClient:
    """HTTP client for a camera running Sony PMCA WebControl."""

    FOCUS_NEAR = 0
    FOCUS_FAR = 1
    ZOOM_TELE = 0
    ZOOM_WIDE = 1

    def __init__(self, host: str, port: int = 8080, timeout: float = 10.0) -> None:
        self.base_url = f"http://{host}:{port}"
        self.timeout = timeout

    def _request_json(
        self,
        path: str,
        method: str = "GET",
        body: Optional[Dict[str, Any]] = None,
        timeout: Optional[float] = None,
    ) -> Dict[str, Any]:
        url = f"{self.base_url}{path}"
        data = json.dumps(body).encode("utf-8") if body is not None else None
        headers = {"Content-Type": "application/json"} if data is not None else {}
        req = urllib.request.Request(url, data=data, headers=headers, method=method)
        with urllib.request.urlopen(req, timeout=timeout or self.timeout) as resp:
            return json.loads(resp.read().decode("utf-8"))

    def _request_bytes(self, path: str, timeout: Optional[float] = None) -> bytes:
        url = f"{self.base_url}{path}"
        req = urllib.request.Request(url, method="GET")
        with urllib.request.urlopen(req, timeout=timeout or self.timeout) as resp:
            return resp.read()

    # --- State & Event Long-Polling ---

    def get_state(self) -> Dict[str, Any]:
        """Return latest snapshots of Aperture, Shutter, Focus, Zoom, Lens, and seq."""
        return self._request_json("/api/state")

    def get_events(self, since: int = 0, timeout: int = 10) -> Dict[str, Any]:
        """Long-poll for camera callback events newer than `since`."""
        return self._request_json(
            f"/api/events?since={since}&timeout={timeout}",
            timeout=max(self.timeout, timeout + 5.0),
        )

    def wait_for_event(
        self,
        event_type: str,
        since: Optional[int] = None,
        timeout: float = 15.0,
    ) -> Optional[Dict[str, Any]]:
        """Block until an event of `event_type` arrives or timeout expires."""
        seq = self.get_state().get("seq", 0) if since is None else since
        deadline = time.time() + timeout
        while time.time() < deadline:
            remaining = max(1, int(deadline - time.time()))
            res = self.get_events(since=seq, timeout=min(10, remaining))
            seq = res.get("seq", seq)
            for ev in res.get("events", []):
                if ev.get("type") == event_type:
                    return ev
        return None

    # --- Raw CameraEx Commands ---

    def camera(self, action: str, **params: Any) -> Dict[str, Any]:
        """Call any whitelisted /api/camera/<action> endpoint."""
        qs = urllib.parse.urlencode({k: v for k, v in params.items() if v is not None})
        path = f"/api/camera/{action}" + (f"?{qs}" if qs else "")
        method = "GET" if action in ("capabilities", "getLensInfo", "getInhibitionInfo") else "POST"
        return self._request_json(path, method=method)

    def capabilities(self) -> Dict[str, Any]:
        """Query CameraEx.LensInfo and all ParametersModifier is*Supported() flags."""
        return self.camera("capabilities")

    # --- Raw ParametersModifier Reflection Proxy ---

    def modifier_list(self) -> Dict[str, Any]:
        """List all available ParametersModifier methods and their signatures."""
        return self._request_json("/api/modifier")

    def modifier_get(self, method_name: str, *args: Any) -> Any:
        """Invoke a ParametersModifier getter/query and return its `result`."""
        qs = urllib.parse.urlencode({f"arg{i}": a for i, a in enumerate(args)})
        path = f"/api/modifier/{method_name}" + (f"?{qs}" if qs else "")
        return self._request_json(path).get("result")

    def modifier_batch_get(self, *method_names: str) -> Dict[str, Any]:
        """Read multiple parameterless ParametersModifier getters in a single IPC call."""
        qs = urllib.parse.urlencode({"get": ",".join(method_names)})
        return self._request_json(f"/api/modifier?{qs}").get("results", {})

    def modifier_set(self, method_name: str, *args: Any) -> Dict[str, Any]:
        """Invoke a ParametersModifier setter."""
        body: Dict[str, Any] = {"args": list(args)} if len(args) > 1 else {"v": args[0] if args else None}
        return self._request_json(f"/api/modifier/{method_name}", method="POST", body=body)

    def modifier_batch_set(self, **assignments: Any) -> Dict[str, Any]:
        """Apply multiple ParametersModifier setters in one setParameters() call."""
        return self._request_json("/api/modifier", method="POST", body=assignments)

    # --- Raw android.hardware.Camera.Parameters ---

    def params_get(self, key: Optional[str] = None) -> Any:
        """Read all or a single key from Camera.Parameters."""
        if key:
            return self._request_json(f"/api/params/{urllib.parse.quote(key)}").get("value")
        return self._request_json("/api/params").get("params", {})

    def params_set(self, **updates: Any) -> Dict[str, Any]:
        """Set raw key-value pairs on Camera.Parameters."""
        return self._request_json("/api/params", method="POST", body=updates)

    # --- LiveView & Photo Downloads ---

    def get_liveview_jpeg(self) -> bytes:
        return self._request_bytes(f"/api/liveview.jpg?t={int(time.time() * 1000)}")

    def save_liveview(self, output_path: str) -> str:
        data = self.get_liveview_jpeg()
        with open(output_path, "wb") as f:
            f.write(data)
        return output_path

    def list_photos(self, limit: int = 50) -> List[Dict[str, Any]]:
        return self._request_json(f"/api/photos?limit={limit}").get("photos", [])

    def download_photo(self, output_path: str, remote_path: Optional[str] = None) -> str:
        if remote_path:
            qs = urllib.parse.urlencode({"path": remote_path})
            data = self._request_bytes(f"/api/photos/download?{qs}", timeout=30.0)
        else:
            data = self._request_bytes("/api/photos/latest", timeout=30.0)
        parent = os.path.dirname(os.path.abspath(output_path))
        if parent:
            os.makedirs(parent, exist_ok=True)
        with open(output_path, "wb") as f:
            f.write(data)
        return output_path

    # --- High-Level Client-Side Helpers ---

    def goto_focus(self, target_pos: int, timeout: float = 15.0) -> int:
        """Move manual focus to `target_pos` using client-side closed-loop control."""
        st = self.get_state()
        focus = st.get("focus", {})
        cur = int(focus.get("currentPosition", 0))
        max_pos = int(focus.get("maxPosition", 0))
        if max_pos > 0:
            target_pos = max(0, min(max_pos, target_pos))
        else:
            target_pos = max(0, target_pos)

        if cur == target_pos:
            return cur

        seq = st.get("seq", 0)
        # Try direct shiftFocusPosition first
        delta = target_pos - cur
        res = self.camera("shiftFocusPosition", n=delta)
        if res.get("result") is True:
            ev = self.wait_for_event("FocusDrive", since=seq, timeout=2.0)
            if ev:
                cur = int(ev.get("data", {}).get("currentPosition", cur))
                if cur == target_pos:
                    return cur

        # Closed-loop fallback via startOneShotFocusDrive
        deadline = time.time() + timeout
        stalled = 0
        while cur != target_pos and time.time() < deadline and stalled < 10:
            diff = target_pos - cur
            direction = self.FOCUS_FAR if diff > 0 else self.FOCUS_NEAR
            abs_d = abs(diff)
            speed = 4 if abs_d > 25 else 3 if abs_d > 15 else 2 if abs_d > 6 else 1
            self.camera("startOneShotFocusDrive", dir=direction, speed=speed)
            ev = self.wait_for_event("FocusDrive", since=seq, timeout=1.0)
            if ev:
                seq = ev.get("seq", seq)
                new_pos = int(ev.get("data", {}).get("currentPosition", cur))
                if new_pos == cur:
                    stalled += 1
                else:
                    stalled = 0
                cur = new_pos
            else:
                stalled += 1
        return cur

    def capture_and_download(
        self,
        output_path: Optional[str] = None,
        timeout: float = 15.0,
    ) -> str:
        """Trigger capture, wait for StoreImageComplete event, and download the photo."""
        seq = self.get_state().get("seq", 0)
        self.camera("burstableTakePicture")
        ev = self.wait_for_event("StoreImageComplete", since=seq, timeout=timeout)
        remote_path = None
        filename = f"photo_{int(time.time())}.jpg"
        if ev:
            data = ev.get("data", {})
            dir_name = data.get("DirectoryName")
            file_name = data.get("FileName")
            if file_name:
                filename = file_name
            if dir_name and file_name:
                # Try locating matching SD card path from /api/photos
                for p in self.list_photos(limit=10):
                    if p.get("name") == file_name:
                        remote_path = p.get("path")
                        break
        target = output_path or filename
        return self.download_photo(target, remote_path=remote_path)

    def focus_stack(
        self,
        min_pos: int,
        max_pos: int,
        shots: int,
        save_dir: Optional[str] = None,
    ) -> List[str]:
        """Execute a client-side focus bracket sequence and optionally download each shot."""
        if shots < 1:
            return []
        if save_dir:
            os.makedirs(save_dir, exist_ok=True)
        positions = (
            [min_pos]
            if shots == 1
            else [round(min_pos + (max_pos - min_pos) * i / (shots - 1)) for i in range(shots)]
        )
        saved_files: List[str] = []
        for idx, pos in enumerate(positions, 1):
            actual = self.goto_focus(pos)
            if save_dir:
                out_file = os.path.join(save_dir, f"stack_{idx:03d}_focus_{actual}.jpg")
                self.capture_and_download(out_file)
                saved_files.append(out_file)
            else:
                seq = self.get_state().get("seq", 0)
                self.camera("burstableTakePicture")
                self.wait_for_event("Shutter", since=seq, timeout=15.0)
        return saved_files
