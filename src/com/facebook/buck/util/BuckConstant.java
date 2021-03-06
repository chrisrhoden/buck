/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.util;


public class BuckConstant {

  /**
   * The directory where Buck will generate its files.
   * <p>
   * Ultimately, this will be able to be set by {@code -Dbuck.output_dir} and the fields
   * {@link #ANDROID_GEN_DIR}, {@link #GEN_DIR}, and
   * {@code BuildRule#BIN_DIRECTORY_NAME} will be hardcoded subdirectories of this directory.
   */
  public static final String BUCK_OUTPUT_DIRECTORY = "buck-out";

  // TODO(mbolin): The constants ANDROID_GEN_DIR, GEN_DIR, and BIN_DIR should be
  // package-private to the com.facebook.buck.rules directory. Currently, they are also used in the
  // com.facebook.buck.shell package, but these values should be injected into shell commands rather
  // than hardcoded therein. This ensures that shell commands stay build-rule-agnostic.

  /**
   * This directory is analogous to the gen/ directory Ant would produce when building an Android
   * application. It contains files such as R.java, BuildConfig.java, and Manifest.java. It is
   * distinct from the "gen/" directory so that Buck can be used alongside Ant, if desired.
   */
  public static final String ANDROID_GEN_DIR =
      System.getProperty("buck.buck_android_dir", BUCK_OUTPUT_DIRECTORY + "/android");

  public static final String GEN_DIR =
      System.getProperty("buck.buck_gen_dir", BUCK_OUTPUT_DIRECTORY + "/gen");

  public static final String BIN_DIR =
      System.getProperty("buck.buck_bin_dir", BUCK_OUTPUT_DIRECTORY + "/bin");

  public static final String ANNOTATION_DIR =
      System.getProperty("buck.buck_annotation_dir", BUCK_OUTPUT_DIRECTORY + "/annotation");

  /**
   * This variable is package-private because conceptually, only parsing logic should be concerned
   * with the files that define build rules. Note that if the value of this variable changes, the
   * {@code BUILD_RULES_FILE_NAME} constant in {@code buck.py} must be updated, as well.
   */
  public static final String BUILD_RULES_FILE_NAME = "BUCK";

  private BuckConstant() {}
}
