// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.graph;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.ir.analysis.value.SingleValue;
import com.android.tools.r8.ir.code.ConstInstruction;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Position;
import com.android.tools.r8.ir.conversion.ExtraParameter;
import com.android.tools.r8.ir.conversion.ExtraUnusedNullParameter;
import com.android.tools.r8.ir.optimize.info.MethodOptimizationInfoFixer;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.ConsumerUtils;
import com.android.tools.r8.utils.IntObjConsumer;
import com.android.tools.r8.utils.IteratorUtils;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Ordering;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap.Entry;
import it.unimi.dsi.fastutil.ints.Int2ReferenceRBTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceSortedMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntBidirectionalIterator;
import it.unimi.dsi.fastutil.ints.IntCollection;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntLists;
import it.unimi.dsi.fastutil.ints.IntSortedSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

public class RewrittenPrototypeDescription {

  public interface ArgumentInfo {

    @SuppressWarnings("ConstantConditions")
    static ArgumentInfo combine(ArgumentInfo arg1, ArgumentInfo arg2) {
      if (arg1 == null) {
        assert arg2 != null;
        return arg2;
      }
      if (arg2 == null) {
        assert arg1 != null;
        return arg1;
      }
      return arg1.combine(arg2);
    }

    ArgumentInfo NO_INFO =
        info -> {
          assert false : "ArgumentInfo NO_INFO should not be combined";
          return info;
        };

    default boolean isRemovedArgumentInfo() {
      return false;
    }

    default RemovedArgumentInfo asRemovedArgumentInfo() {
      return null;
    }

    default boolean isRewrittenTypeInfo() {
      return false;
    }

    default RewrittenTypeInfo asRewrittenTypeInfo() {
      return null;
    }

    // ArgumentInfo are combined with `this` first, and the `info` argument second.
    ArgumentInfo combine(ArgumentInfo info);
  }

  public static class RemovedArgumentInfo implements ArgumentInfo {

    public static class Builder {

      private SingleValue singleValue;
      private DexType type;

      public Builder setSingleValue(SingleValue singleValue) {
        this.singleValue = singleValue;
        return this;
      }

      public Builder setType(DexType type) {
        this.type = type;
        return this;
      }

      public RemovedArgumentInfo build() {
        assert type != null;
        return new RemovedArgumentInfo(singleValue, type);
      }
    }

    private final SingleValue singleValue;
    private final DexType type;

    private RemovedArgumentInfo(SingleValue singleValue, DexType type) {
      this.singleValue = singleValue;
      this.type = type;
    }

    public static Builder builder() {
      return new Builder();
    }

    public boolean hasSingleValue() {
      return singleValue != null;
    }

    public SingleValue getSingleValue() {
      return singleValue;
    }

    public DexType getType() {
      return type;
    }

    public boolean isNeverUsed() {
      return !hasSingleValue();
    }

    @Override
    public boolean isRemovedArgumentInfo() {
      return true;
    }

    @Override
    public RemovedArgumentInfo asRemovedArgumentInfo() {
      return this;
    }

    @Override
    public ArgumentInfo combine(ArgumentInfo info) {
      assert false : "Once the argument is removed one cannot modify it any further.";
      return this;
    }
  }

  public static class RewrittenTypeInfo implements ArgumentInfo {

    private final DexType oldType;
    private final DexType newType;

    static RewrittenTypeInfo toVoid(DexType oldReturnType, DexItemFactory dexItemFactory) {
      return new RewrittenTypeInfo(oldReturnType, dexItemFactory.voidType);
    }

    public RewrittenTypeInfo(DexType oldType, DexType newType) {
      this.oldType = oldType;
      this.newType = newType;
    }

    public RewrittenTypeInfo combine(RewrittenPrototypeDescription other) {
      return other.hasRewrittenReturnInfo() ? combine(other.getRewrittenReturnInfo()) : this;
    }

    public RewrittenTypeInfo combine(RewrittenTypeInfo other) {
      assert !getNewType().isVoidType();
      assert getNewType() == other.getOldType();
      return new RewrittenTypeInfo(getOldType(), other.getNewType());
    }

    public DexType getNewType() {
      return newType;
    }

    public DexType getOldType() {
      return oldType;
    }

    boolean hasBeenChangedToReturnVoid(DexItemFactory dexItemFactory) {
      return newType == dexItemFactory.voidType;
    }

    @Override
    public boolean isRewrittenTypeInfo() {
      return true;
    }

