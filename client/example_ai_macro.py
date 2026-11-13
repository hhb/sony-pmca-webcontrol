"""Example AI / automated mineral macro workflow using A6000MacroClient.

Demonstrates:
1. Checking highlight clipping on live-view frames and adjusting shutter speed.
2. Sweeping focus to find the sharpest front/back range via Laplacian variance.
3. Triggering automated in-camera focus bracketing.
"""

import argparse
import time
from typing import Tuple

import cv2
import numpy as np

from a6000_client import A6000MacroClient


def analyze_frame(jpeg_bytes: bytes) -> Tuple[float, float]:
    """Return (laplacian_sharpness, clipped_highlight_ratio)."""
    arr = np.frombuffer(jpeg_bytes, dtype=np.uint8)
    img = cv2.imdecode(arr, cv2.IMREAD_COLOR)
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    sharpness = float(cv2.Laplacian(gray, cv2.CV_64F).var())
    clipped_ratio = float(np.mean(gray >= 250))
    return sharpness, clipped_ratio


def main() -> None:
    parser = argparse.ArgumentParser(description="Automated mineral macro stacking workflow")
    parser.add_argument("--host", required=True, help="Camera IP address (e.g. 192.168.1.105)")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--min-focus", type=int, default=None, help="Optional manual start focus")
    parser.add_argument("--max-focus", type=int, default=None, help="Optional manual end focus")
    parser.add_argument("--shots", type=int, default=20, help="Number of focus bracket shots")
    args = parser.parse_args()

    cam = A6000MacroClient(args.host, args.port)
    status = cam.get_status()
    print("Initial camera status:", status)

    # Step 1: Check exposure for specular mineral highlights
    for _ in range(4):
        frame = cam.get_liveview_jpeg()
        sharpness, clipped = analyze_frame(frame)
        print(f"Exposure check -> sharpness={sharpness:.1f}, clipped={clipped:.3%}")
        if clipped > 0.03:
            print("Highlights clipped (>3%), increasing shutter speed...")
            cam.set_exposure(shutter="inc")
            time.sleep(0.4)
        else:
            break

    # Step 2: Determine focus range (or use provided bounds)
    if args.min_focus is not None and args.max_focus is not None:
        min_pos, max_pos = args.min_focus, args.max_focus
    else:
        max_scale = status.get("maxFocus", 200) or 200
        samples = []
        for pos in range(0, max_scale + 1, max(5, max_scale // 20)):
            cam.goto_focus(pos, wait=True)
            time.sleep(0.15)
            sharpness, _ = analyze_frame(cam.get_liveview_jpeg())
            samples.append((pos, sharpness))
            print(f"Focus sweep pos={pos} -> sharpness={sharpness:.1f}")

        peak_sharpness = max(s for _, s in samples)
        active = [p for p, s in samples if s >= peak_sharpness * 0.5]
        min_pos = max(0, min(active) - 5)
        max_pos = min(max_scale, max(active) + 5)

    print(f"Running focus bracket: {min_pos} -> {max_pos} ({args.shots} shots)")
    cam.start_bracket(min_pos, max_pos, shots=args.shots, wait=True, timeout=300.0)
    print("Focus bracket complete! RAW/JPEG files saved to camera SD card.")


if __name__ == "__main__":
    main()
