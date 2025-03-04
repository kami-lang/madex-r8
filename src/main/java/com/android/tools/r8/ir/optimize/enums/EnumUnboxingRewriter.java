// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.enums;

import static com.android.tools.r8.ir.analysis.type.Nullability.maybeNull;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexClassAndMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.graph.proto.ArgumentInfo;
import com.android.tools.r8.graph.proto.RewrittenPrototypeDescription;
import com.android.tools.r8.graph.proto.RewrittenTypeInfo;
import com.android.tools.r8.ir.analysis.type.ArrayTypeElement;
import com.android.tools.r8.ir.analysis.type.TypeElement;
import com.android.tools.r8.ir.code.ArrayAccess;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.ConstNumber;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.If;
import com.android.tools.r8.ir.code.InitClass;
import com.android.tools.r8.ir.code.InstanceGet;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.InvokeMethod;
import com.android.tools.r8.ir.code.InvokeMethodWithReceiver;
import com.android.tools.r8.ir.code.InvokeStatic;
import com.android.tools.r8.ir.code.InvokeVirtual;
import com.android.tools.r8.ir.code.MemberType;
import com.android.tools.r8.ir.code.NewUnboxedEnumInstance;
import com.android.tools.r8.ir.code.Phi;
import com.android.tools.r8.ir.code.StaticGet;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.conversion.MethodProcessor;
import com.android.tools.r8.ir.optimize.enums.EnumInstanceFieldData.EnumInstanceFieldKnownData;
import com.android.tools.r8.ir.optimize.enums.classification.CheckNotNullEnumUnboxerMethodClassification;
import com.android.tools.r8.ir.optimize.enums.classification.EnumUnboxerMethodClassification;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.InternalOptions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;

public class EnumUnboxingRewriter {

  private final AppView<AppInfoWithLiveness> appView;
  private final Map<DexMethod, DexMethod> checkNotNullToCheckNotZeroMapping;
  private final DexItemFactory factory;
  private final InternalOptions options;
  private final EnumDataMap unboxedEnumsData;
  private final EnumUnboxingLens enumUnboxingLens;
  private final EnumUnboxingUtilityClasses utilityClasses;

  EnumUnboxingRewriter(
      AppView<AppInfoWithLiveness> appView,
      Map<DexMethod, DexMethod> checkNotNullToCheckNotZeroMapping,
      EnumUnboxingLens enumUnboxingLens,
      EnumDataMap unboxedEnumsInstanceFieldData,
      EnumUnboxingUtilityClasses utilityClasses) {
    this.appView = appView;
    this.checkNotNullToCheckNotZeroMapping = checkNotNullToCheckNotZeroMapping;
    this.factory = appView.dexItemFactory();
    this.options = appView.options();
    this.enumUnboxingLens = enumUnboxingLens;
    this.unboxedEnumsData = unboxedEnumsInstanceFieldData;
    this.utilityClasses = utilityClasses;
  }

  private LocalEnumUnboxingUtilityClass getLocalUtilityClass(DexType enumType) {
    return utilityClasses.getLocalUtilityClass(enumType);
  }

  private SharedEnumUnboxingUtilityClass getSharedUtilityClass() {
    return utilityClasses.getSharedUtilityClass();
  }

  private Map<Instruction, DexType> createInitialConvertedEnums(
      IRCode code, RewrittenPrototypeDescription prototypeChanges) {
    Map<Instruction, DexType> convertedEnums = new IdentityHashMap<>();
    Iterator<Instruction> iterator = code.entryBlock().iterator();
    int originalNumberOfArguments =
        code.getNumberOfArguments()
            + prototypeChanges.getArgumentInfoCollection().numberOfRemovedArguments();
    for (int argumentIndex = 0; argumentIndex < originalNumberOfArguments; argumentIndex++) {
      ArgumentInfo argumentInfo =
          prototypeChanges.getArgumentInfoCollection().getArgumentInfo(argumentIndex);
      if (argumentInfo.isRemovedArgumentInfo()) {
        continue;
      }
      Instruction next = iterator.next();
      assert next.isArgument();
      if (argumentInfo.isRewrittenTypeInfo()) {
        RewrittenTypeInfo rewrittenTypeInfo = argumentInfo.asRewrittenTypeInfo();
        DexType enumType = getEnumTypeOrNull(rewrittenTypeInfo.getOldType().toBaseType(factory));
        if (enumType != null) {
          convertedEnums.put(next, enumType);
        }
      }
    }
    return convertedEnums;
  }