    @Override
    public RewrittenTypeInfo asRewrittenTypeInfo() {
      return this;
    }

    @Override
    public ArgumentInfo combine(ArgumentInfo info) {
      if (info.isRemovedArgumentInfo()) {
        return info;
      }
      assert info.isRewrittenTypeInfo();
      RewrittenTypeInfo rewrittenTypeInfo = info.asRewrittenTypeInfo();
      assert newType == rewrittenTypeInfo.oldType;
      return new RewrittenTypeInfo(oldType, rewrittenTypeInfo.newType);
    }
  }

  public static class ArgumentInfoCollection {

    private static final ArgumentInfoCollection EMPTY = new ArgumentInfoCollection();

    private final Int2ReferenceSortedMap<ArgumentInfo> argumentInfos;

    // Specific constructor for empty.
    private ArgumentInfoCollection() {
      this.argumentInfos = new Int2ReferenceRBTreeMap<>();
    }

    private ArgumentInfoCollection(Int2ReferenceSortedMap<ArgumentInfo> argumentInfos) {
      assert argumentInfos != null : "should use empty.";
      assert !argumentInfos.isEmpty() : "should use empty.";
      this.argumentInfos = argumentInfos;
    }

    public static ArgumentInfoCollection empty() {
      return EMPTY;
    }

    public void forEach(IntObjConsumer<ArgumentInfo> consumer) {
      for (Entry<ArgumentInfo> entry : argumentInfos.int2ReferenceEntrySet()) {
        consumer.accept(entry.getIntKey(), entry.getValue());
      }
    }

    public IntSortedSet getKeys() {
      return argumentInfos.keySet();
    }

    public IntCollection getRemovedParameterIndices() {
      int numberOfRemovedArguments = numberOfRemovedArguments();
      if (numberOfRemovedArguments == 0) {
        return IntLists.EMPTY_LIST;
      }
      if (numberOfRemovedArguments == argumentInfos.size()) {
        return getKeys();
      }
      IntList removedParameterIndices = new IntArrayList(numberOfRemovedArguments);
      Iterator<Entry<ArgumentInfo>> iterator = iterator();
      while (iterator.hasNext()) {
        Entry<ArgumentInfo> entry = iterator.next();
        if (entry.getValue().isRemovedArgumentInfo()) {
          removedParameterIndices.add(entry.getIntKey());
        }
      }
      return removedParameterIndices;
    }

    public boolean isArgumentRemoved(int argumentIndex) {
      return getArgumentInfo(argumentIndex).isRemovedArgumentInfo();
    }

    public boolean isEmpty() {
      return this == EMPTY;
    }

    public Iterator<Entry<ArgumentInfo>> iterator() {
      return argumentInfos.int2ReferenceEntrySet().iterator();
    }

    public boolean hasRemovedArguments() {
      for (ArgumentInfo value : argumentInfos.values()) {
        if (value.isRemovedArgumentInfo()) {
          return true;
        }
      }
      return false;
    }

    public int numberOfRemovedArguments() {
      int removed = 0;
      for (ArgumentInfo value : argumentInfos.values()) {
        if (value.isRemovedArgumentInfo()) {
          removed++;
        }
      }
      return removed;
    }

    public boolean hasArgumentInfo(int argumentIndex) {
      return argumentInfos.containsKey(argumentIndex);
    }

    public ArgumentInfo getArgumentInfo(int argumentIndex) {
      return argumentInfos.getOrDefault(argumentIndex, ArgumentInfo.NO_INFO);
    }

    public int size() {
      return argumentInfos.size();
    }

    public static Builder builder() {
      return new Builder();
    }

    public static class Builder {

      private Int2ReferenceSortedMap<ArgumentInfo> argumentInfos;

      public Builder addArgumentInfo(int argIndex, ArgumentInfo argInfo) {
        if (argumentInfos == null) {
          argumentInfos = new Int2ReferenceRBTreeMap<>();
        }
        assert !argumentInfos.containsKey(argIndex);
        argumentInfos.put(argIndex, argInfo);
        return this;
      }

      public ArgumentInfoCollection build() {
        if (argumentInfos == null || argumentInfos.isEmpty()) {
          return EMPTY;
        }
        return new ArgumentInfoCollection(argumentInfos);
      }
    }

    public DexMethod rewriteMethod(ProgramMethod method, DexItemFactory dexItemFactory) {
      if (isEmpty()) {
        return method.getReference();
      }
      DexProto rewrittenProto = rewriteProto(method, dexItemFactory);
      return method.getReference().withProto(rewrittenProto, dexItemFactory);
    }

