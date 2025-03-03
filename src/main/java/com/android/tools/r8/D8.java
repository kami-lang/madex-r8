// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import static com.android.tools.r8.D8Command.USAGE_MESSAGE;
import static com.android.tools.r8.utils.AssertionUtils.forTesting;
import static com.android.tools.r8.utils.ExceptionUtils.unwrapExecutionException;

import com.android.tools.r8.androidapi.ApiReferenceStubber;
import com.android.tools.r8.dex.ApplicationReader;
import com.android.tools.r8.dex.ApplicationWriter;
import com.android.tools.r8.dex.Marker;
import com.android.tools.r8.dex.Marker.Tool;
import com.android.tools.r8.graph.AppInfo;
import com.android.tools.r8.graph.AppServices;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.LazyLoadedDexApplication;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.graph.analysis.ClassInitializerAssertionEnablingAnalysis;
import com.android.tools.r8.horizontalclassmerging.HorizontalClassMerger;
import com.android.tools.r8.inspector.internal.InspectorImpl;
import com.android.tools.r8.ir.conversion.IRConverter;
import com.android.tools.r8.ir.desugar.TypeRewriter;
import com.android.tools.r8.ir.desugar.desugaredlibrary.DesugaredLibraryAmender;
import com.android.tools.r8.ir.optimize.AssertionsRewriter;
import com.android.tools.r8.ir.optimize.info.OptimizationFeedbackSimple;
import com.android.tools.r8.jar.CfApplicationWriter;
import com.android.tools.r8.kotlin.KotlinMetadataRewriter;
import com.android.tools.r8.naming.NamingLens;
import com.android.tools.r8.naming.PrefixRewritingNamingLens;
import com.android.tools.r8.naming.RecordRewritingNamingLens;
import com.android.tools.r8.naming.signature.GenericSignatureRewriter;
import com.android.tools.r8.origin.CommandLineOrigin;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.shaking.MainDexInfo;
import com.android.tools.r8.synthesis.SyntheticFinalization;
import com.android.tools.r8.synthesis.SyntheticItems;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.CfgPrinter;
import com.android.tools.r8.utils.ExceptionUtils;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.InternalOptions.DesugarState;
import com.android.tools.r8.utils.StringDiagnostic;
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.ThreadUtils;
import com.android.tools.r8.utils.Timing;
import com.google.common.collect.ImmutableList;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

/**
 * The D8 dex compiler.
 *
 * <p>D8 performs modular compilation to DEX bytecode. It supports compilation of Java bytecode and
 * Android DEX bytecode to DEX bytecode including merging a mix of these input formats.
 *
 * <p>The D8 dexer API is intentionally limited and should "do the right thing" given a command. If
 * this API does not suffice please contact the D8/R8 team.
 *
 * <p>The compiler is invoked by calling {@link #run(D8Command) D8.run} with an appropriate {@link
 * D8Command}. For example:
 *
 * <pre>
 *   D8.run(D8Command.builder()
 *       .addProgramFiles(inputPathA, inputPathB)
 *       .setOutput(outputPath, OutputMode.DexIndexed)
 *       .build());
 * </pre>
 *
 * The above reads the input files denoted by {@code inputPathA} and {@code inputPathB}, compiles
 * them to DEX bytecode (compiling from Java bytecode for such inputs and merging for DEX inputs),
 * and then writes the result to the directory or zip archive specified by {@code outputPath}.
 */
@Keep
public final class D8 {

  private D8() {}

  /**
   * Main API entry for the D8 dexer.
   *
   * @param command D8 command.
   */
  public static void run(D8Command command) throws CompilationFailedException {
    AndroidApp app = command.getInputApp();
    InternalOptions options = command.getInternalOptions();
    ExecutorService executor = ThreadUtils.getExecutorService(options);
    ExceptionUtils.withD8CompilationHandler(
        command.getReporter(),
        () -> {
          try {
            run(app, options, executor);
          } finally {
            executor.shutdown();
          }
        });
  }

  /**
   * Main API entry for the D8 dexer with a externally supplied executor service.
   *
   * @param command D8 command.
   * @param executor executor service from which to get threads for multi-threaded processing.
   */
  public static void run(D8Command command, ExecutorService executor)
      throws CompilationFailedException {
    AndroidApp app = command.getInputApp();
    InternalOptions options = command.getInternalOptions();
    ExceptionUtils.withD8CompilationHandler(
        command.getReporter(),
        () -> {
          run(app, options, executor);
        });
  }