  Set<Phi> rewriteCode(
      IRCode code,
      MethodProcessor methodProcessor,
      RewrittenPrototypeDescription prototypeChanges) {
    // We should not process the enum methods, they will be removed and they may contain invalid
    // rewriting rules.
    if (unboxedEnumsData.isEmpty()) {
      return Sets.newIdentityHashSet();
    }
    assert code.isConsistentSSABeforeTypesAreCorrect(appView);
    ProgramMethod context = code.context();
    Map<Instruction, DexType> convertedEnums = createInitialConvertedEnums(code, prototypeChanges);
    Set<Phi> affectedPhis = Sets.newIdentityHashSet();
    ListIterator<BasicBlock> blocks = code.listIterator();
    Set<BasicBlock> seenBlocks = Sets.newIdentityHashSet();
    Set<Instruction> instructionsToRemove = Sets.newIdentityHashSet();
    Value zeroConstValue = null;
    while (blocks.hasNext()) {
      BasicBlock block = blocks.next();
      seenBlocks.add(block);
      zeroConstValue = fixNullsInBlockPhis(code, block, zeroConstValue);
      InstructionListIterator iterator = block.listIterator(code);
      while (iterator.hasNext()) {
        Instruction instruction = iterator.next();
        if (instructionsToRemove.contains(instruction)) {
          iterator.removeOrReplaceByDebugLocalRead();
          continue;
        }

        if (instruction.isInitClass()) {
          InitClass initClass = instruction.asInitClass();
          DexType enumType = getEnumTypeOrNull(initClass.getClassValue());
          if (enumType != null) {
            iterator.removeOrReplaceByDebugLocalRead();
          }
          continue;
        }

        if (instruction.isIf()) {
          If ifInstruction = instruction.asIf();
          if (!ifInstruction.isZeroTest()) {
            for (int operandIndex = 0; operandIndex < 2; operandIndex++) {
              Value operand = ifInstruction.getOperand(operandIndex);
              DexType enumType = getEnumTypeOrNull(operand, convertedEnums);
              if (enumType != null) {
                int otherOperandIndex = 1 - operandIndex;
                Value otherOperand = ifInstruction.getOperand(otherOperandIndex);
                if (otherOperand.getType().isNullType()) {
                  iterator.previous();
                  ifInstruction.replaceValue(
                      otherOperandIndex, iterator.insertConstIntInstruction(code, options, 0));
                  iterator.next();
                  break;
                }
              }
            }
          }
        }

        // Rewrites specific enum methods, such as ordinal, into their corresponding enum unboxed
        // counterpart. The rewriting (== or match) is based on the following:
        // - name, ordinal and compareTo are final and implemented only on java.lang.Enum,
        // - equals, hashCode are final and implemented in java.lang.Enum and java.lang.Object,
        // - getClass is final and implemented only in java.lang.Object,
        // - toString is non-final, implemented in java.lang.Object, java.lang.Enum and possibly
        //   also in the unboxed enum class.
        if (instruction.isInvokeMethodWithReceiver()) {
          InvokeMethodWithReceiver invoke = instruction.asInvokeMethodWithReceiver();
          DexType enumType = getEnumTypeOrNull(invoke.getReceiver(), convertedEnums);
          DexMethod invokedMethod = invoke.getInvokedMethod();
          if (enumType != null) {
            if (invokedMethod == factory.enumMembers.ordinalMethod
                || invokedMethod.match(factory.enumMembers.hashCode)) {
              replaceEnumInvoke(
                  iterator, invoke, getSharedUtilityClass().ensureOrdinalMethod(appView));
              continue;
            } else if (invokedMethod.match(factory.enumMembers.equals)) {
              replaceEnumInvoke(
                  iterator, invoke, getSharedUtilityClass().ensureEqualsMethod(appView));
              continue;
            } else if (invokedMethod == factory.enumMembers.compareTo
                || invokedMethod == factory.enumMembers.compareToWithObject) {
              replaceEnumInvoke(
                  iterator, invoke, getSharedUtilityClass().ensureCompareToMethod(appView));
              continue;
            } else if (invokedMethod == factory.enumMembers.nameMethod) {
              rewriteNameMethod(iterator, invoke, enumType, methodProcessor);
              continue;
            } else if (invokedMethod.match(factory.enumMembers.toString)) {
              DexMethod lookupMethod = enumUnboxingLens.lookupMethod(invokedMethod);
              // If the lookupMethod is different, then a toString method was on the enumType
              // class, which was moved, and the lens code rewriter will rewrite the invoke to
              // that method.
              if (invoke.isInvokeSuper() || lookupMethod == invokedMethod) {
                rewriteNameMethod(iterator, invoke, enumType, methodProcessor);
                continue;
              }
            } else if (invokedMethod == factory.objectMembers.getClass) {
              rewriteNullCheck(iterator, invoke);
              continue;
            }
          } else if (invokedMethod == factory.stringBuilderMethods.appendObject
              || invokedMethod == factory.stringBufferMethods.appendObject) {
            // Rewrites stringBuilder.append(enumInstance) as if it was
            // stringBuilder.append(String.valueOf(unboxedEnumInstance));
            Value enumArg = invoke.getArgument(1);
            DexType enumArgType = getEnumTypeOrNull(enumArg, convertedEnums);
            if (enumArgType != null) {
              ProgramMethod stringValueOfMethod =
                  getLocalUtilityClass(enumArgType).ensureStringValueOfMethod(appView);
              InvokeStatic toStringInvoke =
                  InvokeStatic.builder()
                      .setMethod(stringValueOfMethod)
                      .setSingleArgument(enumArg)
                      .setFreshOutValue(appView, code)
                      .setPosition(invoke)
                      .build();
              DexMethod newAppendMethod =
                  invokedMethod == factory.stringBuilderMethods.appendObject
                      ? factory.stringBuilderMethods.appendString
                      : factory.stringBufferMethods.appendString;
              List<Value> arguments =
                  ImmutableList.of(invoke.getReceiver(), toStringInvoke.outValue());
              InvokeVirtual invokeAppendString =
                  new InvokeVirtual(newAppendMethod, invoke.clearOutValue(), arguments);
              invokeAppendString.setPosition(invoke.getPosition());
              iterator.replaceCurrentInstruction(toStringInvoke);
              if (block.hasCatchHandlers()) {
                iterator
                    .splitCopyCatchHandlers(code, blocks, appView.options())
                    .listIterator(code)
                    .add(invokeAppendString);
              } else {
                iterator.add(invokeAppendString);
              }
              continue;
            }
          }
        } else if (instruction.isInvokeStatic()) {
          rewriteInvokeStatic(
              instruction.asInvokeStatic(),
              code,
              context,
              convertedEnums,
              iterator,
              affectedPhis,
              methodProcessor);
        }
        if (instruction.isStaticGet()) {
          StaticGet staticGet = instruction.asStaticGet();
          DexField field = staticGet.getField();
          DexType holder = field.holder;
          if (!unboxedEnumsData.isUnboxedEnum(holder)) {
            continue;
          }
          if (staticGet.hasUnusedOutValue()) {
            iterator.removeOrReplaceByDebugLocalRead();
            continue;
          }
          affectedPhis.addAll(staticGet.outValue().uniquePhiUsers());
          if (unboxedEnumsData.matchesValuesField(field)) {
            // Load the size of this enum's $VALUES array before the current instruction.
            iterator.previous();
            Value sizeValue =
                iterator.insertConstIntInstruction(
                    code, options, unboxedEnumsData.getValuesSize(holder));
            iterator.next();

            // Replace Enum.$VALUES by a call to: int[] SharedUtilityClass.values(int size).
            InvokeStatic invoke =
                InvokeStatic.builder()
                    .setMethod(getSharedUtilityClass().getValuesMethod())
                    .setFreshOutValue(appView, code)
                    .setSingleArgument(sizeValue)
                    .build();
            iterator.replaceCurrentInstruction(invoke);

            convertedEnums.put(invoke, holder);

            // Check if the call to SharedUtilityClass.values(size) is followed by a call to
            // clone(). If so, remove it, since SharedUtilityClass.values(size) returns a fresh
            // array. This is needed because the javac generated implementation of MyEnum.values()
            // is implemented as `return $VALUES.clone()`.
            removeRedundantValuesArrayCloning(invoke, instructionsToRemove, seenBlocks);
          } else if (unboxedEnumsData.hasUnboxedValueFor(field)) {
            // Replace by ordinal + 1 for null check (null is 0).
            ConstNumber intConstant =
                code.createIntConstant(unboxedEnumsData.getUnboxedValue(field));
            iterator.replaceCurrentInstruction(intConstant);
            convertedEnums.put(intConstant, holder);
          } else {
            // Nothing to do, handled by lens code rewriting.
          }
        }

        if (instruction.isInstanceGet()) {
          InstanceGet instanceGet = instruction.asInstanceGet();
          DexType holder = instanceGet.getField().holder;
          if (unboxedEnumsData.isUnboxedEnum(holder)) {
            ProgramMethod fieldMethod =
                ensureInstanceFieldMethod(instanceGet.getField(), methodProcessor);
            Value rewrittenOutValue =
                code.createValue(
                    TypeElement.fromDexType(fieldMethod.getReturnType(), maybeNull(), appView));
            InvokeStatic invoke =
                new InvokeStatic(
                    fieldMethod.getReference(),
                    rewrittenOutValue,
                    ImmutableList.of(instanceGet.object()));
            iterator.replaceCurrentInstruction(invoke);
            if (unboxedEnumsData.isUnboxedEnum(instanceGet.getField().type)) {
              convertedEnums.put(invoke, instanceGet.getField().type);
            }
          }
        }

        // Rewrite array accesses from MyEnum[] (OBJECT) to int[] (INT).
        if (instruction.isArrayAccess()) {
          ArrayAccess arrayAccess = instruction.asArrayAccess();
          DexType enumType = getEnumTypeOrNull(arrayAccess, convertedEnums);
          if (enumType != null) {
            if (arrayAccess.hasOutValue()) {
              affectedPhis.addAll(arrayAccess.outValue().uniquePhiUsers());
            }
            arrayAccess = arrayAccess.withMemberType(MemberType.INT);
            iterator.replaceCurrentInstruction(arrayAccess);
            convertedEnums.put(arrayAccess, enumType);
          }
          assert validateArrayAccess(arrayAccess);
        }

        if (instruction.isNewUnboxedEnumInstance()) {
          NewUnboxedEnumInstance newUnboxedEnumInstance = instruction.asNewUnboxedEnumInstance();
          assert unboxedEnumsData.isUnboxedEnum(newUnboxedEnumInstance.getType());
          iterator.replaceCurrentInstruction(
              code.createIntConstant(
                  EnumUnboxerImpl.ordinalToUnboxedInt(newUnboxedEnumInstance.getOrdinal())));
        }
      }
    }
    assert code.isConsistentSSABeforeTypesAreCorrect(appView);
    return affectedPhis;
  }

