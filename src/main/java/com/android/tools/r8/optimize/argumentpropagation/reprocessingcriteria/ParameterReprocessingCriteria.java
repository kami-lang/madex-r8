// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.optimize.argumentpropagation.reprocessingcriteria;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.optimize.argumentpropagation.codescanner.ConcreteParameterState;
import com.android.tools.r8.shaking.AppInfoWithLiveness;

public abstract class ParameterReprocessingCriteria {

  public static AlwaysTrueParameterReprocessingCriteria alwaysReprocess() {
    return AlwaysTrueParameterReprocessingCriteria.get();
  }

  public static AlwaysFalseParameterReprocessingCriteria neverReprocess() {
    return AlwaysFalseParameterReprocessingCriteria.get();
  }

  public static Builder builder() {
    return new Builder();
  }

  public boolean isAlwaysReprocess() {
    return false;
  }

  public boolean isNeverReprocess() {
    return false;
  }

  public abstract boolean shouldReprocess(
      AppView<AppInfoWithLiveness> appView,
      ConcreteParameterState parameterState,
      DexType parameterType);

  public abstract boolean shouldReprocessDueToAbstractValue();

  public abstract boolean shouldReprocessDueToDynamicType();

  public abstract boolean shouldReprocessDueToNullability();

  public static class Builder {

    private boolean reprocessDueToAbstractValue;
    private boolean reprocessDueToDynamicType;
    private boolean reprocessDueToNullability;

    Builder setReprocessDueToAbstractValue() {
      reprocessDueToAbstractValue = true;
      return this;
    }

    Builder setReprocessDueToDynamicType() {
      reprocessDueToDynamicType = true;
      return this;
    }

    Builder setReprocessDueToNullability() {
      reprocessDueToNullability = true;
      return this;
    }

    boolean shouldAlwaysReprocess() {
      return reprocessDueToAbstractValue && reprocessDueToDynamicType && reprocessDueToNullability;
    }

    boolean shouldNeverReprocess() {
      return !reprocessDueToAbstractValue
          && !reprocessDueToDynamicType
          && !reprocessDueToNullability;
    }

    public ParameterReprocessingCriteria build() {
      if (shouldAlwaysReprocess()) {
        return alwaysReprocess();
      }
      if (shouldNeverReprocess()) {
        return neverReprocess();
      }
      return new NonTrivialParameterReprocessingCriteria(reprocessDueToDynamicType);
    }
  }
}
