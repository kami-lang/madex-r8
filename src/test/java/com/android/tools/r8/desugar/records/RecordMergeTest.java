// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.desugar.records;

import static com.android.tools.r8.DiagnosticsMatcher.diagnosticType;
import static com.android.tools.r8.utils.codeinspector.Matchers.isAbsent;
import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.D8TestCompileResult;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.errors.DuplicateTypesDiagnostic;
import com.android.tools.r8.errors.MissingGlobalSyntheticsConsumerDiagnostic;
import com.android.tools.r8.synthesis.globals.GlobalSyntheticsConsumerAndProvider;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import java.nio.file.Path;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public class RecordMergeTest extends TestBase {

  private static final String RECORD_NAME_1 = "RecordWithMembers";
  private static final byte[][] PROGRAM_DATA_1 = RecordTestUtils.getProgramData(RECORD_NAME_1);
  private static final String MAIN_TYPE_1 = RecordTestUtils.getMainType(RECORD_NAME_1);
  private static final String EXPECTED_RESULT_1 =
      StringUtils.lines(
          "BobX", "43", "BobX", "43", "FelixX", "-1", "FelixX", "-1", "print", "Bob43", "extra");

  private static final String RECORD_NAME_2 = "SimpleRecord";
  private static final byte[][] PROGRAM_DATA_2 = RecordTestUtils.getProgramData(RECORD_NAME_2);
  private static final String MAIN_TYPE_2 = RecordTestUtils.getMainType(RECORD_NAME_2);
  private static final String EXPECTED_RESULT_2 =
      StringUtils.lines("Jane Doe", "42", "Jane Doe", "42");

  private final TestParameters parameters;

  public RecordMergeTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Parameterized.Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimes().withAllApiLevelsAlsoForCf().build();
  }

  @Test
  public void testFailureWithoutGlobalSyntheticsConsumer() throws Exception {
    assertThrows(
        CompilationFailedException.class,
        () ->
            testForD8(parameters.getBackend())
                .addProgramClassFileData(PROGRAM_DATA_1)
                .setMinApi(parameters.getApiLevel())
                .setIntermediate(true)
                .compileWithExpectedDiagnostics(
                    diagnostics ->
                        diagnostics
                            .assertOnlyErrors()
                            .assertErrorsMatch(
                                diagnosticType(MissingGlobalSyntheticsConsumerDiagnostic.class))));
  }

  @Test
  public void testMergeDesugaredInputs() throws Exception {
    GlobalSyntheticsConsumerAndProvider globals1 = new GlobalSyntheticsConsumerAndProvider();
    Path output1 =
        testForD8(parameters.getBackend())
            .addProgramClassFileData(PROGRAM_DATA_1)
            .setMinApi(parameters.getApiLevel())
            .setIntermediate(true)
            .apply(b -> b.getBuilder().setGlobalSyntheticsConsumer(globals1))
            .compile()
            .inspect(this::assertDoesNotHaveRecordTag)
            .writeToZip();

    GlobalSyntheticsConsumerAndProvider globals2 = new GlobalSyntheticsConsumerAndProvider();
    Path output2 =
        testForD8(parameters.getBackend())
            .addProgramClassFileData(PROGRAM_DATA_2)
            .setMinApi(parameters.getApiLevel())
            .setIntermediate(true)
            .apply(b -> b.getBuilder().setGlobalSyntheticsConsumer(globals2))
            .compile()
            .inspect(this::assertDoesNotHaveRecordTag)
            .writeToZip();

    assertTrue(globals1.hasBytes());
    assertTrue(globals2.hasBytes());

    D8TestCompileResult result =
        testForD8(parameters.getBackend())
            .addProgramFiles(output1, output2)
            .apply(b -> b.getBuilder().addGlobalSyntheticsResourceProviders(globals1, globals2))
            .setMinApi(parameters.getApiLevel())
            .compile()
            .inspect(this::assertHasRecordTag);

    result.run(parameters.getRuntime(), MAIN_TYPE_1).assertSuccessWithOutput(EXPECTED_RESULT_1);
    result.run(parameters.getRuntime(), MAIN_TYPE_2).assertSuccessWithOutput(EXPECTED_RESULT_2);
  }

  @Test
  public void testMergeDesugaredAndNonDesugaredInputs() throws Exception {
    GlobalSyntheticsConsumerAndProvider globals1 = new GlobalSyntheticsConsumerAndProvider();
    Path output1 =
        testForD8(parameters.getBackend())
            .addProgramClassFileData(PROGRAM_DATA_1)
            .setMinApi(parameters.getApiLevel())
            .setIntermediate(true)
            .apply(b -> b.getBuilder().setGlobalSyntheticsConsumer(globals1))
            .compile()
            .writeToZip();

    D8TestCompileResult result =
        testForD8(parameters.getBackend())
            .addProgramFiles(output1)
            .apply(b -> b.getBuilder().addGlobalSyntheticsResourceProviders(globals1))
            .addProgramClassFileData(PROGRAM_DATA_2)
            .setMinApi(parameters.getApiLevel())
            .compile();
    result.run(parameters.getRuntime(), MAIN_TYPE_1).assertSuccessWithOutput(EXPECTED_RESULT_1);
    result.run(parameters.getRuntime(), MAIN_TYPE_2).assertSuccessWithOutput(EXPECTED_RESULT_2);
  }

  @Test
  public void testMergeNonIntermediates() throws Exception {
    Path output1 =
        testForD8(parameters.getBackend())
            .addProgramClassFileData(PROGRAM_DATA_1)
            .setMinApi(parameters.getApiLevel())
            .compile()
            .inspect(this::assertHasRecordTag)
            .writeToZip();

    Path output2 =
        testForD8(parameters.getBackend())
            .addProgramClassFileData(PROGRAM_DATA_2)
            .setMinApi(parameters.getApiLevel())
            .compile()
            .inspect(this::assertHasRecordTag)
            .writeToZip();

    assertThrows(
        CompilationFailedException.class,
        () ->
            testForD8(parameters.getBackend())
                .addProgramFiles(output1, output2)
                .setMinApi(parameters.getApiLevel())
                .compileWithExpectedDiagnostics(
                    diagnostics ->
                        diagnostics
                            .assertOnlyErrors()
                            .assertErrorsMatch(diagnosticType(DuplicateTypesDiagnostic.class))));
  }

  private void assertHasRecordTag(CodeInspector inspector) {
    // Note: this should be asserting on record tag.
    assertThat(inspector.clazz("java.lang.Record"), isPresent());
  }

  private void assertDoesNotHaveRecordTag(CodeInspector inspector) {
    // Note: this should be asserting on record tag.
    assertThat(inspector.clazz("java.lang.Record"), isAbsent());
  }
}
