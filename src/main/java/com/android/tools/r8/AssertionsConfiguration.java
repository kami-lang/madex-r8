// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8;

import com.android.tools.r8.references.MethodReference;
import com.android.tools.r8.utils.BooleanUtils;
import com.android.tools.r8.utils.Reporter;

@Keep
public class AssertionsConfiguration {

  /**
   * The simple transformations of the javac generated assertion code during compilation (see {@link
   * AssertionsConfiguration.Builder#setTransformation(AssertionTransformation)}). For configuring
   * the transformation to invoke an assertion handler use {@link
   * AssertionsConfiguration.Builder#setAssertionHandler(MethodReference)}.
   *
   * @deprecated As of version 3.3 this enum should not be used.
   */
  @Deprecated
  @Keep
  public enum AssertionTransformation {
    /** Unconditionally enable the javac generated assertion code. */
    ENABLE,
    /**
     * Unconditionally disable the javac generated assertion code. This will most likely remove the
     * javac generated assertion code completely.
     */
    DISABLE,
    /** Passthrough of the javac generated assertion code. */
    PASSTHROUGH
  }

  public enum AssertionTransformationScope {
    ALL,
    PACKAGE,
    CLASS
  }

  private final AssertionTransformation transformation;
  private final MethodReference assertionHandler;
  private final AssertionTransformationScope scope;
  private final String value;

  AssertionsConfiguration(
      AssertionTransformation transformation,
      MethodReference assertionHandler,
      AssertionTransformationScope scope,
      String value) {
    this.transformation = transformation;
    this.assertionHandler = assertionHandler;
    this.scope = scope;
    this.value = value;
    assert BooleanUtils.xor(transformation != null, assertionHandler != null);
  }

  public boolean isCompileTimeEnabled() {
    return transformation == AssertionTransformation.ENABLE;
  }

  public boolean isCompileTimeDisabled() {
    return transformation == AssertionTransformation.DISABLE;
  }

  public boolean isPassthrough() {
    return transformation == AssertionTransformation.PASSTHROUGH;
  }

  public boolean isAssertionHandler() {
    return assertionHandler != null;
  }

  /**
   * @deprecated As of version 3.3, use one of {@link #isCompileTimeEnabled()} ()}, {@link
   *     #isCompileTimeDisabled()} ()} or {@link #isPassthrough()} ()}.
   */
  @Deprecated
  public AssertionTransformation getTransformation() {
    return transformation;
  }

  public MethodReference getAssertionHandler() {
    return assertionHandler;
  }

  public AssertionTransformationScope getScope() {
    return scope;
  }

  public String getValue() {
    return value;
  }

  static AssertionsConfiguration.Builder builder(Reporter reporter) {
    return new AssertionsConfiguration.Builder(reporter);
  }

  /**
   * Builder for constructing a <code>{@link AssertionsConfiguration}</code>.
   *
   * <p>A builder is obtained by calling {@link
   * BaseCompilerCommand.Builder#addAssertionsConfiguration}.
   */
  @Keep
  public static class Builder {
    Reporter reporter;
    private AssertionTransformation transformation;
    private MethodReference assertionHandler;
    private AssertionTransformationScope scope;
    private String value;

    private Builder(Reporter reporter) {
      this.reporter = reporter;
    }

    /**
     * Set how to handle javac generated assertion code.
     *
     * @deprecated As of version 3.3, use one of {@link #setCompileTimeDisable()}, {@link
     *     #setCompileTimeDisable()} or {@link #setPassthrough()} ()}.
     */
    @Deprecated
    public AssertionsConfiguration.Builder setTransformation(
        AssertionTransformation transformation) {
      this.transformation = transformation;
      this.assertionHandler = null;
      return this;
    }

    /**
     * Unconditionally enable javac generated assertion code in all packages and classes. This
     * corresponds to passing <code>-enableassertions</code> or <code>-ea</code> to the java CLI.
     */
    public AssertionsConfiguration.Builder setCompileTimeEnable() {
      setTransformation(AssertionTransformation.ENABLE);
      return this;
    }

    /** @deprecated As of version 3.3, replaced by {@link #setCompileTimeEnable()} ()} */
    @Deprecated
    public AssertionsConfiguration.Builder setEnable() {
      setTransformation(AssertionTransformation.ENABLE);
      return this;
    }

    /**
     * Disable the javac generated assertion code in all packages and classes. This corresponds to
     * passing <code>-disableassertions</code> or <code>-da</code> to the java CLI.
     */
    public AssertionsConfiguration.Builder setCompileTimeDisable() {
      setTransformation(AssertionTransformation.DISABLE);
      return this;
    }

    /** @deprecated As of version 3.3, replaced by {@link #setCompileTimeDisable()} */
    @Deprecated
    public AssertionsConfiguration.Builder setDisable() {
      setTransformation(AssertionTransformation.DISABLE);
      return this;
    }

    /** Passthrough of the javac generated assertion code in all packages and classes. */
    public AssertionsConfiguration.Builder setPassthrough() {
      setTransformation(AssertionTransformation.PASSTHROUGH);
      return this;
    }

    /**
     * Rewrite the throwing of <code>java.lang.AssertionError</code> to call the supplied method
     * <code>assertionHandler</code>. The method must be a reference to a static method taking one
     * argument. The type of the argument should be <code>java.lang.Throwable</code> as kotlinc will
     * generate code where the assertion error is thrown as <code>java.lang.Throwable</code>. If all
     * code is generated by javac then the type of the argument can be <code>
     * java.lang.AssertionError</code>. After the assertion handler as been called, the code
     * continues as if assertions where disabled.
     */
    public AssertionsConfiguration.Builder setAssertionHandler(MethodReference assertionHandler) {
      this.transformation = null;
      this.assertionHandler = assertionHandler;
      return this;
    }

