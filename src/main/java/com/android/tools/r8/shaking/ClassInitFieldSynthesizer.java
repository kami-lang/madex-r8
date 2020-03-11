// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.shaking;

import static com.android.tools.r8.graph.DexProgramClass.asProgramClassOrNull;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.FieldAccessFlags;
import com.android.tools.r8.graph.InitClassLens;
import com.android.tools.r8.utils.ThreadUtils;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

public class ClassInitFieldSynthesizer {

  private final AppView<AppInfoWithLiveness> appView;
  private final DexField clinitField;
  private final InitClassLens.Builder lensBuilder = InitClassLens.builder();

  public ClassInitFieldSynthesizer(AppView<AppInfoWithLiveness> appView) {
    this.appView = appView;
    this.clinitField = appView.dexItemFactory().objectMembers.clinitField;
  }

  public void run(ExecutorService executorService) throws ExecutionException {
    ThreadUtils.processItems(
        appView.appInfo().initClassReferences, this::synthesizeClassInitField, executorService);
    appView.setInitClassLens(lensBuilder.build());
  }

  private void synthesizeClassInitField(DexType type) {
    DexProgramClass clazz = asProgramClassOrNull(appView.definitionFor(type));
    if (clazz == null) {
      assert false;
      return;
    }
    // Use an existing non-wide field if there is one (we can't use wide fields since we've only
    // allocated a single register for the out-value of each ClassInit instruction).
    DexEncodedField encodedClinitField = null;
    for (DexEncodedField staticField : clazz.staticFields()) {
      if (!staticField.field.type.isWideType()) {
        encodedClinitField = staticField;
        break;
      }
    }
    if (encodedClinitField == null) {
      FieldAccessFlags accessFlags =
          FieldAccessFlags.fromSharedAccessFlags(
              Constants.ACC_SYNTHETIC
                  | Constants.ACC_FINAL
                  | Constants.ACC_PUBLIC
                  | Constants.ACC_STATIC);
      encodedClinitField =
          new DexEncodedField(
              appView.dexItemFactory().createField(clazz.type, clinitField.type, clinitField.name),
              accessFlags,
              DexAnnotationSet.empty(),
              null);
      clazz.appendStaticField(encodedClinitField);
    }
    lensBuilder.map(type, encodedClinitField.field);
  }
}
