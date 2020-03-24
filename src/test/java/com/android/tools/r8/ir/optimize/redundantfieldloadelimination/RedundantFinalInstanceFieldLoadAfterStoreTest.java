package com.android.tools.r8.ir.optimize.redundantfieldloadelimination;

import static com.android.tools.r8.utils.codeinspector.Matchers.isPresent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.Assert.assertEquals;

import com.android.tools.r8.NeverClassInline;
import com.android.tools.r8.NeverInline;
import com.android.tools.r8.NeverPropagateValue;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.ir.optimize.redundantfieldloadelimination.RedundantFinalStaticFieldLoadAfterStoreTest.A;
import com.android.tools.r8.utils.codeinspector.ClassSubject;
import com.android.tools.r8.utils.codeinspector.CodeInspector;
import com.android.tools.r8.utils.codeinspector.FieldSubject;
import com.android.tools.r8.utils.codeinspector.FoundFieldSubject;
import com.android.tools.r8.utils.codeinspector.FoundMethodSubject;
import com.android.tools.r8.utils.codeinspector.InstructionSubject;
import com.android.tools.r8.utils.codeinspector.MethodSubject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class RedundantFinalInstanceFieldLoadAfterStoreTest extends TestBase {

  private final TestParameters parameters;

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withAllRuntimesAndApiLevels().build();
  }

  public RedundantFinalInstanceFieldLoadAfterStoreTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void test() throws Exception {
    testForR8(parameters.getBackend())
        .addInnerClasses(RedundantFinalInstanceFieldLoadAfterStoreTest.class)
        .addKeepMainRule(TestClass.class)
        .enableInliningAnnotations()
        .enableMemberValuePropagationAnnotations()
        .enableNeverClassInliningAnnotations()
        .setMinApi(parameters.getApiLevel())
        .compile()
        .inspect(this::inspect)
        .run(parameters.getRuntime(), TestClass.class)
        .assertSuccessWithOutputLines("0", "42", "42", "42");
  }

  private void inspect(CodeInspector inspector) {
    ClassSubject aClassSubject = inspector.clazz(A.class);
    assertThat(aClassSubject, isPresent());

    FieldSubject fFieldSubject = aClassSubject.uniqueFieldWithName("f");
    assertThat(fFieldSubject, isPresent());

    MethodSubject initMethodSubject = aClassSubject.init();
    assertThat(initMethodSubject, isPresent());
    // TODO(b/152196923): Should be 0.
    assertEquals(
        2,
        countInstanceGetInstructions(
            initMethodSubject.asFoundMethodSubject(), fFieldSubject.asFoundFieldSubject()));

    MethodSubject mMethodSubject = aClassSubject.uniqueMethodWithName("m");
    assertThat(mMethodSubject, isPresent());
    assertEquals(
        2,
        countInstanceGetInstructions(
            mMethodSubject.asFoundMethodSubject(), fFieldSubject.asFoundFieldSubject()));
  }

  private long countInstanceGetInstructions(
      FoundMethodSubject methodSubject, FoundFieldSubject fieldSubject) {
    return methodSubject
        .streamInstructions()
        .filter(InstructionSubject::isInstanceGet)
        .map(InstructionSubject::getField)
        .filter(fieldSubject.getField().field::equals)
        .count();
  }

  static class TestClass {

    public static void main(String[] args) {
      new A();
    }
  }

  @NeverClassInline
  static class A {

    @NeverPropagateValue final long f;

    static volatile boolean read;
    static volatile boolean initialized;

    A() {
      fork();
      waitUntilRead();
      f = System.currentTimeMillis() > 0 ? 42 : 0;
      initialized = true;
      killNonFinalActiveFields();
      System.out.println(f); // Redundant, since `f` is final and guaranteed to be initialized.
      killNonFinalActiveFields();
      System.out.println(f); // Redundant, since `f` is final and guaranteed to be initialized.
    }

    @NeverInline
    void m() {
      System.out.println(f);
      read = true;
      waitUntilInitialized();
      System.out.println(f); // Not redundant, since `f` is not guaranteed to be initialized.
    }

    void fork() {
      new Thread(this::m).start();
    }

    void killNonFinalActiveFields() {
      if (System.currentTimeMillis() < 0) {
        System.out.println(this);
      }
    }

    void waitUntilInitialized() {
      while (!initialized) {
        Thread.yield();
      }
    }

    void waitUntilRead() {
      while (!read) {
        Thread.yield();
      }
    }
  }
}
