/*
 * Copyright 2021 ZXing authors
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

package com.google.zxing.qrcode.encoder;

import com.google.zxing.qrcode.decoder.Mode;
import com.google.zxing.qrcode.decoder.Version;
import com.google.zxing.common.BitArray;
import com.google.zxing.common.CharacterSetECI;
import com.google.zxing.WriterException;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;

import java.nio.charset.UnsupportedCharsetException;

/**
 * Encoder that encodes minimally
 *
 * Version selection:
 * The version can be preset in the constructor. If it isn't specified then the algorithm will compute three solutions
 * for the three different version classes 1-9, 10-26 and 27-40.
 *
 * It is not clear to me if ever a solution using for example Medium (Versions 10-26) could be smaller than a Small
 * solution (Versions 1-9) (proof for or against would be nice to have).
 * With hypothetical values for the number of length bits, the number of bits per mode and the number of bits per
 * encoded character it can be shown that it can happen at all as follows:
 * We hypothetically assume that a mode is encoded using 1 bit (instead of 4) and a character is encoded in BYTE mode
 * using 2 bit (instead of 8). Using these values we now attempt to encode the four characters "1234".
 * If we furthermore assume that in Version 1-9 the length field has 1 bit length so that it can encode up to 2
 * characters and that in Version 10-26 it has 2 bits length so that we can encode up to 2 characters then it is more
 * efficient to encode with Version 10-26 than with Version 1-9 as shown below:
 *
 * Number of length bits small version (1-9): 1
 * Number of length bits large version (10-26): 2
 * Number of bits per mode item: 1
 * Number of bits per character item: 2
 * BYTE(1,2),BYTE(3,4): 1+1+2+2,1+1+2+2=12 bits
 * BYTE(1,2,3,4): 1+2+2+2+2+2          =11 bits
 *
 * If we however change the capacity of the large encoding from 2 bit to 4 bit so that it potentially can encode 16
 * items, then it is more efficient to encode using the small encoding
 * as shown below:
 *
 * Number of length bits small version (1-9): 1
 * Number of length bits large version (10-26): 4
 * Number of bits per mode item: 1
 * Number of bits per character item: 2
 * BYTE(1,2),BYTE(3,4): 1+1+2+2,1+1+2+2=12 bits
 * BYTE(1,2,3,4): 1+4+2+2+2+2          =13 bits
 *
 * But as mentioned, it is not clear to me if this can ever happen with the actual values.
 *
 * ECI switching:
 *
 * In multi language content the algorithm selects the most compact representation using ECI modes. For example the
 * most compact representation of the string "\u0625\u05D0" is ECI(UTF-8),BYTE(arabic_aleph,hebrew_aleph) while
 * the encoding the string "\u0625\u0625\u05D0" is most compactly represented with two ECIs as 
 * ECI(ISO-8859-6),BYTE(arabic_aleph,arabic_aleph),ECI(ISO-8859-8),BYTE(hebew_aleph).
 *
 * @author Alex Geller
 */
final class MinimalEncoder {

  private enum VersionSize {
    SMALL("version 1-9"),
    MEDIUM("version 10-26"),
    LARGE("version 27-40");

    private final String description;

    VersionSize(String description) {
      this.description = description;
    }

    public String toString() {
      return description;
    }
  }

  private final String stringToEncode;
  private final boolean isGS1;
  private final CharsetEncoder[] encoders;
  private final int priorityEncoderIndex;
  private final ErrorCorrectionLevel ecLevel;

