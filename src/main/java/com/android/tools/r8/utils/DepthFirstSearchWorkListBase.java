// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils;

import static com.android.tools.r8.utils.DepthFirstSearchWorkListBase.ProcessingState.FINISHED;
import static com.android.tools.r8.utils.DepthFirstSearchWorkListBase.ProcessingState.NOT_PROCESSED;
import static com.android.tools.r8.utils.DepthFirstSearchWorkListBase.ProcessingState.WAITING;

import com.android.tools.r8.utils.DepthFirstSearchWorkListBase.DFSNodeImpl;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

public abstract class DepthFirstSearchWorkListBase<N, T extends DFSNodeImpl<N>> {

  public interface DFSNode<N> {
    N getNode();

    boolean seenAndNotProcessed();
  }

  public interface DFSNodeWithState<N, S> extends DFSNode<N> {

    S getState();

    void setState(S backtrackState);

    boolean hasState();
  }

  enum ProcessingState {
    NOT_PROCESSED,
    WAITING,
    FINISHED;
  }

  static class DFSNodeImpl<N> implements DFSNode<N> {

    private final N node;
    private ProcessingState processingState = NOT_PROCESSED;

    private DFSNodeImpl(N node) {
      this.node = node;
    }

    boolean isNotProcessed() {
      return processingState == NOT_PROCESSED;
    }

    boolean isFinished() {
      return processingState == FINISHED;
    }

    void setWaiting() {
      processingState = WAITING;
    }

    void setFinished() {
      assert processingState != FINISHED;
      processingState = FINISHED;
    }

    @Override
    public N getNode() {
      return node;
    }

    @Override
    public boolean seenAndNotProcessed() {
      return processingState == WAITING;
    }
  }

  static class DFSNodeWithStateImpl<N, S> extends DFSNodeImpl<N> implements DFSNodeWithState<N, S> {

    private S state;

    private DFSNodeWithStateImpl(N node) {
      super(node);
    }

    @Override
    public S getState() {
      return state;
    }

    @Override
    public void setState(S state) {
      this.state = state;
    }

    @Override
    public boolean hasState() {
      return state != null;
    }
  }

  private final ArrayDeque<T> workList = new ArrayDeque<>();

  abstract T createDfsNode(N node);

  /** The initial processing of a node during forward search */
  abstract TraversalContinuation<?, ?> internalOnVisit(T node);

  /** The joining of state during backtracking of the algorithm. */
  abstract TraversalContinuation<?, ?> internalOnJoin(T node);

  final T internalEnqueueNode(N value) {
    T dfsNode = createDfsNode(value);
    if (dfsNode.isNotProcessed()) {
      workList.addLast(dfsNode);
    }
    return dfsNode;
  }

  @SafeVarargs
  public final TraversalContinuation<?, ?> run(N... roots) {
    return run(Arrays.asList(roots));
  }

  public final TraversalContinuation<?, ?> run(Collection<N> roots) {
    roots.forEach(this::internalEnqueueNode);
    TraversalContinuation<?, ?> continuation = TraversalContinuation.doContinue();
    while (!workList.isEmpty()) {
      T node = workList.removeLast();
      if (node.isFinished()) {
        continue;
      }
      if (node.isNotProcessed()) {
        workList.addLast(node);
        node.setWaiting();
        continuation = internalOnVisit(node);
      } else {
        assert node.seenAndNotProcessed();
        continuation = internalOnJoin(node);
        node.setFinished();
      }
      if (continuation.shouldBreak()) {
        return continuation;
      }
    }
    return continuation;
  }

  public abstract static class DepthFirstSearchWorkList<N>
      extends DepthFirstSearchWorkListBase<N, DFSNodeImpl<N>> {

    /**
     * The initial processing of the node when visiting the first time during the depth first
     * search.
     *
     * @param node The current node.
     * @param childNodeConsumer A consumer for adding child nodes. If an element has been seen
     *     before but not finished there is a cycle.
     * @return A value describing if the DFS algorithm should continue to run.
     */
    protected abstract TraversalContinuation<?, ?> process(
        DFSNode<N> node, Function<N, DFSNode<N>> childNodeConsumer);

    @Override
    DFSNodeImpl<N> createDfsNode(N node) {
      return new DFSNodeImpl<>(node);
    }

    @Override
    TraversalContinuation<?, ?> internalOnVisit(DFSNodeImpl<N> node) {
      return process(node, this::internalEnqueueNode);
    }

    @Override
    protected TraversalContinuation<?, ?> internalOnJoin(DFSNodeImpl<N> node) {
      return TraversalContinuation.doContinue();
    }
  }

  public abstract static class StatefulDepthFirstSearchWorkList<N, S>
      extends DepthFirstSearchWorkListBase<N, DFSNodeWithStateImpl<N, S>> {

    private final Map<DFSNodeWithStateImpl<N, S>, List<DFSNodeWithState<N, S>>> childStateMap =
        new IdentityHashMap<>();

    /**
     * The initial processing of the node when visiting the first time during the depth first
     * search.
     *
     * @param node The current node.
     * @param childNodeConsumer A consumer for adding child nodes. If an element has been seen
     *     before but not finished there is a cycle.
     * @return A value describing if the DFS algorithm should continue to run.
     */
    protected abstract TraversalContinuation<?, ?> process(
        DFSNodeWithState<N, S> node, Function<N, DFSNodeWithState<N, S>> childNodeConsumer);

    /**
     * The joining of state during backtracking of the algorithm.
     *
     * @param node The current node
     * @param childStates The already computed child states.
     * @return A value describing if the DFS algorithm should continue to run.
     */
    protected abstract TraversalContinuation<?, ?> joiner(
        DFSNodeWithState<N, S> node, List<DFSNodeWithState<N, S>> childStates);

    @Override
    DFSNodeWithStateImpl<N, S> createDfsNode(N node) {
      return new DFSNodeWithStateImpl<>(node);
    }

    @Override
    TraversalContinuation<?, ?> internalOnVisit(DFSNodeWithStateImpl<N, S> node) {
      List<DFSNodeWithState<N, S>> childStates = new ArrayList<>();
      List<DFSNodeWithState<N, S>> removedChildStates = childStateMap.put(node, childStates);
      assert removedChildStates == null;
      return process(
          node,
          successor -> {
            DFSNodeWithStateImpl<N, S> successorNode = internalEnqueueNode(successor);
            childStates.add(successorNode);
            return successorNode;
          });
    }

    @Override
    protected TraversalContinuation<?, ?> internalOnJoin(DFSNodeWithStateImpl<N, S> node) {
      return joiner(
          node,
          childStateMap.computeIfAbsent(
              node,
              n -> {
                assert false : "Unexpected joining of not visited node";
                return new ArrayList<>();
              }));
    }
  }
}
