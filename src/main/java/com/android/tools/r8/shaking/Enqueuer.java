// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import static com.android.tools.r8.graph.DexEncodedMethod.asProgramMethodOrNull;
import static com.android.tools.r8.graph.DexProgramClass.asProgramClassOrNull;
import static com.android.tools.r8.graph.FieldAccessInfoImpl.MISSING_FIELD_ACCESS_INFO;
import static com.android.tools.r8.ir.desugar.LambdaDescriptor.isLambdaMetafactoryMethod;
import static com.android.tools.r8.ir.desugar.itf.InterfaceMethodRewriter.Flavor.ExcludeDexResources;
import static com.android.tools.r8.naming.IdentifierNameStringUtils.identifyIdentifier;
import static com.android.tools.r8.naming.IdentifierNameStringUtils.isReflectionMethod;
import static com.android.tools.r8.utils.FunctionUtils.ignoreArgument;
import static com.android.tools.r8.utils.MapUtils.ignoreKey;
import static java.util.Collections.emptySet;

import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.cf.code.CfInstruction;
import com.android.tools.r8.cf.code.CfInvoke;
import com.android.tools.r8.code.CfOrDexInstruction;
import com.android.tools.r8.contexts.CompilationContext.MethodProcessingContext;
import com.android.tools.r8.contexts.CompilationContext.ProcessorContext;
import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.errors.InterfaceDesugarMissingTypeDiagnostic;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.experimental.graphinfo.GraphConsumer;
import com.android.tools.r8.graph.AppInfoWithClassHierarchy;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.ClassDefinition;
import com.android.tools.r8.graph.ClassResolutionResult;
import com.android.tools.r8.graph.ClasspathOrLibraryClass;
import com.android.tools.r8.graph.ClasspathOrLibraryDefinition;
import com.android.tools.r8.graph.Definition;
import com.android.tools.r8.graph.DexAnnotation;
import com.android.tools.r8.graph.DexAnnotation.AnnotatedKind;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexCallSite;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexClassAndMethod;
import com.android.tools.r8.graph.DexClasspathClass;
import com.android.tools.r8.graph.DexDefinition;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMember;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexItemFactory.ClassMethods;
import com.android.tools.r8.graph.DexLibraryClass;
import com.android.tools.r8.graph.DexMember;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexMethodHandle;
import com.android.tools.r8.graph.DexMethodSignature;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexReference;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexValue;
import com.android.tools.r8.graph.DirectMappedDexApplication;
import com.android.tools.r8.graph.EnclosingMethodAttribute;
import com.android.tools.r8.graph.FieldAccessInfoCollectionImpl;
import com.android.tools.r8.graph.FieldAccessInfoImpl;
import com.android.tools.r8.graph.FieldResolutionResult;
import com.android.tools.r8.graph.FieldResolutionResult.FailedOrUnknownFieldResolutionResult;
import com.android.tools.r8.graph.GenericSignatureEnqueuerAnalysis;
import com.android.tools.r8.graph.InnerClassAttribute;
import com.android.tools.r8.graph.InvalidCode;
import com.android.tools.r8.graph.LookupLambdaTarget;
import com.android.tools.r8.graph.LookupMethodTarget;
import com.android.tools.r8.graph.LookupResult;
import com.android.tools.r8.graph.LookupTarget;
import com.android.tools.r8.graph.MethodAccessInfoCollection;
import com.android.tools.r8.graph.MethodResolutionResult;
import com.android.tools.r8.graph.MethodResolutionResult.FailedResolutionResult;
import com.android.tools.r8.graph.MethodResolutionResult.SingleResolutionResult;
import com.android.tools.r8.graph.NestMemberClassAttribute;
import com.android.tools.r8.graph.ObjectAllocationInfoCollectionImpl;
import com.android.tools.r8.graph.ProgramDefinition;
import com.android.tools.r8.graph.ProgramDerivedContext;
import com.android.tools.r8.graph.ProgramField;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.graph.SubtypingInfo;
import com.android.tools.r8.graph.UseRegistry.MethodHandleUse;
import com.android.tools.r8.graph.analysis.ApiModelAnalysis;
import com.android.tools.r8.graph.analysis.EnqueuerAnalysis;
import com.android.tools.r8.graph.analysis.EnqueuerCheckCastAnalysis;
import com.android.tools.r8.graph.analysis.EnqueuerExceptionGuardAnalysis;
import com.android.tools.r8.graph.analysis.EnqueuerFieldAccessAnalysis;
import com.android.tools.r8.graph.analysis.EnqueuerInstanceOfAnalysis;
import com.android.tools.r8.graph.analysis.EnqueuerInvokeAnalysis;
import com.android.tools.r8.graph.analysis.GetArrayOfMissingTypeVerifyErrorWorkaround;
import com.android.tools.r8.graph.analysis.InvokeVirtualToInterfaceVerifyErrorWorkaround;
import com.android.tools.r8.ir.analysis.proto.ProtoEnqueuerUseRegistry;
import com.android.tools.r8.ir.analysis.proto.schema.ProtoEnqueuerExtension;
import com.android.tools.r8.ir.code.ArrayPut;
import com.android.tools.r8.ir.code.ConstantValueUtils;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InstructionIterator;
import com.android.tools.r8.ir.code.InvokeMethod;
import com.android.tools.r8.ir.code.InvokeVirtual;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.desugar.CfInstructionDesugaringCollection;
import com.android.tools.r8.ir.desugar.CfInstructionDesugaringEventConsumer;
import com.android.tools.r8.ir.desugar.CfInstructionDesugaringEventConsumer.R8CfInstructionDesugaringEventConsumer;
import com.android.tools.r8.ir.desugar.CfPostProcessingDesugaringCollection;
import com.android.tools.r8.ir.desugar.CfPostProcessingDesugaringEventConsumer;
import com.android.tools.r8.ir.desugar.CfPostProcessingDesugaringEventConsumer.R8PostProcessingDesugaringEventConsumer;
import com.android.tools.r8.ir.desugar.LambdaClass;
import com.android.tools.r8.ir.desugar.LambdaDescriptor;
import com.android.tools.r8.ir.desugar.ProgramAdditions;
import com.android.tools.r8.ir.desugar.constantdynamic.ConstantDynamicClass;
import com.android.tools.r8.ir.desugar.desugaredlibrary.apiconversion.DesugaredLibraryAPIConverter;
import com.android.tools.r8.ir.desugar.itf.InterfaceMethodProcessorFacade;
import com.android.tools.r8.ir.desugar.itf.InterfaceProcessor;
import com.android.tools.r8.kotlin.KotlinMetadataEnqueuerExtension;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.naming.identifiernamestring.IdentifierNameStringLookupResult;
import com.android.tools.r8.naming.identifiernamestring.IdentifierNameStringTypeLookupResult;
import com.android.tools.r8.position.Position;
import com.android.tools.r8.shaking.AnnotationMatchResult.MatchedAnnotation;
import com.android.tools.r8.shaking.DelayedRootSetActionItem.InterfaceMethodSyntheticBridgeAction;
import com.android.tools.r8.shaking.EnqueuerEvent.ClassEnqueuerEvent;
import com.android.tools.r8.shaking.EnqueuerEvent.InstantiatedClassEnqueuerEvent;
import com.android.tools.r8.shaking.EnqueuerEvent.LiveClassEnqueuerEvent;
import com.android.tools.r8.shaking.EnqueuerEvent.UnconditionalKeepInfoEvent;
import com.android.tools.r8.shaking.EnqueuerWorklist.EnqueuerAction;
import com.android.tools.r8.shaking.EnqueuerWorklist.TraceInstanceFieldReadAction;
import com.android.tools.r8.shaking.EnqueuerWorklist.TraceInstanceFieldWriteAction;
import com.android.tools.r8.shaking.EnqueuerWorklist.TraceStaticFieldReadAction;
import com.android.tools.r8.shaking.EnqueuerWorklist.TraceStaticFieldWriteAction;
import com.android.tools.r8.shaking.GraphReporter.KeepReasonWitness;
import com.android.tools.r8.shaking.KeepInfoCollection.MutableKeepInfoCollection;
import com.android.tools.r8.shaking.RootSetUtils.ConsequentRootSet;
import com.android.tools.r8.shaking.RootSetUtils.ConsequentRootSetBuilder;
import com.android.tools.r8.shaking.RootSetUtils.RootSet;
import com.android.tools.r8.shaking.RootSetUtils.RootSetBase;
import com.android.tools.r8.shaking.RootSetUtils.RootSetBuilder;
import com.android.tools.r8.shaking.ScopedDexMethodSet.AddMethodIfMoreVisibleResult;
import com.android.tools.r8.synthesis.SyntheticItems.SynthesizingContextOracle;
import com.android.tools.r8.utils.Action;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.IteratorUtils;
import com.android.tools.r8.utils.OptionalBool;
import com.android.tools.r8.utils.Pair;
import com.android.tools.r8.utils.SetUtils;
import com.android.tools.r8.utils.StringDiagnostic;
import com.android.tools.r8.utils.ThreadUtils;
import com.android.tools.r8.utils.Timing;
import com.android.tools.r8.utils.Visibility;
import com.android.tools.r8.utils.WorkList;
import com.android.tools.r8.utils.collections.DexMethodSignatureSet;
import com.android.tools.r8.utils.collections.ProgramFieldSet;
import com.android.tools.r8.utils.collections.ProgramMethodMap;
import com.android.tools.r8.utils.collections.ProgramMethodSet;
import com.google.common.base.Equivalence.Wrapper;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.collect.Sets.SetView;
import it.unimi.dsi.fastutil.objects.Object2BooleanArrayMap;
import it.unimi.dsi.fastutil.objects.Object2BooleanMap;
import java.lang.reflect.InvocationHandler;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Approximates the runtime dependencies for the given set of roots.
 *
 * <p>The implementation filters the static call-graph with liveness information on classes to
 * remove virtual methods that are reachable by their static type but are unreachable at runtime as
 * they are not visible from any instance.
 *
 * <p>As result of the analysis, an instance of {@link AppInfoWithLiveness} is returned. See the
 * field descriptions for details.
 */
public class Enqueuer {

  public enum Mode {
    INITIAL_TREE_SHAKING,
    FINAL_TREE_SHAKING,
    INITIAL_MAIN_DEX_TRACING,
    FINAL_MAIN_DEX_TRACING,
    GENERATE_MAIN_DEX_LIST,
    WHY_ARE_YOU_KEEPING;

    public boolean isTreeShaking() {
      return isInitialTreeShaking() || isFinalTreeShaking();
    }

    public boolean isInitialTreeShaking() {
      return this == INITIAL_TREE_SHAKING;
    }

    public boolean isFinalTreeShaking() {
      return this == FINAL_TREE_SHAKING;
    }

    public boolean isInitialMainDexTracing() {
      return this == INITIAL_MAIN_DEX_TRACING;
    }

    public boolean isFinalMainDexTracing() {
      return this == FINAL_MAIN_DEX_TRACING;
    }

    public boolean isGenerateMainDexList() {
      return this == GENERATE_MAIN_DEX_LIST;
    }

    public boolean isMainDexTracing() {
      return isInitialMainDexTracing() || isFinalMainDexTracing() || isGenerateMainDexList();
    }

    public boolean isWhyAreYouKeeping() {
      return this == WHY_ARE_YOU_KEEPING;
    }
  }

  private final boolean forceProguardCompatibility;
  private final Mode mode;

  private final Set<EnqueuerAnalysis> analyses = new LinkedHashSet<>();
  private final Set<EnqueuerFieldAccessAnalysis> fieldAccessAnalyses = new LinkedHashSet<>();
  private final Set<EnqueuerInvokeAnalysis> invokeAnalyses = new LinkedHashSet<>();
  private final Set<EnqueuerInstanceOfAnalysis> instanceOfAnalyses = new LinkedHashSet<>();
  private final Set<EnqueuerExceptionGuardAnalysis> exceptionGuardAnalyses = new LinkedHashSet<>();
  private final Set<EnqueuerCheckCastAnalysis> checkCastAnalyses = new LinkedHashSet<>();

  // Don't hold a direct pointer to app info (use appView).
  private AppInfoWithClassHierarchy appInfo;
  private final AppView<AppInfoWithClassHierarchy> appView;
  private final EnqueuerDeferredTracing deferredTracing;
  private final ExecutorService executorService;
  private SubtypingInfo subtypingInfo;
  private final InternalOptions options;
  private RootSet rootSet;
  private final EnqueuerUseRegistryFactory useRegistryFactory;
  private AnnotationRemover.Builder annotationRemoverBuilder;
  private final EnqueuerDefinitionSupplier enqueuerDefinitionSupplier =
      new EnqueuerDefinitionSupplier(this);

  private final FieldAccessInfoCollectionImpl fieldAccessInfoCollection =
      new FieldAccessInfoCollectionImpl();
  private final MethodAccessInfoCollection.IdentityBuilder methodAccessInfoCollection =
      MethodAccessInfoCollection.identityBuilder();
  private final ObjectAllocationInfoCollectionImpl.Builder objectAllocationInfoCollection;
  private final Map<DexCallSite, ProgramMethodSet> callSites = new IdentityHashMap<>();

  private final Set<DexMember<?, ?>> identifierNameStrings = Sets.newIdentityHashSet();

  /**
   * Tracks the dependency between a method and the super-method it calls, if any. Used to make
   * super methods become live when they become reachable from a live sub-method.
   */
  private final Map<DexEncodedMethod, ProgramMethodSet> superInvokeDependencies =
      Maps.newIdentityHashMap();
  /** Set of instance fields that can be reached by read/write operations. */
  private final Map<DexProgramClass, ProgramFieldSet> reachableInstanceFields =
      Maps.newIdentityHashMap();

  // TODO(b/180091213): Remove when supported by synthetic items.
  /**
   * The synthesizing contexts for classes synthesized by lambda desugaring and twr close resource
   * desugaring.
   */
  private final Map<DexProgramClass, ProgramMethod> synthesizingContexts = new IdentityHashMap<>();

  /**
   * Set of types that are mentioned in the program. We at least need an empty abstract class item
   * for these.
   */
  private final SetWithReportedReason<DexProgramClass> liveTypes = new SetWithReportedReason<>();

  /** Set of interfaces that have been transitioned to being instantiated indirectly. */
  private final Set<DexProgramClass> interfacesTransitionedToInstantiated =
      Sets.newIdentityHashSet();

  /** Set of classes whose initializer may execute. */
  private final SetWithReportedReason<DexProgramClass> initializedClasses =
      new SetWithReportedReason<>();

  /**
   * Set of interfaces whose interface initializer may execute directly in response to a static
   * field or method access on the interface.
   */
  private final SetWithReportedReason<DexProgramClass> directlyInitializedInterfaces =
      new SetWithReportedReason<>();

  /**
   * Set of interfaces whose interface initializer may execute indirectly as a side-effect of the
   * class initialization of a (non-interface) subclass.
   */
  private final SetWithReportedReason<DexProgramClass> indirectlyInitializedInterfaces =
      new SetWithReportedReason<>();

  /**
   * Set of live types defined in the library and classpath.
   *
   * <p>Used to build a new app of just referenced types and avoid duplicate tracing.
   */
  private final Set<ClasspathOrLibraryClass> liveNonProgramTypes = Sets.newIdentityHashSet();

  private final Set<ClasspathOrLibraryClass> referencedNonProgramTypes = Sets.newIdentityHashSet();

  /** Set of reachable proto types that will be dead code eliminated. */
  private final Set<DexProgramClass> deadProtoTypeCandidates = Sets.newIdentityHashSet();

  /** Set of missing types. */
  private final MissingClasses.Builder missingClassesBuilder;

  /** Set of proto types that were found to be dead during the first round of tree shaking. */
  private Set<DexType> initialDeadProtoTypes = Sets.newIdentityHashSet();

  /** Set of types that was pruned during the first round of tree shaking. */
  private Set<DexType> initialPrunedTypes;

  private final Set<DexType> noClassMerging = Sets.newIdentityHashSet();

  /** Mapping from each unused interface to the set of live types that implements the interface. */
  private final Map<DexProgramClass, Set<DexProgramClass>> unusedInterfaceTypes =
      new IdentityHashMap<>();

  /**
   * Set of methods that are the immediate target of an invoke. They might not actually be live but
   * are required so that invokes can find the method. If a method is only a target but not live,
   * its implementation may be removed and it may be marked abstract.
   */
  private final LiveMethodsSet targetedMethods;

  /** Set of methods that have invalid resolutions or lookups. */
  private final Set<DexMethod> failedMethodResolutionTargets;

  /** Set of methods that have invalid resolutions or lookups. */
  private final Set<DexField> failedFieldResolutionTargets;

  /**
   * Set of program methods that are used as the bootstrap method for an invoke-dynamic instruction.
   */
  private final Set<DexMethod> bootstrapMethods = Sets.newIdentityHashSet();
  /**
   * Set of virtual methods that are the immediate target of an invoke-direct.
   */
  private final Set<DexMethod> virtualMethodsTargetedByInvokeDirect = Sets.newIdentityHashSet();
  /**
   * Set of methods that belong to live classes and can be reached by invokes. These need to be
   * kept.
   */
  private final LiveMethodsSet liveMethods;

  /**
   * Set of fields that belong to live classes and can be reached by invokes. These need to be kept.
   */
  private final LiveFieldsSet liveFields;

  /** A queue of items that need processing. Different items trigger different actions. */
  private EnqueuerWorklist workList;

  private final ProguardCompatibilityActions.Builder proguardCompatibilityActionsBuilder;

  /** A set of methods that need code inspection for Java reflection in use. */
  private final ProgramMethodSet pendingReflectiveUses = ProgramMethodSet.createLinked();

  /** Mapping of types to the resolved methods for that type along with the context. */
  private final Map<DexProgramClass, Map<ResolutionSearchKey, ProgramMethodSet>>
      reachableVirtualTargets = new IdentityHashMap<>();

  /** Collection of keep requirements for the program. */
  private final MutableKeepInfoCollection keepInfo = new MutableKeepInfoCollection();

  /**
   * Conditional minimum keep info for classes, fields, and methods, which should only be applied if
   * the outermost {@link EnqueuerEvent} is triggered during tracing (e.g., class X becomes live).
   */
  private final DependentMinimumKeepInfoCollection dependentMinimumKeepInfo =
      new DependentMinimumKeepInfoCollection();

  /**
   * A set of seen const-class references that serve as an initial lock-candidate set and will
   * prevent class merging.
   */
  private final Set<DexType> lockCandidates = Sets.newIdentityHashSet();

  /**
   * A map from seen init-class references to the minimum required visibility of the corresponding
   * static field.
   */
  private final Map<DexType, Visibility> initClassReferences = new IdentityHashMap<>();

  /**
   * A map from seen init-class references to the minimum required visibility of the corresponding
   * static field.
   */
  private final Set<DexMethod> recordFieldValuesReferences = Sets.newIdentityHashSet();

  /**
   * A map from annotation classes to annotations that need to be processed should the classes ever
   * become live.
   */
  private final Map<DexType, Map<DexAnnotation, List<ProgramDefinition>>> deferredAnnotations =
      new IdentityHashMap<>();

  /**
   * A map from annotation classes to parameter annotations that need to be processed should the
   * classes ever become live.
   */
  private final Map<DexType, Map<DexAnnotation, List<ProgramDefinition>>>
      deferredParameterAnnotations = new IdentityHashMap<>();

  /** Map of active if rules to speed up aapt2 generated keep rules. */
  private Map<Wrapper<ProguardIfRule>, Set<ProguardIfRule>> activeIfRules;

  /**
   * A cache of ScopedDexMethodSet for each live type used for determining that virtual methods that
   * cannot be removed because they are widening access for another virtual method defined earlier
   * in the type hierarchy. See b/136698023 for more information.
   */
  private final Map<DexType, ScopedDexMethodSet> scopedMethodsForLiveTypes =
      new IdentityHashMap<>();

  private final GraphReporter graphReporter;

  private final CfInstructionDesugaringCollection desugaring;
  private final ProgramMethodSet pendingCodeDesugaring = ProgramMethodSet.create();

  // Collections for tracing progress on interface method desugaring.

  // The pending method move set is all the methods that need to be moved to companions.
  // They may or may not need desugaring.
  private final ProgramMethodSet pendingMethodMove = ProgramMethodSet.create();

  // The inverse map records references to companion methods that may now be active but yet to
  // be moved.
  private final ProgramMethodMap<ProgramMethod> pendingMethodMoveInverse =
      ProgramMethodMap.createConcurrent();

  private final InterfaceProcessor interfaceProcessor;

  Enqueuer(
      AppView<? extends AppInfoWithClassHierarchy> appView,
      ExecutorService executorService,
      SubtypingInfo subtypingInfo,
      GraphConsumer keptGraphConsumer,
      Mode mode) {
    assert appView.appServices() != null;
    InternalOptions options = appView.options();
    this.appInfo = appView.appInfo();
    this.appView = appView.withClassHierarchy();
    this.deferredTracing = new EnqueuerDeferredTracing(appView, this, mode);
    this.executorService = executorService;
    this.subtypingInfo = subtypingInfo;
    this.forceProguardCompatibility = options.forceProguardCompatibility;
    this.graphReporter = new GraphReporter(appView, keptGraphConsumer);
    this.missingClassesBuilder = appView.appInfo().getMissingClasses().builder();
    this.mode = mode;
    this.options = options;
    this.useRegistryFactory = createUseRegistryFactory();
    this.workList = EnqueuerWorklist.createWorklist(this);
    this.proguardCompatibilityActionsBuilder =
        mode.isInitialTreeShaking() && options.forceProguardCompatibility
            ? ProguardCompatibilityActions.builder()
            : null;

    if (mode.isTreeShaking()) {
      GetArrayOfMissingTypeVerifyErrorWorkaround.register(appView, this);
      InvokeVirtualToInterfaceVerifyErrorWorkaround.register(appView, this);
      if (options.protoShrinking().enableGeneratedMessageLiteShrinking) {
        registerAnalysis(new ProtoEnqueuerExtension(appView));
      }
      appView.withGeneratedMessageLiteBuilderShrinker(
          shrinker -> registerAnalysis(shrinker.createEnqueuerAnalysis()));
    }

    targetedMethods = new LiveMethodsSet(graphReporter::registerMethod);
    // This set is only populated in edge cases due to multiple default interface methods.
    // The set is generally expected to be empty and in the unlikely chance it is not, it will
    // likely contain two methods. Thus the default capacity of 2.
    failedMethodResolutionTargets = SetUtils.newIdentityHashSet(2);
    failedFieldResolutionTargets = SetUtils.newIdentityHashSet(0);
    liveMethods = new LiveMethodsSet(graphReporter::registerMethod);
    liveFields = new LiveFieldsSet(graphReporter::registerField);
    if (mode.isInitialTreeShaking()) {
      desugaring = CfInstructionDesugaringCollection.create(appView, appView.apiLevelCompute());
      interfaceProcessor = new InterfaceProcessor(appView);
    } else {
      desugaring = CfInstructionDesugaringCollection.empty();
      interfaceProcessor = null;
    }

    objectAllocationInfoCollection =
        ObjectAllocationInfoCollectionImpl.builder(mode.isInitialTreeShaking(), graphReporter);
  }

  private AppInfoWithClassHierarchy appInfo() {
    return appView.appInfo();
  }

  public Mode getMode() {
    return mode;
  }

  public GraphReporter getGraphReporter() {
    return graphReporter;
  }

  private EnqueuerUseRegistryFactory createUseRegistryFactory() {
    if (mode.isFinalTreeShaking()) {
      return appView.withGeneratedMessageLiteShrinker(
          ignore -> ProtoEnqueuerUseRegistry.getFactory(), DefaultEnqueuerUseRegistry::new);
    }
    return DefaultEnqueuerUseRegistry::new;
  }

  public EnqueuerUseRegistryFactory getUseRegistryFactory() {
    return useRegistryFactory;
  }

  public Enqueuer registerAnalysis(EnqueuerAnalysis analysis) {
    analyses.add(analysis);
    return this;
  }

  public Enqueuer registerFieldAccessAnalysis(EnqueuerFieldAccessAnalysis analysis) {
    fieldAccessAnalyses.add(analysis);
    return this;
  }

  public Enqueuer registerInvokeAnalysis(EnqueuerInvokeAnalysis analysis) {
    invokeAnalyses.add(analysis);
    return this;
  }

  public Enqueuer registerInstanceOfAnalysis(EnqueuerInstanceOfAnalysis analysis) {
    instanceOfAnalyses.add(analysis);
    return this;
  }

  public Enqueuer registerCheckCastAnalysis(EnqueuerCheckCastAnalysis analysis) {
    checkCastAnalyses.add(analysis);
    return this;
  }

  public Enqueuer registerExceptionGuardAnalysis(EnqueuerExceptionGuardAnalysis analysis) {
    exceptionGuardAnalyses.add(analysis);
    return this;
  }

  public void setAnnotationRemoverBuilder(AnnotationRemover.Builder annotationRemoverBuilder) {
    this.annotationRemoverBuilder = annotationRemoverBuilder;
  }

  public void setInitialDeadProtoTypes(Set<DexType> initialDeadProtoTypes) {
    assert mode.isFinalTreeShaking();
    this.initialDeadProtoTypes = initialDeadProtoTypes;
  }

  public void setInitialPrunedTypes(Set<DexType> initialPrunedTypes) {
    assert mode.isFinalTreeShaking();
    this.initialPrunedTypes = initialPrunedTypes;
  }

  public void addDeadProtoTypeCandidate(DexType type) {
    DexProgramClass clazz = asProgramClassOrNull(appView.definitionFor(type));
    if (clazz != null) {
      addDeadProtoTypeCandidate(clazz);
    }
  }

  public void addDeadProtoTypeCandidate(DexProgramClass clazz) {
    deadProtoTypeCandidates.add(clazz);
  }

  public boolean addLiveMethod(ProgramMethod method, KeepReason reason) {
    return liveMethods.add(method, reason);
  }

  public boolean addTargetedMethod(ProgramMethod method, KeepReason reason) {
    return targetedMethods.add(method, reason);
  }

  private void recordCompilerSynthesizedTypeReference(DexType type) {
    DexClass clazz = appInfo().definitionFor(type);
    if (clazz == null) {
      ignoreMissingClass(type);
    } else if (clazz.isNotProgramClass()) {
      addLiveNonProgramType(
          clazz.asClasspathOrLibraryClass(), true, this::ignoreMissingClasspathOrLibraryClass);
    }
  }