  /**
   * Creates a MinimalEncoder
   *
   * @param stringToEncode The string to encode
   * @param priorityCharset The preferred {@link Charset}. When the value of the argument is null, the algorithm
   *   chooses charsets that leads to a minimal representation. Otherwise the algorithm will use the priority 
   *   charset to encode any character in the input that can be encoded by it if the charset is among the 
   *   supported charsets.
   * @param isGS1 {@code true} if a FNC1 is to be prepended; {@code false} otherwise
   * @param ecLevel The error correction level.
   * @see ResultList#getVersion
   */
  MinimalEncoder(String stringToEncode, Charset priorityCharset, boolean isGS1,
      ErrorCorrectionLevel ecLevel) throws WriterException {

    this.stringToEncode = stringToEncode;
    this.isGS1 = isGS1;
    this.ecLevel = ecLevel;

    CharsetEncoder[] isoEncoders = new CharsetEncoder[15]; //room for the 15 ISO-8859 charsets 1 through 16.
    isoEncoders[0] = StandardCharsets.ISO_8859_1.newEncoder();
    boolean needUnicodeEncoder = priorityCharset != null && priorityCharset.name().startsWith("UTF");

    for (int i = 0; i < stringToEncode.length(); i++) {
      int cnt = 0;
      int j;
      for (j = 0; j < 15; j++) {
        if (isoEncoders[j] != null) {
          cnt++;
          if (isoEncoders[j].canEncode(stringToEncode.charAt(i))) {
            break;
          }
        }
      }

      if (cnt == 14) { //we need all. Can stop looking further.
        break;
      }

      if (j >= 15) { //no encoder found
        for (j = 0; j < 15; j++) {
          if (j != 11 && isoEncoders[j] == null) { // ISO-8859-12 doesn't exist
            try {
              CharsetEncoder ce = Charset.forName("ISO-8859-" + (j + 1)).newEncoder();
              if (ce.canEncode(stringToEncode.charAt(i))) {
                isoEncoders[j] = ce;
                break;
              }
            } catch (UnsupportedCharsetException e) {
              // continue
            }
          }
        }
        if (j >= 15) {
          if (!StandardCharsets.UTF_16BE.newEncoder().canEncode(stringToEncode.charAt(i))) {
            throw new WriterException("Can not encode character \\u" +
                String.format("%04X", (int) stringToEncode.charAt(i)) + " at position " + i +
                " in input \"" + stringToEncode + "\"");
          }
          needUnicodeEncoder = true;
        }
      }
    }

    int numberOfEncoders = 0;
    for (int j = 0; j < 15; j++) {
      if (isoEncoders[j] != null) {
        if (CharacterSetECI.getCharacterSetECI(isoEncoders[j].charset()) != null) {
          numberOfEncoders++;
        } else {
          needUnicodeEncoder = true;
        }
      }
    }

    if (numberOfEncoders == 1 && !needUnicodeEncoder) {
      encoders = new CharsetEncoder[1];
      encoders[0] = isoEncoders[0];
    } else {
      encoders = new CharsetEncoder[numberOfEncoders + 2];
      int index = 0;
      for (int j = 0; j < 15; j++) {
        if (isoEncoders[j] != null && CharacterSetECI.getCharacterSetECI(isoEncoders[j].charset()) != null) {
          encoders[index++] = isoEncoders[j];
        }
      }

      encoders[index] = StandardCharsets.UTF_8.newEncoder();
      encoders[index + 1] = StandardCharsets.UTF_16BE.newEncoder();
    }

    int priorityEncoderIndexValue = -1;
    if (priorityCharset != null) {
      for (int i = 0; i < encoders.length; i++) {
        if (priorityCharset.name().equals(encoders[i].charset().name())) {
          priorityEncoderIndexValue = i;
          break;
        }
      }
    }
    priorityEncoderIndex = priorityEncoderIndexValue;
  }

  /**
   * Encodes the string minimally
   *
   * @param stringToEncode The string to encode
   * @param version The preferred {@link Version}. A minimal version is computed (see 
   *   {@link ResultList#getVersion method} when the value of the argument is null
   * @param priorityCharset The preferred {@link Charset}. When the value of the argument is null, the algorithm
   *   chooses charsets that leads to a minimal representation. Otherwise the algorithm will use the priority 
   *   charset to encode any character in the input that can be encoded by it if the charset is among the 
   *   supported charsets.
   * @param isGS1 {@code true} if a FNC1 is to be prepended; {@code false} otherwise
   * @param ecLevel The error correction level.
   * @return An instance of {@code ResultList} representing the minimal solution.
   * @see ResultList#getBits
   * @see ResultList#getVersion
   * @see ResultList#getSize
   */
  static ResultList encode(String stringToEncode, Version version, Charset priorityCharset, boolean isGS1,
      ErrorCorrectionLevel ecLevel) throws WriterException {
    return new MinimalEncoder(stringToEncode, priorityCharset, isGS1, ecLevel).encode(version);
  }