  private static void run(String[] args) throws CompilationFailedException {
    D8Command command = D8Command.parse(args, CommandLineOrigin.INSTANCE).build();
    if (command.isPrintHelp()) {
      System.out.println(USAGE_MESSAGE);
      return;
    }
    if (command.isPrintVersion()) {
      System.out.println("D8 " + Version.getVersionString());
      return;
    }
    InternalOptions options = command.getInternalOptions();
    AndroidApp app = command.getInputApp();
    runForTesting(app, options);
  }

  /**
   * Command-line entry to D8.
   *
   * <p>See {@link D8Command#USAGE_MESSAGE} or run {@code d8 --help} for usage information.
   */
  public static void main(String[] args) {
    if (args.length == 0) {
      throw new RuntimeException(StringUtils.joinLines("Invalid invocation.", USAGE_MESSAGE));
    }
    ExceptionUtils.withMainProgramHandler(() -> run(args));
  }

  static void runForTesting(AndroidApp inputApp, InternalOptions options)
      throws CompilationFailedException {
    ExecutorService executor = ThreadUtils.getExecutorService(options);
    ExceptionUtils.withD8CompilationHandler(
        options.reporter,
        () -> {
          try {
            run(inputApp, options, executor);
          } finally {
            executor.shutdown();
          }
        });
  }

  private static AppView<AppInfo> readApp(
      AndroidApp inputApp, InternalOptions options, ExecutorService executor, Timing timing)
      throws IOException {
    timing.begin("Application read");
    ApplicationReader applicationReader = new ApplicationReader(inputApp, options, timing);
    LazyLoadedDexApplication app = applicationReader.read(executor);
    timing.end();
    timing.begin("Load desugared lib");
    options.loadMachineDesugaredLibrarySpecification(timing, app);
    timing.end();
    TypeRewriter typeRewriter = options.getTypeRewriter();
    AppInfo appInfo =
        timing.time(
            "Create app-info",
            () -> AppInfo.createInitialAppInfo(app, applicationReader.readMainDexClasses(app)));
    return timing.time("Create app-view", () -> AppView.createForD8(appInfo, typeRewriter, timing));
  }

