/*
 * Copyright 2010 ZXing authors
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

package com.google.zxing.common.detector;

import com.google.zxing.NotFoundException;
import com.google.zxing.ResultPoint;
import com.google.zxing.common.BitMatrix;

/**
 * <p>
 * Detects a candidate barcode-like rectangular region within an image. It
 * starts around the center of the image, increases the size of the candidate
 * region until it finds a white rectangular region. By keeping track of the
 * last black points it encountered, it determines the corners of the barcode.
 * </p>
 *
 * @author David Olivier
 */
public final class WhiteRectangleDetector {

  private static final int INIT_SIZE = 10;
  private static final int CORR = 1;
  private static final int TOLERANCE = 2;

  private final BitMatrix image;
  private final int height;
  private final int width;
  private final int leftInit;
  private final int rightInit;
  private final int downInit;
  private final int upInit;
  private final boolean tryHarder;

  public WhiteRectangleDetector(BitMatrix image) throws NotFoundException {
    this(image, INIT_SIZE, image.getWidth() / 2, image.getHeight() / 2, false);
  }

  /**
   * @param image barcode image to find a rectangle in
   * @param tryHarder specifies if we are in tryHarder mode
   * @throws NotFoundException if image is too small to accommodate {@code initSize}
   */
  public WhiteRectangleDetector(BitMatrix image, boolean tryHarder) throws NotFoundException {
    this(image, INIT_SIZE, image.getWidth() / 2, image.getHeight() / 2, tryHarder);
  }

  /**
   * @param image barcode image to find a rectangle in
   * @param initSize initial size of search area around center
   * @param x x position of search center
   * @param y y position of search center
   * @param tryHarder specifies if we are in tryHarder mode
   * @throws NotFoundException if image is too small to accommodate {@code initSize}
   */
  public WhiteRectangleDetector(BitMatrix image, int initSize, int x, int y, boolean tryHarder) throws NotFoundException {
    this.image = image;
    height = image.getHeight();
    width = image.getWidth();
    int halfsize = initSize / 2;
    leftInit = x - halfsize;
    rightInit = x + halfsize;
    upInit = y - halfsize;
    downInit = y + halfsize;
    this.tryHarder = tryHarder;
    if (upInit < 0 || leftInit < 0 || downInit >= height || rightInit >= width) {
      throw NotFoundException.getNotFoundInstance();
    }
  }

  /**
   * @param image barcode image to find a rectangle in
   * @param initSize initial size of search area around center
   * @param x x position of search center
   * @param y y position of search center
   * @throws NotFoundException if image is too small to accommodate {@code initSize}
   */
  public WhiteRectangleDetector(BitMatrix image, int initSize, int x, int y) throws NotFoundException {
    this.image = image;
    height = image.getHeight();
    width = image.getWidth();
    int halfsize = initSize / 2;
    leftInit = x - halfsize;
    rightInit = x + halfsize;
    upInit = y - halfsize;
    downInit = y + halfsize;
    this.tryHarder = false;
    if (upInit < 0 || leftInit < 0 || downInit >= height || rightInit >= width) {
      throw NotFoundException.getNotFoundInstance();
    }
  }

