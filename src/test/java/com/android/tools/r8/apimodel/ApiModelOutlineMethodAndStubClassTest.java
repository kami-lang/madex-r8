// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.apimodel;

import static com.android.tools.r8.apimodel.ApiModelingTestHelper.setMockApiLevelForClass;
import static com.android.tools.r8.apimodel.ApiModelingTestHelper.setMockApiLevelForDefaultInstanceInitializer;
import static com.android.tools.r8.apimodel.ApiModelingTestHelper.setMockApiLevelForMethod;
import static com.android.tools.r8.apimodel.ApiModelingTestHelper.verifyThat;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.SingleTestRunResult;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestCompilerBuilder;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.ToolHelper.DexVm.Version;
import com.android.tools.r8.apimodel.ApiModelMockClassTest.TestClass;
import com.android.tools.r8.testing.AndroidBuildVersion;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import java.lang.reflect.Method;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class ApiModelOutlineMethodAndStubClassTest extends TestBase {

  private final AndroidApiLevel libraryClassLevel = AndroidApiLevel.M;
  private final AndroidApiLevel libraryMethodLevel = AndroidApiLevel.Q;

  @Parameter public TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimesAndApiLevels().build();
  }

  public Method apiMethod() throws Exception {
    return LibraryClass.class.getDeclaredMethod("foo");
  }

  private void setupTestBuilder(TestCompilerBuilder<?, ?, ?, ?, ?> testBuilder) throws Exception {
    testBuilder
        .addProgramClasses(Main.class, TestClass.class)
        .addLibraryClasses(LibraryClass.class)
        .addDefaultRuntimeLibrary(parameters)
        .setMinApi(parameters.getApiLevel())
        .addAndroidBuildVersion()
        // TODO(b/213552119): Remove when enabled by default.
        .apply(ApiModelingTestHelper::enableApiCallerIdentification)
        .apply(ApiModelingTestHelper::enableStubbingOfClasses)
        .apply(ApiModelingTestHelper::enableOutliningOfMethods)
        .apply(setMockApiLevelForClass(LibraryClass.class, libraryClassLevel))
        .apply(setMockApiLevelForDefaultInstanceInitializer(LibraryClass.class, libraryClassLevel))
        .apply(setMockApiLevelForMethod(apiMethod(), libraryMethodLevel));
  }

  public boolean addToBootClasspath() {
    return parameters.isDexRuntime()
        && parameters.getRuntime().maxSupportedApiLevel().isGreaterThanOrEqualTo(libraryClassLevel);
  }

  @Test
  public void testD8Debug() throws Exception {
    // TODO(b/197078995): Make this work on 12+.
    assumeTrue(
        parameters.isDexRuntime()
            && parameters.getDexRuntimeVersion().isOlderThan(Version.V12_0_0));
    testForD8(parameters.getBackend())
        .setMode(CompilationMode.DEBUG)
        .apply(this::setupTestBuilder)
        .compile()
        .applyIf(addToBootClasspath(), b -> b.addBootClasspathClasses(LibraryClass.class))
        .run(parameters.getRuntime(), Main.class)
        .apply(this::checkOutput)
        .inspect(this::inspect);
  }

  @Test
  public void testD8Release() throws Exception {
    // TODO(b/197078995): Make this work on 12+.
    assumeFalse(
        parameters.isCfRuntime()
            || parameters.getDexRuntimeVersion().isNewerThanOrEqual(Version.V12_0_0));
    testForD8(parameters.getBackend())
        .setMode(CompilationMode.RELEASE)
        .apply(this::setupTestBuilder)
        .compile()
        .applyIf(addToBootClasspath(), b -> b.addBootClasspathClasses(LibraryClass.class))
        .run(parameters.getRuntime(), Main.class)
        .apply(this::checkOutput)
        .inspect(this::inspect);
  }

  @Test
  public void testR8() throws Exception {
    // TODO(b/197078995): Make this work on 12+.
    assumeFalse(
        parameters.isDexRuntime()
            && parameters.getDexRuntimeVersion().isNewerThanOrEqual(Version.V12_0_0));
    testForR8(parameters.getBackend())
        .apply(this::setupTestBuilder)
        .addKeepMainRule(Main.class)
        .compile()
        .applyIf(addToBootClasspath(), b -> b.addBootClasspathClasses(LibraryClass.class))
        .run(parameters.getRuntime(), Main.class)
        .apply(this::checkOutput)
        .inspect(this::inspect);
  }

  private void inspect(CodeInspector inspector) throws Exception {
    verifyThat(inspector, parameters, LibraryClass.class).stubbedUntil(libraryClassLevel);
    verifyThat(inspector, parameters, apiMethod())
        .isOutlinedFromUntil(
            Main.class.getDeclaredMethod("main", String[].class), libraryMethodLevel);
  }

  private void checkOutput(SingleTestRunResult<?> runResult) {
    if (parameters.isDexRuntime()
        && parameters.getApiLevel().isGreaterThanOrEqualTo(libraryClassLevel)) {
      runResult.assertSuccessWithOutputLines("LibraryClass::foo");
    } else {
      runResult.assertSuccessWithOutputLines("Hello World");
    }
  }

  // Only present from api level 23.
  public static class LibraryClass {

    // Only present from api level 30
    public void foo() {
      System.out.println("LibraryClass::foo");
    }
  }

  public static class Main {

    public static void main(String[] args) {
      if (AndroidBuildVersion.VERSION >= 23) {
        new LibraryClass().foo();
      } else {
        System.out.println("Hello World");
      }
    }
  }
}
