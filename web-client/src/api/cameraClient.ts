export const FOCUS_NEAR = 0;
export const FOCUS_FAR = 1;
export const ZOOM_TELE = 0;
export const ZOOM_WIDE = 1;

export type RpcTarget =
  | 'camera'
  | 'modifier'
  | 'params'
  | 'static:System'
  | 'static:Temperature'
  | 'static:ScalarProperties'
  | string;

export interface RpcCall {
  target: RpcTarget;
  method: string;
  args?: unknown[];
}

export interface RpcResult<T = unknown> {
  ok: boolean;
  target: string;
  method: string;
  result?: T;
  error?: string;
}

export interface CameraState {
  ok: boolean;
  seq: number;
  aperture?: {
    currentAperture?: number;
    currentAvailableMin?: number;
    currentAvailableMax?: number;
  };
  shutter?: {
    currentShutterSpeed_n?: number;
    currentShutterSpeed_d?: number;
  };
  focus?: {
    currentPosition?: number;
    maxPosition?: number;
  };
  zoom?: {
    opticalPosition?: number;
    opticalMagnification?: number;
    digitalPosition?: number;
    digitalMagnification?: number;
    stopped?: boolean;
  };
  autoIso?: number;
  focalLength?: number;
  powerZoom?: boolean | number;
  previewMagnification?: {
    enabled?: boolean;
    ratio?: number;
  };
  lastStoreImage?: {
    imageId?: number;
    file?: string;
  };
  lastShutterStatus?: number;
  capturing?: boolean;
  lens?: {
    LensName?: string;
    LensType?: string;
    FocalLength?: string | { tele?: number; wide?: number };
    MinFValue?: string | { tele?: number; wide?: number };
    MaxFValue?: string | { tele?: number; wide?: number };
  };
  wifi?: string | { ip?: string; port?: number };
}

export interface CameraEvent {
  seq: number;
  t: number;
  type: string;
  data?: Record<string, unknown>;
}

export interface EventsResponse {
  ok: boolean;
  seq: number;
  events: CameraEvent[];
}

export interface DescribeMethod {
  name: string;
  params: string[];
  returnType: string;
}

export interface DescribeResponse {
  ok: boolean;
  targets: Record<string, DescribeMethod[]>;
}

export interface DirEntry {
  name: string;
  path: string;
}

export interface PhotoEntry {
  name: string;
  path: string;
  size: number;
}

export interface PhotosResponse {
  ok: boolean;
  dir: string;
  totalPhotosInDir: number;
  dirs: DirEntry[];
  photos: PhotoEntry[];
}

const STORAGE_KEY = 'pmca_camera_base_url';

export function getDefaultCameraUrl(): string {
  const isHttpsPage =
    typeof window !== 'undefined' && window.location.protocol === 'https:';
  try {
    const saved = localStorage.getItem(STORAGE_KEY);
    if (saved) {
      if (!(isHttpsPage && saved.startsWith('http://'))) {
        return saved;
      }
    }
  } catch {
    // ignore storage errors
  }
  if (
    typeof window !== 'undefined' &&
    (isHttpsPage || window.location.port === '8080') &&
    window.location.protocol.startsWith('http')
  ) {
    return window.location.origin;
  }
  return 'http://192.168.0.250:8080';
}

export function saveCameraUrl(url: string): string {
  const normalized = url.trim().replace(/\/+$/, '');
  try {
    localStorage.setItem(STORAGE_KEY, normalized);
  } catch {
    // ignore
  }
  return normalized;
}

export class CameraClient {
  private baseUrl: string;

  constructor(baseUrl?: string) {
    this.baseUrl = (baseUrl ?? getDefaultCameraUrl()).replace(/\/+$/, '');
  }

  setBaseUrl(url: string): void {
    this.baseUrl = saveCameraUrl(url);
  }

  getBaseUrl(): string {
    return this.baseUrl;
  }

  private async requestJson<T>(path: string, init?: RequestInit): Promise<T> {
    const res = await fetch(`${this.baseUrl}${path}`, {
      ...init,
      headers: {
        ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
        ...(init?.headers ?? {}),
      },
    });
    const data = (await res.json()) as T & { ok?: boolean; error?: string };
    if (!res.ok || data.ok === false) {
      throw new Error(data.error || `HTTP ${res.status} ${res.statusText}`);
    }
    return data;
  }

  async call<T = unknown>(
    target: RpcTarget,
    method: string,
    args: unknown[] = [],
  ): Promise<T> {
    const data = await this.requestJson<RpcResult<T>>('/api/call', {
      method: 'POST',
      body: JSON.stringify({ target, method, args }),
    });
    return data.result as T;
  }

  async callBatch(calls: RpcCall[]): Promise<RpcResult[]> {
    const data = await this.requestJson<{ ok: boolean; results: RpcResult[] }>(
      '/api/call',
      {
        method: 'POST',
        body: JSON.stringify({ calls }),
      },
    );
    return data.results;
  }

