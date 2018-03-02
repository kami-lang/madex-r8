// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.lambda;

import com.android.tools.r8.graph.ClassAccessFlags;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexTypeList;
import com.android.tools.r8.graph.EnclosingMethodAttribute;
import com.android.tools.r8.graph.InnerClassAttribute;
import com.android.tools.r8.ir.optimize.lambda.LambdaGroup.LambdaInfo;
import com.android.tools.r8.origin.SynthesizedOrigin;
import java.util.List;

// Encapsulates lambda group class building logic and separates
// it from the rest of lambda group functionality.
public abstract class LambdaGroupClassBuilder<T extends LambdaGroup> {
  protected final T group;
  protected final DexItemFactory factory;
  protected final String origin;
  protected final List<LambdaInfo> lambdas;

  protected LambdaGroupClassBuilder(T group, DexItemFactory factory, String origin) {
    this.group = group;
    this.factory = factory;
    this.origin = origin;
    this.lambdas = group.lambdas();
  }

  public final DexProgramClass synthesizeClass() {
    return new DexProgramClass(
        group.getGroupClassType(),
        null,
        new SynthesizedOrigin(origin, getClass()),
        buildAccessFlags(),
        getSuperClassType(),
        buildInterfaces(),
        factory.createString(origin),
        buildEnclosingMethodAttribute(),
        buildInnerClasses(),
        buildAnnotations(),
        buildStaticFields(),
        buildInstanceFields(),
        buildDirectMethods(),
        buildVirtualMethods());
  }

  protected abstract DexType getSuperClassType();

  protected abstract ClassAccessFlags buildAccessFlags();

  protected abstract EnclosingMethodAttribute buildEnclosingMethodAttribute();

  protected abstract List<InnerClassAttribute> buildInnerClasses();

  protected abstract DexAnnotationSet buildAnnotations();

  protected abstract DexEncodedMethod[] buildVirtualMethods();

  protected abstract DexEncodedMethod[] buildDirectMethods();

  protected abstract DexEncodedField[] buildInstanceFields();

  protected abstract DexEncodedField[] buildStaticFields();

  protected abstract DexTypeList buildInterfaces();
}