  private void recordTypeReference(DexType type, ProgramDefinition context) {
    recordTypeReference(type, context, this::recordNonProgramClass, this::reportMissingClass);
  }

  private void recordTypeReference(DexType type, ProgramDerivedContext context) {
    recordTypeReference(type, context, this::recordNonProgramClass, this::reportMissingClass);
  }

  private void recordTypeReference(
      DexType type,
      ProgramDerivedContext context,
      BiConsumer<DexClass, ProgramDerivedContext> foundClassConsumer,
      BiConsumer<DexType, ProgramDerivedContext> missingClassConsumer) {
    if (type == null) {
      return;
    }
    if (type.isArrayType()) {
      type = type.toBaseType(appView.dexItemFactory());
    }
    if (!type.isClassType()) {
      return;
    }
    // Lookup the definition, ignoring the result. This populates the missing and referenced sets.
    definitionFor(type, context, foundClassConsumer, missingClassConsumer);
  }

  private void recordMethodReference(DexMethod method, ProgramDerivedContext context) {
    recordMethodReference(method, context, this::recordNonProgramClass, this::reportMissingClass);
  }

  private void recordMethodReference(
      DexMethod method,
      ProgramDerivedContext context,
      BiConsumer<DexClass, ProgramDerivedContext> foundClassConsumer,
      BiConsumer<DexType, ProgramDerivedContext> missingClassConsumer) {
    recordTypeReference(method.holder, context, foundClassConsumer, missingClassConsumer);
    recordTypeReference(method.proto.returnType, context, foundClassConsumer, missingClassConsumer);
    for (DexType type : method.proto.parameters.values) {
      recordTypeReference(type, context, foundClassConsumer, missingClassConsumer);
    }
  }

  private void recordFieldReference(DexField field, ProgramDerivedContext context) {
    recordTypeReference(field.getHolderType(), context);
    recordTypeReference(field.getType(), context);
  }

  public DexEncodedMethod definitionFor(DexMethod method, ProgramDefinition context) {
    DexClass clazz = definitionFor(method.holder, context);
    if (clazz == null) {
      return null;
    }
    return clazz.lookupMethod(method);
  }

  public DexClass definitionFor(DexType type, ProgramDefinition context) {
    return definitionFor(type, context, this::recordNonProgramClass, this::reportMissingClass);
  }

  public boolean hasAlternativeLibraryDefinition(DexProgramClass programClass) {
    ClassResolutionResult classResolutionResult =
        internalDefinitionFor(
            programClass.type, programClass, this::recordNonProgramClass, this::reportMissingClass);
    assert classResolutionResult.hasClassResolutionResult();
    DexClass alternativeClass = classResolutionResult.toAlternativeClassWithProgramOverLibrary();
    assert alternativeClass == null || alternativeClass.isLibraryClass();
    return alternativeClass != null;
  }

  private DexClass definitionFor(
      DexType type,
      ProgramDerivedContext context,
      BiConsumer<DexClass, ProgramDerivedContext> foundClassConsumer,
      BiConsumer<DexType, ProgramDerivedContext> missingClassConsumer) {
    return internalDefinitionFor(type, context, foundClassConsumer, missingClassConsumer)
        .toSingleClassWithProgramOverLibrary();
  }

  private ClassResolutionResult internalDefinitionFor(
      DexType type,
      ProgramDerivedContext context,
      BiConsumer<DexClass, ProgramDerivedContext> foundClassConsumer,
      BiConsumer<DexType, ProgramDerivedContext> missingClassConsumer) {
    ClassResolutionResult classResolutionResult =
        appInfo().contextIndependentDefinitionForWithResolutionResult(type);
    if (classResolutionResult.hasClassResolutionResult()) {
      classResolutionResult.forEachClassResolutionResult(
          clazz -> foundClassConsumer.accept(clazz, context));
    } else {
      missingClassConsumer.accept(type, context);
    }
    return classResolutionResult;
  }

  public FieldAccessInfoCollectionImpl getFieldAccessInfoCollection() {
    return fieldAccessInfoCollection;
  }

  public MutableKeepInfoCollection getKeepInfo() {
    return keepInfo;
  }

  public KeepClassInfo getKeepInfo(DexProgramClass clazz) {
    return keepInfo.getClassInfo(clazz);
  }

  public KeepFieldInfo getKeepInfo(ProgramField field) {
    return keepInfo.getFieldInfo(field);
  }

  public ObjectAllocationInfoCollectionImpl getObjectAllocationInfoCollection() {
    return objectAllocationInfoCollection;
  }

  public EnqueuerWorklist getWorklist() {
    return workList;
  }

  private void addLiveNonProgramType(
      ClasspathOrLibraryClass clazz,
      // TODO(b/216576191): Remove when tracking live library members.
      boolean markProgramSuperTypesAsLiveAndVisitMemberReferences,
      BiConsumer<DexType, ClasspathOrLibraryDefinition> missingClassConsumer) {
    WorkList<ClasspathOrLibraryClass> worklist =
        WorkList.newIdentityWorkList(clazz, liveNonProgramTypes);
    while (worklist.hasNext()) {
      ClasspathOrLibraryClass definition = worklist.next();
      processNewLiveNonProgramType(
          definition,
          worklist,
          missingClassConsumer,
          markProgramSuperTypesAsLiveAndVisitMemberReferences);
    }
  }

  private void processNewLiveNonProgramType(
      ClasspathOrLibraryClass clazz,
      WorkList<ClasspathOrLibraryClass> worklist,
      BiConsumer<DexType, ClasspathOrLibraryDefinition> missingClassConsumer,
      boolean markProgramSuperTypesAsLiveAndVisitMemberReferences) {
    ensureMethodsContinueToWidenAccess(clazz);
    if (markProgramSuperTypesAsLiveAndVisitMemberReferences) {
      if (clazz.isLibraryClass()) {
        // Only libraries must not derive program. Classpath classes can, assuming correct keep
        // rules.
        handleLibraryTypeInheritingFromProgramType(clazz.asLibraryClass());
      }
      clazz.forEachClassField(
          field ->
              addNonProgramClassToWorklist(
                  field.getType(),
                  field.asClasspathOrLibraryDefinition(),
                  referencedNonProgramTypes::add,
                  missingClassConsumer));
      clazz.forEachClassMethod(
          method -> {
            ClasspathOrLibraryDefinition derivedContext = method.asClasspathOrLibraryDefinition();
            addNonProgramClassToWorklist(
                method.getReturnType(),
                derivedContext,
                referencedNonProgramTypes::add,
                missingClassConsumer);
            for (DexType parameter : method.getParameters()) {
              addNonProgramClassToWorklist(
                  parameter, derivedContext, referencedNonProgramTypes::add, missingClassConsumer);
            }
          });
    }
    for (DexType supertype : clazz.allImmediateSupertypes()) {
      addNonProgramClassToWorklist(
          supertype,
          clazz.asClasspathOrLibraryDefinition(),
          worklist::addIfNotSeen,
          missingClassConsumer);
    }
  }

  private void addNonProgramClassToWorklist(
      DexType type,
      ClasspathOrLibraryDefinition context,
      Consumer<ClasspathOrLibraryClass> classAdder,
      BiConsumer<DexType, ClasspathOrLibraryDefinition> missingClassConsumer) {
    if (type.isArrayType()) {
      type = type.toBaseType(appView.dexItemFactory());
    }
    if (!type.isClassType()) {
      return;
    }
    ClassResolutionResult classResolutionResult =
        appView.contextIndependentDefinitionForWithResolutionResult(type);
    if (!classResolutionResult.hasClassResolutionResult()) {
      missingClassConsumer.accept(type, context);
      return;
    }
    classResolutionResult.forEachClassResolutionResult(
        clazz -> {
          if (!clazz.isProgramClass()) {
            classAdder.accept(clazz.asClasspathOrLibraryClass());
          }
        });
  }

  private DexProgramClass getProgramClassOrNull(DexType type, ProgramDefinition context) {
    DexClass clazz = definitionFor(type, context);
    return clazz != null && clazz.isProgramClass() ? clazz.asProgramClass() : null;
  }

  private DexProgramClass getProgramHolderOrNull(
      DexMember<?, ?> member, ProgramDefinition context) {
    return getProgramClassOrNull(member.getHolderType(), context);
  }

  private DexClass getClassOrNullFromReflectiveAccess(DexType type, ProgramDefinition context) {
    // To avoid that we report reflectively accessed types as missing.
    return definitionFor(
        type, context, this::recordNonProgramClassWithNoMissingReporting, this::ignoreMissingClass);
  }

  private DexProgramClass getProgramClassOrNullFromReflectiveAccess(
      DexType type, ProgramDefinition context) {
    return asProgramClassOrNull(getClassOrNullFromReflectiveAccess(type, context));
  }

  private void handleLibraryTypeInheritingFromProgramType(DexLibraryClass clazz) {
    if (clazz.superType != null) {
      ensureFromLibraryOrThrow(clazz.superType, clazz);
    }
    for (DexType iface : clazz.interfaces.values) {
      ensureFromLibraryOrThrow(iface, clazz);
    }
  }

  private void warnIfClassExtendsInterfaceOrImplementsClass(DexProgramClass clazz) {
    if (clazz.superType != null) {
      DexClass superClass = definitionFor(clazz.superType, clazz);
      if (superClass != null && superClass.isInterface()) {
        options.reporter.warning(
            new StringDiagnostic(
                "Class "
                    + clazz.toSourceString()
                    + " extends "
                    + superClass.toSourceString()
                    + " which is an interface"));
      }
    }
    for (DexType iface : clazz.interfaces.values) {
      DexClass ifaceClass = definitionFor(iface, clazz);
      if (ifaceClass != null && !ifaceClass.isInterface()) {
        options.reporter.warning(
            new StringDiagnostic(
                "Class "
                    + clazz.toSourceString()
                    + " implements "
                    + ifaceClass.toSourceString()
                    + " which is not an interface"));
      }
    }
  }

  private void enqueueAllIfNotShrinking() {
    if (appView.options().isShrinking()) {
      return;
    }
    // Add everything if we are not shrinking.
    KeepClassInfo.Joiner keepClassInfo = KeepClassInfo.newEmptyJoiner().disallowShrinking();
    KeepFieldInfo.Joiner keepFieldInfo = KeepFieldInfo.newEmptyJoiner().disallowShrinking();
    KeepMethodInfo.Joiner keepMethodInfo = KeepMethodInfo.newEmptyJoiner().disallowShrinking();
    EnqueuerEvent preconditionEvent = UnconditionalKeepInfoEvent.get();
    for (DexProgramClass clazz : appView.appInfo().classes()) {
      if (appView.getSyntheticItems().isSyntheticClass(clazz)
          && !appView.getSyntheticItems().isSubjectToKeepRules(clazz)) {
        // Don't treat compiler synthesized classes as kept roots.
        continue;
      }
      enqueueClassDueToNoShrinkingRule(clazz, keepClassInfo, preconditionEvent);
      clazz.forEachProgramField(
          field -> enqueueFieldDueToNoShrinkingRule(field, keepFieldInfo, preconditionEvent));
      clazz.forEachProgramMethod(
          method -> enqueueMethodDueToNoShrinkingRule(method, keepMethodInfo, preconditionEvent));
    }
  }

  private void enqueueClassDueToNoShrinkingRule(
      DexProgramClass clazz,
      KeepClassInfo.Joiner minimumKeepInfo,
      EnqueuerEvent preconditionEvent) {
    assert minimumKeepInfo.verifyShrinkingDisallowedWithRule(options);
    DexDefinition precondition = preconditionEvent.getDefinition(appInfo());
    enqueueKeepRuleInstantiatedType(clazz, minimumKeepInfo.getRules(), precondition);
  }

  private void enqueueKeepRuleInstantiatedType(
      DexProgramClass clazz, Set<ProguardKeepRuleBase> rules, DexDefinition precondition) {
    KeepReasonWitness witness = graphReporter.reportKeepClass(precondition, rules, clazz);
    if (clazz.isAnnotation()) {
      workList.enqueueMarkAnnotationInstantiatedAction(clazz, witness);
    } else if (clazz.isInterface()) {
      workList.enqueueMarkInterfaceInstantiatedAction(clazz, witness);
    } else {
      workList.enqueueMarkInstantiatedAction(clazz, null, InstantiationReason.KEEP_RULE, witness);
      if (clazz.hasDefaultInitializer()) {
        ProgramMethod defaultInitializer = clazz.getProgramDefaultInitializer();
        if (forceProguardCompatibility) {
          workList.enqueueMarkMethodKeptAction(
              defaultInitializer,
              graphReporter.reportCompatKeepDefaultInitializer(defaultInitializer));
        }
        if (clazz.isExternalizable(appView)) {
          workList.enqueueMarkMethodLiveAction(defaultInitializer, defaultInitializer, witness);
          applyMinimumKeepInfoWhenLiveOrTargeted(
              defaultInitializer, KeepMethodInfo.newEmptyJoiner().disallowOptimization());
        }
      }
    }
  }

  private void enqueueFieldDueToNoShrinkingRule(
      ProgramField field, KeepFieldInfo.Joiner minimumKeepInfo, EnqueuerEvent preconditionEvent) {
    assert minimumKeepInfo.verifyShrinkingDisallowedWithRule(options);
    DexDefinition precondition = preconditionEvent.getDefinition(appInfo());
    workList.enqueueMarkFieldKeptAction(
        field,
        graphReporter.reportKeepField(
            precondition, minimumKeepInfo.getRules(), field.getDefinition()));
  }

  private void enqueueMethodDueToNoShrinkingRule(
      ProgramMethod method,
      KeepMethodInfo.Joiner minimumKeepInfo,
      EnqueuerEvent preconditionEvent) {
    assert minimumKeepInfo.verifyShrinkingDisallowedWithRule(options);
    DexDefinition precondition = preconditionEvent.getDefinition(appInfo());
    workList.enqueueMarkMethodKeptAction(
        method,
        graphReporter.reportKeepMethod(
            precondition, minimumKeepInfo.getRules(), method.getDefinition()));
  }

  private void enqueueFirstNonSerializableClassInitializer(
      DexProgramClass clazz, KeepReason reason) {
    assert clazz.isSerializable(appView);
    // Climb up the class hierarchy. Break out if the definition is not found, or hit the library
    // classes which are kept by definition, or encounter the first non-serializable class.
    while (clazz.isSerializable(appView)) {
      DexProgramClass superClass = getProgramClassOrNull(clazz.superType, clazz);
      if (superClass == null) {
        return;
      }
      clazz = superClass;
    }
    if (clazz.hasDefaultInitializer()) {
      workList.enqueueMarkMethodLiveAction(clazz.getProgramDefaultInitializer(), clazz, reason);
      applyMinimumKeepInfoWhenLiveOrTargeted(
          clazz.getProgramDefaultInitializer(),
          KeepMethodInfo.newEmptyJoiner().disallowOptimization());
    }
  }

  private void compatEnqueueHolderIfDependentNonStaticMember(
      DexProgramClass holder, Set<ProguardKeepRuleBase> compatRules) {
    if (!forceProguardCompatibility || compatRules == null) {
      return;
    }
    // TODO(b/120959039): This needs the set of instance member as preconditon.
    enqueueKeepRuleInstantiatedType(holder, compatRules, null);
  }

  //
  // Things to do with registering events. This is essentially the interface for byte-code
  // traversals.
  //

  private boolean registerMethodWithTargetAndContext(
      BiPredicate<DexMethod, ProgramMethod> registration, DexMethod method, ProgramMethod context) {
    DexType baseHolder = method.holder.toBaseType(appView.dexItemFactory());
    if (baseHolder.isClassType()) {
      markTypeAsLive(baseHolder, context);
      return registration.test(method, context);
    }
    return false;
  }

  public boolean registerFieldRead(DexField field, ProgramMethod context) {
    return registerFieldAccess(field, context, true, false);
  }

  public boolean registerReflectiveFieldRead(ProgramField field, ProgramMethod context) {
    return registerFieldAccess(field.getReference(), context, true, true);
  }

  public boolean registerFieldWrite(DexField field, ProgramMethod context) {
    return registerFieldAccess(field, context, false, false);
  }

  public boolean registerReflectiveFieldWrite(ProgramField field, ProgramMethod context) {
    return registerFieldAccess(field.getReference(), context, false, true);
  }

  public void traceReflectiveFieldAccess(ProgramField field, ProgramMethod context) {
    deferredTracing.notifyReflectiveFieldAccess(field, context);
    boolean changed = registerReflectiveFieldRead(field, context);
    changed |= registerReflectiveFieldWrite(field, context);
    if (changed) {
      markFieldAsReachable(field, context, KeepReason.reflectiveUseIn(context));
    }
  }

  public void traceReflectiveFieldRead(ProgramField field, ProgramMethod context) {
    deferredTracing.notifyReflectiveFieldAccess(field, context);
    if (registerReflectiveFieldRead(field, context)) {
      markFieldAsReachable(field, context, KeepReason.reflectiveUseIn(context));
    }
  }

  public void traceReflectiveFieldWrite(ProgramField field, ProgramMethod context) {
    deferredTracing.notifyReflectiveFieldAccess(field, context);
    if (registerReflectiveFieldWrite(field, context)) {
      markFieldAsReachable(field, context, KeepReason.reflectiveUseIn(context));
    }
  }

  private boolean registerFieldAccess(
      DexField field, ProgramMethod context, boolean isRead, boolean isReflective) {
    FieldAccessInfoImpl info = fieldAccessInfoCollection.get(field);
    if (info == null) {
      DexEncodedField encodedField = resolveField(field, context).getResolvedField();

      // If the field does not exist, then record this in the mapping, such that we don't have to
      // resolve the field the next time.
      if (encodedField == null) {
        fieldAccessInfoCollection.extend(field, MISSING_FIELD_ACCESS_INFO);
        return true;
      }

      // Check if we have previously created a FieldAccessInfo object for the field definition.
      info = fieldAccessInfoCollection.get(encodedField.getReference());

      // If not, we must create one.
      if (info == null) {
        info = new FieldAccessInfoImpl(encodedField.getReference());
        fieldAccessInfoCollection.extend(encodedField.getReference(), info);
      }

      // If `field` is an indirect reference, then create a mapping for it, such that we don't have
      // to resolve the field the next time we see the reference.
      if (field != encodedField.getReference()) {
        fieldAccessInfoCollection.extend(field, info);
      }
    } else if (info == MISSING_FIELD_ACCESS_INFO) {
      return false;
    }
    if (isReflective) {
      if (isRead) {
        if (!info.hasReflectiveRead()) {
          info.setHasReflectiveRead();
          return true;
        }
      } else {
        if (!info.hasReflectiveWrite()) {
          info.setHasReflectiveWrite();
          return true;
        }
      }
      return false;
    }
    return isRead ? info.recordRead(field, context) : info.recordWrite(field, context);
  }

  void traceCallSite(DexCallSite callSite, ProgramMethod context) {
    // Do not lookup java.lang.invoke.LambdaMetafactory when compiling for DEX to avoid reporting
    // the class as missing.
    if (options.isGeneratingClassFiles() || !isLambdaMetafactoryMethod(callSite, appInfo())) {
      DexProgramClass bootstrapClass =
          getProgramHolderOrNull(callSite.bootstrapMethod.asMethod(), context);
      if (bootstrapClass != null) {
        bootstrapMethods.add(callSite.bootstrapMethod.asMethod());
      }
    }

    LambdaDescriptor descriptor = LambdaDescriptor.tryInfer(callSite, appInfo(), context);
    if (descriptor == null) {
      for (DexValue bootstrapArgument : callSite.getBootstrapArgs()) {
        if (bootstrapArgument.isDexValueMethodHandle()) {
          DexMethodHandle method = bootstrapArgument.asDexValueMethodHandle().getValue();
          if (method.isMethodHandle()) {
            disableClosedWorldReasoning(method.asMethod(), context);
          }
        }
      }
      return;
    }

    assert options.desugarState.isOff();

    markLambdaAsInstantiated(descriptor, context);
    transitionMethodsForInstantiatedLambda(descriptor);
    callSites.computeIfAbsent(callSite, ignore -> ProgramMethodSet.create()).add(context);
    descriptor.captures.forEach(type -> markTypeAsLive(type, context));

    // For call sites representing a lambda, we link the targeted method
    // or field as if it were referenced from the current method.

    DexMethodHandle implHandle = descriptor.implHandle;
    assert implHandle != null;

    DexMethod method = implHandle.asMethod();
    switch (implHandle.type) {
      case INVOKE_STATIC:
        traceInvokeStaticFromLambda(method, context);
        break;
      case INVOKE_INTERFACE:
        traceInvokeInterfaceFromLambda(method, context);
        break;
      case INVOKE_INSTANCE:
        traceInvokeVirtualFromLambda(method, context);
        break;
      case INVOKE_DIRECT:
        traceInvokeDirectFromLambda(method, context);
        break;
      case INVOKE_CONSTRUCTOR:
        traceNewInstanceFromLambda(method.holder, context);
        break;
      default:
        throw new Unreachable();
    }

    disableClosedWorldReasoning(method, context);
  }

  private void disableClosedWorldReasoning(DexMethod reference, ProgramMethod context) {
    SingleResolutionResult<?> resolutionResult =
        resolveMethod(reference, context, KeepReason.methodHandleReferencedIn(context));
    if (resolutionResult != null && resolutionResult.getResolvedHolder().isProgramClass()) {
      applyMinimumKeepInfoWhenLiveOrTargeted(
          resolutionResult.getResolvedProgramMethod(),
          KeepMethodInfo.newEmptyJoiner().disallowClosedWorldReasoning());
    }
  }

  void traceCheckCast(DexType type, ProgramMethod currentMethod, boolean ignoreCompatRules) {
    checkCastAnalyses.forEach(analysis -> analysis.traceCheckCast(type, currentMethod));
    internalTraceConstClassOrCheckCast(type, currentMethod, ignoreCompatRules);
  }

  void traceSafeCheckCast(DexType type, ProgramMethod currentMethod) {
    checkCastAnalyses.forEach(analysis -> analysis.traceSafeCheckCast(type, currentMethod));
    internalTraceConstClassOrCheckCast(type, currentMethod, true);
  }

  void traceConstClass(
      DexType type,
      ProgramMethod currentMethod,
      ListIterator<? extends CfOrDexInstruction> iterator,
      boolean ignoreCompatRules) {
    handleLockCandidate(type, currentMethod, iterator);
    internalTraceConstClassOrCheckCast(type, currentMethod, ignoreCompatRules);
  }

  private void handleLockCandidate(
      DexType type,
      ProgramMethod currentMethod,
      ListIterator<? extends CfOrDexInstruction> iterator) {
    // We conservatively group T.class and T[].class to ensure that we do not merge T with S if
    // potential locks on T[].class and S[].class exists.
    DexType baseType = type.toBaseType(appView.dexItemFactory());
    if (baseType.isClassType()) {
      DexProgramClass baseClass = getProgramClassOrNull(baseType, currentMethod);
      if (baseClass != null && isConstClassMaybeUsedAsLock(currentMethod, iterator)) {
        lockCandidates.add(baseType);
      }
    }
  }

  /**
   * Returns true if the const-class value may flow into a monitor instruction.
   *
   * <p>Some common usages of const-class values are handled, such as calls to Class.get*Name().
   */
  private boolean isConstClassMaybeUsedAsLock(
      ProgramMethod currentMethod, ListIterator<? extends CfOrDexInstruction> iterator) {
    if (iterator == null) {
      return true;
    }
    boolean result = true;
    if (currentMethod.getDefinition().getCode().isCfCode()) {
      CfInstruction nextInstruction =
          IteratorUtils.nextUntil(
                  iterator,
                  instruction ->
                      !instruction.asCfInstruction().isLabel()
                          && !instruction.asCfInstruction().isPosition())
              .asCfInstruction();
      assert nextInstruction != null;
      if (nextInstruction.isInvoke()) {
        CfInvoke invoke = nextInstruction.asInvoke();
        DexMethod invokedMethod = invoke.getMethod();
        ClassMethods classMethods = appView.dexItemFactory().classMethods;
        if (classMethods.isReflectiveNameLookup(invokedMethod)
            || invokedMethod == classMethods.desiredAssertionStatus
            || invokedMethod == classMethods.getClassLoader
            || invokedMethod == classMethods.getPackage) {
          result = false;
        }
      }
      iterator.previous();
    }
    return result;
  }

  private void internalTraceConstClassOrCheckCast(
      DexType type, ProgramMethod currentMethod, boolean ignoreCompatRules) {
    traceTypeReference(type, currentMethod);
    if (!forceProguardCompatibility || ignoreCompatRules) {
      return;
    }
    DexType baseType = type.toBaseType(appView.dexItemFactory());
    if (baseType.isClassType()) {
      DexProgramClass baseClass = getProgramClassOrNull(baseType, currentMethod);
      if (baseClass != null) {
        // Don't require any constructor, see b/112386012.
        markClassAsInstantiatedWithCompatRule(
            baseClass, () -> graphReporter.reportCompatInstantiated(baseClass, currentMethod));
      }
    }
  }

  void traceRecordFieldValues(DexField[] fields, ProgramMethod currentMethod) {
    // TODO(b/203377129): Consider adding an enqueuer extension instead of growing the
    //  number of fields in appInfoWithLiveness.
    if (mode.isFinalTreeShaking()) {
      recordFieldValuesReferences.add(currentMethod.getReference());
    }
  }

  void traceInitClass(DexType type, ProgramMethod currentMethod) {
    assert type.isClassType();

    Visibility oldMinimumRequiredVisibility = initClassReferences.get(type);
    if (oldMinimumRequiredVisibility == null) {
      DexProgramClass clazz = getProgramClassOrNull(type, currentMethod);
      if (clazz == null) {
        assert false;
        return;
      }

      initClassReferences.put(
          type, computeMinimumRequiredVisibilityForInitClassField(type, currentMethod.getHolder()));

      markTypeAsLive(clazz, currentMethod);
      markDirectAndIndirectClassInitializersAsLive(clazz);
      return;
    }

    if (oldMinimumRequiredVisibility.isPublic()) {
      return;
    }

    Visibility minimumRequiredVisibilityForCurrentMethod =
        computeMinimumRequiredVisibilityForInitClassField(type, currentMethod.getHolder());

    // There should never be a need to have an InitClass instruction for the enclosing class.
    assert !minimumRequiredVisibilityForCurrentMethod.isPrivate();

    if (minimumRequiredVisibilityForCurrentMethod.isPublic()) {
      initClassReferences.put(type, minimumRequiredVisibilityForCurrentMethod);
      return;
    }

    if (oldMinimumRequiredVisibility.isProtected()) {
      return;
    }

    if (minimumRequiredVisibilityForCurrentMethod.isProtected()) {
      initClassReferences.put(type, minimumRequiredVisibilityForCurrentMethod);
      return;
    }

    assert oldMinimumRequiredVisibility.isPackagePrivate();
    assert minimumRequiredVisibilityForCurrentMethod.isPackagePrivate();
  }

