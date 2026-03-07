export default {
  async prepareSmartFillImage(
    imageUri: string,
    tolerance: number,
  ): Promise<number> {
    throw new Error("SmartFillSegmentation is not implemented for web yet.");
  },

  async prepareSmartFillLookup(
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
  }> {
    throw new Error("SmartFillSegmentation is not implemented for web yet.");
  },

  async getSmartFillMask(
    imageUri: string,
    startX: number,
    startY: number,
    tolerance: number,
  ): Promise<string> {
    throw new Error("SmartFillSegmentation is not implemented for web yet.");
  },
};