    public AssertionsConfiguration.Builder setScopeAll() {
      this.scope = AssertionTransformationScope.ALL;
      this.value = null;
      return this;
    }

    /**
     * Apply the specified transformation in package <code>packageName</code> and all subpackages.
     * If <code>packageName</code> is the empty string, this specifies that the transformation is
     * applied ion the unnamed package.
     *
     * <p>If the transformation is 'enable' this corresponds to passing <code>
     * -enableassertions:packageName...</code> or <code>-ea:packageName...</code> to the java CLI.
     *
     * <p>If the transformation is 'disable' this corresponds to passing <code>
     * -disableassertions:packageName...</code> or <code>-da:packageName...</code> to the java CLI.
     */
    public AssertionsConfiguration.Builder setScopePackage(String packageName) {
      this.scope = AssertionTransformationScope.PACKAGE;
      this.value = packageName;
      return this;
    }

    /**
     * Apply the specified transformation in class <code>className</code>.
     *
     * <p>If the transformation is 'enable' this corresponds to passing <code>
     * -enableassertions:className</code> or <code>-ea:className...</code> to the java CLI.
     *
     * <p>If the transformation is 'disable' this corresponds to passing <code>
     * -disableassertions:className</code> or <code>-da:className</code> to the java CLI.
     */
    public AssertionsConfiguration.Builder setScopeClass(String className) {
      this.scope = AssertionTransformationScope.CLASS;
      this.value = className;
      return this;
    }

    /** Build and return the {@link AssertionsConfiguration}. */
    public AssertionsConfiguration build() {
      if (transformation == null && assertionHandler == null) {
        reporter.error(
            "No transformation or assertion handler specified for building AssertionConfiguration");
      }
      if (scope == null) {
        reporter.error("No scope specified for building AssertionConfiguration");
      }
      if (scope == AssertionTransformationScope.PACKAGE && value == null) {
        reporter.error("No package name specified for building AssertionConfiguration");
      }
      if (scope == AssertionTransformationScope.CLASS && value == null) {
        reporter.error("No class name specified for building AssertionConfiguration");
      }
      return new AssertionsConfiguration(transformation, assertionHandler, scope, value);
    }

    /**
     * Static helper to build an <code>AssertionConfiguration</code> which unconditionally enables
     * javac generated assertion code in all packages and classes. To be used like this:
     *
     * <pre>
     *   D8Command command = D8Command.builder()
     *     .addAssertionsConfiguration(AssertionsConfiguration.Builder::enableAllAssertions)
     *     ...
     *     .build();
     * </pre>
     *
     * which is a shorthand for:
     *
     * <pre>
     *   D8Command command = D8Command.builder()
     *     .addAssertionsConfiguration(
     *         builder -> builder.setCompileTimeEnable().setScopeAll().build())
     *     ...
     *     .build();
     * </pre>
     */
    public static AssertionsConfiguration compileTimeEnableAllAssertions(
        AssertionsConfiguration.Builder builder) {
      return builder.setCompileTimeEnable().setScopeAll().build();
    }

    /**
     * @deprecated As of version 3.3, replaced by {@link #compileTimeEnableAllAssertions(Builder)}
     *     ()}
     */
    @Deprecated
    public static AssertionsConfiguration enableAllAssertions(
        AssertionsConfiguration.Builder builder) {
      return compileTimeEnableAllAssertions(builder);
    }

    /**
     * Static helper to build an <code>AssertionConfiguration</code> which unconditionally disables
     * javac generated assertion code in all packages and classes. To be used like this:
     *
     * <pre>
     *   D8Command command = D8Command.builder()
     *     .addAssertionsConfiguration(
     *         AssertionsConfiguration.Builder::compileTimeDisableAllAssertions)
     *     ...
     *     .build();
     * </pre>
     *
     * which is a shorthand for:
     *
     * <pre>
     *   D8Command command = D8Command.builder()
     *     .addAssertionsConfiguration(
     *         builder -> builder.setCompileTimeDisabled().setScopeAll().build())
     *     ...
     *     .build();
     * </pre>
     */
    public static AssertionsConfiguration compileTimeDisableAllAssertions(
        AssertionsConfiguration.Builder builder) {
      return builder.setCompileTimeDisable().setScopeAll().build();
    }

    /**
     * @deprecated As of version 3.3, replaced by {@link #compileTimeDisableAllAssertions(Builder)}
     *     ()}
     */
    @Deprecated
    public static AssertionsConfiguration disableAllAssertions(
        AssertionsConfiguration.Builder builder) {
      return compileTimeDisableAllAssertions(builder);
    }

    /**
     * Static helper to build an <code>AssertionConfiguration</code> which will passthrough javac
     * generated assertion code in all packages and classes. To be used like this:
     *
     * <pre>
     *   D8Command command = D8Command.builder()
     *     .addAssertionsConfiguration(AssertionsConfiguration.Builder::passthroughAllAssertions)
     *     ...
     *     .build();
     * </pre>
     *
     * which is a shorthand for:
     *
     * <pre>
     *   D8Command command = D8Command.builder()
     *     .addAssertionsConfiguration(builder -> builder.setPassthrough().setScopeAll().build())
     *     ...
     *     .build();
     * </pre>
     */
    public static AssertionsConfiguration passthroughAllAssertions(
        AssertionsConfiguration.Builder builder) {
      return builder.setPassthrough().setScopeAll().build();
    }
  }
}