    public DexProto rewriteProto(ProgramMethod method, DexItemFactory dexItemFactory) {
      return isEmpty()
          ? method.getProto()
          : dexItemFactory.createProto(method.getReturnType(), rewriteParameters(method));
    }

    public DexType[] rewriteParameters(ProgramMethod method) {
      return rewriteParameters(method.getDefinition());
    }

    public DexType[] rewriteParameters(DexEncodedMethod encodedMethod) {
      // Currently not allowed to remove the receiver of an instance method. This would involve
      // changing invoke-direct/invoke-virtual into invoke-static.
      assert encodedMethod.isStatic() || !getArgumentInfo(0).isRemovedArgumentInfo();
      DexType[] params = encodedMethod.getReference().proto.parameters.values;
      if (isEmpty()) {
        return params;
      }
      DexType[] newParams = new DexType[params.length - numberOfRemovedArguments()];
      int offset = encodedMethod.getFirstNonReceiverArgumentIndex();
      int newParamIndex = 0;
      for (int oldParamIndex = 0; oldParamIndex < params.length; oldParamIndex++) {
        ArgumentInfo argInfo = argumentInfos.get(oldParamIndex + offset);
        if (argInfo == null) {
          newParams[newParamIndex++] = params[oldParamIndex];
        } else if (argInfo.isRewrittenTypeInfo()) {
          RewrittenTypeInfo rewrittenTypeInfo = argInfo.asRewrittenTypeInfo();
          assert params[oldParamIndex] == rewrittenTypeInfo.oldType;
          newParams[newParamIndex++] = rewrittenTypeInfo.newType;
        }
      }
      return newParams;
    }

    public ArgumentInfoCollection combine(ArgumentInfoCollection info) {
      if (isEmpty()) {
        return info;
      } else {
        if (info.isEmpty()) {
          return this;
        }
      }

      Int2ReferenceSortedMap<ArgumentInfo> newArgInfos = new Int2ReferenceRBTreeMap<>();
      newArgInfos.putAll(argumentInfos);
      IntBidirectionalIterator iterator = argumentInfos.keySet().iterator();
      int offset = 0;
      int nextArgIndex;
      for (int pendingArgIndex : info.argumentInfos.keySet()) {
        nextArgIndex = peekNextOrMax(iterator);
        while (nextArgIndex <= pendingArgIndex + offset) {
          iterator.nextInt();
          ArgumentInfo argumentInfo = argumentInfos.get(nextArgIndex);
          nextArgIndex = peekNextOrMax(iterator);
          if (argumentInfo.isRemovedArgumentInfo()) {
            offset++;
          }
        }
        ArgumentInfo newArgInfo =
            nextArgIndex == pendingArgIndex + offset
                ? ArgumentInfo.combine(
                    argumentInfos.get(nextArgIndex), info.argumentInfos.get(pendingArgIndex))
                : info.argumentInfos.get(pendingArgIndex);
        newArgInfos.put(pendingArgIndex + offset, newArgInfo);
      }
      assert Ordering.natural().isOrdered(newArgInfos.keySet());
      return new ArgumentInfoCollection(newArgInfos);
    }

    static int peekNextOrMax(IntBidirectionalIterator iterator) {
      if (iterator.hasNext()) {
        int i = iterator.nextInt();
        iterator.previousInt();
        return i;
      }
      return Integer.MAX_VALUE;
    }

    public MethodOptimizationInfoFixer createMethodOptimizationInfoFixer() {
      RewrittenPrototypeDescription prototypeChanges =
          RewrittenPrototypeDescription.create(Collections.emptyList(), null, this);
      return prototypeChanges.createMethodOptimizationInfoFixer();
    }

    /**
     * Returns a function for rewriting the parameter annotations on a method info after prototype
     * changes were made.
     */
    public Consumer<DexEncodedMethod.Builder> createParameterAnnotationsRemover(
        DexEncodedMethod method) {
      if (numberOfRemovedArguments() > 0 && !method.parameterAnnotationsList.isEmpty()) {
        return builder -> {
          int firstArgumentIndex = method.getFirstNonReceiverArgumentIndex();
          builder.removeParameterAnnotations(
              oldIndex -> getArgumentInfo(oldIndex + firstArgumentIndex).isRemovedArgumentInfo());
        };
      }
      return ConsumerUtils.emptyConsumer();
    }