  ResultList encode(Version version) throws WriterException {
    if (version == null) { //compute minimal encoding trying the three version sizes.
      final Version[] versions = {getVersion(VersionSize.SMALL),
                                  getVersion(VersionSize.MEDIUM),
                                  getVersion(VersionSize.LARGE)};
      ResultList[] results = {encodeSpecificVersion(versions[0]),
                              encodeSpecificVersion(versions[1]),
                              encodeSpecificVersion(versions[2])};
      int smallestSize = Integer.MAX_VALUE;
      int smallestResult = -1;
      for (int i = 0; i < 3; i++) {
        int size = results[i].getSize();
        if (Encoder.willFit(size, versions[i], ecLevel) && size < smallestSize) {
          smallestSize = size;
          smallestResult = i;
        }
      }
      if (smallestResult < 0) {
        throw new WriterException("Data too big for any version");
      }
      return results[smallestResult];
    } else { //compute minimal encoding for a given version
      ResultList result = encodeSpecificVersion(version);
      if (!Encoder.willFit(result.getSize(), getVersion(getVersionSize(result.getVersion())), ecLevel)) {
        throw new WriterException("Data too big for version" + version);
      }
      return result;
    }
  }

  static VersionSize getVersionSize(Version version) {
    return version.getVersionNumber() <= 9 ? VersionSize.SMALL : version.getVersionNumber() <= 26 ?
      VersionSize.MEDIUM : VersionSize.LARGE;
  }

  static Version getVersion(VersionSize versionSize) {
    switch (versionSize) {
      case SMALL:
        return Version.getVersionForNumber(9);
      case MEDIUM:
        return Version.getVersionForNumber(26);
      case LARGE:
      default:
        return Version.getVersionForNumber(40);
    }
  }

  static boolean isNumeric(char c) {
    return c >= '0' && c <= '9';
  }

  static boolean isDoubleByteKanji(char c) {
    return Encoder.isOnlyDoubleByteKanji(String.valueOf(c));
  }

  static boolean isAlphanumeric(char c) {
    return Encoder.getAlphanumericCode(c) != -1;
  }

  /**
   * Returns the maximum number of encodeable characters in the given mode for the given version. Example: in
   * Version 1, 2^10 digits or 2^8 bytes can be encoded. In Version 3 it is 2^14 digits and 2^16 bytes
   */
  static int getMaximumNumberOfEncodeableCharacters(Version version, Mode mode) {
    int count = mode.getCharacterCountBits(version);
    return count == 0 ? 0 : 1 << count;
  }

  boolean canEncode(Mode mode, char c) {
    switch (mode) {
      case KANJI: return isDoubleByteKanji(c);
      case ALPHANUMERIC: return isAlphanumeric(c);
      case NUMERIC: return isNumeric(c);
      case BYTE: return true; //any character can be encoded as byte(s). Up to the caller to manage splitting into
                              //multiple bytes when String.getBytes(Charset) return more than one byte.
      default:
        return false;
    }
  }

  static int getCompactedOrdinal(Mode mode) {
    if (mode == null) {
      return 0;
    }
    switch (mode) {
      case KANJI:
        return 0;
      case ALPHANUMERIC:
        return 1;
      case NUMERIC:
        return 2;
      case BYTE:
        return 3;
      default:
        throw new IllegalStateException("Illegal mode " + mode);
    }
  }

  ResultList postProcess(Edge solution, Version version) {
    ResultList result = new ResultList(version,solution);
    Edge edge = solution;
    if (isGS1) {
      ResultList.ResultNode first = result.get(0);
      if (first != null) {
        if (first.mode != Mode.ECI) {
          boolean haveECI = false;
          for (ResultList.ResultNode resultNode : result) {
            if (resultNode.mode == Mode.ECI) {
              haveECI = true;
              break;
            }
          }
          if (haveECI) {
            //prepend a default character set ECI
            result.add(0,result.new ResultNode(Mode.ECI, 0, 0, 0));
          }
        }
      }

      first = result.get(0);
      if (first.mode != Mode.ECI) {
        //prepend a FNC1_FIRST_POSITION
        result.add(0,result.new ResultNode(Mode.FNC1_FIRST_POSITION, 0, 0, 0));
      } else {
        //insert a FNC1_FIRST_POSITION after the ECI
        result.add(1,result.new ResultNode(Mode.FNC1_FIRST_POSITION, 0, 0, 0));
      }
    }
    //Add TERMINATOR according to "8.4.8 Terminator"
    //TODO: The terminator can be omitted if there are less than 4 bit in the capacity of the symbol.
    result.add(result.new ResultNode(Mode.TERMINATOR, stringToEncode.length(), 0, 0));
    return result;
  }

