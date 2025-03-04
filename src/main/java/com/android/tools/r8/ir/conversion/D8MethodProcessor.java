// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.conversion;

import com.android.tools.r8.contexts.CompilationContext.MethodProcessingContext;
import com.android.tools.r8.contexts.CompilationContext.ProcessorContext;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.conversion.callgraph.CallSiteInformation;
import com.android.tools.r8.ir.desugar.CfInstructionDesugaringEventConsumer;
import com.android.tools.r8.ir.desugar.CfInstructionDesugaringEventConsumer.D8CfInstructionDesugaringEventConsumer;
import com.android.tools.r8.ir.optimize.info.OptimizationFeedbackIgnore;
import com.android.tools.r8.utils.ThreadUtils;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

public class D8MethodProcessor extends MethodProcessor {

  private final IRConverter converter;
  private final ExecutorService executorService;
  private final Set<DexType> scheduled = Sets.newIdentityHashSet();

  // Asynchronous method processing actions. These are "terminal" method processing actions in the
  // sense that the method processing is known not to fork any other futures.
  private final List<Future<?>> terminalFutures = Collections.synchronizedList(new ArrayList<>());

  // Asynchronous method processing actions. This list includes both "terminal" and "non-terminal"
  // method processing actions. Thus, before the asynchronous method processing finishes, it may
  // fork the processing of another method.
  private final List<Future<?>> nonTerminalFutures =
      Collections.synchronizedList(new ArrayList<>());

  private ProcessorContext processorContext;

  public D8MethodProcessor(IRConverter converter, ExecutorService executorService) {
    this.converter = converter;
    this.executorService = executorService;
    this.processorContext = converter.appView.createProcessorContext();
  }

  public void addScheduled(DexProgramClass clazz) {
    boolean added = scheduled.add(clazz.getType());
    assert added;
  }

  public void newWave() {
    this.processorContext = converter.appView.createProcessorContext();
  }

  @Override
  public MethodProcessingContext createMethodProcessingContext(ProgramMethod method) {
    return processorContext.createMethodProcessingContext(method);
  }

  @Override
  public boolean isProcessedConcurrently(ProgramMethod method) {
    // In D8 all methods are considered independently compiled.
    return true;
  }

  @Override
  public boolean shouldApplyCodeRewritings(ProgramMethod method) {
    return true;
  }

  public void scheduleMethodForProcessing(
      ProgramMethod method, D8CfInstructionDesugaringEventConsumer eventConsumer) {
    // TODO(b/179755192): By building up waves of methods in the class converter, we can avoid the
    //  following check and always process the method asynchronously.
    if (!scheduled.contains(method.getHolderType())
        && !converter.appView.getSyntheticItems().isNonLegacySynthetic(method.getHolder())) {
      // The non-synthetic holder is not scheduled. It will be processed once holder is scheduled.
      return;
    }
    nonTerminalFutures.add(
        ThreadUtils.processAsynchronously(
            () ->
                converter.rewriteNonDesugaredCode(
                    method,
                    eventConsumer,
                    OptimizationFeedbackIgnore.getInstance(),
                    this,
                    processorContext.createMethodProcessingContext(method)),
            executorService));
  }

  @Override
  public void scheduleDesugaredMethodForProcessing(ProgramMethod method) {
    // TODO(b/179755192): By building up waves of methods in the class converter, we can avoid the
    //  following check and always process the method asynchronously.
    if (!scheduled.contains(method.getHolderType())
        && !converter.appView.getSyntheticItems().isNonLegacySynthetic(method.getHolder())) {
      // The non-synthetic holder is not scheduled. It will be processed once holder is scheduled.
      return;
    }
    if (method.getDefinition().isAbstract()) {
      return;
    }
    terminalFutures.add(
        ThreadUtils.processAsynchronously(
            () ->
                converter.rewriteDesugaredCode(
                    method,
                    OptimizationFeedbackIgnore.getInstance(),
                    this,
                    processorContext.createMethodProcessingContext(method)),
            executorService));
  }

  public D8MethodProcessor scheduleDesugaredMethodsForProcessing(Iterable<ProgramMethod> methods) {
    methods.forEach(this::scheduleDesugaredMethodForProcessing);
    return this;
  }

  @Override
  public CallSiteInformation getCallSiteInformation() {
    throw new Unreachable("Invalid attempt to obtain call-site information in D8");
  }

  public void awaitMethodProcessing() throws ExecutionException {
    // Await the non-terminal futures until there are only terminal futures left.
    while (!nonTerminalFutures.isEmpty()) {
      List<Future<?>> futuresToAwait;
      synchronized (nonTerminalFutures) {
        futuresToAwait = new ArrayList<>(nonTerminalFutures);
        nonTerminalFutures.clear();
      }
      ThreadUtils.awaitFutures(futuresToAwait);
    }

    // Await the terminal futures. There futures will by design not to fork new method processing.
    int numberOfTerminalFutures = terminalFutures.size();
    ThreadUtils.awaitFutures(terminalFutures);
    assert terminalFutures.size() == numberOfTerminalFutures;
    terminalFutures.clear();
  }

  public void processMethod(
      ProgramMethod method, CfInstructionDesugaringEventConsumer desugaringEventConsumer) {
    converter.convertMethod(
        method,
        desugaringEventConsumer,
        this,
        processorContext.createMethodProcessingContext(method));
  }

  public boolean verifyNoPendingMethodProcessing() {
    assert terminalFutures.isEmpty();
    assert nonTerminalFutures.isEmpty();
    return true;
  }
}
