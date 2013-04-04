/*
 * Copyright 2009 ZXing authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.zxing.pdf417.detector;

import java.util.Arrays;
import java.util.Map;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.ChecksumException;
import com.google.zxing.DecodeHintType;
import com.google.zxing.FormatException;
import com.google.zxing.NotFoundException;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.AdjustableBitMatrix;
import com.google.zxing.common.RotationTransformer;
import com.google.zxing.common.TransformableBitMatrix;
import com.google.zxing.pdf417.PDF417Common;

/**
 * <p>Encapsulates logic that can detect a PDF417 Code in an image, even if the
 * PDF417 Code is rotated or skewed, or partially obscured.</p>
 *
 * @author SITA Lab (kevin.osullivan@sita.aero)
 * @author dswitkin@google.com (Daniel Switkin)
 * @author Guenther Grau
 */
public final class Detector {

  private static final int[] INDEXES_START_PATTERN = new int[] { 0, 4, 1, 5 };
  private static final int[] INDEXES_STOP_PATTERN = new int[] { 6, 2, 7, 3 };
  private static final int INTEGER_MATH_SHIFT = 8;
  private static final int PATTERN_MATCH_RESULT_SCALE_FACTOR = 1 << INTEGER_MATH_SHIFT;
  private static final int MAX_AVG_VARIANCE = (int) (PATTERN_MATCH_RESULT_SCALE_FACTOR * 0.42f);
  private static final int MAX_INDIVIDUAL_VARIANCE = (int) (PATTERN_MATCH_RESULT_SCALE_FACTOR * 0.8f);

  // B S B S B S B S Bar/Space pattern
  // 11111111 0 1 0 1 0 1 000
  private static final int[] START_PATTERN = { 8, 1, 1, 1, 1, 1, 1, 3 };
  // 1111111 0 1 000 1 0 1 00 1
  private static final int[] STOP_PATTERN = { 7, 1, 1, 3, 1, 1, 1, 2, 1 };
  private static final int MODULE_COUNT_STOP_PATTERN = PDF417Common.getBitCountSum(STOP_PATTERN);
  private static final int MAX_PIXEL_DRIFT = 3;
  private static final int SKIPPED_ROW_COUNT_MAX = 50;

  private final BinaryBitmap image;

  public Detector(BinaryBitmap image) {
    this.image = image;
  }

  /**
   * <p>Detects a PDF417 Code in an image, simply.</p>
   *
   * @return {@link PDF417DetectorResult} encapsulating results of detecting a PDF417 Code
   * @throws NotFoundException if no QR Code can be found
   * @throws ChecksumException 
   * @throws FormatException 
   */
  public PDF417DetectorResult detect() throws NotFoundException, FormatException, ChecksumException {
    return detect(null);
  }

  /**
   * <p>Detects a PDF417 Code in an image. Only checks 0 and 180 degree rotations.</p>
   *
   * @param hints optional hints to detector
   * @return {@link PDF417DetectorResult} encapsulating results of detecting a PDF417 Code
   * @throws NotFoundException if no PDF417 Code can be found
   * @throws ChecksumException 
   * @throws FormatException 
   */
  public PDF417DetectorResult detect(Map<DecodeHintType, ?> hints) throws NotFoundException, FormatException, ChecksumException {
    boolean tryHarder = hints != null && hints.containsKey(DecodeHintType.TRY_HARDER);
    // Fetch the 1 bit matrix once up front.
    // TODO detection improvement, tryHarder could try several different luminance thresholds or even different binarizers
    RotationTransformer rotationTransformer = new RotationTransformer();
    TransformableBitMatrix matrix = new TransformableBitMatrix((AdjustableBitMatrix) image.getBlackMatrix(), rotationTransformer);
    // Try to find the vertices assuming the image is upright.
    ResultPoint[] vertices = findVertices(matrix, tryHarder);
    if (vertices[0] == null) {
      // Maybe the image is rotated 180 degrees?
      rotationTransformer.setRotate(true);
      vertices = findVertices(matrix, tryHarder);
    }

    if (vertices[0] == null) {
      throw NotFoundException.getNotFoundInstance();
    }

    float codewordWidth = computeCodewordWidthFromStartStopPattern(vertices);
    if (codewordWidth < PDF417Common.MODULES_IN_CODEWORD) {
      throw NotFoundException.getNotFoundInstance();
    }

    return new PDF417DetectorResult(matrix, vertices, codewordWidth);
  }

