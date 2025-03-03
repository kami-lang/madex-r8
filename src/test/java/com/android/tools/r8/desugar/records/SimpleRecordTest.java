// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.desugar.records;

import static org.junit.Assume.assumeTrue;

import com.android.tools.r8.GlobalSyntheticsConsumer;
import com.android.tools.r8.R8FullTestBuilder;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestRuntime.CfVm;
import com.android.tools.r8.synthesis.globals.GlobalSyntheticsConsumerAndProvider;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.StringUtils;
import java.nio.file.Path;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class SimpleRecordTest extends TestBase {

  private static final String RECORD_NAME = "SimpleRecord";
  private static final byte[][] PROGRAM_DATA = RecordTestUtils.getProgramData(RECORD_NAME);
  private static final String MAIN_TYPE = RecordTestUtils.getMainType(RECORD_NAME);
  private static final String EXPECTED_RESULT =
      StringUtils.lines("Jane Doe", "42", "Jane Doe", "42");

  private final TestParameters parameters;

  public SimpleRecordTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Parameterized.Parameters(name = "{0}")
  public static List<Object[]> data() {
    return buildParameters(
        getTestParameters()
            .withAllRuntimes()
            .withAllApiLevelsAlsoForCf()
            .build());
  }

  private boolean isCfWithNativeRecordSupport() {
    return parameters.isCfRuntime()
        && parameters.asCfRuntime().isNewerThanOrEqual(CfVm.JDK14)
        && parameters.getApiLevel().equals(AndroidApiLevel.B);
  }

  @Test
  public void testReference() throws Exception {
    assumeTrue(isCfWithNativeRecordSupport());
    testForJvm()
        .addProgramClassFileData(PROGRAM_DATA)
        .run(parameters.getRuntime(), MAIN_TYPE)
        .assertSuccessWithOutput(EXPECTED_RESULT);
  }

  @Test
  public void testD8() throws Exception {
    testForD8(parameters.getBackend())
        .addProgramClassFileData(PROGRAM_DATA)
        .setMinApi(parameters.getApiLevel())
        .compile()
        .inspectWithOptions(
            RecordTestUtils::assertNoJavaLangRecord,
            options -> options.testing.disableRecordApplicationReaderMap = true)
        .run(parameters.getRuntime(), MAIN_TYPE)
        .assertSuccessWithOutput(EXPECTED_RESULT);
  }

  @Test
  public void testD8Intermediate() throws Exception {
    assumeTrue(parameters.isDexRuntime());
    GlobalSyntheticsConsumerAndProvider globals = new GlobalSyntheticsConsumerAndProvider();
    Path path = compileIntermediate(globals);
    testForD8()
        .addProgramFiles(path)
        .apply(b -> b.getBuilder().addGlobalSyntheticsResourceProviders(globals))
        .setMinApi(parameters.getApiLevel())
        .setIncludeClassesChecksum(true)
        .run(parameters.getRuntime(), MAIN_TYPE)
        .assertSuccessWithOutput(EXPECTED_RESULT);
  }

  @Test
  public void testD8IntermediateNoDesugaringInStep2() throws Exception {
    assumeTrue(parameters.isDexRuntime());
    GlobalSyntheticsConsumerAndProvider globals = new GlobalSyntheticsConsumerAndProvider();
    Path path = compileIntermediate(globals);
    // In Android Studio they disable desugaring at this point to improve build speed.
    testForD8()
        .addProgramFiles(path)
        .apply(b -> b.getBuilder().addGlobalSyntheticsResourceProviders(globals))
        .setMinApi(parameters.getApiLevel())
        .setIncludeClassesChecksum(true)
        .disableDesugaring()
        .run(parameters.getRuntime(), MAIN_TYPE)
        .assertSuccessWithOutput(EXPECTED_RESULT);
  }

  private Path compileIntermediate(GlobalSyntheticsConsumer globalSyntheticsConsumer)
      throws Exception {
    return testForD8(Backend.DEX)
        .addProgramClassFileData(PROGRAM_DATA)
        .setMinApi(parameters.getApiLevel())
        .setIntermediate(true)
        .setIncludeClassesChecksum(true)
        .apply(b -> b.getBuilder().setGlobalSyntheticsConsumer(globalSyntheticsConsumer))
        .compile()
        .writeToZip();
  }

  @Test
  public void testR8() throws Exception {
    assumeTrue(parameters.isDexRuntime() || isCfWithNativeRecordSupport());
    R8FullTestBuilder builder =
        testForR8(parameters.getBackend())
            .addProgramClassFileData(PROGRAM_DATA)
            .setMinApi(parameters.getApiLevel())
            .addKeepMainRule(MAIN_TYPE);
    if (parameters.isCfRuntime()) {
      builder
          .addLibraryFiles(RecordTestUtils.getJdk15LibraryFiles(temp))
          .compile()
          .inspect(RecordTestUtils::assertRecordsAreRecords)
          .run(parameters.getRuntime(), MAIN_TYPE)
          .assertSuccessWithOutput(EXPECTED_RESULT);
      return;
    }
    builder
        .compile()
        .inspectWithOptions(
            RecordTestUtils::assertNoJavaLangRecord,
            options -> options.testing.disableRecordApplicationReaderMap = true)
        .run(parameters.getRuntime(), MAIN_TYPE)
        .assertSuccessWithOutput(EXPECTED_RESULT);
  }

  @Test
  public void testR8NoMinification() throws Exception {
    assumeTrue(parameters.isDexRuntime() || isCfWithNativeRecordSupport());
    R8FullTestBuilder builder =
        testForR8(parameters.getBackend())
            .addProgramClassFileData(PROGRAM_DATA)
            .noMinification()
            .setMinApi(parameters.getApiLevel())
            .addKeepMainRule(MAIN_TYPE);
    if (parameters.isCfRuntime()) {
      builder
          .addLibraryFiles(RecordTestUtils.getJdk15LibraryFiles(temp))
          .compile()
          .inspect(RecordTestUtils::assertRecordsAreRecords)
          .run(parameters.getRuntime(), MAIN_TYPE)
          .assertSuccessWithOutput(EXPECTED_RESULT);
      return;
    }
    builder
        .compile()
        .inspectWithOptions(
            RecordTestUtils::assertNoJavaLangRecord,
            options -> options.testing.disableRecordApplicationReaderMap = true)
        .run(parameters.getRuntime(), MAIN_TYPE)
        .assertSuccessWithOutput(EXPECTED_RESULT);
  }
}