  private void rewriteInvokeStatic(
      InvokeStatic invoke,
      IRCode code,
      ProgramMethod context,
      Map<Instruction, DexType> convertedEnums,
      InstructionListIterator instructionIterator,
      Set<Phi> affectedPhis,
      MethodProcessor methodProcessor) {
    DexClassAndMethod singleTarget = invoke.lookupSingleTarget(appView, context);
    if (singleTarget == null) {
      return;
    }
    DexMethod invokedMethod = singleTarget.getReference();

    // Calls to java.lang.Enum.
    if (invokedMethod.getHolderType() == factory.enumType) {
      if (invokedMethod == factory.enumMembers.valueOf) {
        if (!invoke.getFirstArgument().isConstClass()) {
          return;
        }
        DexType enumType =
            invoke.getFirstArgument().getConstInstruction().asConstClass().getValue();
        if (!unboxedEnumsData.isUnboxedEnum(enumType)) {
          return;
        }
        ProgramMethod valueOfMethod = getLocalUtilityClass(enumType).ensureValueOfMethod(appView);
        Value outValue = invoke.outValue();
        Value rewrittenOutValue = null;
        if (outValue != null) {
          rewrittenOutValue = code.createValue(TypeElement.getInt());
          affectedPhis.addAll(outValue.uniquePhiUsers());
        }
        InvokeStatic replacement =
            new InvokeStatic(
                valueOfMethod.getReference(),
                rewrittenOutValue,
                Collections.singletonList(invoke.inValues().get(1)));
        instructionIterator.replaceCurrentInstruction(replacement);
        convertedEnums.put(replacement, enumType);
      }
      return;
    }

    // Calls to java.lang.Objects.
    if (invokedMethod.getHolderType() == factory.objectsType) {
      if (invokedMethod == factory.objectsMethods.requireNonNull) {
        assert invoke.arguments().size() == 1;
        Value argument = invoke.getFirstArgument();
        DexType enumType = getEnumTypeOrNull(argument, convertedEnums);
        if (enumType != null) {
          rewriteNullCheck(instructionIterator, invoke);
        }
      } else if (invokedMethod == factory.objectsMethods.requireNonNullWithMessage) {
        assert invoke.arguments().size() == 2;
        Value argument = invoke.getFirstArgument();
        DexType enumType = getEnumTypeOrNull(argument, convertedEnums);
        if (enumType != null) {
          replaceEnumInvoke(
              instructionIterator,
              invoke,
              getSharedUtilityClass().ensureCheckNotZeroWithMessageMethod(appView));
        }
      }
      return;
    }

    // Calls to java.lang.String.
    if (invokedMethod.getHolderType() == factory.stringType) {
      if (invokedMethod == factory.stringMembers.valueOf) {
        assert invoke.arguments().size() == 1;
        Value argument = invoke.getFirstArgument();
        DexType enumType = getEnumTypeOrNull(argument, convertedEnums);
        if (enumType != null) {
          ProgramMethod stringValueOfMethod =
              getLocalUtilityClass(enumType).ensureStringValueOfMethod(appView);
          instructionIterator.replaceCurrentInstruction(
              new InvokeStatic(
                  stringValueOfMethod.getReference(), invoke.outValue(), invoke.arguments()));
        }
      }
      return;
    }

    // Calls to java.lang.System.
    if (invokedMethod.getHolderType() == factory.javaLangSystemType) {
      if (invokedMethod == factory.javaLangSystemMembers.arraycopy) {
        // Intentionally empty.
      } else if (invokedMethod == factory.javaLangSystemMembers.identityHashCode) {
        assert invoke.arguments().size() == 1;
        Value argument = invoke.getFirstArgument();
        DexType enumType = getEnumTypeOrNull(argument, convertedEnums);
        if (enumType != null) {
          invoke.outValue().replaceUsers(argument);
          instructionIterator.removeOrReplaceByDebugLocalRead();
        }
      }
      return;
    }

    if (singleTarget.isProgramMethod()
        && checkNotNullToCheckNotZeroMapping.containsKey(singleTarget.getReference())) {
      DexMethod checkNotZeroMethodReference =
          checkNotNullToCheckNotZeroMapping.get(singleTarget.getReference());
      ProgramMethod checkNotZeroMethod =
          appView
              .appInfo()
              .resolveMethodOnClassHolder(checkNotZeroMethodReference)
              .getResolvedProgramMethod();
      if (checkNotZeroMethod != null) {
        EnumUnboxerMethodClassification classification =
            checkNotZeroMethod.getOptimizationInfo().getEnumUnboxerMethodClassification();
        if (classification.isCheckNotNullClassification()) {
          CheckNotNullEnumUnboxerMethodClassification checkNotNullClassification =
              classification.asCheckNotNullClassification();
          Value argument = invoke.getArgument(checkNotNullClassification.getArgumentIndex());
          DexType enumType = getEnumTypeOrNull(argument, convertedEnums);
          if (enumType != null) {
            InvokeStatic replacement =
                InvokeStatic.builder()
                    .setMethod(checkNotZeroMethod)
                    .setArguments(invoke.arguments())
                    .setPosition(invoke.getPosition())
                    .build();
            instructionIterator.replaceCurrentInstruction(replacement);
            convertedEnums.put(replacement, enumType);
          }
        } else {
          assert false;
        }
      } else {
        assert false;
      }
    }
  }