  void addEdge(ArrayList<Edge>[][][] edges, int position, Edge edge) {
    int vertexIndex = position + edge.characterLength;
    if (edges[vertexIndex][edge.charsetEncoderIndex][getCompactedOrdinal(edge.mode)] == null) {
      edges[vertexIndex][edge.charsetEncoderIndex][getCompactedOrdinal(edge.mode)] = new ArrayList<>();
    }
    edges[vertexIndex][edge.charsetEncoderIndex][getCompactedOrdinal(edge.mode)].add(edge);
  }

  void addEdges(Version version, ArrayList<Edge>[][][] edges, int from, Edge previous) {
    int start = 0;
    int end = encoders.length;
    if (priorityEncoderIndex >= 0 && encoders[priorityEncoderIndex].canEncode(stringToEncode.charAt(from))) {
      start = priorityEncoderIndex;
      end = priorityEncoderIndex + 1;
    }

    for (int i = start; i < end; i++) {
      if (encoders[i].canEncode(stringToEncode.charAt(from))) {
        addEdge(edges, from, new Edge(Mode.BYTE, from, i, 1, previous, version));
      }
    }

    if (canEncode(Mode.KANJI, stringToEncode.charAt(from))) {
      addEdge(edges, from, new Edge(Mode.KANJI, from, 0, 1, previous, version));
    }

    int inputLength = stringToEncode.length();
    if (canEncode(Mode.ALPHANUMERIC, stringToEncode.charAt(from))) {
      addEdge(edges, from, new Edge(Mode.ALPHANUMERIC, from, 0, from + 1 >= inputLength ||
          !canEncode(Mode.ALPHANUMERIC, stringToEncode.charAt(from + 1)) ? 1 : 2, previous, version));
    }

    if (canEncode(Mode.NUMERIC, stringToEncode.charAt(from))) {
      addEdge(edges, from, new Edge(Mode.NUMERIC, from, 0, from + 1 >= inputLength ||
          !canEncode(Mode.NUMERIC, stringToEncode.charAt(from + 1)) ? 1 : from + 2 >= inputLength ||
          !canEncode(Mode.NUMERIC, stringToEncode.charAt(from + 2)) ? 2 : 3, previous, version));
    }
  }
  ResultList encodeSpecificVersion(Version version) throws WriterException {

    @SuppressWarnings("checkstyle:lineLength")
    /* A vertex represents a tuple of a position in the input, a mode and an a character encoding where position 0
     * denotes the position left of the first character, 1 the position left of the second character and so on.
     * Likewise the end vertices are located after the last character at position stringToEncode.length().
     *
     * An edge leading to such a vertex encodes one or more of the characters left of the position that the vertex
     * represents and encodes it in the same encoding and mode as the vertex on which the edge ends. In other words,
     * all edges leading to a particular vertex encode the same characters in the same mode with the same character
     * encoding. They differ only by their source vertices who are all located at i+1 minus the number of encoded
     * characters.
     *
     * The edges leading to a vertex are stored in such a way that there is a fast way to enumerate the edges ending
     * on a particular vertex.
     *
     * The algorithm processes the vertices in order of their position thereby performing the following:
     *
     * For every vertex at position i the algorithm enumerates the edges ending on the vertex and removes all but the
     * shortest from that list.
     * Then it processes the vertices for the position i+1. If i+1 == stringToEncode.length() then the algorithm ends
     * and chooses the the edge with the smallest size from any of the edges leading to vertices at this position.
     * Otherwise the algorithm computes all possible outgoing edges for the vertices at the position i+1
     *
     * Examples:
     * The process is illustrated by showing the graph (edges) after each iteration from left to right over the input:
     * An edge is drawn as follows "(" + fromVertex + ") -- " + encodingMode + "(" + encodedInput + ") (" +
     * accumulatedSize + ") --> (" + toVertex + ")"
     *
     * Example 1 encoding the string "ABCDE":
     * Note: This example assumes that alphanumeric encoding is only possible in multiples of two characters so that
     * the example is both short and showing the principle. In reality this restriction does not exist. 
     *
     * Initial situation
     * (initial) -- BYTE(A) (20) --> (1_BYTE)
     * (initial) -- ALPHANUMERIC(AB)                     (24) --> (2_ALPHANUMERIC)
     *
     * Situation after adding edges to vertices at position 1
     * (initial) -- BYTE(A) (20) --> (1_BYTE) -- BYTE(B) (28) --> (2_BYTE)
     *                               (1_BYTE) -- ALPHANUMERIC(BC)                             (44) --> (3_ALPHANUMERIC)
     * (initial) -- ALPHANUMERIC(AB)                     (24) --> (2_ALPHANUMERIC)
     *
     * Situation after adding edges to vertices at position 2
     * (initial) -- BYTE(A) (20) --> (1_BYTE)
     * (initial) -- ALPHANUMERIC(AB)                     (24) --> (2_ALPHANUMERIC)
     * (initial) -- BYTE(A) (20) --> (1_BYTE) -- BYTE(B) (28) --> (2_BYTE)
                                   * (1_BYTE) -- ALPHANUMERIC(BC)                             (44) --> (3_ALPHANUMERIC)
     * (initial) -- ALPHANUMERIC(AB)                     (24) --> (2_ALPHANUMERIC) -- BYTE(C) (44) --> (3_BYTE)
     *                                                            (2_ALPHANUMERIC) -- ALPHANUMERIC(CD)                             (35) --> (4_ALPHANUMERIC)
     *
     * Situation after adding edges to vertices at position 3
     * (initial) -- BYTE(A) (20) --> (1_BYTE) -- BYTE(B) (28) --> (2_BYTE) -- BYTE(C)         (36) --> (3_BYTE)
     *                               (1_BYTE) -- ALPHANUMERIC(BC)                             (44) --> (3_ALPHANUMERIC) -- BYTE(D) (64) --> (4_BYTE)
     *                                                                                                 (3_ALPHANUMERIC) -- ALPHANUMERIC(DE)                             (55) --> (5_ALPHANUMERIC)
     * (initial) -- ALPHANUMERIC(AB)                     (24) --> (2_ALPHANUMERIC) -- ALPHANUMERIC(CD)                             (35) --> (4_ALPHANUMERIC)
     *                                                            (2_ALPHANUMERIC) -- ALPHANUMERIC(CD)                             (35) --> (4_ALPHANUMERIC)
     *
     * Situation after adding edges to vertices at position 4
     * (initial) -- BYTE(A) (20) --> (1_BYTE) -- BYTE(B) (28) --> (2_BYTE) -- BYTE(C)         (36) --> (3_BYTE) -- BYTE(D) (44) --> (4_BYTE)
     *                               (1_BYTE) -- ALPHANUMERIC(BC)                             (44) --> (3_ALPHANUMERIC) -- ALPHANUMERIC(DE)                             (55) --> (5_ALPHANUMERIC)
     * (initial) -- ALPHANUMERIC(AB)                     (24) --> (2_ALPHANUMERIC) -- ALPHANUMERIC(CD)                             (35) --> (4_ALPHANUMERIC) -- BYTE(E) (55) --> (5_BYTE)
     *
     * Situation after adding edges to vertices at position 5
     * (initial) -- BYTE(A) (20) --> (1_BYTE) -- BYTE(B) (28) --> (2_BYTE) -- BYTE(C)         (36) --> (3_BYTE) -- BYTE(D)         (44) --> (4_BYTE) -- BYTE(E)         (52) --> (5_BYTE)
     *                               (1_BYTE) -- ALPHANUMERIC(BC)                             (44) --> (3_ALPHANUMERIC) -- ALPHANUMERIC(DE)                             (55) --> (5_ALPHANUMERIC)
     * (initial) -- ALPHANUMERIC(AB)                     (24) --> (2_ALPHANUMERIC) -- ALPHANUMERIC(CD)                             (35) --> (4_ALPHANUMERIC)
     *
     * Encoding as BYTE(ABCDE) has the smallest size of 52 and is hence chosen. The encodation ALPHANUMERIC(ABCD),
     * BYTE(E) is longer with a size of 55.
     *
     * Example 2 encoding the string "XXYY" where X denotes a character unique to character set ISO-8859-2 and Y a
     * character unique to ISO-8859-3. Both characters encode as double byte in UTF-8:
     *
     * Initial situation
     * (initial) -- BYTE(X) (32) --> (1_BYTE_ISO-8859-2)
     * (initial) -- BYTE(X) (40) --> (1_BYTE_UTF-8)
     * (initial) -- BYTE(X) (40) --> (1_BYTE_UTF-16BE)
     *
     * Situation after adding edges to vertices at position 1
     * (initial) -- BYTE(X) (32) --> (1_BYTE_ISO-8859-2) -- BYTE(X) (40) --> (2_BYTE_ISO-8859-2)
     *                               (1_BYTE_ISO-8859-2) -- BYTE(X) (72) --> (2_BYTE_UTF-8)
     *                               (1_BYTE_ISO-8859-2) -- BYTE(X) (72) --> (2_BYTE_UTF-16BE)
     * (initial) -- BYTE(X) (40) --> (1_BYTE_UTF-8)
     * (initial) -- BYTE(X) (40) --> (1_BYTE_UTF-16BE)
     *
     * Situation after adding edges to vertices at position 2
     * (initial) -- BYTE(X) (32) --> (1_BYTE_ISO-8859-2) -- BYTE(X) (40) --> (2_BYTE_ISO-8859-2)
     *                                                                       (2_BYTE_ISO-8859-2) -- BYTE(Y) (72) --> (3_BYTE_ISO-8859-3)
     *                                                                       (2_BYTE_ISO-8859-2) -- BYTE(Y) (80) --> (3_BYTE_UTF-8)
     *                                                                       (2_BYTE_ISO-8859-2) -- BYTE(Y) (80) --> (3_BYTE_UTF-16BE)
     * (initial) -- BYTE(X) (40) --> (1_BYTE_UTF-8) -- BYTE(X) (56) --> (2_BYTE_UTF-8)
     * (initial) -- BYTE(X) (40) --> (1_BYTE_UTF-16BE) -- BYTE(X) (56) --> (2_BYTE_UTF-16BE)
     *
     * Situation after adding edges to vertices at position 3
     * (initial) -- BYTE(X) (32) --> (1_BYTE_ISO-8859-2) -- BYTE(X) (40) --> (2_BYTE_ISO-8859-2) -- BYTE(Y) (72) --> (3_BYTE_ISO-8859-3)
     *                                                                                                               (3_BYTE_ISO-8859-3) -- BYTE(Y) (80) --> (4_BYTE_ISO-8859-3)
     *                                                                                                               (3_BYTE_ISO-8859-3) -- BYTE(Y) (112) --> (4_BYTE_UTF-8)
     *                                                                                                               (3_BYTE_ISO-8859-3) -- BYTE(Y) (112) --> (4_BYTE_UTF-16BE)
     * (initial) -- BYTE(X) (40) --> (1_BYTE_UTF-8) -- BYTE(X) (56) --> (2_BYTE_UTF-8) -- BYTE(Y) (72) --> (3_BYTE_UTF-8)
     * (initial) -- BYTE(X) (40) --> (1_BYTE_UTF-16BE) -- BYTE(X) (56) --> (2_BYTE_UTF-16BE) -- BYTE(Y) (72) --> (3_BYTE_UTF-16BE)
     *
     * Situation after adding edges to vertices at position 4
     * (initial) -- BYTE(X) (32) --> (1_BYTE_ISO-8859-2) -- BYTE(X) (40) --> (2_BYTE_ISO-8859-2) -- BYTE(Y) (72) --> (3_BYTE_ISO-8859-3) -- BYTE(Y) (80) --> (4_BYTE_ISO-8859-3)
     *                                                                                                               (3_BYTE_UTF-8) -- BYTE(Y) (88) --> (4_BYTE_UTF-8)
     *                                                                                                               (3_BYTE_UTF-16BE) -- BYTE(Y) (88) --> (4_BYTE_UTF-16BE)
     * (initial) -- BYTE(X) (40) --> (1_BYTE_UTF-8) -- BYTE(X) (56) --> (2_BYTE_UTF-8) -- BYTE(Y) (72) --> (3_BYTE_UTF-8)
     * (initial) -- BYTE(X) (40) --> (1_BYTE_UTF-16BE) -- BYTE(X) (56) --> (2_BYTE_UTF-16BE) -- BYTE(Y) (72) --> (3_BYTE_UTF-16BE)
     *
     * Encoding as ECI(ISO-8859-2),BYTE(XX),ECI(ISO-8859-3),BYTE(YY) has the smallest size of 80 and is hence chosen.
     * The encodation ECI(UTF-8),BYTE(XXYY) is longer with a size of 88.
     */

    int inputLength = stringToEncode.length();

    // Array that represents vertices. There is a vertex for every character, encoding and mode. The vertex contains
    // a list of all edges that lead to it that have the same encoding and mode.
    // The lists are created lazily

    // The last dimension in the array below encodes the 4 modes KANJI, ALPHANUMERIC, NUMERIC and BYTE via the
    // function getCompactedOrdinal(Mode)
    @SuppressWarnings("unchecked")
    ArrayList<Edge>[][][] edges = new ArrayList[inputLength + 1][encoders.length][4];
    addEdges(version, edges, 0, null);

    for (int i = 1; i <= inputLength; i++) {
      for (int j = 0; j < encoders.length; j++) {
        for (int k = 0; k < 4; k++) {
          Edge minimalEdge;
          if (edges[i][j][k] != null) {
            ArrayList<Edge> localEdges = edges[i][j][k];
            int minimalIndex = -1;
            int minimalSize = Integer.MAX_VALUE;
            for (int l = 0; l < localEdges.size(); l++) {
              Edge edge = localEdges.get(l);
              if (edge.cachedTotalSize < minimalSize) {
                minimalIndex = l;
                minimalSize = edge.cachedTotalSize;
              }
            }
            assert minimalIndex != -1;
            minimalEdge = localEdges.get(minimalIndex);
            localEdges.clear();
            localEdges.add(minimalEdge);
            if (i < inputLength) {
              addEdges(version, edges, i, minimalEdge);
            }
          }
        }
      }

    }
    int minimalJ = -1;
    int minimalK = -1;
    int minimalSize = Integer.MAX_VALUE;
    for (int j = 0; j < encoders.length; j++) {
      for (int k = 0; k < 4; k++) {
        if (edges[inputLength][j][k] != null) {
          ArrayList<Edge> localEdges = edges[inputLength][j][k];
          assert localEdges.size() == 1;
          Edge edge = localEdges.get(0);
          if (edge.cachedTotalSize < minimalSize) {
            minimalSize = edge.cachedTotalSize;
            minimalJ = j;
            minimalK = k;
          }
        }
      }
    }
    if (minimalJ < 0) {
      throw new WriterException("Internal error: failed to encode \"" + stringToEncode + "\"");
    }
    return postProcess(edges[inputLength][minimalJ][minimalK].get(0), version);
  }

