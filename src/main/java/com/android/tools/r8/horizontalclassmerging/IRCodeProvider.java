// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.horizontalclassmerging;

import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.AppInfoWithClassHierarchy;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.GraphLens;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.code.IRCode;

public interface IRCodeProvider {

  IRCode buildIR(ProgramMethod method);

  void setGraphLens(GraphLens graphLens);

  static IRCodeProvider create(AppView<? extends AppInfoWithClassHierarchy> appView) {
    return new IRCodeProviderImpl(appView);
  }

  static IRCodeProvider createThrowing() {
    return new IRCodeProvider() {
      @Override
      public IRCode buildIR(ProgramMethod method) {
        throw new UnsupportedOperationException("Should never build IR for methods in D8");
      }

      @Override
      public void setGraphLens(GraphLens graphLens) {}
    };
  }

  class IRCodeProviderImpl implements IRCodeProvider {

    private final AppView<AppInfo> appViewForConversion;

    private IRCodeProviderImpl(AppView<? extends AppInfoWithClassHierarchy> appView) {
      // At this point the code rewritings described by repackaging and synthetic finalization have
      // not been applied to the code objects. These code rewritings will be applied in the
      // application writer. We therefore simulate that we are in D8, to allow building IR for each
      // of the class initializers without applying the unapplied code rewritings, to avoid that we
      // apply the lens more than once to the same piece of code.
      AppView<AppInfo> appViewForConversion =
          AppView.createForD8(AppInfo.createInitialAppInfo(appView.appInfo().app()));
      appViewForConversion.setGraphLens(appView.graphLens());
      appViewForConversion.setCodeLens(appView.codeLens());
      this.appViewForConversion = appViewForConversion;
    }

    @Override
    public IRCode buildIR(ProgramMethod method) {
      return method
          .getDefinition()
          .getCode()
          .buildIR(method, appViewForConversion, method.getOrigin());
    }

    @Override
    public void setGraphLens(GraphLens graphLens) {
      appViewForConversion.setGraphLens(graphLens);
    }
  }
}
