/*
 * Copyright 2011 ZXing authors
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

package com.google.zxing.client.j2se;

import com.beust.jcommander.JCommander;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;

import java.nio.file.Paths;
import java.util.Locale;
import java.util.Map;

/**
 * Command line utility for encoding barcodes.
 * 
 * @author Sean Owen
 */
public final class CommandLineEncoder {

  private CommandLineEncoder() {
  }

  public static void main(String[] args) throws Exception {
    EncoderConfig config = new EncoderConfig();
    JCommander jCommander = new JCommander(config, args);
    jCommander.setProgramName(CommandLineEncoder.class.getSimpleName());
    if (config.help) {
      jCommander.usage();
      return;
    }
    String outFileString = config.outputFileBase;
    if (EncoderConfig.DEFAULT_OUTPUT_FILE_BASE.equals(outFileString)) {
      outFileString += '.' + config.imageFormat.toLowerCase(Locale.ENGLISH);
    }
    
    if (config.barcodeFormat == BarcodeFormat.QR_CODE) {
        Map<EncodeHintType,?> hints = new Map<EncodeHintType,?>;
        if (config.errorcorrection == 'H') {
            hints.put(EncodeHintType.ERRORCORRECTION,ErrorCorrectionLevel.H);
        } else if (config.errorcorrection == 'L') {
            hints.put(EncodeHintType.ERRORCORRECTION,ErrorCorrectionLevel.L);
        } else if (config.errorcorrection == 'Q') {
            hints.put(EncodeHintType.ERRORCORRECTION,ErrorCorrectionLevel.Q);
        } else {
            hints.put(EncodeHintType.ERRORCORRECTION,ErrorCorrectionLevel.M);
        }
        BitMatrix matrix = new MultiFormatWriter().encode(config.contents.get(0), config.barcodeFormat,
                                                          config.width, config.height, hints);
        MatrixToImageWriter.writeToPath(matrix, config.imageFormat, Paths.get(outFileString));
        
    } else {
        BitMatrix matrix = new MultiFormatWriter().encode(config.contents.get(0), config.barcodeFormat,
                                                          config.width, config.height);
        MatrixToImageWriter.writeToPath(matrix, config.imageFormat, Paths.get(outFileString));
    }
  }

}
