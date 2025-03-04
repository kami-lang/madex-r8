// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.cf.code;

import static com.android.tools.r8.utils.BiPredicateUtils.or;

import com.android.tools.r8.cf.CfPrinter;
import com.android.tools.r8.cf.code.CfFrame.FrameType;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.CfCode;
import com.android.tools.r8.graph.CfCompareHelper;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.GraphLens;
import com.android.tools.r8.graph.InitClassLens;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.code.ValueType;
import com.android.tools.r8.ir.conversion.CfSourceCode;
import com.android.tools.r8.ir.conversion.CfState;
import com.android.tools.r8.ir.conversion.CfState.Slot;
import com.android.tools.r8.ir.conversion.IRBuilder;
import com.android.tools.r8.ir.conversion.LensCodeRewriterUtils;
import com.android.tools.r8.ir.optimize.Inliner.ConstraintWithTarget;
import com.android.tools.r8.ir.optimize.InliningConstraints;
import com.android.tools.r8.naming.NamingLens;
import com.android.tools.r8.utils.structural.CompareToVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

public class CfStore extends CfInstruction {

  private final int var;
  private final ValueType type;

  public CfStore(ValueType type, int var) {
    this.var = var;
    this.type = type;
  }

  @Override
  public int getCompareToId() {
    return getStoreType();
  }

  @Override
  public int internalAcceptCompareTo(
      CfInstruction other, CompareToVisitor visitor, CfCompareHelper helper) {
    return visitor.visitInt(var, other.asStore().var);
  }

  private int getStoreType() {
    switch (type) {
      case OBJECT:
        return Opcodes.ASTORE;
      case INT:
        return Opcodes.ISTORE;
      case FLOAT:
        return Opcodes.FSTORE;
      case LONG:
        return Opcodes.LSTORE;
      case DOUBLE:
        return Opcodes.DSTORE;
      default:
        throw new Unreachable("Unexpected type " + type);
    }
  }

  @Override
  public CfStore asStore() {
    return this;
  }

  @Override
  public boolean isStore() {
    return true;
  }

  @Override
  public void write(
      AppView<?> appView,
      ProgramMethod context,
      DexItemFactory dexItemFactory,
      GraphLens graphLens,
      InitClassLens initClassLens,
      NamingLens namingLens,
      LensCodeRewriterUtils rewriter,
      MethodVisitor visitor) {
    visitor.visitVarInsn(getStoreType(), var);
  }

  @Override
  public int bytecodeSizeUpperBound() {
    // xstore_0 .. xstore_3, xstore or wide xstore, where x is a, i, f, l or d
    return var <= 3 ? 1 : ((var < 256) ? 2 : 4);
  }

  @Override
  public void print(CfPrinter printer) {
    printer.print(this);
  }

  public ValueType getType() {
    return type;
  }

  public int getLocalIndex() {
    return var;
  }

  @Override
  public void buildIR(IRBuilder builder, CfState state, CfSourceCode code) {
    Slot pop = state.pop();
    builder.addMove(type, state.write(var, pop).register, pop.register);
  }

  @Override
  public boolean emitsIR() {
    return false;
  }

  @Override
  public ConstraintWithTarget inliningConstraint(
      InliningConstraints inliningConstraints, CfCode code, ProgramMethod context) {
    return inliningConstraints.forStore();
  }

  @Override
  public void evaluate(
      CfFrameVerificationHelper frameBuilder,
      DexMethod context,
      AppView<?> appView,
      DexItemFactory dexItemFactory) {
    // ..., ref →
    // ...
    FrameType pop = frameBuilder.pop();
    switch (type) {
      case OBJECT:
        frameBuilder.checkIsAssignable(
            pop,
            dexItemFactory.objectType,
            or(
                frameBuilder::isUninitializedThisAndTarget,
                frameBuilder::isUninitializedNewAndTarget,
                frameBuilder::isAssignableAndInitialized));
        frameBuilder.storeLocal(var, pop);
        return;
      case INT:
        frameBuilder.checkIsAssignable(
            pop, dexItemFactory.intType, frameBuilder::isAssignableAndInitialized);
        frameBuilder.storeLocal(var, FrameType.initialized(dexItemFactory.intType));
        return;
      case FLOAT:
        frameBuilder.checkIsAssignable(
            pop, dexItemFactory.floatType, frameBuilder::isAssignableAndInitialized);
        frameBuilder.storeLocal(var, FrameType.initialized(dexItemFactory.floatType));
        return;
      case LONG:
        frameBuilder.checkIsAssignable(
            pop, dexItemFactory.longType, frameBuilder::isAssignableAndInitialized);
        frameBuilder.storeLocal(var, FrameType.initialized(dexItemFactory.longType));
        frameBuilder.storeLocal(var + 1, FrameType.initialized(dexItemFactory.longType));
        return;
      case DOUBLE:
        frameBuilder.checkIsAssignable(
            pop, dexItemFactory.doubleType, frameBuilder::isAssignableAndInitialized);
        frameBuilder.storeLocal(var, FrameType.initialized(dexItemFactory.doubleType));
        frameBuilder.storeLocal(var + 1, FrameType.initialized(dexItemFactory.doubleType));
        return;
      default:
        throw new Unreachable("Unexpected type " + type);
    }
  }
}
