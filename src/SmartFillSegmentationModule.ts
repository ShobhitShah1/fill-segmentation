import { NativeModule, requireNativeModule } from "expo";

declare class SmartFillSegmentationModule extends NativeModule {
  /**
   * Prepares and caches paintable regions for an image so later region lookups
   * can happen without reloading or rescanning the bitmap.
   */
  prepareSmartFillImage(imageUri: string, tolerance: number): Promise<number>;

  /**
   * Prepares and returns a row-wise lookup table so region resolution can
   * happen synchronously in JavaScript during drawing.
   */
  prepareSmartFillLookup(
    imageUri: string,
    tolerance: number,
  ): Promise<{
    width: number;
    height: number;
    regionCount: number;
    rows: number[][];
    regionPaths: Record<string, string>;
    regionPixelCounts: Record<string, number>;
    regionTouchesEdge: Record<string, boolean>;
  }>;

  /**
   * Generates an SVG path for the region at the given image coordinate.
   */
  getSmartFillMask(
    imageUri: string,
    startX: number,
    startY: number,
    tolerance: number,
  ): Promise<string>;
}

export default requireNativeModule<SmartFillSegmentationModule>(
  "SmartFillSegmentation",
);
