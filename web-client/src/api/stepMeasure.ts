export interface GridSharpness {
  cellEnergies: Float64Array;
  maxEnergy: number;
  mask: Uint8Array;
  activeCount: number;
}

const GRID_N = 12;
// Minimum average squared gradient per pixel to consider a cell textured & in focus
const MIN_TEXTURE_ENERGY = 120;

/**
 * Compute per-cell gradient energy over a GRID_N x GRID_N grid inside an ROI ImageData.
 */
export function computeRoiSharpness(
  img: ImageData,
  referencePeak?: number,
): GridSharpness {
  const { width, height, data } = img;
  const cellEnergies = new Float64Array(GRID_N * GRID_N);
  const cellCounts = new Uint32Array(GRID_N * GRID_N);

  const luma = new Float32Array(width * height);
  for (let i = 0, p = 0; i < luma.length; i++, p += 4) {
    luma[i] = 0.299 * data[p] + 0.587 * data[p + 1] + 0.114 * data[p + 2];
  }

  for (let y = 1; y < height - 1; y++) {
    const gyIdx = Math.min(GRID_N - 1, Math.floor((y * GRID_N) / height));
    const row = y * width;
    for (let x = 1; x < width - 1; x++) {
      const gxIdx = Math.min(GRID_N - 1, Math.floor((x * GRID_N) / width));
      const idx = row + x;
      const dx = luma[idx + 1] - luma[idx - 1];
      const dy = luma[idx + width] - luma[idx - width];
      const cellIdx = gyIdx * GRID_N + gxIdx;
      cellEnergies[cellIdx] += dx * dx + dy * dy;
      cellCounts[cellIdx] += 1;
    }
  }

  let maxEnergy = 0;
  for (let i = 0; i < cellEnergies.length; i++) {
    if (cellCounts[i] > 0) {
      cellEnergies[i] /= cellCounts[i];
    }
    if (cellEnergies[i] > maxEnergy) {
      maxEnergy = cellEnergies[i];
    }
  }

  const peak = Math.max(maxEnergy, referencePeak ?? 0);
  const threshold = Math.max(MIN_TEXTURE_ENERGY, peak * 0.45);
  const mask = new Uint8Array(GRID_N * GRID_N);
  let activeCount = 0;
  for (let i = 0; i < cellEnergies.length; i++) {
    if (cellEnergies[i] >= threshold) {
      mask[i] = 1;
      activeCount += 1;
    }
  }

  return { cellEnergies, maxEnergy, mask, activeCount };
}

export function computeMaskOverlap(a: GridSharpness, b: GridSharpness): number {
  if (a.activeCount === 0) return 0;
  let intersection = 0;
  for (let i = 0; i < a.mask.length; i++) {
    if (a.mask[i] && b.mask[i]) {
      intersection += 1;
    }
  }
  return intersection / a.activeCount;
}

export function isBaselineTextured(baseline: GridSharpness): boolean {
  return baseline.maxEnergy >= MIN_TEXTURE_ENERGY && baseline.activeCount >= 2;
}