    public int getNewArgumentIndex(int argumentIndex) {
      int numberOfArgumentsRemovedBeforeArgument = 0;
      Iterator<Entry<ArgumentInfo>> iterator = iterator();
      while (iterator.hasNext()) {
        Entry<ArgumentInfo> entry = iterator.next();
        int argumentIndexForInfo = entry.getIntKey();
        if (argumentIndexForInfo >= argumentIndex) {
          break;
        }
        ArgumentInfo argumentInfo = entry.getValue();
        if (argumentInfo.isRemovedArgumentInfo()) {
          numberOfArgumentsRemovedBeforeArgument++;
        }
      }
      assert IteratorUtils.allRemainingMatchDestructive(
          iterator, entry -> entry.getIntKey() >= argumentIndex);
      return argumentIndex - numberOfArgumentsRemovedBeforeArgument;
    }
  }

  private static final RewrittenPrototypeDescription NONE = new RewrittenPrototypeDescription();

  private final List<ExtraParameter> extraParameters;
  private final ArgumentInfoCollection argumentInfoCollection;
  private final RewrittenTypeInfo rewrittenReturnInfo;

  private RewrittenPrototypeDescription() {
    this.extraParameters = Collections.emptyList();
    this.rewrittenReturnInfo = null;
    this.argumentInfoCollection = ArgumentInfoCollection.empty();
  }

  private RewrittenPrototypeDescription(
      List<ExtraParameter> extraParameters,
      RewrittenTypeInfo rewrittenReturnInfo,
      ArgumentInfoCollection argumentsInfo) {
    assert argumentsInfo != null;
    this.extraParameters = extraParameters;
    this.rewrittenReturnInfo = rewrittenReturnInfo;
    this.argumentInfoCollection = argumentsInfo;
    assert !isEmpty();
  }

  private static RewrittenPrototypeDescription create(
      List<ExtraParameter> extraParameters,
      RewrittenTypeInfo rewrittenReturnInfo,
      ArgumentInfoCollection argumentsInfo) {
    return extraParameters.isEmpty() && rewrittenReturnInfo == null && argumentsInfo.isEmpty()
        ? none()
        : new RewrittenPrototypeDescription(extraParameters, rewrittenReturnInfo, argumentsInfo);
  }

  public static RewrittenPrototypeDescription createForUninstantiatedTypes(
      DexMethod method,
      AppView<AppInfoWithLiveness> appView,
      ArgumentInfoCollection removedArgumentsInfo) {
    DexType returnType = method.proto.returnType;
    RewrittenTypeInfo returnInfo =
        returnType.isAlwaysNull(appView)
            ? RewrittenTypeInfo.toVoid(returnType, appView.dexItemFactory())
            : null;
    return create(Collections.emptyList(), returnInfo, removedArgumentsInfo);
  }

  public static RewrittenPrototypeDescription createForRewrittenTypes(
      RewrittenTypeInfo returnInfo, ArgumentInfoCollection rewrittenArgumentsInfo) {
    return create(Collections.emptyList(), returnInfo, rewrittenArgumentsInfo);
  }

  public static RewrittenPrototypeDescription none() {
    return NONE;
  }

  public MethodOptimizationInfoFixer createMethodOptimizationInfoFixer() {
    return new RewrittenPrototypeDescriptionMethodOptimizationInfoFixer(this);
  }

  public RewrittenPrototypeDescription combine(RewrittenPrototypeDescription other) {
    if (isEmpty()) {
      return other;
    }
    if (other.isEmpty()) {
      return this;
    }
    // We currently don't have any passes that remove extra parameters inserted by previous passes.
    // If the input prototype changes have removed some of the extra parameters, we would need to
    // adapt the merging of prototype changes below.
    List<ExtraParameter> newExtraParameters =
        ImmutableList.<ExtraParameter>builder()
            .addAll(getExtraParameters())
            .addAll(other.getExtraParameters())
            .build();
    RewrittenTypeInfo newRewrittenTypeInfo =
        hasRewrittenReturnInfo()
            ? getRewrittenReturnInfo().combine(other)
            : other.getRewrittenReturnInfo();
    ArgumentInfoCollection newArgumentInfoCollection =
        getArgumentInfoCollection().combine(other.getArgumentInfoCollection());
    return new RewrittenPrototypeDescription(
        newExtraParameters, newRewrittenTypeInfo, newArgumentInfoCollection);
  }