  /**
   * <p>
   * Detects a candidate barcode-like rectangular region within an image. It
   * starts around the center of the image, increases the size of the candidate
   * region until it finds a white rectangular region.
   * </p>
   *
   * @return {@link ResultPoint}[] describing the corners of the rectangular
   *         region. The first and last points are opposed on the diagonal, as
   *         are the second and third. The first point will be the topmost
   *         point and the last, the bottommost. The second point will be
   *         leftmost and the third, the rightmost
   * @throws NotFoundException if no Data Matrix Code can be found
   */
  public ResultPoint[] detect() throws NotFoundException {

    int left = leftInit;
    int right = rightInit;
    int up = upInit;
    int down = downInit;
    boolean sizeExceeded = false;
    boolean aBlackPointFoundOnBorder = true;
    boolean atLeastOneBlackPointFoundOnBorder = false;
    
    boolean atLeastOneBlackPointFoundOnRight = false;
    boolean atLeastOneBlackPointFoundOnBottom = false;
    boolean atLeastOneBlackPointFoundOnLeft = false;
    boolean atLeastOneBlackPointFoundOnTop = false;

    while (aBlackPointFoundOnBorder) {

      aBlackPointFoundOnBorder = false;

      // .....
      // .   |
      // .....
      boolean rightBorderNotWhite = true;
      while ((rightBorderNotWhite || !atLeastOneBlackPointFoundOnRight) && right < width) {
        rightBorderNotWhite = containsBlackPoint(up, down, right, false);
        if (rightBorderNotWhite) {
          right++;
          aBlackPointFoundOnBorder = true;
          atLeastOneBlackPointFoundOnRight = true;
        } else if (!atLeastOneBlackPointFoundOnRight) {
          right++;
        }
      }

      if (right >= width) {
        sizeExceeded = true;
        break;
      }

      // .....
      // .   .
      // .___.
      boolean bottomBorderNotWhite = true;
      while ((bottomBorderNotWhite || !atLeastOneBlackPointFoundOnBottom) && down < height) {
        bottomBorderNotWhite = containsBlackPoint(left, right, down, true);
        if (bottomBorderNotWhite) {
          down++;
          aBlackPointFoundOnBorder = true;
          atLeastOneBlackPointFoundOnBottom = true;
        } else if (!atLeastOneBlackPointFoundOnBottom) {
          down++;
        }
      }

      if (down >= height) {
        sizeExceeded = true;
        break;
      }

      // .....
      // |   .
      // .....
      boolean leftBorderNotWhite = true;
      while ((leftBorderNotWhite || !atLeastOneBlackPointFoundOnLeft) && left >= 0) {
        leftBorderNotWhite = containsBlackPoint(up, down, left, false);
        if (leftBorderNotWhite) {
          left--;
          aBlackPointFoundOnBorder = true;
          atLeastOneBlackPointFoundOnLeft = true;
        } else if (!atLeastOneBlackPointFoundOnLeft) {
          left--;
        }
      }

      if (left < 0) {
        sizeExceeded = true;
        break;
      }

      // .___.
      // .   .
      // .....
      boolean topBorderNotWhite = true;
      while ((topBorderNotWhite  || !atLeastOneBlackPointFoundOnTop) && up >= 0) {
        topBorderNotWhite = containsBlackPoint(left, right, up, true);
        if (topBorderNotWhite) {
          up--;
          aBlackPointFoundOnBorder = true;
          atLeastOneBlackPointFoundOnTop = true;
        } else if (!atLeastOneBlackPointFoundOnTop) {
          up--;
        }
      }

      if (up < 0) {
        sizeExceeded = true;
        break;
      }

      if (aBlackPointFoundOnBorder) {
        atLeastOneBlackPointFoundOnBorder = true;
      }
    }

    if (!sizeExceeded && atLeastOneBlackPointFoundOnBorder) {

      //go up right
      ResultPoint z = findEdgePoint(new ResultPoint(left, down), new ResultPoint(right,up));
      //go down right
      ResultPoint t = findEdgePoint(new ResultPoint(left, up), new ResultPoint(right,down));
      //go down left
      ResultPoint x = findEdgePoint(new ResultPoint(right, up), new ResultPoint(left,down));
      //go up left
      ResultPoint y = findEdgePoint(new ResultPoint(right, down), new ResultPoint(left,up));

      //if an edge is not found
      if(z == null || t == null || x == null || y == null) {
        throw NotFoundException.getNotFoundInstance();
      }

      return centerEdges(y, z, x, t);

    } else {
      throw NotFoundException.getNotFoundInstance();
    }
  }