  private final class Edge {
    private final Mode mode;
    private final int fromPosition;
    private final int charsetEncoderIndex;
    private final int characterLength;
    private final Edge previous;
    private final int cachedTotalSize;
    private Edge(Mode mode, int fromPosition, int charsetEncoderIndex, int characterLength, Edge previous,
        Version version) {
      this.mode = mode; 
      this.fromPosition = fromPosition;
      this.charsetEncoderIndex = mode == Mode.BYTE || previous == null ? charsetEncoderIndex :
          previous.charsetEncoderIndex; //inherit the encoding if not of type BYTE
      this.characterLength = characterLength;
      this.previous = previous;

      int size = previous != null ? previous.cachedTotalSize : 0;

      boolean needECI = mode == Mode.BYTE &&
          (previous == null && this.charsetEncoderIndex != 0) || // at the beginning and charset is not ISO-8859-1
          (previous != null && this.charsetEncoderIndex != previous.charsetEncoderIndex);

      if (previous == null || mode != previous.mode || needECI) {
        size += 4 + mode.getCharacterCountBits(version);
      }
      switch (mode) {
        case KANJI:
          size += 13;
          break;
        case ALPHANUMERIC:
          size += characterLength == 1 ? 6 : 11;
          break;
        case NUMERIC:
          size += characterLength == 1 ? 4 : characterLength == 2 ? 7 : 10;
          break;
        case BYTE:
          size += 8 * stringToEncode.substring(fromPosition, fromPosition + characterLength).getBytes(
              encoders[charsetEncoderIndex].charset()).length;
          if (needECI) {
            size += 4 + 8; // the ECI assignment numbers for ISO-8859-x, UTF-8 and UTF-16 are all 8 bit long
          }
          break;
      }
      cachedTotalSize = size;
    }
  }