  public boolean isEmpty() {
    return extraParameters.isEmpty()
        && rewrittenReturnInfo == null
        && argumentInfoCollection.isEmpty();
  }

  public boolean hasExtraParameters() {
    return !extraParameters.isEmpty();
  }

  public List<ExtraParameter> getExtraParameters() {
    return extraParameters;
  }

  public int numberOfExtraParameters() {
    return extraParameters.size();
  }

  public boolean hasBeenChangedToReturnVoid(DexItemFactory dexItemFactory) {
    return rewrittenReturnInfo != null
        && rewrittenReturnInfo.hasBeenChangedToReturnVoid(dexItemFactory);
  }

  public ArgumentInfoCollection getArgumentInfoCollection() {
    return argumentInfoCollection;
  }

  public boolean hasRewrittenReturnInfo() {
    return rewrittenReturnInfo != null;
  }

  public boolean requiresRewritingAtCallSite() {
    return hasRewrittenReturnInfo()
        || numberOfExtraParameters() > 0
        || argumentInfoCollection.numberOfRemovedArguments() > 0;
  }

  public RewrittenTypeInfo getRewrittenReturnInfo() {
    return rewrittenReturnInfo;
  }

  /**
   * Returns the {@link ConstInstruction} that should be used to materialize the result of
   * invocations to the method represented by this {@link RewrittenPrototypeDescription}.
   *
   * <p>This method should only be used for methods that return a constant value and whose return
   * type has been changed to void.
   *
   * <p>Note that the current implementation always returns null at this point.
   */
  public ConstInstruction getConstantReturn(IRCode code, Position position) {
    ConstInstruction instruction = code.createConstNull();
    instruction.setPosition(position);
    return instruction;
  }

  public DexProto rewriteProto(DexEncodedMethod encodedMethod, DexItemFactory dexItemFactory) {
    if (isEmpty()) {
      return encodedMethod.getReference().proto;
    }
    DexType newReturnType =
        rewrittenReturnInfo != null
            ? rewrittenReturnInfo.newType
            : encodedMethod.getReference().proto.returnType;
    DexType[] newParameters = argumentInfoCollection.rewriteParameters(encodedMethod);
    return dexItemFactory.createProto(newReturnType, newParameters);
  }

  public RewrittenPrototypeDescription withConstantReturn(
      DexType oldReturnType, DexItemFactory dexItemFactory) {
    assert rewrittenReturnInfo == null;
    return !hasBeenChangedToReturnVoid(dexItemFactory)
        ? new RewrittenPrototypeDescription(
            extraParameters,
            RewrittenTypeInfo.toVoid(oldReturnType, dexItemFactory),
            argumentInfoCollection)
        : this;
  }

  public RewrittenPrototypeDescription withRemovedArguments(ArgumentInfoCollection other) {
    if (other.isEmpty()) {
      return this;
    }
    return new RewrittenPrototypeDescription(
        extraParameters, rewrittenReturnInfo, argumentInfoCollection.combine(other));
  }

  public RewrittenPrototypeDescription withRewrittenReturnInfo(
      RewrittenTypeInfo rewrittenReturnInfo) {
    if (rewrittenReturnInfo == null) {
      return this;
    }
    if (!hasRewrittenReturnInfo()) {
      return new RewrittenPrototypeDescription(
          extraParameters, rewrittenReturnInfo, argumentInfoCollection);
    }
    throw new Unreachable();
  }

  public RewrittenPrototypeDescription withExtraUnusedNullParameter() {
    return withExtraUnusedNullParameters(1);
  }

  public RewrittenPrototypeDescription withExtraUnusedNullParameters(
      int numberOfExtraUnusedNullParameters) {
    List<ExtraParameter> parameters =
        Collections.nCopies(numberOfExtraUnusedNullParameters, new ExtraUnusedNullParameter());
    return withExtraParameters(parameters);
  }

  public RewrittenPrototypeDescription withExtraParameter(ExtraParameter parameter) {
    return withExtraParameters(Collections.singletonList(parameter));
  }

  public RewrittenPrototypeDescription withExtraParameters(List<ExtraParameter> parameters) {
    if (parameters.isEmpty()) {
      return this;
    }
    List<ExtraParameter> newExtraParameters =
        new ArrayList<>(extraParameters.size() + parameters.size());
    newExtraParameters.addAll(extraParameters);
    newExtraParameters.addAll(parameters);
    return new RewrittenPrototypeDescription(
        newExtraParameters, rewrittenReturnInfo, argumentInfoCollection);
  }
}