  public void rewriteNullCheck(InstructionListIterator iterator, InvokeMethod invoke) {
    assert !invoke.hasOutValue() || !invoke.outValue().hasAnyUsers();
    replaceEnumInvoke(iterator, invoke, getSharedUtilityClass().ensureCheckNotZeroMethod(appView));
  }

  private void removeRedundantValuesArrayCloning(
      InvokeStatic invoke, Set<Instruction> instructionsToRemove, Set<BasicBlock> seenBlocks) {
    for (Instruction user : invoke.outValue().aliasedUsers()) {
      if (user.isInvokeVirtual()) {
        InvokeVirtual cloneCandidate = user.asInvokeVirtual();
        if (cloneCandidate.getInvokedMethod().match(appView.dexItemFactory().objectMembers.clone)) {
          if (cloneCandidate.hasOutValue()) {
            cloneCandidate.outValue().replaceUsers(invoke.outValue());
          }
          BasicBlock cloneBlock = cloneCandidate.getBlock();
          if (cloneBlock == invoke.getBlock() || !seenBlocks.contains(cloneBlock)) {
            instructionsToRemove.add(cloneCandidate);
          } else {
            cloneBlock.removeInstruction(cloneCandidate);
          }
        }
      }
    }
  }

  private void rewriteNameMethod(
      InstructionListIterator iterator,
      InvokeMethodWithReceiver invoke,
      DexType enumType,
      MethodProcessor methodProcessor) {
    ProgramMethod toStringMethod =
        getLocalUtilityClass(enumType)
            .ensureGetInstanceFieldMethod(appView, factory.enumMembers.nameField);
    iterator.replaceCurrentInstruction(
        new InvokeStatic(toStringMethod.getReference(), invoke.outValue(), invoke.arguments()));
  }