  /**
   * Locate the vertices and the codewords area of a black blob using the Start
   * and Stop patterns as locators.
   *
   * @param matrix the scanned barcode image.
   * @return an array containing the vertices:
   *           vertices[0] x, y top left barcode
   *           vertices[1] x, y bottom left barcode
   *           vertices[2] x, y top right barcode
   *           vertices[3] x, y bottom right barcode
   *           vertices[4] x, y top left codeword area
   *           vertices[5] x, y bottom left codeword area
   *           vertices[6] x, y top right codeword area
   *           vertices[7] x, y bottom right codeword area
   */
  // TODO Add additional start position on image to support finding multiple barcodes in a single image
  // should probably search from left to right, then top to bottom
  private static ResultPoint[] findVertices(TransformableBitMatrix matrix, boolean tryHarder) {
    int height = matrix.getHeight();
    int width = matrix.getWidth();

    ResultPoint[] result = new ResultPoint[8];
    copyToResult(result, findRowsWithPattern(matrix, height, width, START_PATTERN), INDEXES_START_PATTERN);

    // TODO This should use the results from the start pattern and start the search in the same row after the 
    // end of the starting pattern to support detection of several barcodes on a single image
    copyToResult(result, findRowsWithPattern(matrix, height, width, STOP_PATTERN), INDEXES_STOP_PATTERN);
    return result;
  }

  private static void copyToResult(ResultPoint[] result, ResultPoint[] tmpResult, int[] indexesStartPattern) {
    for (int i = 0; i < indexesStartPattern.length; i++) {
      result[indexesStartPattern[i]] = tmpResult[i];
    }
  }

  private static ResultPoint[] findRowsWithPattern(TransformableBitMatrix matrix, int height, int width, int[] pattern) {
    ResultPoint[] result = new ResultPoint[4];
    // A PDF471 barcode should have at least 3 rows, with each row being >= 3 times the module width. Therefore it should be at least
    // 9 pixels tall.
    int rowStep = 5;
    boolean found = false;
    int[] counters = new int[pattern.length];
    // First row that contains pattern
    int startRow = 0;
    for (; startRow < height; startRow += rowStep) {
      int[] loc = findGuardPattern(matrix, 0, startRow, width, false, pattern, counters);
      if (loc != null) {
        while (startRow > 0) {
          int[] previousRowLoc = findGuardPattern(matrix, 0, --startRow, width, false, pattern, counters);
          if (previousRowLoc != null) {
            loc = previousRowLoc;
          } else {
            startRow++;
            break;
          }
        }
        result[0] = new ResultPoint(loc[0], startRow);
        result[1] = new ResultPoint(loc[1], startRow);
        found = true;
        break;
      }
    }
    // Last row of the current symbol that contains pattern
    if (found) {
      int skippedRowCount = 0;
      int stopRow = startRow + 1;
      int[] previousRowLoc = new int[] { (int) result[0].getX(), (int) result[1].getX() };
      for (; stopRow < height; stopRow++) {
        int[] loc = findGuardPattern(matrix, previousRowLoc[0], stopRow, width, false, pattern, counters);
        if (loc != null && Math.abs(previousRowLoc[0] - loc[0]) < 5 && Math.abs(previousRowLoc[1] - loc[1]) < 5) {
          previousRowLoc = loc;
          skippedRowCount = 0;
        } else {
          if (skippedRowCount > SKIPPED_ROW_COUNT_MAX) {
            break;
          } else {
            skippedRowCount++;
          }
        }
      }
      stopRow -= skippedRowCount;
      result[2] = new ResultPoint(previousRowLoc[0], stopRow);
      result[3] = new ResultPoint(previousRowLoc[1], stopRow);
    }
    return result;
  }

  private static float computeCodewordWidthFromPattern(ResultPoint[] vertices) throws NotFoundException {
    return (ResultPoint.distance(vertices[0], vertices[1]) + ResultPoint.distance(vertices[2], vertices[3])) / 2.0f;
  }

  /**
   * <p>Estimates module size (pixels in a module) based on the Start and End
   * finder patterns.</p>
   *
   * @param vertices an array of vertices:
   *           vertices[0] x, y top left barcode
   *           vertices[1] x, y bottom left barcode
   *           vertices[2] x, y top right barcode
   *           vertices[3] x, y bottom right barcode
   *           vertices[4] x, y top left codeword area
   *           vertices[5] x, y bottom left codeword area
   *           vertices[6] x, y top right codeword area
   *           vertices[7] x, y bottom right codeword area
   * @return the module size.
   * @throws NotFoundException 
   */
  private static float computeCodewordWidthFromStartStopPattern(ResultPoint[] vertices) throws NotFoundException {
    ResultPoint[] patternCoordninates = getPatternCoordinates(vertices, INDEXES_START_PATTERN);
    if (patternCoordninates == null) {
      throw NotFoundException.getNotFoundInstance();
    }
    float startPatternWidth = computeCodewordWidthFromPattern(patternCoordninates);
    patternCoordninates = getPatternCoordinates(vertices, INDEXES_STOP_PATTERN);
    if (patternCoordninates == null) {
      return startPatternWidth;
    }
    return (startPatternWidth + computeCodewordWidthFromPattern(patternCoordninates) *
        PDF417Common.MODULES_IN_CODEWORD /
        MODULE_COUNT_STOP_PATTERN) / 2f;
  }

