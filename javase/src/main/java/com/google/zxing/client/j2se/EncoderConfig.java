/*
 * Copyright 2015 ZXing authors
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

import java.util.List;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.validators.PositiveInteger;
import com.google.zxing.BarcodeFormat;

final class EncoderConfig {

  static final String DEFAULT_OUTPUT_FILE_BASE = "out";

  @Parameter(names = "--barcode_format",
      description = "Format to encode, from BarcodeFormat class. Not all formats are supported")
  BarcodeFormat barcodeFormat = BarcodeFormat.QR_CODE;

  @Parameter(names = "--image_format",
      description = "image output format, such as PNG, JPG, GIF")
  String imageFormat = "PNG";

  @Parameter(names = "--output",
      description = " File to write to. Defaults to out.png")
  String outputFileBase = DEFAULT_OUTPUT_FILE_BASE;

  @Parameter(names = "--width",
      description = "Image width",
      validateWith = PositiveInteger.class)
  int width = 300;

  @Parameter(names = "--height",
      description = "Image height",
      validateWith = PositiveInteger.class)
  int height = 300;

  @Parameter(names = "--help",
      description = "Prints this help message",
      help = true)
  boolean help;
  
  @Parameter(names = "--errorcorrection",
      description = "Define the error correction level. Defaults to 'M'",
      validateWith = PositiveInteger.class)
  char errorcorrection = 'M';


  @Parameter(description = "(Text to encode)", required = true)
  List<String> contents;

}