  private Value fixNullsInBlockPhis(IRCode code, BasicBlock block, Value zeroConstValue) {
    for (Phi phi : block.getPhis()) {
      if (getEnumTypeOrNull(phi.getType()) != null) {
        for (int i = 0; i < phi.getOperands().size(); i++) {
          Value operand = phi.getOperand(i);
          if (operand.getType().isNullType()) {
            if (zeroConstValue == null) {
              zeroConstValue = insertConstZero(code);
            }
            phi.replaceOperandAt(i, zeroConstValue);
          }
        }
      }
    }
    return zeroConstValue;
  }

  private Value insertConstZero(IRCode code) {
    InstructionListIterator iterator = code.entryBlock().listIterator(code);
    while (iterator.hasNext() && iterator.peekNext().isArgument()) {
      iterator.next();
    }
    return iterator.insertConstIntInstruction(code, options, 0);
  }

  private ProgramMethod ensureInstanceFieldMethod(DexField field, MethodProcessor methodProcessor) {
    EnumInstanceFieldKnownData enumFieldKnownData =
        unboxedEnumsData.getInstanceFieldData(field.holder, field);
    if (enumFieldKnownData.isOrdinal()) {
      return getSharedUtilityClass().ensureOrdinalMethod(appView);
    }
    return getLocalUtilityClass(field.getHolderType()).ensureGetInstanceFieldMethod(appView, field);
  }