  private static void run(AndroidApp inputApp, InternalOptions options, ExecutorService executor)
      throws IOException {
    if (options.printMemory) {
      // Run GC twice to remove objects with finalizers.
      System.gc();
      System.gc();
      Runtime runtime = Runtime.getRuntime();
      System.out.println("D8 is running with total memory:" + runtime.totalMemory());
      System.out.println("D8 is running with free memory:" + runtime.freeMemory());
      System.out.println("D8 is running with max memory:" + runtime.maxMemory());
    }
    Timing timing = Timing.create("D8", options);
    try {
      // Synthetic assertion to check that testing assertions works and can be enabled.
      assert forTesting(options, () -> !options.testing.testEnableTestAssertions);

      timing.begin("Read input app");
      AppView<AppInfo> appView = readApp(inputApp, options, executor, timing);
      timing.end();
      timing.begin("Desugared library amend");
      DesugaredLibraryAmender.run(appView);
      timing.end();
      timing.begin("Collect input synthetics");
      SyntheticItems.collectSyntheticInputs(appView);
      timing.end();

      final CfgPrinter printer = options.printCfg ? new CfgPrinter() : null;

      if (AssertionsRewriter.isEnabled(options)) {
        // Run analysis to mark all <clinit> methods having the javac generated assertion
        // enabling code.
        ClassInitializerAssertionEnablingAnalysis analysis =
            new ClassInitializerAssertionEnablingAnalysis(
                appView, OptimizationFeedbackSimple.getInstance());
        ThreadUtils.processItems(
            appView.appInfo().classes(),
            clazz -> {
              ProgramMethod classInitializer = clazz.getProgramClassInitializer();
              if (classInitializer != null) {
                analysis.processNewlyLiveMethod(classInitializer, clazz, null, null);
              }
            },
            executor);
      }

      if (options.testing.enableD8ResourcesPassThrough) {
        appView.setAppServices(AppServices.builder(appView).build());
      }

      new IRConverter(appView, timing, printer).convert(appView, executor);

      if (options.printCfg) {
        if (options.printCfgFile == null || options.printCfgFile.isEmpty()) {
          System.out.print(printer.toString());
        } else {
          try (OutputStreamWriter writer =
              new OutputStreamWriter(
                  new FileOutputStream(options.printCfgFile), StandardCharsets.UTF_8)) {
            writer.write(printer.toString());
          }
        }
      }

      // Close any internal archive providers now the application is fully processed.
      inputApp.closeInternalArchiveProviders();

      // If a method filter is present don't produce output since the application is likely partial.
      if (options.hasMethodsFilter()) {
        System.out.println("Finished compilation with method filter: ");
        options.methodsFilter.forEach((m) -> System.out.println("  - " + m));
      }

      // Preserve markers from input dex code and add a marker with the current version
      // if there were class file inputs.
      boolean hasClassResources = appView.appInfo().app().getFlags().hasReadProgramClassFromCf();
      boolean hasDexResources = appView.appInfo().app().getFlags().hasReadProgramClassFromDex();

      Marker marker = options.getMarker(Tool.D8);
      Set<Marker> markers = new HashSet<>(appView.dexItemFactory().extractMarkers());
      // TODO(b/166617364): Don't add an additional marker when desugaring is turned off.
      if (hasClassResources
          && (options.desugarState != DesugarState.OFF
              || markers.isEmpty()
              || (markers.size() == 1 && markers.iterator().next().isL8()))) {
        markers.add(marker);
      }
      Marker.checkCompatibleDesugaredLibrary(markers, options.reporter);

      InspectorImpl.runInspections(options.outputInspections, appView.appInfo().classes());
      NamingLens namingLens = NamingLens.getIdentityLens();
      namingLens = PrefixRewritingNamingLens.createPrefixRewritingNamingLens(appView, namingLens);
      namingLens = RecordRewritingNamingLens.createRecordRewritingNamingLens(appView, namingLens);

      if (options.isGeneratingDex()
          && hasDexResources
          && hasClassResources
          && appView.typeRewriter.isRewriting()) {
        // There are both cf and dex inputs in the program, and rewriting is required for
        // desugared library only on cf inputs. We cannot easily rewrite part of the program
        // without iterating again the IR. We fall-back to writing one app with rewriting and
        // merging it with the other app in rewriteNonDexInputs.
        timing.begin("Rewrite non-dex inputs");
        DexApplication app =
            rewriteNonDexInputs(
                appView, inputApp, options, executor, timing, appView.appInfo().app(), namingLens);
        timing.end();
        appView.setAppInfo(
            new AppInfo(
                appView.appInfo().getSyntheticItems().commit(app),
                appView.appInfo().getMainDexInfo()));
        namingLens = NamingLens.getIdentityLens();
      } else if (options.isGeneratingDex() && hasDexResources) {
        namingLens = NamingLens.getIdentityLens();
      }

      // Since tracing is not lens aware, this needs to be done prior to synthetic finalization
      // which will construct a graph lens.
      if (options.isGeneratingDex() && !options.mainDexKeepRules.isEmpty()) {
        appView.dexItemFactory().clearTypeElementsCache();
        MainDexInfo mainDexInfo =
            new GenerateMainDexList(options)
                .traceMainDex(
                    executor, appView.appInfo().app(), appView.appInfo().getMainDexInfo());
        appView.setAppInfo(appView.appInfo().rebuildWithMainDexInfo(mainDexInfo));
      }

      finalizeApplication(appView, executor);

      HorizontalClassMerger.createForD8ClassMerging(appView).runIfNecessary(executor, timing);

      new GenericSignatureRewriter(appView, namingLens)
          .runForD8(appView.appInfo().classes(), executor);
      new KotlinMetadataRewriter(appView, namingLens).runForD8(executor);

      if (options.isGeneratingClassFiles()) {
        new CfApplicationWriter(appView, marker, namingLens)
            .write(options.getClassFileConsumer(), inputApp);
      } else {
        if (options.apiModelingOptions().enableStubbingOfClasses) {
          new ApiReferenceStubber(appView).run(executor);
        }
        new ApplicationWriter(
                appView,
                marker == null ? null : ImmutableList.copyOf(markers),
                namingLens)
            .write(executor, inputApp);
      }
      options.printWarnings();
    } catch (ExecutionException e) {
      throw unwrapExecutionException(e);
    } finally {
      options.signalFinishedToConsumers();
      // Dump timings.
      if (options.printTimes) {
        timing.report();
      }
    }
  }

