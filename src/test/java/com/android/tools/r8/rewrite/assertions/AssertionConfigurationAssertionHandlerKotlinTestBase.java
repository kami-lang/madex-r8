// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.rewrite.assertions;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.junit.Assume.assumeTrue;

import com.android.tools.r8.KotlinTestBase;
import com.android.tools.r8.KotlinTestParameters;
import com.android.tools.r8.R8FullTestBuilder;
import com.android.tools.r8.TestCompilerBuilder;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.references.MethodReference;
import com.android.tools.r8.rewrite.assertions.assertionhandler.AssertionHandlers;
import com.android.tools.r8.utils.BooleanUtils;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
public abstract class AssertionConfigurationAssertionHandlerKotlinTestBase extends KotlinTestBase {

  @Parameterized.Parameters(name = "{0}, {1}, kotlin-stdlib as library: {2}, -Xassertions=jvm: {3}")
  public static Collection<Object[]> data() {
    return buildParameters(
        getTestParameters().withAllRuntimesAndApiLevels().build(),
        getKotlinTestParameters().withAllCompilersAndTargetVersions().build(),
        BooleanUtils.values(),
        BooleanUtils.values());
  }

  protected final TestParameters parameters;
  protected final boolean kotlinStdlibAsLibrary;
  protected final boolean useJvmAssertions;
  protected final KotlinCompileMemoizer compiledForAssertions;

  public AssertionConfigurationAssertionHandlerKotlinTestBase(
      TestParameters parameters,
      KotlinTestParameters kotlinParameters,
      boolean kotlinStdlibAsClasspath,
      boolean useJvmAssertions)
      throws IOException {
    super(kotlinParameters);
    this.parameters = parameters;
    this.kotlinStdlibAsLibrary = kotlinStdlibAsClasspath;
    this.useJvmAssertions = useJvmAssertions;
    this.compiledForAssertions =
        useJvmAssertions ? kotlinWithJvmAssertions() : kotlinWithoutJvmAssertions();
  }

  private KotlinCompileMemoizer kotlinWithJvmAssertions() throws IOException {
    return getCompileMemoizer(getKotlinFiles())
        .configure(kotlinCompilerTool -> kotlinCompilerTool.setUseJvmAssertions(true));
  }

  private KotlinCompileMemoizer kotlinWithoutJvmAssertions() throws IOException {
    return getCompileMemoizer(getKotlinFiles())
        .configure(kotlinCompilerTool -> kotlinCompilerTool.setUseJvmAssertions(false));
  }

  protected abstract String getExpectedOutput();

  protected abstract MethodReference getAssertionHandler() throws Exception;

  protected abstract List<Path> getKotlinFiles() throws IOException;

  protected abstract String getTestClassName();

  protected void configureR8(R8FullTestBuilder builder) {}

  private Path kotlinStdlibLibraryForRuntime() throws Exception {
    Path kotlinStdlibCf = kotlinc.getKotlinStdlibJar();
    if (parameters.getRuntime().isCf()) {
      return kotlinStdlibCf;
    }

    Path kotlinStdlibDex = temp.newFolder().toPath().resolve("kotlin-stdlib-dex.jar");
    testForD8()
        .addProgramFiles(kotlinStdlibCf)
        .setMinApi(parameters.getApiLevel())
        .compile()
        .writeToZip(kotlinStdlibDex);
    return kotlinStdlibDex;
  }

  private MethodReference getAssertionHandlerIgnoreException() {
    try {
      return getAssertionHandler();
    } catch (Exception e) {
      return null;
    }
  }

  private void configureKotlinStdlib(TestCompilerBuilder<?, ?, ?, ?, ?> builder) throws Exception {
    if (kotlinStdlibAsLibrary) {
      builder
          .addClasspathFiles(kotlinc.getKotlinStdlibJar())
          .addRunClasspathFiles(kotlinStdlibLibraryForRuntime());
    } else {
      builder.addProgramFiles(kotlinc.getKotlinStdlibJar());
    }
  }

  @Test
  public void testD8() throws Exception {
    assumeTrue(parameters.isDexRuntime());
    testForD8()
        .apply(this::configureKotlinStdlib)
        .setMinApi(parameters.getApiLevel())
        .addProgramClasses(AssertionHandlers.class)
        .addProgramFiles(compiledForAssertions.getForConfiguration(kotlinc, targetVersion))
        .addAssertionsConfiguration(
            builder ->
                builder
                    .setAssertionHandler(getAssertionHandlerIgnoreException())
                    .setScopeAll()
                    .build())
        .run(parameters.getRuntime(), getTestClassName())
        .assertSuccessWithOutput(getExpectedOutput());
  }

  @Test
  public void testR8() throws Exception {
    testForR8(parameters.getBackend())
        .apply(this::configureKotlinStdlib)
        .setMinApi(parameters.getApiLevel())
        .addProgramClasses(AssertionHandlers.class)
        .addProgramFiles(compiledForAssertions.getForConfiguration(kotlinc, targetVersion))
        .addAssertionsConfiguration(
            builder ->
                builder
                    .setAssertionHandler(getAssertionHandlerIgnoreException())
                    .setScopeAll()
                    .build())
        .addKeepMainRule(getTestClassName())
        .apply(this::configureR8)
        .allowDiagnosticWarningMessages(!kotlinStdlibAsLibrary)
        .compile()
        .applyIf(
            !kotlinStdlibAsLibrary,
            result ->
                result.assertAllWarningMessagesMatch(
                    equalTo("Resource 'META-INF/MANIFEST.MF' already exists.")))
        .run(parameters.getRuntime(), getTestClassName())
        .assertSuccessWithOutput(getExpectedOutput());
  }
}
