import type { SmartFillPreparedLookup } from "./SmartFillSegmentation.types";

function clamp(value: number, min: number, max: number): number {
  return Math.max(min, Math.min(max, value));
}

export function getSmartFillRegionIdAt(
  lookup: SmartFillPreparedLookup,
  x: number,
  y: number,
): number | null {
  if (lookup.width <= 0 || lookup.height <= 0) {
    return null;
  }

  const clampedX = clamp(Math.round(x), 0, lookup.width - 1);
  const clampedY = clamp(Math.round(y), 0, lookup.height - 1);
  const row = lookup.rows[clampedY];

  if (!row || row.length === 0) {
    return null;
  }

  for (let index = 0; index < row.length; index += 3) {
    const startX = row[index];
    const endXExclusive = row[index + 1];
    const regionId = row[index + 2];

    if (clampedX >= startX && clampedX < endXExclusive) {
      return regionId;
    }
  }

  return null;
}

function scoreRegionCandidate(
  lookup: SmartFillPreparedLookup,
  regionId: number,
  distanceSquared: number,
): number {
  const key = String(regionId);
  const size = lookup.regionPixelCounts[key] ?? 999_999;
  const touchesEdge = lookup.regionTouchesEdge[key] ?? false;

  return (
    distanceSquared * 1_000_000 +
    (touchesEdge ? 25_000 : 0) +
    Math.min(size, 200_000)
  );
}

export function resolveSmartFillRegionPath(
  lookup: SmartFillPreparedLookup,
  x: number,
  y: number,
): string {
  if (lookup.width <= 0 || lookup.height <= 0) {
    return "";
  }

  const clampedX = clamp(Math.round(x), 0, lookup.width - 1);
  const clampedY = clamp(Math.round(y), 0, lookup.height - 1);
  const directRegionId = getSmartFillRegionIdAt(lookup, clampedX, clampedY);

  if (directRegionId !== null) {
    const directPath = lookup.regionPaths[String(directRegionId)] ?? "";
    if (directPath) {
      return directPath;
    }
  }

  const maxRadius = Math.max(
    10,
    Math.min(28, Math.round(Math.max(lookup.width, lookup.height) / 70)),
  );
  let bestRegionId: number | null = null;
  let bestScore = Number.POSITIVE_INFINITY;

  for (let radius = 1; radius <= maxRadius; radius += 1) {
    const minY = Math.max(0, clampedY - radius);
    const maxY = Math.min(lookup.height - 1, clampedY + radius);
    const radiusSquared = radius * radius;

    for (let currentY = minY; currentY <= maxY; currentY += 1) {
      const row = lookup.rows[currentY];
      if (!row || row.length === 0) {
        continue;
      }

      for (let index = 0; index < row.length; index += 3) {
        const startX = row[index];
        const endXExclusive = row[index + 1];
        const regionId = row[index + 2];

        let dx = 0;
        if (clampedX < startX) dx = startX - clampedX;
        else if (clampedX >= endXExclusive) dx = clampedX - (endXExclusive - 1);

        const dy = Math.abs(currentY - clampedY);
        const distanceSquared = dx * dx + dy * dy;
        if (distanceSquared > radiusSquared) {
          continue;
        }

        const score = scoreRegionCandidate(lookup, regionId, distanceSquared);
        if (score < bestScore) {
          bestScore = score;
          bestRegionId = regionId;
        }
      }
    }

    if (bestRegionId !== null) {
      return lookup.regionPaths[String(bestRegionId)] ?? "";
    }
  }

  for (let currentY = 0; currentY < lookup.height; currentY += 1) {
    const row = lookup.rows[currentY];
    if (!row || row.length === 0) {
      continue;
    }

    for (let index = 0; index < row.length; index += 3) {
      const startX = row[index];
      const endXExclusive = row[index + 1];
      const regionId = row[index + 2];

      let dx = 0;
      if (clampedX < startX) dx = startX - clampedX;
      else if (clampedX >= endXExclusive) dx = clampedX - (endXExclusive - 1);

      const dy = Math.abs(currentY - clampedY);
      const distanceSquared = dx * dx + dy * dy;
      const score = scoreRegionCandidate(lookup, regionId, distanceSquared);

      if (score < bestScore) {
        bestScore = score;
        bestRegionId = regionId;
      }
    }
  }

  if (bestRegionId !== null) {
    return lookup.regionPaths[String(bestRegionId)] ?? "";
  }

  const fallbackRegionId = Object.keys(lookup.regionPaths)[0];
  return fallbackRegionId ? lookup.regionPaths[fallbackRegionId] ?? "" : "";
}
