// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize;

import static com.android.tools.r8.graph.DexProgramClass.asProgramClassOrNull;
import static com.android.tools.r8.ir.analysis.type.Nullability.definitelyNotNull;
import static com.android.tools.r8.ir.analysis.type.Nullability.maybeNull;

import com.android.tools.r8.algorithms.scc.SCC;
import com.android.tools.r8.contexts.CompilationContext.MethodProcessingContext;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AccessControl;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DebugLocalInfo;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexClassAndMethod;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.FieldResolutionResult.SingleProgramFieldResolutionResult;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.horizontalclassmerging.HorizontalClassMergerUtils;
import com.android.tools.r8.ir.analysis.equivalence.BasicBlockBehavioralSubsumption;
import com.android.tools.r8.ir.analysis.type.DynamicTypeWithUpperBound;
import com.android.tools.r8.ir.analysis.type.Nullability;
import com.android.tools.r8.ir.analysis.type.TypeAnalysis;
import com.android.tools.r8.ir.analysis.type.TypeElement;
import com.android.tools.r8.ir.analysis.type.TypeUtils;
import com.android.tools.r8.ir.analysis.value.AbstractValue;
import com.android.tools.r8.ir.analysis.value.ConstantOrNonConstantNumberValue;
import com.android.tools.r8.ir.analysis.value.SingleConstClassValue;
import com.android.tools.r8.ir.analysis.value.SingleFieldValue;
import com.android.tools.r8.ir.code.ArrayLength;
import com.android.tools.r8.ir.code.ArrayPut;
import com.android.tools.r8.ir.code.Assume;
import com.android.tools.r8.ir.code.BasicBlock;
import com.android.tools.r8.ir.code.Binop;
import com.android.tools.r8.ir.code.CatchHandlers;
import com.android.tools.r8.ir.code.CatchHandlers.CatchHandler;
import com.android.tools.r8.ir.code.CheckCast;
import com.android.tools.r8.ir.code.ConstInstruction;
import com.android.tools.r8.ir.code.ConstNumber;
import com.android.tools.r8.ir.code.ConstString;
import com.android.tools.r8.ir.code.DebugLocalWrite;
import com.android.tools.r8.ir.code.DebugLocalsChange;
import com.android.tools.r8.ir.code.DominatorTree;
import com.android.tools.r8.ir.code.Goto;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.IRMetadata;
import com.android.tools.r8.ir.code.If;
import com.android.tools.r8.ir.code.If.Type;
import com.android.tools.r8.ir.code.InstanceFieldInstruction;
import com.android.tools.r8.ir.code.InstanceGet;
import com.android.tools.r8.ir.code.InstanceOf;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.Instruction.SideEffectAssumption;
import com.android.tools.r8.ir.code.InstructionIterator;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.InstructionOrPhi;
import com.android.tools.r8.ir.code.IntSwitch;
import com.android.tools.r8.ir.code.Invoke;
import com.android.tools.r8.ir.code.InvokeDirect;
import com.android.tools.r8.ir.code.InvokeInterface;
import com.android.tools.r8.ir.code.InvokeMethod;
import com.android.tools.r8.ir.code.InvokeMethodWithReceiver;
import com.android.tools.r8.ir.code.InvokeNewArray;
import com.android.tools.r8.ir.code.InvokeStatic;
import com.android.tools.r8.ir.code.InvokeVirtual;
import com.android.tools.r8.ir.code.Move;
import com.android.tools.r8.ir.code.NewArrayEmpty;
import com.android.tools.r8.ir.code.NewArrayFilledData;
import com.android.tools.r8.ir.code.NewInstance;
import com.android.tools.r8.ir.code.NumericType;
import com.android.tools.r8.ir.code.Phi;
import com.android.tools.r8.ir.code.Position;
import com.android.tools.r8.ir.code.Position.SyntheticPosition;
import com.android.tools.r8.ir.code.StaticGet;
import com.android.tools.r8.ir.code.Switch;
import com.android.tools.r8.ir.code.Throw;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.code.ValueType;
import com.android.tools.r8.ir.code.Xor;
import com.android.tools.r8.ir.conversion.MethodProcessor;
import com.android.tools.r8.ir.optimize.UtilityMethodsForCodeOptimizations.UtilityMethodForCodeOptimizations;
import com.android.tools.r8.ir.optimize.controlflow.SwitchCaseAnalyzer;
import com.android.tools.r8.ir.optimize.info.MethodOptimizationInfo;
import com.android.tools.r8.ir.regalloc.LinearScanRegisterAllocator;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.BooleanUtils;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.InternalOutputMode;
import com.android.tools.r8.utils.LongInterval;
import com.android.tools.r8.utils.SetUtils;
import com.google.common.base.Equivalence;
import com.google.common.base.Equivalence.Wrapper;
import com.google.common.base.Suppliers;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Sets;
import com.google.common.collect.Streams;
import it.unimi.dsi.fastutil.ints.Int2IntMap;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceAVLTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceMap.Entry;
import it.unimi.dsi.fastutil.ints.Int2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceSortedMap;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.ints.IntIterator;
import it.unimi.dsi.fastutil.ints.IntList;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.Long2ReferenceOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class CodeRewriter {

  private enum InstanceOfResult {
    UNKNOWN,
    TRUE,
    FALSE
  }

  private static final int MAX_FILL_ARRAY_SIZE = 8 * Constants.KILOBYTE;
  // This constant was determined by experimentation.
  private static final int STOP_SHARED_CONSTANT_THRESHOLD = 50;

  private final AppView<?> appView;
  private final DexItemFactory dexItemFactory;
  private final InternalOptions options;

  public CodeRewriter(AppView<?> appView) {
    this.appView = appView;
    this.options = appView.options();
    this.dexItemFactory = appView.dexItemFactory();
  }

  public static void removeAssumeInstructions(AppView<?> appView, IRCode code) {
    // We need to update the types of all values whose definitions depend on a non-null value.
    // This is needed to preserve soundness of the types after the Assume instructions have been
    // removed.
    //
    // As an example, consider a check-cast instruction on the form "z = (T) y". If y used to be
    // defined by a NonNull instruction, then the type analysis could have used this information
    // to mark z as non-null. However, cleanupNonNull() have now replaced y by a nullable value x.
    // Since z is defined as "z = (T) x", and x is nullable, it is no longer sound to have that z
    // is not nullable. This is fixed by rerunning the type analysis for the affected values.
    Set<Value> valuesThatRequireWidening = Sets.newIdentityHashSet();

    InstructionListIterator it = code.instructionListIterator();
    boolean needToCheckTrivialPhis = false;
    while (it.hasNext()) {
      Instruction instruction = it.next();

      // The following deletes Assume instructions and replaces any specialized value by its
      // original value:
      //   y <- Assume(x)
      //   ...
      //   y.foo()
      //
      // becomes:
      //
      //   x.foo()
      if (instruction.isAssume()) {
        Assume assumeInstruction = instruction.asAssume();
        Value src = assumeInstruction.src();
        Value dest = assumeInstruction.outValue();

        valuesThatRequireWidening.addAll(dest.affectedValues());

        // Replace `dest` by `src`.
        needToCheckTrivialPhis |= dest.numberOfPhiUsers() > 0;
        dest.replaceUsers(src);
        it.remove();
      }
    }

    // Assume insertion may introduce phis, e.g.,
    //   y <- Assume(x)
    //   ...
    //   z <- phi(x, y)
    //
    // Therefore, Assume elimination may result in a trivial phi:
    //   z <- phi(x, x)
    if (needToCheckTrivialPhis) {
      code.removeAllDeadAndTrivialPhis(valuesThatRequireWidening);
    }

    if (!valuesThatRequireWidening.isEmpty()) {
      new TypeAnalysis(appView).widening(valuesThatRequireWidening);
    }

    assert Streams.stream(code.instructions()).noneMatch(Instruction::isAssume);
  }

  private static boolean removedTrivialGotos(IRCode code) {
    ListIterator<BasicBlock> iterator = code.listIterator();
    assert iterator.hasNext();
    BasicBlock block = iterator.next();
    BasicBlock nextBlock;
    do {
      nextBlock = iterator.hasNext() ? iterator.next() : null;
      // Trivial goto block are only kept if they are self-targeting or are targeted by
      // fallthroughs.
      BasicBlock blk = block;  // Additional local for lambda below.
      assert !block.isTrivialGoto()
          || block.exit().asGoto().getTarget() == block
          || code.entryBlock() == block
          || block.getPredecessors().stream().anyMatch((b) -> b.exit().fallthroughBlock() == blk);
      // Trivial goto blocks never target the next block (in that case there should just be a
      // fallthrough).
      assert !block.isTrivialGoto() || block.exit().asGoto().getTarget() != nextBlock;
      block = nextBlock;
    } while (block != null);
    return true;
  }

  // Rewrite 'throw new NullPointerException()' to 'throw null'.
  public void rewriteThrowNullPointerException(IRCode code) {
    boolean shouldRemoveUnreachableBlocks = false;
    for (BasicBlock block : code.blocks) {
      InstructionListIterator it = block.listIterator(code);
      while (it.hasNext()) {
        Instruction instruction = it.next();

        // Check for the patterns 'if (x == null) throw null' and
        // 'if (x == null) throw new NullPointerException()'.
        if (instruction.isIf()) {
          if (appView
              .dexItemFactory()
              .objectsMethods
              .isRequireNonNullMethod(code.method().getReference())) {
            continue;
          }

          If ifInstruction = instruction.asIf();
          if (!ifInstruction.isZeroTest()) {
            continue;
          }

          Value value = ifInstruction.lhs();
          if (!value.getType().isReferenceType()) {
            assert value.getType().isPrimitiveType();
            continue;
          }

          BasicBlock valueIsNullTarget = ifInstruction.targetFromCondition(0);
          if (valueIsNullTarget.getPredecessors().size() != 1
              || !valueIsNullTarget.exit().isThrow()) {
            continue;
          }

          Throw throwInstruction = valueIsNullTarget.exit().asThrow();
          Value exceptionValue = throwInstruction.exception().getAliasedValue();
          if (!exceptionValue.isConstZero()
              && exceptionValue.isDefinedByInstructionSatisfying(Instruction::isNewInstance)) {
            NewInstance newInstance = exceptionValue.definition.asNewInstance();
            if (newInstance.clazz != dexItemFactory.npeType) {
              continue;
            }
            if (newInstance.outValue().numberOfAllUsers() != 2) {
              continue; // Could be mutated before it is thrown.
            }
            InvokeDirect constructorCall = newInstance.getUniqueConstructorInvoke(dexItemFactory);
            if (constructorCall == null) {
              continue;
            }
            if (constructorCall.getInvokedMethod() != dexItemFactory.npeMethods.init) {
              continue;
            }
          } else if (!exceptionValue.isConstZero()) {
            continue;
          }

          boolean canDetachValueIsNullTarget = true;
          for (Instruction i : valueIsNullTarget.instructionsBefore(throwInstruction)) {
            if (!i.isBlockLocalInstructionWithoutSideEffects(appView, code.context())) {
              canDetachValueIsNullTarget = false;
              break;
            }
          }
          if (!canDetachValueIsNullTarget) {
            continue;
          }

          insertNotNullCheck(
              block,
              it,
              ifInstruction,
              ifInstruction.targetFromCondition(1),
              valueIsNullTarget,
              throwInstruction.getPosition());
          shouldRemoveUnreachableBlocks = true;
        }

        // Check for 'new-instance NullPointerException' with 2 users, not declaring a local and
        // not ending the scope of any locals.
        if (instruction.isNewInstance()
            && instruction.asNewInstance().clazz == dexItemFactory.npeType
            && instruction.outValue().numberOfAllUsers() == 2
            && !instruction.outValue().hasLocalInfo()
            && instruction.getDebugValues().isEmpty()) {
          if (it.hasNext()) {
            Instruction instruction2 = it.next();
            // Check for 'invoke NullPointerException.init() not ending the scope of any locals
            // and with the result of the first instruction as the argument. Also check that
            // the two first instructions have the same position.
            if (instruction2.isInvokeDirect()
                && instruction2.getDebugValues().isEmpty()) {
              InvokeDirect invokeDirect = instruction2.asInvokeDirect();
              if (invokeDirect.getInvokedMethod() == dexItemFactory.npeMethods.init
                  && invokeDirect.getReceiver() == instruction.outValue()
                  && invokeDirect.arguments().size() == 1
                  && invokeDirect.getPosition() == instruction.getPosition()) {
                if (it.hasNext()) {
                  Instruction instruction3 = it.next();
                  // Finally check that the last instruction is a throw of the initialized
                  // exception object and replace with 'throw null' if so.
                  if (instruction3.isThrow()
                      && instruction3.asThrow().exception() == instruction.outValue()) {
                    // Create const 0 with null type and a throw using that value.
                    Instruction nullPointer = code.createConstNull();
                    Instruction throwInstruction = new Throw(nullPointer.outValue());
                    // Preserve positions: we have checked that the first two original instructions
                    // have the same position.
                    assert instruction.getPosition() == instruction2.getPosition();
                    nullPointer.setPosition(instruction.getPosition());
                    throwInstruction.setPosition(instruction3.getPosition());
                    // Copy debug values from original throw to new throw to correctly end scope
                    // of locals.
                    instruction3.moveDebugValues(throwInstruction);
                    // Remove the three original instructions.
                    it.remove();
                    it.previous();
                    it.remove();
                    it.previous();
                    it.remove();
                    // Replace them with 'const 0' and 'throw'.
                    it.add(nullPointer);
                    it.add(throwInstruction);
                  }
                }
              }
            }
          }
        }
      }
    }
    if (shouldRemoveUnreachableBlocks) {
      Set<Value> affectedValues = code.removeUnreachableBlocks();
      if (!affectedValues.isEmpty()) {
        new TypeAnalysis(appView).narrowing(affectedValues);
      }
    }
    assert code.isConsistentSSA(appView);
  }

  public static boolean isFallthroughBlock(BasicBlock block) {
    for (BasicBlock pred : block.getPredecessors()) {
      if (pred.exit().fallthroughBlock() == block) {
        return true;
      }
    }
    return false;
  }

  private static void collapseTrivialGoto(
      IRCode code, BasicBlock block, BasicBlock nextBlock, List<BasicBlock> blocksToRemove) {

    // This is the base case for GOTO loops.
    if (block.exit().asGoto().getTarget() == block) {
      return;
    }

    BasicBlock target = block.endOfGotoChain();

    boolean needed = false;

    if (target == null) {
      // This implies we are in a loop of GOTOs. In that case, we will iteratively remove each
      // trivial GOTO one-by-one until the above base case (one block targeting itself) is left.
      target = block.exit().asGoto().getTarget();
    }

    if (target != nextBlock) {
      // Not targeting the fallthrough block, determine if we need this goto. We need it if
      // a fallthrough can hit this block. That is the case if the block is the entry block
      // or if one of the predecessors fall through to the block.
      needed = code.entryBlock() == block || isFallthroughBlock(block);
    }

    if (!needed) {
      blocksToRemove.add(block);
      unlinkTrivialGotoBlock(block, target);
    }
  }

  public static void unlinkTrivialGotoBlock(BasicBlock block, BasicBlock target) {
    assert block.isTrivialGoto();
    for (BasicBlock pred : block.getPredecessors()) {
      pred.replaceSuccessor(block, target);
    }
    for (BasicBlock succ : block.getSuccessors()) {
      succ.getMutablePredecessors().remove(block);
    }
    for (BasicBlock pred : block.getPredecessors()) {
      if (!target.getPredecessors().contains(pred)) {
        target.getMutablePredecessors().add(pred);
      }
    }
  }

  private static void collapseIfTrueTarget(BasicBlock block) {
    If insn = block.exit().asIf();
    BasicBlock target = insn.getTrueTarget();
    BasicBlock newTarget = target.endOfGotoChain();
    BasicBlock fallthrough = insn.fallthroughBlock();
    BasicBlock newFallthrough = fallthrough.endOfGotoChain();
    if (newTarget != null && target != newTarget) {
      insn.getBlock().replaceSuccessor(target, newTarget);
      target.getMutablePredecessors().remove(block);
      if (!newTarget.getPredecessors().contains(block)) {
        newTarget.getMutablePredecessors().add(block);
      }
    }
    if (block.exit().isIf()) {
      insn = block.exit().asIf();
      if (insn.getTrueTarget() == newFallthrough) {
        // Replace if with the same true and fallthrough target with a goto to the fallthrough.
        block.replaceSuccessor(insn.getTrueTarget(), fallthrough);
        assert block.exit().isGoto();
        assert block.exit().asGoto().getTarget() == fallthrough;
      }
    }
  }

  private static void collapseNonFallthroughSwitchTargets(BasicBlock block) {
    Switch insn = block.exit().asSwitch();
    BasicBlock fallthroughBlock = insn.fallthroughBlock();
    Set<BasicBlock> replacedBlocks = new HashSet<>();
    for (int j = 0; j < insn.targetBlockIndices().length; j++) {
      BasicBlock target = insn.targetBlock(j);
      if (target != fallthroughBlock) {
        BasicBlock newTarget = target.endOfGotoChain();
        if (newTarget != null && target != newTarget && !replacedBlocks.contains(target)) {
          insn.getBlock().replaceSuccessor(target, newTarget);
          target.getMutablePredecessors().remove(block);
          if (!newTarget.getPredecessors().contains(block)) {
            newTarget.getMutablePredecessors().add(block);
          }
          replacedBlocks.add(target);
        }
      }
    }
  }

  // TODO(sgjesse); Move this somewhere else, and reuse it for some of the other switch rewritings.
  public abstract static class InstructionBuilder<T> {
    protected int blockNumber;
    protected final Position position;

    protected InstructionBuilder(Position position) {
      this.position = position;
    }

    public abstract T self();

    public T setBlockNumber(int blockNumber) {
      this.blockNumber = blockNumber;
      return self();
    }
  }

  public static class SwitchBuilder extends InstructionBuilder<SwitchBuilder> {
    private Value value;
    private final Int2ReferenceSortedMap<BasicBlock> keyToTarget = new Int2ReferenceAVLTreeMap<>();
    private BasicBlock fallthrough;

    public SwitchBuilder(Position position) {
      super(position);
    }

    @Override
    public SwitchBuilder self() {
      return this;
    }

    public SwitchBuilder setValue(Value value) {
      this.value = value;
      return  this;
    }

    public SwitchBuilder addKeyAndTarget(int key, BasicBlock target) {
      keyToTarget.put(key, target);
      return this;
    }

    public SwitchBuilder setFallthrough(BasicBlock fallthrough) {
      this.fallthrough = fallthrough;
      return this;
    }

    public BasicBlock build(IRMetadata metadata) {
      final int NOT_FOUND = -1;
      Object2IntMap<BasicBlock> targetToSuccessorIndex = new Object2IntLinkedOpenHashMap<>();
      targetToSuccessorIndex.defaultReturnValue(NOT_FOUND);

      int[] keys = new int[keyToTarget.size()];
      int[] targetBlockIndices = new int[keyToTarget.size()];
      // Sort keys descending.
      int count = 0;
      IntIterator iter = keyToTarget.keySet().iterator();
      while (iter.hasNext()) {
        int key = iter.nextInt();
        BasicBlock target = keyToTarget.get(key);
        Integer targetIndex =
            targetToSuccessorIndex.computeIfAbsent(target, b -> targetToSuccessorIndex.size());
        keys[count] = key;
        targetBlockIndices[count] = targetIndex;
        count++;
      }
      Integer fallthroughIndex =
          targetToSuccessorIndex.computeIfAbsent(fallthrough, b -> targetToSuccessorIndex.size());
      IntSwitch newSwitch = new IntSwitch(value, keys, targetBlockIndices, fallthroughIndex);
      newSwitch.setPosition(position);
      BasicBlock newSwitchBlock = BasicBlock.createSwitchBlock(blockNumber, newSwitch, metadata);
      for (BasicBlock successor : targetToSuccessorIndex.keySet()) {
        newSwitchBlock.link(successor);
      }
      return newSwitchBlock;
    }
  }

  public static class IfBuilder extends InstructionBuilder<IfBuilder> {
    private final IRCode code;
    private Value left;
    private int right;
    private BasicBlock target;
    private BasicBlock fallthrough;

    public IfBuilder(Position position, IRCode code) {
      super(position);
      this.code = code;
    }

    @Override
    public IfBuilder self() {
      return this;
    }

    public IfBuilder setLeft(Value left) {
      this.left = left;
      return  this;
    }

    public IfBuilder setRight(int right) {
      this.right = right;
      return  this;
    }

    public IfBuilder setTarget(BasicBlock target) {
      this.target = target;
      return this;
    }

    public IfBuilder setFallthrough(BasicBlock fallthrough) {
      this.fallthrough = fallthrough;
      return this;
    }

    public BasicBlock build() {
      assert target != null;
      assert fallthrough != null;
      If newIf;
      BasicBlock ifBlock;
      if (right != 0) {
        ConstNumber rightConst = code.createIntConstant(right);
        rightConst.setPosition(position);
        newIf = new If(Type.EQ, ImmutableList.of(left, rightConst.dest()));
        ifBlock = BasicBlock.createIfBlock(blockNumber, newIf, code.metadata(), rightConst);
      } else {
        newIf = new If(Type.EQ, left);
        ifBlock = BasicBlock.createIfBlock(blockNumber, newIf, code.metadata());
      }
      newIf.setPosition(position);
      ifBlock.link(target);
      ifBlock.link(fallthrough);
      return ifBlock;
    }
  }

  /**
   * Covert the switch instruction to a sequence of if instructions checking for a specified set of
   * keys, followed by a new switch with the remaining keys.
   */
  void convertSwitchToSwitchAndIfs(
      IRCode code,
      ListIterator<BasicBlock> blocksIterator,
      BasicBlock originalBlock,
      InstructionListIterator iterator,
      IntSwitch theSwitch,
      List<IntList> switches,
      IntList keysToRemove) {

    Position position = theSwitch.getPosition();

    // Extract the information from the switch before removing it.
    Int2ReferenceSortedMap<BasicBlock> keyToTarget = theSwitch.getKeyToTargetMap();

    // Keep track of the current fallthrough, starting with the original.
    BasicBlock fallthroughBlock = theSwitch.fallthroughBlock();

    // Split the switch instruction into its own block and remove it.
    iterator.previous();
    BasicBlock originalSwitchBlock = iterator.split(code, blocksIterator);
    assert !originalSwitchBlock.hasCatchHandlers();
    assert originalSwitchBlock.getInstructions().size() == 1;
    assert originalBlock.exit().isGoto();
    theSwitch.moveDebugValues(originalBlock.exit());
    blocksIterator.remove();
    theSwitch.getBlock().detachAllSuccessors();
    BasicBlock block = theSwitch.getBlock().unlinkSinglePredecessor();
    assert theSwitch.getBlock().getPredecessors().size() == 0;
    assert theSwitch.getBlock().getSuccessors().size() == 0;
    assert block == originalBlock;

    // Collect the new blocks for adding to the block list.
    LinkedList<BasicBlock> newBlocks = new LinkedList<>();

    // Build the switch-blocks backwards, to always have the fallthrough block in hand.
    for (int i = switches.size() - 1; i >= 0; i--) {
      SwitchBuilder switchBuilder = new SwitchBuilder(position);
      switchBuilder.setValue(theSwitch.value());
      IntList keys = switches.get(i);
      for (int j = 0; j < keys.size(); j++) {
        int key = keys.getInt(j);
        switchBuilder.addKeyAndTarget(key, keyToTarget.get(key));
      }
      switchBuilder.setFallthrough(fallthroughBlock).setBlockNumber(code.getNextBlockNumber());
      BasicBlock newSwitchBlock = switchBuilder.build(code.metadata());
      newBlocks.addFirst(newSwitchBlock);
      fallthroughBlock = newSwitchBlock;
    }

    // Build the if-blocks backwards, to always have the fallthrough block in hand.
    for (int i = keysToRemove.size() - 1; i >= 0; i--) {
      int key = keysToRemove.getInt(i);
      BasicBlock peeledOffTarget = keyToTarget.get(key);
      IfBuilder ifBuilder = new IfBuilder(position, code);
      ifBuilder
          .setLeft(theSwitch.value())
          .setRight(key)
          .setTarget(peeledOffTarget)
          .setFallthrough(fallthroughBlock)
          .setBlockNumber(code.getNextBlockNumber());
      BasicBlock ifBlock = ifBuilder.build();
      newBlocks.addFirst(ifBlock);
      fallthroughBlock = ifBlock;
    }

    // Finally link the block before the original switch to the new block sequence.
    originalBlock.link(fallthroughBlock);

    // Finally add the blocks.
    newBlocks.forEach(blocksIterator::add);
  }

  private static class Interval {

    private final IntList keys = new IntArrayList();

    public Interval(IntList... allKeys) {
      assert allKeys.length > 0;
      for (IntList keys : allKeys) {
        assert keys.size() > 0;
        this.keys.addAll(keys);
      }
    }

    public int getMin() {
      return keys.getInt(0);
    }

    public int getMax() {
      return keys.getInt(keys.size() - 1);
    }

    public void addInterval(Interval other) {
      assert getMax() < other.getMin();
      keys.addAll(other.keys);
    }

    public long packedSavings(InternalOutputMode mode) {
      long packedTargets = (long) getMax() - (long) getMin() + 1;
      if (!IntSwitch.canBePacked(mode, packedTargets)) {
        return Long.MIN_VALUE + 1;
      }
      long sparseCost =
          IntSwitch.baseSparseSize(mode) + IntSwitch.sparsePayloadSize(mode, keys.size());
      long packedCost =
          IntSwitch.basePackedSize(mode) + IntSwitch.packedPayloadSize(mode, packedTargets);
      return sparseCost - packedCost;
    }

    public long estimatedSize(InternalOutputMode mode) {
      return IntSwitch.estimatedSize(mode, keys.toIntArray());
    }
  }

  private Interval combineOrAddInterval(
      List<Interval> intervals, Interval previous, Interval current) {
    // As a first iteration, we only combine intervals if their packed size is less than their
    // sparse counterpart. In CF we will have to add a load and a jump which add to the
    // stack map table (1 is the size of a same entry).
    InternalOutputMode mode = options.getInternalOutputMode();
    int penalty = mode.isGeneratingClassFiles() ? 3 + 1 : 0;
    if (previous == null) {
      intervals.add(current);
      return current;
    }
    Interval combined = new Interval(previous.keys, current.keys);
    long packedSavings = combined.packedSavings(mode);
    if (packedSavings <= 0
        || packedSavings < previous.estimatedSize(mode) + current.estimatedSize(mode) - penalty) {
      intervals.add(current);
      return current;
    } else {
      intervals.set(intervals.size() - 1, combined);
      return combined;
    }
  }

  private void tryAddToBiggestSavings(
      Set<Interval> biggestPackedSet,
      PriorityQueue<Interval> intervals,
      Interval toAdd,
      int maximumNumberOfSwitches) {
    assert !biggestPackedSet.contains(toAdd);
    long savings = toAdd.packedSavings(options.getInternalOutputMode());
    if (savings <= 0) {
      return;
    }
    if (intervals.size() < maximumNumberOfSwitches) {
      intervals.add(toAdd);
      biggestPackedSet.add(toAdd);
    } else if (savings > intervals.peek().packedSavings(options.getInternalOutputMode())) {
      intervals.add(toAdd);
      biggestPackedSet.add(toAdd);
      biggestPackedSet.remove(intervals.poll());
    }
  }

  private int sizeForKeysWrittenAsIfs(ValueType type, Collection<Integer> keys) {
    int ifsSize = If.estimatedSize(options.getInternalOutputMode()) * keys.size();
    // In Cf we also require a load as well (and a stack map entry)
    if (options.getInternalOutputMode().isGeneratingClassFiles()) {
      ifsSize += keys.size() * (3 + 1);
    }
    for (int k : keys) {
      if (k != 0) {
        ifsSize += ConstNumber.estimatedSize(options.getInternalOutputMode(), type, k);
      }
    }
    return ifsSize;
  }

  private int codeUnitMargin() {
    return options.getInternalOutputMode().isGeneratingClassFiles() ? 3 : 1;
  }

  private int findIfsForCandidates(
      List<Interval> newSwitches, IntSwitch theSwitch, IntList outliers) {
    Set<Interval> switchesToRemove = new HashSet<>();
    InternalOutputMode mode = options.getInternalOutputMode();
    int outliersAsIfSize = 0;
    // The candidateForIfs is either an index to a switch that can be eliminated totally or a sparse
    // where removing a key may produce a greater saving. It is only if keys are small in the packed
    // switch that removing the keys makes sense (size wise).
    for (Interval candidate : newSwitches) {
      int maxIfBudget = 10;
      long switchSize = candidate.estimatedSize(mode);
      int sizeOfAllKeysAsIf = sizeForKeysWrittenAsIfs(theSwitch.value().outType(), candidate.keys);
      if (candidate.keys.size() <= maxIfBudget
          && sizeOfAllKeysAsIf < switchSize - codeUnitMargin()) {
        outliersAsIfSize += sizeOfAllKeysAsIf;
        switchesToRemove.add(candidate);
        outliers.addAll(candidate.keys);
        continue;
      }
      // One could do something clever here, but we use a simple algorithm that use the fact that
      // all keys are sorted in ascending order and that the smallest absolute value will give the
      // best saving.
      IntList candidateKeys = candidate.keys;
      int smallestPosition = -1;
      long smallest = Long.MAX_VALUE;
      for (int i = 0; i < candidateKeys.size(); i++) {
        long current = Math.abs((long) candidateKeys.getInt(i));
        if (current < smallest) {
          smallestPosition = i;
          smallest = current;
        }
      }
      // Add as many keys forward and backward as we have budget and we decrease in size.
      IntList ifKeys = new IntArrayList();
      ifKeys.add(candidateKeys.getInt(smallestPosition));
      long previousSavings = 0;
      long currentSavings =
          switchSize
              - sizeForKeysWrittenAsIfs(theSwitch.value().outType(), ifKeys)
              - IntSwitch.estimatedSparseSize(mode, candidateKeys.size() - ifKeys.size());
      int minIndex = smallestPosition - 1;
      int maxIndex = smallestPosition + 1;
      while (ifKeys.size() < maxIfBudget && currentSavings > previousSavings) {
        if (minIndex >= 0 && maxIndex < candidateKeys.size()) {
          long valMin = Math.abs((long) candidateKeys.getInt(minIndex));
          int valMax = Math.abs(candidateKeys.getInt(maxIndex));
          if (valMax <= valMin) {
            ifKeys.add(candidateKeys.getInt(maxIndex++));
          } else {
            ifKeys.add(candidateKeys.getInt(minIndex--));
          }
        } else if (minIndex >= 0) {
          ifKeys.add(candidateKeys.getInt(minIndex--));
        } else if (maxIndex < candidateKeys.size()) {
          ifKeys.add(candidateKeys.getInt(maxIndex++));
        } else {
          // No more elements to add as if's.
          break;
        }
        previousSavings = currentSavings;
        currentSavings =
            switchSize
                - sizeForKeysWrittenAsIfs(theSwitch.value().outType(), ifKeys)
                - IntSwitch.estimatedSparseSize(mode, candidateKeys.size() - ifKeys.size());
      }
      if (previousSavings >= currentSavings) {
        // Remove the last added key since it did not contribute to savings.
        int lastKey = ifKeys.getInt(ifKeys.size() - 1);
        ifKeys.removeInt(ifKeys.size() - 1);
        if (lastKey == candidateKeys.getInt(minIndex + 1)) {
          minIndex++;
        } else {
          maxIndex--;
        }
      }
      // Adjust pointers into the candidate keys.
      minIndex++;
      maxIndex--;
      if (ifKeys.size() > 0) {
        int ifsSize = sizeForKeysWrittenAsIfs(theSwitch.value().outType(), ifKeys);
        long newSwitchSize =
            IntSwitch.estimatedSparseSize(mode, candidateKeys.size() - ifKeys.size());
        if (newSwitchSize + ifsSize + codeUnitMargin() < switchSize) {
          candidateKeys.removeElements(minIndex, maxIndex);
          outliers.addAll(ifKeys);
          outliersAsIfSize += ifsSize;
        }
      }
    }
    newSwitches.removeAll(switchesToRemove);
    return outliersAsIfSize;
  }

  public boolean rewriteSwitch(IRCode code) {
    return rewriteSwitch(code, SwitchCaseAnalyzer.getInstance());
  }

  private boolean rewriteSwitch(IRCode code, SwitchCaseAnalyzer switchCaseAnalyzer) {
    if (!options.isSwitchRewritingEnabled()) {
      return false;
    }
    if (!code.metadata().mayHaveSwitch()) {
      return false;
    }
    return rewriteSwitchFull(code, switchCaseAnalyzer);
  }

  private boolean rewriteSwitchFull(IRCode code, SwitchCaseAnalyzer switchCaseAnalyzer) {
    boolean needToRemoveUnreachableBlocks = false;
    ListIterator<BasicBlock> blocksIterator = code.listIterator();
    while (blocksIterator.hasNext()) {
      BasicBlock block = blocksIterator.next();
      InstructionListIterator iterator = block.listIterator(code);
      while (iterator.hasNext()) {
        Instruction instruction = iterator.next();
        if (instruction.isSwitch()) {
          Switch theSwitch = instruction.asSwitch();
          if (options.testing.enableDeadSwitchCaseElimination) {
            SwitchCaseEliminator eliminator =
                removeUnnecessarySwitchCases(code, theSwitch, iterator, switchCaseAnalyzer);
            if (eliminator != null) {
              if (eliminator.mayHaveIntroducedUnreachableBlocks()) {
                needToRemoveUnreachableBlocks = true;
              }

              iterator.previous();
              instruction = iterator.next();
              if (instruction.isGoto()) {
                continue;
              }

              assert instruction.isSwitch();
              theSwitch = instruction.asSwitch();
            }
          }
          if (theSwitch.isIntSwitch()) {
            rewriteIntSwitch(code, blocksIterator, block, iterator, theSwitch.asIntSwitch());
          }
        }
      }
    }

    // Rewriting of switches introduces new branching structure. It relies on critical edges
    // being split on the way in but does not maintain this property. We therefore split
    // critical edges at exit.
    code.splitCriticalEdges();

    Set<Value> affectedValues =
        needToRemoveUnreachableBlocks ? code.removeUnreachableBlocks() : ImmutableSet.of();
    if (!affectedValues.isEmpty()) {
      new TypeAnalysis(appView).narrowing(affectedValues);
    }
    assert code.isConsistentSSA(appView);
    return !affectedValues.isEmpty();
  }

  void rewriteSingleKeySwitchToIf(
      IRCode code, BasicBlock block, InstructionListIterator iterator, IntSwitch theSwitch) {
    // Rewrite the switch to an if.
    int fallthroughBlockIndex = theSwitch.getFallthroughBlockIndex();
    int caseBlockIndex = theSwitch.targetBlockIndices()[0];
    if (fallthroughBlockIndex < caseBlockIndex) {
      block.swapSuccessorsByIndex(fallthroughBlockIndex, caseBlockIndex);
    }
    If replacement;
    if (theSwitch.isIntSwitch() && theSwitch.asIntSwitch().getFirstKey() == 0) {
      replacement = new If(Type.EQ, theSwitch.value());
    } else {
      Instruction labelConst = theSwitch.materializeFirstKey(appView, code);
      labelConst.setPosition(theSwitch.getPosition());
      iterator.previous();
      iterator.add(labelConst);
      Instruction dummy = iterator.next();
      assert dummy == theSwitch;
      replacement = new If(Type.EQ, ImmutableList.of(theSwitch.value(), labelConst.outValue()));
    }
    iterator.replaceCurrentInstruction(replacement);
  }

  private void rewriteIntSwitch(
      IRCode code,
      ListIterator<BasicBlock> blockIterator,
      BasicBlock block,
      InstructionListIterator iterator,
      IntSwitch theSwitch) {
    if (disableSwitchToIfRewritingForClassIdComparisons(theSwitch)) {
      return;
    }

    if (theSwitch.numberOfKeys() == 1) {
      rewriteSingleKeySwitchToIf(code, block, iterator, theSwitch);
      return;
    }

    // If there are more than 1 key, we use the following algorithm to find keys to combine.
    // First, scan through the keys forward and combine each packed interval with the
    // previous interval if it gives a net saving.
    // Secondly, go through all created intervals and combine the ones without a saving into
    // a single interval and keep a max number of packed switches.
    // Finally, go through all intervals and check if the switch or part of the switch
    // should be transformed to ifs.

    // Phase 1: Combine packed intervals.
    InternalOutputMode mode = options.getInternalOutputMode();
    int[] keys = theSwitch.getKeys();
    int maxNumberOfIfsOrSwitches = 10;
    PriorityQueue<Interval> biggestPackedSavings =
        new PriorityQueue<>((x, y) -> Long.compare(y.packedSavings(mode), x.packedSavings(mode)));
    Set<Interval> biggestPackedSet = new HashSet<>();
    List<Interval> intervals = new ArrayList<>();
    int previousKey = keys[0];
    IntList currentKeys = new IntArrayList();
    currentKeys.add(previousKey);
    Interval previousInterval = null;
    for (int i = 1; i < keys.length; i++) {
      int key = keys[i];
      if (((long) key - (long) previousKey) > 1) {
        Interval current = new Interval(currentKeys);
        Interval added = combineOrAddInterval(intervals, previousInterval, current);
        if (added != current && biggestPackedSet.contains(previousInterval)) {
          biggestPackedSet.remove(previousInterval);
          biggestPackedSavings.remove(previousInterval);
        }
        tryAddToBiggestSavings(
            biggestPackedSet, biggestPackedSavings, added, maxNumberOfIfsOrSwitches);
        previousInterval = added;
        currentKeys = new IntArrayList();
      }
      currentKeys.add(key);
      previousKey = key;
    }
    Interval current = new Interval(currentKeys);
    Interval added = combineOrAddInterval(intervals, previousInterval, current);
    if (added != current && biggestPackedSet.contains(previousInterval)) {
      biggestPackedSet.remove(previousInterval);
      biggestPackedSavings.remove(previousInterval);
    }
    tryAddToBiggestSavings(biggestPackedSet, biggestPackedSavings, added, maxNumberOfIfsOrSwitches);

    // Phase 2: combine sparse intervals into a single bin.
    // Check if we should save a space for a sparse switch, if so, remove the switch with
    // the smallest savings.
    if (biggestPackedSet.size() == maxNumberOfIfsOrSwitches
        && maxNumberOfIfsOrSwitches < intervals.size()) {
      biggestPackedSet.remove(biggestPackedSavings.poll());
    }
    Interval sparse = null;
    List<Interval> newSwitches = new ArrayList<>(maxNumberOfIfsOrSwitches);
    for (int i = 0; i < intervals.size(); i++) {
      Interval interval = intervals.get(i);
      if (biggestPackedSet.contains(interval)) {
        newSwitches.add(interval);
      } else if (sparse == null) {
        sparse = interval;
        newSwitches.add(sparse);
      } else {
        sparse.addInterval(interval);
      }
    }

    // Phase 3: at this point we are guaranteed to have the biggest saving switches
    // in newIntervals, potentially with a switch combining the remaining intervals.
    // Now we check to see if we can create any if's to reduce size.
    IntList outliers = new IntArrayList();
    int outliersAsIfSize =
        appView.options().testing.enableSwitchToIfRewriting
            ? findIfsForCandidates(newSwitches, theSwitch, outliers)
            : 0;

    long newSwitchesSize = 0;
    List<IntList> newSwitchSequences = new ArrayList<>(newSwitches.size());
    for (Interval interval : newSwitches) {
      newSwitchesSize += interval.estimatedSize(mode);
      newSwitchSequences.add(interval.keys);
    }

    long currentSize = IntSwitch.estimatedSize(mode, theSwitch.getKeys());
    if (newSwitchesSize + outliersAsIfSize + codeUnitMargin() < currentSize) {
      convertSwitchToSwitchAndIfs(
          code, blockIterator, block, iterator, theSwitch, newSwitchSequences, outliers);
    }
  }

  // TODO(b/181732463): We currently disable switch-to-if rewritings for switches on $r8$classId
  //  field values (from horizontal class merging. See bug for more details.
  private boolean disableSwitchToIfRewritingForClassIdComparisons(IntSwitch theSwitch) {
    Value switchValue = theSwitch.value().getAliasedValue();
    if (!switchValue.isDefinedByInstructionSatisfying(Instruction::isInstanceGet)) {
      return false;
    }
    AppInfoWithLiveness appInfo = appView.appInfoWithLiveness();
    if (appInfo == null) {
      return false;
    }
    InstanceGet instanceGet = switchValue.getDefinition().asInstanceGet();
    SingleProgramFieldResolutionResult resolutionResult =
        appInfo.resolveField(instanceGet.getField()).asSingleProgramFieldResolutionResult();
    if (resolutionResult == null) {
      return false;
    }
    DexEncodedField resolvedField = resolutionResult.getResolvedField();
    return HorizontalClassMergerUtils.isClassIdField(appView, resolvedField);
  }

  private SwitchCaseEliminator removeUnnecessarySwitchCases(
      IRCode code,
      Switch theSwitch,
      InstructionListIterator iterator,
      SwitchCaseAnalyzer switchCaseAnalyzer) {
    BasicBlock defaultTarget = theSwitch.fallthroughBlock();
    SwitchCaseEliminator eliminator = null;
    BasicBlockBehavioralSubsumption behavioralSubsumption =
        new BasicBlockBehavioralSubsumption(appView, code);

    // Compute the set of switch cases that can be removed.
    AbstractValue switchAbstractValue = theSwitch.value().getAbstractValue(appView, code.context());
    for (int i = 0; i < theSwitch.numberOfKeys(); i++) {
      BasicBlock targetBlock = theSwitch.targetBlock(i);

      if (switchCaseAnalyzer.switchCaseIsAlwaysHit(theSwitch, i)) {
        if (eliminator == null) {
          eliminator = new SwitchCaseEliminator(theSwitch, iterator);
        }
        eliminator.markSwitchCaseAsAlwaysHit(i);
        break;
      }

      // This switch case can be removed if the behavior of the target block is equivalent to the
      // behavior of the default block, or if the switch case is unreachable.
      if (switchCaseAnalyzer.switchCaseIsUnreachable(theSwitch, switchAbstractValue, i)
          || behavioralSubsumption.isSubsumedBy(targetBlock, defaultTarget)) {
        if (eliminator == null) {
          eliminator = new SwitchCaseEliminator(theSwitch, iterator);
        }
        eliminator.markSwitchCaseForRemoval(i);
      }
    }

    if (eliminator == null || eliminator.isFallthroughLive()) {
      if (switchCaseAnalyzer.switchFallthroughIsNeverHit(theSwitch, switchAbstractValue)) {
        if (eliminator == null) {
          eliminator = new SwitchCaseEliminator(theSwitch, iterator);
        }
        eliminator.markSwitchFallthroughAsNeverHit();
      }
    }

    if (eliminator != null) {
      eliminator.optimize();
    }
    return eliminator;
  }

  /**
   * Rewrite all branch targets to the destination of trivial goto chains when possible. Does not
   * rewrite fallthrough targets as that would require block reordering and the transformation only
   * makes sense after SSA destruction where there are no phis.
   */
  public static void collapseTrivialGotos(AppView<?> appView, IRCode code) {
    assert code.isConsistentGraph(appView);
    List<BasicBlock> blocksToRemove = new ArrayList<>();
    // Rewrite all non-fallthrough targets to the end of trivial goto chains and remove
    // first round of trivial goto blocks.
    ListIterator<BasicBlock> iterator = code.listIterator();
    assert iterator.hasNext();
    BasicBlock block = iterator.next();
    BasicBlock nextBlock;

    do {
      nextBlock = iterator.hasNext() ? iterator.next() : null;
      if (block.isTrivialGoto()) {
        collapseTrivialGoto(code, block, nextBlock, blocksToRemove);
      }
      if (block.exit().isIf()) {
        collapseIfTrueTarget(block);
      }
      if (block.exit().isSwitch()) {
        collapseNonFallthroughSwitchTargets(block);
      }
      block = nextBlock;
    } while (nextBlock != null);
    code.removeBlocks(blocksToRemove);
    // Get rid of gotos to the next block.
    while (!blocksToRemove.isEmpty()) {
      blocksToRemove = new ArrayList<>();
      iterator = code.listIterator();
      block = iterator.next();
      do {
        nextBlock = iterator.hasNext() ? iterator.next() : null;
        if (block.isTrivialGoto()) {
          collapseTrivialGoto(code, block, nextBlock, blocksToRemove);
        }
        block = nextBlock;
      } while (block != null);
      code.removeBlocks(blocksToRemove);
    }
    assert removedTrivialGotos(code);
    assert code.isConsistentGraph(appView);
  }

  private boolean checkArgumentType(InvokeMethod invoke, int argumentIndex) {
    // TODO(sgjesse): Insert cast if required.
    TypeElement returnType =
        TypeElement.fromDexType(invoke.getInvokedMethod().proto.returnType, maybeNull(), appView);
    TypeElement argumentType =
        TypeElement.fromDexType(getArgumentType(invoke, argumentIndex), maybeNull(), appView);
    return appView.enableWholeProgramOptimizations()
        ? argumentType.lessThanOrEqual(returnType, appView)
        : argumentType.equals(returnType);
  }

  private DexType getArgumentType(InvokeMethod invoke, int argumentIndex) {
    if (invoke.isInvokeStatic()) {
      return invoke.getInvokedMethod().proto.parameters.values[argumentIndex];
    }
    if (argumentIndex == 0) {
      return invoke.getInvokedMethod().holder;
    }
    return invoke.getInvokedMethod().proto.parameters.values[argumentIndex - 1];
  }

  // Replace result uses for methods where something is known about what is returned.
  public boolean rewriteMoveResult(IRCode code) {
    if (options.isGeneratingClassFiles() || !code.metadata().mayHaveInvokeMethod()) {
      return false;
    }

    AssumeRemover assumeRemover = new AssumeRemover(appView, code);
    boolean changed = false;
    boolean mayHaveRemovedTrivialPhi = false;
    Set<BasicBlock> blocksToBeRemoved = Sets.newIdentityHashSet();
    ListIterator<BasicBlock> blockIterator = code.listIterator();
    while (blockIterator.hasNext()) {
      BasicBlock block = blockIterator.next();
      if (blocksToBeRemoved.contains(block)) {
        continue;
      }

      InstructionListIterator iterator = block.listIterator(code);
      while (iterator.hasNext()) {
        InvokeMethod invoke = iterator.next().asInvokeMethod();
        if (invoke == null || !invoke.hasOutValue() || invoke.outValue().hasLocalInfo()) {
          continue;
        }

        // Check if the invoked method is known to return one of its arguments.
        DexClassAndMethod target = invoke.lookupSingleTarget(appView, code.context());
        if (target == null) {
          continue;
        }

        MethodOptimizationInfo optimizationInfo = target.getDefinition().getOptimizationInfo();
        if (!optimizationInfo.returnsArgument()) {
          continue;
        }

        int argumentIndex = optimizationInfo.getReturnedArgument();
        // Replace the out value of the invoke with the argument and ignore the out value.
        if (argumentIndex < 0 || !checkArgumentType(invoke, argumentIndex)) {
          continue;
        }

        Value argument = invoke.arguments().get(argumentIndex);
        Value outValue = invoke.outValue();
        assert outValue.verifyCompatible(argument.outType());

        // Make sure that we are only narrowing information here. Note, in cases where we cannot
        // find the definition of types, computing lessThanOrEqual will return false unless it is
        // object.
        if (!argument.getType().lessThanOrEqual(outValue.getType(), appView)) {
          continue;
        }

        Set<Value> affectedValues =
            argument.getType().equals(outValue.getType())
                ? Collections.emptySet()
                : outValue.affectedValues();

        assumeRemover.markAssumeDynamicTypeUsersForRemoval(outValue);
        mayHaveRemovedTrivialPhi |= outValue.numberOfPhiUsers() > 0;
        outValue.replaceUsers(argument);
        invoke.setOutValue(null);
        changed = true;

        if (!affectedValues.isEmpty()) {
          new TypeAnalysis(appView).narrowing(affectedValues);
        }
      }
    }
    assumeRemover.removeMarkedInstructions(blocksToBeRemoved).finish();
    Set<Value> affectedValues = Sets.newIdentityHashSet();
    if (!blocksToBeRemoved.isEmpty()) {
      code.removeBlocks(blocksToBeRemoved);
      code.removeAllDeadAndTrivialPhis(affectedValues);
      assert code.getUnreachableBlocks().isEmpty();
    } else if (mayHaveRemovedTrivialPhi || assumeRemover.mayHaveIntroducedTrivialPhi()) {
      code.removeAllDeadAndTrivialPhis(affectedValues);
    }
    if (!affectedValues.isEmpty()) {
      new TypeAnalysis(appView).narrowing(affectedValues);
    }
    assert code.isConsistentSSA(appView);
    return changed;
  }

  enum RemoveCheckCastInstructionIfTrivialResult {
    NO_REMOVALS,
    REMOVED_CAST_DO_NARROW
  }

  public void removeTrivialCheckCastAndInstanceOfInstructions(
      IRCode code,
      ProgramMethod context,
      MethodProcessor methodProcessor,
      MethodProcessingContext methodProcessingContext) {
    if (!appView.enableWholeProgramOptimizations()) {
      return;
    }

    assert appView.appInfo().hasLiveness();
    AppView<AppInfoWithLiveness> appViewWithLiveness = appView.withLiveness();

    if (!appView.options().testing.enableCheckCastAndInstanceOfRemoval) {
      return;
    }

    IRMetadata metadata = code.metadata();
    if (!metadata.mayHaveCheckCast() && !metadata.mayHaveInstanceOf()) {
      return;
    }

    // If we can remove a CheckCast it is due to us having at least as much information about the
    // type as the CheckCast gives. We then need to propagate that information to the users of
    // the CheckCast to ensure further optimizations and removals of CheckCast:
    //
    //    : 1: NewArrayEmpty        v2 <- v1(1) java.lang.String[]  <-- v2 = String[]
    // ...
    //    : 2: CheckCast            v5 <- v2; java.lang.Object[]    <-- v5 = Object[]
    // ...
    //    : 3: ArrayGet             v7 <- v5, v6(0)                 <-- v7 = Object
    //    : 4: CheckCast            v8 <- v7; java.lang.String      <-- v8 = String
    // ...
    //
    // When looking at line 2 we can conclude that the CheckCast is trivial because v2 is String[]
    // and remove it. However, v7 is still only known to be Object and we cannot remove the
    // CheckCast at line 4 unless we update v7 with the most precise information by narrowing the
    // affected values of v5. We therefore have to run the type analysis after each CheckCast
    // removal.
    TypeAnalysis typeAnalysis = new TypeAnalysis(appView);
    Set<Value> affectedValues = Sets.newIdentityHashSet();
    InstructionListIterator it = code.instructionListIterator();
    boolean needToRemoveTrivialPhis = false;
    while (it.hasNext()) {
      Instruction current = it.next();
      if (current.isCheckCast()) {
        boolean hasPhiUsers = current.outValue().hasPhiUsers();
        RemoveCheckCastInstructionIfTrivialResult removeResult =
            removeCheckCastInstructionIfTrivial(
                appViewWithLiveness,
                current.asCheckCast(),
                it,
                code,
                context,
                affectedValues,
                methodProcessor,
                methodProcessingContext);
        if (removeResult != RemoveCheckCastInstructionIfTrivialResult.NO_REMOVALS) {
          assert removeResult == RemoveCheckCastInstructionIfTrivialResult.REMOVED_CAST_DO_NARROW;
          needToRemoveTrivialPhis |= hasPhiUsers;
          typeAnalysis.narrowing(affectedValues);
          affectedValues.clear();
        }
      } else if (current.isInstanceOf()) {
        boolean hasPhiUsers = current.outValue().hasPhiUsers();
        if (removeInstanceOfInstructionIfTrivial(
            appViewWithLiveness, current.asInstanceOf(), it, code)) {
          needToRemoveTrivialPhis |= hasPhiUsers;
        }
      }
    }
    // ... v1
    // ...
    // v2 <- check-cast v1, T
    // v3 <- phi(v1, v2)
    // Removing check-cast may result in a trivial phi:
    // v3 <- phi(v1, v1)
    if (needToRemoveTrivialPhis) {
      code.removeAllDeadAndTrivialPhis(affectedValues);
      if (!affectedValues.isEmpty()) {
        typeAnalysis.narrowing(affectedValues);
      }
    }
    assert code.isConsistentSSA(appView);
  }

  // Returns true if the given check-cast instruction was removed.
  private RemoveCheckCastInstructionIfTrivialResult removeCheckCastInstructionIfTrivial(
      AppView<AppInfoWithLiveness> appViewWithLiveness,
      CheckCast checkCast,
      InstructionListIterator it,
      IRCode code,
      ProgramMethod context,
      Set<Value> affectedValues,
      MethodProcessor methodProcessor,
      MethodProcessingContext methodProcessingContext) {
    Value inValue = checkCast.object();
    Value outValue = checkCast.outValue();
    DexType castType = checkCast.getType();
    DexType baseCastType = castType.toBaseType(dexItemFactory);

    // If the cast type is not accessible in the current context, we should not remove the cast
    // in order to preserve runtime errors. Note that JVM and ART behave differently: see
    // {@link com.android.tools.r8.ir.optimize.checkcast.IllegalAccessErrorTest}.
    if (baseCastType.isClassType()) {
      DexClass baseCastClass = appView.definitionFor(baseCastType);
      if (baseCastClass == null
          || AccessControl.isClassAccessible(baseCastClass, code.context(), appViewWithLiveness)
              .isPossiblyFalse()) {
        return RemoveCheckCastInstructionIfTrivialResult.NO_REMOVALS;
      }
    }

    if (!appView
        .getOpenClosedInterfacesCollection()
        .isDefinitelyInstanceOfStaticType(appViewWithLiveness, inValue)) {
      return RemoveCheckCastInstructionIfTrivialResult.NO_REMOVALS;
    }

    // If the in-value is `null` and the cast-type is a float-array type, then trivial check-cast
    // elimination may lead to verification errors. See b/123269162.
    if (options.canHaveArtCheckCastVerifierBug()) {
      if (inValue.getType().isNullType()
          && castType.isArrayType()
          && castType.toBaseType(dexItemFactory).isFloatType()) {
        return RemoveCheckCastInstructionIfTrivialResult.NO_REMOVALS;
      }
    }

    TypeElement inTypeLattice = inValue.getType();
    TypeElement outTypeLattice = outValue.getType();
    TypeElement castTypeLattice = castType.toTypeElement(appView, inTypeLattice.nullability());

    assert inTypeLattice.nullability().lessThanOrEqual(outTypeLattice.nullability());

    if (inTypeLattice.lessThanOrEqual(castTypeLattice, appView)) {
      // 1) Trivial cast.
      //   A a = ...
      //   A a' = (A) a;
      // 2) Up-cast: we already have finer type info.
      //   A < B
      //   A a = ...
      //   B b = (B) a;
      assert inTypeLattice.lessThanOrEqual(outTypeLattice, appView);
      // The removeOrReplaceByDebugLocalWrite will propagate the incoming value for the CheckCast
      // to the users of the CheckCast's out value.
      //
      // v2 = CheckCast A v1  ~~>  DebugLocalWrite $v0 <- v1
      //
      // The DebugLocalWrite is not a user of the outvalue, we therefore have to wait and take the
      // CheckCast invalue users that includes the potential DebugLocalWrite.
      removeOrReplaceByDebugLocalWrite(checkCast, it, inValue, outValue);
      affectedValues.addAll(inValue.affectedValues());
      return RemoveCheckCastInstructionIfTrivialResult.REMOVED_CAST_DO_NARROW;
    }

    // If values of cast type are guaranteed to be null, then the out-value must be null if the cast
    // succeeds. After removing all usages of the out-value, the check-cast instruction is replaced
    // by a call to throwClassCastExceptionIfNotNull() to allow dead code elimination of the cast
    // type.
    if (castType.isClassType()
        && castType.isAlwaysNull(appViewWithLiveness)
        && !outValue.hasDebugUsers()) {
      // Replace all usages of the out-value by null.
      it.previous();
      Value nullValue = it.insertConstNullInstruction(code, options);
      it.next();
      checkCast.outValue().replaceUsers(nullValue);
      affectedValues.addAll(nullValue.affectedValues());

      // Replace the check-cast instruction by throwClassCastExceptionIfNotNull().
      UtilityMethodForCodeOptimizations throwClassCastExceptionIfNotNullMethod =
          UtilityMethodsForCodeOptimizations.synthesizeThrowClassCastExceptionIfNotNullMethod(
              appView, methodProcessingContext);
      throwClassCastExceptionIfNotNullMethod.optimize(methodProcessor);
      InvokeStatic replacement =
          InvokeStatic.builder()
              .setMethod(throwClassCastExceptionIfNotNullMethod.getMethod())
              .setSingleArgument(checkCast.object())
              .setPosition(checkCast)
              .build();
      it.replaceCurrentInstruction(replacement);
      assert replacement.lookupSingleTarget(appView, context) != null;
      return RemoveCheckCastInstructionIfTrivialResult.REMOVED_CAST_DO_NARROW;
    }

    // If the cast is guaranteed to succeed and only there to ensure the program type checks, then
    // check if the program would still type check after removing the cast.
    if (checkCast.isSafeCheckCast()
        || checkCast
            .getFirstOperand()
            .getDynamicType(appViewWithLiveness)
            .getDynamicUpperBoundType()
            .lessThanOrEqualUpToNullability(castTypeLattice, appView)) {
      TypeElement useType =
          TypeUtils.computeUseType(appViewWithLiveness, context, checkCast.outValue());
      if (inTypeLattice.lessThanOrEqualUpToNullability(useType, appView)) {
        return RemoveCheckCastInstructionIfTrivialResult.REMOVED_CAST_DO_NARROW;
      }
    }

    // Otherwise, keep the checkcast to preserve verification errors. E.g., down-cast:
    // A < B < C
    // c = ...        // Even though we know c is of type A,
    // a' = (B) c;    // (this could be removed, since chained below.)
    // a'' = (A) a';  // this should remain for runtime verification.
    assert !inTypeLattice.isDefinitelyNull() || (inValue.isPhi() && !inTypeLattice.isNullType());
    assert outTypeLattice.equalUpToNullability(castTypeLattice);
    return RemoveCheckCastInstructionIfTrivialResult.NO_REMOVALS;
  }

  // Returns true if the given instance-of instruction was removed.
  private boolean removeInstanceOfInstructionIfTrivial(
      AppView<AppInfoWithLiveness> appViewWithLiveness,
      InstanceOf instanceOf,
      InstructionListIterator it,
      IRCode code) {
    ProgramMethod context = code.context();

    // If the instance-of type is not accessible in the current context, we should not remove the
    // instance-of instruction in order to preserve IllegalAccessError.
    DexType instanceOfBaseType = instanceOf.type().toBaseType(dexItemFactory);
    if (instanceOfBaseType.isClassType()) {
      DexClass instanceOfClass = appView.definitionFor(instanceOfBaseType);
      if (instanceOfClass == null
          || AccessControl.isClassAccessible(instanceOfClass, context, appViewWithLiveness)
              .isPossiblyFalse()) {
        return false;
      }
    }

    Value inValue = instanceOf.value();
    if (!appView
        .getOpenClosedInterfacesCollection()
        .isDefinitelyInstanceOfStaticType(appViewWithLiveness, inValue)) {
      return false;
    }

    TypeElement inType = inValue.getType();
    TypeElement instanceOfType =
        TypeElement.fromDexType(instanceOf.type(), inType.nullability(), appView);
    Value aliasValue = inValue.getAliasedValue();

    InstanceOfResult result = InstanceOfResult.UNKNOWN;
    if (inType.isDefinitelyNull()) {
      result = InstanceOfResult.FALSE;
    } else if (inType.lessThanOrEqual(instanceOfType, appView) && !inType.isNullable()) {
      result = InstanceOfResult.TRUE;
    } else if (!aliasValue.isPhi()
        && aliasValue.definition.isCreatingInstanceOrArray()
        && instanceOfType.strictlyLessThan(inType, appView)) {
      result = InstanceOfResult.FALSE;
    } else if (appView.appInfo().hasLiveness()) {
      if (instanceOf.type().isClassType()
          && isNeverInstantiatedDirectlyOrIndirectly(instanceOf.type())) {
        // The type of the instance-of instruction is a program class, and is never instantiated
        // directly or indirectly. Thus, the in-value must be null, meaning that the instance-of
        // instruction will always evaluate to false.
        result = InstanceOfResult.FALSE;
      }

      if (result == InstanceOfResult.UNKNOWN) {
        if (inType.isClassType()
            && isNeverInstantiatedDirectlyOrIndirectly(inType.asClassType().getClassType())) {
          // The type of the in-value is a program class, and is never instantiated directly or
          // indirectly. This, the in-value must be null, meaning that the instance-of instruction
          // will always evaluate to false.
          result = InstanceOfResult.FALSE;
        }
      }

      if (result == InstanceOfResult.UNKNOWN) {
        Value aliasedValue =
            inValue.getSpecificAliasedValue(
                value ->
                    value.isDefinedByInstructionSatisfying(
                        Instruction::isAssumeWithDynamicTypeAssumption));
        if (aliasedValue != null) {
          DynamicTypeWithUpperBound dynamicType =
              aliasedValue.getDefinition().asAssume().getDynamicTypeAssumption().getDynamicType();
          Nullability nullability = dynamicType.getNullability();
          if (nullability.isDefinitelyNull()) {
            result = InstanceOfResult.FALSE;
          } else if (dynamicType.getDynamicUpperBoundType().lessThanOrEqual(instanceOfType, appView)
              && (!inType.isNullable() || !nullability.isNullable())) {
            result = InstanceOfResult.TRUE;
          }
        }
      }
    }
    if (result != InstanceOfResult.UNKNOWN) {
      ConstNumber newInstruction =
          new ConstNumber(
              new Value(
                  code.valueNumberGenerator.next(),
                  TypeElement.getInt(),
                  instanceOf.outValue().getLocalInfo()),
              result == InstanceOfResult.TRUE ? 1 : 0);
      it.replaceCurrentInstruction(newInstruction);
      return true;
    }
    return false;
  }

  private boolean isNeverInstantiatedDirectlyOrIndirectly(DexType type) {
    assert appView.appInfo().hasLiveness();
    assert type.isClassType();
    DexProgramClass clazz = asProgramClassOrNull(appView.definitionFor(type));
    return clazz != null
        && !appView.appInfo().withLiveness().isInstantiatedDirectlyOrIndirectly(clazz);
  }

  public static void removeOrReplaceByDebugLocalWrite(
      Instruction currentInstruction, InstructionListIterator it, Value inValue, Value outValue) {
    if (outValue.hasLocalInfo() && outValue.getLocalInfo() != inValue.getLocalInfo()) {
      DebugLocalWrite debugLocalWrite = new DebugLocalWrite(outValue, inValue);
      it.replaceCurrentInstruction(debugLocalWrite);
    } else {
      if (outValue.hasLocalInfo()) {
        assert outValue.getLocalInfo() == inValue.getLocalInfo();
        // Should remove the end-marker before replacing the current instruction.
        currentInstruction.removeDebugValue(outValue.getLocalInfo());
      }
      outValue.replaceUsers(inValue);
      it.removeOrReplaceByDebugLocalRead();
    }
  }

  // Split constants that flow into ranged invokes. This gives the register allocator more
  // freedom in assigning register to ranged invokes which can greatly reduce the number
  // of register needed (and thereby code size as well).
  public void splitRangeInvokeConstants(IRCode code) {
    for (BasicBlock block : code.blocks) {
      InstructionListIterator it = block.listIterator(code);
      while (it.hasNext()) {
        Instruction current = it.next();
        if (current.isInvoke() && current.asInvoke().requiredArgumentRegisters() > 5) {
          Invoke invoke = current.asInvoke();
          it.previous();
          Map<ConstNumber, ConstNumber> oldToNew = new IdentityHashMap<>();
          for (int i = 0; i < invoke.inValues().size(); i++) {
            Value value = invoke.inValues().get(i);
            if (value.isConstNumber() && value.numberOfUsers() > 1) {
              ConstNumber definition = value.getConstInstruction().asConstNumber();
              Value originalValue = definition.outValue();
              ConstNumber newNumber = oldToNew.get(definition);
              if (newNumber == null) {
                newNumber = ConstNumber.copyOf(code, definition);
                it.add(newNumber);
                newNumber.setPosition(current.getPosition());
                oldToNew.put(definition, newNumber);
              }
              invoke.inValues().set(i, newNumber.outValue());
              originalValue.removeUser(invoke);
              newNumber.outValue().addUser(invoke);
            }
          }
          it.next();
        }
      }
    }
    assert code.isConsistentSSA(appView);
  }

  /**
   * If an instruction is known to be a binop/lit8 or binop//lit16 instruction, update the
   * instruction to use its own constant that will be defined just before the instruction. This
   * transformation allows to decrease pressure on register allocation by defining the shortest
   * range of constant used by this kind of instruction. D8 knowns at build time that constant will
   * be encoded directly into the final Dex instruction.
   */
  public void useDedicatedConstantForLitInstruction(IRCode code) {
    if (!code.metadata().mayHaveArithmeticOrLogicalBinop()) {
      return;
    }

    for (BasicBlock block : code.blocks) {
      InstructionListIterator instructionIterator = block.listIterator(code);
      // Collect all the non constant in values for binop/lit8 or binop/lit16 instructions.
      Set<Value> binopsWithLit8OrLit16NonConstantValues = Sets.newIdentityHashSet();
      while (instructionIterator.hasNext()) {
        Instruction currentInstruction = instructionIterator.next();
        if (!isBinopWithLit8OrLit16(currentInstruction)) {
          continue;
        }
        Value value = binopWithLit8OrLit16NonConstant(currentInstruction.asBinop());
        assert value != null;
        binopsWithLit8OrLit16NonConstantValues.add(value);
      }
      if (binopsWithLit8OrLit16NonConstantValues.isEmpty()) {
        continue;
      }
      // Find last use in block of all the non constant in values for binop/lit8 or binop/lit16
      // instructions.
      Reference2IntMap<Value> lastUseOfBinopsWithLit8OrLit16NonConstantValues =
          new Reference2IntOpenHashMap<>();
      lastUseOfBinopsWithLit8OrLit16NonConstantValues.defaultReturnValue(-1);
      int currentInstructionNumber = block.getInstructions().size();
      while (instructionIterator.hasPrevious()) {
        Instruction currentInstruction = instructionIterator.previous();
        currentInstructionNumber--;
        for (Value value :
            Iterables.concat(currentInstruction.inValues(), currentInstruction.getDebugValues())) {
          if (!binopsWithLit8OrLit16NonConstantValues.contains(value)) {
            continue;
          }
          if (!lastUseOfBinopsWithLit8OrLit16NonConstantValues.containsKey(value)) {
            lastUseOfBinopsWithLit8OrLit16NonConstantValues.put(value, currentInstructionNumber);
          }
        }
      }
      // Do the transformation except if the binop can use the binop/2addr format.
      currentInstructionNumber--;
      assert currentInstructionNumber == -1;
      while (instructionIterator.hasNext()) {
        Instruction currentInstruction = instructionIterator.next();
        currentInstructionNumber++;
        if (!isBinopWithLit8OrLit16(currentInstruction)) {
          continue;
        }
        Binop binop = currentInstruction.asBinop();
        if (!canBe2AddrInstruction(
            binop, currentInstructionNumber, lastUseOfBinopsWithLit8OrLit16NonConstantValues)) {
          Value constValue = binopWithLit8OrLit16Constant(currentInstruction);
          if (constValue.numberOfAllUsers() > 1) {
            // No need to do the transformation if the const value is already used only one time.
            ConstNumber newConstant = ConstNumber
                .copyOf(code, constValue.definition.asConstNumber());
            newConstant.setPosition(currentInstruction.getPosition());
            newConstant.setBlock(currentInstruction.getBlock());
            currentInstruction.replaceValue(constValue, newConstant.outValue());
            constValue.removeUser(currentInstruction);
            instructionIterator.previous();
            instructionIterator.add(newConstant);
            instructionIterator.next();
          }
        }
      }
    }

    assert code.isConsistentSSA(appView);
  }

  // Check if a binop can be represented in the binop/lit8 or binop/lit16 form.
  private static boolean isBinopWithLit8OrLit16(Instruction instruction) {
    if (!instruction.isArithmeticBinop() && !instruction.isLogicalBinop()) {
      return false;
    }
    Binop binop = instruction.asBinop();
    // If one of the values does not need a register it is implicitly a binop/lit8 or binop/lit16.
    boolean result =
        !binop.needsValueInRegister(binop.leftValue())
            || !binop.needsValueInRegister(binop.rightValue());
    assert !result || binop.leftValue().isConstNumber() || binop.rightValue().isConstNumber();
    return result;
  }

  // Return the constant in-value of a binop/lit8 or binop/lit16 instruction.
  private static Value binopWithLit8OrLit16Constant(Instruction instruction) {
    assert isBinopWithLit8OrLit16(instruction);
    Binop binop = instruction.asBinop();
    if (binop.leftValue().isConstNumber()) {
      return binop.leftValue();
    } else if (binop.rightValue().isConstNumber()) {
      return binop.rightValue();
    } else {
      throw new Unreachable();
    }
  }

  // Return the non-constant in-value of a binop/lit8 or binop/lit16 instruction.
  private static Value binopWithLit8OrLit16NonConstant(Binop binop) {
    if (binop.leftValue().isConstNumber()) {
      return binop.rightValue();
    } else if (binop.rightValue().isConstNumber()) {
      return binop.leftValue();
    } else {
      throw new Unreachable();
    }
  }

  /**
   * Estimate if a binary operation can be a binop/2addr form or not. It can be a 2addr form when an
   * argument is no longer needed after the binary operation and can be overwritten. That is
   * definitely the case if there is no path between the binary operation and all other usages.
   */
  private static boolean canBe2AddrInstruction(
      Binop binop, int binopInstructionNumber, Reference2IntMap<Value> lastUseOfRelevantValue) {
    Value value = binopWithLit8OrLit16NonConstant(binop);
    assert value != null;
    int lastUseInstructionNumber = lastUseOfRelevantValue.getInt(value);
    // The binop instruction is a user, so there is always a last use in the block.
    assert lastUseInstructionNumber != -1;
    if (lastUseInstructionNumber > binopInstructionNumber) {
      return false;
    }

    Set<BasicBlock> noPathTo = Sets.newIdentityHashSet();
    BasicBlock binopBlock = binop.getBlock();
    Iterable<InstructionOrPhi> users =
        value.debugUsers() != null
            ? Iterables.concat(value.uniqueUsers(), value.debugUsers(), value.uniquePhiUsers())
            : Iterables.concat(value.uniqueUsers(), value.uniquePhiUsers());
    for (InstructionOrPhi user : users) {
      BasicBlock userBlock = user.getBlock();
      if (userBlock == binopBlock) {
        // All users in the current block are either before the binop instruction or the
        // binop instruction itself.
        continue;
      }
      if (noPathTo.contains(userBlock)) {
        continue;
      }
      if (binopBlock.hasPathTo(userBlock)) {
        return false;
      }
      noPathTo.add(userBlock);
    }

    return true;
  }

  public void shortenLiveRanges(IRCode code) {
    // Currently, we are only shortening the live range of ConstNumbers in the entry block
    // and ConstStrings with one user.
    // TODO(ager): Generalize this to shorten live ranges for more instructions? Currently
    // doing so seems to make things worse.
    Supplier<DominatorTree> dominatorTreeMemoization =
        Suppliers.memoize(() -> new DominatorTree(code));
    Map<BasicBlock, Map<Value, Instruction>> addConstantInBlock = new IdentityHashMap<>();
    LinkedList<BasicBlock> blocks = code.blocks;
    for (BasicBlock block : blocks) {
      if (block == blocks.getFirst()) {
        // For the first block process all ConstNumber as well as ConstString instructions.
        shortenLiveRangesInsideBlock(
            code,
            block,
            dominatorTreeMemoization,
            addConstantInBlock,
            insn ->
                (insn.isConstNumber() && insn.outValue().hasAnyUsers())
                    || (insn.isConstString() && insn.outValue().hasAnyUsers()));
      } else {
        // For all following blocks only process ConstString with just one use.
        shortenLiveRangesInsideBlock(
            code,
            block,
            dominatorTreeMemoization,
            addConstantInBlock,
            insn -> insn.isConstString() && insn.outValue().numberOfAllUsers() == 1);
      }
    }

    // Heuristic to decide if constant instructions are shared in dominator block
    // of usages or moved to the usages.

    // Process all blocks in stable order to avoid non-determinism of hash map iterator.
    for (BasicBlock block : blocks) {
      Map<Value, Instruction> constants = addConstantInBlock.get(block);
      if (constants == null) {
        continue;
      }

      Set<Value> alreadyMoved = SetUtils.newIdentityHashSet(constants.size());
      if (block != blocks.getFirst() && constants.size() > STOP_SHARED_CONSTANT_THRESHOLD) {
        // If there are too many constants in the same block, they are copied rather than shared
        // except if they are used by phi instructions or they are a string constants.
        assert constants instanceof LinkedHashMap;
        for (Instruction constantInstruction : constants.values()) {
          if (!constantInstruction.outValue().hasPhiUsers()
              && !constantInstruction.isConstString()) {
            assert constantInstruction.isConstNumber();
            ConstNumber constNumber = constantInstruction.asConstNumber();
            Value constantValue = constantInstruction.outValue();
            assert constantValue.hasUsers();
            assert constantValue.numberOfUsers() == constantValue.numberOfAllUsers();
            for (Instruction user : constantValue.uniqueUsers()) {
              ConstNumber newCstNum = ConstNumber.copyOf(code, constNumber);
              newCstNum.setPosition(user.getPosition());
              InstructionListIterator iterator = user.getBlock().listIterator(code, user);
              iterator.previous();
              iterator.add(newCstNum);
              user.replaceValue(constantValue, newCstNum.outValue());
            }
            constantValue.clearUsers();
            alreadyMoved.add(constantInstruction.outValue());
          }
        }
      }

      // Add constant into the dominator block of usages.
      boolean hasCatchHandlers = block.hasCatchHandlers();
      InstructionListIterator it = block.listIterator(code);
      while (it.hasNext()) {
        Instruction instruction = it.next();
        if (instruction.isJumpInstruction()
            || (hasCatchHandlers && instruction.instructionTypeCanThrow())
            || (options.canHaveCmpIfFloatBug() && instruction.isCmp())) {
          break;
        }
        forEachUse(
            instruction,
            use -> {
              Instruction constantInstruction = constants.get(use);
              if (constantInstruction != null && !alreadyMoved.contains(use)) {
                it.previous();
                constantInstruction.setPosition(instruction.getPosition());
                it.add(constantInstruction);
                it.next();
                alreadyMoved.add(use);
              }
            });
      }
      // Insert remaining constant instructions prior to the "exit".
      Instruction next = it.previous();
      for (Instruction constantInstruction : constants.values()) {
        if (!alreadyMoved.contains(constantInstruction.outValue())) {
          constantInstruction.setPosition(next.getPosition());
          it.add(constantInstruction);
        }
      }
    }

    assert code.isConsistentSSA(appView);
  }

  private void forEachUse(Instruction instruction, Consumer<Value> fn) {
    instruction.inValues().forEach(fn);
    instruction.getDebugValues().forEach(fn);
  }

  private void shortenLiveRangesInsideBlock(
      IRCode code,
      BasicBlock block,
      Supplier<DominatorTree> dominatorTreeMemoization,
      Map<BasicBlock, Map<Value, Instruction>> addConstantInBlock,
      Predicate<ConstInstruction> selector) {
    InstructionListIterator iterator = block.listIterator(code);
    while (iterator.hasNext()) {
      Instruction next = iterator.next();
      if (!next.isConstInstruction()) {
        continue;
      }

      // We don't want to push const string instructions down into code that has monitors since
      // we may attach catch handlers that are not catch-all when inlining. This is symmetric in how
      // we don't do const string canonicalization.
      if ((next.isConstString() || next.isDexItemBasedConstString())
          && code.metadata().mayHaveMonitorInstruction()) {
        continue;
      }

      ConstInstruction instruction = next.asConstInstruction();
      if (!selector.test(instruction) || instruction.outValue().hasLocalInfo()) {
        continue;
      }
      Set<Instruction> uniqueUsers = instruction.outValue().uniqueUsers();
      // Here we try to stop wasting time in the common case of large array of constants creation.
      // We do not want to move a high number of constants up just to move them down because it
      // takes multiple seconds in some cases (ZoneName clinit for instance).
      // In array creation, the pattern is something like:
      //   Const number (the array index)
      //   Const (the array entry value)
      //   ArrayPut
      // And both constants are used only in the put. The heuristic is therefore to check for
      // constants used only once if the use is within the next two instructions, and only swap
      // them if that is the case (cannot shorten the live range anyway).
      // This heuristic drops down the time spent in large array of constant creation in
      // shortenLiveRanges from multiple seconds to multiple milliseconds.
      if (uniqueUsers.size() == 1 && instruction.outValue().uniquePhiUsers().size() == 0) {
        Instruction uniqueUse = uniqueUsers.iterator().next();
        if (iterator.hasNext()) {
          Instruction nextNext = iterator.next();
          if (uniqueUse == nextNext && nextNext.isArrayPut()) {
            assert !uniqueUse.isConstInstruction();
            continue;
          }
          if (nextNext.isConstInstruction()) {
            Set<Instruction> uniqueUsersNext = nextNext.outValue().uniqueUsers();
            if (uniqueUsersNext.size() == 1
                && nextNext.outValue().uniquePhiUsers().size() == 0
                && iterator.hasNext()) {
              Instruction nextNextNext = iterator.peekNext();
              Instruction uniqueUseNext = uniqueUsersNext.iterator().next();
              if (uniqueUse == nextNextNext
                  && uniqueUseNext == nextNextNext
                  && nextNextNext.isArrayPut()) {
                continue;
              }
            }
          }
          iterator.previous();
        }
      }
      // Collect the blocks for all users of the constant.
      List<BasicBlock> userBlocks = new LinkedList<>();
      for (Instruction user : uniqueUsers) {
        userBlocks.add(user.getBlock());
      }
      for (Phi phi : instruction.outValue().uniquePhiUsers()) {
        userBlocks.add(phi.getBlock());
      }
      // Locate the closest dominator block for all user blocks.
      DominatorTree dominatorTree = dominatorTreeMemoization.get();
      BasicBlock dominator = dominatorTree.closestDominator(userBlocks);
      // If the closest dominator block is a block that uses the constant for a phi the constant
      // needs to go in the immediate dominator block so that it is available for phi moves.
      for (Phi phi : instruction.outValue().uniquePhiUsers()) {
        if (phi.getBlock() == dominator) {
          if (instruction.outValue().numberOfAllUsers() == 1 &&
              phi.usesValueOneTime(instruction.outValue())) {
            // Out value is used only one time, move the constant directly to the corresponding
            // branch rather than into the dominator to avoid to generate a const on paths which
            // does not required it.
            int predIndex = phi.getOperands().indexOf(instruction.outValue());
            dominator = dominator.getPredecessors().get(predIndex);
          } else {
            dominator = dominatorTree.immediateDominator(dominator);
          }
          break;
        }
      }

      if (instruction.instructionTypeCanThrow()) {
        if (block.hasCatchHandlers() || dominator.hasCatchHandlers()) {
          // Do not move the constant if the constant instruction can throw
          // and the dominator or the original block has catch handlers.
          continue;
        }
      }

      Map<Value, Instruction> csts =
          addConstantInBlock.computeIfAbsent(dominator, k -> new LinkedHashMap<>());

      ConstInstruction copy = instruction.isConstNumber()
          ? ConstNumber.copyOf(code, instruction.asConstNumber())
          : ConstString.copyOf(code, instruction.asConstString());
      instruction.outValue().replaceUsers(copy.outValue());
      csts.put(copy.outValue(), copy);
    }
  }

  private short[] computeArrayFilledData(ConstInstruction[] values, int size, int elementSize) {
    if (values == null) {
      return null;
    }
    if (elementSize == 1) {
      short[] result = new short[(size + 1) / 2];
      for (int i = 0; i < size; i += 2) {
        short value = (short) (values[i].asConstNumber().getIntValue() & 0xFF);
        if (i + 1 < size) {
          value |= (short) ((values[i + 1].asConstNumber().getIntValue() & 0xFF) << 8);
        }
        result[i / 2] = value;
      }
      return result;
    }
    assert elementSize == 2 || elementSize == 4 || elementSize == 8;
    int shortsPerConstant = elementSize / 2;
    short[] result = new short[size * shortsPerConstant];
    for (int i = 0; i < size; i++) {
      long value = values[i].asConstNumber().getRawValue();
      for (int part = 0; part < shortsPerConstant; part++) {
        result[i * shortsPerConstant + part] = (short) ((value >> (16 * part)) & 0xFFFFL);
      }
    }
    return result;
  }

  private ConstInstruction[] computeConstantArrayValues(
      NewArrayEmpty newArray, BasicBlock block, int size) {
    if (size > MAX_FILL_ARRAY_SIZE) {
      return null;
    }
    ConstInstruction[] values = new ConstInstruction[size];
    int remaining = size;
    Set<Instruction> users = newArray.outValue().uniqueUsers();
    Set<BasicBlock> visitedBlocks = Sets.newIdentityHashSet();
    // We allow the array instantiations to cross block boundaries as long as it hasn't encountered
    // an instruction instance that can throw an exception.
    InstructionIterator it = block.iterator();
    it.nextUntil(i -> i == newArray);
    do {
      visitedBlocks.add(block);
      while (it.hasNext()) {
        Instruction instruction = it.next();
        // If we encounter an instruction that can throw an exception we need to bail out of the
        // optimization so that we do not transform half-initialized arrays into fully initialized
        // arrays on exceptional edges. If the block has no handlers it is not observable so
        // we perform the rewriting.
        if (block.hasCatchHandlers() && instruction.instructionInstanceCanThrow()) {
          return null;
        }
        if (!users.contains(instruction)) {
          continue;
        }
        // If the initialization sequence is broken by another use we cannot use a
        // fill-array-data instruction.
        if (!instruction.isArrayPut()) {
          return null;
        }
        ArrayPut arrayPut = instruction.asArrayPut();
        if (!(arrayPut.value().isConstant() && arrayPut.index().isConstNumber())) {
          return null;
        }
        int index = arrayPut.index().getConstInstruction().asConstNumber().getIntValue();
        if (index < 0 || index >= values.length) {
          return null;
        }
        if (values[index] != null) {
          return null;
        }
        ConstInstruction value = arrayPut.value().getConstInstruction();
        values[index] = value;
        --remaining;
        if (remaining == 0) {
          return values;
        }
      }
      BasicBlock nextBlock = block.exit().isGoto() ? block.exit().asGoto().getTarget() : null;
      block = nextBlock != null && !visitedBlocks.contains(nextBlock) ? nextBlock : null;
      it = block != null ? block.iterator() : null;
    } while (it != null);
    return null;
  }

  private boolean allowNewFilledArrayConstruction(Instruction instruction) {
    if (!(instruction instanceof NewArrayEmpty)) {
      return false;
    }
    NewArrayEmpty newArray = instruction.asNewArrayEmpty();
    if (!newArray.size().isConstant()) {
      return false;
    }
    assert newArray.size().isConstNumber();
    int size = newArray.size().getConstInstruction().asConstNumber().getIntValue();
    if (size < 1) {
      return false;
    }
    if (newArray.type.isPrimitiveArrayType()) {
      return true;
    }
    return newArray.type == dexItemFactory.stringArrayType
        && options.canUseFilledNewArrayOfObjects();
  }

  /**
   * Replace new-array followed by stores of constants to all entries with new-array
   * and fill-array-data / filled-new-array.
   */
  public void simplifyArrayConstruction(IRCode code) {
    if (options.isGeneratingClassFiles()) {
      return;
    }
    for (BasicBlock block : code.blocks) {
      // Map from the array value to the number of array put instruction to remove for that value.
      Map<Value, Instruction> instructionToInsertForArray = new HashMap<>();
      Map<Value, Integer> storesToRemoveForArray = new HashMap<>();
      // First pass: identify candidates and insert fill array data instruction.
      InstructionListIterator it = block.listIterator(code);
      while (it.hasNext()) {
        Instruction instruction = it.next();
        if (instruction.getLocalInfo() != null
            || !allowNewFilledArrayConstruction(instruction)) {
          continue;
        }
        NewArrayEmpty newArray = instruction.asNewArrayEmpty();
        int size = newArray.size().getConstInstruction().asConstNumber().getIntValue();
        ConstInstruction[] values = computeConstantArrayValues(newArray, block, size);
        if (values == null) {
          continue;
        }
        if (newArray.type == dexItemFactory.stringArrayType) {
          // Don't replace with filled-new-array if it requires more than 200 consecutive registers.
          if (size > 200) {
            continue;
          }
          List<Value> stringValues = new ArrayList<>(size);
          for (ConstInstruction value : values) {
            stringValues.add(value.outValue());
          }
          Value invokeValue = code.createValue(newArray.getOutType(), newArray.getLocalInfo());
          InvokeNewArray invoke =
              new InvokeNewArray(dexItemFactory.stringArrayType, invokeValue, stringValues);
          for (Value value : newArray.inValues()) {
            value.removeUser(newArray);
          }
          newArray.outValue().replaceUsers(invokeValue);
          it.removeOrReplaceByDebugLocalRead();
          instructionToInsertForArray.put(invokeValue, invoke);
          storesToRemoveForArray.put(invokeValue, size);
        } else {
          // If there is only one element it is typically smaller to generate the array put
          // instruction instead of fill array data.
          if (size == 1) {
            continue;
          }
          int elementSize = newArray.type.elementSizeForPrimitiveArrayType();
          short[] contents = computeArrayFilledData(values, size, elementSize);
          if (contents == null) {
            continue;
          }
          if (block.hasCatchHandlers()) {
            continue;
          }
          int arraySize = newArray.size().getConstInstruction().asConstNumber().getIntValue();
          NewArrayFilledData fillArray =
              new NewArrayFilledData(newArray.outValue(), elementSize, arraySize, contents);
          fillArray.setPosition(newArray.getPosition());
          it.add(fillArray);
          storesToRemoveForArray.put(newArray.outValue(), size);
        }
      }
      // Second pass: remove all the array put instructions for the array for which we have
      // inserted a fill array data instruction instead.
      if (!storesToRemoveForArray.isEmpty()) {
        Set<BasicBlock> visitedBlocks = Sets.newIdentityHashSet();
        do {
          visitedBlocks.add(block);
          it = block.listIterator(code);
          while (it.hasNext()) {
            Instruction instruction = it.next();
            if (instruction.isArrayPut()) {
              Value array = instruction.asArrayPut().array();
              Integer toRemoveCount = storesToRemoveForArray.get(array);
              if (toRemoveCount != null) {
                if (toRemoveCount > 0) {
                  storesToRemoveForArray.put(array, --toRemoveCount);
                  it.remove();
                }
                if (toRemoveCount == 0) {
                  storesToRemoveForArray.put(array, --toRemoveCount);
                  Instruction construction = instructionToInsertForArray.get(array);
                  if (construction != null) {
                    // Set the position of the new array construction to be the position of the
                    // last removed put at which point we are now adding the construction.
                    construction.setPosition(instruction.getPosition());
                    it.add(construction);
                  }
                }
              }
            }
          }
          BasicBlock nextBlock = block.exit().isGoto() ? block.exit().asGoto().getTarget() : null;
          block = nextBlock != null && !visitedBlocks.contains(nextBlock) ? nextBlock : null;
        } while (block != null);
      }
    }
    assert code.isConsistentSSA(appView);
  }

  // TODO(mikaelpeltier) Manage that from and to instruction do not belong to the same block.
  private static boolean hasLocalOrLineChangeBetween(
      Instruction from, Instruction to, DexString localVar) {
    if (from.getBlock() != to.getBlock()) {
      return true;
    }
    if (from.getPosition().isSome()
        && to.getPosition().isSome()
        && !from.getPosition().equals(to.getPosition())) {
      return true;
    }
    Position position = null;
    for (Instruction instruction : from.getBlock().instructionsAfter(from)) {
      if (position == null) {
        if (instruction.getPosition().isSome()) {
          position = instruction.getPosition();
        }
      } else if (instruction.getPosition().isSome()
          && !position.equals(instruction.getPosition())) {
        return true;
      }
      if (instruction == to) {
        return false;
      }
      if (instruction.outValue() != null && instruction.outValue().hasLocalInfo()) {
        if (instruction.outValue().getLocalInfo().name == localVar) {
          return true;
        }
      }
    }
    throw new Unreachable();
  }

  public void simplifyDebugLocals(IRCode code) {
    for (BasicBlock block : code.blocks) {
      InstructionListIterator iterator = block.listIterator(code);
      while (iterator.hasNext()) {
        Instruction prevInstruction = iterator.peekPrevious();
        Instruction instruction = iterator.next();
        if (instruction.isDebugLocalWrite()) {
          assert instruction.inValues().size() == 1;
          Value inValue = instruction.inValues().get(0);
          DebugLocalInfo localInfo = instruction.outValue().getLocalInfo();
          DexString localName = localInfo.name;
          if (!inValue.hasLocalInfo() &&
              inValue.numberOfAllUsers() == 1 &&
              inValue.definition != null &&
              !hasLocalOrLineChangeBetween(inValue.definition, instruction, localName)) {
            inValue.setLocalInfo(localInfo);
            instruction.outValue().replaceUsers(inValue);
            Value overwrittenLocal = instruction.removeDebugValue(localInfo);
            if (overwrittenLocal != null) {
              overwrittenLocal.addDebugLocalEnd(inValue.definition);
            }
            if (prevInstruction != null &&
                (prevInstruction.outValue() == null
                    || !prevInstruction.outValue().hasLocalInfo()
                    || !instruction.getDebugValues().contains(prevInstruction.outValue()))) {
              instruction.moveDebugValues(prevInstruction);
            }
            iterator.removeOrReplaceByDebugLocalRead();
          }
        }
      }
    }
  }

  private static class CSEExpressionEquivalence extends Equivalence<Instruction> {

    private final InternalOptions options;

    private CSEExpressionEquivalence(InternalOptions options) {
      this.options = options;
    }

    @Override
    protected boolean doEquivalent(Instruction a, Instruction b) {
      // Some Dalvik VMs incorrectly handle Cmp instructions which leads to a requirement
      // that we do not perform common subexpression elimination for them. See comment on
      // canHaveCmpLongBug for details.
      if (a.isCmp() && options.canHaveCmpLongBug()) {
        return false;
      }
      // Note that we don't consider positions because CSE can at most remove an instruction.
      if (!a.identicalNonValueNonPositionParts(b)) {
        return false;
      }
      // For commutative binary operations any order of in-values are equal.
      if (a.isBinop() && a.asBinop().isCommutative()) {
        Value a0 = a.inValues().get(0);
        Value a1 = a.inValues().get(1);
        Value b0 = b.inValues().get(0);
        Value b1 = b.inValues().get(1);
        return (identicalValue(a0, b0) && identicalValue(a1, b1))
            || (identicalValue(a0, b1) && identicalValue(a1, b0));
      } else {
        // Compare all in-values.
        assert a.inValues().size() == b.inValues().size();
        for (int i = 0; i < a.inValues().size(); i++) {
          if (!identicalValue(a.inValues().get(i), b.inValues().get(i))) {
            return false;
          }
        }
        return true;
      }
    }

    @Override
    protected int doHash(Instruction instruction) {
      final int prime = 29;
      int hash = instruction.getClass().hashCode();
      if (instruction.isBinop()) {
        Binop binop = instruction.asBinop();
        Value in0 = instruction.inValues().get(0);
        Value in1 = instruction.inValues().get(1);
        if (binop.isCommutative()) {
          hash += hash * prime + getHashCode(in0) * getHashCode(in1);
        } else {
          hash += hash * prime + getHashCode(in0);
          hash += hash * prime + getHashCode(in1);
        }
        return hash;
      } else {
        for (Value value : instruction.inValues()) {
          hash += hash * prime + getHashCode(value);
        }
      }
      return hash;
    }

    private static boolean identicalValue(Value a, Value b) {
      if (a.equals(b)) {
        return true;
      }
      if (a.isConstNumber() && b.isConstNumber()) {
        // Do not take assumption that constants are canonicalized.
        return a.definition.identicalNonValueNonPositionParts(b.definition);
      }
      return false;
    }

    private static int getHashCode(Value a) {
      if (a.isConstNumber()) {
        // Do not take assumption that constants are canonicalized.
        return Long.hashCode(a.definition.asConstNumber().getRawValue());
      }
      return a.hashCode();
    }
  }

  private boolean shareCatchHandlers(Instruction i0, Instruction i1) {
    if (!i0.instructionTypeCanThrow()) {
      assert !i1.instructionTypeCanThrow();
      return true;
    }
    assert i1.instructionTypeCanThrow();
    // TODO(sgjesse): This could be even better by checking for the exceptions thrown, e.g. div
    // and rem only ever throw ArithmeticException.
    CatchHandlers<BasicBlock> ch0 = i0.getBlock().getCatchHandlers();
    CatchHandlers<BasicBlock> ch1 = i1.getBlock().getCatchHandlers();
    return ch0.equals(ch1);
  }

  private boolean isCSEInstructionCandidate(Instruction instruction) {
    return (instruction.isBinop()
        || instruction.isUnop()
        || instruction.isInstanceOf()
        || instruction.isCheckCast())
        && instruction.getLocalInfo() == null
        && !instruction.hasInValueWithLocalInfo();
  }

  private boolean hasCSECandidate(IRCode code, int noCandidate) {
    for (BasicBlock block : code.blocks) {
      for (Instruction instruction : block.getInstructions()) {
        if (isCSEInstructionCandidate(instruction)) {
          return true;
        }
      }
      block.mark(noCandidate);
    }
    return false;
  }

  public void commonSubexpressionElimination(IRCode code) {
    int noCandidate = code.reserveMarkingColor();
    if (hasCSECandidate(code, noCandidate)) {
      final ListMultimap<Wrapper<Instruction>, Value> instructionToValue =
          ArrayListMultimap.create();
      final CSEExpressionEquivalence equivalence = new CSEExpressionEquivalence(options);
      final DominatorTree dominatorTree = new DominatorTree(code);
      for (int i = 0; i < dominatorTree.getSortedBlocks().length; i++) {
        BasicBlock block = dominatorTree.getSortedBlocks()[i];
        if (block.isMarked(noCandidate)) {
          continue;
        }
        InstructionListIterator iterator = block.listIterator(code);
        while (iterator.hasNext()) {
          Instruction instruction = iterator.next();
          if (isCSEInstructionCandidate(instruction)) {
            List<Value> candidates = instructionToValue.get(equivalence.wrap(instruction));
            boolean eliminated = false;
            if (candidates.size() > 0) {
              for (Value candidate : candidates) {
                if (dominatorTree.dominatedBy(block, candidate.definition.getBlock())
                    && shareCatchHandlers(instruction, candidate.definition)) {
                  instruction.outValue().replaceUsers(candidate);
                  candidate.uniquePhiUsers().forEach(Phi::removeTrivialPhi);
                  eliminated = true;
                  iterator.removeOrReplaceByDebugLocalRead();
                  break;  // Don't try any more candidates.
                }
              }
            }
            if (!eliminated) {
              instructionToValue.put(equivalence.wrap(instruction), instruction.outValue());
            }
          }
        }
      }
    }
    code.returnMarkingColor(noCandidate);
    assert code.isConsistentSSA(appView);
  }

  static class ControlFlowSimplificationResult {
    private boolean anyAffectedValues;
    private boolean anySimplifications;

    private ControlFlowSimplificationResult(boolean anyAffectedValues, boolean anySimplifications) {
      assert !anyAffectedValues || anySimplifications;
      this.anyAffectedValues = anyAffectedValues;
      this.anySimplifications = anySimplifications;
    }

    public boolean anyAffectedValues() {
      return anyAffectedValues;
    }

    public boolean anySimplifications() {
      return anySimplifications;
    }
  }

  public boolean simplifyControlFlow(IRCode code) {
    boolean anyAffectedValues = rewriteSwitch(code);
    anyAffectedValues |= simplifyIf(code).anyAffectedValues();
    return anyAffectedValues;
  }

  public ControlFlowSimplificationResult simplifyIf(IRCode code) {
    BasicBlockBehavioralSubsumption behavioralSubsumption =
        new BasicBlockBehavioralSubsumption(appView, code);
    boolean simplified = false;
    for (BasicBlock block : code.blocks) {
      // Skip removed (= unreachable) blocks.
      if (block.getNumber() != 0 && block.getPredecessors().isEmpty()) {
        continue;
      }
      if (block.exit().isIf()) {
        flipIfBranchesIfNeeded(code, block);
        if (rewriteIfWithConstZero(code, block)) {
          simplified = true;
        }

        if (simplifyKnownBooleanCondition(code, block)) {
          simplified = true;
          if (!block.exit().isIf()) {
            continue;
          }
        }

        // Simplify if conditions when possible.
        If theIf = block.exit().asIf();
        if (theIf.isZeroTest()) {
          if (simplifyIfZeroTest(code, block, theIf)) {
            simplified = true;
            continue;
          }
        } else {
          if (simplifyNonIfZeroTest(code, block, theIf)) {
            simplified = true;
            continue;
          }
        }

        // Unable to determine which branch will be taken. Check if the true target can safely be
        // rewritten to the false target.
        if (behavioralSubsumption.isSubsumedBy(theIf.getTrueTarget(), theIf.fallthroughBlock())) {
          simplifyIfWithKnownCondition(code, block, theIf, theIf.fallthroughBlock());
          simplified = true;
        }
      }
    }
    Set<Value> affectedValues = code.removeUnreachableBlocks();
    if (!affectedValues.isEmpty()) {
      new TypeAnalysis(appView).narrowing(affectedValues);
    }
    assert code.isConsistentSSA(appView);
    return new ControlFlowSimplificationResult(!affectedValues.isEmpty(), simplified);
  }

  private boolean simplifyIfZeroTest(IRCode code, BasicBlock block, If theIf) {
    Value lhs = theIf.lhs();
    Value lhsRoot = lhs.getAliasedValue();
    if (lhsRoot.isConstNumber()) {
      ConstNumber cond = lhsRoot.getConstInstruction().asConstNumber();
      BasicBlock target = theIf.targetFromCondition(cond);
      simplifyIfWithKnownCondition(code, block, theIf, target);
      return true;
    }

    if (theIf.isNullTest()) {
      assert theIf.getType() == Type.EQ || theIf.getType() == Type.NE;

      if (lhs.isAlwaysNull(appView)) {
        simplifyIfWithKnownCondition(code, block, theIf, theIf.targetFromNullObject());
        return true;
      }

      if (lhs.isNeverNull()) {
        simplifyIfWithKnownCondition(code, block, theIf, theIf.targetFromNonNullObject());
        return true;
      }
    }

    if (theIf.getType() == Type.EQ || theIf.getType() == Type.NE) {
      AbstractValue lhsAbstractValue = lhs.getAbstractValue(appView, code.context());
      if (lhsAbstractValue.isConstantOrNonConstantNumberValue()
          && !lhsAbstractValue.asConstantOrNonConstantNumberValue().containsInt(0)) {
        // Value doesn't contain zero at all.
        simplifyIfWithKnownCondition(code, block, theIf, theIf.targetFromCondition(1));
        return true;
      }
    }

    if (lhs.hasValueRange()) {
      LongInterval interval = lhs.getValueRange();
      if (!interval.containsValue(0)) {
        // Interval doesn't contain zero at all.
        int sign = Long.signum(interval.getMin());
        simplifyIfWithKnownCondition(code, block, theIf, sign);
        return true;
      }

      // Interval contains zero.
      switch (theIf.getType()) {
        case GE:
        case LT:
          // [a, b] >= 0 is always true if a >= 0.
          // [a, b] < 0 is always false if a >= 0.
          // In both cases a zero condition takes the right branch.
          if (interval.getMin() == 0) {
            simplifyIfWithKnownCondition(code, block, theIf, 0);
            return true;
          }
          break;

        case LE:
        case GT:
          // [a, b] <= 0 is always true if b <= 0.
          // [a, b] > 0 is always false if b <= 0.
          // In both cases a zero condition takes the right branch.
          if (interval.getMax() == 0) {
            simplifyIfWithKnownCondition(code, block, theIf, 0);
            return true;
          }
          break;

        case EQ:
        case NE:
          // Only a single element interval [0, 0] can be dealt with here.
          // Such intervals should have been replaced by constants.
          assert !interval.isSingleValue();
          break;
      }
    }
    return false;
  }

  private boolean simplifyNonIfZeroTest(IRCode code, BasicBlock block, If theIf) {
    Value lhs = theIf.lhs();
    Value lhsRoot = lhs.getAliasedValue();
    Value rhs = theIf.rhs();
    Value rhsRoot = rhs.getAliasedValue();
    if (lhsRoot == rhsRoot) {
      // Comparing the same value.
      simplifyIfWithKnownCondition(code, block, theIf, theIf.targetFromCondition(0));
      return true;
    }

    if (lhsRoot.isDefinedByInstructionSatisfying(Instruction::isCreatingInstanceOrArray)
        && rhsRoot.isDefinedByInstructionSatisfying(Instruction::isCreatingInstanceOrArray)) {
      // Comparing two newly created objects.
      assert theIf.getType() == Type.EQ || theIf.getType() == Type.NE;
      simplifyIfWithKnownCondition(code, block, theIf, theIf.targetFromCondition(1));
      return true;
    }

    if (lhsRoot.isConstNumber() && rhsRoot.isConstNumber()) {
      // Zero test with a constant of comparison between between two constants.
      ConstNumber left = lhsRoot.getConstInstruction().asConstNumber();
      ConstNumber right = rhsRoot.getConstInstruction().asConstNumber();
      BasicBlock target = theIf.targetFromCondition(left, right);
      simplifyIfWithKnownCondition(code, block, theIf, target);
      return true;
    }

    if (theIf.getType() == Type.EQ || theIf.getType() == Type.NE) {
      AbstractValue lhsAbstractValue = lhs.getAbstractValue(appView, code.context());
      AbstractValue rhsAbstractValue = rhs.getAbstractValue(appView, code.context());
      if (lhsAbstractValue.isConstantOrNonConstantNumberValue()
          && rhsAbstractValue.isConstantOrNonConstantNumberValue()) {
        ConstantOrNonConstantNumberValue lhsNumberValue =
            lhsAbstractValue.asConstantOrNonConstantNumberValue();
        ConstantOrNonConstantNumberValue rhsNumberValue =
            rhsAbstractValue.asConstantOrNonConstantNumberValue();
        if (!lhsNumberValue.mayOverlapWith(rhsNumberValue)) {
          // No overlap.
          simplifyIfWithKnownCondition(code, block, theIf, 1);
          return true;
        }
      }
    }

    if (lhs.hasValueRange() && rhs.hasValueRange()) {
      // Zero test with a value range, or comparison between between two values,
      // each with a value ranges.
      LongInterval leftRange = lhs.getValueRange();
      LongInterval rightRange = rhs.getValueRange();
      // Two overlapping ranges. Check for single point overlap.
      if (!leftRange.overlapsWith(rightRange)) {
        // No overlap.
        int cond = Long.signum(leftRange.getMin() - rightRange.getMin());
        simplifyIfWithKnownCondition(code, block, theIf, cond);
        return true;
      }

      // The two intervals overlap. We can simplify if they overlap at the end points.
      switch (theIf.getType()) {
        case LT:
        case GE:
          // [a, b] < [c, d] is always false when a == d.
          // [a, b] >= [c, d] is always true when a == d.
          // In both cases 0 condition will choose the right branch.
          if (leftRange.getMin() == rightRange.getMax()) {
            simplifyIfWithKnownCondition(code, block, theIf, 0);
            return true;
          }
          break;
        case GT:
        case LE:
          // [a, b] > [c, d] is always false when b == c.
          // [a, b] <= [c, d] is always true when b == c.
          // In both cases 0 condition will choose the right branch.
          if (leftRange.getMax() == rightRange.getMin()) {
            simplifyIfWithKnownCondition(code, block, theIf, 0);
            return true;
          }
          break;
        case EQ:
        case NE:
          // Since there is overlap EQ and NE cannot be determined.
          break;
      }
    }

    if (theIf.getType() == Type.EQ || theIf.getType() == Type.NE) {
      ProgramMethod context = code.context();
      AbstractValue abstractValue = lhs.getAbstractValue(appView, context);
      if (abstractValue.isSingleConstClassValue()) {
        AbstractValue otherAbstractValue = rhs.getAbstractValue(appView, context);
        if (otherAbstractValue.isSingleConstClassValue()) {
          SingleConstClassValue singleConstClassValue = abstractValue.asSingleConstClassValue();
          SingleConstClassValue otherSingleConstClassValue =
              otherAbstractValue.asSingleConstClassValue();
          simplifyIfWithKnownCondition(
              code,
              block,
              theIf,
              BooleanUtils.intValue(
                  singleConstClassValue.getType() != otherSingleConstClassValue.getType()));
          return true;
        }
        return false;
      }

      if (abstractValue.isSingleFieldValue()) {
        AbstractValue otherAbstractValue = rhs.getAbstractValue(appView, context);
        if (otherAbstractValue.isSingleFieldValue()) {
          SingleFieldValue singleFieldValue = abstractValue.asSingleFieldValue();
          SingleFieldValue otherSingleFieldValue = otherAbstractValue.asSingleFieldValue();
          if (singleFieldValue.getField() == otherSingleFieldValue.getField()) {
            simplifyIfWithKnownCondition(code, block, theIf, 0);
            return true;
          }

          DexClass holder = appView.definitionForHolder(singleFieldValue.getField());
          DexEncodedField field = singleFieldValue.getField().lookupOnClass(holder);
          if (field != null && field.isEnum()) {
            DexClass otherHolder = appView.definitionForHolder(otherSingleFieldValue.getField());
            DexEncodedField otherField =
                otherSingleFieldValue.getField().lookupOnClass(otherHolder);
            if (otherField != null && otherField.isEnum()) {
              simplifyIfWithKnownCondition(code, block, theIf, 1);
              return true;
            }
          }
        }
      }
    }

    return false;
  }

  private void simplifyIfWithKnownCondition(
      IRCode code, BasicBlock block, If theIf, BasicBlock target) {
    BasicBlock deadTarget =
        target == theIf.getTrueTarget() ? theIf.fallthroughBlock() : theIf.getTrueTarget();
    rewriteIfToGoto(code, block, theIf, target, deadTarget);
  }

  private void simplifyIfWithKnownCondition(IRCode code, BasicBlock block, If theIf, int cond) {
    simplifyIfWithKnownCondition(code, block, theIf, theIf.targetFromCondition(cond));
  }

  /**
   * This optimization exploits that we can sometimes learn the constant value of an SSA value that
   * flows into an if-eq of if-neq instruction.
   *
   * <p>Consider the following example:
   *
   * <pre>
   * 1. if (obj != null) {
   * 2.  return doStuff();
   * 3. }
   * 4. return null;
   * </pre>
   *
   * <p>Since we know that `obj` is null in all blocks that are dominated by the false-target of the
   * if-instruction in line 1, we can safely replace the null-constant in line 4 by `obj`, and
   * thereby save a const-number instruction.
   */
  public void redundantConstNumberRemoval(IRCode code) {
    if (appView.options().canHaveDalvikIntUsedAsNonIntPrimitiveTypeBug()
        && !appView.options().testing.forceRedundantConstNumberRemoval) {
      // See also b/124152497.
      return;
    }

    if (!code.metadata().mayHaveConstNumber()) {
      return;
    }

    Supplier<Long2ReferenceMap<List<ConstNumber>>> constantsByValue =
        Suppliers.memoize(() -> getConstantsByValue(code));
    Supplier<DominatorTree> dominatorTree = Suppliers.memoize(() -> new DominatorTree(code));

    boolean changed = false;
    for (BasicBlock block : code.blocks) {
      Instruction lastInstruction = block.getInstructions().getLast();
      if (!lastInstruction.isIf()) {
        continue;
      }

      If ifInstruction = lastInstruction.asIf();
      Type type = ifInstruction.getType();

      Value lhs = ifInstruction.inValues().get(0);
      Value rhs = !ifInstruction.isZeroTest() ? ifInstruction.inValues().get(1) : null;

      if (!ifInstruction.isZeroTest() && !lhs.isConstNumber() && !rhs.isConstNumber()) {
        // We can only conclude anything from an if-instruction if it is a zero-test or if one of
        // the two operands is a constant.
        continue;
      }

      // If the type is neither EQ nor NE, we cannot conclude anything about any of the in-values
      // of the if-instruction from the outcome of the if-instruction.
      if (type != Type.EQ && type != Type.NE) {
        continue;
      }

      BasicBlock trueTarget, falseTarget;
      if (type == Type.EQ) {
        trueTarget = ifInstruction.getTrueTarget();
        falseTarget = ifInstruction.fallthroughBlock();
      } else {
        falseTarget = ifInstruction.getTrueTarget();
        trueTarget = ifInstruction.fallthroughBlock();
      }

      if (ifInstruction.isZeroTest()) {
        changed |=
            replaceDominatedConstNumbers(0, lhs, trueTarget, constantsByValue, code, dominatorTree);
        if (lhs.knownToBeBoolean()) {
          changed |=
              replaceDominatedConstNumbers(
                  1, lhs, falseTarget, constantsByValue, code, dominatorTree);
        }
      } else {
        assert rhs != null;
        if (lhs.isConstNumber()) {
          ConstNumber lhsAsNumber = lhs.getConstInstruction().asConstNumber();
          changed |=
              replaceDominatedConstNumbers(
                  lhsAsNumber.getRawValue(),
                  rhs,
                  trueTarget,
                  constantsByValue,
                  code,
                  dominatorTree);
          if (lhs.knownToBeBoolean() && rhs.knownToBeBoolean()) {
            changed |=
                replaceDominatedConstNumbers(
                    negateBoolean(lhsAsNumber),
                    rhs,
                    falseTarget,
                    constantsByValue,
                    code,
                    dominatorTree);
          }
        } else {
          assert rhs.isConstNumber();
          ConstNumber rhsAsNumber = rhs.getConstInstruction().asConstNumber();
          changed |=
              replaceDominatedConstNumbers(
                  rhsAsNumber.getRawValue(),
                  lhs,
                  trueTarget,
                  constantsByValue,
                  code,
                  dominatorTree);
          if (lhs.knownToBeBoolean() && rhs.knownToBeBoolean()) {
            changed |=
                replaceDominatedConstNumbers(
                    negateBoolean(rhsAsNumber),
                    lhs,
                    falseTarget,
                    constantsByValue,
                    code,
                    dominatorTree);
          }
        }
      }

      if (constantsByValue.get().isEmpty()) {
        break;
      }
    }

    if (changed) {
      code.removeAllDeadAndTrivialPhis();
    }
    assert code.isConsistentSSA(appView);
  }

  private static Long2ReferenceMap<List<ConstNumber>> getConstantsByValue(IRCode code) {
    // A map from the raw value of constants in `code` to the const number instructions that define
    // the given raw value (irrespective of the type of the raw value).
    Long2ReferenceMap<List<ConstNumber>> constantsByValue = new Long2ReferenceOpenHashMap<>();

    // Initialize `constantsByValue`.
    for (Instruction instruction : code.instructions()) {
      if (instruction.isConstNumber()) {
        ConstNumber constNumber = instruction.asConstNumber();
        if (constNumber.outValue().hasLocalInfo()) {
          // Not necessarily constant, because it could be changed in the debugger.
          continue;
        }
        long rawValue = constNumber.getRawValue();
        if (constantsByValue.containsKey(rawValue)) {
          constantsByValue.get(rawValue).add(constNumber);
        } else {
          List<ConstNumber> list = new ArrayList<>();
          list.add(constNumber);
          constantsByValue.put(rawValue, list);
        }
      }
    }
    return constantsByValue;
  }

  private static int negateBoolean(ConstNumber number) {
    assert number.outValue().knownToBeBoolean();
    return number.getRawValue() == 0 ? 1 : 0;
  }

  private boolean replaceDominatedConstNumbers(
      long withValue,
      Value newValue,
      BasicBlock dominator,
      Supplier<Long2ReferenceMap<List<ConstNumber>>> constantsByValueSupplier,
      IRCode code,
      Supplier<DominatorTree> dominatorTree) {
    if (newValue.hasLocalInfo()) {
      // We cannot replace a constant with a value that has local info, because that could change
      // debugging behavior.
      return false;
    }

    Long2ReferenceMap<List<ConstNumber>> constantsByValue = constantsByValueSupplier.get();
    List<ConstNumber> constantsWithValue = constantsByValue.get(withValue);
    if (constantsWithValue == null || constantsWithValue.isEmpty()) {
      return false;
    }

    boolean changed = false;

    ListIterator<ConstNumber> constantWithValueIterator = constantsWithValue.listIterator();
    while (constantWithValueIterator.hasNext()) {
      ConstNumber constNumber = constantWithValueIterator.next();
      Value value = constNumber.outValue();
      assert !value.hasLocalInfo();
      assert constNumber.getRawValue() == withValue;

      BasicBlock block = constNumber.getBlock();

      // If the following condition does not hold, then the if-instruction does not dominate the
      // block containing the constant, although the true or false target does.
      if (block == dominator && block.getPredecessors().size() != 1) {
        // This should generally not happen, but it is possible to write bytecode where it does.
        assert false;
        continue;
      }

      if (value.knownToBeBoolean() && !newValue.knownToBeBoolean()) {
        // We cannot replace a boolean by a none-boolean since that can lead to verification
        // errors. For example, the following code fails with "register v1 has type Imprecise
        // Constant: 127 but expected Boolean return-1nr".
        //
        //   public boolean convertIntToBoolean(int v1) {
        //       const/4 v0, 0x1
        //       if-eq v1, v0, :eq_true
        //       const/4 v1, 0x0
        //     :eq_true
        //       return v1
        //   }
        continue;
      }

      if (dominatorTree.get().dominatedBy(block, dominator)) {
        if (newValue.getType().lessThanOrEqual(value.getType(), appView)) {
          value.replaceUsers(newValue);
          block.listIterator(code, constNumber).removeOrReplaceByDebugLocalRead();
          constantWithValueIterator.remove();
          changed = true;
        } else if (value.getType().isNullType()) {
          // TODO(b/120257211): Need a mechanism to determine if `newValue` can be used at all of
          // the use sites of `value` without introducing a type error.
        }
      }
    }

    if (constantsWithValue.isEmpty()) {
      constantsByValue.remove(withValue);
    }

    return changed;
  }

  // Find all instructions that always throw, split the block after each such instruction and follow
  // it with a block throwing a null value (which should result in NPE). Note that this throw is not
  // expected to be ever reached, but is intended to satisfy verifier.
  public void optimizeAlwaysThrowingInstructions(IRCode code) {
    Set<Value> affectedValues = Sets.newIdentityHashSet();
    Set<BasicBlock> blocksToRemove = Sets.newIdentityHashSet();
    ListIterator<BasicBlock> blockIterator = code.listIterator();
    ProgramMethod context = code.context();
    boolean hasUnlinkedCatchHandlers = false;
    while (blockIterator.hasNext()) {
      BasicBlock block = blockIterator.next();
      if (block.getNumber() != 0 && block.getPredecessors().isEmpty()) {
        continue;
      }
      if (blocksToRemove.contains(block)) {
        continue;
      }
      InstructionListIterator instructionIterator = block.listIterator(code);
      while (instructionIterator.hasNext()) {
        Instruction instruction = instructionIterator.next();
        if (instruction.throwsOnNullInput()) {
          Value inValue = instruction.getNonNullInput();
          if (inValue.isAlwaysNull(appView)) {
            // Insert `throw null` after the instruction if it is not guaranteed to throw an NPE.
            if (instruction.isAssume()) {
              // If this assume is in a block with catch handlers, then the out-value can have
              // usages in the catch handler if the block's throwing instruction comes after the
              // assume instruction. In this case, the catch handler is also guaranteed to be dead,
              // so we detach it from the current block.
              if (block.hasCatchHandlers()
                  && block.isInstructionBeforeThrowingInstruction(instruction)) {
                for (CatchHandler<BasicBlock> catchHandler : block.getCatchHandlers()) {
                  catchHandler.getTarget().unlinkCatchHandler();
                }
                hasUnlinkedCatchHandlers = true;
              }
            } else if (instruction.isInstanceFieldInstruction()) {
              InstanceFieldInstruction instanceFieldInstruction =
                  instruction.asInstanceFieldInstruction();
              if (instanceFieldInstruction.instructionInstanceCanThrow(
                  appView, context, SideEffectAssumption.RECEIVER_NOT_NULL)) {
                instructionIterator.next();
              }
            } else if (instruction.isInvokeMethodWithReceiver()) {
              InvokeMethodWithReceiver invoke = instruction.asInvokeMethodWithReceiver();
              SideEffectAssumption assumption =
                  SideEffectAssumption.RECEIVER_NOT_NULL.join(
                      SideEffectAssumption.INVOKED_METHOD_DOES_NOT_HAVE_SIDE_EFFECTS);
              if (invoke.instructionMayHaveSideEffects(appView, context, assumption)) {
                instructionIterator.next();
              }
            }
            instructionIterator.replaceCurrentInstructionWithThrowNull(
                appView, code, blockIterator, blocksToRemove, affectedValues);
            continue;
          }
        }

        if (!instruction.isInvokeMethod()) {
          continue;
        }

        InvokeMethod invoke = instruction.asInvokeMethod();
        DexClassAndMethod singleTarget = invoke.lookupSingleTarget(appView, code.context());
        if (singleTarget == null) {
          continue;
        }

        MethodOptimizationInfo optimizationInfo =
            singleTarget.getDefinition().getOptimizationInfo();

        // If the invoke instruction is a null check, we can remove it.
        boolean isNullCheck = false;
        if (optimizationInfo.hasNonNullParamOrThrow()) {
          BitSet nonNullParamOrThrow = optimizationInfo.getNonNullParamOrThrow();
          for (int i = 0; i < invoke.arguments().size(); i++) {
            Value argument = invoke.arguments().get(i);
            if (argument.isAlwaysNull(appView) && nonNullParamOrThrow.get(i)) {
              isNullCheck = true;
              break;
            }
          }
        }
        // If the invoke instruction never returns normally, we can insert a throw null instruction
        // after the invoke.
        if (isNullCheck || optimizationInfo.neverReturnsNormally()) {
          instructionIterator.setInsertionPosition(invoke.getPosition());
          instructionIterator.next();
          instructionIterator.replaceCurrentInstructionWithThrowNull(
              appView, code, blockIterator, blocksToRemove, affectedValues);
          instructionIterator.unsetInsertionPosition();
        }
      }
    }
    code.removeBlocks(blocksToRemove);
    if (hasUnlinkedCatchHandlers) {
      affectedValues.addAll(code.removeUnreachableBlocks());
    }
    assert code.getUnreachableBlocks().isEmpty();
    if (!affectedValues.isEmpty()) {
      new TypeAnalysis(appView).narrowing(affectedValues);
    }
    assert code.isConsistentSSA(appView);
  }

  /* Identify simple diamond shapes converting boolean true/false to 1/0. We consider the forms:
   *
   * (1)
   *
   *      [dbg pos x]             [dbg pos x]
   *   ifeqz booleanValue       ifnez booleanValue
   *      /        \              /        \
   * [dbg pos x][dbg pos x]  [dbg pos x][dbg pos x]
   *  [const 0]  [const 1]    [const 1]  [const 0]
   *    goto      goto          goto      goto
   *      \        /              \        /
   *      phi(0, 1)                phi(1, 0)
   *
   * which can be replaced by a fallthrough and the phi value can be replaced
   * with the boolean value itself.
   *
   * (2)
   *
   *      [dbg pos x]              [dbg pos x]
   *    ifeqz booleanValue       ifnez booleanValue
   *      /        \              /        \
   * [dbg pos x][dbg pos x]  [dbg pos x][dbg pos x]
   *  [const 1]  [const 0]   [const 0]  [const 1]
   *    goto      goto          goto      goto
   *      \        /              \        /
   *      phi(1, 0)                phi(0, 1)
   *
   * which can be replaced by a fallthrough and the phi value can be replaced
   * by an xor instruction which is smaller.
   */
  private boolean simplifyKnownBooleanCondition(IRCode code, BasicBlock block) {
    If theIf = block.exit().asIf();
    Value testValue = theIf.inValues().get(0);
    if (theIf.isZeroTest() && testValue.knownToBeBoolean()) {
      BasicBlock trueBlock = theIf.getTrueTarget();
      BasicBlock falseBlock = theIf.fallthroughBlock();
      if (isBlockSupportedBySimplifyKnownBooleanCondition(trueBlock) &&
          isBlockSupportedBySimplifyKnownBooleanCondition(falseBlock) &&
          trueBlock.getSuccessors().get(0) == falseBlock.getSuccessors().get(0)) {
        BasicBlock targetBlock = trueBlock.getSuccessors().get(0);
        if (targetBlock.getPredecessors().size() == 2) {
          int trueIndex = targetBlock.getPredecessors().indexOf(trueBlock);
          int falseIndex = trueIndex == 0 ? 1 : 0;
          int deadPhis = 0;
          // Locate the phis that have the same value as the boolean and replace them
          // by the boolean in all users.
          for (Phi phi : targetBlock.getPhis()) {
            Value trueValue = phi.getOperand(trueIndex);
            Value falseValue = phi.getOperand(falseIndex);
            if (trueValue.isConstNumber() && falseValue.isConstNumber()) {
              ConstNumber trueNumber = trueValue.getConstInstruction().asConstNumber();
              ConstNumber falseNumber = falseValue.getConstInstruction().asConstNumber();
              if ((theIf.getType() == Type.EQ &&
                  trueNumber.isIntegerZero() &&
                  falseNumber.isIntegerOne()) ||
                  (theIf.getType() == Type.NE &&
                      trueNumber.isIntegerOne() &&
                      falseNumber.isIntegerZero())) {
                phi.replaceUsers(testValue);
                deadPhis++;
              } else if ((theIf.getType() == Type.NE &&
                           trueNumber.isIntegerZero() &&
                           falseNumber.isIntegerOne()) ||
                         (theIf.getType() == Type.EQ &&
                           trueNumber.isIntegerOne() &&
                           falseNumber.isIntegerZero())) {
                Value newOutValue = code.createValue(phi.getType(), phi.getLocalInfo());
                ConstNumber cstToUse = trueNumber.isIntegerOne() ? trueNumber : falseNumber;
                BasicBlock phiBlock = phi.getBlock();
                Position phiPosition = phiBlock.getPosition();
                int insertIndex = 0;
                if (cstToUse.getBlock() == trueBlock || cstToUse.getBlock() == falseBlock) {
                  // The constant belongs to the block to remove, create a new one.
                  cstToUse = ConstNumber.copyOf(code, cstToUse);
                  cstToUse.setBlock(phiBlock);
                  cstToUse.setPosition(phiPosition);
                  phiBlock.getInstructions().add(insertIndex++, cstToUse);
                }
                phi.replaceUsers(newOutValue);
                Instruction newInstruction = new Xor(NumericType.INT, newOutValue, testValue,
                    cstToUse.outValue());
                newInstruction.setBlock(phiBlock);
                // The xor is replacing a phi so it does not have an actual position.
                newInstruction.setPosition(phiPosition);
                phiBlock.listIterator(code, insertIndex).add(newInstruction);
                deadPhis++;
              }
            }
          }
          // If all phis were removed, there is no need for the diamond shape anymore
          // and it can be rewritten to a goto to one of the branches.
          if (deadPhis == targetBlock.getPhis().size()) {
            rewriteIfToGoto(code, block, theIf, trueBlock, falseBlock);
            return true;
          }
          return deadPhis > 0;
        }
      }
    }
    return false;
  }

  private boolean isBlockSupportedBySimplifyKnownBooleanCondition(BasicBlock b) {
    if (b.isTrivialGoto()) {
      return true;
    }

    int instructionSize = b.getInstructions().size();
    if (b.exit().isGoto() && (instructionSize == 2 || instructionSize == 3)) {
      Instruction constInstruction = b.getInstructions().get(instructionSize - 2);
      if (constInstruction.isConstNumber()) {
        if (!constInstruction.asConstNumber().isIntegerOne() &&
            !constInstruction.asConstNumber().isIntegerZero()) {
          return false;
        }
        if (instructionSize == 2) {
          return true;
        }
        Instruction firstInstruction = b.getInstructions().getFirst();
        if (firstInstruction.isDebugPosition()) {
          assert b.getPredecessors().size() == 1;
          BasicBlock predecessorBlock = b.getPredecessors().get(0);
          InstructionIterator it = predecessorBlock.iterator(predecessorBlock.exit());
          Instruction previousPosition = null;
          while (it.hasPrevious() && !(previousPosition = it.previous()).isDebugPosition()) {
            // Intentionally empty.
          }
          if (previousPosition != null) {
            return previousPosition.getPosition() == firstInstruction.getPosition();
          }
        }
      }
    }

    return false;
  }

  private void rewriteIfToGoto(
      IRCode code, BasicBlock block, If theIf, BasicBlock target, BasicBlock deadTarget) {
    deadTarget.unlinkSinglePredecessorSiblingsAllowed();
    assert theIf == block.exit();
    block.replaceLastInstruction(new Goto(), code);
    assert block.exit().isGoto();
    assert block.exit().asGoto().getTarget() == target;
  }

  private void insertNotNullCheck(
      BasicBlock block,
      InstructionListIterator iterator,
      If theIf,
      BasicBlock target,
      BasicBlock deadTarget,
      Position position) {
    deadTarget.unlinkSinglePredecessorSiblingsAllowed();
    assert theIf == block.exit();
    iterator.previous();
    Instruction instruction;
    DexMethod getClassMethod = appView.dexItemFactory().objectMembers.getClass;
    instruction = new InvokeVirtual(getClassMethod, null, ImmutableList.of(theIf.lhs()));
    instruction.setPosition(position);
    iterator.add(instruction);
    iterator.next();
    iterator.replaceCurrentInstruction(new Goto());
    assert block.exit().isGoto();
    assert block.exit().asGoto().getTarget() == target;
  }

  private boolean rewriteIfWithConstZero(IRCode code, BasicBlock block) {
    If theIf = block.exit().asIf();
    if (theIf.isZeroTest()) {
      return false;
    }

    Value leftValue = theIf.lhs();
    Value rightValue = theIf.rhs();
    if (leftValue.isConstNumber() || rightValue.isConstNumber()) {
      if (leftValue.isConstNumber()) {
        if (leftValue.getConstInstruction().asConstNumber().isZero()) {
          If ifz = new If(theIf.getType().forSwappedOperands(), rightValue);
          block.replaceLastInstruction(ifz, code);
          assert block.exit() == ifz;
          return true;
        }
      } else if (rightValue.getConstInstruction().asConstNumber().isZero()) {
        If ifz = new If(theIf.getType(), leftValue);
        block.replaceLastInstruction(ifz, code);
        assert block.exit() == ifz;
        return true;
      }
    }

    return false;
  }

  private boolean flipIfBranchesIfNeeded(IRCode code, BasicBlock block) {
    If theIf = block.exit().asIf();
    BasicBlock trueTarget = theIf.getTrueTarget();
    BasicBlock fallthrough = theIf.fallthroughBlock();
    assert trueTarget != fallthrough;

    if (!fallthrough.isSimpleAlwaysThrowingPath() || trueTarget.isSimpleAlwaysThrowingPath()) {
      return false;
    }

    // In case fall-through block always throws there is a good chance that it
    // is created for error checks and 'trueTarget' represents most more common
    // non-error case. Flipping the if in this case may result in faster code
    // on older Android versions.
    List<Value> inValues = theIf.inValues();
    If newIf = new If(theIf.getType().inverted(), inValues);
    block.replaceLastInstruction(newIf, code);
    block.swapSuccessors(trueTarget, fallthrough);
    return true;
  }

  public void rewriteKnownArrayLengthCalls(IRCode code) {
    InstructionListIterator iterator = code.instructionListIterator();
    while (iterator.hasNext()) {
      Instruction current = iterator.next();
      if (!current.isArrayLength()) {
        continue;
      }

      ArrayLength arrayLength = current.asArrayLength();
      if (arrayLength.hasOutValue() && arrayLength.outValue().hasLocalInfo()) {
        continue;
      }

      Value array = arrayLength.array().getAliasedValue();
      if (array.isPhi() || !arrayLength.array().isNeverNull() || array.hasLocalInfo()) {
        continue;
      }

      AbstractValue abstractValue = array.getAbstractValue(appView, code.context());
      if (!abstractValue.hasKnownArrayLength() && !array.isNeverNull()) {
        continue;
      }
      Instruction arrayDefinition = array.getDefinition();
      assert arrayDefinition != null;

      Set<Phi> phiUsers = arrayLength.outValue().uniquePhiUsers();
      if (arrayDefinition.isNewArrayEmpty()) {
        Value size = arrayDefinition.asNewArrayEmpty().size();
        arrayLength.outValue().replaceUsers(size);
        iterator.removeOrReplaceByDebugLocalRead();
      } else if (arrayDefinition.isNewArrayFilledData()) {
        long size = arrayDefinition.asNewArrayFilledData().size;
        if (size > Integer.MAX_VALUE) {
          continue;
        }
        iterator.replaceCurrentInstructionWithConstInt(code, (int) size);
      } else if (abstractValue.hasKnownArrayLength()) {
        iterator.replaceCurrentInstructionWithConstInt(code, abstractValue.getKnownArrayLength());
      } else {
        continue;
      }

      phiUsers.forEach(Phi::removeTrivialPhi);
    }
    assert code.isConsistentSSA(appView);
  }

  public void rewriteAssertionErrorTwoArgumentConstructor(IRCode code, InternalOptions options) {
    if (options.canUseAssertionErrorTwoArgumentConstructor()) {
      return;
    }

    ListIterator<BasicBlock> blockIterator = code.listIterator();
    while (blockIterator.hasNext()) {
      BasicBlock block = blockIterator.next();
      InstructionListIterator insnIterator = block.listIterator(code);
      while (insnIterator.hasNext()) {
        Instruction current = insnIterator.next();
        if (current.isInvokeMethod()) {
          DexMethod invokedMethod = current.asInvokeMethod().getInvokedMethod();
          if (invokedMethod == dexItemFactory.assertionErrorMethods.initMessageAndCause) {
            // Rewrite calls to new AssertionError(message, cause) to new AssertionError(message)
            // and then initCause(cause).
            List<Value> inValues = current.inValues();
            assert inValues.size() == 3; // receiver, message, cause

            // Remove cause from the constructor call
            List<Value> newInitInValues = inValues.subList(0, 2);
            insnIterator.replaceCurrentInstruction(
                new InvokeDirect(
                    dexItemFactory.assertionErrorMethods.initMessage, null, newInitInValues));

            // On API 15 and older we cannot use initCause because of a bug in AssertionError.
            if (options.canInitCauseAfterAssertionErrorObjectConstructor()) {
              // Add a call to Throwable.initCause(cause)
              if (block.hasCatchHandlers()) {
                insnIterator = insnIterator.split(code, blockIterator).listIterator(code);
              }
              List<Value> initCauseArguments = Arrays.asList(inValues.get(0), inValues.get(2));
              InvokeVirtual initCause =
                  new InvokeVirtual(
                      dexItemFactory.throwableMethods.initCause,
                      code.createValue(
                          TypeElement.fromDexType(
                              dexItemFactory.throwableType, maybeNull(), appView)),
                      initCauseArguments);
              initCause.setPosition(current.getPosition());
              insnIterator.add(initCause);
            }
          }
        }
      }
    }
    assert code.isConsistentSSA(appView);
  }

  /**
   * Remove moves that are not actually used by instructions in exiting paths. These moves can arise
   * due to debug local info needing a particular value and the live-interval for it then moves it
   * back into the properly assigned register. If the register is only used for debug purposes, it
   * is safe to just remove the move and update the local information accordingly.
   */
  public static void removeUnneededMovesOnExitingPaths(
      IRCode code, LinearScanRegisterAllocator allocator) {
    if (!allocator.options().debug) {
      return;
    }
    for (BasicBlock block : code.blocks) {
      // Skip non-exit blocks.
      if (!block.getSuccessors().isEmpty()) {
        continue;
      }
      // Skip blocks with no locals at entry.
      Int2ReferenceMap<DebugLocalInfo> localsAtEntry = block.getLocalsAtEntry();
      if (localsAtEntry == null || localsAtEntry.isEmpty()) {
        continue;
      }
      // Find the locals state after spill moves.
      DebugLocalsChange postSpillLocalsChange = null;
      for (Instruction instruction : block.getInstructions()) {
        if (instruction.getNumber() != -1 || postSpillLocalsChange != null) {
          break;
        }
        postSpillLocalsChange = instruction.asDebugLocalsChange();
      }
      // Skip if the locals state did not change.
      if (postSpillLocalsChange == null
          || !postSpillLocalsChange.apply(new Int2ReferenceOpenHashMap<>(localsAtEntry))) {
        continue;
      }
      // Collect the moves that can safely be removed.
      Set<Move> unneededMoves = computeUnneededMoves(block, postSpillLocalsChange, allocator);
      if (unneededMoves.isEmpty()) {
        continue;
      }
      Int2IntMap previousMapping = new Int2IntOpenHashMap();
      Int2IntMap mapping = new Int2IntOpenHashMap();
      InstructionListIterator it = block.listIterator(code);
      while (it.hasNext()) {
        Instruction instruction = it.next();
        if (instruction.isMove()) {
          Move move = instruction.asMove();
          if (unneededMoves.contains(move)) {
            int dst = allocator.getRegisterForValue(move.dest(), move.getNumber());
            int src = allocator.getRegisterForValue(move.src(), move.getNumber());
            int mappedSrc = mapping.getOrDefault(src, src);
            mapping.put(dst, mappedSrc);
            it.removeInstructionIgnoreOutValue();
          }
        } else if (instruction.isDebugLocalsChange()) {
          DebugLocalsChange change = instruction.asDebugLocalsChange();
          updateDebugLocalsRegisterMap(previousMapping, change.getEnding());
          updateDebugLocalsRegisterMap(mapping, change.getStarting());
          previousMapping = mapping;
          mapping = new Int2IntOpenHashMap(previousMapping);
        }
      }
    }
  }

  private static Set<Move> computeUnneededMoves(
      BasicBlock block,
      DebugLocalsChange postSpillLocalsChange,
      LinearScanRegisterAllocator allocator) {
    Set<Move> unneededMoves = Sets.newIdentityHashSet();
    IntSet usedRegisters = new IntOpenHashSet();
    IntSet clobberedRegisters = new IntOpenHashSet();
    // Backwards instruction scan collecting the registers used by actual instructions.
    boolean inEntrySpillMoves = false;
    InstructionIterator it = block.iterator(block.getInstructions().size());
    while (it.hasPrevious()) {
      Instruction instruction = it.previous();
      if (instruction == postSpillLocalsChange) {
        inEntrySpillMoves = true;
      }
      // If this is a move in the block-entry spill moves check if it is unneeded.
      if (inEntrySpillMoves && instruction.isMove()) {
        Move move = instruction.asMove();
        int dst = allocator.getRegisterForValue(move.dest(), move.getNumber());
        int src = allocator.getRegisterForValue(move.src(), move.getNumber());
        if (!usedRegisters.contains(dst) && !clobberedRegisters.contains(src)) {
          unneededMoves.add(move);
          continue;
        }
      }
      if (instruction.outValue() != null && instruction.outValue().needsRegister()) {
        int register =
            allocator.getRegisterForValue(instruction.outValue(), instruction.getNumber());
        // The register is defined anew, so uses before this are on distinct values.
        usedRegisters.remove(register);
        // Mark it clobbered to avoid any uses in locals after this point to become invalid.
        clobberedRegisters.add(register);
      }
      if (!instruction.inValues().isEmpty()) {
        for (Value inValue : instruction.inValues()) {
          if (inValue.needsRegister()) {
            int register = allocator.getRegisterForValue(inValue, instruction.getNumber());
            // Record the register as being used.
            usedRegisters.add(register);
          }
        }
      }
    }
    return unneededMoves;
  }

  private static void updateDebugLocalsRegisterMap(
      Int2IntMap mapping, Int2ReferenceMap<DebugLocalInfo> locals) {
    // If nothing is mapped nothing needs to be changed.
    if (mapping.isEmpty()) {
      return;
    }
    // Locals is final, so we copy and clear it during update.
    Int2ReferenceMap<DebugLocalInfo> copy = new Int2ReferenceOpenHashMap<>(locals);
    locals.clear();
    for (Entry<DebugLocalInfo> entry : copy.int2ReferenceEntrySet()) {
      int oldRegister = entry.getIntKey();
      int newRegister = mapping.getOrDefault(oldRegister, oldRegister);
      locals.put(newRegister, entry.getValue());
    }
  }

  private Value addConstString(IRCode code, InstructionListIterator iterator, String s) {
    TypeElement typeLattice = TypeElement.stringClassType(appView, definitelyNotNull());
    Value value = code.createValue(typeLattice);
    iterator.add(new ConstString(value, dexItemFactory.createString(s)));
    return value;
  }

  /**
   * Insert code into <code>method</code> to log the argument types to System.out.
   *
   * The type is determined by calling getClass() on the argument.
   */
  public void logArgumentTypes(DexEncodedMethod method, IRCode code) {
    List<Value> arguments = code.collectArguments();
    BasicBlock block = code.entryBlock();
    InstructionListIterator iterator = block.listIterator(code);

    // Attach some synthetic position to all inserted code.
    Position position =
        SyntheticPosition.builder().setLine(1).setMethod(method.getReference()).build();
    iterator.setInsertionPosition(position);

    // Split arguments into their own block.
    iterator.nextUntil(instruction -> !instruction.isArgument());
    iterator.previous();
    iterator.split(code);
    iterator.previous();

    // Now that the block is split there should not be any catch handlers in the block.
    assert !block.hasCatchHandlers();
    DexType javaLangSystemType = dexItemFactory.javaLangSystemType;
    DexType javaIoPrintStreamType = dexItemFactory.javaIoPrintStreamType;
    Value out =
        code.createValue(
            TypeElement.fromDexType(javaIoPrintStreamType, definitelyNotNull(), appView));

    DexProto proto = dexItemFactory.createProto(dexItemFactory.voidType, dexItemFactory.objectType);
    DexMethod print = dexItemFactory.createMethod(javaIoPrintStreamType, proto, "print");
    DexMethod printLn = dexItemFactory.createMethod(javaIoPrintStreamType, proto, "println");

    iterator.add(
        new StaticGet(
            out, dexItemFactory.createField(javaLangSystemType, javaIoPrintStreamType, "out")));

    Value value = addConstString(code, iterator, "INVOKE ");
    iterator.add(new InvokeVirtual(print, null, ImmutableList.of(out, value)));

    value = addConstString(code, iterator, method.getReference().qualifiedName());
    iterator.add(new InvokeVirtual(print, null, ImmutableList.of(out, value)));

    Value openParenthesis = addConstString(code, iterator, "(");
    Value comma = addConstString(code, iterator, ",");
    Value closeParenthesis = addConstString(code, iterator, ")");
    Value indent = addConstString(code, iterator, "  ");
    Value nul = addConstString(code, iterator, "(null)");
    Value primitive = addConstString(code, iterator, "(primitive)");
    Value empty = addConstString(code, iterator, "");

    iterator.add(new InvokeVirtual(printLn, null, ImmutableList.of(out, openParenthesis)));
    for (int i = 0; i < arguments.size(); i++) {
      iterator.add(new InvokeVirtual(print, null, ImmutableList.of(out, indent)));

      // Add a block for end-of-line printing.
      BasicBlock eol =
          BasicBlock.createGotoBlock(code.getNextBlockNumber(), position, code.metadata());
      code.blocks.add(eol);

      BasicBlock successor = block.unlinkSingleSuccessor();
      block.link(eol);
      eol.link(successor);

      Value argument = arguments.get(i);
      if (!argument.getType().isReferenceType()) {
        iterator.add(new InvokeVirtual(print, null, ImmutableList.of(out, primitive)));
      } else {
        // Insert "if (argument != null) ...".
        successor = block.unlinkSingleSuccessor();
        If theIf = new If(Type.NE, argument);
        theIf.setPosition(position);
        BasicBlock ifBlock =
            BasicBlock.createIfBlock(code.getNextBlockNumber(), theIf, code.metadata());
        code.blocks.add(ifBlock);
        // Fallthrough block must be added right after the if.
        BasicBlock isNullBlock =
            BasicBlock.createGotoBlock(code.getNextBlockNumber(), position, code.metadata());
        code.blocks.add(isNullBlock);
        BasicBlock isNotNullBlock =
            BasicBlock.createGotoBlock(code.getNextBlockNumber(), position, code.metadata());
        code.blocks.add(isNotNullBlock);

        // Link the added blocks together.
        block.link(ifBlock);
        ifBlock.link(isNotNullBlock);
        ifBlock.link(isNullBlock);
        isNotNullBlock.link(successor);
        isNullBlock.link(successor);

        // Fill code into the blocks.
        iterator = isNullBlock.listIterator(code);
        iterator.setInsertionPosition(position);
        iterator.add(new InvokeVirtual(print, null, ImmutableList.of(out, nul)));
        iterator = isNotNullBlock.listIterator(code);
        iterator.setInsertionPosition(position);
        value = code.createValue(TypeElement.classClassType(appView, definitelyNotNull()));
        iterator.add(
            new InvokeVirtual(
                dexItemFactory.objectMembers.getClass, value, ImmutableList.of(arguments.get(i))));
        iterator.add(new InvokeVirtual(print, null, ImmutableList.of(out, value)));
      }

      iterator = eol.listIterator(code);
      iterator.setInsertionPosition(position);
      if (i == arguments.size() - 1) {
        iterator.add(new InvokeVirtual(printLn, null, ImmutableList.of(out, closeParenthesis)));
      } else {
        iterator.add(new InvokeVirtual(printLn, null, ImmutableList.of(out, comma)));
      }
      block = eol;
    }
    // When we fall out of the loop the iterator is in the last eol block.
    iterator.add(new InvokeVirtual(printLn, null, ImmutableList.of(out, empty)));
  }

  public static void ensureDirectStringNewToInit(IRCode code, DexItemFactory dexItemFactory) {
    for (Instruction instruction : code.instructions()) {
      if (instruction.isInvokeDirect()) {
        InvokeDirect invoke = instruction.asInvokeDirect();
        DexMethod method = invoke.getInvokedMethod();
        if (dexItemFactory.isConstructor(method)
            && method.holder == dexItemFactory.stringType
            && invoke.getReceiver().isPhi()) {
          NewInstance newInstance = findNewInstance(invoke.getReceiver().asPhi());
          replaceTrivialNewInstancePhis(newInstance.outValue());
          if (invoke.getReceiver().isPhi()) {
            throw new CompilationError(
                "Failed to remove trivial phis between new-instance and <init>");
          }
          newInstance.markNoSpilling();
        }
      }
    }
  }

  // The javac fix for JDK-8272564 has to be rewritten back to invoke-virtual on Object if the
  // method with an Object signature is not defined on the interface. See
  // https://bugs.openjdk.java.net/browse/JDK-8272564
  public static void rewriteJdk8272564Fix(IRCode code, AppView<?> appView) {
    DexItemFactory dexItemFactory = appView.dexItemFactory();
    InstructionListIterator it = code.instructionListIterator();
    while (it.hasNext()) {
      Instruction instruction = it.next();
      if (instruction.isInvokeInterface()) {
        InvokeInterface invoke = instruction.asInvokeInterface();
        DexMethod method = invoke.getInvokedMethod();
        DexMethod objectMember = dexItemFactory.objectMembers.matchingPublicObjectMember(method);
        if (objectMember != null && appView.definitionFor(method) == null) {
          it.replaceCurrentInstruction(
              new InvokeVirtual(objectMember, invoke.outValue(), invoke.arguments()));
        }
      }
    }
  }

  private static NewInstance findNewInstance(Phi phi) {
    Set<Phi> seen = Sets.newIdentityHashSet();
    Set<Value> values = Sets.newIdentityHashSet();
    recursiveAddOperands(phi, seen, values);
    if (values.size() != 1) {
      throw new CompilationError("Failed to identify unique new-instance for <init>");
    }
    Value newInstanceValue = values.iterator().next();
    if (newInstanceValue.definition == null || !newInstanceValue.definition.isNewInstance()) {
      throw new CompilationError("Invalid defining value for call to <init>");
    }
    return newInstanceValue.definition.asNewInstance();
  }

  private static void recursiveAddOperands(Phi phi, Set<Phi> seen, Set<Value> values) {
    for (Value operand : phi.getOperands()) {
      if (!operand.isPhi()) {
        values.add(operand);
      } else {
        Phi phiOp = operand.asPhi();
        if (seen.add(phiOp)) {
          recursiveAddOperands(phiOp, seen, values);
        }
      }
    }
  }

  // If an <init> call takes place on a phi the code must contain an irreducible loop between the
  // new-instance and the <init>. Assuming the code is verifiable, new-instance must flow to a
  // unique <init>. Here we compute the set of strongly connected phis making use of the
  // new-instance value and replace all trivial ones by the new-instance value.
  // This is a simplified variant of the removeRedundantPhis algorithm in Section 3.2 of:
  // http://compilers.cs.uni-saarland.de/papers/bbhlmz13cc.pdf
  private static void replaceTrivialNewInstancePhis(Value newInstanceValue) {
    List<Set<Value>> components =
        new SCC<Value>(Value::uniquePhiUsers).computeSCC(newInstanceValue);
    for (int i = components.size() - 1; i >= 0; i--) {
      Set<Value> component = components.get(i);
      if (component.size() == 1 && component.iterator().next() == newInstanceValue) {
        continue;
      }
      Set<Phi> trivialPhis = Sets.newIdentityHashSet();
      for (Value value : component) {
        boolean isTrivial = true;
        Phi p = value.asPhi();
        for (Value op : p.getOperands()) {
          if (op != newInstanceValue && !component.contains(op)) {
            isTrivial = false;
            break;
          }
        }
        if (isTrivial) {
          trivialPhis.add(p);
        }
      }
      for (Phi trivialPhi : trivialPhis) {
        for (Value op : trivialPhi.getOperands()) {
          op.removePhiUser(trivialPhi);
        }
        trivialPhi.replaceUsers(newInstanceValue);
        trivialPhi.getBlock().removePhi(trivialPhi);
      }
    }
  }
}