  final class ResultList extends ArrayList<ResultList.ResultNode> {

    final Version version;

    ResultList(Version version,Edge solution) {
      this.version = version;
      int length = 0;
      Edge current = solution;
      while (current != null) {
        length += current.characterLength;
        Edge previous = current.previous;

        boolean needECI = current.mode == Mode.BYTE &&
            (previous == null && current.charsetEncoderIndex != 0) || // at the beginning and charset is not ISO-8859-1
            (previous != null && current.charsetEncoderIndex != previous.charsetEncoderIndex);

        if (previous == null || previous.mode != current.mode || needECI) {
          add(0,new ResultNode(current.mode, current.fromPosition, current.charsetEncoderIndex, length));
          length = 0;
        }

        if (needECI) {
          add(0,new ResultNode(Mode.ECI, current.fromPosition, current.charsetEncoderIndex, 0));
        }
        current = previous;
      }
    }

    /**
     * returns the size in bits
     */
    int getSize() {
      int result = 0;
      for (ResultNode resultNode : this) {
        result += resultNode.getSize();
      }
      return result;
    }

    /**
     * appends the bits
     */
    void getBits(BitArray bits) throws WriterException {
      for (ResultNode resultNode : this) {
        resultNode.getBits(bits);
      }
    }

    Version getVersion() {
      int versionNumber = version.getVersionNumber();
      int lowerLimit;
      int upperLimit;
      switch (getVersionSize(version)) {
        case SMALL:
          lowerLimit = 1;
          upperLimit = 9;
          break;
        case MEDIUM:
          lowerLimit = 10;
          upperLimit = 26;
          break;
        case LARGE:
        default:
          lowerLimit = 27;
          upperLimit = 40;
          break;
      }
      // increase version if needed
      while (versionNumber < upperLimit && !Encoder.willFit(getSize(), Version.getVersionForNumber(versionNumber),
        ecLevel)) {
        versionNumber++;
      }
      // shrink version if possible
      while (versionNumber > lowerLimit && Encoder.willFit(getSize(), Version.getVersionForNumber(versionNumber - 1),
        ecLevel)) {
        versionNumber--;
      }
      return Version.getVersionForNumber(versionNumber);
    }