  private void replaceEnumInvoke(
      InstructionListIterator iterator, InvokeMethod invoke, ProgramMethod method) {
    InvokeStatic replacement =
        new InvokeStatic(
            method.getReference(),
            invoke.hasUnusedOutValue() ? null : invoke.outValue(),
            invoke.arguments());
    assert !replacement.hasOutValue()
        || !replacement.getInvokedMethod().getReturnType().isVoidType();
    iterator.replaceCurrentInstruction(replacement);
  }

  private boolean validateArrayAccess(ArrayAccess arrayAccess) {
    ArrayTypeElement arrayType = arrayAccess.array().getType().asArrayType();
    if (arrayType == null) {
      assert arrayAccess.array().getType().isNullType();
      return true;
    }
    assert arrayAccess.getMemberType() != MemberType.OBJECT
        || arrayType.getNesting() > 1
        || arrayType.getBaseType().isReferenceType();
    return true;
  }

  private DexType getEnumTypeOrNull(Value receiver, Map<Instruction, DexType> convertedEnums) {
    TypeElement type = receiver.getType();
    if (type.isInt() || (type.isArrayType() && type.asArrayType().getBaseType().isInt())) {
      return receiver.isPhi() ? null : convertedEnums.get(receiver.getDefinition());
    }
    return getEnumTypeOrNull(type);
  }

  private DexType getEnumTypeOrNull(TypeElement type) {
    if (!type.isClassType()) {
      return null;
    }
    return getEnumTypeOrNull(type.asClassType().getClassType());
  }

  private DexType getEnumTypeOrNull(DexType type) {
    return unboxedEnumsData.isUnboxedEnum(type) ? type : null;
  }

  private DexType getEnumTypeOrNull(
      ArrayAccess arrayAccess, Map<Instruction, DexType> convertedEnums) {
    ArrayTypeElement arrayType = arrayAccess.array().getType().asArrayType();
    if (arrayType == null) {
      assert arrayAccess.array().getType().isNullType();
      return null;
    }
    if (arrayType.getNesting() != 1) {
      return null;
    }
    TypeElement baseType = arrayType.getBaseType();
    if (baseType.isClassType()) {
      DexType classType = baseType.asClassType().getClassType();
      return unboxedEnumsData.isUnboxedEnum(classType) ? classType : null;
    }
    return getEnumTypeOrNull(arrayAccess.array(), convertedEnums);
  }
}
