export interface SmartFillConfig {
  /**
   * Local device file URI for the image to segment.
   * Example: 'file:///path/to/image.jpg'
   */
  imageUri: string;
  /**
   * The X coordinate (in pixels relative to image width) to seed the region lookup.
   */
  startX: number;
  /**
   * The Y coordinate (in pixels relative to image height) to seed the region lookup.
   */
  startY: number;
  /**
   * The tolerance (0-255) used by the native segmentation logic.
   * Default is 20.
   */
  tolerance?: number;
}

export interface SmartFillPreparationConfig {
  /**
   * Local device file URI for the image to prepare.
   */
  imageUri: string;
  /**
   * The tolerance (0-255) used by the native preparation step.
   * Default is 20.
   */
  tolerance?: number;
}

export interface SmartFillPreparedLookup {
  /**
   * The prepared image width in pixels.
   */
  width: number;
  /**
   * The prepared image height in pixels.
   */
  height: number;
  /**
   * Number of paintable closed regions detected in the image.
   */
  regionCount: number;
  /**
   * Row-wise lookup runs encoded as [startX, endXExclusive, regionId, ...].
   */
  rows: number[][];
  /**
   * SVG region paths keyed by region identifier.
   */
  regionPaths: Record<string, string>;
  /**
   * Pixel count keyed by region identifier.
   */
  regionPixelCounts: Record<string, number>;
  /**
   * Whether region touches image edge, keyed by region identifier.
   */
  regionTouchesEdge: Record<string, boolean>;
}