    public String toString() {
      StringBuilder result = new StringBuilder();
      ResultNode previous = null;
      for (ResultNode current : this) {
        if (previous != null) {
          result.append(",");
        }
        result.append(current.toString());
        previous = current;
      }
      return result.toString();
    }

    final class ResultNode {

      private final Mode mode;
      private final int fromPosition;
      private final int charsetEncoderIndex;
      private final int characterLength;

      ResultNode(Mode mode, int fromPosition, int charsetEncoderIndex, int characterLength) {
        this.mode = mode;
        this.fromPosition = fromPosition;
        this.charsetEncoderIndex = charsetEncoderIndex;
        this.characterLength = characterLength;
      }

      /**
       * returns the size in bits
       */
      private int getSize() {
        int size = 4 + mode.getCharacterCountBits(version);
        switch (mode) {
          case KANJI:
            size += 13 * characterLength;
            break;
          case ALPHANUMERIC:
            size += (characterLength / 2) * 11;
            size += (characterLength % 2) == 1 ? 6 : 0;
            break;
          case NUMERIC:
            size += (characterLength / 3) * 10;
            int rest = characterLength % 3;
            size += rest == 1 ? 4 : rest == 2 ? 7 : 0;
            break;
          case BYTE:
            size += 8 * getCharacterCountIndicator();
            break;
          case ECI:
            size += 8; // the ECI assignment numbers for ISO-8859-x, UTF-8 and UTF-16 are all 8 bit long
        }
        return size;
      }

