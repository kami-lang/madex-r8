// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.dex;

import static com.android.tools.r8.utils.LineNumberOptimizer.runAndWriteMap;

import com.android.tools.r8.ByteBufferProvider;
import com.android.tools.r8.ByteDataView;
import com.android.tools.r8.DataDirectoryResource;
import com.android.tools.r8.DataEntryResource;
import com.android.tools.r8.DataResourceConsumer;
import com.android.tools.r8.DataResourceProvider;
import com.android.tools.r8.DataResourceProvider.Visitor;
import com.android.tools.r8.DexFilePerClassFileConsumer;
import com.android.tools.r8.DexIndexedConsumer;
import com.android.tools.r8.ProgramConsumer;
import com.android.tools.r8.ResourceException;
import com.android.tools.r8.SourceFileEnvironment;
import com.android.tools.r8.debuginfo.DebugRepresentation;
import com.android.tools.r8.debuginfo.DebugRepresentation.DebugRepresentationPredicate;
import com.android.tools.r8.dex.FileWriter.ByteBufferResult;
import com.android.tools.r8.dex.VirtualFile.FilePerInputClassDistributor;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.features.FeatureSplitConfiguration.DataResourceProvidersAndConsumer;
import com.android.tools.r8.graph.AppServices;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexAnnotation;
import com.android.tools.r8.graph.DexAnnotationDirectory;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexDebugInfoForWriting;
import com.android.tools.r8.graph.DexEncodedArray;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexTypeList;
import com.android.tools.r8.graph.DexValue;
import com.android.tools.r8.graph.DexWritableCode;
import com.android.tools.r8.graph.EnclosingMethodAttribute;
import com.android.tools.r8.graph.InnerClassAttribute;
import com.android.tools.r8.graph.ObjectToOffsetMapping;
import com.android.tools.r8.graph.ParameterAnnotationsList;
import com.android.tools.r8.naming.NamingLens;
import com.android.tools.r8.naming.ProguardMapSupplier.ProguardMapId;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.shaking.MainDexInfo;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.ArrayUtils;
import com.android.tools.r8.utils.Box;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.ExceptionUtils;
import com.android.tools.r8.utils.InternalGlobalSyntheticsProgramConsumer.InternalGlobalSyntheticsDexConsumer;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.OriginalSourceFiles;
import com.android.tools.r8.utils.PredicateUtils;
import com.android.tools.r8.utils.StringDiagnostic;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.ThreadUtils;
import com.android.tools.r8.utils.Timing;
import com.android.tools.r8.utils.Timing.TimingMerger;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ObjectArrays;
import it.unimi.dsi.fastutil.objects.Reference2LongMap;
import it.unimi.dsi.fastutil.objects.Reference2LongOpenHashMap;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public class ApplicationWriter {

  public final AppView<?> appView;
  public final NamingLens namingLens;
  public final InternalOptions options;
  private final CodeToKeep desugaredLibraryCodeToKeep;
  private final Predicate<DexType> isTypeMissing;
  public List<Marker> markers;
  public List<DexString> markerStrings;
  public Set<VirtualFile> globalSyntheticFiles;

  public DexIndexedConsumer programConsumer;
  public InternalGlobalSyntheticsDexConsumer globalsSyntheticsConsumer;

  private static class SortAnnotations extends MixedSectionCollection {

    private final NamingLens namingLens;

    public SortAnnotations(NamingLens namingLens) {
      this.namingLens = namingLens;
    }

    @Override
    public boolean add(DexAnnotationSet dexAnnotationSet) {
      // Annotation sets are sorted by annotation types.
      dexAnnotationSet.sort(namingLens);
      return true;
    }

    @Override
    public boolean add(DexAnnotation annotation) {
      // The elements of encoded annotation must be sorted by name.
      annotation.annotation.sort();
      return true;
    }

    @Override
    public boolean add(DexEncodedArray dexEncodedArray) {
      // Dex values must potentially be sorted, eg, for DexValueAnnotation.
      for (DexValue value : dexEncodedArray.values) {
        value.sort();
      }
      return true;
    }

    @Override
    public boolean add(DexProgramClass dexClassData) {
      return true;
    }

    @Override
    public boolean add(DexEncodedMethod method, DexWritableCode dexCode) {
      return true;
    }

    @Override
    public boolean add(DexDebugInfoForWriting dexDebugInfo) {
      return true;
    }

    @Override
    public boolean add(DexTypeList dexTypeList) {
      return true;
    }

    @Override
    public boolean add(ParameterAnnotationsList parameterAnnotationsList) {
      return true;
    }

    @Override
    public boolean setAnnotationsDirectoryForClass(DexProgramClass clazz,
        DexAnnotationDirectory annotationDirectory) {
      return true;
    }
  }

  public ApplicationWriter(
      AppView<?> appView,
      List<Marker> markers,
      NamingLens namingLens) {
    this(
        appView,
        markers,
        namingLens,
        null);
  }

  public ApplicationWriter(
      AppView<?> appView,
      List<Marker> markers,
      NamingLens namingLens,
      DexIndexedConsumer consumer) {
    this.appView = appView;
    this.options = appView.options();
    this.desugaredLibraryCodeToKeep = CodeToKeep.createCodeToKeep(options, namingLens);
    this.markers = markers;
    this.namingLens = namingLens;
    this.programConsumer = consumer;
    this.isTypeMissing =
        PredicateUtils.isNull(appView.appInfo()::definitionForWithoutExistenceAssert);
  }

  private List<VirtualFile> distribute(ExecutorService executorService)
      throws ExecutionException, IOException {
    Collection<DexProgramClass> classes = appView.appInfo().classes();
    Collection<DexProgramClass> globalSynthetics = new ArrayList<>();
    if (appView.options().intermediate && appView.options().hasGlobalSyntheticsConsumer()) {
      Collection<DexProgramClass> allClasses = classes;
      classes = new ArrayList<>(allClasses.size());
      for (DexProgramClass clazz : allClasses) {
        if (appView.getSyntheticItems().isGlobalSyntheticClass(clazz)) {
          globalSynthetics.add(clazz);
        } else {
          classes.add(clazz);
        }
      }
    }

    // Distribute classes into dex files.
    VirtualFile.Distributor distributor;
    if (options.isGeneratingDexFilePerClassFile()) {
      distributor =
          new VirtualFile.FilePerInputClassDistributor(
              this,
              classes,
              options.getDexFilePerClassFileConsumer().combineSyntheticClassesWithPrimaryClass());
    } else if (!options.canUseMultidex()
        && options.mainDexKeepRules.isEmpty()
        && appView.appInfo().getMainDexInfo().isEmpty()
        && options.enableMainDexListCheck) {
      distributor = new VirtualFile.MonoDexDistributor(this, classes, options);
    } else {
      distributor = new VirtualFile.FillFilesDistributor(this, classes, options, executorService);
    }

    List<VirtualFile> virtualFiles = distributor.run();
    if (!globalSynthetics.isEmpty()) {
      List<VirtualFile> files =
          new FilePerInputClassDistributor(this, globalSynthetics, false).run();
      globalSyntheticFiles = new HashSet<>(files);
      virtualFiles.addAll(globalSyntheticFiles);
      globalsSyntheticsConsumer =
          new InternalGlobalSyntheticsDexConsumer(options.getGlobalSyntheticsConsumer());
    }
    return virtualFiles;
  }

  /**
   * For each class within a virtual file, this function insert a string that contains the
   * checksum information about that class.
   *
   * This needs to be done after distribute but before dex string sorting.
   */
  private void encodeChecksums(Iterable<VirtualFile> files) {
    Collection<DexProgramClass> classes = appView.appInfo().classes();
    Reference2LongMap<DexString> inputChecksums = new Reference2LongOpenHashMap<>(classes.size());
    for (DexProgramClass clazz : classes) {
      inputChecksums.put(namingLens.lookupDescriptor(clazz.getType()), clazz.getChecksum());
    }
    for (VirtualFile file : files) {
      ClassesChecksum toWrite = new ClassesChecksum();
      for (DexProgramClass clazz : file.classes()) {
        DexString desc = namingLens.lookupDescriptor(clazz.type);
        toWrite.addChecksum(desc.toString(), inputChecksums.getLong(desc));
      }
      file.injectString(appView.dexItemFactory().createString(toWrite.toJsonString()));
    }
  }

  private boolean willComputeProguardMap() {
    return options.proguardMapConsumer != null;
  }

  /** Writer that never needs the input app to deal with mapping info for kotlin. */
  public void write(ExecutorService executorService) throws IOException, ExecutionException {
    assert !willComputeProguardMap();
    write(executorService, null);
  }

  public void write(ExecutorService executorService, AndroidApp inputApp)
      throws IOException, ExecutionException {
    Timing timing = appView.appInfo().app().timing;

    timing.begin("DexApplication.write");

    Box<ProguardMapId> delayedProguardMapId = new Box<>();
    List<LazyDexString> lazyDexStrings = new ArrayList<>();
    computeMarkerStrings(delayedProguardMapId, lazyDexStrings);
    OriginalSourceFiles originalSourceFiles =
        computeSourceFileString(delayedProguardMapId, lazyDexStrings);

    try {
      timing.begin("Insert Attribute Annotations");
      // TODO(b/151313715): Move this to the writer threads.
      insertAttributeAnnotations();
      timing.end();

      // Each DexCallSite must have its instruction offset set for sorting.
      if (options.isGeneratingDex()) {
        timing.begin("Set call-site contexts");
        setCallSiteContexts(executorService);
        timing.end();
      }

      // Generate the dex file contents.
      timing.begin("Distribute");
      List<VirtualFile> virtualFiles = distribute(executorService);
      timing.end();
      if (options.encodeChecksums) {
        timing.begin("Encode checksums");
        encodeChecksums(virtualFiles);
        timing.end();
      }
      assert markers == null
          || markers.isEmpty()
          || appView.dexItemFactory().extractMarkers() != null;
      assert appView.withProtoShrinker(
          shrinker -> virtualFiles.stream().allMatch(shrinker::verifyDeadProtoTypesNotReferenced),
          true);

      // TODO(b/151313617): Sorting annotations mutates elements so run single threaded on main.
      timing.begin("Sort Annotations");
      SortAnnotations sortAnnotations = new SortAnnotations(namingLens);
      appView.appInfo().classes().forEach((clazz) -> clazz.addDependencies(sortAnnotations));
      timing.end();

      {
        // Compute offsets and rewrite jumbo strings so that code offsets are fixed.
        TimingMerger merger =
            timing.beginMerger("Pre-write phase", ThreadUtils.getNumberOfThreads(executorService));
        Collection<Timing> timings =
            ThreadUtils.processItemsWithResults(
                virtualFiles,
                virtualFile -> {
                  Timing fileTiming = Timing.create("VirtualFile " + virtualFile.getId(), options);
                  computeOffsetMappingAndRewriteJumboStrings(
                      virtualFile, lazyDexStrings, fileTiming);
                  DebugRepresentation.computeForFile(
                      virtualFile, appView.graphLens(), namingLens, options);
                  fileTiming.end();
                  return fileTiming;
                },
                executorService);
        merger.add(timings);
        merger.end();
      }


      // Now code offsets are fixed, compute the mapping file content.
      if (willComputeProguardMap()) {
        // TODO(b/220999985): Refactor line number optimization to be per file and thread it above.
        DebugRepresentationPredicate representation =
            DebugRepresentation.fromFiles(virtualFiles, options);
        delayedProguardMapId.set(
            runAndWriteMap(
                inputApp, appView, namingLens, timing, originalSourceFiles, representation));
      }

      // With the mapping id/hash known, it is safe to compute the remaining dex strings.
      timing.begin("Compute lazy strings");
      List<DexString> forcedStrings = new ArrayList<>();
      for (LazyDexString lazyDexString : lazyDexStrings) {
        forcedStrings.add(lazyDexString.compute());
      }
      timing.end();

      {
        // Write the actual dex code.
        TimingMerger merger =
            timing.beginMerger("Write files", ThreadUtils.getNumberOfThreads(executorService));
        Collection<Timing> timings =
            ThreadUtils.processItemsWithResults(
                virtualFiles,
                virtualFile -> {
                  Timing fileTiming = Timing.create("VirtualFile " + virtualFile.getId(), options);
                  writeVirtualFile(virtualFile, fileTiming, forcedStrings);
                  fileTiming.end();
                  return fileTiming;
                },
                executorService);
        merger.add(timings);
        merger.end();
        if (globalsSyntheticsConsumer != null) {
          globalsSyntheticsConsumer.finished(options.reporter);
        }
      }

      // A consumer can manage the generated keep rules.
      if (options.desugaredLibraryKeepRuleConsumer != null && !desugaredLibraryCodeToKeep.isNop()) {
        assert !options.isDesugaredLibraryCompilation();
        desugaredLibraryCodeToKeep.generateKeepRules(options);
      }
      // Fail if there are pending errors, e.g., the program consumers may have reported errors.
      options.reporter.failIfPendingErrors();
      // Supply info to all additional resource consumers.
      supplyAdditionalConsumers(appView.appInfo().app(), appView, namingLens, options);
    } finally {
      timing.end();
    }
  }

  private void computeMarkerStrings(
      Box<ProguardMapId> delayedProguardMapId, List<LazyDexString> lazyDexStrings) {
    if (markers != null && !markers.isEmpty()) {
      int firstNonLazyMarker = 0;
      if (willComputeProguardMap()) {
        firstNonLazyMarker++;
        lazyDexStrings.add(
            new LazyDexString() {

              @Override
              public DexString internalCompute() {
                Marker marker = markers.get(0);
                marker.setPgMapId(delayedProguardMapId.get().getId());
                return marker.toDexString(appView.dexItemFactory());
              }
            });
      }
      markerStrings = new ArrayList<>(markers.size() - firstNonLazyMarker);
      for (int i = firstNonLazyMarker; i < markers.size(); i++) {
        markerStrings.add(markers.get(i).toDexString(appView.dexItemFactory()));
      }
    }
  }

  private OriginalSourceFiles computeSourceFileString(
      Box<ProguardMapId> delayedProguardMapId, List<LazyDexString> lazyDexStrings) {
    if (options.sourceFileProvider == null) {
      return OriginalSourceFiles.fromClasses();
    }
    if (!willComputeProguardMap()) {
      rewriteSourceFile(null);
      return OriginalSourceFiles.unreachable();
    }
    // Clear all source files so as not to collect the original files.
    Collection<DexProgramClass> classes = appView.appInfo().classes();
    Map<DexType, DexString> originalSourceFiles = new HashMap<>(classes.size());
    for (DexProgramClass clazz : classes) {
      DexString originalSourceFile = clazz.getSourceFile();
      if (originalSourceFile != null) {
        originalSourceFiles.put(clazz.getType(), originalSourceFile);
        clazz.setSourceFile(null);
      }
    }
    // Add a lazy dex string computation to defer construction of the actual string.
    lazyDexStrings.add(
        new LazyDexString() {
          @Override
          public DexString internalCompute() {
            return rewriteSourceFile(delayedProguardMapId.get());
          }
        });

    return OriginalSourceFiles.fromMap(originalSourceFiles);
  }

  public static SourceFileEnvironment createSourceFileEnvironment(ProguardMapId proguardMapId) {
    if (proguardMapId == null) {
      return new SourceFileEnvironment() {
        @Override
        public String getMapId() {
          return null;
        }

        @Override
        public String getMapHash() {
          return null;
        }
      };
    }
    return new SourceFileEnvironment() {
      @Override
      public String getMapId() {
        return proguardMapId.getId();
      }

      @Override
      public String getMapHash() {
        return proguardMapId.getHash();
      }
    };
  }

  private DexString rewriteSourceFile(ProguardMapId proguardMapId) {
    assert options.sourceFileProvider != null;
    SourceFileEnvironment environment = createSourceFileEnvironment(proguardMapId);
    String sourceFile = options.sourceFileProvider.get(environment);
    DexString dexSourceFile =
        sourceFile == null ? null : options.itemFactory.createString(sourceFile);
    appView.appInfo().classes().forEach(clazz -> clazz.setSourceFile(dexSourceFile));
    return dexSourceFile;
  }

  private void computeOffsetMappingAndRewriteJumboStrings(
      VirtualFile virtualFile, List<LazyDexString> lazyDexStrings, Timing timing) {
    if (virtualFile.isEmpty()) {
      return;
    }
    timing.begin("Compute object offset mapping");
    virtualFile.computeMapping(appView, namingLens, lazyDexStrings.size(), timing);
    timing.end();
    timing.begin("Rewrite jumbo strings");
    rewriteCodeWithJumboStrings(
        virtualFile.getObjectMapping(), virtualFile.classes(), appView.appInfo().app());
    timing.end();
  }

  private void writeVirtualFile(
      VirtualFile virtualFile, Timing timing, List<DexString> forcedStrings) {
    if (virtualFile.isEmpty()) {
      return;
    }

    ProgramConsumer consumer;
    ByteBufferProvider byteBufferProvider;

    if (globalSyntheticFiles != null && globalSyntheticFiles.contains(virtualFile)) {
      consumer = globalsSyntheticsConsumer;
      byteBufferProvider = globalsSyntheticsConsumer;
    } else if (programConsumer != null) {
      consumer = programConsumer;
      byteBufferProvider = programConsumer;
    } else if (virtualFile.getPrimaryClassDescriptor() != null) {
      consumer = options.getDexFilePerClassFileConsumer();
      byteBufferProvider = options.getDexFilePerClassFileConsumer();
    } else {
      if (virtualFile.getFeatureSplit() != null) {
        ProgramConsumer featureConsumer = virtualFile.getFeatureSplit().getProgramConsumer();
        assert featureConsumer instanceof DexIndexedConsumer;
        consumer = featureConsumer;
        byteBufferProvider = (DexIndexedConsumer) featureConsumer;
      } else {
        consumer = options.getDexIndexedConsumer();
        byteBufferProvider = options.getDexIndexedConsumer();
      }
    }

    timing.begin("Reindex for lazy strings");
    ObjectToOffsetMapping objectMapping = virtualFile.getObjectMapping();
    objectMapping.computeAndReindexForLazyDexStrings(forcedStrings);
    timing.end();

    timing.begin("Write bytes");
    ByteBufferResult result = writeDexFile(objectMapping, byteBufferProvider, timing);
    ByteDataView data =
        new ByteDataView(result.buffer.array(), result.buffer.arrayOffset(), result.length);
    timing.end();
    timing.begin("Pass bytes to consumer");
    if (consumer instanceof DexFilePerClassFileConsumer) {
      ((DexFilePerClassFileConsumer) consumer)
          .accept(
              virtualFile.getPrimaryClassDescriptor(),
              data,
              virtualFile.getClassDescriptors(),
              options.reporter);
    } else {
      ((DexIndexedConsumer) consumer)
          .accept(virtualFile.getId(), data, virtualFile.getClassDescriptors(), options.reporter);
    }
    timing.end();
    // Release use of the backing buffer now that accept has returned.
    data.invalidate();
    byteBufferProvider.releaseByteBuffer(result.buffer.asByteBuffer());
  }

  public static void supplyAdditionalConsumers(
      DexApplication application,
      AppView<?> appView,
      NamingLens namingLens,
      InternalOptions options) {
    if (options.configurationConsumer != null) {
      ExceptionUtils.withConsumeResourceHandler(
          options.reporter, options.configurationConsumer,
          options.getProguardConfiguration().getParsedConfiguration());
      ExceptionUtils.withFinishedResourceHandler(options.reporter, options.configurationConsumer);
    }
    if (options.mainDexListConsumer != null) {
      ExceptionUtils.withConsumeResourceHandler(
          options.reporter, options.mainDexListConsumer, writeMainDexList(appView, namingLens));
      ExceptionUtils.withFinishedResourceHandler(options.reporter, options.mainDexListConsumer);
    }

    DataResourceConsumer dataResourceConsumer = options.dataResourceConsumer;
    if (dataResourceConsumer != null) {
      ImmutableList<DataResourceProvider> dataResourceProviders = application.dataResourceProviders;
      ResourceAdapter resourceAdapter =
          new ResourceAdapter(appView, application.dexItemFactory, namingLens, options);

      adaptAndPassDataResources(
          options, dataResourceConsumer, dataResourceProviders, resourceAdapter);

      // Write the META-INF/services resources. Sort on service names and keep the order from
      // the input for the implementation lines for deterministic output.
      if (!appView.appServices().isEmpty()) {
        appView
            .appServices()
            .visit(
                (DexType service, List<DexType> implementations) -> {
                  String serviceName =
                      DescriptorUtils.descriptorToJavaType(
                          namingLens.lookupDescriptor(service).toString());
                  dataResourceConsumer.accept(
                      DataEntryResource.fromBytes(
                          StringUtils.lines(
                                  implementations.stream()
                                      .map(namingLens::lookupDescriptor)
                                      .map(DexString::toString)
                                      .map(DescriptorUtils::descriptorToJavaType)
                                      .collect(Collectors.toList()))
                              .getBytes(),
                          AppServices.SERVICE_DIRECTORY_NAME + serviceName,
                          Origin.unknown()),
                      options.reporter);
                });
      }
    }

    if (options.featureSplitConfiguration != null) {
      for (DataResourceProvidersAndConsumer entry :
          options.featureSplitConfiguration.getDataResourceProvidersAndConsumers()) {
        ResourceAdapter resourceAdapter =
            new ResourceAdapter(appView, application.dexItemFactory, namingLens, options);
        adaptAndPassDataResources(
            options, entry.getConsumer(), entry.getProviders(), resourceAdapter);
      }
    }
  }

  private static void adaptAndPassDataResources(
      InternalOptions options,
      DataResourceConsumer dataResourceConsumer,
      Collection<DataResourceProvider> dataResourceProviders,
      ResourceAdapter resourceAdapter) {
    Set<String> generatedResourceNames = new HashSet<>();

    for (DataResourceProvider dataResourceProvider : dataResourceProviders) {
      try {
        dataResourceProvider.accept(
            new Visitor() {
              @Override
              public void visit(DataDirectoryResource directory) {
                DataDirectoryResource adapted = resourceAdapter.adaptIfNeeded(directory);
                if (adapted != null) {
                  dataResourceConsumer.accept(adapted, options.reporter);
                  options.reporter.failIfPendingErrors();
                }
              }

              @Override
              public void visit(DataEntryResource file) {
                if (resourceAdapter.isService(file)) {
                  // META-INF/services resources are handled below.
                  return;
                }

                DataEntryResource adapted = resourceAdapter.adaptIfNeeded(file);
                if (generatedResourceNames.add(adapted.getName())) {
                  dataResourceConsumer.accept(adapted, options.reporter);
                } else {
                  options.reporter.warning(
                      new StringDiagnostic("Resource '" + file.getName() + "' already exists."));
                }
                options.reporter.failIfPendingErrors();
              }
            });
      } catch (ResourceException e) {
        throw new CompilationError(e.getMessage(), e);
      }
    }
  }

  private void insertAttributeAnnotations() {
    // Convert inner-class attributes to DEX annotations
    for (DexProgramClass clazz : appView.appInfo().classes()) {
      insertAttributeAnnotationsForClass(clazz);
      clazz.fields().forEach(this::insertAttributeAnnotationsForField);
      clazz.methods().forEach(this::insertAttributeAnnotationsForMethod);
    }
  }

  private void insertAttributeAnnotationsForClass(DexProgramClass clazz) {
    EnclosingMethodAttribute enclosingMethod = clazz.getEnclosingMethodAttribute();
    List<InnerClassAttribute> innerClasses = clazz.getInnerClasses();
    if (enclosingMethod == null
        && innerClasses.isEmpty()
        && clazz.getClassSignature().hasNoSignature()) {
      return;
    }

    // EnclosingMember translates directly to an enclosing class/method if present.
    List<DexAnnotation> annotations = new ArrayList<>(2 + innerClasses.size());
    if (enclosingMethod != null) {
      if (enclosingMethod.getEnclosingMethod() != null) {
        annotations.add(
            DexAnnotation.createEnclosingMethodAnnotation(
                enclosingMethod.getEnclosingMethod(), options.itemFactory));
      } else {
        // At this point DEX can't distinguish between local classes and member classes based on
        // the enclosing class annotation itself.
        annotations.add(
            DexAnnotation.createEnclosingClassAnnotation(
                enclosingMethod.getEnclosingClass(), options.itemFactory));
      }
    }

    // Each inner-class entry becomes a inner-class (or inner-class & enclosing-class pair) if
    // it relates to the present class. If it relates to the outer-type (and is named) it becomes
    // part of the member-classes annotation.
    if (!innerClasses.isEmpty()) {
      List<DexType> memberClasses = new ArrayList<>(innerClasses.size());
      for (InnerClassAttribute innerClass : innerClasses) {
        if (clazz.type == innerClass.getInner()) {
          if (enclosingMethod == null
              && (innerClass.getOuter() == null || innerClass.isAnonymous())) {
            options.warningMissingEnclosingMember(
                clazz.type, clazz.origin, clazz.getInitialClassFileVersion());
          } else {
            annotations.add(
                DexAnnotation.createInnerClassAnnotation(
                    namingLens.lookupInnerName(innerClass, options),
                    innerClass.getAccess(),
                    options.itemFactory));
            if (innerClass.getOuter() != null && innerClass.isNamed()) {
              annotations.add(
                  DexAnnotation.createEnclosingClassAnnotation(
                      innerClass.getOuter(), options.itemFactory));
            }
          }
        } else if (clazz.type == innerClass.getOuter() && innerClass.isNamed()) {
          memberClasses.add(innerClass.getInner());
        }
      }
      if (!memberClasses.isEmpty()) {
        annotations.add(
            DexAnnotation.createMemberClassesAnnotation(memberClasses, options.itemFactory));
      }
    }

    if (clazz.getClassSignature().hasSignature()) {
      annotations.add(
          DexAnnotation.createSignatureAnnotation(
              clazz.getClassSignature().toRenamedString(namingLens, isTypeMissing),
              options.itemFactory));
    }

    if (!annotations.isEmpty()) {
      // Append the annotations to annotations array of the class.
      DexAnnotation[] copy =
          ObjectArrays.concat(
              clazz.annotations().annotations,
              annotations.toArray(DexAnnotation.EMPTY_ARRAY),
              DexAnnotation.class);
      clazz.setAnnotations(DexAnnotationSet.create(copy));
    }

    // Clear the attribute structures now that they are represented in annotations.
    clazz.clearEnclosingMethodAttribute();
    clazz.clearInnerClasses();
    clazz.clearClassSignature();
  }

  private void insertAttributeAnnotationsForField(DexEncodedField field) {
    if (field.getGenericSignature().hasNoSignature()) {
      return;
    }
    // Append the annotations to annotations array of the field.
    field.setAnnotations(
        DexAnnotationSet.create(
            ArrayUtils.appendSingleElement(
                field.annotations().annotations,
                DexAnnotation.createSignatureAnnotation(
                    field.getGenericSignature().toRenamedString(namingLens, isTypeMissing),
                    options.itemFactory))));
    field.clearGenericSignature();
  }

  private void insertAttributeAnnotationsForMethod(DexEncodedMethod method) {
    if (method.getGenericSignature().hasNoSignature()) {
      return;
    }
    // Append the annotations to annotations array of the method.
    method.setAnnotations(
        DexAnnotationSet.create(
            ArrayUtils.appendSingleElement(
                method.annotations().annotations,
                DexAnnotation.createSignatureAnnotation(
                    method.getGenericSignature().toRenamedString(namingLens, isTypeMissing),
                    options.itemFactory))));
    method.clearGenericSignature();
  }

  private void setCallSiteContexts(ExecutorService executorService) throws ExecutionException {
    ThreadUtils.processItems(
        appView.appInfo().classes(), this::setCallSiteContexts, executorService);
  }

  private void setCallSiteContexts(DexProgramClass clazz) {
    clazz.forEachProgramMethodMatching(
        DexEncodedMethod::hasCode,
        method -> method.getDefinition().getCode().asDexWritableCode().setCallSiteContexts(method));
  }

  /**
   * Rewrites the code for all methods in the given file so that they use JumboString for at least
   * the strings that require it in mapping.
   *
   * <p>If run multiple times on a class, the lowest index that is required to be a JumboString will
   * be used.
   */
  private void rewriteCodeWithJumboStrings(
      ObjectToOffsetMapping mapping,
      Collection<DexProgramClass> classes,
      DexApplication application) {
    // Do not bail out early if forcing jumbo string processing.
    if (!options.testing.forceJumboStringProcessing) {
      // If there are no strings with jumbo indices at all this is a no-op.
      if (!mapping.hasJumboStrings()) {
        return;
      }
      // If the globally highest sorting string is not a jumbo string this is also a no-op.
      if (application.highestSortingString != null
          && application.highestSortingString.compareTo(mapping.getFirstJumboString()) < 0) {
        return;
      }
    }
    for (DexProgramClass clazz : classes) {
      clazz.forEachProgramMethodMatching(
          DexEncodedMethod::hasCode,
          method -> {
            DexWritableCode code = method.getDefinition().getCode().asDexWritableCode();
            DexWritableCode rewrittenCode =
                code.rewriteCodeWithJumboStrings(
                    method,
                    mapping,
                    application.dexItemFactory,
                    options.testing.forceJumboStringProcessing);
            method.setCode(rewrittenCode.asCode(), appView);
          });
    }
  }

  private ByteBufferResult writeDexFile(
      ObjectToOffsetMapping objectMapping, ByteBufferProvider provider, Timing timing) {
    FileWriter fileWriter =
        new FileWriter(
            provider,
            objectMapping,
            appView.appInfo(),
            options,
            namingLens,
            desugaredLibraryCodeToKeep);
    // Collect the non-fixed sections.
    timing.time("collect", fileWriter::collect);
    // Generate and write the bytes.
    return timing.time("generate", fileWriter::generate);
  }

  private static String mapMainDexListName(DexType type, NamingLens namingLens) {
    return DescriptorUtils.descriptorToJavaType(namingLens.lookupDescriptor(type).toString())
        .replace('.', '/') + ".class";
  }

  private static String writeMainDexList(AppView<?> appView, NamingLens namingLens) {
    // TODO(b/178231294): Clean up by streaming directly to the consumer.
    MainDexInfo mainDexInfo = appView.appInfo().getMainDexInfo();
    StringBuilder builder = new StringBuilder();
    List<DexType> list = new ArrayList<>(mainDexInfo.size());
    mainDexInfo.forEach(list::add);
    list.sort(DexType::compareTo);
    list.forEach(
        type -> builder.append(mapMainDexListName(type, namingLens)).append('\n'));
    return builder.toString();
  }

  public abstract static class LazyDexString {
    private boolean computed = false;

    public abstract DexString internalCompute();

    public final DexString compute() {
      assert !computed;
      DexString value = internalCompute();
      computed = true;
      return value;
    }
  }
}