  /**
   * Return the edge point of DataMatrix that is closer to the corner point of rectangle passed as the first
   * argument.
   *
   * @param edge {@link ResultPoint} that is the first corner point of the examined rectangle
   * @param oppEdge {@link ResultPoint} that is the diagonal opposite corner point of the examined rectangle
   * @return {@link ResultPoint} that is the edge of the DataMatrix closer to edge parameter
   */
  private ResultPoint findEdgePoint(ResultPoint edge, ResultPoint oppEdge) {

    int maxSize = (int)Math.abs(edge.getX() - oppEdge.getX());
    int verticalMaxSize = (int)Math.abs(edge.getY() - oppEdge.getY());

    ResultPoint a = null;
    ResultPoint a1 = null;
    ResultPoint a2 = null;
    boolean bordersChecked = false;
    //go up right
    for (int i = 1, j = 2; (j<maxSize/2) && (j<verticalMaxSize/2); i++, j+=2) {
      //in case of try harder mode a black point may exist in borders because of tolerance
      //in that case the first point to check must be the black point in border line if any
      if (tryHarder && !bordersChecked){
        a1 = getBlackPointOnSegment(edge.getX(), edge.getY(), edge.getX() < oppEdge.getX() ? edge.getX() + (maxSize / 2) : edge.getX() - (maxSize / 2), edge.getY());
        a2 = getBlackPointOnSegment(edge.getX(), edge.getY(), edge.getX(), edge.getY() > oppEdge.getY() ?  edge.getY() - (verticalMaxSize / 2) : edge.getY() + (verticalMaxSize / 2));
        a1 = (a1 != null) && (isCornerPoint(a1, edge, maxSize, verticalMaxSize)) ? a1: null;
        a2 = (a2 != null) && (isCornerPoint(a2, edge, verticalMaxSize, maxSize)) ? a2: null;
        bordersChecked = true;
      }
      if(a == null) {
        a = getBlackPointOnSegment(edge.getX(), edge.getY() > oppEdge.getY() ? edge.getY() - i :  edge.getY() + i, edge.getX() < oppEdge.getX() ? edge.getX() + i : edge.getX() - i, edge.getY());
      }
      if (a1 == null) {
        a1 = getBlackPointOnSegment(edge.getX() < oppEdge.getX() ? edge.getX() + j : edge.getX() - j, edge.getY(), edge.getX(), edge.getY() > oppEdge.getY() ? edge.getY() - i : edge.getY() + i);
      }
      if (a2 == null) {
        a2 = getBlackPointOnSegment(edge.getX(), edge.getY() > oppEdge.getY() ? edge.getY() - j : edge.getY() + j, edge.getX() < oppEdge.getX() ? edge.getX() + i : edge.getX() - i, edge.getY());
      }
      if ((a != null) && (!tryHarder)) {
        break;
      } else if ((a1 != null) && (a2 != null) && tryHarder) {
        //if we are not in black module get the middle
        if(!inBlackModule(a1, a2)){
          a = new ResultPoint((a1.getX() + a2.getX()) / 2, (a1.getY() + a2.getY()) / 2);
          //decentralize point
          a = decentralizePoint(a, edge, oppEdge);
        }else{//if we are in black module
          //select the point that is in border line or construct a point that is closer to borders
          a = inBorderLine(a1, edge, oppEdge) ? a1 : (inBorderLine(a2, edge, oppEdge) ? a2 :
                  new ResultPoint(edge.getX() < oppEdge.getX() ? Math.min(a1.getX(), a2.getX()) : Math.max(a1.getX(), a2.getX()), edge.getY() > oppEdge.getY() ? Math.max(a1.getY(),a2.getY()) : Math.min(a1.getY(),a2.getY())));
        }
        break;
      }
    }
    return a;
  }

  /**
   * Returns true if a point is in one of the four border lines
   */
  private boolean inBorderLine(ResultPoint a, ResultPoint edge, ResultPoint oppEdge) {
    return (a.getX() == edge.getX()) || (a.getX() == oppEdge.getX()) || (a.getY() == edge.getY()) || (a.getY() == oppEdge.getY());
  }

  /**
   * Decentralize black point according to it's position in image
   */
  private ResultPoint decentralizePoint(ResultPoint a, ResultPoint edge, ResultPoint oppEdge) {

    //while point is black
    while(image.get((int)a.getX(), (int)a.getY())){
      a = new ResultPoint(edge.getX() > oppEdge.getX() ? a.getX()+CORR: a.getX()-CORR,
              edge.getY() > oppEdge.getY()? a.getY()+CORR: a.getY()-CORR);
    }
    //actually two more points away because finally this point will be centered
    return new ResultPoint(edge.getX() > oppEdge.getX() ? a.getX()+CORR+1: a.getX()-CORR-1,
            edge.getY() > oppEdge.getY()? a.getY()+CORR+1: a.getY()-CORR-1);
  }