      /**
       * returns the length in characters according to the specification (differs from getCharacterLength() in BYTE mode
       * for multi byte encoded characters)
       */
      private int getCharacterCountIndicator() {
        return mode == Mode.BYTE ? stringToEncode.substring(fromPosition, fromPosition + characterLength).getBytes(
            encoders[charsetEncoderIndex].charset()).length : characterLength;
      }

      /**
       * appends the bits
       */
      private void getBits(BitArray bits) throws WriterException {
        bits.appendBits(mode.getBits(), 4);
        if (characterLength > 0) {
          int length = getCharacterCountIndicator();
          bits.appendBits(length, mode.getCharacterCountBits(version));
        }
        if (mode == Mode.ECI) {
          bits.appendBits(CharacterSetECI.getCharacterSetECI(encoders[charsetEncoderIndex].charset()).getValue(), 8);
        } else if (characterLength > 0) {
          // append data
          Encoder.appendBytes(stringToEncode.substring(fromPosition, fromPosition + characterLength), mode, bits,
              encoders[charsetEncoderIndex].charset());
        }
      }

      public String toString() {
        StringBuilder result = new StringBuilder();
        result.append(mode).append('(');
        if (mode == Mode.ECI) {
          result.append(encoders[charsetEncoderIndex].charset().displayName());
        } else {
          result.append(makePrintable(stringToEncode.substring(fromPosition, fromPosition + characterLength)));
        }
        result.append(')');
        return result.toString();
      }

      private String makePrintable(String s) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
          if (s.charAt(i) < 32 || s.charAt(i) > 126) {
            result.append('.');
          } else {
            result.append(s.charAt(i));
          }
        }
        return result.toString();
      }
    }
  }
}