  async describe(target?: string, q?: string): Promise<DescribeResponse> {
    const params = new URLSearchParams();
    if (target) params.set('target', target);
    if (q) params.set('q', q);
    const qs = params.toString();
    return this.requestJson<DescribeResponse>(`/api/describe${qs ? `?${qs}` : ''}`);
  }

  async getState(): Promise<CameraState> {
    return this.requestJson<CameraState>('/api/state');
  }

  async pollEvents(
    since: number,
    timeoutSec = 10,
    signal?: AbortSignal,
  ): Promise<EventsResponse> {
    return this.requestJson<EventsResponse>(
      `/api/events?since=${since}&timeout=${timeoutSec}`,
      { signal },
    );
  }

  async listPhotos(dir = '', limit = 100): Promise<PhotosResponse> {
    const params = new URLSearchParams();
    if (dir) params.set('dir', dir);
    params.set('limit', String(limit));
    return this.requestJson<PhotosResponse>(`/api/photos?${params.toString()}`);
  }

  liveViewUrl(ts = Date.now()): string {
    return `${this.baseUrl}/api/liveview.jpg?t=${ts}`;
  }

  latestPhotoUrl(ts = Date.now()): string {
    return `${this.baseUrl}/api/photos/latest?t=${ts}`;
  }

  photoDownloadUrl(path: string): string {
    return `${this.baseUrl}/api/photos/download?path=${encodeURIComponent(path)}`;
  }

  // --- Convenience Camera Controls ---

  takePicture(): Promise<unknown> {
    return this.call('camera', 'burstableTakePicture');
  }

  incrementAperture(): Promise<unknown> {
    return this.call('camera', 'incrementAperture');
  }

  decrementAperture(): Promise<unknown> {
    return this.call('camera', 'decrementAperture');
  }

  incrementShutter(): Promise<unknown> {
    return this.call('camera', 'incrementShutterSpeed');
  }

  decrementShutter(): Promise<unknown> {
    return this.call('camera', 'decrementShutterSpeed');
  }

  setIso(iso: number): Promise<unknown> {
    return this.call('camera', 'setISOSensitivity', [iso]);
  }

  focusStep(direction: 0 | 1, speed: 1 | 2 | 3 | 4 = 2): Promise<unknown> {
    return this.call('camera', 'startOneShotFocusDrive', [direction, speed]);
  }

  zoomStep(direction: 0 | 1, speed = 1): Promise<unknown> {
    return this.call('camera', 'startOneShotZoom', [direction, speed]);
  }

  /**
   * Closed-loop focus positioning using startOneShotFocusDrive (safe on ILCE-6000).
   */
  async gotoFocus(
    targetPos: number,
    tolerance = 2,
    maxSteps = 40,
    signal?: AbortSignal,
  ): Promise<number> {
    for (let i = 0; i < maxSteps; i++) {
      if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
      const state = await this.getState();
      const current = state.focus?.currentPosition;
      if (typeof current !== 'number') {
        throw new Error('Focus position unavailable (ensure lens is in MF)');
      }
      const diff = targetPos - current;
      if (Math.abs(diff) <= tolerance) {
        return current;
      }
      const dir = diff > 0 ? FOCUS_FAR : FOCUS_NEAR;
      const absDiff = Math.abs(diff);
      const speed: 1 | 2 | 3 | 4 =
        absDiff > 30 ? 4 : absDiff > 12 ? 3 : absDiff > 4 ? 2 : 1;
      await this.focusStep(dir, speed);
      await new Promise((r) => setTimeout(r, 120));
    }
    const finalState = await this.getState();
    return finalState.focus?.currentPosition ?? targetPos;
  }

  async runFocusStack(
    startPos: number,
    endPos: number,
    steps: number,
    settleMs: number,
    onProgress: (info: { index: number; total: number; targetPos: number; actualPos: number }) => void,
    signal?: AbortSignal,
  ): Promise<void> {
    const count = Math.max(2, steps);
    for (let i = 0; i < count; i++) {
      if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
      const ratio = i / (count - 1);
      const target = Math.round(startPos + (endPos - startPos) * ratio);
      const actual = await this.gotoFocus(target, 2, 40, signal);
      onProgress({ index: i + 1, total: count, targetPos: target, actualPos: actual });
      await new Promise((r) => setTimeout(r, settleMs));
      if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
      await this.takePicture();
      await new Promise((r) => setTimeout(r, 900));
    }
  }
}

export function formatAperture(raw?: number): string {
  if (!raw || raw <= 0) return '--';
  return `F${(raw / 100).toFixed(1).replace(/\.0$/, '')}`;
}

export function formatShutter(n?: number, d?: number): string {
  if (!n || !d) return '--';
  if (n >= d) {
    const sec = n / d;
    return `${Number.isInteger(sec) ? sec : sec.toFixed(1)}s`;
  }
  return `1/${Math.round(d / n)}`;
}

export function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(2)} MB`;
}
