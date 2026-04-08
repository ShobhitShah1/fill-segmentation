# smart-fill-segmentation

Native smart-fill segmentation for Expo and React Native.

Detect regions once, then do fast tap-fill or free-draw paint that stays inside object parts (hands, body sections, animal parts, background pockets, etc.).

## Why Use This

Most paint tools leak outside boundaries on mobile images. This package gives you:

- Native region detection (prepared once, reused while drawing)
- SVG paths per detected region
- Real-time JS lookup for smooth interaction
- Better edge handling for thin outlines and anti-aliased borders
- Android preprocessing backed by OpenCV for stronger local line detection

## Install

```sh
yarn add smart-fill-segmentation
```

Because this is a native module, rebuild your dev client after install:

```sh
yarn expo prebuild
yarn expo run:android
# or
yarn expo run:ios
```

Do not test in Expo Go.

On Android, the native module now pulls in the official OpenCV package during the Gradle build.

## 3-Minute Quick Start

### 1) Prepare lookup once per image

```ts
import { prepareSmartFillLookup } from "smart-fill-segmentation";

const lookup = await prepareSmartFillLookup({
  imageUri: localFileUri, // file:// URI
  tolerance: 14,          // line-art: 10-18, photos/textured: 16-28
});
```

### 2) Map touch coordinates to image pixel coordinates

```ts
function toImagePoint(
  touchX: number,
  touchY: number,
  renderedWidth: number,
  renderedHeight: number,
  imageWidth: number,
  imageHeight: number,
) {
  return {
    x: Math.max(0, Math.min(imageWidth - 1, Math.round((touchX / renderedWidth) * imageWidth))),
    y: Math.max(0, Math.min(imageHeight - 1, Math.round((touchY / renderedHeight) * imageHeight))),
  };
}
```

### 3) Resolve a region path (direct hit + nearest fallback)

```ts
import type { SmartFillPreparedLookup } from "smart-fill-segmentation";

export function resolveRegionPath(
  lookup: SmartFillPreparedLookup,
  x: number,
  y: number,
): string {
  const clampedX = Math.max(0, Math.min(lookup.width - 1, x));
  const clampedY = Math.max(0, Math.min(lookup.height - 1, y));

  // 1) Direct row hit
  const row = lookup.rows[clampedY] ?? [];
  for (let i = 0; i < row.length; i += 3) {
    const startX = row[i];
    const endXExclusive = row[i + 1];
    const regionId = row[i + 2];
    if (clampedX >= startX && clampedX < endXExclusive) {
      return lookup.regionPaths[String(regionId)] ?? "";
    }
  }

  // 2) Nearest fallback search
  const maxRadius = Math.max(10, Math.min(28, Math.round(Math.max(lookup.width, lookup.height) / 70)));
  let bestRegionId: number | null = null;
  let bestScore = Number.POSITIVE_INFINITY;

  for (let radius = 1; radius <= maxRadius; radius += 1) {
    const minY = Math.max(0, clampedY - radius);
    const maxY = Math.min(lookup.height - 1, clampedY + radius);
    const radiusSquared = radius * radius;

    for (let currentY = minY; currentY <= maxY; currentY += 1) {
      const scanRow = lookup.rows[currentY] ?? [];
      for (let i = 0; i < scanRow.length; i += 3) {
        const startX = scanRow[i];
        const endXExclusive = scanRow[i + 1];
        const regionId = scanRow[i + 2];

        let dx = 0;
        if (clampedX < startX) dx = startX - clampedX;
        else if (clampedX >= endXExclusive) dx = clampedX - (endXExclusive - 1);

        const dy = Math.abs(currentY - clampedY);
        const distanceSquared = dx * dx + dy * dy;
        if (distanceSquared > radiusSquared) continue;

        const key = String(regionId);
        const size = lookup.regionPixelCounts[key] ?? 999_999;
        const touchesEdge = lookup.regionTouchesEdge[key] ?? false;

        // Distance first, then mild edge penalty, then smaller-region preference.
        const score = distanceSquared * 1_000_000 + (touchesEdge ? 25_000 : 0) + Math.min(size, 200_000);

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

  // 3) Last-resort fallback
  const firstRegionId = Object.keys(lookup.regionPaths)[0];
  return firstRegionId ? lookup.regionPaths[firstRegionId] : "";
}
```

### 4) Render fill or paint clipped to that path

```tsx
import Svg, { ClipPath, Defs, Path } from "react-native-svg";

<Svg viewBox={`0 0 ${lookup.width} ${lookup.height}`}>
  <Defs>
    <ClipPath id="region-clip">
      <Path d={regionPath} />
    </ClipPath>
  </Defs>

  {/* Fill */}
  <Path d={regionPath} fill={fillColor} stroke={fillColor} strokeWidth={1.4} />

  {/* Free draw (kept inside region) */}
  <Path
    d={userStrokePath}
    clipPath="url(#region-clip)"
    stroke={strokeColor}
    strokeWidth={28}
    fill="none"
    strokeLinecap="round"
    strokeLinejoin="round"
  />
</Svg>
```

## API Reference

### `prepareSmartFillImage(config)`
Prepare and cache native segmentation data.

```ts
await prepareSmartFillImage({ imageUri, tolerance?: number });
```

### `prepareSmartFillLookup(config)`
Prepare + return lookup object for real-time JS region resolution.

```ts
const lookup = await prepareSmartFillLookup({ imageUri, tolerance?: number });
```

### `getSmartFillMask(config)`
Direct native call to resolve a single region path from one seed point.

```ts
const path = await getSmartFillMask({ imageUri, startX, startY, tolerance?: number });
```

### Types

`SmartFillPreparedLookup` includes:

- `width`, `height`
- `regionCount`
- `rows` (row runs encoded as `[startX, endXExclusive, regionId, ...]`)
- `regionPaths`
- `regionPixelCounts`
- `regionTouchesEdge`

## Tolerance Presets

- `10-14`: strict line-art, strong boundaries
- `15-22`: balanced default
- `23-35`: noisy images, anti-aliased outlines, textured assets

## Production Integration Checklist

1. Always convert remote image URLs to local `file://` before preparing.
2. Prepare lookup once per image and cache by `imageUri + tolerance`.
3. Keep touch mapping in original image coordinates.
4. Use region clipping for brush strokes, not per-point native calls.
5. Re-prepare lookup only when image/tolerance changes.

## Performance Tips

- Resize very large source images to ~1024 max side before preparation.
- Reuse lookup object while user paints.
- Keep paint operations in JS; avoid repeated native bridge calls during drag.

## Troubleshooting

### `TurboModuleRegistry.getEnforcing(...): 'SourceCode' could not be found`
Native app binary and JS bundle are out of sync.

```sh
yarn expo start -c
yarn expo run:android
# or
yarn expo run:ios
```

### Region misses or leaves tiny edge gaps

- Increase tolerance by `+2` to `+6`
- Ensure touch is mapped to original image pixel coordinates
- Use clipped stroke + fill stroke (shown above) for cleaner edges

### "No region found" in some parts

- Use nearest fallback resolver (copy-paste helper above)
- Lower very aggressive filtering in your own post-processing
- Prefer high-contrast line assets for best segmentation stability

## Example App

A complete integration is available in:

- `example/App.tsx`

Includes:

- Tap fill
- Free draw clipped to region
- Sample animal assets
- Pick-image workflow

## Package

- NPM: [smart-fill-segmentation](https://www.npmjs.com/package/smart-fill-segmentation)
