// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import static com.android.tools.r8.utils.ConsumerUtils.emptyConsumer;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.LookupResult.LookupResultSuccess;
import com.android.tools.r8.graph.LookupResult.LookupResultSuccess.LookupResultCollectionState;
import com.android.tools.r8.ir.desugar.LambdaDescriptor;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.shaking.InstantiatedObject;
import com.android.tools.r8.utils.BooleanBox;
import com.android.tools.r8.utils.Box;
import com.android.tools.r8.utils.ConsumerUtils;
import com.android.tools.r8.utils.OptionalBool;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

public abstract class MethodResolutionResult
    extends MemberResolutionResult<DexEncodedMethod, DexMethod> {

  @Override
  public boolean isMethodResolutionResult() {
    return true;
  }

  @Override
  public MethodResolutionResult asMethodResolutionResult() {
    return this;
  }

  /**
   * Returns true if resolution succeeded *and* the resolved method has a known definition.
   *
   * <p>Note that {@code !isSingleResolution() && !isFailedResolution()} can be true. In that case
   * that resolution has succeeded, but the definition of the resolved method is unknown. In
   * particular this is the case for the clone() method on arrays.
   */
  public boolean isSingleResolution() {
    return false;
  }

  /** Returns non-null if isSingleResolution() is true, otherwise null. */
  public SingleResolutionResult<?> asSingleResolution() {
    return null;
  }

  @Override
  public boolean isSuccessfulMemberResolutionResult() {
    return false;
  }

  @Override
  public SuccessfulMemberResolutionResult<DexEncodedMethod, DexMethod>
      asSuccessfulMemberResolutionResult() {
    return null;
  }

  public boolean isIncompatibleClassChangeErrorResult() {
    return false;
  }

  public boolean isNoSuchMethodErrorResult(DexClass context, AppInfoWithClassHierarchy appInfo) {
    return false;
  }

  public boolean isIllegalAccessErrorResult(DexClass context, AppInfoWithClassHierarchy appInfo) {
    return false;
  }

  public boolean isClassNotFoundResult() {
    return false;
  }

  public boolean isArrayCloneMethodResult() {
    return false;
  }

  /** Returns non-null if isFailedResolution() is true, otherwise null. */
  public FailedResolutionResult asFailedResolution() {
    return null;
  }

  public DexClass getResolvedHolder() {
    return null;
  }

  public DexEncodedMethod getResolvedMethod() {
    return null;
  }

  /** Short-hand to get the single resolution method if resolution finds it, null otherwise. */
  public final DexEncodedMethod getSingleTarget() {
    return isSingleResolution() ? asSingleResolution().getResolvedMethod() : null;
  }

  public DexClass getInitialResolutionHolder() {
    return null;
  }

  public ProgramMethod getResolvedProgramMethod() {
    return null;
  }

  @Override
  public DexClassAndMethod getResolutionPair() {
    return null;
  }

  public abstract OptionalBool isAccessibleForVirtualDispatchFrom(
      ProgramDefinition context, AppInfoWithClassHierarchy appInfo);

  public abstract boolean isVirtualTarget();

  /** Lookup the single target of an invoke-special on this resolution result if possible. */
  public abstract DexClassAndMethod lookupInvokeSpecialTarget(
      DexProgramClass context, AppInfoWithClassHierarchy appInfo);

  /** Lookup the single target of an invoke-super on this resolution result if possible. */
  public abstract DexClassAndMethod lookupInvokeSuperTarget(
      DexProgramClass context, AppInfoWithClassHierarchy appInfo);

  /** Lookup the single target of an invoke-direct on this resolution result if possible. */
  public abstract DexEncodedMethod lookupInvokeDirectTarget(
      DexProgramClass context, AppInfoWithClassHierarchy appInfo);

  /** Lookup the single target of an invoke-static on this resolution result if possible. */
  public abstract DexEncodedMethod lookupInvokeStaticTarget(
      DexProgramClass context, AppInfoWithClassHierarchy appInfo);

  public abstract LookupResult lookupVirtualDispatchTargets(
      DexProgramClass context,
      AppInfoWithClassHierarchy appInfo,
      InstantiatedSubTypeInfo instantiatedInfo,
      PinnedPredicate pinnedPredicate);

  public final LookupResult lookupVirtualDispatchTargets(
      DexProgramClass context, AppInfoWithLiveness appInfo) {
    return lookupVirtualDispatchTargets(
        context, appInfo, appInfo, appInfo::isPinnedNotProgramOrLibraryOverride);
  }

  public abstract LookupResult lookupVirtualDispatchTargets(
      DexProgramClass context,
      AppInfoWithLiveness appInfo,
      DexProgramClass refinedReceiverUpperBound,
      DexProgramClass refinedReceiverLowerBound);

  public abstract LookupTarget lookupVirtualDispatchTarget(
      InstantiatedObject instance, AppInfoWithClassHierarchy appInfo);

  public abstract LookupMethodTarget lookupVirtualDispatchTarget(
      DexClass dynamicInstance, AppInfoWithClassHierarchy appInfo);

  public abstract LookupTarget lookupVirtualDispatchTarget(
      LambdaDescriptor lambdaInstance,
      AppInfoWithClassHierarchy appInfo,
      Consumer<? super DexEncodedMethod> methodCausingFailureConsumer);

  public abstract void visitMethodResolutionResults(
      Consumer<? super SingleResolutionResult<?>> programOrClasspathConsumer,
      Consumer<? super SingleLibraryResolutionResult> libraryResultConsumer,
      Consumer<? super ArrayCloneMethodResult> cloneResultConsumer,
      Consumer<? super FailedResolutionResult> failedResolutionConsumer);

  public boolean hasProgramResult() {
    return false;
  }

  public SingleClasspathResolutionResult asSingleClasspathResolutionResult() {
    return null;
  }

  protected SingleProgramResolutionResult asSingleProgramResolutionResult() {
    return null;
  }

  public static SingleResolutionResult<?> createSingleResolutionResult(
      DexClass initialResolutionHolder, DexClass holder, DexEncodedMethod definition) {
    if (holder.isLibraryClass()) {
      return new SingleLibraryResolutionResult(
          initialResolutionHolder, holder.asLibraryClass(), definition);
    } else if (holder.isClasspathClass()) {
      return new SingleClasspathResolutionResult(
          initialResolutionHolder, holder.asClasspathClass(), definition);
    } else {
      assert holder.isProgramClass();
      return new SingleProgramResolutionResult(
          initialResolutionHolder, holder.asProgramClass(), definition);
    }
  }

  /** Result for a resolution that succeeds with a known declaration/definition. */
  public abstract static class SingleResolutionResult<T extends DexClass>
      extends MethodResolutionResult
      implements SuccessfulMemberResolutionResult<DexEncodedMethod, DexMethod> {
    private final DexClass initialResolutionHolder;
    private final T resolvedHolder;
    private final DexEncodedMethod resolvedMethod;

    public SingleResolutionResult(
        DexClass initialResolutionHolder, T resolvedHolder, DexEncodedMethod resolvedMethod) {
      assert initialResolutionHolder != null;
      assert resolvedHolder != null;
      assert resolvedMethod != null;
      assert resolvedHolder.type == resolvedMethod.getHolderType();
      this.resolvedHolder = resolvedHolder;
      this.resolvedMethod = resolvedMethod;
      this.initialResolutionHolder = initialResolutionHolder;
      assert !resolvedMethod.isPrivateMethod()
          || initialResolutionHolder.type == resolvedMethod.getHolderType();
    }

    public abstract SingleResolutionResult<T> withInitialResolutionHolder(
        DexClass newInitialResolutionHolder);

    @Override
    public DexClass getInitialResolutionHolder() {
      return initialResolutionHolder;
    }

    @Override
    public T getResolvedHolder() {
      return resolvedHolder;
    }

    @Override
    public DexEncodedMethod getResolvedMember() {
      return resolvedMethod;
    }

    @Override
    public DexEncodedMethod getResolvedMethod() {
      return resolvedMethod;
    }

    @Override
    public ProgramMethod getResolvedProgramMethod() {
      return resolvedHolder.isProgramClass()
          ? new ProgramMethod(resolvedHolder.asProgramClass(), resolvedMethod)
          : null;
    }

    @Override
    public DexClassAndMethod getResolutionPair() {
      return DexClassAndMethod.create(resolvedHolder, resolvedMethod);
    }

    @Override
    public boolean isSingleResolution() {
      return true;
    }

    @Override
    public SingleResolutionResult<?> asSingleResolution() {
      return this;
    }

    @Override
    public boolean isSuccessfulMemberResolutionResult() {
      return true;
    }

    @Override
    public SuccessfulMemberResolutionResult<DexEncodedMethod, DexMethod>
        asSuccessfulMemberResolutionResult() {
      return this;
    }

    @Override
    public OptionalBool isAccessibleFrom(
        ProgramDefinition context, AppInfoWithClassHierarchy appInfo) {
      return AccessControl.isMemberAccessible(this, context, appInfo);
    }

    @Override
    public OptionalBool isAccessibleForVirtualDispatchFrom(
        ProgramDefinition context, AppInfoWithClassHierarchy appInfo) {
      if (resolvedMethod.isVirtualMethod()) {
        return isAccessibleFrom(context, appInfo);
      }
      return OptionalBool.FALSE;
    }

    @Override
    public boolean isVirtualTarget() {
      return resolvedMethod.isVirtualMethod();
    }

    /**
     * This is intended to model the actual behavior of invoke-special on a JVM.
     *
     * <p>See https://docs.oracle.com/javase/specs/jvms/se11/html/jvms-6.html#jvms-6.5.invokespecial
     * and comments below for deviations due to diverging behavior on actual JVMs.
     */
    @Override
    public DexClassAndMethod lookupInvokeSpecialTarget(
        DexProgramClass context, AppInfoWithClassHierarchy appInfo) {
      // If the resolution is non-accessible then no target exists.
      if (isAccessibleFrom(context, appInfo).isPossiblyTrue()) {
        return internalInvokeSpecialOrSuper(
            context, appInfo, (sup, sub) -> isSuperclass(sup, sub, appInfo));
      }
      return null;
    }

    /**
     * Lookup the target of an invoke-super.
     *
     * <p>This will return the target iff the resolution succeeded and the target is valid (i.e.,
     * non-static and non-initializer) and accessible from {@code context}.
     *
     * <p>Additionally, this will also verify that the invoke-super is valid, i.e., it is on the a
     * super type of the current context. Any invoke-special targeting the same type should have
     * been mapped to an invoke-direct, but could change due to merging so we need to still allow
     * the context to be equal to the targeted (symbolically referenced) type.
     *
     * @param context Class the invoke is contained in, i.e., the holder of the caller.
     * @param appInfo Application info.
     * @return The actual target for the invoke-super or {@code null} if no valid target is found.
     */
    @Override
    public DexClassAndMethod lookupInvokeSuperTarget(
        DexProgramClass context, AppInfoWithClassHierarchy appInfo) {
      if (resolvedMethod.isInstanceInitializer()
          || (initialResolutionHolder != context
              && !isSuperclass(initialResolutionHolder, context, appInfo))) {
        // If the target is <init> or not on a super class then the call is invalid.
        return null;
      }
      if (isAccessibleFrom(context, appInfo).isPossiblyTrue()) {
        return internalInvokeSpecialOrSuper(context, appInfo, (sup, sub) -> true);
      }
      return null;
    }

    /**
     * Lookup the target of an invoke-static.
     *
     * <p>This method will resolve the method on the holder and only return a non-null value if the
     * result of resolution was a static, non-abstract method.
     *
     * @param context Class the invoke is contained in, i.e., the holder of the caller.
     * @param appInfo Application info.
     * @return The actual target or {@code null} if none found.
     */
    @Override
    public DexEncodedMethod lookupInvokeStaticTarget(
        DexProgramClass context, AppInfoWithClassHierarchy appInfo) {
      if (isAccessibleFrom(context, appInfo).isFalse()) {
        return null;
      }
      if (resolvedMethod.isStatic()) {
        return resolvedMethod;
      }
      return null;
    }

    /**
     * Lookup direct method following the super chain from the holder of {@code method}.
     *
     * <p>This method will lookup private and constructor methods.
     *
     * @param context Class the invoke is contained in, i.e., the holder of the caller. * @param
     *     appInfo Application info.
     * @return The actual target or {@code null} if none found.
     */
    @Override
    public DexEncodedMethod lookupInvokeDirectTarget(
        DexProgramClass context, AppInfoWithClassHierarchy appInfo) {
      if (isAccessibleFrom(context, appInfo).isFalse()) {
        return null;
      }
      if (resolvedMethod.isDirectMethod()) {
        return resolvedMethod;
      }
      return null;
    }

    private DexClassAndMethod internalInvokeSpecialOrSuper(
        DexProgramClass context,
        AppInfoWithClassHierarchy appInfo,
        BiPredicate<DexClass, DexClass> isSuperclass) {

      // Statics cannot be targeted by invoke-special/super.
      if (getResolvedMethod().isStatic()) {
        return null;
      }

      if (getResolvedHolder().isInterface() && getResolvedMethod().isPrivate()) {
        return getResolutionPair();
      }

      // The symbolic reference is the holder type that resolution was initiated at.
      DexClass symbolicReference = initialResolutionHolder;

      // First part of the spec is to determine the starting point for lookup for invoke special.
      // Notice that the specification indicates that the immediate super type should
      // be used when three items hold, the second being:
      //   is-class(sym-ref) => is-super(sym-ref, context)
      // in the case of an interface that is trivially satisfied, which would lead the initial type
      // to be java.lang.Object. However in practice the lookup appears to start at the symbolic
      // reference in the case of interfaces, so the second condition should likely be interpreted:
      //   is-class(sym-ref) *and* is-super(sym-ref, context).
      final DexClass initialType;
      if (!resolvedMethod.isInstanceInitializer()
          && !symbolicReference.isInterface()
          && isSuperclass.test(symbolicReference, context)) {
        // If reference is a super type of the context then search starts at the immediate super.
        initialType = context.superType == null ? null : appInfo.definitionFor(context.superType);
      } else {
        // Otherwise it starts at the reference itself.
        initialType = symbolicReference;
      }
      // Abort if for some reason the starting point could not be found.
      if (initialType == null) {
        return null;
      }
      // 1-3. Search the initial class and its supers in order for a matching instance method.
      DexMethod method = getResolvedMethod().getReference();
      DexClassAndMethod target = null;
      DexClass current = initialType;
      while (current != null) {
        target = current.lookupClassMethod(method);
        if (target != null) {
          break;
        }
        current = current.superType == null ? null : appInfo.definitionFor(current.superType);
      }
      // 4. Otherwise, it is the single maximally specific method:
      if (target == null) {
        target = appInfo.lookupMaximallySpecificMethod(initialType, method);
      }
      if (target == null) {
        return null;
      }
      // Linking exceptions:
      // A non-instance method throws IncompatibleClassChangeError.
      if (target.getAccessFlags().isStatic()) {
        return null;
      }
      // An instance initializer that is not to the symbolic reference throws NoSuchMethodError.
      // It appears as if this check is also in place for non-initializer methods too.
      // See NestInvokeSpecialMethodAccessWithIntermediateTest.
      if ((target.getDefinition().isInstanceInitializer() || target.getAccessFlags().isPrivate())
          && target.getHolderType() != symbolicReference.type) {
        return null;
      }
      // Runtime exceptions:
      // An abstract method throws AbstractMethodError.
      if (target.getAccessFlags().isAbstract()) {
        return null;
      }
      return target;
    }

    private static boolean isSuperclass(
        DexClass sup, DexClass sub, AppInfoWithClassHierarchy appInfo) {
      return appInfo.isStrictSubtypeOf(sub.type, sup.type);
    }

    @Override
    public LookupResult lookupVirtualDispatchTargets(
        DexProgramClass context,
        AppInfoWithClassHierarchy appInfo,
        InstantiatedSubTypeInfo instantiatedInfo,
        PinnedPredicate pinnedPredicate) {
      // Check that the initial resolution holder is accessible from the context.
      assert appInfo.isSubtype(initialResolutionHolder.type, resolvedHolder.type)
          : initialResolutionHolder.type + " is not a subtype of " + resolvedHolder.type;
      if (context != null && isAccessibleFrom(context, appInfo).isFalse()) {
        return LookupResult.createFailedResult();
      }
      if (resolvedMethod.isPrivateMethod()) {
        // If the resolved reference is private there is no dispatch.
        // This is assuming that the method is accessible, which implies self/nest access.
        // Only include if the target has code or is native.
        boolean isIncomplete =
            pinnedPredicate.isPinned(resolvedHolder) && pinnedPredicate.isPinned(resolvedMethod);
        DexClassAndMethod resolutionPair = getResolutionPair();
        return LookupResult.createResult(
            Collections.singletonMap(resolutionPair.getReference(), resolutionPair),
            Collections.emptyList(),
            Collections.emptyList(),
            isIncomplete
                ? LookupResultCollectionState.Incomplete
                : LookupResultCollectionState.Complete);
      }
      assert resolvedMethod.isNonPrivateVirtualMethod();
      LookupResultSuccess.Builder resultBuilder = LookupResultSuccess.builder();
      LookupCompletenessHelper incompleteness = new LookupCompletenessHelper(pinnedPredicate);
      instantiatedInfo.forEachInstantiatedSubType(
          initialResolutionHolder.type,
          subClass -> {
            incompleteness.checkClass(subClass);
            LookupMethodTarget lookupTarget =
                lookupVirtualDispatchTarget(
                    subClass, appInfo, resolvedHolder.type, resultBuilder::addMethodCausingFailure);
            if (lookupTarget != null) {
              incompleteness.checkDexClassAndMethod(lookupTarget);
              addVirtualDispatchTarget(lookupTarget, resolvedHolder.isInterface(), resultBuilder);
            }
          },
          lambda -> {
            assert resolvedHolder.isInterface()
                || resolvedHolder.type == appInfo.dexItemFactory().objectType;
            LookupTarget target =
                lookupVirtualDispatchTarget(
                    lambda, appInfo, resultBuilder::addMethodCausingFailure);
            if (target != null) {
              if (target.isLambdaTarget()) {
                resultBuilder.addLambdaTarget(target.asLambdaTarget());
              } else {
                addVirtualDispatchTarget(
                    target.asMethodTarget(), resolvedHolder.isInterface(), resultBuilder);
              }
            }
          });
      return resultBuilder
          .setState(incompleteness.computeCollectionState(resolvedMethod.getReference(), appInfo))
          .build();
    }

    @Override
    public LookupResult lookupVirtualDispatchTargets(
        DexProgramClass context,
        AppInfoWithLiveness appInfo,
        DexProgramClass refinedReceiverUpperBound,
        DexProgramClass refinedReceiverLowerBound) {
      assert refinedReceiverUpperBound != null;
      assert appInfo.isSubtype(refinedReceiverUpperBound.type, initialResolutionHolder.type);
      assert refinedReceiverLowerBound == null
          || appInfo.isSubtype(refinedReceiverLowerBound.type, refinedReceiverUpperBound.type);
      // TODO(b/148769279): Remove the check for hasInstantiatedLambdas.
      Box<Boolean> hasInstantiatedLambdas = new Box<>(false);
      InstantiatedSubTypeInfo instantiatedSubTypeInfo =
          instantiatedSubTypeInfoForInstantiatedType(
              appInfo,
              refinedReceiverUpperBound,
              refinedReceiverLowerBound,
              hasInstantiatedLambdas);
      LookupResult lookupResult =
          lookupVirtualDispatchTargets(
              context,
              appInfo,
              instantiatedSubTypeInfo,
              appInfo::isPinnedNotProgramOrLibraryOverride);
      if (hasInstantiatedLambdas.get() && lookupResult.isLookupResultSuccess()) {
        lookupResult.asLookupResultSuccess().setIncomplete();
      }
      return lookupResult;
    }

    private InstantiatedSubTypeInfo instantiatedSubTypeInfoForInstantiatedType(
        AppInfoWithLiveness appInfo,
        DexProgramClass refinedReceiverUpperBound,
        DexProgramClass refinedReceiverLowerBound,
        Box<Boolean> hasInstantiatedLambdas) {
      return (ignored, subTypeConsumer, callSiteConsumer) -> {
        Consumer<DexProgramClass> lambdaInstantiatedConsumer =
            subType -> {
              subTypeConsumer.accept(subType);
              if (appInfo.isInstantiatedInterface(subType)) {
                hasInstantiatedLambdas.set(true);
              }
            };
        if (refinedReceiverLowerBound == null) {
          appInfo.forEachInstantiatedSubType(
              refinedReceiverUpperBound.type, lambdaInstantiatedConsumer, callSiteConsumer);
        } else {
          appInfo.forEachInstantiatedSubTypeInChain(
              refinedReceiverUpperBound,
              refinedReceiverLowerBound,
              lambdaInstantiatedConsumer,
              callSiteConsumer);
        }
      };
    }

    private static void addVirtualDispatchTarget(
        LookupMethodTarget target,
        boolean holderIsInterface,
        LookupResultSuccess.Builder resultBuilder) {
      assert target.isMethodTarget();
      DexEncodedMethod targetMethod = target.asMethodTarget().getDefinition();
      assert !targetMethod.isPrivateMethod();
      if (holderIsInterface) {
        // Add default interface methods to the list of targets.
        //
        // This helps to make sure we take into account synthesized lambda classes
        // that we are not aware of. Like in the following example, we know that all
        // classes, XX in this case, override B::bar(), but there are also synthesized
        // classes for lambda which don't, so we still need default method to be live.
        //
        //   public static void main(String[] args) {
        //     X x = () -> {};
        //     x.bar();
        //   }
        //
        //   interface X {
        //     void foo();
        //     default void bar() { }
        //   }
        //
        //   class XX implements X {
        //     public void foo() { }
        //     public void bar() { }
        //   }
        //
        if (targetMethod.isDefaultMethod()) {
          resultBuilder.addMethodTarget(target);
        }
        // Default methods are looked up when looking at a specific subtype that does not override
        // them. Otherwise, we would look up default methods that are actually never used.
        // However, we have to add bridge methods, otherwise we can remove a bridge that will be
        // used.
        if (!targetMethod.accessFlags.isAbstract() && targetMethod.accessFlags.isBridge()) {
          resultBuilder.addMethodTarget(target);
        }
      } else {
        resultBuilder.addMethodTarget(target);
      }
    }

    /**
     * This implements the logic for the actual method selection for a virtual target, according to
     * https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-6.html#jvms-6.5.invokevirtual where
     * we have an object ref on the stack.
     */
    @Override
    public LookupTarget lookupVirtualDispatchTarget(
        InstantiatedObject instance, AppInfoWithClassHierarchy appInfo) {
      return instance.isClass()
          ? lookupVirtualDispatchTarget(instance.asClass(), appInfo)
          : lookupVirtualDispatchTarget(instance.asLambda(), appInfo, emptyConsumer());
    }

    @Override
    public LookupMethodTarget lookupVirtualDispatchTarget(
        DexClass dynamicInstance, AppInfoWithClassHierarchy appInfo) {
      return lookupVirtualDispatchTarget(
          dynamicInstance, appInfo, initialResolutionHolder.type, emptyConsumer());
    }

    @Override
    public LookupTarget lookupVirtualDispatchTarget(
        LambdaDescriptor lambdaInstance,
        AppInfoWithClassHierarchy appInfo,
        Consumer<? super DexEncodedMethod> methodCausingFailureConsumer) {
      if (lambdaInstance.getMainMethod().match(resolvedMethod)) {
        DexMethod methodReference = lambdaInstance.implHandle.asMethod();
        DexClass holder = appInfo.definitionForHolder(methodReference);
        DexClassAndMethod method = methodReference.lookupMemberOnClass(holder);
        if (method == null) {
          // The targeted method might not exist, eg, Throwable.addSuppressed in an old library.
          return null;
        }
        return new LookupLambdaTarget(lambdaInstance, method);
      }
      return lookupMaximallySpecificDispatchTarget(
          lambdaInstance, appInfo, methodCausingFailureConsumer);
    }

    private LookupMethodTarget lookupVirtualDispatchTarget(
        DexClass dynamicInstance,
        AppInfoWithClassHierarchy appInfo,
        DexType resolutionHolder,
        Consumer<? super DexEncodedMethod> methodCausingFailureConsumer) {
      assert appInfo.isSubtype(dynamicInstance.type, resolutionHolder)
          : dynamicInstance.type + " is not a subtype of " + resolutionHolder;
      // TODO(b/148591377): Enable this assertion.
      // The dynamic type cannot be an interface.
      // assert !dynamicInstance.isInterface();
      DexClassAndMethod initialResolutionPair = getResolutionPair();
      if (resolvedMethod.isPrivateMethod()) {
        // If the resolved reference is private there is no dispatch.
        // This is assuming that the method is accessible, which implies self/nest access.
        return initialResolutionPair;
      }
      boolean allowPackageBlocked = resolvedMethod.accessFlags.isPackagePrivate();
      DexClass current = dynamicInstance;
      DexClassAndMethod overrideTarget = initialResolutionPair;
      while (current != null) {
        DexEncodedMethod candidate =
            lookupOverrideCandidate(overrideTarget.getDefinition(), current);
        if (candidate == DexEncodedMethod.SENTINEL && allowPackageBlocked) {
          overrideTarget = findWideningOverride(initialResolutionPair, current, appInfo);
          allowPackageBlocked = false;
          continue;
        }
        if (candidate == null || candidate == DexEncodedMethod.SENTINEL) {
          // We cannot find a target above the resolved method.
          if (current.type == overrideTarget.getHolderType()) {
            return null;
          }
          current = current.superType == null ? null : appInfo.definitionFor(current.superType);
          continue;
        }
        DexClassAndMethod target = DexClassAndMethod.create(current, candidate);
        return overrideTarget != initialResolutionPair
            ? new LookupMethodTargetWithAccessOverride(target, overrideTarget)
            : target;
      }
      // If we have not found a candidate and the holder is not an interface it must be because the
      // class is missing.
      if (!resolvedHolder.isInterface()) {
        return null;
      }
      return lookupMaximallySpecificDispatchTarget(
          dynamicInstance, appInfo, methodCausingFailureConsumer);
    }

    private DexClassAndMethod lookupMaximallySpecificDispatchTarget(
        DexClass dynamicInstance,
        AppInfoWithClassHierarchy appInfo,
        Consumer<? super DexEncodedMethod> methodCausingFailureConsumer) {
      MethodResolutionResult maximallySpecificResolutionResult =
          appInfo.resolveMaximallySpecificTarget(dynamicInstance, resolvedMethod.getReference());
      if (maximallySpecificResolutionResult.isSingleResolution()) {
        return maximallySpecificResolutionResult.getResolutionPair();
      }
      if (maximallySpecificResolutionResult.isFailedResolution()) {
        maximallySpecificResolutionResult
            .asFailedResolution()
            .forEachFailureDependency(methodCausingFailureConsumer);
        return null;
      }
      assert maximallySpecificResolutionResult.isArrayCloneMethodResult();
      return null;
    }

    private DexClassAndMethod lookupMaximallySpecificDispatchTarget(
        LambdaDescriptor lambdaDescriptor,
        AppInfoWithClassHierarchy appInfo,
        Consumer<? super DexEncodedMethod> methodCausingFailureConsumer) {
      MethodResolutionResult maximallySpecificResolutionResult =
          appInfo.resolveMaximallySpecificTarget(lambdaDescriptor, resolvedMethod.getReference());
      if (maximallySpecificResolutionResult.isSingleResolution()) {
        return maximallySpecificResolutionResult.getResolutionPair();
      }
      if (maximallySpecificResolutionResult.isFailedResolution()) {
        maximallySpecificResolutionResult
            .asFailedResolution()
            .forEachFailureDependency(methodCausingFailureConsumer);
        return null;
      }
      assert maximallySpecificResolutionResult.isArrayCloneMethodResult();
      return null;
    }

    /**
     * C contains a declaration for an instance method m that overrides (§5.4.5) the resolved
     * method, then m is the method to be invoked. If the candidate is not a valid override, we
     * return sentinel to indicate that we have to search for a method that is widening access
     * inside the package.
     */
    private static DexEncodedMethod lookupOverrideCandidate(
        DexEncodedMethod method, DexClass clazz) {
      DexEncodedMethod candidate = clazz.lookupVirtualMethod(method.getReference());
      assert candidate == null || !candidate.isPrivateMethod();
      if (candidate != null) {
        return isOverriding(method, candidate) ? candidate : DexEncodedMethod.SENTINEL;
      }
      return null;
    }

    private static DexClassAndMethod findWideningOverride(
        DexClassAndMethod resolvedMethod, DexClass clazz, AppInfoWithClassHierarchy appView) {
      // Otherwise, lookup to first override that is distinct from resolvedMethod.
      assert resolvedMethod.getDefinition().accessFlags.isPackagePrivate();
      while (clazz.superType != null) {
        clazz = appView.definitionFor(clazz.superType);
        if (clazz == null) {
          return resolvedMethod;
        }
        DexEncodedMethod otherOverride = clazz.lookupVirtualMethod(resolvedMethod.getReference());
        if (otherOverride != null
            && isOverriding(resolvedMethod.getDefinition(), otherOverride)
            && (otherOverride.accessFlags.isPublic() || otherOverride.accessFlags.isProtected())) {
          assert resolvedMethod.getDefinition() != otherOverride;
          return DexClassAndMethod.create(clazz, otherOverride);
        }
      }
      return resolvedMethod;
    }

    /**
     * Implementation of method overriding according to the jvm specification
     * https://docs.oracle.com/javase/specs/jvms/se8/html/jvms-5.html#jvms-5.4.5
     *
     * <p>The implementation assumes that the holder of the candidate is a subtype of the holder of
     * the resolved method. It also assumes that resolvedMethod is the actual method to find a
     * lookup for (that is, it is either mA or m').
     */
    public static boolean isOverriding(
        DexEncodedMethod resolvedMethod, DexEncodedMethod candidate) {
      assert resolvedMethod.getReference().match(candidate.getReference());
      assert !candidate.isPrivateMethod();
      if (resolvedMethod.accessFlags.isPublic() || resolvedMethod.accessFlags.isProtected()) {
        return true;
      }
      // For package private methods, a valid override has to be inside the package.
      assert resolvedMethod.accessFlags.isPackagePrivate();
      return resolvedMethod.getHolderType().isSamePackage(candidate.getHolderType());
    }
  }

  public static class SingleProgramResolutionResult
      extends SingleResolutionResult<DexProgramClass> {

    public SingleProgramResolutionResult(
        DexClass initialResolutionHolder,
        DexProgramClass resolvedHolder,
        DexEncodedMethod resolvedMethod) {
      super(initialResolutionHolder, resolvedHolder, resolvedMethod);
    }

    @Override
    public SingleResolutionResult<DexProgramClass> withInitialResolutionHolder(
        DexClass newInitialResolutionHolder) {
      return newInitialResolutionHolder != getInitialResolutionHolder()
          ? new SingleProgramResolutionResult(
              newInitialResolutionHolder, getResolvedHolder(), getResolvedMethod())
          : this;
    }

    @Override
    public void visitMethodResolutionResults(
        Consumer<? super SingleResolutionResult<?>> programOrClasspathConsumer,
        Consumer<? super SingleLibraryResolutionResult> libraryResultConsumer,
        Consumer<? super ArrayCloneMethodResult> cloneResultConsumer,
        Consumer<? super FailedResolutionResult> failedResolutionConsumer) {
      programOrClasspathConsumer.accept(this);
    }

    @Override
    public boolean hasProgramResult() {
      return true;
    }

    @Override
    protected SingleProgramResolutionResult asSingleProgramResolutionResult() {
      return this;
    }
  }

  public static class SingleClasspathResolutionResult
      extends SingleResolutionResult<DexClasspathClass> {

    public SingleClasspathResolutionResult(
        DexClass initialResolutionHolder,
        DexClasspathClass resolvedHolder,
        DexEncodedMethod resolvedMethod) {
      super(initialResolutionHolder, resolvedHolder, resolvedMethod);
    }

    @Override
    public SingleClasspathResolutionResult withInitialResolutionHolder(
        DexClass newInitialResolutionHolder) {
      return newInitialResolutionHolder != getInitialResolutionHolder()
          ? new SingleClasspathResolutionResult(
              newInitialResolutionHolder, getResolvedHolder(), getResolvedMethod())
          : this;
    }

    @Override
    public void visitMethodResolutionResults(
        Consumer<? super SingleResolutionResult<?>> programOrClasspathConsumer,
        Consumer<? super SingleLibraryResolutionResult> libraryResultConsumer,
        Consumer<? super ArrayCloneMethodResult> cloneResultConsumer,
        Consumer<? super FailedResolutionResult> failedResolutionConsumer) {
      programOrClasspathConsumer.accept(this);
    }

    @Override
    public SingleClasspathResolutionResult asSingleClasspathResolutionResult() {
      return this;
    }
  }

  public static class SingleLibraryResolutionResult
      extends SingleResolutionResult<DexLibraryClass> {

    public SingleLibraryResolutionResult(
        DexClass initialResolutionHolder,
        DexLibraryClass resolvedHolder,
        DexEncodedMethod resolvedMethod) {
      super(initialResolutionHolder, resolvedHolder, resolvedMethod);
    }

    @Override
    public SingleLibraryResolutionResult withInitialResolutionHolder(
        DexClass newInitialResolutionHolder) {
      return newInitialResolutionHolder != getInitialResolutionHolder()
          ? new SingleLibraryResolutionResult(
              newInitialResolutionHolder, getResolvedHolder(), getResolvedMethod())
          : this;
    }

    @Override
    public void visitMethodResolutionResults(
        Consumer<? super SingleResolutionResult<?>> programOrClasspathConsumer,
        Consumer<? super SingleLibraryResolutionResult> libraryResultConsumer,
        Consumer<? super ArrayCloneMethodResult> cloneResultConsumer,
        Consumer<? super FailedResolutionResult> failedResolutionConsumer) {
      libraryResultConsumer.accept(this);
    }
  }

  abstract static class EmptyResult extends MethodResolutionResult {

    @Override
    public final DexClassAndMethod lookupInvokeSpecialTarget(
        DexProgramClass context, AppInfoWithClassHierarchy appInfo) {
      return null;
    }

    @Override
    public DexClassAndMethod lookupInvokeSuperTarget(
        DexProgramClass context, AppInfoWithClassHierarchy appInfo) {
      return null;
    }

    @Override
    public DexEncodedMethod lookupInvokeStaticTarget(
        DexProgramClass context, AppInfoWithClassHierarchy appInfo) {
      return null;
    }

    @Override
    public DexEncodedMethod lookupInvokeDirectTarget(
        DexProgramClass context, AppInfoWithClassHierarchy appInfo) {
      return null;
    }

    @Override
    public LookupResult lookupVirtualDispatchTargets(
        DexProgramClass context,
        AppInfoWithClassHierarchy appInfo,
        InstantiatedSubTypeInfo instantiatedInfo,
        PinnedPredicate pinnedPredicate) {
      return LookupResult.getIncompleteEmptyResult();
    }

    @Override
    public LookupResult lookupVirtualDispatchTargets(
        DexProgramClass context,
        AppInfoWithLiveness appInfo,
        DexProgramClass refinedReceiverUpperBound,
        DexProgramClass refinedReceiverLowerBound) {
      return LookupResult.getIncompleteEmptyResult();
    }

    @Override
    public LookupTarget lookupVirtualDispatchTarget(
        InstantiatedObject instance, AppInfoWithClassHierarchy appInfo) {
      return null;
    }

    @Override
    public LookupMethodTarget lookupVirtualDispatchTarget(
        DexClass dynamicInstance, AppInfoWithClassHierarchy appInfo) {
      return null;
    }

    @Override
    public LookupTarget lookupVirtualDispatchTarget(
        LambdaDescriptor lambdaInstance,
        AppInfoWithClassHierarchy appInfo,
        Consumer<? super DexEncodedMethod> methodCausingFailureConsumer) {
      return null;
    }
  }

  /** Singleton result for the special case resolving the array clone() method. */
  public static class ArrayCloneMethodResult extends EmptyResult {

    static final ArrayCloneMethodResult INSTANCE = new ArrayCloneMethodResult();

    private ArrayCloneMethodResult() {
      // Intentionally left empty.
    }

    @Override
    public OptionalBool isAccessibleFrom(
        ProgramDefinition context, AppInfoWithClassHierarchy appInfo) {
      return OptionalBool.TRUE;
    }

    @Override
    public OptionalBool isAccessibleForVirtualDispatchFrom(
        ProgramDefinition context, AppInfoWithClassHierarchy appInfo) {
      return OptionalBool.TRUE;
    }

    @Override
    public boolean isVirtualTarget() {
      return true;
    }

    @Override
    public void visitMethodResolutionResults(
        Consumer<? super SingleResolutionResult<?>> programOrClasspathConsumer,
        Consumer<? super SingleLibraryResolutionResult> libraryResultConsumer,
        Consumer<? super ArrayCloneMethodResult> cloneResultConsumer,
        Consumer<? super FailedResolutionResult> failedResolutionConsumer) {
      cloneResultConsumer.accept(this);
    }

    @Override
    public boolean isArrayCloneMethodResult() {
      return true;
    }
  }

  /** Base class for all types of failed resolutions. */
  public abstract static class FailedResolutionResult extends EmptyResult {

    @Override
    public boolean isFailedResolution() {
      return true;
    }

    @Override
    public FailedResolutionResult asFailedResolution() {
      return this;
    }

    public void forEachFailureDependency(
        Consumer<? super DexEncodedMethod> methodCausingFailureConsumer) {
      // Default failure has no dependencies.
    }

    @Override
    public OptionalBool isAccessibleFrom(
        ProgramDefinition context, AppInfoWithClassHierarchy appInfo) {
      return OptionalBool.FALSE;
    }

    @Override
    public OptionalBool isAccessibleForVirtualDispatchFrom(
        ProgramDefinition context, AppInfoWithClassHierarchy appInfo) {
      return OptionalBool.FALSE;
    }

    @Override
    public boolean isVirtualTarget() {
      return false;
    }

    public boolean hasMethodsCausingError() {
      return false;
    }

    @Override
    public void visitMethodResolutionResults(
        Consumer<? super SingleResolutionResult<?>> programOrClasspathConsumer,
        Consumer<? super SingleLibraryResolutionResult> libraryResultConsumer,
        Consumer<? super ArrayCloneMethodResult> cloneResultConsumer,
        Consumer<? super FailedResolutionResult> failedResolutionConsumer) {
      failedResolutionConsumer.accept(this);
    }
  }

  public static class ClassNotFoundResult extends FailedResolutionResult {
    static final ClassNotFoundResult INSTANCE = new ClassNotFoundResult();

    private ClassNotFoundResult() {
      // Intentionally left empty.
    }

    @Override
    public boolean isClassNotFoundResult() {
      return true;
    }
  }

  public abstract static class FailedResolutionWithCausingMethods extends FailedResolutionResult {

    private final Collection<DexEncodedMethod> methodsCausingError;

    private FailedResolutionWithCausingMethods(Collection<DexEncodedMethod> methodsCausingError) {
      this.methodsCausingError = methodsCausingError;
    }

    @Override
    public void forEachFailureDependency(
        Consumer<? super DexEncodedMethod> methodCausingFailureConsumer) {
      this.methodsCausingError.forEach(methodCausingFailureConsumer);
    }

    @Override
    public boolean hasMethodsCausingError() {
      return methodsCausingError.size() > 0;
    }
  }

  public static class IncompatibleClassResult extends FailedResolutionWithCausingMethods {
    static final IncompatibleClassResult INSTANCE =
        new IncompatibleClassResult(Collections.emptyList());

    private IncompatibleClassResult(Collection<DexEncodedMethod> methodsCausingError) {
      super(methodsCausingError);
    }

    static IncompatibleClassResult create(Collection<DexEncodedMethod> methodsCausingError) {
      return methodsCausingError.isEmpty()
          ? INSTANCE
          : new IncompatibleClassResult(methodsCausingError);
    }

    @Override
    public boolean isIncompatibleClassChangeErrorResult() {
      return true;
    }
  }

  public static class NoSuchMethodResult extends FailedResolutionResult {

    static final NoSuchMethodResult INSTANCE = new NoSuchMethodResult();

    @Override
    public boolean isNoSuchMethodErrorResult(DexClass context, AppInfoWithClassHierarchy appInfo) {
      return true;
    }
  }

  static class IllegalAccessOrNoSuchMethodResult extends FailedResolutionWithCausingMethods {

    private final DexClass initialResolutionHolder;

    public IllegalAccessOrNoSuchMethodResult(
        DexClass initialResolutionHolder, Collection<DexEncodedMethod> methodsCausingError) {
      super(methodsCausingError);
      this.initialResolutionHolder = initialResolutionHolder;
    }

    public IllegalAccessOrNoSuchMethodResult(
        DexClass initialResolutionHolder, DexEncodedMethod methodCausingError) {
      this(initialResolutionHolder, Collections.singletonList(methodCausingError));
      assert methodCausingError != null;
    }

    @Override
    public boolean isIllegalAccessErrorResult(DexClass context, AppInfoWithClassHierarchy appInfo) {
      if (!hasMethodsCausingError()) {
        return false;
      }
      BooleanBox seenNoAccess = new BooleanBox(false);
      forEachFailureDependency(
          method -> {
            DexClassAndMethod classAndMethod =
                DexClassAndMethod.create(appInfo.definitionFor(method.getHolderType()), method);
            seenNoAccess.or(
                AccessControl.isMemberAccessible(
                        classAndMethod, initialResolutionHolder, context, appInfo)
                    .isFalse());
          });
      return seenNoAccess.get();
    }

    @Override
    public boolean isNoSuchMethodErrorResult(DexClass context, AppInfoWithClassHierarchy appInfo) {
      if (!hasMethodsCausingError()) {
        return true;
      }
      if (isIllegalAccessErrorResult(context, appInfo)) {
        return false;
      }
      // At this point we know we have methods causing errors but we have access to them. To be
      // certain that this is the case where we have nest access but we are invoking a method with
      // an incorrect symbolic reference, we directly test for it by having an assert.
      assert verifyInvalidSymbolicReference();
      return true;
    }

    private boolean verifyInvalidSymbolicReference() {
      BooleanBox invalidSymbolicReference = new BooleanBox(true);
      forEachFailureDependency(
          method -> {
            invalidSymbolicReference.and(
                method.getHolderType() != initialResolutionHolder.getType());
          });
      return invalidSymbolicReference.get();
    }
  }

  public abstract static class MultipleMethodResolutionResult<
          C extends DexClass & ProgramOrClasspathClass, T extends SingleResolutionResult<C>>
      extends MethodResolutionResult {

    protected final T programOrClasspathResult;
    protected final List<SingleLibraryResolutionResult> libraryResolutionResults;
    protected final List<FailedResolutionResult> failedResolutionResults;

    public MultipleMethodResolutionResult(
        T programOrClasspathResult,
        List<SingleLibraryResolutionResult> libraryResolutionResults,
        List<FailedResolutionResult> failedResolutionResults) {
      this.programOrClasspathResult = programOrClasspathResult;
      this.libraryResolutionResults = libraryResolutionResults;
      this.failedResolutionResults = failedResolutionResults;
    }

    @Override
    public OptionalBool isAccessibleFrom(
        ProgramDefinition context, AppInfoWithClassHierarchy appInfo) {
      throw new Unreachable("Should not be called on MultipleFieldResolutionResult");
    }

    @Override
    public OptionalBool isAccessibleForVirtualDispatchFrom(
        ProgramDefinition context, AppInfoWithClassHierarchy appInfo) {
      throw new Unreachable("Should not be called on MultipleFieldResolutionResult");
    }

    @Override
    public boolean isVirtualTarget() {
      throw new Unreachable("Should not be called on MultipleFieldResolutionResult");
    }

    @Override
    public DexClassAndMethod lookupInvokeSpecialTarget(
        DexProgramClass context, AppInfoWithClassHierarchy appInfo) {
      throw new Unreachable("Should not be called on MultipleFieldResolutionResult");
    }

    @Override
    public DexClassAndMethod lookupInvokeSuperTarget(
        DexProgramClass context, AppInfoWithClassHierarchy appInfo) {
      throw new Unreachable("Should not be called on MultipleFieldResolutionResult");
    }

    @Override
    public DexEncodedMethod lookupInvokeDirectTarget(
        DexProgramClass context, AppInfoWithClassHierarchy appInfo) {
      throw new Unreachable("Should not be called on MultipleFieldResolutionResult");
    }

    @Override
    public DexEncodedMethod lookupInvokeStaticTarget(
        DexProgramClass context, AppInfoWithClassHierarchy appInfo) {
      throw new Unreachable("Should not be called on MultipleFieldResolutionResult");
    }

    @Override
    public LookupResult lookupVirtualDispatchTargets(
        DexProgramClass context,
        AppInfoWithClassHierarchy appInfo,
        InstantiatedSubTypeInfo instantiatedInfo,
        PinnedPredicate pinnedPredicate) {
      throw new Unreachable("Should not be called on MultipleFieldResolutionResult");
    }

    @Override
    public LookupResult lookupVirtualDispatchTargets(
        DexProgramClass context,
        AppInfoWithLiveness appInfo,
        DexProgramClass refinedReceiverUpperBound,
        DexProgramClass refinedReceiverLowerBound) {
      throw new Unreachable("Should not be called on MultipleFieldResolutionResult");
    }

    @Override
    public LookupTarget lookupVirtualDispatchTarget(
        InstantiatedObject instance, AppInfoWithClassHierarchy appInfo) {
      throw new Unreachable("Should not be called on MultipleFieldResolutionResult");
    }

    @Override
    public DexClassAndMethod lookupVirtualDispatchTarget(
        DexClass dynamicInstance, AppInfoWithClassHierarchy appInfo) {
      throw new Unreachable("Should not be called on MultipleFieldResolutionResult");
    }

    @Override
    public LookupTarget lookupVirtualDispatchTarget(
        LambdaDescriptor lambdaInstance,
        AppInfoWithClassHierarchy appInfo,
        Consumer<? super DexEncodedMethod> methodCausingFailureConsumer) {
      throw new Unreachable("Should not be called on MultipleFieldResolutionResult");
    }

    @Override
    public void visitMethodResolutionResults(
        Consumer<? super SingleResolutionResult<?>> programOrClasspathConsumer,
        Consumer<? super SingleLibraryResolutionResult> libraryResultConsumer,
        Consumer<? super ArrayCloneMethodResult> cloneResultConsumer,
        Consumer<? super FailedResolutionResult> failedResolutionConsumer) {
      if (programOrClasspathResult != null) {
        programOrClasspathConsumer.accept(programOrClasspathResult);
      }
      libraryResolutionResults.forEach(libraryResultConsumer);
      failedResolutionResults.forEach(failedResolutionConsumer);
    }
  }

  public static class MultipleProgramWithLibraryResolutionResult
      extends MultipleMethodResolutionResult<DexProgramClass, SingleProgramResolutionResult> {

    public MultipleProgramWithLibraryResolutionResult(
        SingleProgramResolutionResult programOrClasspathResult,
        List<SingleLibraryResolutionResult> libraryResolutionResults,
        List<FailedResolutionResult> failedOrUnknownResolutionResults) {
      super(programOrClasspathResult, libraryResolutionResults, failedOrUnknownResolutionResults);
    }
  }

  public static class MultipleClasspathWithLibraryResolutionResult
      extends MultipleMethodResolutionResult<DexClasspathClass, SingleClasspathResolutionResult> {

    public MultipleClasspathWithLibraryResolutionResult(
        SingleClasspathResolutionResult programOrClasspathResult,
        List<SingleLibraryResolutionResult> libraryResolutionResults,
        List<FailedResolutionResult> failedOrUnknownResolutionResults) {
      super(programOrClasspathResult, libraryResolutionResults, failedOrUnknownResolutionResults);
    }
  }

  public static class MultipleLibraryMethodResolutionResult
      extends MultipleMethodResolutionResult<DexProgramClass, SingleProgramResolutionResult> {

    public MultipleLibraryMethodResolutionResult(
        List<SingleLibraryResolutionResult> libraryResolutionResults,
        List<FailedResolutionResult> failedOrUnknownResolutionResults) {
      super(null, libraryResolutionResults, failedOrUnknownResolutionResults);
    }
  }

  public static Builder builder() {
    return new Builder();
  }

  public static class Builder {

    private MethodResolutionResult possiblySingleResult = null;
    private List<MethodResolutionResult> allResults = null;

    private Builder() {}

    public void addResolutionResult(MethodResolutionResult result) {
      if (possiblySingleResult == null) {
        possiblySingleResult = result;
        return;
      }
      if (allResults == null) {
        allResults = new ArrayList<>();
        allResults.add(possiblySingleResult);
      }
      allResults.add(result);
    }

    public MethodResolutionResult buildOrIfEmpty(MethodResolutionResult emptyResult) {
      if (possiblySingleResult == null) {
        return emptyResult;
      } else if (allResults == null) {
        return possiblySingleResult;
      }
      Box<SingleResolutionResult<?>> singleResult = new Box<>();
      List<SingleLibraryResolutionResult> libraryResults = new ArrayList<>();
      List<FailedResolutionResult> failedResults = new ArrayList<>();
      allResults.forEach(
          otherResult -> {
            otherResult.visitMethodResolutionResults(
                otherProgramOrClasspathResult -> {
                  if (singleResult.isSet()) {
                    assert false : "Unexpected multiple results between program and classpath";
                    if (singleResult.get().hasProgramResult()) {
                      return;
                    }
                  }
                  singleResult.set(otherProgramOrClasspathResult);
                },
                newLibraryResult -> {
                  if (!Iterables.any(
                      libraryResults,
                      existing ->
                          existing.getResolvedHolder() == newLibraryResult.getResolvedHolder())) {
                    libraryResults.add(newLibraryResult);
                  }
                },
                ConsumerUtils.emptyConsumer(),
                newFailedResult -> {
                  if (!Iterables.any(
                      failedResults,
                      existing ->
                          existing.isFailedResolution() == newFailedResult.isFailedResolution())) {
                    failedResults.add(newFailedResult);
                  }
                });
          });
      if (!singleResult.isSet()) {
        if (libraryResults.size() == 1 && failedResults.isEmpty()) {
          return libraryResults.get(0);
        } else if (libraryResults.isEmpty() && failedResults.size() == 1) {
          return failedResults.get(0);
        } else {
          return new MultipleLibraryMethodResolutionResult(libraryResults, failedResults);
        }
      } else if (libraryResults.isEmpty() && failedResults.isEmpty()) {
        return singleResult.get();
      } else if (singleResult.get().hasProgramResult()) {
        return new MultipleProgramWithLibraryResolutionResult(
            singleResult.get().asSingleProgramResolutionResult(), libraryResults, failedResults);
      } else {
        SingleClasspathResolutionResult classpathResult =
            singleResult.get().asSingleClasspathResolutionResult();
        assert classpathResult != null;
        return new MultipleClasspathWithLibraryResolutionResult(
            classpathResult, libraryResults, failedResults);
      }
    }
  }
}