  /**
   * Determines if a {@link ResultPoint} is an edge using a heuristic algorithm.
   * The two {@link ResultPoint} parameters must have the same Y coordinates or same X coordinates otherwise an
   * {@link IllegalArgumentException} is thrown.
   *
   * @param a the {@link ResultPoint} to examine
   * @param b the rectangle corner {@link ResultPoint} related with the examined point
   * @param pointsSideMaxSize the maxsize of the side of points
   * @param pointsVerticalSideMaxSize the maxsize of the side that is vertical to the points
   * @return true if the examined {@link ResultPoint} is an edge point of data matrix
   */
  private boolean isCornerPoint(ResultPoint a, ResultPoint b, int pointsSideMaxSize, int pointsVerticalSideMaxSize) {

    //if they are on Y axis
    if (a.getX() == b.getX()) {
      //for the 5% of the maxsize
      int i;
      for (i = 1; i < pointsVerticalSideMaxSize * 5 / 100; i++) {
        //move horizontally
        int dist1 = MathUtils.round(MathUtils.distance(a.getX(), a.getY(), b.getX() + i < image.getWidth() ? b.getX() + i : image.getWidth() - 1, b.getY()));
        int dist2 = MathUtils.round(MathUtils.distance(a.getX(), a.getY(), b.getX() - i > 0 ? b.getX() - i : 0, b.getY()));

        int blackPoints1 = countBlackPointsOnSegment(a.getX(), a.getY(), b.getX() + i < image.getWidth() ? b.getX() + i : image.getWidth() - 1, b.getY());
        int blackPoints2 = countBlackPointsOnSegment(a.getX(), a.getY(), b.getX() - i > 0 ? b.getX() - i : 0, b.getY());

        //if black points are more than 10% of the distance
        if((blackPoints1/(float)dist1 > 0.1f) || (blackPoints2/(float)dist2 > 0.1f)){
          return false;
        }
      }

      //for 100% of the vertical maxsize starting at corner point and moving towards the opposite corner on Y axis
      for (int j = 1; j < pointsSideMaxSize; j++) {
        int dist1 = MathUtils.round(MathUtils.distance(a.getX(), a.getY(),
                Math.abs(image.getWidth() - a.getX()) < a.getX() ?
                        (((b.getX()+i)<image.getWidth()) ? (b.getX() + i) : image.getWidth() - 1) :
                        (((b.getX()-i)>0) ? (b.getX()-i) : 0),
                        Math.abs(image.getHeight() - b.getY()) < b.getY() ? b.getY() - j : b.getY() + j));
        int blackPoints1 = countBlackPointsOnSegment(a.getX(), a.getY(),
                Math.abs(image.getWidth() - a.getX()) < a.getX() ?
                        (((b.getX()+i)<image.getWidth()) ? (b.getX() + i) : image.getWidth() - 1) :
                        (((b.getX()-i)>0) ? (b.getX()-i) : 0),
                        Math.abs(image.getHeight() - b.getY()) < b.getY() ? b.getY() - j : b.getY() + j);
        //if black points are more than 15% of the distance
        if(blackPoints1/(float)dist1 > 0.15f){
          return false;
        }
      }

    }else if (a.getY() == b.getY()){//they are on X axis
      //for the 5% of the maxsize
      int i;
      for (i = 1; i < pointsVerticalSideMaxSize * 5 / 100; i++) {
        //move vertically
        int dist1 = MathUtils.round(MathUtils.distance(a.getX(), a.getY(), b.getX(), b.getY() + i < image.getHeight() ? b.getY() + i : image.getHeight()-1));
        int dist2 = MathUtils.round(MathUtils.distance(a.getX(), a.getY(), b.getX(), b.getY() - i > 0 ? b.getY() - i : 0));

        int blackPoints1 = countBlackPointsOnSegment(a.getX(), a.getY(), b.getX(), b.getY() + i < image.getHeight() ? b.getY() + i : image.getHeight()-1);
        int blackPoints2 = countBlackPointsOnSegment(a.getX(), a.getY(), b.getX(), b.getY() - i > 0 ? b.getY() - i : 0);

        //if black points are more than 90% of the distance we are probably in black module
        if((blackPoints1/(float)dist1 > 0.1f) || (blackPoints2/(float)dist2 > 0.1f)){
          return false;
        }
      }

      //for 100% of the horizontal maxsize starting at corner point
      for (int j = 1; j < pointsSideMaxSize; j++) {
        int dist1 = MathUtils.round(MathUtils.distance(a.getX(), a.getY(),
                Math.abs(image.getWidth() - b.getX()) < b.getX() ? b.getX() - j : b.getX() + j,
                Math.abs(image.getHeight() - a.getY()) < a.getY() ?
                        (((b.getY()+i)<image.getHeight()) ? (b.getY()+i) : image.getHeight() - 1) :
                        (((b.getY()-i)>0) ? (b.getY()-i) : 0)));
        int blackPoints1 = countBlackPointsOnSegment(a.getX(), a.getY(),
                Math.abs(image.getWidth() - b.getX()) < b.getX() ? b.getX() - j : b.getX() + j,
                Math.abs(image.getHeight() - a.getY()) < a.getY() ?
                        (((b.getY()+i)<image.getHeight()) ? (b.getY()+i) : image.getHeight() - 1) :
                        (((b.getY()-i)>0) ? (b.getY()-i) : 0));
        //if black points are more than 15% of the distance
        if(blackPoints1/(float)dist1 > 0.15f){
          return false;
        }
      }
    }else{
      throw new IllegalArgumentException("Examined points must have same Xs or same Ys");
    }

    return true;
  }

