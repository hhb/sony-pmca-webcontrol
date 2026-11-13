"""Python client SDK for Sony PMCA WebControl HTTP Server."""

import json
import time
import urllib.parse
import urllib.request
from typing import Any, Dict, Optional


class SonyPmcaClient:
    """HTTP client to control a Sony PlayMemories camera running the PMCA WebControl APK."""

    def __init__(self, host: str, port: int = 8080, timeout: float = 5.0) -> None:
        self.base_url = f"http://{host}:{port}"
        self.timeout = timeout

    def _request(self, path: str, method: str = "GET") -> bytes:
        req = urllib.request.Request(f"{self.base_url}{path}", method=method)
        with urllib.request.urlopen(req, timeout=self.timeout) as resp:
            return resp.read()

    def _json_request(self, path: str, method: str = "GET") -> Dict[str, Any]:
        raw = self._request(path, method=method)
        return json.loads(raw.decode("utf-8"))

    def get_status(self) -> Dict[str, Any]:
        """Return camera state, currentFocus, maxFocus, aperture, shutter, iso, etc."""
        return self._json_request("/api/status", method="GET")

    def get_liveview_jpeg(self) -> bytes:
        """Fetch the latest live-view preview frame as raw JPEG bytes."""
        return self._request("/api/liveview.jpg", method="GET")

    def save_liveview(self, output_path: str) -> str:
        """Fetch live-view JPEG and save it to disk."""
        data = self.get_liveview_jpeg()
        with open(output_path, "wb") as f:
            f.write(data)
        return output_path

    def drive_focus(self, direction: str = "far", speed: int = 1) -> Dict[str, Any]:
        """Drive focus one step in 'near' or 'far' direction at speed 1..4."""
        qs = urllib.parse.urlencode({"dir": direction, "speed": int(speed)})
        return self._json_request(f"/api/focus/drive?{qs}", method="POST")

    def goto_focus(self, position: int, wait: bool = True, timeout: float = 15.0) -> Dict[str, Any]:
        """Move focus to an absolute scale position."""
        qs = urllib.parse.urlencode({"pos": int(position)})
        res = self._json_request(f"/api/focus/goto?{qs}", method="POST")
        if wait:
            self.wait_until_idle(timeout=timeout)
        return res

    def start_bracket(
        self, min_pos: int, max_pos: int, shots: int = 15, wait: bool = False, timeout: float = 300.0
    ) -> Dict[str, Any]:
        """Start automated focus bracketing from min_pos to max_pos."""
        qs = urllib.parse.urlencode({"min": int(min_pos), "max": int(max_pos), "shots": int(shots)})
        res = self._json_request(f"/api/bracket?{qs}", method="POST")
        if wait:
            self.wait_until_idle(timeout=timeout)
        return res

    def abort_bracket(self) -> Dict[str, Any]:
        """Abort an active focus bracket sequence."""
        return self._json_request("/api/bracket/abort", method="POST")

    def capture(self, wait: bool = True, timeout: float = 15.0) -> Dict[str, Any]:
        """Trigger a single shutter release."""
        res = self._json_request("/api/capture", method="POST")
        if wait:
            self.wait_until_idle(timeout=timeout)
        return res

    def set_exposure(
        self,
        aperture: Optional[str] = None,
        shutter: Optional[str] = None,
        iso: Optional[int] = None,
    ) -> Dict[str, Any]:
        """Adjust aperture ('inc'/'dec'), shutter ('inc'/'dec'), or ISO (e.g. 100)."""
        params: Dict[str, Any] = {}
        if aperture is not None:
            params["aperture"] = aperture
        if shutter is not None:
            params["shutter"] = shutter
        if iso is not None:
            params["iso"] = int(iso)
        qs = urllib.parse.urlencode(params)
        return self._json_request(f"/api/exposure?{qs}", method="POST")

    def wait_until_idle(self, timeout: float = 60.0, poll_interval: float = 0.2) -> Dict[str, Any]:
        """Poll /api/status until camera state returns to 'idle'."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            st = self.get_status()
            if st.get("state") == "idle":
                return st
            if st.get("state") == "error":
                raise RuntimeError(f"Camera entered error state: {st}")
            time.sleep(poll_interval)
        raise TimeoutError(f"Timed out waiting for camera idle after {timeout}s")


A6000MacroClient = SonyPmcaClient