  private static void finalizeApplication(AppView<AppInfo> appView, ExecutorService executorService)
      throws ExecutionException {
    SyntheticFinalization.finalize(appView, executorService);
  }

  private static DexApplication rewriteNonDexInputs(
      AppView<AppInfo> appView,
      AndroidApp inputApp,
      InternalOptions options,
      ExecutorService executor,
      Timing timing,
      DexApplication app,
      NamingLens desugaringLens)
      throws IOException, ExecutionException {
    // TODO(b/154575955): Remove the naming lens in D8.
    appView
        .options()
        .reporter
        .warning(
            new StringDiagnostic(
                "The compilation is slowed down due to a mix of class file and dex file inputs in"
                    + " the context of desugared library. This can be fixed by pre-compiling to"
                    + " dex the class file inputs and dex merging only dex files."));
    List<DexProgramClass> dexProgramClasses = new ArrayList<>();
    List<DexProgramClass> nonDexProgramClasses = new ArrayList<>();
    for (DexProgramClass aClass : app.classes()) {
      if (aClass.originatesFromDexResource()) {
        dexProgramClasses.add(aClass);
      } else {
        nonDexProgramClasses.add(aClass);
      }
    }
    DexApplication cfApp = app.builder().replaceProgramClasses(nonDexProgramClasses).build();
    appView.setAppInfo(
        new AppInfo(
            appView.appInfo().getSyntheticItems().commit(cfApp),
            appView.appInfo().getMainDexInfo()));
    ConvertedCfFiles convertedCfFiles = new ConvertedCfFiles();
    new GenericSignatureRewriter(appView, desugaringLens)
        .run(appView.appInfo().classes(), executor);
    new KotlinMetadataRewriter(appView, desugaringLens).runForD8(executor);
    new ApplicationWriter(
            appView,
            null,
            desugaringLens,
            convertedCfFiles)
        .write(executor);
    AndroidApp.Builder builder = AndroidApp.builder(inputApp);
    builder.getProgramResourceProviders().clear();
    builder.addProgramResourceProvider(convertedCfFiles);
    AndroidApp newAndroidApp = builder.build();
    DexApplication newApp = new ApplicationReader(newAndroidApp, options, timing).read(executor);
    DexApplication.Builder<?> finalDexApp = newApp.builder();
    for (DexProgramClass dexProgramClass : dexProgramClasses) {
      finalDexApp.addProgramClass(dexProgramClass);
    }
    return finalDexApp.build();
  }

  static void optimize(
      AppView<AppInfo> appView, InternalOptions options, Timing timing, ExecutorService executor)
      throws IOException, ExecutionException {
    final CfgPrinter printer = options.printCfg ? new CfgPrinter() : null;

    new IRConverter(appView, timing, printer).convert(appView, executor);

    if (options.printCfg) {
      if (options.printCfgFile == null || options.printCfgFile.isEmpty()) {
        System.out.print(printer.toString());
      } else {
        try (OutputStreamWriter writer =
            new OutputStreamWriter(
                new FileOutputStream(options.printCfgFile), StandardCharsets.UTF_8)) {
          writer.write(printer.toString());
        }
      }
    }
  }

  static class ConvertedCfFiles implements DexIndexedConsumer, ProgramResourceProvider {

    private final List<ProgramResource> resources = new ArrayList<>();

    @Override
    public synchronized void accept(
        int fileIndex, ByteDataView data, Set<String> descriptors, DiagnosticsHandler handler) {
      // TODO(b/154106502): Map Origin information.
      resources.add(
          ProgramResource.fromBytes(
              Origin.unknown(), ProgramResource.Kind.DEX, data.copyByteData(), descriptors));
    }

    @Override
    public Collection<ProgramResource> getProgramResources() throws ResourceException {
      return resources;
    }

    @Override
    public void finished(DiagnosticsHandler handler) {}
  }
}