  private Visibility computeMinimumRequiredVisibilityForInitClassField(
      DexType clazz, DexProgramClass context) {
    if (clazz.isSamePackage(context.type)) {
      return Visibility.PACKAGE_PRIVATE;
    }
    if (appInfo.isStrictSubtypeOf(context.type, clazz)) {
      return Visibility.PROTECTED;
    }
    return Visibility.PUBLIC;
  }

  void traceMethodHandle(
      DexMethodHandle methodHandle, MethodHandleUse use, ProgramMethod currentMethod) {
    // If a method handle is not an argument to a lambda metafactory it could flow to a
    // MethodHandle.invokeExact invocation. For that to work, the receiver type cannot have
    // changed and therefore we cannot perform member rebinding. For these handles, we maintain
    // the receiver for the method handle. Therefore, we have to make sure that the receiver
    // stays in the output (and is not class merged). To ensure that we treat the receiver
    // as instantiated.
    if (methodHandle.isMethodHandle() && use != MethodHandleUse.ARGUMENT_TO_LAMBDA_METAFACTORY) {
      DexType type = methodHandle.asMethod().holder;
      DexProgramClass clazz = getProgramClassOrNull(type, currentMethod);
      if (clazz != null) {
        KeepReason reason = KeepReason.methodHandleReferencedIn(currentMethod);
        if (clazz.isAnnotation()) {
          markTypeAsLive(clazz, graphReporter.registerClass(clazz, reason));
        } else if (clazz.isInterface()) {
          markInterfaceAsInstantiated(clazz, graphReporter.registerInterface(clazz, reason));
        } else {
          workList.enqueueMarkInstantiatedAction(
              clazz, null, InstantiationReason.REFERENCED_IN_METHOD_HANDLE, reason);
        }
      }
    }
  }

  void traceTypeReference(DexType type, ProgramMethod currentMethod) {
    markTypeAsLive(type, currentMethod);
  }

  void traceInstanceOf(DexType type, ProgramMethod currentMethod) {
    instanceOfAnalyses.forEach(analysis -> analysis.traceInstanceOf(type, currentMethod));
    traceTypeReference(type, currentMethod);
  }

  void traceExceptionGuard(DexType guard, ProgramMethod currentMethod) {
    exceptionGuardAnalyses.forEach(analysis -> analysis.traceExceptionGuard(guard, currentMethod));
    traceTypeReference(guard, currentMethod);
  }

  void traceInvokeDirect(DexMethod invokedMethod, ProgramMethod context) {
    boolean skipTracing =
        registerDeferredActionForDeadProtoBuilder(
            invokedMethod.holder,
            context,
            () -> workList.enqueueTraceInvokeDirectAction(invokedMethod, context));
    if (skipTracing) {
      addDeadProtoTypeCandidate(invokedMethod.holder);
      return;
    }

    traceInvokeDirect(invokedMethod, context, KeepReason.invokedFrom(context));
  }

  /** Returns true if a deferred action was registered. */
  private boolean registerDeferredActionForDeadProtoBuilder(
      DexType type, ProgramMethod currentMethod, Action action) {
    DexProgramClass clazz = getProgramClassOrNull(type, currentMethod);
    if (clazz != null) {
      return appView.withGeneratedMessageLiteBuilderShrinker(
          shrinker ->
              shrinker.deferDeadProtoBuilders(
                  clazz, currentMethod, () -> liveTypes.registerDeferredAction(clazz, action)),
          false);
    }
    return false;
  }

  void traceInvokeDirectFromLambda(DexMethod invokedMethod, ProgramMethod context) {
    traceInvokeDirect(invokedMethod, context, KeepReason.invokedFromLambdaCreatedIn(context));
  }