  private static ResultPoint[] getPatternCoordinates(ResultPoint[] vertices, int[] indexes) {
    ResultPoint[] result = new ResultPoint[indexes.length];
    for (int i = 0; i < indexes.length; i++) {
      if (vertices[indexes[i]] == null) {
        return null;
      }
      result[i] = vertices[indexes[i]];
    }
    return result;
  }

  /**
   * @param matrix row of black/white values to search
   * @param column x position to start search
   * @param row y position to start search
   * @param width the number of pixels to search on this row
   * @param pattern pattern of counts of number of black and white pixels that are
   *                 being searched for as a pattern
   * @param counters array of counters, as long as pattern, to re-use 
   * @return start/end horizontal offset of guard pattern, as an array of two ints.
   */
  private static int[] findGuardPattern(TransformableBitMatrix matrix, int column, int row, int width, boolean whiteFirst, int[] pattern,
      int[] counters) {
    Arrays.fill(counters, 0, counters.length, 0);
    int patternLength = pattern.length;
    boolean isWhite = whiteFirst;
    int counterPosition = 0;
    int patternStart = column;
    int pixelDrift = 0;

    // if there are black pixels left of the current pixel shift to the left, but only for MAX_PIXEL_DRIFT pixels 
    while (matrix.get(patternStart, row) && patternStart > 0 && pixelDrift++ < MAX_PIXEL_DRIFT) {
      patternStart--;
    }
    int x = patternStart;
    //    // if the current pixel is white shift to the right, but only for MAX_PIXEL_DRIFT pixels 
    //    pixelDrift = 0;
    //    while (!matrix.get(x, row) && x < width && pixelDrift++ < MAX_PIXEL_DRIFT) {
    //      x++;
    //    }
    for (; x < width; x++) {
      boolean pixel = matrix.get(x, row);
      if (pixel ^ isWhite) {
        counters[counterPosition]++;
      } else {
        if (counterPosition == patternLength - 1) {
          if (patternMatchVariance(counters, pattern, MAX_INDIVIDUAL_VARIANCE) < MAX_AVG_VARIANCE) {
            return new int[] { patternStart, x };
          }
          patternStart += counters[0] + counters[1];
          System.arraycopy(counters, 2, counters, 0, patternLength - 2);
          counters[patternLength - 2] = 0;
          counters[patternLength - 1] = 0;
          counterPosition--;
        } else {
          counterPosition++;
        }
        counters[counterPosition] = 1;
        isWhite = !isWhite;
      }
    }
    if (counterPosition == patternLength - 1) {
      if (patternMatchVariance(counters, pattern, MAX_INDIVIDUAL_VARIANCE) < MAX_AVG_VARIANCE) {
        return new int[] { patternStart, x - 1 };
      }
    }
    return null;
  }

  /**
   * Determines how closely a set of observed counts of runs of black/white
   * values matches a given target pattern. This is reported as the ratio of
   * the total variance from the expected pattern proportions across all
   * pattern elements, to the length of the pattern.
   *
   * @param counters observed counters
   * @param pattern expected pattern
   * @param maxIndividualVariance The most any counter can differ before we give up
   * @return ratio of total variance between counters and pattern compared to
   *         total pattern size, where the ratio has been multiplied by 256.
   *         So, 0 means no variance (perfect match); 256 means the total
   *         variance between counters and patterns equals the pattern length,
   *         higher values mean even more variance
   */
  private static int patternMatchVariance(int[] counters, int[] pattern, int maxIndividualVariance) {
    int numCounters = counters.length;
    int total = 0;
    int patternLength = 0;
    for (int i = 0; i < numCounters; i++) {
      total += counters[i];
      patternLength += pattern[i];
    }
    if (total < patternLength) {
      // If we don't even have one pixel per unit of bar width, assume this
      // is too small to reliably match, so fail:
      return Integer.MAX_VALUE;
    }
    // We're going to fake floating-point math in integers. We just need to use more bits.
    // Scale up patternLength so that intermediate values below like scaledCounter will have
    // more "significant digits".
    int unitBarWidth = (total << INTEGER_MATH_SHIFT) / patternLength;
    maxIndividualVariance = (maxIndividualVariance * unitBarWidth) >> INTEGER_MATH_SHIFT;

    int totalVariance = 0;
    for (int x = 0; x < numCounters; x++) {
      int counter = counters[x] << INTEGER_MATH_SHIFT;
      int scaledPattern = pattern[x] * unitBarWidth;
      int variance = counter > scaledPattern ? counter - scaledPattern : scaledPattern - counter;
      if (variance > maxIndividualVariance) {
        return Integer.MAX_VALUE;
      }
      totalVariance += variance;
    }
    return totalVariance / total;
  }
}