  /**
   * Returns true if on a segment of (a1, a2) over 90% are black points.
   * If a1, a2 points are same return true if it is a black point otherwise false.
   *
   * @param a1 {@link ResultPoint} the start of the segment
   * @param a2 {@link ResultPoint} the end of the segment
   * @return true if segment is in black module otherwise false
   */
  private boolean inBlackModule(ResultPoint a1, ResultPoint a2) {

    int dist = MathUtils.round(MathUtils.distance(a1.getX(), a1.getY(), a2.getX(), a2.getY()));
    int blackPoints = countBlackPointsOnSegment(a1.getX(), a1.getY(), a2.getX(), a2.getY());
    return dist == 0 ? image.get((int)a1.getX(), (int)a1.getY()) : blackPoints/(float)dist > 0.9f;
  }

  private ResultPoint getBlackPointOnSegment(float aX, float aY, float bX, float bY) {
    int dist = MathUtils.round(MathUtils.distance(aX, aY, bX, bY));
    float xStep = (bX - aX) / dist;
    float yStep = (bY - aY) / dist;

    for (int i = 0; i < dist; i++) {
      int x = MathUtils.round(aX + i * xStep);
      int y = MathUtils.round(aY + i * yStep);
      if (image.get(x, y)) {
        return new ResultPoint(x, y);
      }
    }
    return null;
  }

  /**
   * recenters the points of a constant distance towards the center
   *
   * @param y bottom most point
   * @param z left most point
   * @param x right most point
   * @param t top most point
   * @return {@link ResultPoint}[] describing the corners of the rectangular
   *         region. The first and last points are opposed on the diagonal, as
   *         are the second and third. The first point will be the topmost
   *         point and the last, the bottommost. The second point will be
   *         leftmost and the third, the rightmost
   */
  private ResultPoint[] centerEdges(ResultPoint y, ResultPoint z,
                                    ResultPoint x, ResultPoint t) {

    //
    //       t            t
    //  z                      x
    //        x    OR    z
    //   y                    y
    //

    float yi = y.getX();
    float yj = y.getY();
    float zi = z.getX();
    float zj = z.getY();
    float xi = x.getX();
    float xj = x.getY();
    float ti = t.getX();
    float tj = t.getY();

    if (yi < width / 2.0f) {
      return new ResultPoint[]{
          new ResultPoint(ti - CORR, tj + CORR),
          new ResultPoint(zi + CORR, zj + CORR),
          new ResultPoint(xi - CORR, xj - CORR),
          new ResultPoint(yi + CORR, yj - CORR)};
    } else {
      return new ResultPoint[]{
          new ResultPoint(ti + CORR, tj + CORR),
          new ResultPoint(zi + CORR, zj - CORR),
          new ResultPoint(xi - CORR, xj + CORR),
          new ResultPoint(yi - CORR, yj - CORR)};
    }
  }

  /**
   * Determines whether a segment contains a black point
   *
   * @param a          min value of the scanned coordinate
   * @param b          max value of the scanned coordinate
   * @param fixed      value of fixed coordinate
   * @param horizontal set to true if scan must be horizontal, false if vertical
   * @return true if a black point has been found, else false.
   */
  private boolean containsBlackPoint(int a, int b, int fixed, boolean horizontal) {

    int tolerancePixels = Math.round(Math.abs(a - b) * TOLERANCE / 100f);
    int blackBitsCounter = 0;
    if (horizontal) {
      for (int x = a; x <= b; x++) {
        if (image.get(x, fixed)) {
          blackBitsCounter++;
          if (tryHarder && (blackBitsCounter > tolerancePixels)){
            return true;
          }else if(!tryHarder){
            return true;
          }
        }
      }
    } else {
      for (int y = a; y <= b; y++) {
        if (image.get(fixed, y)) {
          blackBitsCounter++;
          if (tryHarder && blackBitsCounter > tolerancePixels){
            return true;
          }else if(!tryHarder){
            return true;
          }
        }
      }
    }
    return false;
  }

  /**
   * Counts black points on a segment
   *
   * @param aX fist point's X coordinate
   * @param aY fist point's Y coordinate
   * @param bX second point's X coordinate
   * @param bY second point's Y coordinate
   * @return black points number
   */
  private int countBlackPointsOnSegment(float aX, float aY, float bX, float bY) {
    int counter = 0;
    int dist = MathUtils.round(MathUtils.distance(aX, aY, bX, bY));
    float xStep = (bX - aX) / dist;
    float yStep = (bY - aY) / dist;

    for (int i = 0; i <= dist; i++) {
      int x = MathUtils.round(aX + i * xStep);
      int y = MathUtils.round(aY + i * yStep);
      if (image.get(x, y)) {
        counter++;
      }
    }
    return counter;
  }
}
