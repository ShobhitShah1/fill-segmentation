import SmartFillSegmentationModule from "./SmartFillSegmentationModule";
import {
  SmartFillConfig,
  SmartFillPreparedLookup,
  SmartFillPreparationConfig,
} from "./SmartFillSegmentation.types";

export {
  SmartFillConfig,
  SmartFillPreparedLookup,
  SmartFillPreparationConfig,
};

export async function prepareSmartFillImage(
  config: SmartFillPreparationConfig,
): Promise<number> {
  const { imageUri, tolerance = 20 } = config;

  if (!imageUri) {
    throw new Error("SmartFillSegmentation: Missing required parameter imageUri");
  }

  return await SmartFillSegmentationModule.prepareSmartFillImage(
    imageUri,
    tolerance,
  );
}

export async function prepareSmartFillLookup(
  config: SmartFillPreparationConfig,
): Promise<SmartFillPreparedLookup> {
  const { imageUri, tolerance = 20 } = config;

  if (!imageUri) {
    throw new Error("SmartFillSegmentation: Missing required parameter imageUri");
  }

  return await SmartFillSegmentationModule.prepareSmartFillLookup(
    imageUri,
    tolerance,
  );
}

/**
 * Calculates a closed SVG path (`d` attribute string) for the region rooted at
 * `(startX, startY)`.
 */
export async function getSmartFillMask(
  config: SmartFillConfig,
): Promise<string> {
  const { imageUri, startX, startY, tolerance = 20 } = config;

  if (!imageUri || startX === undefined || startY === undefined) {
    throw new Error(
      "SmartFillSegmentation: Missing required parameters (imageUri, startX, startY)",
    );
  }

  return await SmartFillSegmentationModule.getSmartFillMask(
    imageUri,
    startX,
    startY,
    tolerance,
  );
}
