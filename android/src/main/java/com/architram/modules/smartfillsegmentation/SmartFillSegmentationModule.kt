package com.architram.modules.smartfillsegmentation

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.LinkedHashMap
import java.util.LinkedList
import java.util.Queue
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class SmartFillSegmentationModule : Module() {

  data class HorizontalRun(val startX: Int, val endXExclusive: Int)
  data class RegionRun(val regionId: Int, val startX: Int, val endXExclusive: Int)
  data class RegionMetadata(var pixelCount: Int = 0, var touchesEdge: Boolean = false)
  data class PreparedImage(
    val width: Int,
    val height: Int,
    val regionIds: IntArray,
    val regionPaths: Map<Int, String>,
    val rowRuns: List<List<Int>>,
    val regionPixelCounts: Map<Int, Int>,
    val regionTouchesEdge: Map<Int, Boolean>,
  )

  private val preparedImages = LinkedHashMap<String, PreparedImage>()

  override fun definition() = ModuleDefinition {
    Name("SmartFillSegmentation")

    AsyncFunction("prepareSmartFillImage") Coroutine { imageUri: String, tolerance: Int ->
      withContext(Dispatchers.Default) {
        val safeTolerance = tolerance.coerceIn(0, 255)
        val prepared = loadOrPrepareImage(imageUri, safeTolerance)
        prepared.regionPaths.size
      }
    }

    AsyncFunction("prepareSmartFillLookup") Coroutine { imageUri: String, tolerance: Int ->
      withContext(Dispatchers.Default) {
        val safeTolerance = tolerance.coerceIn(0, 255)
        val prepared = loadOrPrepareImage(imageUri, safeTolerance)

        mapOf(
          "width" to prepared.width,
          "height" to prepared.height,
          "regionCount" to prepared.regionPaths.size,
          "rows" to prepared.rowRuns,
          "regionPaths" to prepared.regionPaths.mapKeys { it.key.toString() },
          "regionPixelCounts" to prepared.regionPixelCounts.mapKeys { it.key.toString() },
          "regionTouchesEdge" to prepared.regionTouchesEdge.mapKeys { it.key.toString() },
        )
      }
    }

    AsyncFunction("getSmartFillMask") Coroutine { imageUri: String, startX: Float, startY: Float, tolerance: Int ->
      withContext(Dispatchers.Default) {
        val safeTolerance = tolerance.coerceIn(0, 255)
        val prepared = preparedImages[buildCacheKey(imageUri, safeTolerance)]
        if (prepared != null) {
          return@withContext lookupPreparedRegionPath(prepared, startX, startY)
        }

        val bitmap = loadBitmap(imageUri) ?: throw Exception("Failed to load image at $imageUri")

        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) {
          return@withContext ""
        }

        val x = startX.toInt().coerceIn(0, width - 1)
        val y = startY.toInt().coerceIn(0, height - 1)

        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val mask = buildConnectedMask(pixels, width, height, x, y, safeTolerance)
        generateSvgPathFromMask(mask, width, height)
      }
    }
  }

  private fun buildCacheKey(imageUri: String, tolerance: Int): String = "$imageUri|$tolerance"

  private fun loadOrPrepareImage(imageUri: String, tolerance: Int): PreparedImage {
    val key = buildCacheKey(imageUri, tolerance)
    preparedImages[key]?.let { return it }

    val bitmap = loadBitmap(imageUri) ?: throw Exception("Failed to load image at $imageUri")
    val prepared = buildPreparedImage(bitmap, tolerance)
    cachePreparedImage(imageUri, tolerance, prepared)
    return prepared
  }

  private fun cachePreparedImage(imageUri: String, tolerance: Int, prepared: PreparedImage) {
    val key = buildCacheKey(imageUri, tolerance)
    preparedImages.remove(key)
    preparedImages[key] = prepared

    while (preparedImages.size > 8) {
      val eldestKey = preparedImages.entries.firstOrNull()?.key ?: break
      preparedImages.remove(eldestKey)
    }
  }

  private fun lookupPreparedRegionPath(prepared: PreparedImage, startX: Float, startY: Float): String {
    if (prepared.width <= 0 || prepared.height <= 0) {
      return ""
    }

    val x = startX.toInt().coerceIn(0, prepared.width - 1)
    val y = startY.toInt().coerceIn(0, prepared.height - 1)
    val regionId = resolveRegionId(prepared, x, y)
    if (regionId <= 0) {
      return ""
    }

    return prepared.regionPaths[regionId] ?: ""
  }

  private fun resolveRegionId(prepared: PreparedImage, x: Int, y: Int): Int {
    val width = prepared.width
    val height = prepared.height

    val directRegion = prepared.regionIds[y * width + x]
    if (directRegion > 0 && prepared.regionPaths.containsKey(directRegion)) {
      return directRegion
    }

    val maxRadius = (max(width, height) / 90).coerceIn(8, 24)
    var bestRegionId = 0
    var bestScore = Long.MAX_VALUE

    for (radius in 1..maxRadius) {
      val minY = max(0, y - radius)
      val maxY = min(height - 1, y + radius)
      val radiusSquared = radius * radius

      for (currentY in minY..maxY) {
        val row = prepared.rowRuns[currentY]
        if (row.isEmpty()) {
          continue
        }

        var index = 0
        while (index <= row.size - 3) {
          val startX = row[index]
          val endXExclusive = row[index + 1]
          val candidateRegion = row[index + 2]
          index += 3

          if (candidateRegion <= 0 || !prepared.regionPaths.containsKey(candidateRegion)) {
            continue
          }

          var dx = 0
          if (x < startX) {
            dx = startX - x
          } else if (x >= endXExclusive) {
            dx = x - (endXExclusive - 1)
          }

          val dy = abs(currentY - y)
          val distanceSquared = dx * dx + dy * dy
          if (distanceSquared > radiusSquared) {
            continue
          }

          val score = scoreRegionCandidate(prepared, candidateRegion, distanceSquared)
          if (score < bestScore) {
            bestScore = score
            bestRegionId = candidateRegion
          }
        }
      }

      if (bestRegionId > 0) {
        return bestRegionId
      }
    }

    for (currentY in 0 until height) {
      val row = prepared.rowRuns[currentY]
      if (row.isEmpty()) {
        continue
      }

      var index = 0
      while (index <= row.size - 3) {
        val startX = row[index]
        val endXExclusive = row[index + 1]
        val candidateRegion = row[index + 2]
        index += 3

        if (candidateRegion <= 0 || !prepared.regionPaths.containsKey(candidateRegion)) {
          continue
        }

        var dx = 0
        if (x < startX) {
          dx = startX - x
        } else if (x >= endXExclusive) {
          dx = x - (endXExclusive - 1)
        }

        val dy = abs(currentY - y)
        val distanceSquared = dx * dx + dy * dy
        val score = scoreRegionCandidate(prepared, candidateRegion, distanceSquared)
        if (score < bestScore) {
          bestScore = score
          bestRegionId = candidateRegion
        }
      }
    }

    if (bestRegionId > 0) {
      return bestRegionId
    }

    return prepared.regionPaths.keys.firstOrNull() ?: 0
  }

  private fun scoreRegionCandidate(prepared: PreparedImage, regionId: Int, distanceSquared: Int): Long {
    val candidateSize = prepared.regionPixelCounts[regionId] ?: Int.MAX_VALUE
    val candidateTouchesEdge = prepared.regionTouchesEdge[regionId] ?: false
    return distanceSquared.toLong() * 1_000_000L +
      (if (candidateTouchesEdge) 25_000L else 0L) +
      min(candidateSize, 200_000).toLong()
  }

  private fun expandRegionsIntoBarrierPixels(
    regionIds: IntArray,
    barrierMask: BooleanArray,
    width: Int,
    height: Int,
    iterations: Int,
  ): IntArray {
    if (iterations <= 0) {
      return regionIds
    }

    var current = regionIds.copyOf()
    val neighborDx = intArrayOf(-1, 1, 0, 0)
    val neighborDy = intArrayOf(0, 0, -1, 1)

    repeat(iterations) {
      val next = current.copyOf()
      var changed = false

      for (y in 0 until height) {
        for (x in 0 until width) {
          val index = y * width + x
          if (!barrierMask[index] || current[index] > 0) {
            continue
          }

          var candidateRegionId = 0
          var ambiguous = false

          for (direction in 0..3) {
            val nextX = x + neighborDx[direction]
            val nextY = y + neighborDy[direction]
            if (nextX !in 0 until width || nextY !in 0 until height) {
              continue
            }

            val nextIndex = nextY * width + nextX
            val neighborRegionId = current[nextIndex]
            if (neighborRegionId <= 0) {
              continue
            }

            if (candidateRegionId == 0) {
              candidateRegionId = neighborRegionId
            } else if (candidateRegionId != neighborRegionId) {
              ambiguous = true
              break
            }
          }

          if (!ambiguous && candidateRegionId > 0) {
            next[index] = candidateRegionId
            changed = true
          }
        }
      }

      current = next
      if (!changed) {
        return current
      }
    }

    return current
  }

  private fun buildPreparedImage(bitmap: Bitmap, tolerance: Int): PreparedImage {
    val width = bitmap.width
    val height = bitmap.height
    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    var regionIds = IntArray(width * height)
    val regionMetadata = mutableMapOf<Int, RegionMetadata>()
    val queue: Queue<Int> = LinkedList()
    val inkThreshold = computeInkThreshold(tolerance)
    val barrierMask = buildBarrierMask(pixels, width, height, inkThreshold)
    val minimumRegionSize = ((width + height) / 90).coerceIn(4, 24)
    var nextRegionId = 1

    val dx = intArrayOf(-1, 1, 0, 0)
    val dy = intArrayOf(0, 0, -1, 1)

    for (index in pixels.indices) {
      if (regionIds[index] != 0 || barrierMask[index]) {
        continue
      }

      val regionId = nextRegionId++
      val metadata = RegionMetadata()
      regionMetadata[regionId] = metadata

      regionIds[index] = regionId
      queue.add(index)

      while (queue.isNotEmpty()) {
        val currentIndex = queue.poll() ?: continue
        val x = currentIndex % width
        val y = currentIndex / width

        metadata.pixelCount += 1
        if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
          metadata.touchesEdge = true
        }

        for (direction in 0..3) {
          val nextX = x + dx[direction]
          val nextY = y + dy[direction]
          if (nextX !in 0 until width || nextY !in 0 until height) {
            continue
          }

          val nextIndex = nextY * width + nextX
          if (regionIds[nextIndex] != 0 || barrierMask[nextIndex]) {
            continue
          }

          regionIds[nextIndex] = regionId
          queue.add(nextIndex)
        }
      }
    }

    val expandedRegionIds = expandRegionsIntoBarrierPixels(
      regionIds = regionIds,
      barrierMask = barrierMask,
      width = width,
      height = height,
      iterations = 2,
    )

    for (index in regionIds.indices) {
      val originalRegionId = regionIds[index]
      val expandedRegionId = expandedRegionIds[index]
      if (expandedRegionId <= 0 || expandedRegionId == originalRegionId) {
        continue
      }

      val metadata = regionMetadata[expandedRegionId] ?: continue
      metadata.pixelCount += 1

      val x = index % width
      val y = index / width
      if (x == 0 || y == 0 || x == width - 1 || y == height - 1) {
        metadata.touchesEdge = true
      }
    }

    regionIds = expandedRegionIds

    val eligibleRegionIds = regionMetadata
      .filterValues { metadata -> metadata.pixelCount >= minimumRegionSize }
      .keys
      .toMutableSet()

    if (eligibleRegionIds.isEmpty()) {
      for ((regionId, metadata) in regionMetadata) {
        if (metadata.pixelCount > 0) {
          eligibleRegionIds.add(regionId)
        }
      }
    }

    val pathBuilders = mutableMapOf<Int, StringBuilder>()
    val activeRuns = linkedMapOf<RegionRun, Int>()
    val lookupRows = MutableList(height) { mutableListOf<Int>() }

    for (y in 0 until height) {
      val rowRuns = mutableListOf<RegionRun>()
      var x = 0
      while (x < width) {
        val regionId = regionIds[y * width + x]
        if (regionId <= 0 || regionId !in eligibleRegionIds) {
          x += 1
          continue
        }

        val startX = x
        while (x < width && regionIds[y * width + x] == regionId) {
          x += 1
        }

        rowRuns.add(RegionRun(regionId, startX, x))
        lookupRows[y].add(startX)
        lookupRows[y].add(x)
        lookupRows[y].add(regionId)
      }

      val rowRunSet = rowRuns.toSet()
      val iterator = activeRuns.entries.iterator()
      while (iterator.hasNext()) {
        val entry = iterator.next()
        if (entry.key !in rowRunSet) {
          appendRectanglePath(
            pathBuilders.getOrPut(entry.key.regionId) { StringBuilder() },
            entry.key.startX,
            entry.value,
            entry.key.endXExclusive,
            y,
          )
          iterator.remove()
        }
      }

      for (run in rowRuns) {
        if (!activeRuns.containsKey(run)) {
          activeRuns[run] = y
        }
      }
    }

    for ((run, startY) in activeRuns) {
      appendRectanglePath(
        pathBuilders.getOrPut(run.regionId) { StringBuilder() },
        run.startX,
        startY,
        run.endXExclusive,
        height,
      )
    }

    val regionPaths = pathBuilders
      .mapValues { (_, builder) -> builder.toString().trim() }
      .filterValues { it.isNotEmpty() }

    val regionPixelCounts = mutableMapOf<Int, Int>()
    val regionTouchesEdge = mutableMapOf<Int, Boolean>()
    for ((regionId, metadata) in regionMetadata) {
      if (!regionPaths.containsKey(regionId)) {
        continue
      }
      regionPixelCounts[regionId] = metadata.pixelCount
      regionTouchesEdge[regionId] = metadata.touchesEdge
    }

    return PreparedImage(
      width = width,
      height = height,
      regionIds = regionIds,
      regionPaths = regionPaths,
      rowRuns = lookupRows,
      regionPixelCounts = regionPixelCounts,
      regionTouchesEdge = regionTouchesEdge,
    )
  }

  private fun computeInkThreshold(tolerance: Int): Int {
    return (106 - tolerance / 3).coerceIn(90, 118)
  }

  private fun buildBarrierMask(
    pixels: IntArray,
    width: Int,
    height: Int,
    inkThreshold: Int,
  ): BooleanArray {
    val rawBarrier = BooleanArray(width * height)
    for (index in pixels.indices) {
      rawBarrier[index] = isBarrierPixel(pixels[index], inkThreshold)
    }

    val closingRadius = if (max(width, height) >= 1600) 2 else 1
    return closeMask(rawBarrier, width, height, closingRadius)
  }

  private fun closeMask(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
    if (radius <= 0) {
      return mask
    }
    val dilated = dilateMask(mask, width, height, radius)
    return erodeMask(dilated, width, height, radius)
  }

  private fun dilateMask(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
    val output = BooleanArray(mask.size)

    for (y in 0 until height) {
      val minY = max(0, y - radius)
      val maxY = min(height - 1, y + radius)
      for (x in 0 until width) {
        val minX = max(0, x - radius)
        val maxX = min(width - 1, x + radius)
        var hit = false

        outer@ for (ny in minY..maxY) {
          val rowOffset = ny * width
          for (nx in minX..maxX) {
            if (mask[rowOffset + nx]) {
              hit = true
              break@outer
            }
          }
        }

        output[y * width + x] = hit
      }
    }

    return output
  }

  private fun erodeMask(mask: BooleanArray, width: Int, height: Int, radius: Int): BooleanArray {
    val output = BooleanArray(mask.size)

    for (y in 0 until height) {
      for (x in 0 until width) {
        var keep = true

        for (ny in (y - radius)..(y + radius)) {
          if (ny !in 0 until height) {
            keep = false
            break
          }

          val rowOffset = ny * width
          for (nx in (x - radius)..(x + radius)) {
            if (nx !in 0 until width || !mask[rowOffset + nx]) {
              keep = false
              break
            }
          }

          if (!keep) {
            break
          }
        }

        output[y * width + x] = keep
      }
    }

    return output
  }

  private fun isBarrierPixel(pixel: Int, inkThreshold: Int): Boolean {
    val alpha = Color.alpha(pixel)
    if (alpha <= 18) {
      return false
    }

    val luminance = luminance(pixel)
    val weightedInk = ((255 - luminance) * alpha + 127) / 255
    return weightedInk >= inkThreshold || (alpha >= 210 && luminance <= 124)
  }

  private fun luminance(pixel: Int): Int {
    return (Color.red(pixel) * 299 + Color.green(pixel) * 587 + Color.blue(pixel) * 114) / 1000
  }

  private fun loadBitmap(uriString: String): Bitmap? {
    return try {
      val uri = Uri.parse(uriString)
      val context = requireNotNull(appContext.reactContext)
      val options = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inMutable = false
        inScaled = false
      }

      context.contentResolver.openInputStream(uri)?.use { stream ->
        val decodedBitmap = BitmapFactory.decodeStream(stream, null, options) ?: return@use null
        if (decodedBitmap.config == Bitmap.Config.ARGB_8888) {
          decodedBitmap
        } else {
          decodedBitmap.copy(Bitmap.Config.ARGB_8888, false).also {
            decodedBitmap.recycle()
          }
        }
      }
    } catch (e: Exception) {
      e.printStackTrace()
      null
    }
  }

  private fun buildConnectedMask(
    pixels: IntArray,
    width: Int,
    height: Int,
    startX: Int,
    startY: Int,
    tolerance: Int,
  ): BooleanArray {
    val startIdx = startY * width + startX
    val startColor = pixels[startIdx]

    val visited = BooleanArray(width * height)
    val mask = BooleanArray(width * height)
    val queue: Queue<Int> = LinkedList()

    queue.add(startIdx)
    visited[startIdx] = true
    mask[startIdx] = true

    val dx = intArrayOf(-1, 1, 0, 0)
    val dy = intArrayOf(0, 0, -1, 1)

    while (queue.isNotEmpty()) {
      val idx = queue.poll() ?: continue
      val cx = idx % width
      val cy = idx / width

      for (i in 0..3) {
        val nx = cx + dx[i]
        val ny = cy + dy[i]
        if (nx !in 0 until width || ny !in 0 until height) {
          continue
        }

        val nextIdx = ny * width + nx
        if (visited[nextIdx]) {
          continue
        }
        visited[nextIdx] = true

        if (!colorMatch(startColor, pixels[nextIdx], tolerance)) {
          continue
        }

        mask[nextIdx] = true
        queue.add(nextIdx)
      }
    }

    return mask
  }

  private fun colorMatch(c1: Int, c2: Int, tolerance: Int): Boolean {
    val r1 = Color.red(c1)
    val g1 = Color.green(c1)
    val b1 = Color.blue(c1)
    val a1 = Color.alpha(c1)

    val r2 = Color.red(c2)
    val g2 = Color.green(c2)
    val b2 = Color.blue(c2)
    val a2 = Color.alpha(c2)

    return abs(r1 - r2) <= tolerance &&
      abs(g1 - g2) <= tolerance &&
      abs(b1 - b2) <= tolerance &&
      abs(a1 - a2) <= tolerance
  }

  private fun generateSvgPathFromMask(mask: BooleanArray, width: Int, height: Int): String {
    val builder = StringBuilder()
    val activeRuns = linkedMapOf<HorizontalRun, Int>()

    for (y in 0 until height) {
      val rowRuns = mutableListOf<HorizontalRun>()
      var x = 0
      while (x < width) {
        while (x < width && !mask[y * width + x]) {
          x++
        }
        if (x >= width) {
          break
        }

        val startX = x
        while (x < width && mask[y * width + x]) {
          x++
        }
        rowRuns.add(HorizontalRun(startX, x))
      }

      val rowRunSet = rowRuns.toSet()
      val iterator = activeRuns.entries.iterator()
      while (iterator.hasNext()) {
        val entry = iterator.next()
        if (entry.key !in rowRunSet) {
          appendRectanglePath(builder, entry.key.startX, entry.value, entry.key.endXExclusive, y)
          iterator.remove()
        }
      }

      for (run in rowRuns) {
        if (!activeRuns.containsKey(run)) {
          activeRuns[run] = y
        }
      }
    }

    for ((run, startY) in activeRuns) {
      appendRectanglePath(builder, run.startX, startY, run.endXExclusive, height)
    }

    return builder.toString().trim()
  }

  private fun appendRectanglePath(
    builder: StringBuilder,
    startX: Int,
    startY: Int,
    endXExclusive: Int,
    endYExclusive: Int,
  ) {
    if (endXExclusive <= startX || endYExclusive <= startY) {
      return
    }

    builder.append("M ")
      .append(startX)
      .append(' ')
      .append(startY)
      .append(" H ")
      .append(endXExclusive)
      .append(" V ")
      .append(endYExclusive)
      .append(" H ")
      .append(startX)
      .append(" Z ")
  }
}