  private void traceInvokeDirect(
      DexMethod invokedMethod, ProgramMethod context, KeepReason reason) {
    if (!registerMethodWithTargetAndContext(
        methodAccessInfoCollection::registerInvokeDirectInContext, invokedMethod, context)) {
      return;
    }
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Register invokeDirect `%s`.", invokedMethod);
    }
    handleInvokeOfDirectTarget(invokedMethod, context, reason);
    invokeAnalyses.forEach(analysis -> analysis.traceInvokeDirect(invokedMethod, context));
  }

  void traceInvokeInterface(DexMethod invokedMethod, ProgramMethod context) {
    traceInvokeInterface(invokedMethod, context, KeepReason.invokedFrom(context));
  }

  void traceInvokeInterfaceFromLambda(DexMethod invokedMethod, ProgramMethod context) {
    traceInvokeInterface(invokedMethod, context, KeepReason.invokedFromLambdaCreatedIn(context));
  }

  private void traceInvokeInterface(
      DexMethod method, ProgramMethod context, KeepReason keepReason) {
    if (!registerMethodWithTargetAndContext(
        methodAccessInfoCollection::registerInvokeInterfaceInContext, method, context)) {
      return;
    }
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Register invokeInterface `%s`.", method);
    }
    markVirtualMethodAsReachable(method, true, context, keepReason);
    invokeAnalyses.forEach(analysis -> analysis.traceInvokeInterface(method, context));
  }

  void traceInvokeStatic(DexMethod invokedMethod, ProgramMethod context) {
    traceInvokeStatic(invokedMethod, context, KeepReason.invokedFrom(context));
  }

  void traceInvokeStaticFromLambda(DexMethod invokedMethod, ProgramMethod context) {
    traceInvokeStatic(invokedMethod, context, KeepReason.invokedFromLambdaCreatedIn(context));
  }

  private void traceInvokeStatic(
      DexMethod invokedMethod, ProgramMethod context, KeepReason reason) {
    DexItemFactory dexItemFactory = appView.dexItemFactory();
    if (dexItemFactory.classMethods.isReflectiveClassLookup(invokedMethod)
        || dexItemFactory.atomicFieldUpdaterMethods.isFieldUpdater(invokedMethod)) {
      // Implicitly add -identifiernamestring rule for the Java reflection in use.
      identifierNameStrings.add(invokedMethod);
      // Revisit the current method to implicitly add -keep rule for items with reflective access.
      pendingReflectiveUses.add(context);
    }
    // See comment in handleJavaLangEnumValueOf.
    if (invokedMethod == dexItemFactory.enumMembers.valueOf) {
      pendingReflectiveUses.add(context);
    }
    // Handling of application services.
    if (dexItemFactory.serviceLoaderMethods.isLoadMethod(invokedMethod)) {
      pendingReflectiveUses.add(context);
    }
    if (invokedMethod == dexItemFactory.proxyMethods.newProxyInstance) {
      pendingReflectiveUses.add(context);
    }
    if (!registerMethodWithTargetAndContext(
        methodAccessInfoCollection::registerInvokeStaticInContext, invokedMethod, context)) {
      return;
    }
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Register invokeStatic `%s`.", invokedMethod);
    }
    handleInvokeOfStaticTarget(invokedMethod, context, reason);
    invokeAnalyses.forEach(analysis -> analysis.traceInvokeStatic(invokedMethod, context));
  }

  void traceInvokeSuper(DexMethod invokedMethod, ProgramMethod context) {
    // We have to revisit super invokes based on the context they are found in. The same
    // method descriptor will hit different targets, depending on the context it is used in.
    DexMethod actualTarget = getInvokeSuperTarget(invokedMethod, context);
    if (!registerMethodWithTargetAndContext(
        methodAccessInfoCollection::registerInvokeSuperInContext, invokedMethod, context)) {
      return;
    }
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Register invokeSuper `%s`.", actualTarget);
    }
    workList.enqueueMarkReachableSuperAction(invokedMethod, context);
    invokeAnalyses.forEach(analysis -> analysis.traceInvokeSuper(invokedMethod, context));
  }

  void traceInvokeVirtual(DexMethod invokedMethod, ProgramMethod context) {
    traceInvokeVirtual(invokedMethod, context, KeepReason.invokedFrom(context));
  }

  void traceInvokeVirtualFromLambda(DexMethod invokedMethod, ProgramMethod context) {
    traceInvokeVirtual(invokedMethod, context, KeepReason.invokedFromLambdaCreatedIn(context));
  }

  private void traceInvokeVirtual(
      DexMethod invokedMethod, ProgramMethod context, KeepReason reason) {
    if (invokedMethod == appView.dexItemFactory().classMethods.newInstance
        || invokedMethod == appView.dexItemFactory().constructorMethods.newInstance) {
      pendingReflectiveUses.add(context);
    } else if (appView.dexItemFactory().classMethods.isReflectiveMemberLookup(invokedMethod)) {
      // Implicitly add -identifiernamestring rule for the Java reflection in use.
      identifierNameStrings.add(invokedMethod);
      // Revisit the current method to implicitly add -keep rule for items with reflective access.
      pendingReflectiveUses.add(context);
    }
    if (!registerMethodWithTargetAndContext(
        methodAccessInfoCollection::registerInvokeVirtualInContext, invokedMethod, context)) {
      return;
    }
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Register invokeVirtual `%s`.", invokedMethod);
    }
    markVirtualMethodAsReachable(invokedMethod, false, context, reason);
    invokeAnalyses.forEach(analysis -> analysis.traceInvokeVirtual(invokedMethod, context));
  }

  void traceNewInstance(DexType type, ProgramMethod context) {
    boolean skipTracing =
        registerDeferredActionForDeadProtoBuilder(
            type, context, () -> workList.enqueueTraceNewInstanceAction(type, context));
    if (skipTracing) {
      addDeadProtoTypeCandidate(type);
      return;
    }

    traceNewInstance(
        type,
        context,
        InstantiationReason.NEW_INSTANCE_INSTRUCTION,
        KeepReason.instantiatedIn(context));
  }

  void traceNewInstanceFromLambda(DexType type, ProgramMethod context) {
    traceNewInstance(
        type, context, InstantiationReason.LAMBDA, KeepReason.invokedFromLambdaCreatedIn(context));
  }

  private void traceNewInstance(
      DexType type,
      ProgramMethod context,
      InstantiationReason instantiationReason,
      KeepReason keepReason) {
    DexProgramClass clazz = getProgramClassOrNull(type, context);
    if (clazz != null) {
      if (clazz.isAnnotation() || clazz.isInterface()) {
        markTypeAsLive(clazz, graphReporter.registerClass(clazz, keepReason));
      } else {
        workList.enqueueMarkInstantiatedAction(clazz, context, instantiationReason, keepReason);
      }
    }
  }

  void traceInstanceFieldRead(DexField field, ProgramMethod currentMethod) {
    traceInstanceFieldRead(field, currentMethod, FieldAccessMetadata.DEFAULT);
  }

  void traceInstanceFieldReadFromMethodHandle(DexField field, ProgramMethod currentMethod) {
    traceInstanceFieldRead(field, currentMethod, FieldAccessMetadata.FROM_METHOD_HANDLE);
  }

  void traceInstanceFieldReadFromRecordMethodHandle(DexField field, ProgramMethod currentMethod) {
    traceInstanceFieldRead(field, currentMethod, FieldAccessMetadata.FROM_RECORD_METHOD_HANDLE);
  }

  enum FieldAccessKind {
    INSTANCE_READ,
    INSTANCE_WRITE,
    STATIC_READ,
    STATIC_WRITE;

    boolean isRead() {
      return this == INSTANCE_READ || this == STATIC_READ;
    }

    boolean isStatic() {
      return this == STATIC_READ || this == STATIC_WRITE;
    }

    boolean isWrite() {
      return !isRead();
    }

    EnqueuerAction toEnqueuerAction(
        DexField fieldReference, ProgramMethod context, FieldAccessMetadata metadata) {
      switch (this) {
        case INSTANCE_READ:
          return new TraceInstanceFieldReadAction(fieldReference, context, metadata);
        case INSTANCE_WRITE:
          return new TraceInstanceFieldWriteAction(fieldReference, context, metadata);
        case STATIC_READ:
          return new TraceStaticFieldReadAction(fieldReference, context, metadata);
        case STATIC_WRITE:
          return new TraceStaticFieldWriteAction(fieldReference, context, metadata);
        default:
          throw new Unreachable();
      }
    }
  }

  static class FieldAccessMetadata {

    private static int DEFERRED_MASK = 1;
    private static int FROM_METHOD_HANDLE_MASK = 2;
    private static int FROM_RECORD_METHOD_HANDLE_MASK = 4;

    static FieldAccessMetadata DEFAULT = new FieldAccessMetadata(0);
    static FieldAccessMetadata FROM_METHOD_HANDLE =
        new FieldAccessMetadata(FROM_METHOD_HANDLE_MASK);
    static FieldAccessMetadata FROM_RECORD_METHOD_HANDLE =
        new FieldAccessMetadata(FROM_RECORD_METHOD_HANDLE_MASK);

    private final FieldAccessMetadata deferred;
    private final int flags;

    private FieldAccessMetadata(int flags) {
      this.flags = flags;
      this.deferred = isDeferred() ? this : new FieldAccessMetadata(flags | DEFERRED_MASK);
    }

    boolean isDeferred() {
      return (flags & DEFERRED_MASK) != 0;
    }

    boolean isFromMethodHandle() {
      return (flags & FROM_METHOD_HANDLE_MASK) != 0;
    }

    boolean isFromRecordMethodHandle() {
      return (flags & FROM_RECORD_METHOD_HANDLE_MASK) != 0;
    }

    public FieldAccessMetadata toDeferred() {
      return deferred;
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj) {
        return true;
      }
      if (obj == null || getClass() != obj.getClass()) {
        return false;
      }
      FieldAccessMetadata metadata = (FieldAccessMetadata) obj;
      return flags == metadata.flags;
    }

    @Override
    public int hashCode() {
      return flags;
    }
  }

  void traceInstanceFieldRead(
      DexField fieldReference, ProgramMethod currentMethod, FieldAccessMetadata metadata) {
    if (!metadata.isDeferred() && !registerFieldRead(fieldReference, currentMethod)) {
      return;
    }

    FieldResolutionResult resolutionResult = resolveField(fieldReference, currentMethod);
    if (deferredTracing.deferTracingOfFieldAccess(
        fieldReference, resolutionResult, currentMethod, FieldAccessKind.INSTANCE_READ, metadata)) {
      assert !metadata.isDeferred();
      return;
    }

    resolutionResult.visitFieldResolutionResults(
        singleResolutionResult -> {
          fieldAccessAnalyses.forEach(
              analysis ->
                  analysis.traceInstanceFieldRead(
                      fieldReference, singleResolutionResult, currentMethod, workList));

          ProgramField field = singleResolutionResult.getProgramField();
          if (field == null) {
            // No need to trace into the non-program code.
            return;
          }

          assert !mode.isFinalTreeShaking() || !field.getDefinition().getOptimizationInfo().isDead()
              : "Unexpected reference in `"
                  + currentMethod.toSourceString()
                  + "` to field marked dead: "
                  + field.getReference().toSourceString();

          if (metadata.isFromMethodHandle()) {
            fieldAccessInfoCollection.get(field.getReference()).setReadFromMethodHandle();
          } else if (metadata.isFromRecordMethodHandle()) {
            fieldAccessInfoCollection.get(field.getReference()).setReadFromRecordInvokeDynamic();
          }

          if (Log.ENABLED) {
            Log.verbose(getClass(), "Register Iget `%s`.", fieldReference);
          }

          if (field.getReference() != fieldReference) {
            // Mark the initial resolution holder as live.
            markTypeAsLive(singleResolutionResult.getInitialResolutionHolder(), currentMethod);
          }

          workList.enqueueMarkFieldAsReachableAction(
              field, currentMethod, KeepReason.fieldReferencedIn(currentMethod));
        },
        failedResolution -> {
          // Must trace the types from the field reference even if it does not exist.
          traceFieldReference(fieldReference, failedResolution, currentMethod);
          noClassMerging.add(fieldReference.getHolderType());
        });
  }

  void traceInstanceFieldWrite(DexField field, ProgramMethod currentMethod) {
    traceInstanceFieldWrite(field, currentMethod, FieldAccessMetadata.DEFAULT);
  }

  void traceInstanceFieldWriteFromMethodHandle(DexField field, ProgramMethod currentMethod) {
    traceInstanceFieldWrite(field, currentMethod, FieldAccessMetadata.FROM_METHOD_HANDLE);
  }

  void traceInstanceFieldWrite(
      DexField fieldReference, ProgramMethod currentMethod, FieldAccessMetadata metadata) {
    if (!metadata.isDeferred() && !registerFieldWrite(fieldReference, currentMethod)) {
      return;
    }

    FieldResolutionResult resolutionResult = resolveField(fieldReference, currentMethod);
    if (deferredTracing.deferTracingOfFieldAccess(
        fieldReference,
        resolutionResult,
        currentMethod,
        FieldAccessKind.INSTANCE_WRITE,
        metadata)) {
      assert !metadata.isDeferred();
      return;
    }

    resolutionResult.visitFieldResolutionResults(
        singleResolutionResult -> {
          fieldAccessAnalyses.forEach(
              analysis ->
                  analysis.traceInstanceFieldWrite(
                      fieldReference, singleResolutionResult, currentMethod, workList));

          ProgramField field = singleResolutionResult.getProgramField();
          if (field == null) {
            // No need to trace into the non-program code.
            return;
          }

          assert !mode.isFinalTreeShaking() || !field.getDefinition().getOptimizationInfo().isDead()
              : "Unexpected reference in `"
                  + currentMethod.toSourceString()
                  + "` to field marked dead: "
                  + field.getReference().toSourceString();

          if (metadata.isFromMethodHandle()) {
            fieldAccessInfoCollection.get(field.getReference()).setWrittenFromMethodHandle();
          }

          if (Log.ENABLED) {
            Log.verbose(getClass(), "Register Iput `%s`.", fieldReference);
          }

          if (field.getReference() != fieldReference) {
            // Mark the initial resolution holder as live.
            markTypeAsLive(singleResolutionResult.getInitialResolutionHolder(), currentMethod);
          }

          KeepReason reason = KeepReason.fieldReferencedIn(currentMethod);
          workList.enqueueMarkFieldAsReachableAction(field, currentMethod, reason);
        },
        failedResolution -> {
          // Must trace the types from the field reference even if it does not exist.
          traceFieldReference(fieldReference, failedResolution, currentMethod);
          noClassMerging.add(fieldReference.getHolderType());
        });
  }

  void traceStaticFieldRead(DexField field, ProgramMethod currentMethod) {
    traceStaticFieldRead(field, currentMethod, FieldAccessMetadata.DEFAULT);
  }

  void traceStaticFieldReadFromMethodHandle(DexField field, ProgramMethod currentMethod) {
    traceStaticFieldRead(field, currentMethod, FieldAccessMetadata.FROM_METHOD_HANDLE);
  }

  void traceStaticFieldRead(
      DexField fieldReference, ProgramMethod currentMethod, FieldAccessMetadata metadata) {
    if (!metadata.isDeferred() && !registerFieldRead(fieldReference, currentMethod)) {
      return;
    }

    FieldResolutionResult resolutionResult = resolveField(fieldReference, currentMethod);

    if (appView.options().protoShrinking().enableGeneratedExtensionRegistryShrinking) {
      // If it is a dead proto extension field, don't trace onwards.
      boolean skipTracing =
          appView.withGeneratedExtensionRegistryShrinker(
              shrinker ->
                  shrinker.isDeadProtoExtensionField(
                      resolutionResult, fieldAccessInfoCollection, keepInfo),
              false);
      if (skipTracing) {
        addDeadProtoTypeCandidate(resolutionResult.getSingleProgramField().getHolder());
        return;
      }
    }

    if (deferredTracing.deferTracingOfFieldAccess(
        fieldReference, resolutionResult, currentMethod, FieldAccessKind.STATIC_READ, metadata)) {
      assert !metadata.isDeferred();
      return;
    }

    resolutionResult.visitFieldResolutionResults(
        singleResolutionResult -> {
          fieldAccessAnalyses.forEach(
              analysis ->
                  analysis.traceStaticFieldRead(
                      fieldReference, singleResolutionResult, currentMethod, workList));

          ProgramField field = singleResolutionResult.getProgramField();
          if (field == null) {
            // No need to trace into the non-program code.
            return;
          }

          assert !mode.isFinalTreeShaking() || !field.getDefinition().getOptimizationInfo().isDead()
              : "Unexpected reference in `"
                  + currentMethod.toSourceString()
                  + "` to field marked dead: "
                  + field.getReference().toSourceString();

          if (metadata.isFromMethodHandle()) {
            fieldAccessInfoCollection.get(field.getReference()).setReadFromMethodHandle();
          }

          if (Log.ENABLED) {
            Log.verbose(getClass(), "Register Sget `%s`.", fieldReference);
          }

          if (field.getReference() != fieldReference) {
            // Mark the initial resolution holder as live. Note that this should only be done if
            // the field
            // is not a dead proto field (in which case we bail-out above).
            markTypeAsLive(singleResolutionResult.getInitialResolutionHolder(), currentMethod);
          }

          markFieldAsLive(field, currentMethod);
        },
        failedResolution -> {
          // Must trace the types from the field reference even if it does not exist.
          traceFieldReference(fieldReference, failedResolution, currentMethod);
          noClassMerging.add(fieldReference.getHolderType());
          // Record field reference for generated extension registry shrinking.
          appView.withGeneratedExtensionRegistryShrinker(
              shrinker ->
                  shrinker.handleFailedOrUnknownFieldResolution(
                      fieldReference, currentMethod, mode));
        });
  }

  void traceStaticFieldWrite(DexField field, ProgramMethod currentMethod) {
    traceStaticFieldWrite(field, currentMethod, FieldAccessMetadata.DEFAULT);
  }

  void traceStaticFieldWriteFromMethodHandle(DexField field, ProgramMethod currentMethod) {
    traceStaticFieldWrite(field, currentMethod, FieldAccessMetadata.FROM_METHOD_HANDLE);
  }

  void traceStaticFieldWrite(
      DexField fieldReference, ProgramMethod currentMethod, FieldAccessMetadata metadata) {
    if (!metadata.isDeferred() && !registerFieldWrite(fieldReference, currentMethod)) {
      return;
    }

    FieldResolutionResult resolutionResult = resolveField(fieldReference, currentMethod);

    if (appView.options().protoShrinking().enableGeneratedExtensionRegistryShrinking) {
      // If it is a dead proto extension field, don't trace onwards.
      boolean skipTracing =
          appView.withGeneratedExtensionRegistryShrinker(
              shrinker ->
                  shrinker.isDeadProtoExtensionField(
                      resolutionResult, fieldAccessInfoCollection, keepInfo),
              false);
      if (skipTracing) {
        addDeadProtoTypeCandidate(resolutionResult.getSingleProgramField().getHolder());
        return;
      }
    }

    if (deferredTracing.deferTracingOfFieldAccess(
        fieldReference, resolutionResult, currentMethod, FieldAccessKind.STATIC_WRITE, metadata)) {
      assert !metadata.isDeferred();
      return;
    }

    resolutionResult.visitFieldResolutionResults(
        singleResolutionResult -> {
          fieldAccessAnalyses.forEach(
              analysis ->
                  analysis.traceStaticFieldWrite(
                      fieldReference, singleResolutionResult, currentMethod, workList));

          ProgramField field = singleResolutionResult.getProgramField();
          if (field == null) {
            // No need to trace into the non-program code.
            return;
          }

          assert !mode.isFinalTreeShaking() || !field.getDefinition().getOptimizationInfo().isDead()
              : "Unexpected reference in `"
                  + currentMethod.toSourceString()
                  + "` to field marked dead: "
                  + field.getReference().toSourceString();

          if (metadata.isFromMethodHandle()) {
            fieldAccessInfoCollection.get(field.getReference()).setWrittenFromMethodHandle();
          }

          if (Log.ENABLED) {
            Log.verbose(getClass(), "Register Sput `%s`.", fieldReference);
          }

          if (field.getReference() != fieldReference) {
            // Mark the initial resolution holder as live. Note that this should only be done if
            // the field
            // is not a dead proto field (in which case we bail-out above).
            markTypeAsLive(singleResolutionResult.getInitialResolutionHolder(), currentMethod);
          }

          markFieldAsLive(field, currentMethod);
        },
        failedResolution -> {
          // Must trace the types from the field reference even if it does not exist.
          traceFieldReference(fieldReference, failedResolution, currentMethod);
          noClassMerging.add(fieldReference.getHolderType());
        });
  }

  private DexMethod getInvokeSuperTarget(DexMethod method, ProgramMethod currentMethod) {
    DexClass methodHolderClass = appView.definitionFor(method.holder);
    if (methodHolderClass != null && methodHolderClass.isInterface()) {
      return method;
    }
    DexProgramClass holderClass = currentMethod.getHolder();
    if (holderClass.superType == null || holderClass.isInterface()) {
      // We do not know better or this call is made from an interface.
      return method;
    }
    // Return the invoked method on the supertype.
    return appView.dexItemFactory().createMethod(holderClass.superType, method.proto, method.name);
  }

  //
  // Actual actions performed.
  //

  private boolean verifyMethodIsTargeted(ProgramMethod method) {
    DexEncodedMethod definition = method.getDefinition();
    assert !definition.isClassInitializer() : "Class initializers are never targeted";
    assert targetedMethods.contains(definition);
    return true;
  }

  private boolean verifyTypeIsLive(DexProgramClass clazz) {
    assert liveTypes.contains(clazz);
    return true;
  }

  private void markTypeAsLive(DexType type, ProgramDefinition context) {
    if (type.isArrayType()) {
      markTypeAsLive(type.toBaseType(appView.dexItemFactory()), context);
      return;
    }
    if (!type.isClassType()) {
      // Ignore primitive types.
      return;
    }
    DexProgramClass clazz = getProgramClassOrNull(type, context);
    if (clazz == null) {
      return;
    }
    markTypeAsLive(clazz, context);
  }

  private void markTypeAsLive(DexType type, ProgramDefinition context, KeepReason reason) {
    if (type.isArrayType()) {
      markTypeAsLive(type.toBaseType(appView.dexItemFactory()), context, reason);
      return;
    }
    if (!type.isClassType()) {
      // Ignore primitive types and void.
      return;
    }
    DexProgramClass clazz = getProgramClassOrNull(type, context);
    if (clazz == null) {
      return;
    }
    markTypeAsLive(clazz, reason);
  }

  private void markTypeAsLive(DexClass clazz, ProgramDefinition context) {
    if (clazz.isProgramClass()) {
      DexProgramClass programClass = clazz.asProgramClass();
      markTypeAsLive(programClass, graphReporter.reportClassReferencedFrom(programClass, context));
    }
  }

  private void markTypeAsLive(DexProgramClass clazz, ProgramDefinition context) {
    markTypeAsLive(clazz, graphReporter.reportClassReferencedFrom(clazz, context));
  }

  void markTypeAsLive(DexProgramClass clazz, KeepReason reason) {
    assert clazz != null;
    markTypeAsLive(
        clazz,
        scopedMethodsForLiveTypes.computeIfAbsent(
            clazz.getType(), ignore -> new ScopedDexMethodSet()),
        graphReporter.registerClass(clazz, reason));
  }

  private void markTypeAsLive(
      DexProgramClass clazz, ScopedDexMethodSet seen, KeepReasonWitness witness) {
    if (!liveTypes.add(clazz, witness)) {
      return;
    }

    assert !mode.isFinalMainDexTracing()
            || !options.testing.checkForNotExpandingMainDexTracingResult
            || appView.appInfo().getMainDexInfo().isTracedRoot(clazz, appView.getSyntheticItems())
        : "Class " + clazz.toSourceString() + " was not a main dex root in the first round";

    assert !appView.unboxedEnums().isUnboxedEnum(clazz);

    // Mark types in inner-class attributes referenced.
    {
      BiConsumer<DexType, ProgramDerivedContext> missingClassConsumer =
          options.reportMissingClassesInInnerClassAttributes
              ? this::reportMissingClass
              : this::ignoreMissingClass;
      for (InnerClassAttribute innerClassAttribute : clazz.getInnerClasses()) {
        recordTypeReference(
            innerClassAttribute.getInner(),
            clazz,
            this::recordNonProgramClass,
            missingClassConsumer);
        recordTypeReference(
            innerClassAttribute.getOuter(),
            clazz,
            this::recordNonProgramClass,
            missingClassConsumer);
      }
    }

    // Mark types in nest attributes referenced.
    if (clazz.isNestHost()) {
      for (NestMemberClassAttribute nestMemberClassAttribute :
          clazz.getNestMembersClassAttributes()) {
        recordTypeReference(nestMemberClassAttribute.getNestMember(), clazz);
      }
    } else {
      recordTypeReference(clazz.getNestHost(), clazz);
    }

    EnclosingMethodAttribute enclosingMethodAttribute = clazz.getEnclosingMethodAttribute();
    if (enclosingMethodAttribute != null) {
      DexMethod enclosingMethod = enclosingMethodAttribute.getEnclosingMethod();
      BiConsumer<DexType, ProgramDerivedContext> missingClassConsumer =
          options.reportMissingClassesInEnclosingMethodAttribute
              ? this::reportMissingClass
              : this::ignoreMissingClass;
      if (enclosingMethod != null) {
        recordMethodReference(
            enclosingMethod, clazz, this::recordNonProgramClass, missingClassConsumer);
      } else {
        DexType enclosingClass = enclosingMethodAttribute.getEnclosingClass();
        recordTypeReference(
            enclosingClass, clazz, this::recordNonProgramClass, missingClassConsumer);
      }
    }

    if (Log.ENABLED) {
      Log.verbose(getClass(), "Type `%s` has become live.", clazz.type);
    }

    KeepReason reason = KeepReason.reachableFromLiveType(clazz.type);

    for (DexType iface : clazz.getInterfaces()) {
      markInterfaceTypeAsLiveViaInheritanceClause(iface, clazz);
    }

    if (clazz.superType != null) {
      ScopedDexMethodSet seenForSuper =
          scopedMethodsForLiveTypes.computeIfAbsent(
              clazz.superType, ignore -> new ScopedDexMethodSet());
      seen.setParent(seenForSuper);
      markTypeAsLive(clazz.superType, clazz);
    }

    // Warn if the class extends an interface or implements a class
    warnIfClassExtendsInterfaceOrImplementsClass(clazz);

    // If this is an interface that has just become live, then report previously seen but unreported
    // implemented-by edges.
    transitionUnusedInterfaceToLive(clazz);

    // We cannot remove virtual methods defined earlier in the type hierarchy if it is widening
    // access and is defined in an interface:
    //
    // public interface I {
    //   void clone();
    // }
    //
    // class Model implements I {
    //   public void clone() { ... } <-- this cannot be removed
    // }
    //
    // Any class loading of Model with Model.clone() removed will result in an illegal access
    // error because their exists an existing implementation (here it is Object.clone()). This is
    // only a problem in the DEX VM. We have to make this check no matter the output because
    // CF libraries can be used by Android apps. See b/136698023 for more information.
    ensureMethodsContinueToWidenAccess(clazz, seen, reason);

    if (clazz.isSerializable(appView)) {
      enqueueFirstNonSerializableClassInitializer(clazz, reason);
    }

    // Update keep info.
    applyMinimumKeepInfo(clazz);
    applyMinimumKeepInfoDependentOn(new LiveClassEnqueuerEvent(clazz));
    if (hasAlternativeLibraryDefinition(clazz)) {
      getKeepInfo().keepClass(clazz);
    }

    processAnnotations(clazz);

    // If this type has deferred annotations, we have to process those now, too.
    if (clazz.isAnnotation()) {
      processDeferredAnnotations(clazz, deferredAnnotations, AnnotatedKind::from);
      processDeferredAnnotations(
          clazz, deferredParameterAnnotations, annotatedItem -> AnnotatedKind.PARAMETER);
    }

    compatEnqueueHolderIfDependentNonStaticMember(
        clazz, rootSet.getDependentKeepClassCompatRule(clazz.getType()));

    analyses.forEach(analysis -> analysis.processNewlyLiveClass(clazz, workList));
  }

  private void processDeferredAnnotations(
      DexProgramClass clazz,
      Map<DexType, Map<DexAnnotation, List<ProgramDefinition>>> deferredAnnotations,
      Function<ProgramDefinition, AnnotatedKind> kindProvider) {
    Map<DexAnnotation, List<ProgramDefinition>> annotations =
        deferredAnnotations.remove(clazz.getType());
    if (annotations != null) {
      assert annotations.keySet().stream()
          .allMatch(annotation -> annotation.getAnnotationType() == clazz.getType());
      annotations.forEach(
          (annotation, annotatedItems) ->
              annotatedItems.forEach(
                  annotatedItem ->
                      processAnnotation(
                          annotatedItem, annotation, kindProvider.apply(annotatedItem))));
    }
  }

  private void ensureMethodsContinueToWidenAccess(ClassDefinition clazz) {
    assert !clazz.isProgramClass();
    ScopedDexMethodSet seen =
        scopedMethodsForLiveTypes.computeIfAbsent(
            clazz.getType(), ignore -> new ScopedDexMethodSet());
    clazz.getMethodCollection().forEachVirtualMethod(seen::addMethodIfMoreVisible);
  }

  private void ensureMethodsContinueToWidenAccess(
      DexProgramClass clazz, ScopedDexMethodSet seen, KeepReason reason) {
    clazz.forEachProgramVirtualMethodMatching(
        definition ->
            seen.addMethodIfMoreVisible(definition)
                    == AddMethodIfMoreVisibleResult.ADDED_MORE_VISIBLE
                && appView.appInfo().methodDefinedInInterfaces(definition, clazz.type),
        method -> markMethodAsTargeted(method, reason));
  }

  private void markInterfaceTypeAsLiveViaInheritanceClause(
      DexType type, DexProgramClass implementer) {
    DexProgramClass clazz = getProgramClassOrNull(type, implementer);
    if (clazz == null) {
      return;
    }

    if (!appView.options().enableUnusedInterfaceRemoval
        || rootSet.noUnusedInterfaceRemoval.contains(type)
        || mode.isMainDexTracing()) {
      markTypeAsLive(clazz, implementer);
      return;
    }

    if (liveTypes.contains(clazz)) {
      // The interface is already live, so make sure to report this implements-edge.
      graphReporter.reportClassReferencedFrom(clazz, implementer);
      return;
    }

    // No need to mark the type as live. If an interface type is only reachable via the
    // inheritance clause of another type it can simply be removed from the inheritance clause.
    // The interface is needed if it has a live default interface method or field, though.
    // Therefore, we record that this implemented-by edge has not been reported, such that we
    // can report it in the future if one its members becomes live.
    WorkList<DexProgramClass> worklist = WorkList.newIdentityWorkList();
    worklist.addIfNotSeen(clazz);
    while (worklist.hasNext()) {
      DexProgramClass current = worklist.next();
      if (liveTypes.contains(current)) {
        continue;
      }
      Set<DexProgramClass> implementors =
          unusedInterfaceTypes.computeIfAbsent(current, ignore -> Sets.newIdentityHashSet());
      if (implementors.add(implementer)) {
        for (DexType iface : current.getInterfaces()) {
          DexProgramClass definition = getProgramClassOrNull(iface, current);
          if (definition != null) {
            if (definition.isPublic()
                || implementer.getType().isSamePackage(definition.getType())) {
              worklist.addIfNotSeen(definition);
            } else {
              markTypeAsLive(current, implementer);
            }
          }
        }
      }
    }
  }

  private void enqueueHolderWithDependentInstanceConstructor(
      ProgramMethod instanceInitializer, Set<ProguardKeepRuleBase> reasons) {
    DexProgramClass holder = instanceInitializer.getHolder();
    enqueueKeepRuleInstantiatedType(holder, reasons, instanceInitializer.getDefinition());
  }

  private void processAnnotations(ProgramDefinition annotatedItem) {
    processAnnotations(
        annotatedItem,
        annotatedItem.getDefinition().annotations(),
        AnnotatedKind.from(annotatedItem));
  }

  private void processAnnotations(
      ProgramDefinition annotatedItem, DexAnnotationSet annotations, AnnotatedKind kind) {
    processAnnotations(annotatedItem, annotations.annotations, kind);
  }

  private void processAnnotations(
      ProgramDefinition annotatedItem, DexAnnotation[] annotations, AnnotatedKind kind) {
    for (DexAnnotation annotation : annotations) {
      processAnnotation(annotatedItem, annotation, kind);
    }
  }

  void processAnnotation(
      ProgramDefinition annotatedItem, DexAnnotation annotation, AnnotatedKind kind) {
    DexType type = annotation.getAnnotationType();
    DexClass clazz = definitionFor(type, annotatedItem);
    boolean annotationTypeIsLibraryClass = clazz == null || clazz.isNotProgramClass();
    boolean isLive = annotationTypeIsLibraryClass || liveTypes.contains(clazz.asProgramClass());
    if (!shouldKeepAnnotation(annotatedItem, annotation, kind, isLive)) {
      // Remember this annotation for later.
      if (!annotationTypeIsLibraryClass) {
        Map<DexType, Map<DexAnnotation, List<ProgramDefinition>>> deferredAnnotations =
            kind.isParameter() ? deferredParameterAnnotations : this.deferredAnnotations;
        Map<DexAnnotation, List<ProgramDefinition>> deferredAnnotationsForAnnotationType =
            deferredAnnotations.computeIfAbsent(type, ignore -> new IdentityHashMap<>());
        deferredAnnotationsForAnnotationType
            .computeIfAbsent(annotation, ignore -> new ArrayList<>())
            .add(annotatedItem);
      }
      return;
    }

    // Report that the annotation is retained due to the annotated item.
    graphReporter.registerAnnotation(annotation, annotatedItem);

    // Report that the items referenced from inside the annotation are retained due to the
    // annotation.
    AnnotationReferenceMarker referenceMarker =
        new AnnotationReferenceMarker(annotation, annotatedItem);
    annotation.annotation.collectIndexedItems(referenceMarker);
  }

  private boolean shouldKeepAnnotation(
      ProgramDefinition annotatedItem,
      DexAnnotation annotation,
      AnnotatedKind annotatedKind,
      boolean isLive) {
    if (annotationRemoverBuilder != null
        && annotationRemoverBuilder.isRetainedForFinalTreeShaking(annotation)) {
      assert mode.isInitialTreeShaking();
      return true;
    }
    return AnnotationRemover.shouldKeepAnnotation(
        appView, annotatedItem, annotation, isLive, annotatedKind);
  }

  private FieldResolutionResult resolveField(DexField field, ProgramDefinition context) {
    // Record the references in case they are not program types.
    FieldResolutionResult fieldResolutionResult = appInfo.resolveField(field);
    fieldResolutionResult.visitFieldResolutionResults(
        resolutionResult -> {
          recordFieldReference(
              field, resolutionResult.getResolutionPair().asProgramDerivedContext(context));
        },
        failedResolution -> {
          failedFieldResolutionTargets.add(field);
          recordFieldReference(field, context);
        });
    return fieldResolutionResult;
  }

  private SingleResolutionResult<?> resolveMethod(
      DexMethod method, ProgramDefinition context, KeepReason reason) {
    // Record the references in case they are not program types.
    MethodResolutionResult resolutionResult = appInfo.unsafeResolveMethodDueToDexFormat(method);
    if (resolutionResult.isFailedResolution()) {
      markFailedMethodResolutionTargets(
          method, resolutionResult.asFailedResolution(), context, reason);
      recordMethodReference(method, context, this::recordFoundClass, this::reportMissingClass);
    } else {
      recordMethodReference(method, context);
    }
    return resolutionResult.asSingleResolution();
  }

  private SingleResolutionResult<?> resolveMethod(
      DexMethod method, ProgramDefinition context, KeepReason reason, boolean interfaceInvoke) {
    // Record the references in case they are not program types.
    MethodResolutionResult resolutionResult = appInfo.resolveMethod(method, interfaceInvoke);
    if (resolutionResult.isSingleResolution()) {
      recordMethodReference(
          method, resolutionResult.getResolutionPair().asProgramDerivedContext(context));
    } else {
      assert resolutionResult.isFailedResolution();
      markFailedMethodResolutionTargets(
          method, resolutionResult.asFailedResolution(), context, reason);
      recordMethodReference(method, context, this::recordFoundClass, this::reportMissingClass);
    }
    return resolutionResult.asSingleResolution();
  }

  private void handleInvokeOfStaticTarget(
      DexMethod reference, ProgramDefinition context, KeepReason reason) {
    SingleResolutionResult<?> resolution = resolveMethod(reference, context, reason);
    if (resolution == null || resolution.getResolvedHolder().isNotProgramClass()) {
      return;
    }
    DexProgramClass clazz = resolution.getResolvedHolder().asProgramClass();
    DexEncodedMethod encodedMethod = resolution.getResolvedMethod();

    // We have to mark the resolved method as targeted even if it cannot actually be invoked
    // to make sure the invocation will keep failing in the appropriate way.
    ProgramMethod method = new ProgramMethod(clazz, encodedMethod);
    markMethodAsTargeted(method, reason);

    // Only mark methods for which invocation will succeed at runtime live.
    if (encodedMethod.isStatic()) {
      markDirectAndIndirectClassInitializersAsLive(clazz);
      markDirectStaticOrConstructorMethodAsLive(method, reason);
    }
  }

  void markDirectAndIndirectClassInitializersAsLive(DexProgramClass clazz) {
    if (clazz.isInterface()) {
      // Accessing a static field or method on an interface does not trigger the class initializer
      // of any parent interfaces.
      markInterfaceInitializedDirectly(clazz);
      return;
    }

    WorkList<DexProgramClass> worklist = WorkList.newIdentityWorkList(clazz);
    while (worklist.hasNext()) {
      DexProgramClass current = worklist.next();
      if (current.isInterface()) {
        if (!markInterfaceInitializedIndirectly(current)) {
          continue;
        }
      } else {
        if (!markDirectClassInitializerAsLive(current)) {
          continue;
        }
      }

      // Mark all class initializers in all super types as live.
      for (DexType superType : current.allImmediateSupertypes()) {
        DexProgramClass superClass = getProgramClassOrNull(superType, current);
        if (superClass != null) {
          worklist.addIfNotSeen(superClass);
        }
      }
    }
  }

  /** Returns true if the class became initialized for the first time. */
  private boolean markDirectClassInitializerAsLive(DexProgramClass clazz) {
    ProgramMethod clinit = clazz.getProgramClassInitializer();
    KeepReasonWitness witness = graphReporter.reportReachableClassInitializer(clazz, clinit);
    if (!initializedClasses.add(clazz, witness)) {
      return false;
    }
    if (clinit != null && clinit.getDefinition().getOptimizationInfo().mayHaveSideEffects()) {
      markDirectStaticOrConstructorMethodAsLive(clinit, witness);
    }
    return true;
  }

  /**
   * Marks the interface as initialized directly and promotes the interface initializer to being
   * live if it isn't already.
   */
  private void markInterfaceInitializedDirectly(DexProgramClass clazz) {
    ProgramMethod clinit = clazz.getProgramClassInitializer();
    // Mark the interface as initialized directly.
    KeepReasonWitness witness = graphReporter.reportReachableClassInitializer(clazz, clinit);
    if (!directlyInitializedInterfaces.add(clazz, witness)) {
      return;
    }
    // Promote the interface initializer to being live if it isn't already.
    if (clinit == null || !clinit.getDefinition().getOptimizationInfo().mayHaveSideEffects()) {
      return;
    }
    if (indirectlyInitializedInterfaces.contains(clazz)
        && clazz.getMethodCollection().hasVirtualMethods(DexEncodedMethod::isDefaultMethod)) {
      assert liveMethods.contains(clinit);
      return;
    }
    markDirectStaticOrConstructorMethodAsLive(clinit, witness);
  }

  /**
   * Marks the interface as initialized indirectly and promotes the interface initializer to being
   * live if the interface has a default interface method and is not already live.
   *
   * @return true if the interface became initialized indirectly for the first time.
   */
  private boolean markInterfaceInitializedIndirectly(DexProgramClass clazz) {
    ProgramMethod clinit = clazz.getProgramClassInitializer();
    // Mark the interface as initialized indirectly.
    KeepReasonWitness witness = graphReporter.reportReachableClassInitializer(clazz, clinit);
    if (!indirectlyInitializedInterfaces.add(clazz, witness)) {
      return false;
    }
    // Promote the interface initializer to being live if it has a default interface method and
    // isn't already live.
    if (clinit == null
        || !clinit.getDefinition().getOptimizationInfo().mayHaveSideEffects()
        || !clazz.getMethodCollection().hasVirtualMethods(DexEncodedMethod::isDefaultMethod)) {
      return true;
    }
    if (directlyInitializedInterfaces.contains(clazz)) {
      assert liveMethods.contains(clinit);
      return true;
    }
    markDirectStaticOrConstructorMethodAsLive(clinit, witness);
    return true;
  }

  // Package protected due to entry point from worklist.
  void markNonStaticDirectMethodAsReachable(
      DexMethod method, ProgramDefinition context, KeepReason reason) {
    handleInvokeOfDirectTarget(method, context, reason);
  }

  private void handleInvokeOfDirectTarget(
      DexMethod reference, ProgramDefinition context, KeepReason reason) {
    DexType holder = reference.holder;
    DexProgramClass clazz = getProgramClassOrNull(holder, context);
    if (clazz == null) {
      recordMethodReference(reference, context);
      return;
    }
    // TODO(zerny): Is it ok that we lookup in both the direct and virtual pool here?
    DexEncodedMethod encodedMethod = clazz.lookupMethod(reference);
    if (encodedMethod == null) {
      failedMethodResolutionTargets.add(reference);
      return;
    }

    ProgramMethod method = new ProgramMethod(clazz, encodedMethod);

    // We have to mark the resolved method as targeted even if it cannot actually be invoked
    // to make sure the invocation will keep failing in the appropriate way.
    markMethodAsTargeted(method, reason);

    // Only mark methods for which invocation will succeed at runtime live.
    if (encodedMethod.isStatic()) {
      return;
    }

    markDirectStaticOrConstructorMethodAsLive(method, reason);

    // It is valid to have an invoke-direct instruction in a default interface method that
    // targets another default method in the same interface (see testInvokeSpecialToDefault-
    // Method). In a class, that would lead to a verification error.
    if (encodedMethod.isNonPrivateVirtualMethod()
        && virtualMethodsTargetedByInvokeDirect.add(encodedMethod.getReference())) {
      workList.enqueueMarkMethodLiveAction(method, context, reason);
    }
  }

  private void ensureFromLibraryOrThrow(DexType type, DexLibraryClass context) {
    if (mode.isMainDexTracing()) {
      // b/72312389: android.jar contains parts of JUnit and most developers include JUnit in
      // their programs. This leads to library classes extending program classes. When tracing
      // main dex lists we allow this.
      return;
    }
    DexProgramClass clazz = asProgramClassOrNull(appInfo().definitionFor(type));
    if (clazz == null) {
      return;
    }
    DexClass alternativeResolutionResult =
        appInfo()
            .contextIndependentDefinitionForWithResolutionResult(type)
            .toAlternativeClassWithProgramOverLibrary();
    if (alternativeResolutionResult != null && alternativeResolutionResult.isLibraryClass()) {
      // We are in a situation where a library class inherits from a library class, which has a
      // program class duplicated version for low API levels.
      recordNonProgramClass(alternativeResolutionResult, clazz);
      return;
    }
    if (forceProguardCompatibility) {
      // To ensure that the program works correctly we have to pin all super types and members
      // in the tree.
      KeepReason keepReason = KeepReason.reachableFromLiveType(context.type);
      keepClassAndAllMembers(clazz, keepReason);
      appInfo.forEachSuperType(
          clazz,
          (superType, subclass, ignored) -> {
            DexProgramClass superClass = asProgramClassOrNull(appInfo().definitionFor(superType));
            if (superClass != null) {
              keepClassAndAllMembers(superClass, keepReason);
            }
          });
    }
    if (appView.getDontWarnConfiguration().matches(context)) {
      // Ignore.
      return;
    }
    // Only report an error during the first round of treeshaking.
    if (mode.isInitialTreeShaking()) {
      Diagnostic message =
          new StringDiagnostic(
              "Library class "
                  + context.type.toSourceString()
                  + (clazz.isInterface() ? " implements " : " extends ")
                  + "program class "
                  + type.toSourceString());
      if (forceProguardCompatibility) {
        options.reporter.warning(message);
      } else {
        options.reporter.error(message);
      }
    }
  }

  private void shouldNotBeMinified(ProgramDefinition definition) {
    if (options.isMinificationEnabled()) {
      rootSet.shouldNotBeMinified(definition);
    }
  }

  private void keepClassAndAllMembers(DexProgramClass clazz, KeepReason keepReason) {
    KeepReasonWitness keepReasonWitness = graphReporter.registerClass(clazz, keepReason);
    markClassAsInstantiatedWithCompatRule(clazz.asProgramClass(), () -> keepReasonWitness);
    keepInfo.keepClass(clazz);
    shouldNotBeMinified(clazz);
    clazz.forEachProgramField(
        field -> {
          keepInfo.keepField(field);
          shouldNotBeMinified(field);
          markFieldAsKept(field, keepReasonWitness);
        });
    clazz.forEachProgramMethod(
        method -> {
          keepInfo.keepMethod(method);
          shouldNotBeMinified(method);
          markMethodAsKept(method, keepReasonWitness);
        });
  }

  private void recordFoundClass(DexClass clazz, ProgramDerivedContext context) {
    if (clazz.isProgramClass()) {
      if (context.isProgramContext()) {
        markTypeAsLive(clazz, context.getContext().asProgramDefinition());
      }
    } else {
      recordNonProgramClass(clazz, context);
    }
  }

  private void recordNonProgramClass(DexClass clazz, ProgramDerivedContext context) {
    if (!clazz.isProgramClass()) {
      addLiveNonProgramType(
          clazz.asClasspathOrLibraryClass(),
          true,
          (missingType, derivedContext) ->
              reportMissingClass(missingType, derivedContext.asProgramDerivedContext(context)));
    }
  }

  private void recordNonProgramClassWithNoMissingReporting(
      DexClass clazz, ProgramDerivedContext context) {
    if (!clazz.isProgramClass()) {
      addLiveNonProgramType(
          clazz.asClasspathOrLibraryClass(), true, this::ignoreMissingClasspathOrLibraryClass);
    }
  }

  private void ignoreMissingClass(DexType clazz) {
    missingClassesBuilder.ignoreNewMissingClass(clazz);
  }

  private void ignoreMissingClass(DexType clazz, ProgramDerivedContext context) {
    ignoreMissingClass(clazz);
  }

  private void ignoreMissingClasspathOrLibraryClass(DexType clazz) {
    ignoreMissingClass(clazz);
  }

  private void ignoreMissingClasspathOrLibraryClass(
      DexType clazz, ClasspathOrLibraryDefinition context) {
    ignoreMissingClasspathOrLibraryClass(clazz);
  }

  private void reportMissingClass(DexType clazz, ProgramDerivedContext context) {
    assert !mode.isFinalTreeShaking()
            || missingClassesBuilder.wasAlreadyMissing(clazz)
            || appView.dexItemFactory().isPossiblyCompilerSynthesizedType(clazz)
            || initialDeadProtoTypes.contains(clazz)
            // TODO(b/157107464): See if we can clean this up.
            || (initialPrunedTypes != null && initialPrunedTypes.contains(clazz))
        : "Unexpected missing class `" + clazz.toSourceString() + "`";
    // Do not report missing classes from D8/R8 synthesized methods on non-synthetic classes (for
    // example, lambda accessibility bridges).
    // TODO(b/180376674): Clean this up. Ideally the D8/R8 synthesized methods would be synthesized
    //  using synthetic items, such that the synthetic items infrastructure would track the
    //  synthesizing contexts for these methods as well. That way, this would just work without any
    //  special handling because the mapping to the synthesizing contexts would also work for these
    //  synthetic methods.
    if (context.isProgramContext()
        && context.getContext().isMethod()
        && context.getContext().asMethod().getDefinition().isD8R8Synthesized()
        && !appView
            .getSyntheticItems()
            .isSyntheticClass(context.getContext().asProgramDefinition().getContextClass())) {
      missingClassesBuilder.ignoreNewMissingClass(clazz);
    } else {
      missingClassesBuilder.addNewMissingClass(clazz, context);
    }
  }

  /**
   * Adds the class to the set of instantiated classes and marks its fields and methods live
   * depending on the currently seen invokes and field reads.
   */
  // Package protected due to entry point from worklist.
  void processNewlyInstantiatedClass(
      DexProgramClass clazz,
      ProgramMethod context,
      InstantiationReason instantiationReason,
      KeepReason keepReason) {
    assert !clazz.isAnnotation();
    assert !clazz.isInterface();

    // Notify analyses. This is done even if `clazz` has already been marked as instantiated,
    // because each analysis may depend on seeing all the (clazz, reason) pairs. Thus, not doing so
    // could lead to nondeterminism.
    analyses.forEach(
        analysis ->
            analysis.processNewlyInstantiatedClass(clazz.asProgramClass(), context, workList));

    if (!markInstantiatedClass(clazz, context, instantiationReason, keepReason)) {
      return;
    }

    if (Log.ENABLED) {
      Log.verbose(getClass(), "Class `%s` is instantiated, processing...", clazz);
    }
    // This class becomes live, so it and all its supertypes become live types.
    markTypeAsLive(clazz, graphReporter.registerClass(clazz, keepReason));
    // Instantiation triggers class initialization.
    markDirectAndIndirectClassInitializersAsLive(clazz);
    // For all methods of the class, if we have seen a call, mark the method live.
    // We only do this for virtual calls, as the other ones will be done directly.
    transitionMethodsForInstantiatedClass(clazz);
    // For all instance fields visible from the class, mark them live if we have seen a read.
    transitionFieldsForInstantiatedClass(clazz);
    // Add all dependent instance members to the workqueue.
    transitionDependentItemsForInstantiatedClass(clazz);
  }

  // TODO(b/146016987): Make this the single instantiation entry rather than the worklist action.
  private boolean markInstantiatedClass(
      DexProgramClass clazz,
      ProgramMethod context,
      InstantiationReason instantiationReason,
      KeepReason keepReason) {
    assert !clazz.isInterface();
    return objectAllocationInfoCollection.recordDirectAllocationSite(
        clazz, context, instantiationReason, keepReason, appInfo);
  }

  void markAnnotationAsInstantiated(DexProgramClass clazz, KeepReasonWitness witness) {
    assert clazz.isAnnotation();
    if (!objectAllocationInfoCollection.recordInstantiatedAnnotation(clazz, appInfo)) {
      return;
    }
    markTypeAsLive(clazz, witness);
    transitionDependentItemsForInstantiatedInterface(clazz);
  }

  void markInterfaceAsInstantiated(DexProgramClass clazz, KeepReasonWitness witness) {
    assert !clazz.isAnnotation();
    assert clazz.isInterface();
    if (!objectAllocationInfoCollection.recordInstantiatedInterface(clazz, appInfo)) {
      return;
    }
    markTypeAsLive(clazz, witness);
    transitionDependentItemsForInstantiatedInterface(clazz);
  }

  private void markLambdaAsInstantiated(LambdaDescriptor descriptor, ProgramMethod context) {
    // Each descriptor is unique, so there is no check for already marking the lambda.
    for (DexType iface : descriptor.interfaces) {
      checkLambdaInterface(iface, context);
      objectAllocationInfoCollection.recordInstantiatedLambdaInterface(iface, descriptor, appInfo);
    }
  }

  private void checkLambdaInterface(DexType itf, ProgramMethod context) {
    DexClass clazz = definitionFor(itf, context);
    if (clazz == null) {
      if (!appView.getDontWarnConfiguration().matches(itf)) {
        StringDiagnostic message =
            new StringDiagnostic(
                "Lambda expression implements missing interface `" + itf.toSourceString() + "`",
                context.getOrigin());
        options.reporter.warning(message);
      }
    } else if (!clazz.isInterface()) {
      if (!appView.getDontWarnConfiguration().matches(itf)) {
        StringDiagnostic message =
            new StringDiagnostic(
                "Lambda expression expected to implement an interface, but found "
                    + "`"
                    + itf.toSourceString()
                    + "`",
                context.getOrigin());
        options.reporter.warning(message);
      }
    }
  }

  private void transitionMethodsForInstantiatedLambda(LambdaDescriptor lambda) {
    transitionMethodsForInstantiatedObject(
        InstantiatedObject.of(lambda), appInfo.dexItemFactory().objectType, lambda.interfaces);
  }

  private void transitionMethodsForInstantiatedClass(DexProgramClass clazz) {
    assert !clazz.isAnnotation();
    assert !clazz.isInterface();
    transitionMethodsForInstantiatedObject(
        InstantiatedObject.of(clazz), clazz.type, Collections.emptyList());
  }

  /**
   * Marks all methods live that are overrides of reachable methods for a given instantiation.
   *
   * <p>Only reachable methods in the hierarchy of the given instantiation and above are considered,
   * and only the lowest such reachable target (ie, mirroring resolution). All library and classpath
   * methods are considered reachable.
   */
  private void transitionMethodsForInstantiatedObject(
      InstantiatedObject instantiation, DexType type, List<DexType> interfaces) {
    WorkList<DexType> worklist = WorkList.newIdentityWorkList(type);
    worklist.addIfNotSeen(interfaces);
    while (worklist.hasNext()) {
      DexClass clazz = appInfo().definitionFor(worklist.next());
      if (clazz == null) {
        continue;
      }
      if (clazz.isProgramClass()) {
        markProgramMethodOverridesAsLive(instantiation, clazz.asProgramClass());
      } else {
        markLibraryAndClasspathMethodOverridesAsLive(instantiation, clazz);
      }
      if (clazz.superType != null) {
        worklist.addIfNotSeen(clazz.superType);
      }
      worklist.addIfNotSeen(clazz.interfaces);
    }
  }

  private Map<ResolutionSearchKey, ProgramMethodSet> getReachableVirtualTargets(
      DexProgramClass clazz) {
    return reachableVirtualTargets.getOrDefault(clazz, Collections.emptyMap());
  }

  private void markProgramMethodOverridesAsLive(
      InstantiatedObject instantiation, DexProgramClass currentClass) {
    assert instantiation.isLambda()
        || appInfo.isSubtype(instantiation.asClass().getType(), currentClass.type);
    getReachableVirtualTargets(currentClass)
        .forEach(
            (resolutionSearchKey, contexts) -> {
              SingleResolutionResult<?> singleResolution =
                  appInfo
                      .resolveMethod(resolutionSearchKey.method, resolutionSearchKey.isInterface)
                      .asSingleResolution();
              if (singleResolution == null) {
                assert false : "Should not be null";
                return;
              }
              Map<DexProgramClass, List<ProgramMethod>> contextsByClass = new IdentityHashMap<>();
              for (ProgramMethod context : contexts) {
                contextsByClass
                    .computeIfAbsent(context.getHolder(), ignoreKey(ArrayList::new))
                    .add(context);
              }
              contextsByClass.forEach(
                  (contextHolder, contextsInHolder) -> {
                    LookupResult lookupResult =
                        singleResolution.lookupVirtualDispatchTargets(
                            contextHolder,
                            appInfo,
                            (type, subTypeConsumer, lambdaConsumer) -> {
                              assert appInfo.isSubtype(currentClass.type, type);
                              instantiation.apply(subTypeConsumer, lambdaConsumer);
                            },
                            definition ->
                                keepInfo.isPinned(definition.getReference(), appInfo, options));
                    lookupResult.forEach(
                        target ->
                            markVirtualDispatchTargetAsLive(
                                target,
                                programMethod ->
                                    graphReporter.reportReachableMethodAsLive(
                                        singleResolution.getResolvedMethod().getReference(),
                                        programMethod)));
                    lookupResult.forEachFailureDependency(
                        method -> {
                          DexProgramClass clazz =
                              getProgramClassOrNull(method.getHolderType(), contextHolder);
                          if (clazz != null) {
                            failedMethodResolutionTargets.add(method.getReference());
                            for (ProgramMethod context : contextsInHolder) {
                              markMethodAsTargeted(
                                  new ProgramMethod(clazz, method),
                                  KeepReason.invokedFrom(context));
                            }
                          }
                        });
                  });
            });
  }

  private void markLibraryAndClasspathMethodOverridesAsLive(
      InstantiatedObject instantiation, DexClass libraryClass) {
    assert libraryClass.isNotProgramClass();
    if (mode.isMainDexTracing()) {
      // Library roots must be specified for tracing of library methods. For classpath the expected
      // use case is that the classes will be classloaded, thus they should have no bearing on the
      // content of the main dex file.
      return;
    }
    for (DexEncodedMethod method : libraryClass.virtualMethods()) {
      assert !method.isPrivateMethod();
      // Note: It would be reasonable to not process methods already seen during the marking of
      // program usages, but that would cause the methods to not be marked as library overrides.
      markLibraryOrClasspathOverrideLive(
          instantiation,
          libraryClass,
          appInfo.resolveMethodOn(libraryClass, method.getReference()));

      // Due to API conversion, some overrides can be hidden since they will be rewritten. See
      // class comment of DesugaredLibraryAPIConverter and vivifiedType logic.
      // In the first enqueuer phase, the signature has not been desugared, so firstResolution
      // maintains the library override. In the second enqueuer phase, the signature has been
      // desugared, and the second resolution maintains the the library override.
      if (instantiation.isClass()
          && appView.typeRewriter.hasRewrittenTypeInSignature(
              method.getReference().proto, appView)) {
        DexMethod methodToResolve =
            DesugaredLibraryAPIConverter.methodWithVivifiedTypeInSignature(
                method.getReference(), method.getHolderType(), appView);
        assert methodToResolve != method.getReference();
        markLibraryOrClasspathOverrideLive(
            instantiation,
            libraryClass,
            appInfo.resolveMethodOn(instantiation.asClass(), methodToResolve));
      }
    }
  }

  private void markLibraryOrClasspathOverrideLive(
      InstantiatedObject instantiation,
      DexClass libraryOrClasspathClass,
      MethodResolutionResult resolution) {
    LookupTarget lookup = resolution.lookupVirtualDispatchTarget(instantiation, appInfo);
    if (lookup == null) {
      return;
    }
    if (!shouldMarkLibraryMethodOverrideAsReachable(lookup)) {
      return;
    }
    markVirtualDispatchTargetAsLive(
        lookup,
        method ->
            graphReporter.reportLibraryMethodAsLive(
                instantiation, method, libraryOrClasspathClass));
    if (instantiation.isClass()) {
      // TODO(b/149976493): We need to mark these for lambdas too!
      markOverridesAsLibraryMethodOverrides(
          instantiation.asClass(), lookup.asMethodTarget().getDefinition().getReference());
    }
  }

  private void markOverridesAsLibraryMethodOverrides(
      DexProgramClass instantiatedClass, DexMethod libraryMethodOverride) {
    WorkList<DexProgramClass> worklist = WorkList.newIdentityWorkList();
    worklist.addIfNotSeen(instantiatedClass);
    while (worklist.hasNext()) {
      DexProgramClass clazz = worklist.next();
      DexEncodedMethod override = clazz.lookupVirtualMethod(libraryMethodOverride);
      if (override != null) {
        if (override.isLibraryMethodOverride().isTrue()) {
          continue;
        }
        override.setLibraryMethodOverride(OptionalBool.TRUE);
      }
      clazz.forEachImmediateSupertype(
          superType -> {
            DexProgramClass superclass = getProgramClassOrNull(superType, clazz);
            if (superclass != null) {
              worklist.addIfNotSeen(superclass);
            }
          });
    }
  }

  /**
   * Marks all fields live that can be reached by a read assuming that the given type or one of its
   * subtypes is instantiated.
   */
  private void transitionFieldsForInstantiatedClass(DexProgramClass clazz) {
    do {
      ProgramFieldSet reachableFields = reachableInstanceFields.get(clazz);
      if (reachableFields != null) {
        // TODO(b/120959039): Should the reason this field is reachable come from the set?
        KeepReason reason = KeepReason.reachableFromLiveType(clazz.type);
        for (ProgramField field : reachableFields) {
          markFieldAsLive(field, clazz, reason);
        }
      }
      clazz = getProgramClassOrNull(clazz.superType, clazz);
    } while (clazz != null && !objectAllocationInfoCollection.isInstantiatedDirectly(clazz));
  }

  private void transitionDependentItemsForInstantiatedClass(DexProgramClass clazz) {
    assert !clazz.isAnnotation();
    assert !clazz.isInterface();
    transitionDependentItemsForInstantiatedItem(clazz);
  }

  private void transitionDependentItemsForInstantiatedInterface(DexProgramClass clazz) {
    assert clazz.isInterface();
    transitionDependentItemsForInstantiatedItem(clazz);
  }

  private void transitionDependentItemsForInstantiatedItem(DexProgramClass clazz) {
    WorkList<DexProgramClass> interfacesToTransition =
        WorkList.newWorkList(interfacesTransitionedToInstantiated);
    if (clazz.getAccessFlags().isInterface()) {
      interfacesToTransition.addIfNotSeen(clazz);
    } else {
      do {
        // Handle keep rules that are dependent on the class being instantiated.
        applyMinimumKeepInfoDependentOn(new InstantiatedClassEnqueuerEvent(clazz));

        for (DexType interfaceType : clazz.getInterfaces()) {
          DexProgramClass interfaceClass =
              asProgramClassOrNull(definitionFor(interfaceType, clazz));
          if (interfaceClass != null) {
            interfacesToTransition.addIfNotSeen(interfaceClass);
          }
        }

        // Visit the super type.
        clazz =
            clazz.superType != null
                ? asProgramClassOrNull(appView.definitionFor(clazz.superType))
                : null;
      } while (clazz != null && !objectAllocationInfoCollection.isInstantiatedDirectly(clazz));
    }

    while (interfacesToTransition.hasNext()) {
      DexProgramClass interfaceClass = interfacesToTransition.next();
      applyMinimumKeepInfoDependentOn(new InstantiatedClassEnqueuerEvent(interfaceClass));

      for (DexType indirectInterfaceType : interfaceClass.getInterfaces()) {
        DexProgramClass indirectInterfaceClass =
            asProgramClassOrNull(definitionFor(indirectInterfaceType, interfaceClass));
        if (indirectInterfaceClass != null) {
          interfacesToTransition.addIfNotSeen(indirectInterfaceClass);
        }
      }
    }
  }

  private void transitionUnusedInterfaceToLive(DexProgramClass clazz) {
    if (clazz.isInterface()) {
      Set<DexProgramClass> implementedBy = unusedInterfaceTypes.remove(clazz);
      if (implementedBy != null) {
        for (DexProgramClass implementer : implementedBy) {
          markTypeAsLive(clazz, implementer);
        }
      }
    } else {
      assert !unusedInterfaceTypes.containsKey(clazz);
    }
  }

  private void markFieldAsLive(ProgramField field, ProgramMethod context) {
    markFieldAsLive(field, context, KeepReason.fieldReferencedIn(context));
  }

  private void markFieldAsLive(ProgramField field, ProgramDefinition context, KeepReason reason) {
    // This field might be an instance field reachable from a static context, e.g. a getStatic that
    // resolves to an instance field. We have to keep the instance field nonetheless, as otherwise
    // we might unmask a shadowed static field and hence change semantics.
    if (!liveFields.add(field, reason)) {
      // Already live.
      return;
    }

    // Mark the field as targeted.
    if (field.getAccessFlags().isStatic()) {
      traceFieldDefinition(field);
      markDirectAndIndirectClassInitializersAsLive(field.getHolder());
    } else if (!reachableInstanceFields
        .getOrDefault(field.getHolder(), ProgramFieldSet.empty())
        .contains(field)) {
      traceFieldDefinition(field);
    }

    // Update keep info.
    applyMinimumKeepInfo(field);
    if (hasAlternativeLibraryDefinition(field.getHolder()) && !field.getDefinition().isPrivate()) {
      getKeepInfo().keepField(field);
    }

    // Notify analyses.
    analyses.forEach(analysis -> analysis.processNewlyLiveField(field, context, workList));
  }

  // Package protected due to entry point from worklist.
  void markFieldAsReachable(ProgramField field, ProgramDefinition context, KeepReason reason) {
    // We might have a instance field access that is dispatched to a static field. In such case,
    // we have to keep the static field, so that the dispatch fails at runtime in the same way that
    // it did before. We have to keep the field even if the receiver has no live inhabitants, as
    // field resolution happens before the receiver is inspected.
    if (field.getDefinition().isStatic()
        || objectAllocationInfoCollection.isInstantiatedDirectlyOrHasInstantiatedSubtype(
            field.getHolder())) {
      markFieldAsLive(field, context, reason);
    }

    if (liveFields.contains(field)
        || !reachableInstanceFields
            .computeIfAbsent(field.getHolder(), ignore -> ProgramFieldSet.create())
            .add(field)) {
      // Already reachable.
      graphReporter.registerField(field.getDefinition(), reason);
      return;
    }

    traceFieldDefinition(field);

    analyses.forEach(analysis -> analysis.notifyMarkFieldAsReachable(field, workList));
  }

  private void traceFieldDefinition(ProgramField field) {
    markTypeAsLive(field.getHolder(), field);
    markTypeAsLive(field.getType(), field);
    processAnnotations(field);
  }

  private void traceFieldReference(
      DexField field,
      FailedOrUnknownFieldResolutionResult resolutionResult,
      ProgramMethod context) {
    markTypeAsLive(field.getHolderType(), context);
    markTypeAsLive(field.getType(), context);
  }

  private void markDirectStaticOrConstructorMethodAsLive(ProgramMethod method, KeepReason reason) {
    if (workList.enqueueMarkMethodLiveAction(method, method, reason)) {
      assert workList.enqueueAssertAction(
          () -> {
            // Should have marked the holder type live.
            assert method.getDefinition().isClassInitializer() || verifyMethodIsTargeted(method);
            assert verifyTypeIsLive(method.getHolder());
          });
    } else {
      assert method.getDefinition().isClassInitializer() || verifyMethodIsTargeted(method);
      assert workList.enqueueAssertAction(() -> verifyTypeIsLive(method.getHolder()));
    }
  }

  private void markVirtualMethodAsLive(ProgramMethod method, KeepReason reason) {
    // Only explicit keep rules or reflective use should make abstract methods live.
    assert !method.getDefinition().isAbstract()
        || reason.isDueToKeepRule()
        || reason.isDueToReflectiveUse();
    workList.enqueueMarkMethodLiveAction(method, method, reason);
  }

  public boolean isFieldReferenced(DexEncodedField field) {
    FieldAccessInfoImpl info = fieldAccessInfoCollection.get(field.getReference());
    return info != null;
  }

  public boolean isFieldReferenced(ProgramField field) {
    return isFieldReferenced(field.getDefinition());
  }

  public boolean isFieldLive(ProgramField field) {
    return liveFields.contains(field);
  }

  public boolean isFieldLive(DexEncodedField field) {
    return liveFields.contains(field);
  }

  public boolean isFieldRead(ProgramField field) {
    FieldAccessInfoImpl info = fieldAccessInfoCollection.get(field.getReference());
    return info != null && info.isRead();
  }

  public boolean isFieldWrittenInMethodSatisfying(
      ProgramField field, Predicate<ProgramMethod> predicate) {
    FieldAccessInfoImpl info = fieldAccessInfoCollection.get(field.getReference());
    return info != null && info.isWrittenInMethodSatisfying(predicate);
  }

  public boolean isFieldWrittenOutsideDefaultConstructor(ProgramField field) {
    FieldAccessInfoImpl info = fieldAccessInfoCollection.get(field.getReference());
    if (info == null) {
      return false;
    }
    DexEncodedMethod defaultInitializer = field.getHolder().getDefaultInitializer();
    return defaultInitializer != null
        ? info.isWrittenOutside(defaultInitializer)
        : info.isWritten();
  }

  public boolean isPreconditionForMinimumKeepInfoSatisfied(EnqueuerEvent preconditionEvent) {
    if (preconditionEvent == null || preconditionEvent.isUnconditionalKeepInfoEvent()) {
      return true;
    }
    if (preconditionEvent.isClassEvent()) {
      ClassEnqueuerEvent classEvent = preconditionEvent.asClassEvent();
      DexProgramClass clazz = asProgramClassOrNull(appView.definitionFor(classEvent.getType()));
      if (clazz == null) {
        return false;
      }
      if (preconditionEvent.isLiveClassEvent()) {
        return liveTypes.contains(clazz);
      }
      if (preconditionEvent.isInstantiatedClassEvent()) {
        return objectAllocationInfoCollection.isInstantiatedDirectlyOrHasInstantiatedSubtype(clazz);
      }
    }
    assert false;
    return false;
  }

  public boolean isMemberLive(DexEncodedMember<?, ?> member) {
    assert member != null;
    return member.isDexEncodedField()
        ? liveFields.contains(member.asDexEncodedField())
        : liveMethods.contains(member.asDexEncodedMethod());
  }

  public boolean isMethodLive(DexEncodedMethod method) {
    return liveMethods.contains(method);
  }

  public boolean isMethodLive(ProgramMethod method) {
    return isMethodLive(method.getDefinition());
  }

  public boolean isMethodTargeted(DexEncodedMethod method) {
    return targetedMethods.contains(method);
  }

  public boolean isMethodTargeted(ProgramMethod method) {
    return isMethodTargeted(method.getDefinition());
  }

  public boolean isTypeLive(DexClass clazz) {
    return clazz.isProgramClass()
        ? isTypeLive(clazz.asProgramClass())
        : isNonProgramTypeLive(clazz);
  }

  public boolean isTypeLive(DexProgramClass clazz) {
    return liveTypes.contains(clazz);
  }

  public boolean isNonProgramTypeLive(DexClass clazz) {
    assert !clazz.isProgramClass();
    return liveNonProgramTypes.contains(clazz);
  }

  public boolean isReachable(Definition definition) {
    assert definition != null;

    if (definition.isClass()) {
      return isTypeLive(definition.asClass());
    }

    assert definition.isMember();

    if (definition.getContextClass().isProgramClass()) {
      if (definition.isField()) {
        ProgramField field = definition.asProgramField();
        return isFieldLive(field) || isFieldReferenced(field);
      } else {
        assert definition.isMethod();
        ProgramMethod method = definition.asProgramMethod();
        return isMethodLive(method) || isMethodTargeted(method);
      }
    }

    return isNonProgramTypeLive(definition.getContextClass());
  }

  public void forAllLiveClasses(Consumer<DexProgramClass> consumer) {
    liveTypes.getItems().forEach(consumer);
  }

  private void markVirtualMethodAsReachable(
      DexMethod method, boolean interfaceInvoke, ProgramMethod context, KeepReason reason) {
    if (method.holder.isArrayType()) {
      // This is an array type, so the actual class will be generated at runtime. We treat this
      // like an invoke on a direct subtype of java.lang.Object that has no further subtypes.
      // As it has no subtypes, it cannot affect liveness of the program we are processing.
      // Ergo, we can ignore it. We need to make sure that the element type is available, though.
      markTypeAsLive(method.holder, context, reason);
      return;
    }

    SingleResolutionResult<?> resolution = resolveMethod(method, context, reason, interfaceInvoke);
    if (resolution == null) {
      return;
    }

    // Note that all virtual methods derived from library methods are kept regardless of being
    // reachable, so the following only needs to consider reachable targets in the program.
    // TODO(b/70160030): Revise this to support tree shaking library methods on non-escaping types.
    DexProgramClass initialResolutionHolder =
        resolution.getInitialResolutionHolder().asProgramClass();
    if (initialResolutionHolder == null) {
      recordMethodReference(method, context);
      return;
    }

    if (resolution.getResolvedHolder().isNotProgramClass()) {
      // TODO(b/70160030): If the resolution is on a library method, then the keep edge needs to go
      // directly to the target method in the program. Thus this method will need to ensure that
      // 'reason' is not already reported (eg, must be delayed / non-witness) and report that for
      // each possible target edge below.
      return;
    }

    DexProgramClass contextHolder = context.getContextClass();
    // If the method has already been marked, just report the new reason for the resolved target and
    // save the context to ensure correct lookup of virtual dispatch targets.
    ResolutionSearchKey resolutionSearchKey = new ResolutionSearchKey(method, interfaceInvoke);
    ProgramMethodSet seenContexts =
        getReachableVirtualTargets(initialResolutionHolder).get(resolutionSearchKey);
    if (seenContexts != null) {
      seenContexts.add(context);
      graphReporter.registerMethod(resolution.getResolvedMethod(), reason);
      return;
    }

    if (Log.ENABLED) {
      Log.verbose(getClass(), "Marking virtual method `%s` as reachable.", method);
    }

    // We have to mark the resolution targeted, even if it does not become live, we
    // need at least an abstract version of it so that it can be targeted.
    DexProgramClass resolvedHolder = resolution.getResolvedHolder().asProgramClass();
    DexEncodedMethod resolvedMethod = resolution.getResolvedMethod();
    markMethodAsTargeted(new ProgramMethod(resolvedHolder, resolvedMethod), reason);
    if (resolution.isAccessibleForVirtualDispatchFrom(contextHolder, appInfo).isFalse()) {
      // Not accessible from this context, so this call will cause a runtime exception.
      return;
    }

    // The method resolved and is accessible, so currently live overrides become live.
    reachableVirtualTargets
        .computeIfAbsent(initialResolutionHolder, ignoreArgument(HashMap::new))
        .computeIfAbsent(resolutionSearchKey, ignoreArgument(ProgramMethodSet::create))
        .add(context);

    resolution
        .lookupVirtualDispatchTargets(
            contextHolder,
            appInfo,
            (type, subTypeConsumer, lambdaConsumer) ->
                objectAllocationInfoCollection.forEachInstantiatedSubType(
                    type, subTypeConsumer, lambdaConsumer, appInfo),
            definition -> keepInfo.isPinned(definition.getReference(), appInfo, options))
        .forEach(
            target ->
                markVirtualDispatchTargetAsLive(
                    target,
                    programMethod ->
                        graphReporter.reportReachableMethodAsLive(
                            resolvedMethod.getReference(), programMethod)));
  }

  private void markVirtualDispatchTargetAsLive(
      LookupTarget target, Function<ProgramMethod, KeepReasonWitness> reason) {
    target.accept(
        method -> markVirtualDispatchMethodTargetAsLive(method, reason),
        lambda -> markVirtualDispatchLambdaTargetAsLive(lambda, reason));
    analyses.forEach(analysis -> analysis.notifyMarkVirtualDispatchTargetAsLive(target, workList));
  }

  private void markVirtualDispatchMethodTargetAsLive(
      LookupMethodTarget target, Function<ProgramMethod, KeepReasonWitness> reason) {
    ProgramMethod programMethod = target.getTarget().asProgramMethod();
    if (programMethod != null && !programMethod.getDefinition().isAbstract()) {
      KeepReasonWitness appliedReason = reason.apply(programMethod);
      markVirtualMethodAsLive(programMethod, appliedReason);
      DexClassAndMethod accessOverride = target.getAccessOverride();
      if (accessOverride != null && accessOverride.isProgramMethod()) {
        markMethodAsTargeted(accessOverride.asProgramMethod(), appliedReason);
      }
    }
  }

  private void markVirtualDispatchLambdaTargetAsLive(
      LookupLambdaTarget target, Function<ProgramMethod, KeepReasonWitness> reason) {
    ProgramMethod implementationMethod = target.getImplementationMethod().asProgramMethod();
    if (implementationMethod != null) {
      workList.enqueueMarkMethodLiveAction(
          implementationMethod, implementationMethod, reason.apply(implementationMethod));
    }
  }

  private void markFailedMethodResolutionTargets(
      DexMethod symbolicMethod,
      FailedResolutionResult failedResolution,
      ProgramDefinition context,
      KeepReason reason) {
    failedMethodResolutionTargets.add(symbolicMethod);
    failedResolution.forEachFailureDependency(
        method -> {
          DexProgramClass clazz = getProgramClassOrNull(method.getHolderType(), context);
          if (clazz != null) {
            failedMethodResolutionTargets.add(method.getReference());
            markMethodAsTargeted(new ProgramMethod(clazz, method), reason);
          }
        });
  }

  private DexMethod generatedEnumValuesMethod(DexClass enumClass) {
    DexType arrayOfEnumClass =
        appView
            .dexItemFactory()
            .createType(
                appView.dexItemFactory().createString("[" + enumClass.type.toDescriptorString()));
    DexProto proto = appView.dexItemFactory().createProto(arrayOfEnumClass);
    return appView
        .dexItemFactory()
        .createMethod(enumClass.type, proto, appView.dexItemFactory().createString("values"));
  }

  private void markEnumValuesAsReachable(DexProgramClass clazz, KeepReason reason) {
    ProgramMethod valuesMethod = clazz.lookupProgramMethod(generatedEnumValuesMethod(clazz));
    if (valuesMethod != null) {
      // TODO(sgjesse): Does this have to be enqueued as a root item? Right now it is done as the
      // marking for not renaming it is in the root set.
      workList.enqueueMarkMethodKeptAction(valuesMethod, reason);
      keepInfo.joinMethod(
          valuesMethod,
          joiner -> joiner.disallowMinification().disallowOptimization().disallowShrinking());
      shouldNotBeMinified(valuesMethod);
    }
  }

  // Package protected due to entry point from worklist.
  void markSuperMethodAsReachable(DexMethod reference, ProgramMethod from) {
    KeepReason reason = KeepReason.targetedBySuperFrom(from);
    SingleResolutionResult<?> resolution = resolveMethod(reference, from, reason);
    if (resolution == null) {
      return;
    }
    // If the resolution is in the program, mark it targeted.
    if (resolution.getResolvedHolder().isProgramClass()) {
      markMethodAsTargeted(
          new ProgramMethod(
              resolution.getResolvedHolder().asProgramClass(), resolution.getResolvedMethod()),
          reason);
    }
    // If invoke target is invalid (inaccessible or not an instance-method) record it and stop.
    DexClassAndMethod target = resolution.lookupInvokeSuperTarget(from.getHolder(), appInfo);
    if (target == null) {
      failedMethodResolutionTargets.add(resolution.getResolvedMethod().getReference());
      analyses.forEach(
          analyses ->
              analyses.notifyFailedMethodResolutionTarget(
                  resolution.getResolvedMethod(), workList));
      return;
    }

    DexProgramClass clazz = target.getHolder().asProgramClass();
    if (clazz == null) {
      return;
    }

    ProgramMethod method = target.asProgramMethod();

    if (Log.ENABLED) {
      Log.verbose(
          getClass(), "Adding super constraint from `%s` to `%s`", from, target.getReference());
    }
    if (superInvokeDependencies
        .computeIfAbsent(from.getDefinition(), ignore -> ProgramMethodSet.create())
        .add(method)) {
      if (liveMethods.contains(from)) {
        markMethodAsTargeted(method, KeepReason.invokedViaSuperFrom(from));
        if (!target.getAccessFlags().isAbstract()) {
          markVirtualMethodAsLive(method, KeepReason.invokedViaSuperFrom(from));
        }
      }
    }
  }

  // Returns the set of live types.
  public MainDexInfo traceMainDex(ExecutorService executorService, Timing timing)
      throws ExecutionException {
    assert analyses.isEmpty();
    assert mode.isMainDexTracing();
    this.rootSet = appView.getMainDexRootSet();
    // Translate the result of root-set computation into enqueuer actions.
    includeMinimumKeepInfo(rootSet);
    trace(executorService, timing);
    options.reporter.failIfPendingErrors();
    // Calculate the automatic main dex list according to legacy multidex constraints.
    MainDexInfo.Builder builder = appView.appInfo().getMainDexInfo().builder();
    liveTypes.getItems().forEach(builder::addRoot);
    if (mode.isInitialMainDexTracing()) {
      liveMethods.getItems().forEach(method -> builder.addRoot(method.getReference()));
    } else {
      assert appView.appInfo().getMainDexInfo().isTracedMethodRootsCleared()
          || mode.isGenerateMainDexList();
    }
    new MainDexListBuilder(appView, builder.getRoots(), builder).run();
    MainDexInfo previousMainDexInfo = appInfo.getMainDexInfo();
    return builder.build(previousMainDexInfo);
  }

  public EnqueuerResult traceApplication(
      RootSet rootSet, ExecutorService executorService, Timing timing) throws ExecutionException {
    this.rootSet = rootSet;
    rootSet.pendingMethodMoveInverse.forEach(pendingMethodMoveInverse::put);
    // Translate the result of root-set computation into enqueuer actions.
    if (mode.isTreeShaking()
        && appView.options().hasProguardConfiguration()
        && !options.kotlinOptimizationOptions().disableKotlinSpecificOptimizations) {
      registerAnalysis(
          new KotlinMetadataEnqueuerExtension(
              appView, enqueuerDefinitionSupplier, initialPrunedTypes));
    }
    if (appView.options().getProguardConfiguration() != null
        && appView.options().getProguardConfiguration().getKeepAttributes().signature) {
      registerAnalysis(new GenericSignatureEnqueuerAnalysis(enqueuerDefinitionSupplier));
    }
    registerAnalysis(new ApiModelAnalysis(appView));

    // Transfer the minimum keep info from the root set into the Enqueuer state.
    includeMinimumKeepInfo(rootSet);

    if (mode.isInitialTreeShaking()) {
      // This is simulating the effect of the "root set" applied rules.
      // This is done only in the initial pass, in subsequent passes the "rules" are reapplied
      // by iterating the instances.
    } else if (appView.getKeepInfo() != null) {
      EnqueuerEvent preconditionEvent = UnconditionalKeepInfoEvent.get();
      appView
          .getKeepInfo()
          .forEachRuleInstance(
              appView,
              (clazz, minimumKeepInfo) ->
                  applyMinimumKeepInfoWhenLive(clazz, preconditionEvent, minimumKeepInfo),
              (field, minimumKeepInfo) ->
                  applyMinimumKeepInfoWhenLive(field, preconditionEvent, minimumKeepInfo),
              this::applyMinimumKeepInfoWhenLiveOrTargeted);
    }
    enqueueAllIfNotShrinking();
    trace(executorService, timing);
    options.reporter.failIfPendingErrors();
    finalizeLibraryMethodOverrideInformation();
    analyses.forEach(analyses -> analyses.done(this));
    assert verifyKeptGraph();
    if (mode.isInitialTreeShaking() && forceProguardCompatibility) {
      appView.setProguardCompatibilityActions(proguardCompatibilityActionsBuilder.build());
    } else {
      assert proguardCompatibilityActionsBuilder == null;
    }
    if (mode.isWhyAreYouKeeping()) {
      // For why are you keeping the information is reported through the kept graph callbacks and
      // no AppInfo is returned.
      return null;
    }
    return createEnqueuerResult(appInfo);
  }

  private void includeMinimumKeepInfo(RootSetBase rootSet) {
    rootSet
        .getDependentMinimumKeepInfo()
        .forEach(
            appView,
            this::recordDependentMinimumKeepInfo,
            this::recordDependentMinimumKeepInfo,
            this::recordDependentMinimumKeepInfo);
  }

  private void applyMinimumKeepInfo(DexProgramClass clazz) {
    EnqueuerEvent preconditionEvent = UnconditionalKeepInfoEvent.get();
    KeepClassInfo.Joiner minimumKeepInfoForClass =
        dependentMinimumKeepInfo.remove(preconditionEvent, clazz.getType());
    if (minimumKeepInfoForClass != null) {
      keepInfo.joinClass(clazz, info -> info.merge(minimumKeepInfoForClass));
      enqueueClassIfShrinkingIsDisallowed(clazz, preconditionEvent, minimumKeepInfoForClass);
    }
  }

  private void applyMinimumKeepInfoWhenLive(
      DexProgramClass clazz,
      EnqueuerEvent preconditionEvent,
      KeepClassInfo.Joiner minimumKeepInfo) {
    if (liveTypes.contains(clazz)) {
      keepInfo.joinClass(clazz, info -> info.merge(minimumKeepInfo));
    } else {
      dependentMinimumKeepInfo
          .getOrCreateUnconditionalMinimumKeepInfo()
          .mergeMinimumKeepInfoFor(clazz.getType(), minimumKeepInfo);
    }
    enqueueClassIfShrinkingIsDisallowed(clazz, preconditionEvent, minimumKeepInfo);
  }

  private void enqueueClassIfShrinkingIsDisallowed(
      DexProgramClass clazz,
      EnqueuerEvent preconditionEvent,
      KeepClassInfo.Joiner minimumKeepInfo) {
    if ((options.isShrinking() || mode.isMainDexTracing())
        && !minimumKeepInfo.isShrinkingAllowed()) {
      assert minimumKeepInfo.verifyShrinkingDisallowedWithRule(options);
      enqueueClassDueToNoShrinkingRule(clazz, minimumKeepInfo, preconditionEvent);
    }
  }

  private void recordDependentMinimumKeepInfo(
      EnqueuerEvent preconditionEvent,
      DexProgramClass clazz,
      KeepClassInfo.Joiner minimumKeepInfo) {
    if (isPreconditionForMinimumKeepInfoSatisfied(preconditionEvent)) {
      applyMinimumKeepInfoWhenLive(clazz, preconditionEvent, minimumKeepInfo);
    } else {
      dependentMinimumKeepInfo
          .getOrCreateMinimumKeepInfoFor(preconditionEvent)
          .mergeMinimumKeepInfoFor(clazz.getType(), minimumKeepInfo);
    }
    if (preconditionEvent.isUnconditionalKeepInfoEvent()) {
      enqueueClassIfShrinkingIsDisallowed(clazz, preconditionEvent, minimumKeepInfo);
    }
  }

  private void applyMinimumKeepInfo(ProgramField field) {
    EnqueuerEvent preconditionEvent = UnconditionalKeepInfoEvent.get();
    KeepFieldInfo.Joiner minimumKeepInfoForField =
        dependentMinimumKeepInfo.remove(preconditionEvent, field.getReference());
    if (minimumKeepInfoForField != null) {
      keepInfo.joinField(field, info -> info.merge(minimumKeepInfoForField));
      enqueueFieldIfShrinkingIsDisallowed(field, preconditionEvent, minimumKeepInfoForField);
    }
  }

  private void applyMinimumKeepInfoWhenLive(
      ProgramField field, EnqueuerEvent preconditionEvent, KeepFieldInfo.Joiner minimumKeepInfo) {
    if (liveFields.contains(field)) {
      keepInfo.joinField(field, info -> info.merge(minimumKeepInfo));
    } else {
      dependentMinimumKeepInfo
          .getOrCreateUnconditionalMinimumKeepInfo()
          .mergeMinimumKeepInfoFor(field.getReference(), minimumKeepInfo);
    }
    enqueueFieldIfShrinkingIsDisallowed(field, preconditionEvent, minimumKeepInfo);
  }

  private void enqueueFieldIfShrinkingIsDisallowed(
      ProgramField field, EnqueuerEvent preconditionEvent, KeepFieldInfo.Joiner minimumKeepInfo) {
    if ((options.isShrinking() || mode.isMainDexTracing())
        && !minimumKeepInfo.isShrinkingAllowed()) {
      assert minimumKeepInfo.verifyShrinkingDisallowedWithRule(options);
      enqueueFieldDueToNoShrinkingRule(field, minimumKeepInfo, preconditionEvent);
    }
  }

  private void recordDependentMinimumKeepInfo(
      EnqueuerEvent preconditionEvent, ProgramField field, KeepFieldInfo.Joiner minimumKeepInfo) {
    if (isPreconditionForMinimumKeepInfoSatisfied(preconditionEvent)) {
      applyMinimumKeepInfoWhenLive(field, preconditionEvent, minimumKeepInfo);
    } else {
      dependentMinimumKeepInfo
          .getOrCreateMinimumKeepInfoFor(preconditionEvent)
          .mergeMinimumKeepInfoFor(field.getReference(), minimumKeepInfo);
    }
    if (preconditionEvent.isUnconditionalKeepInfoEvent()) {
      enqueueFieldIfShrinkingIsDisallowed(field, preconditionEvent, minimumKeepInfo);
    }
  }

  private void applyMinimumKeepInfo(ProgramMethod method) {
    EnqueuerEvent preconditionEvent = UnconditionalKeepInfoEvent.get();
    KeepMethodInfo.Joiner minimumKeepInfoForMethod =
        dependentMinimumKeepInfo.remove(preconditionEvent, method.getReference());
    if (minimumKeepInfoForMethod != null) {
      keepInfo.joinMethod(method, info -> info.merge(minimumKeepInfoForMethod));
      enqueueMethodIfShrinkingIsDisallowed(method, preconditionEvent, minimumKeepInfoForMethod);
    }
  }

  public void applyMinimumKeepInfoWhenLiveOrTargeted(
      ProgramMethod method, KeepMethodInfo.Joiner minimumKeepInfo) {
    applyMinimumKeepInfoWhenLiveOrTargeted(method, minimumKeepInfo, EnqueuerEvent.unconditional());
  }

  private void applyMinimumKeepInfoWhenLiveOrTargeted(
      ProgramMethod method,
      KeepMethodInfo.Joiner minimumKeepInfo,
      EnqueuerEvent preconditionEvent) {
    if (liveMethods.contains(method) || targetedMethods.contains(method)) {
      keepInfo.joinMethod(method, info -> info.merge(minimumKeepInfo));
    } else {
      dependentMinimumKeepInfo
          .getOrCreateUnconditionalMinimumKeepInfo()
          .mergeMinimumKeepInfoFor(method.getReference(), minimumKeepInfo);
    }
    enqueueMethodIfShrinkingIsDisallowed(method, preconditionEvent, minimumKeepInfo);
  }

  private void enqueueMethodIfShrinkingIsDisallowed(
      ProgramMethod method,
      EnqueuerEvent preconditionEvent,
      KeepMethodInfo.Joiner minimumKeepInfo) {
    if ((options.isShrinking() || mode.isMainDexTracing())
        && !minimumKeepInfo.isShrinkingAllowed()) {
      assert minimumKeepInfo.verifyShrinkingDisallowedWithRule(options);
      enqueueMethodDueToNoShrinkingRule(method, minimumKeepInfo, preconditionEvent);

      if (method.getDefinition().isInstanceInitializer()) {
        enqueueHolderWithDependentInstanceConstructor(method, minimumKeepInfo.getRules());
      }
    }
  }

  private void recordDependentMinimumKeepInfo(
      EnqueuerEvent preconditionEvent,
      ProgramMethod method,
      KeepMethodInfo.Joiner minimumKeepInfo) {
    if (isPreconditionForMinimumKeepInfoSatisfied(preconditionEvent)) {
      applyMinimumKeepInfoWhenLiveOrTargeted(method, minimumKeepInfo, preconditionEvent);
    } else {
      dependentMinimumKeepInfo
          .getOrCreateMinimumKeepInfoFor(preconditionEvent)
          .mergeMinimumKeepInfoFor(method.getReference(), minimumKeepInfo);
    }

    if (preconditionEvent.isUnconditionalKeepInfoEvent()) {
      enqueueMethodIfShrinkingIsDisallowed(method, preconditionEvent, minimumKeepInfo);
    }
  }

  private void applyMinimumKeepInfoDependentOn(EnqueuerEvent preconditionEvent) {
    MinimumKeepInfoCollection minimumKeepClassInfoDependentOnPrecondition =
        dependentMinimumKeepInfo.remove(preconditionEvent);
    if (minimumKeepClassInfoDependentOnPrecondition != null) {
      minimumKeepClassInfoDependentOnPrecondition.forEach(
          appView,
          (clazz, minimumKeepInfoForClass) ->
              applyMinimumKeepInfoWhenLive(clazz, preconditionEvent, minimumKeepInfoForClass),
          (field, minimumKeepInfoForField) ->
              applyMinimumKeepInfoWhenLive(field, preconditionEvent, minimumKeepInfoForField),
          (method, minimumKeepInfoForMethod) ->
              applyMinimumKeepInfoWhenLiveOrTargeted(
                  method, minimumKeepInfoForMethod, preconditionEvent));
    }
  }

  public static class SyntheticAdditions {

    private final ProcessorContext processorContext;
    private Map<DexMethod, MethodProcessingContext> methodProcessingContexts =
        new ConcurrentHashMap<>();

    private final List<ProgramMethod> desugaredMethods = new LinkedList<>();

    private final Map<DexMethod, ProgramMethod> liveMethods = new ConcurrentHashMap<>();

    private final ProgramMethodMap<KeepMethodInfo.Joiner> minimumKeepInfo =
        ProgramMethodMap.createConcurrent();

    private final Map<DexType, DexClasspathClass> syntheticClasspathClasses =
        new ConcurrentHashMap<>();

    private final Map<DexProgramClass, Set<DexClass>> injectedInterfaces =
        new ConcurrentHashMap<>();

    // Subset of live methods that need have keep requirements.
    private final List<Pair<ProgramMethod, Consumer<KeepMethodInfo.Joiner>>>
        liveMethodsWithKeepActions = new ArrayList<>();

    SyntheticAdditions(ProcessorContext processorContext) {
      this.processorContext = processorContext;
    }

    MethodProcessingContext getMethodContext(ProgramMethod method) {
      return methodProcessingContexts.computeIfAbsent(
          method.getReference(), k -> processorContext.createMethodProcessingContext(method));
    }

    boolean isEmpty() {
      boolean empty =
          desugaredMethods.isEmpty()
              && liveMethods.isEmpty()
              && syntheticClasspathClasses.isEmpty()
              && injectedInterfaces.isEmpty();
      assert !empty || liveMethodsWithKeepActions.isEmpty();
      return empty;
    }

    public void addLiveClasspathClass(DexClasspathClass clazz) {
      DexClasspathClass old = syntheticClasspathClasses.put(clazz.type, clazz);
      assert old == null || old == clazz;
    }

    public void addLiveMethod(ProgramMethod method) {
      DexMethod signature = method.getDefinition().getReference();
      ProgramMethod old = liveMethods.put(signature, method);
      assert old == null;
    }

    public void addMethodWithDesugaredCodeForTracing(ProgramMethod method) {
      desugaredMethods.add(method);
    }

    public void injectInterface(DexProgramClass clazz, DexClass newInterface) {
      Set<DexClass> newInterfaces =
          injectedInterfaces.computeIfAbsent(clazz, ignored -> Sets.newConcurrentHashSet());
      newInterfaces.add(newInterface);
    }

    public void addMinimumKeepInfo(ProgramMethod method, Consumer<KeepMethodInfo.Joiner> consumer) {
      consumer.accept(
          minimumKeepInfo.computeIfAbsent(method, ignoreKey(KeepMethodInfo::newEmptyJoiner)));
    }

    void enqueueWorkItems(Enqueuer enqueuer) {
      assert enqueuer.mode.isInitialTreeShaking();

      // All synthetic additions are initial tree shaking only. No need to track keep reasons.
      KeepReasonWitness fakeReason = enqueuer.graphReporter.fakeReportShouldNotBeUsed();

      for (ProgramMethod desugaredMethod : desugaredMethods) {
        enqueuer.workList.enqueueTraceCodeAction(desugaredMethod);
      }

      liveMethodsWithKeepActions.forEach(
          item -> enqueuer.keepInfo.joinMethod(item.getFirst(), item.getSecond()));
      for (ProgramMethod liveMethod : liveMethods.values()) {
        assert !enqueuer.targetedMethods.contains(liveMethod.getDefinition());
        enqueuer.markMethodAsTargeted(liveMethod, fakeReason);
        enqueuer.workList.enqueueMarkMethodLiveAction(liveMethod, liveMethod, fakeReason);
      }
      enqueuer.liveNonProgramTypes.addAll(syntheticClasspathClasses.values());
      injectedInterfaces.forEach(
          (clazz, itfs) -> {
            enqueuer.objectAllocationInfoCollection.injectInterfaces(
                enqueuer.appInfo(), clazz, itfs);
          });

      minimumKeepInfo.forEach(
          (method, minimumKeepInfoForMethod) ->
              enqueuer.applyMinimumKeepInfoWhenLiveOrTargeted(method, minimumKeepInfoForMethod));
    }
  }

  private void synthesize() throws ExecutionException {
    if (!mode.isInitialTreeShaking()) {
      return;
    }
    // First part of synthesis is to create and register all reachable synthetic additions.
    // In particular these additions are order independent, i.e., it does not matter which are
    // registered first and no dependencies may exist among them.
    SyntheticAdditions additions = new SyntheticAdditions(appView.createProcessorContext());
    desugar(additions);
    synthesizeInterfaceMethodBridges(additions);
    if (additions.isEmpty()) {
      return;
    }

    // Commit the pending synthetics and recompute subtypes.
    appInfo = appInfo.rebuildWithClassHierarchy(app -> app);
    appView.setAppInfo(appInfo);
    subtypingInfo = SubtypingInfo.create(appView);

    // Finally once all synthesized items "exist" it is now safe to continue tracing. The new work
    // items are enqueued and the fixed point will continue once this subroutine returns.
    additions.enqueueWorkItems(this);
  }

  private boolean mustMoveToInterfaceCompanionMethod(ProgramMethod method) {
    return method.getHolder().isInterface()
        && method.getDefinition().isNonAbstractNonNativeMethod()
        && !method.getDefinition().isInitializer();
  }

  private boolean addToPendingDesugaring(ProgramMethod method) {
    if (options.isInterfaceMethodDesugaringEnabled()) {
      if (mustMoveToInterfaceCompanionMethod(method)) {
        // TODO(b/199043500): Once "live moved methods" are tracked this can avoid the code check.
        if (!InvalidCode.isInvalidCode(method.getDefinition().getCode())) {
          pendingMethodMove.add(method);
        }
        return true;
      }
      ProgramMethod nonMovedMethod = pendingMethodMoveInverse.get(method);
      if (nonMovedMethod != null) {
        // Any non-moved code must be a proper pending item.
        assert InvalidCode.isInvalidCode(method.getDefinition().getCode());
        assert !InvalidCode.isInvalidCode(nonMovedMethod.getDefinition().getCode());
        pendingMethodMove.add(nonMovedMethod);
        return true;
      }
    }
    if (desugaring.needsDesugaring(method)) {
      pendingCodeDesugaring.add(method);
      return true;
    }
    return false;
  }

  private void desugar(SyntheticAdditions additions) throws ExecutionException {
    if (pendingCodeDesugaring.isEmpty() && pendingMethodMove.isEmpty()) {
      return;
    }

    // All non-moving methods are ready for tracing post desugar.
    pendingCodeDesugaring.forEach(additions::addMethodWithDesugaredCodeForTracing);
    // Then amend the desugar set with the move methods that need desugaring.
    for (ProgramMethod method : pendingMethodMove) {
      if (desugaring.needsDesugaring(method)) {
        pendingCodeDesugaring.add(method);
      }
    }

    R8CfInstructionDesugaringEventConsumer eventConsumer =
        CfInstructionDesugaringEventConsumer.createForR8(
            appView,
            this::recordLambdaSynthesizingContext,
            this::recordConstantDynamicSynthesizingContext,
            this::recordTwrCloseResourceMethodSynthesizingContext,
            additions,
            (method, companion) -> {
              if (!isMethodLive(method)) {
                // Record the original placement of the companion method such that we can desugar
                // and transfer the code if and when the companion method becomes live.
                pendingMethodMoveInverse.put(companion, method);
              }
            });

    // Prepare desugaring by collecting all the synthetic methods required on program classes.
    ProgramAdditions programAdditions = new ProgramAdditions();
    ThreadUtils.processItems(
        pendingCodeDesugaring,
        method -> desugaring.prepare(method, programAdditions),
        executorService);
    programAdditions.apply(executorService);

    // Then do the actual desugaring.
    ThreadUtils.processItems(
        pendingCodeDesugaring,
        method -> desugaring.desugar(method, additions.getMethodContext(method), eventConsumer),
        executorService);

    // Move the pending methods and mark them live and ready for tracing.
    for (ProgramMethod method : pendingMethodMove) {
      ProgramMethod companion =
          interfaceProcessor
              .getHelper()
              .ensureMethodOfProgramCompanionClassStub(method, eventConsumer);
      interfaceProcessor.finalizeMoveToCompanionMethod(method, companion);
      pendingMethodMoveInverse.remove(companion);
      // TODO(b/199043500): Once "live moved methods" are tracked this can be removed.
      if (!isMethodLive(companion)) {
        additions.addLiveMethod(companion);
      }
      additions.addMethodWithDesugaredCodeForTracing(companion);
    }

    eventConsumer.finalizeDesugaring();

    pendingMethodMove.clear();
    pendingCodeDesugaring.clear();
  }

  private void recordLambdaSynthesizingContext(LambdaClass lambdaClass, ProgramMethod context) {
    synchronized (synthesizingContexts) {
      synthesizingContexts.put(lambdaClass.getLambdaProgramClass(), context);
    }
  }

  private void recordConstantDynamicSynthesizingContext(
      ConstantDynamicClass constantDynamicClass, ProgramMethod context) {
    synchronized (synthesizingContexts) {
      synthesizingContexts.put(constantDynamicClass.getConstantDynamicProgramClass(), context);
    }
  }

  private void recordTwrCloseResourceMethodSynthesizingContext(
      ProgramMethod closeMethod, ProgramMethod context) {
    synchronized (synthesizingContexts) {
      synthesizingContexts.put(closeMethod.getHolder(), context);
    }
  }

  private void synthesizeInterfaceMethodBridges(SyntheticAdditions additions) {
    for (ProgramMethod bridge : syntheticInterfaceMethodBridges.values()) {
      DexProgramClass holder = bridge.getHolder();
      DexEncodedMethod method = bridge.getDefinition();
      holder.addVirtualMethod(method);
    }
    syntheticInterfaceMethodBridges.clear();
  }

  private void finalizeLibraryMethodOverrideInformation() {
    for (DexProgramClass liveType : liveTypes.getItems()) {
      for (DexEncodedMethod method : liveType.virtualMethods()) {
        if (method.isLibraryMethodOverride().isUnknown()) {
          method.setLibraryMethodOverride(OptionalBool.FALSE);
        }
      }
    }
  }

  private boolean verifyKeptGraph() {
    if (appView.options().testing.verifyKeptGraphInfo) {
      for (DexProgramClass liveType : liveTypes.getItems()) {
        assert graphReporter.verifyRootedPath(liveType);
      }
    }
    return true;
  }

  private EnqueuerResult createEnqueuerResult(AppInfoWithClassHierarchy appInfo)
      throws ExecutionException {
    // Compute the set of dead proto types.
    deadProtoTypeCandidates.removeIf(this::isTypeLive);
    Set<DexType> deadProtoTypes =
        SetUtils.newIdentityHashSet(deadProtoTypeCandidates.size() + initialDeadProtoTypes.size());
    deadProtoTypeCandidates.forEach(deadProtoType -> deadProtoTypes.add(deadProtoType.type));
    deadProtoTypes.addAll(initialDeadProtoTypes);

    // Remove the temporary mappings that have been inserted into the field access info collection
    // and verify that the mapping is then one-to-one.
    fieldAccessInfoCollection.removeIf(
        (field, info) -> field != info.getField() || info == MISSING_FIELD_ACCESS_INFO);
    assert fieldAccessInfoCollection.verifyMappingIsOneToOne();

    // Verify all references on the input app before synthesizing definitions.
    assert verifyReferences(appInfo.app());

    // Prune the root set items that turned out to be dead.
    // TODO(b/150736225): Pruning of dead root set items is still incomplete.
    rootSet.pruneDeadItems(appView, this);
    if (mode.isTreeShaking() && appView.hasMainDexRootSet()) {
      assert rootSet != appView.getMainDexRootSet();
      appView.getMainDexRootSet().pruneDeadItems(appView, this);
    }

    // Ensure references from all hard coded factory items.
    appView
        .dexItemFactory()
        .forEachPossiblyCompilerSynthesizedType(this::recordCompilerSynthesizedTypeReference);

    // Rebuild a new app only containing referenced types.
    Set<DexLibraryClass> libraryClasses = Sets.newIdentityHashSet();
    Set<DexClasspathClass> classpathClasses = Sets.newIdentityHashSet();
    // Ensure all referenced non program types have their hierarchy built as live.
    referencedNonProgramTypes.forEach(
        clazz -> addLiveNonProgramType(clazz, false, this::ignoreMissingClasspathOrLibraryClass));
    for (ClasspathOrLibraryClass clazz : liveNonProgramTypes) {
      if (clazz.isLibraryClass()) {
        libraryClasses.add(clazz.asLibraryClass());
      } else if (clazz.isClasspathClass()) {
        classpathClasses.add(clazz.asClasspathClass());
      } else {
        assert false;
      }
    }

    // Add just referenced non-program types. We can't replace the program classes at this point as
    // they are needed in tree pruning.
    DirectMappedDexApplication app =
        appInfo
            .app()
            .asDirect()
            .builder()
            .replaceLibraryClasses(libraryClasses)
            .replaceClasspathClasses(classpathClasses)
            .build();

    // Verify the references on the pruned application after type synthesis.
    assert verifyReferences(app);

    SynthesizingContextOracle lambdaSynthesizingContextOracle =
        syntheticClass -> {
          ProgramMethod lambdaSynthesisContext = synthesizingContexts.get(syntheticClass);
          return lambdaSynthesisContext != null
              ? ImmutableSet.of(lambdaSynthesisContext.getReference())
              : ImmutableSet.of(syntheticClass.getType());
        };
    amendKeepInfoWithCompanionMethods();
    deferredTracing.rewriteApplication(executorService);
    AppInfoWithLiveness appInfoWithLiveness =
        new AppInfoWithLiveness(
            appInfo.getSyntheticItems().commit(app),
            appInfo.getClassToFeatureSplitMap(),
            appInfo.getMainDexInfo(),
            deadProtoTypes,
            mode.isInitialTreeShaking()
                ? missingClassesBuilder.reportMissingClasses(
                    appView, lambdaSynthesizingContextOracle)
                : missingClassesBuilder.assertNoMissingClasses(appView),
            SetUtils.mapIdentityHashSet(liveTypes.getItems(), DexProgramClass::getType),
            Enqueuer.toDescriptorSet(targetedMethods.getItems()),
            failedMethodResolutionTargets,
            failedFieldResolutionTargets,
            bootstrapMethods,
            virtualMethodsTargetedByInvokeDirect,
            toDescriptorSet(liveMethods.getItems()),
            // Filter out library fields and pinned fields, because these are read by default.
            fieldAccessInfoCollection,
            methodAccessInfoCollection.build(),
            objectAllocationInfoCollection.build(appInfo),
            callSites,
            keepInfo,
            rootSet.mayHaveSideEffects,
            rootSet.noSideEffects,
            rootSet.assumedValues,
            amendWithCompanionMethods(rootSet.alwaysInline),
            amendWithCompanionMethods(rootSet.neverInlineDueToSingleCaller),
            amendWithCompanionMethods(rootSet.whyAreYouNotInlining),
            amendWithCompanionMethods(rootSet.reprocess),
            amendWithCompanionMethods(rootSet.neverReprocess),
            rootSet.alwaysClassInline,
            rootSet.neverClassInline,
            noClassMerging,
            rootSet.noVerticalClassMerging,
            rootSet.noHorizontalClassMerging,
            rootSet.neverPropagateValue,
            joinIdentifierNameStrings(rootSet.identifierNameStrings, identifierNameStrings),
            emptySet(),
            Collections.emptyMap(),
            lockCandidates,
            initClassReferences,
            recordFieldValuesReferences);
    appInfo.markObsolete();
    if (options.testing.enqueuerInspector != null) {
      options.testing.enqueuerInspector.accept(appInfoWithLiveness, mode);
    }
    return new EnqueuerResult(appInfoWithLiveness);
  }

  private void forEachCompanionMethod(BiConsumer<DexMethod, DexMethod> consumer) {
    if (interfaceProcessor != null) {
      interfaceProcessor.forEachMethodToMove(consumer);
    }
  }

  private void amendKeepInfoWithCompanionMethods() {
    forEachCompanionMethod(
        (methodReference, companionReference) -> {
          ProgramMethod companion = appView.definitionFor(companionReference).asProgramMethod();
          KeepMethodInfo.Joiner minimumKeepInfoForCompanion =
              keepInfo.getMethodInfo(methodReference, appInfo).joiner();
          KeepMethodInfo.Joiner extraMinimumKeepInfoForCompanion =
              dependentMinimumKeepInfo
                  .getUnconditionalMinimumKeepInfoOrDefault(MinimumKeepInfoCollection.empty())
                  .getOrDefault(methodReference, KeepMethodInfo.newEmptyJoiner())
                  .asMethodJoiner();
          keepInfo.evaluateMethodRule(
              companion, minimumKeepInfoForCompanion.merge(extraMinimumKeepInfoForCompanion));
        });
  }

  private Set<DexMethod> amendWithCompanionMethods(Set<DexMethod> methods) {
    if (methods.isEmpty() || interfaceProcessor == null) {
      return methods;
    }
    Set<DexMethod> companionMethods = Sets.newIdentityHashSet();
    interfaceProcessor.forEachMethodToMove(
        (method, companion) -> {
          if (methods.contains(method)) {
            companionMethods.add(companion);
          }
        });
    methods.addAll(companionMethods);
    return methods;
  }

  private boolean verifyReferences(DexApplication app) {
    WorkList<DexClass> worklist = WorkList.newIdentityWorkList();
    for (DexProgramClass clazz : liveTypes.getItems()) {
      worklist.addIfNotSeen(clazz);
    }
    while (worklist.hasNext()) {
      DexClass clazz = worklist.next();
      assert verifyReferencedType(clazz, worklist, app);
    }
    return true;
  }

  private boolean verifyReferencedType(
      DexType type, WorkList<DexClass> worklist, DexApplication app) {
    if (type.isArrayType()) {
      type = type.toBaseType(appView.dexItemFactory());
    }
    if (!type.isClassType()) {
      return true;
    }
    DexClass clazz = app.definitionFor(type);
    if (clazz == null) {
      assert missingClassesBuilder.contains(type)
          : "Expected type to be in missing types': " + type;
    } else {
      assert !missingClassesBuilder.contains(type)
          : "Type with definition also in missing types: " + type;
      // Eager assert while the context is still present.
      assert clazz.isProgramClass() || liveNonProgramTypes.contains(clazz)
          : "Expected type to be in live non-program types: " + clazz;
      worklist.addIfNotSeen(clazz);
    }
    return true;
  }

  private boolean verifyReferencedType(
      DexClass clazz, WorkList<DexClass> worklist, DexApplication app) {
    for (DexType supertype : clazz.allImmediateSupertypes()) {
      assert verifyReferencedType(supertype, worklist, app);
    }
    assert clazz.isProgramClass() || liveNonProgramTypes.contains(clazz)
        : "Expected type to be in live non-program types: " + clazz;
    if (clazz.isProgramClass()) {
      for (DexEncodedField field : clazz.fields()) {
        if (isFieldReferenced(field)) {
          assert verifyReferencedType(field.getReference().type, worklist, app);
        }
      }
      for (DexEncodedMethod method : clazz.methods()) {
        if (isMethodTargeted(method)) {
          assert verifyReferencedMethod(method, worklist, app);
        }
      }
    }
    return true;
  }

  private boolean verifyReferencedMethod(
      DexEncodedMethod method, WorkList<DexClass> worklist, DexApplication app) {
    assert verifyReferencedType(method.getReference().proto.returnType, worklist, app);
    for (DexType param : method.getReference().proto.parameters.values) {
      assert verifyReferencedType(param, worklist, app);
    }
    return true;
  }

  private static <D extends DexEncodedMember<D, R>, R extends DexMember<D, R>>
      Set<R> toDescriptorSet(Set<D> set) {
    Set<R> result = Sets.newIdentityHashSet();
    for (D item : set) {
      result.add(item.getReference());
    }
    return result;
  }

  private static Object2BooleanMap<DexMember<?, ?>> joinIdentifierNameStrings(
      Set<DexMember<?, ?>> explicit, Set<DexMember<?, ?>> implicit) {
    Object2BooleanMap<DexMember<?, ?>> result = new Object2BooleanArrayMap<>();
    for (DexMember<?, ?> e : explicit) {
      result.putIfAbsent(e, true);
    }
    for (DexMember<?, ?> i : implicit) {
      result.putIfAbsent(i, false);
    }
    return result;
  }

  private void trace(ExecutorService executorService, Timing timing) throws ExecutionException {
    timing.begin("Grow the tree.");
    try {
      while (true) {
        long numberOfLiveItems = getNumberOfLiveItems();
        while (!workList.isEmpty()) {
          EnqueuerAction action = workList.poll();
          action.run(this);
        }

        // Continue fix-point processing if -if rules are enabled by items that newly became live.
        long numberOfLiveItemsAfterProcessing = getNumberOfLiveItems();
        if (numberOfLiveItemsAfterProcessing > numberOfLiveItems) {
          // Build the mapping of active if rules. We use a single collection of if-rules to allow
          // removing if rules that have a constant sequent keep rule when they materialize.
          if (activeIfRules == null) {
            activeIfRules = new HashMap<>();
            IfRuleClassPartEquivalence equivalence = new IfRuleClassPartEquivalence();
            for (ProguardIfRule ifRule : rootSet.ifRules) {
              Wrapper<ProguardIfRule> wrap = equivalence.wrap(ifRule);
              activeIfRules.computeIfAbsent(wrap, ignore -> new LinkedHashSet<>()).add(ifRule);
            }
          }
          ConsequentRootSetBuilder consequentSetBuilder =
              ConsequentRootSet.builder(appView, subtypingInfo, this);
          IfRuleEvaluator ifRuleEvaluator =
              new IfRuleEvaluator(
                  appView,
                  subtypingInfo,
                  this,
                  executorService,
                  activeIfRules,
                  consequentSetBuilder);
          addConsequentRootSet(ifRuleEvaluator.run());
          assert getNumberOfLiveItems() == numberOfLiveItemsAfterProcessing;
          if (!workList.isEmpty()) {
            continue;
          }
        }

        // Continue fix-point processing while there are additional work items to ensure items that
        // are passed to Java reflections are traced.
        if (!pendingReflectiveUses.isEmpty()) {
          pendingReflectiveUses.forEach(this::handleReflectiveBehavior);
          pendingReflectiveUses.clear();
        }
        if (!workList.isEmpty()) {
          continue;
        }

        // Allow deferred tracing to enqueue worklist items.
        if (deferredTracing.enqueueWorklistActions(workList)) {
          assert !workList.isEmpty();
          continue;
        }

        // Notify each analysis that a fixpoint has been reached, and give each analysis an
        // opportunity to add items to the worklist.
        analyses.forEach(analysis -> analysis.notifyFixpoint(this, workList, timing));
        if (!workList.isEmpty()) {
          continue;
        }

        for (DelayedRootSetActionItem delayedRootSetActionItem :
            rootSet.delayedRootSetActionItems) {
          if (delayedRootSetActionItem.isInterfaceMethodSyntheticBridgeAction()) {
            identifySyntheticInterfaceMethodBridges(
                delayedRootSetActionItem.asInterfaceMethodSyntheticBridgeAction());
          }
        }

        synthesize();

        ConsequentRootSet consequentRootSet = computeDelayedInterfaceMethodSyntheticBridges();
        addConsequentRootSet(consequentRootSet);
        rootSet
            .getDependentMinimumKeepInfo()
            .merge(consequentRootSet.getDependentMinimumKeepInfo());
        rootSet.delayedRootSetActionItems.clear();

        if (!workList.isEmpty()) {
          continue;
        }

        // Reached the fixpoint.
        break;
      }

      if (mode.isInitialTreeShaking()) {
        postProcessingDesugaring();
      }

      if (Log.ENABLED) {
        Set<DexEncodedMethod> allLive = Sets.newIdentityHashSet();
        Set<DexEncodedMethod> reachableNotLive = Sets.difference(allLive, liveMethods.getItems());
        Log.debug(getClass(), "%s methods are reachable but not live", reachableNotLive.size());
        Log.info(getClass(), "Only reachable: %s", reachableNotLive);
        SetView<DexEncodedMethod> targetedButNotLive =
            Sets.difference(targetedMethods.getItems(), liveMethods.getItems());
        Log.debug(getClass(), "%s methods are targeted but not live", targetedButNotLive.size());
        Log.info(getClass(), "Targeted but not live: %s", targetedButNotLive);
      }
    } finally {
      timing.end();
    }
  }

  private void postProcessingDesugaring() throws ExecutionException {
    SyntheticAdditions syntheticAdditions =
        new SyntheticAdditions(appView.createProcessorContext());

    assert workList.isEmpty();

    R8PostProcessingDesugaringEventConsumer eventConsumer =
        CfPostProcessingDesugaringEventConsumer.createForR8(
            syntheticAdditions,
            desugaring,
            (context, missing) ->
                missingClassesBuilder.addNewMissingClassWithDesugarDiagnostic(
                    missing,
                    context,
                    new InterfaceDesugarMissingTypeDiagnostic(
                        context.getOrigin(),
                        Position.UNKNOWN,
                        missing.asClassReference(),
                        context.getType().asClassReference(),
                        null)));
    InterfaceMethodProcessorFacade interfaceDesugaring =
        desugaring.getInterfaceMethodPostProcessingDesugaringR8(
            ExcludeDexResources, liveMethods::contains, interfaceProcessor);
    CfPostProcessingDesugaringCollection.create(appView, interfaceDesugaring)
        .postProcessingDesugaring(
            liveTypes.items, liveMethods::contains, eventConsumer, executorService);

    if (syntheticAdditions.isEmpty()) {
      return;
    }

    // Commit the pending synthetics and recompute subtypes.
    appInfo = appInfo.rebuildWithClassHierarchy(app -> app);
    appView.setAppInfo(appInfo);
    subtypingInfo = SubtypingInfo.create(appView);

    syntheticAdditions.enqueueWorkItems(this);

    workList = workList.nonPushable();

    while (!workList.isEmpty()) {
      EnqueuerAction action = workList.poll();
      action.run(this);
    }
  }

  private long getNumberOfLiveItems() {
    long result = liveTypes.getItems().size();
    result += liveMethods.items.size();
    result += liveFields.fields.size();
    return result;
  }

  private void addConsequentRootSet(ConsequentRootSet consequentRootSet) {
    // TODO(b/132600955): This modifies the root set, but the consequent should not be persistent.
    //  Instead, the consequent root set should be added to collections that are owned by the
    //  enqueuer, similar to Enqueuer#dependentMinimumKeepClassInfo.
    rootSet.addConsequentRootSet(consequentRootSet);
    includeMinimumKeepInfo(consequentRootSet);
    consequentRootSet.pendingMethodMoveInverse.forEach(pendingMethodMoveInverse::put);

    // Check for compatibility rules indicating that the holder must be implicitly kept.
    if (forceProguardCompatibility) {
      consequentRootSet.dependentKeepClassCompatRule.forEach(
          (precondition, compatRules) -> {
            assert precondition.isDexType();
            DexProgramClass preconditionHolder =
                asProgramClassOrNull(appInfo().definitionFor(precondition.asDexType()));
            compatEnqueueHolderIfDependentNonStaticMember(preconditionHolder, compatRules);
          });
    }
  }

  private ConsequentRootSet computeDelayedInterfaceMethodSyntheticBridges() {
    RootSetBuilder builder = RootSet.builder(appView, subtypingInfo);
    for (DelayedRootSetActionItem delayedRootSetActionItem : rootSet.delayedRootSetActionItems) {
      if (delayedRootSetActionItem.isInterfaceMethodSyntheticBridgeAction()) {
        handleInterfaceMethodSyntheticBridgeAction(
            delayedRootSetActionItem.asInterfaceMethodSyntheticBridgeAction(), builder);
      }
    }
    return builder.buildConsequentRootSet();
  }

  private final Map<DexMethod, ProgramMethod> syntheticInterfaceMethodBridges =
      new LinkedHashMap<>();

  private void identifySyntheticInterfaceMethodBridges(
      InterfaceMethodSyntheticBridgeAction action) {
    ProgramMethod methodToKeep = action.getMethodToKeep();
    ProgramMethod singleTarget = action.getSingleTarget();
    if (rootSet.isShrinkingDisallowedUnconditionally(singleTarget, options)) {
      return;
    }
    if (methodToKeep != singleTarget
        && !syntheticInterfaceMethodBridges.containsKey(
            methodToKeep.getDefinition().getReference())) {
      syntheticInterfaceMethodBridges.put(
          methodToKeep.getDefinition().getReference(), methodToKeep);
    }
  }

  private void handleInterfaceMethodSyntheticBridgeAction(
      InterfaceMethodSyntheticBridgeAction action, RootSetBuilder builder) {
    ProgramMethod methodToKeep = action.getMethodToKeep();
    ProgramMethod singleTarget = action.getSingleTarget();
    DexEncodedMethod singleTargetMethod = singleTarget.getDefinition();
    if (rootSet.isShrinkingDisallowedUnconditionally(singleTarget, options)) {
      return;
    }
    if (singleTargetMethod.isLibraryMethodOverride().isTrue()) {
      methodToKeep.getDefinition().setLibraryMethodOverride(OptionalBool.TRUE);
    }
    action.getAction().accept(builder);
  }

  void retainAnnotationForFinalTreeShaking(List<MatchedAnnotation> matchedAnnotations) {
    assert mode.isInitialTreeShaking();
    if (annotationRemoverBuilder != null) {
      for (MatchedAnnotation matchedAnnotation : matchedAnnotations) {
        annotationRemoverBuilder.retainAnnotation(matchedAnnotation.getAnnotation());
        workList.enqueueTraceAnnotationAction(
            matchedAnnotation.getAnnotatedItem(),
            matchedAnnotation.getAnnotation(),
            matchedAnnotation.getAnnotatedKind());
      }
    }
  }

  // Package protected due to entry point from worklist.
  void markMethodAsKept(ProgramMethod target, KeepReason reason) {
    DexEncodedMethod definition = target.getDefinition();
    DexProgramClass holder = target.getHolder();
    DexMethod reference = target.getReference();
    markMethodAsTargeted(target, reason);
    if (definition.isVirtualMethod()) {
      // A virtual method. Mark it as reachable so that subclasses, if instantiated, keep
      // their overrides. However, we don't mark it live, as a keep rule might not imply that
      // the corresponding class is live.
      markVirtualMethodAsReachable(reference, holder.isInterface(), target, reason);
      // When generating interface bridges the method may be inserted into a live hierarchy.
      // If so we need to also mark it as live as the reachable check above will not reprocess the
      // hierarchy.
      if (definition.isNonAbstractVirtualMethod()
          && objectAllocationInfoCollection.isInstantiatedDirectlyOrHasInstantiatedSubtype(
              holder)) {
        // TODO(b/120959039): Codify the kept-graph expectations for these cases in tests.
        markVirtualMethodAsLive(target, reason);
      }
    } else {
      markDirectStaticOrConstructorMethodAsLive(target, reason);
    }
  }

  // Package protected due to entry point from worklist.
  void markFieldAsKept(ProgramField field, KeepReason reason) {
    if (field.getDefinition().isStatic()) {
      markFieldAsLive(field, field, reason);
    } else {
      workList.enqueueMarkFieldAsReachableAction(field, field, reason);
    }
  }

  private boolean shouldMarkLibraryMethodOverrideAsReachable(LookupTarget override) {
    if (override.isLambdaTarget()) {
      return true;
    }
    ProgramMethod programMethod = override.asMethodTarget().getTarget().asProgramMethod();
    if (programMethod == null) {
      return false;
    }
    DexProgramClass clazz = programMethod.getHolder();
    DexEncodedMethod method = programMethod.getDefinition();
    assert method.isVirtualMethod();

    if (method.isAbstract() || method.isPrivateMethod()) {
      return false;
    }

    if (appView.isClassEscapingIntoLibrary(clazz.type)) {
      return true;
    }

    // If there is an instantiated subtype of `clazz` that escapes into the library and does not
    // override `method` then we need to mark the method as being reachable.
    Set<DexProgramClass> immediateSubtypes = getImmediateSubtypesInInstantiatedHierarchy(clazz);
    if (immediateSubtypes.isEmpty()) {
      return false;
    }
    Deque<DexProgramClass> worklist = new ArrayDeque<>(immediateSubtypes);
    Set<DexProgramClass> visited = SetUtils.newIdentityHashSet(immediateSubtypes);

    while (!worklist.isEmpty()) {
      DexProgramClass current = worklist.removeFirst();
      assert visited.contains(current);

      if (current.lookupVirtualMethod(method.getReference()) != null) {
        continue;
      }

      if (appView.isClassEscapingIntoLibrary(current.type)) {
        return true;
      }

      for (DexProgramClass subtype : getImmediateSubtypesInInstantiatedHierarchy(current)) {
        if (visited.add(subtype)) {
          worklist.add(subtype);
        }
      }
    }

    return false;
  }

  private Set<DexProgramClass> getImmediateSubtypesInInstantiatedHierarchy(DexProgramClass clazz) {
    Set<DexClass> subtypes =
        objectAllocationInfoCollection.getImmediateSubtypesInInstantiatedHierarchy(clazz.type);
    if (subtypes == null) {
      return emptySet();
    }
    Set<DexProgramClass> programClasses = SetUtils.newIdentityHashSet(subtypes.size());
    for (DexClass subtype : subtypes) {
      if (subtype.isProgramClass()) {
        programClasses.add(subtype.asProgramClass());
      }
    }
    return programClasses;
  }

  // Package protected due to entry point from worklist.
  void markMethodAsLive(ProgramMethod method, ProgramDefinition context) {
    assert liveMethods.contains(method);

    DexEncodedMethod definition = method.getDefinition();
    assert !definition.getOptimizationInfo().forceInline();

    if (definition.isStatic()) {
      markDirectAndIndirectClassInitializersAsLive(method.getHolder());
    }

    traceNonDesugaredCode(method);

    ProgramMethodSet superCallTargets = superInvokeDependencies.get(method.getDefinition());
    if (superCallTargets != null) {
      for (ProgramMethod superCallTarget : superCallTargets) {
        if (Log.ENABLED) {
          Log.verbose(getClass(), "Found super invoke constraint on `%s`.", superCallTarget);
        }
        markMethodAsTargeted(superCallTarget, KeepReason.invokedViaSuperFrom(method));
        markVirtualMethodAsLive(superCallTarget, KeepReason.invokedViaSuperFrom(method));
      }
    }

    // Notify analyses.
    analyses.forEach(analysis -> analysis.processNewlyLiveMethod(method, context, this, workList));
  }

  private void markMethodAsTargeted(ProgramMethod method, KeepReason reason) {
    if (!addTargetedMethod(method, reason)) {
      // Already targeted.
      return;
    }

    if (!liveMethods.contains(method)) {
      traceMethodDefinitionExcludingCode(method);
    }

    if (forceProguardCompatibility) {
      // Keep targeted default methods in compatibility mode. The tree pruner will otherwise make
      // these methods abstract, whereas Proguard does not (seem to) touch their code.
      if (!method.getAccessFlags().isAbstract() && method.getHolder().isInterface()) {
        markMethodAsLiveWithCompatRule(method);
      }
    }
    analyses.forEach(analysis -> analysis.notifyMarkMethodAsTargeted(method, workList));
  }

  void traceMethodDefinitionExcludingCode(ProgramMethod method) {
    markReferencedTypesAsLive(method);
    processAnnotations(method);
    method
        .getDefinition()
        .getParameterAnnotations()
        .forEachAnnotation(
            annotation -> processAnnotation(method, annotation, AnnotatedKind.PARAMETER));

    // Update keep info.
    applyMinimumKeepInfo(method);
    if (hasAlternativeLibraryDefinition(method.getHolder())
        && !method.getDefinition().isPrivateMethod()) {
      getKeepInfo().keepMethod(method);
    }
  }

  private void traceNonDesugaredCode(ProgramMethod method) {
    if (getMode().isInitialTreeShaking()) {
      if (addToPendingDesugaring(method)) {
        return;
      }
    }

    traceCode(method);
  }

  void traceCode(ProgramMethod method) {
    DefaultEnqueuerUseRegistry registry =
        useRegistryFactory.create(appView, method, this, appView.apiLevelCompute());
    method.registerCodeReferences(registry);
    // Notify analyses.
    analyses.forEach(analysis -> analysis.processTracedCode(method, registry, workList));
  }

  private void markReferencedTypesAsLive(ProgramMethod method) {
    markTypeAsLive(method.getHolder(), method);
    markParameterAndReturnTypesAsLive(method);
  }

  private void markParameterAndReturnTypesAsLive(ProgramMethod method) {
    for (DexType parameterType : method.getDefinition().getParameters()) {
      markTypeAsLive(parameterType, method);
    }
    markTypeAsLive(method.getDefinition().returnType(), method);
  }

  private void markClassAsInstantiatedWithReason(DexProgramClass clazz, KeepReason reason) {
    workList.enqueueMarkInstantiatedAction(clazz, null, InstantiationReason.REFLECTION, reason);
    if (clazz.hasDefaultInitializer()) {
      ProgramMethod defaultInitializer = clazz.getProgramDefaultInitializer();
      workList.enqueueMarkReachableDirectAction(
          defaultInitializer.getReference(), defaultInitializer, reason);
    }
  }

  private void markClassAsInstantiatedWithCompatRule(
      DexProgramClass clazz, Supplier<KeepReason> reasonSupplier) {
    assert forceProguardCompatibility;

    if (!addCompatInstantiatedClass(clazz)) {
      return;
    }

    KeepReasonWitness witness = graphReporter.registerClass(clazz, reasonSupplier.get());
    if (clazz.isAnnotation()) {
      markTypeAsLive(clazz, witness);
    } else if (clazz.isInterface()) {
      markInterfaceAsInstantiated(clazz, witness);
    } else {
      workList.enqueueMarkInstantiatedAction(clazz, null, InstantiationReason.KEEP_RULE, witness);
      if (clazz.hasDefaultInitializer()) {
        ProgramMethod defaultInitializer = clazz.getProgramDefaultInitializer();
        workList.enqueueMarkReachableDirectAction(
            defaultInitializer.getReference(),
            defaultInitializer,
            graphReporter.reportCompatKeepDefaultInitializer(defaultInitializer));
      }
    }
  }

  private boolean addCompatInstantiatedClass(DexProgramClass clazz) {
    assert forceProguardCompatibility;

    // During the first round of tree shaking, we compat-instantiate all classes referenced from
    // check-cast, const-class, and instance-of instructions.
    if (mode.isInitialTreeShaking()) {
      proguardCompatibilityActionsBuilder.addCompatInstantiatedType(clazz);
      return true;
    }

    assert proguardCompatibilityActionsBuilder == null;

    // Otherwise, we only compat-instantiate classes referenced from check-cast, const-class, and
    // instance-of instructions that were also compat-instantiated during the first round of tree
    // shaking.
    return appView.hasProguardCompatibilityActions()
        && appView.getProguardCompatibilityActions().isCompatInstantiated(clazz);
  }

  private void markMethodAsLiveWithCompatRule(ProgramMethod method) {
    workList.enqueueMarkMethodLiveAction(
        method, method, graphReporter.reportCompatKeepMethod(method));
  }

  private void handleReflectiveBehavior(ProgramMethod method) {
    IRCode code = method.buildIR(appView);
    InstructionIterator iterator = code.instructionIterator();
    while (iterator.hasNext()) {
      Instruction instruction = iterator.next();
      handleReflectiveBehavior(method, instruction);
    }
  }

  private void handleReflectiveBehavior(ProgramMethod method, Instruction instruction) {
    if (!instruction.isInvokeMethod()) {
      return;
    }
    InvokeMethod invoke = instruction.asInvokeMethod();
    DexMethod invokedMethod = invoke.getInvokedMethod();
    DexItemFactory dexItemFactory = appView.dexItemFactory();
    if (invokedMethod == dexItemFactory.classMethods.newInstance) {
      handleJavaLangClassNewInstance(method, invoke);
      return;
    }
    if (invokedMethod == dexItemFactory.constructorMethods.newInstance) {
      handleJavaLangReflectConstructorNewInstance(method, invoke);
      return;
    }
    if (invokedMethod == dexItemFactory.enumMembers.valueOf) {
      handleJavaLangEnumValueOf(method, invoke);
      return;
    }
    if (invokedMethod == dexItemFactory.proxyMethods.newProxyInstance) {
      handleJavaLangReflectProxyNewProxyInstance(method, invoke);
      return;
    }
    if (dexItemFactory.serviceLoaderMethods.isLoadMethod(invokedMethod)) {
      handleServiceLoaderInvocation(method, invoke);
      return;
    }
    if (!isReflectionMethod(dexItemFactory, invokedMethod)) {
      return;
    }
    IdentifierNameStringLookupResult<?> identifierLookupResult =
        identifyIdentifier(invoke, appView, method);
    if (identifierLookupResult == null) {
      return;
    }
    DexReference referencedItem = identifierLookupResult.getReference();
    if (referencedItem.isDexType()) {
      assert identifierLookupResult.isTypeResult();
      IdentifierNameStringTypeLookupResult identifierTypeLookupResult =
          identifierLookupResult.asTypeResult();
      DexProgramClass clazz =
          getProgramClassOrNullFromReflectiveAccess(referencedItem.asDexType(), method);
      if (clazz == null) {
        return;
      }
      markTypeAsLive(clazz, KeepReason.reflectiveUseIn(method));
      if (clazz.canBeInstantiatedByNewInstance()
          && identifierTypeLookupResult.isTypeCompatInstantiatedFromUse(options)) {
        markClassAsInstantiatedWithCompatRule(clazz, () -> KeepReason.reflectiveUseIn(method));
      } else if (identifierTypeLookupResult.isTypeInitializedFromUse()) {
        markDirectAndIndirectClassInitializersAsLive(clazz);
      }
      // To ensure we are not moving the class because we cannot prune it when there is a reflective
      // use of it.
      if (keepInfo.getClassInfo(clazz).isShrinkingAllowed(options)) {
        keepInfo.joinClass(clazz, joiner -> joiner.disallowOptimization().disallowShrinking());
      }
    } else if (referencedItem.isDexField()) {
      DexField field = referencedItem.asDexField();
      DexProgramClass clazz = getProgramClassOrNullFromReflectiveAccess(field.holder, method);
      if (clazz == null) {
        return;
      }
      DexEncodedField encodedField = clazz.lookupField(field);
      if (encodedField == null) {
        return;
      }
      // Normally, we generate a -keepclassmembers rule for the field, such that the field is only
      // kept if it is a static field, or if the holder or one of its subtypes are instantiated.
      // However, if the invoked method is a field updater, then we always need to keep instance
      // fields since the creation of a field updater throws a NoSuchFieldException if the field
      // is not present.
      boolean keepClass =
          !encodedField.isStatic()
              && dexItemFactory.atomicFieldUpdaterMethods.isFieldUpdater(invokedMethod);
      if (keepClass) {
        workList.enqueueMarkInstantiatedAction(
            clazz, null, InstantiationReason.REFLECTION, KeepReason.reflectiveUseIn(method));
      }
      if (keepInfo.getFieldInfo(encodedField, clazz).isShrinkingAllowed(options)) {
        ProgramField programField = new ProgramField(clazz, encodedField);
        keepInfo.joinField(
            programField, joiner -> joiner.disallowOptimization().disallowShrinking());
        markFieldAsKept(programField, KeepReason.reflectiveUseIn(method));
      }
    } else {
      assert referencedItem.isDexMethod();
      DexMethod targetedMethodReference = referencedItem.asDexMethod();
      DexProgramClass clazz =
          getProgramClassOrNullFromReflectiveAccess(targetedMethodReference.holder, method);
      if (clazz == null) {
        return;
      }
      ProgramMethod targetedMethod = clazz.lookupProgramMethod(targetedMethodReference);
      if (targetedMethod == null) {
        return;
      }
      KeepReason reason = KeepReason.reflectiveUseIn(method);
      if (targetedMethod.getDefinition().belongsToDirectPool()) {
        markMethodAsTargeted(targetedMethod, reason);
        markDirectStaticOrConstructorMethodAsLive(targetedMethod, reason);
      } else {
        markVirtualMethodAsLive(targetedMethod, reason);
      }
      applyMinimumKeepInfoWhenLiveOrTargeted(
          targetedMethod, KeepMethodInfo.newEmptyJoiner().disallowOptimization());
    }
  }

  /** Handles reflective uses of {@link Class#newInstance()}. */
  private void handleJavaLangClassNewInstance(ProgramMethod method, InvokeMethod invoke) {
    if (!invoke.isInvokeVirtual()) {
      assert false;
      return;
    }

    DexType instantiatedType =
        ConstantValueUtils.getDexTypeRepresentedByValue(
            invoke.asInvokeVirtual().getReceiver(), appView);
    if (instantiatedType == null || !instantiatedType.isClassType()) {
      // Give up, we can't tell which class is being instantiated, or the type is not a class type.
      // The latter should not happen in practice.
      return;
    }

    DexProgramClass clazz = getProgramClassOrNullFromReflectiveAccess(instantiatedType, method);
    if (clazz == null) {
      return;
    }
    ProgramMethod defaultInitializer = clazz.getProgramDefaultInitializer();
    if (defaultInitializer != null) {
      KeepReason reason = KeepReason.reflectiveUseIn(method);
      markClassAsInstantiatedWithReason(clazz, reason);
      markMethodAsTargeted(defaultInitializer, reason);
      markDirectStaticOrConstructorMethodAsLive(defaultInitializer, reason);
      applyMinimumKeepInfoWhenLiveOrTargeted(
          defaultInitializer, KeepMethodInfo.newEmptyJoiner().disallowOptimization());
    }
  }

  /** Handles reflective uses of {@link java.lang.reflect.Constructor#newInstance(Object...)}. */
  private void handleJavaLangReflectConstructorNewInstance(
      ProgramMethod method, InvokeMethod invoke) {
    if (!invoke.isInvokeVirtual()) {
      assert false;
      return;
    }

    Value constructorValue = invoke.asInvokeVirtual().getReceiver().getAliasedValue();
    if (constructorValue.isPhi() || !constructorValue.definition.isInvokeVirtual()) {
      // Give up, we can't tell which class is being instantiated.
      return;
    }

    InvokeVirtual constructorDefinition = constructorValue.definition.asInvokeVirtual();
    DexMethod invokedMethod = constructorDefinition.getInvokedMethod();
    if (invokedMethod != appView.dexItemFactory().classMethods.getConstructor
        && invokedMethod != appView.dexItemFactory().classMethods.getDeclaredConstructor) {
      // Give up, we can't tell which constructor is being invoked.
      return;
    }

    DexType instantiatedType =
        ConstantValueUtils.getDexTypeRepresentedByValue(
            constructorDefinition.getReceiver(), appView);
    if (instantiatedType == null || !instantiatedType.isClassType()) {
      // Give up, we can't tell which constructor is being invoked, or the type is not a class type.
      // The latter should not happen in practice.
      return;
    }

    DexProgramClass clazz = getProgramClassOrNullFromReflectiveAccess(instantiatedType, method);
    if (clazz == null) {
      return;
    }
    Value parametersValue = constructorDefinition.inValues().get(1);
    if (parametersValue.isPhi() || !parametersValue.definition.isNewArrayEmpty()) {
      // Give up, we can't tell which constructor is being invoked.
      return;
    }

    Value parametersSizeValue = parametersValue.definition.asNewArrayEmpty().size();
    if (parametersSizeValue.isPhi() || !parametersSizeValue.definition.isConstNumber()) {
      // Give up, we can't tell which constructor is being invoked.
      return;
    }

    ProgramMethod initializer = null;

    int parametersSize = parametersSizeValue.definition.asConstNumber().getIntValue();
    if (parametersSize == 0) {
      initializer = clazz.getProgramDefaultInitializer();
    } else {
      DexType[] parameterTypes = new DexType[parametersSize];
      int missingIndices = parametersSize;
      for (Instruction user : parametersValue.uniqueUsers()) {
        if (user.isArrayPut()) {
          ArrayPut arrayPutInstruction = user.asArrayPut();
          if (arrayPutInstruction.array() != parametersValue) {
            return;
          }

          Value indexValue = arrayPutInstruction.index();
          if (indexValue.isPhi() || !indexValue.definition.isConstNumber()) {
            return;
          }
          int index = indexValue.definition.asConstNumber().getIntValue();
          if (index >= parametersSize) {
            return;
          }

          DexType type =
              ConstantValueUtils.getDexTypeRepresentedByValue(arrayPutInstruction.value(), appView);
          if (type == null) {
            return;
          }

          if (parameterTypes[index] == type) {
            continue;
          }
          if (parameterTypes[index] != null) {
            return;
          }
          parameterTypes[index] = type;
          missingIndices--;
        }
      }

      if (missingIndices == 0) {
        initializer = clazz.getProgramInitializer(parameterTypes);
      }
    }

    if (initializer != null) {
      KeepReason reason = KeepReason.reflectiveUseIn(method);
      markClassAsInstantiatedWithReason(clazz, reason);
      markMethodAsTargeted(initializer, reason);
      markDirectStaticOrConstructorMethodAsLive(initializer, reason);
      applyMinimumKeepInfoWhenLiveOrTargeted(
          initializer, KeepMethodInfo.newEmptyJoiner().disallowOptimization());
    }
  }

  /**
   * Handles reflective uses of {@link java.lang.reflect.Proxy#newProxyInstance(ClassLoader,
   * Class[], InvocationHandler)}.
   */
  private void handleJavaLangReflectProxyNewProxyInstance(
      ProgramMethod method, InvokeMethod invoke) {
    if (!invoke.isInvokeStatic()) {
      assert false;
      return;
    }

    Value interfacesValue = invoke.arguments().get(1);
    if (interfacesValue.isPhi() || !interfacesValue.definition.isNewArrayEmpty()) {
      // Give up, we can't tell which interfaces the proxy implements.
      return;
    }

    WorkList<DexProgramClass> worklist = WorkList.newIdentityWorkList();
    for (Instruction user : interfacesValue.uniqueUsers()) {
      if (!user.isArrayPut()) {
        continue;
      }

      ArrayPut arrayPut = user.asArrayPut();
      DexType type = ConstantValueUtils.getDexTypeRepresentedByValue(arrayPut.value(), appView);
      if (type == null || !type.isClassType()) {
        continue;
      }

      DexProgramClass clazz = getProgramClassOrNullFromReflectiveAccess(type, method);
      if (clazz != null && clazz.isInterface()) {
        KeepReason reason = KeepReason.reflectiveUseIn(method);
        markInterfaceAsInstantiated(clazz, graphReporter.registerClass(clazz, reason));
        worklist.addIfNotSeen(clazz);
      }
    }

    while (worklist.hasNext()) {
      DexProgramClass clazz = worklist.next();
      assert clazz.isInterface();

      // Keep this interface to ensure that we do not merge the interface into its unique subtype,
      // or merge other interfaces into it horizontally.
      keepInfo.joinClass(clazz, joiner -> joiner.disallowOptimization().disallowShrinking());

      // Also keep all of its virtual methods to ensure that the devirtualizer does not perform
      // illegal rewritings of invoke-interface instructions into invoke-virtual instructions.
      if (mode.isInitialTreeShaking()) {
        KeepReason reason = KeepReason.reflectiveUseIn(method);
        clazz.forEachProgramVirtualMethod(
            virtualMethod -> {
              keepInfo.joinMethod(
                  virtualMethod, joiner -> joiner.disallowOptimization().disallowShrinking());
              markVirtualMethodAsReachable(virtualMethod.getReference(), true, method, reason);
            });
      }

      // Repeat for all super interfaces.
      for (DexType implementedType : clazz.getInterfaces()) {
        DexProgramClass implementedClass =
            asProgramClassOrNull(definitionFor(implementedType, clazz));
        if (implementedClass != null && implementedClass.isInterface()) {
          worklist.addIfNotSeen(implementedClass);
        }
      }
    }
  }

  private void handleJavaLangEnumValueOf(ProgramMethod method, InvokeMethod invoke) {
    // The use of java.lang.Enum.valueOf(java.lang.Class, java.lang.String) will indirectly
    // access the values() method of the enum class passed as the first argument. The method
    // SomeEnumClass.valueOf(java.lang.String) which is generated by javac for all enums will
    // call this method.
    if (invoke.inValues().get(0).isConstClass()) {
      DexType type = invoke.inValues().get(0).definition.asConstClass().getValue();
      DexProgramClass clazz = getProgramClassOrNull(type, method);
      if (clazz != null && clazz.isEnum()) {
        markEnumValuesAsReachable(clazz, KeepReason.invokedFrom(method));
      }
    }
  }

  private void handleServiceLoaderInvocation(ProgramMethod method, InvokeMethod invoke) {
    if (invoke.inValues().size() == 0) {
      // Should never happen.
      return;
    }

    Value argument = invoke.inValues().get(0).getAliasedValue();
    if (!argument.isPhi() && argument.definition.isConstClass()) {
      DexType serviceType = argument.definition.asConstClass().getValue();
      if (!appView.appServices().allServiceTypes().contains(serviceType)) {
        // Should never happen.
        if (Log.ENABLED) {
          options.reporter.warning(
              new StringDiagnostic(
                  "The type `"
                      + serviceType.toSourceString()
                      + "` is being passed to the method `"
                      + invoke.getInvokedMethod().toSourceString()
                      + "`, but was not found in `META-INF/services/`.",
                  method.getOrigin()));
        }
        return;
      }

      handleServiceInstantiation(serviceType, method, KeepReason.reflectiveUseIn(method));
    } else {
      KeepReason reason = KeepReason.reflectiveUseIn(method);
      for (DexType serviceType : appView.appServices().allServiceTypes()) {
        handleServiceInstantiation(serviceType, method, reason);
      }
    }
  }

  private void handleServiceInstantiation(
      DexType serviceType, ProgramMethod context, KeepReason reason) {
    List<DexType> serviceImplementationTypes =
        appView.appServices().serviceImplementationsFor(serviceType);
    DexMethodSignatureSet serviceMethods = getServiceMethods(serviceType, context);
    for (DexType serviceImplementationType : serviceImplementationTypes) {
      if (!serviceImplementationType.isClassType()) {
        // Should never happen.
        continue;
      }

      DexProgramClass serviceImplementationClass =
          getProgramClassOrNull(serviceImplementationType, context);
      if (serviceImplementationClass == null) {
        continue;
      }

      markClassAsInstantiatedWithReason(serviceImplementationClass, reason);

      ProgramMethod defaultInitializer = serviceImplementationClass.getProgramDefaultInitializer();
      if (defaultInitializer != null) {
        applyMinimumKeepInfoWhenLiveOrTargeted(
            defaultInitializer, KeepMethodInfo.newEmptyJoiner().disallowOptimization());
      }

      for (DexMethodSignature serviceMethod : serviceMethods) {
        ProgramMethod serviceImplementationMethod =
            asProgramMethodOrNull(
                serviceImplementationClass.getMethodCollection().getMethod(serviceMethod),
                serviceImplementationClass);
        if (serviceImplementationMethod != null) {
          applyMinimumKeepInfoWhenLiveOrTargeted(
              serviceImplementationMethod, KeepMethodInfo.newEmptyJoiner().disallowOptimization());
        }
      }
    }
  }

  private DexMethodSignatureSet getServiceMethods(DexType serviceType, ProgramMethod context) {
    DexMethodSignatureSet serviceMethods = DexMethodSignatureSet.create();
    WorkList<DexType> serviceTypes = WorkList.newIdentityWorkList(serviceType);
    while (serviceTypes.hasNext()) {
      DexType current = serviceTypes.next();
      DexClass clazz = getClassOrNullFromReflectiveAccess(current, context);
      if (clazz == null) {
        continue;
      }
      clazz.forEachClassMethodMatching(DexEncodedMethod::belongsToVirtualPool, serviceMethods::add);
      serviceTypes.addIfNotSeen(clazz.getInterfaces());
    }
    return serviceMethods;
  }

  private static class SetWithReportedReason<T> {

    private final Set<T> items = Sets.newIdentityHashSet();
    private final Map<T, List<Action>> deferredActions = new IdentityHashMap<>();

    boolean add(T item, KeepReasonWitness witness) {
      assert witness != null;
      if (items.add(item)) {
        deferredActions.getOrDefault(item, Collections.emptyList()).forEach(Action::execute);
        return true;
      }
      return false;
    }

    boolean contains(T item) {
      return items.contains(item);
    }

    boolean registerDeferredAction(T item, Action action) {
      if (!items.contains(item)) {
        deferredActions.computeIfAbsent(item, ignore -> new ArrayList<>()).add(action);
        return true;
      }
      return false;
    }

    Set<T> getItems() {
      return Collections.unmodifiableSet(items);
    }
  }

  private class LiveFieldsSet {

    private final Set<DexEncodedField> fields = Sets.newIdentityHashSet();

    private final BiConsumer<DexEncodedField, KeepReason> register;

    LiveFieldsSet(BiConsumer<DexEncodedField, KeepReason> register) {
      this.register = register;
    }

    boolean add(ProgramField field, KeepReason reason) {
      DexEncodedField definition = field.getDefinition();
      register.accept(definition, reason);
      transitionUnusedInterfaceToLive(field.getHolder());
      return fields.add(definition);
    }

    boolean contains(DexEncodedField field) {
      return fields.contains(field);
    }

    boolean contains(ProgramField field) {
      return contains(field.getDefinition());
    }
  }

  private class LiveMethodsSet {

    private final Set<DexEncodedMethod> items = Sets.newIdentityHashSet();

    private final BiConsumer<DexEncodedMethod, KeepReason> register;

    LiveMethodsSet(BiConsumer<DexEncodedMethod, KeepReason> register) {
      this.register = register;
    }

    boolean add(ProgramMethod method, KeepReason reason) {
      DexEncodedMethod definition = method.getDefinition();
      register.accept(definition, reason);
      transitionUnusedInterfaceToLive(method.getHolder());
      return items.add(definition);
    }

    boolean contains(DexEncodedMethod method) {
      return items.contains(method);
    }

    boolean contains(ProgramMethod method) {
      return contains(method.getDefinition());
    }

    Set<DexEncodedMethod> getItems() {
      return Collections.unmodifiableSet(items);
    }
  }

  private class AnnotationReferenceMarker implements IndexedItemCollection {

    private final ProgramDefinition context;
    private final KeepReason reason;

    private AnnotationReferenceMarker(DexAnnotation annotation, ProgramDefinition context) {
      this.context = context;
      this.reason = KeepReason.referencedInAnnotation(annotation, context);
    }

    @Override
    public boolean addClass(DexProgramClass dexProgramClass) {
      return false;
    }

    @Override
    public boolean addField(DexField fieldReference) {
      recordFieldReference(fieldReference, context);
      DexProgramClass holder = getProgramHolderOrNull(fieldReference, context);
      if (holder == null) {
        return false;
      }
      ProgramField field = holder.lookupProgramField(fieldReference);
      if (field == null) {
        return false;
      }
      // There is no dispatch on annotations, so only keep what is directly referenced.
      if (field.getReference() != fieldReference) {
        return false;
      }
      if (field.getDefinition().isStatic()) {
        FieldAccessInfoImpl fieldAccessInfo =
            fieldAccessInfoCollection.contains(fieldReference)
                ? fieldAccessInfoCollection.get(fieldReference)
                : fieldAccessInfoCollection.extend(
                    fieldReference, new FieldAccessInfoImpl(fieldReference));
        fieldAccessInfo.setReadFromAnnotation();
        markFieldAsLive(field, context, reason);
        // When an annotation has a field of an enum type the JVM will use the values() method on
        // that enum class if the field is referenced.
        if (options.isGeneratingClassFiles() && field.getHolder().isEnum()) {
          markEnumValuesAsReachable(field.getHolder(), reason);
        }
      } else {
        // There is no dispatch on annotations, so only keep what is directly referenced.
        workList.enqueueMarkFieldAsReachableAction(field, context, reason);
      }
      return false;
    }

    @Override
    public boolean addMethod(DexMethod method) {
      // Record the references in case they are not program types.
      recordMethodReference(method, context);
      DexProgramClass holder = getProgramHolderOrNull(method, context);
      if (holder == null) {
        return false;
      }
      DexEncodedMethod target = holder.lookupDirectMethod(method);
      if (target != null) {
        // There is no dispatch on annotations, so only keep what is directly referenced.
        if (target.getReference() == method) {
          markDirectStaticOrConstructorMethodAsLive(new ProgramMethod(holder, target), reason);
        }
      } else {
        target = holder.lookupVirtualMethod(method);
        // There is no dispatch on annotations, so only keep what is directly referenced.
        if (target != null && target.getReference() == method) {
          markMethodAsTargeted(new ProgramMethod(holder, target), reason);
        }
      }
      return false;
    }

    @Override
    public boolean addString(DexString string) {
      return false;
    }

    @Override
    public boolean addProto(DexProto proto) {
      return false;
    }

    @Override
    public boolean addCallSite(DexCallSite callSite) {
      return false;
    }

    @Override
    public boolean addMethodHandle(DexMethodHandle methodHandle) {
      return false;
    }

    @Override
    public boolean addType(DexType type) {
      markTypeAsLive(type, context, reason);
      return false;
    }
  }

  public static class EnqueuerDefinitionSupplier {

    private final Enqueuer enqueuer;

    EnqueuerDefinitionSupplier(Enqueuer enqueuer) {
      this.enqueuer = enqueuer;
    }

    public DexClass definitionFor(DexType type, ProgramDefinition context) {
      return enqueuer.definitionFor(
          type, context, enqueuer::recordNonProgramClass, enqueuer::ignoreMissingClass);
    }
  }

  public static class ResolutionSearchKey {

    private final DexMethod method;
    private final boolean isInterface;

    private ResolutionSearchKey(DexMethod method, boolean isInterface) {
      this.method = method;
      this.isInterface = isInterface;
    }

    @Override
    public boolean equals(Object o) {
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      ResolutionSearchKey that = (ResolutionSearchKey) o;
      return method == that.method && isInterface == that.isInterface;
    }

    @Override
    public int hashCode() {
      return Objects.hash(method, isInterface);
    }
  }
}
