/*
 * Copyright (C) 2023 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.copybara.regenerate;

import static com.google.copybara.exception.ValidationException.checkCondition;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.ImmutableList;
import com.google.copybara.AutoPatchfileConfiguration;
import com.google.copybara.Destination.PatchRegenerator;
import com.google.copybara.Destination.Writer;
import com.google.copybara.DestinationReader;
import com.google.copybara.GeneralOptions;
import com.google.copybara.Workflow;
import com.google.copybara.WorkflowMode;
import com.google.copybara.WorkflowOptions;
import com.google.copybara.WorkflowRunHelper;
import com.google.copybara.WriterContext;
import com.google.copybara.exception.RepoException;
import com.google.copybara.exception.ValidationException;
import com.google.copybara.monitor.EventMonitor.ChangeMigrationFinishedEvent;
import com.google.copybara.revision.Revision;
import com.google.copybara.util.AutoPatchUtil;
import com.google.copybara.util.Glob;
import com.google.copybara.util.InsideGitDirException;
import com.google.copybara.util.SinglePatch;
import com.google.copybara.util.console.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import javax.annotation.Nullable;

/**
 * Regenerate contains the implementation of the logic to checkout the correct versions of code and
 * calling the helper classes to diff and upload the contents.
 */
public class Regenerate<O extends Revision, D extends Revision> {

  Console console;
  @Nullable AutoPatchfileConfiguration autoPatchfileConfiguration;
  Workflow<O, D> workflow;
  Path workdir;
  GeneralOptions generalOptions;
  WorkflowOptions workflowOptions;
  RegenerateOptions regenerateOptions;
  @Nullable String sourceRef;

  public static Regenerate<? extends Revision, ? extends Revision> newRegenerate(
      Workflow<? extends Revision, ? extends Revision> workflow,
      Path workdir,
      GeneralOptions generalOptions,
      WorkflowOptions workflowOptions,
      RegenerateOptions regenerateOptions,
      @Nullable String sourceRef) {
    return new Regenerate<>(
        workflow, workdir, generalOptions, workflowOptions, regenerateOptions, sourceRef);
  }

  public Regenerate(
      Workflow<O, D> workflow,
      Path workdir,
      GeneralOptions generalOptions,
      WorkflowOptions workflowOptions,
      RegenerateOptions regenerateOptions,
      @Nullable String sourceRef) {
    this.workflow = workflow;
    this.workdir = workdir;
    this.generalOptions = generalOptions;
    this.workflowOptions = workflowOptions;
    this.regenerateOptions = regenerateOptions;
    this.console = generalOptions.console();
    this.sourceRef = sourceRef;
    this.autoPatchfileConfiguration = workflow.getAutoPatchfileConfiguration();
  }

  @VisibleForTesting
  public void regenerate() throws ValidationException, RepoException, IOException {

    Writer<D> destinationWriter =
        workflow
            .getDestination()
            .newWriter(
                new WriterContext(
                    workflow.getName(),
                    workflowOptions.workflowIdentityUser,
                    generalOptions.dryRunMode,
                    workflow.getOrigin().resolve(null),
                    workflow.getDestinationFiles().roots()));
    PatchRegenerator patchRegenerator =
        destinationWriter
            .getPatchRegenerator(generalOptions.console())
            .orElseThrow(
                () ->
                    new ValidationException(
                        "this destination does not support regenerating patch files"));

    // use the same directory names as workflow
    // TODO(b/296111124)
    Path previousPath = workdir.resolve("premerge");
    Path nextPath = workdir.resolve("checkout");

    Path autopatchPath = workdir.resolve("autopatches");
    Files.createDirectories(previousPath);
    Files.createDirectories(nextPath);
    Files.createDirectories(autopatchPath);

    Optional<String> optRegenTarget = regenerateOptions.getRegenTarget();
    if (optRegenTarget.isEmpty()) {
      optRegenTarget = patchRegenerator.inferRegenTarget();
    }
    String regenTarget =
        optRegenTarget.orElseThrow(
            () ->
                new ValidationException(
                    "Regen target was neither supplied nor able to be inferred. Supply with"
                        + " --regen-target parameter"));
    AutoPatchfileConfiguration autopatchConfig = workflow.getAutoPatchfileConfiguration();

    // If there are no line numbers in the patches and the workflow is not using SinglePatch,
    // default to the import baseline (as long as SinglePatch is not being used).
    boolean noLineNumbers =
        autopatchConfig == null || autopatchConfig.stripFileNamesAndLineNumbers();
    boolean useImportBaseline =
        regenerateOptions.getRegenImportBaseline() || (!workflow.useSinglePatch() && noLineNumbers);

    if (useImportBaseline) {
      previousPath =
          prepareDiffWithImportBaseline(
              autopatchConfig, workflow, workdir, nextPath, regenTarget, destinationWriter);
    } else {
      Optional<String> optRegenBaseline = regenerateOptions.getRegenBaseline();
      if (optRegenBaseline.isEmpty()) {
        optRegenBaseline = patchRegenerator.inferRegenBaseline();
      }
      String regenBaseline =
          optRegenBaseline.orElseThrow(
              () ->
                  new ValidationException(
                      "Regen baseline was neither supplied nor able to be inferred. Supply with"
                          + " --regen-baseline parameter"));
      if (workflow.useSinglePatch()) {
        prepareDiffWithSinglePatchBaseline(
            autopatchConfig,
            workflow,
            destinationWriter,
            previousPath,
            nextPath,
            autopatchPath,
            regenBaseline,
            regenTarget
        );
      } else {
        checkCondition(autopatchConfig != null,
            "Autopatch config required to regenerate from patch files");
        prepareDiffWithReversePatchBaseline(
            autopatchConfig,
            workflow,
            destinationWriter,
            previousPath,
            nextPath,
            autopatchPath,
            regenBaseline,
            regenTarget);
      }
    }

    Optional<byte[]> singlePatch = Optional.empty();
    if (workflow.useSinglePatch()) {
      try {
        singlePatch = Optional.of(
            SinglePatch.generateSinglePatch(previousPath, nextPath,
                workflow.getDestination().getHashFunction(),
                workflow.getGeneralOptions().getEnvironment(),
                workflow.getDestinationFiles()).toBytes());
      } catch (InsideGitDirException e) {
        throw new ValidationException("Error generating single patch", e);
      }
    }

    if (autopatchConfig != null) {
      // generate new autopatch files in the target directory
      try {
        AutoPatchUtil.generatePatchFiles(
            previousPath,
            nextPath,
            Path.of(autopatchConfig.directoryPrefix()),
            autopatchConfig.directory(),
            workflow.isVerbose(),
            workflow.getGeneralOptions().getEnvironment(),
            autopatchConfig.header(),
            autopatchConfig.suffix(),
            nextPath,
            autopatchConfig.stripFileNamesAndLineNumbers(),
            autopatchConfig.glob());
      } catch (InsideGitDirException e) {
        throw new ValidationException(
            String.format(
                "Could not automatically generate patch files because temporary directory %s is"
                    + " inside git repository %s. Error received is %s",
                e.getPath(), e.getGitDirPath(), e.getMessage()),
            e);
      }
    }

    if (singlePatch.isPresent()) {
      Files.createDirectories(nextPath.resolve(workflow.fullSinglePatchPath()).getParent());
      Files.write(nextPath.resolve(workflow.fullSinglePatchPath()), singlePatch.get());
    }

    // push the new set of files
    patchRegenerator.updateChange(
        workflow.getName(), nextPath, workflow.getDestinationFiles(), regenTarget);
  }

  private void prepareDiffWithReversePatchBaseline(
      AutoPatchfileConfiguration autopatchConfig,
      Workflow<O, D> workflow,
      Writer<D> destinationWriter,
      Path previousPath,
      Path nextPath,
      Path autopatchPath,
      String regenBaseline,
      String regenTarget)
      throws ValidationException, RepoException, IOException {
    // download all files except for patch files
    Glob autopatchGlob =
        AutoPatchUtil.getAutopatchGlob(
            autopatchConfig.directoryPrefix(), autopatchConfig.directory());
    Glob patchlessDestinationFiles = Glob.difference(workflow.getDestinationFiles(), autopatchGlob);

    // copy the baseline to one directory
    DestinationReader previousDestinationReader =
        destinationWriter.getDestinationReader(console, regenBaseline, workdir);
    previousDestinationReader.copyDestinationFilesToDirectory(
        patchlessDestinationFiles, previousPath);

    // copy the target to another directory
    DestinationReader nextDestinationReader =
        destinationWriter.getDestinationReader(console, regenTarget, workdir);
    nextDestinationReader.copyDestinationFilesToDirectory(patchlessDestinationFiles, nextPath);

    // copy existing autopatch files to a third directory
    previousDestinationReader.copyDestinationFilesToDirectory(autopatchGlob, autopatchPath);

    // reverse autopatch files on the target directory here to get a pristine import
    AutoPatchUtil.reversePatchFiles(
        previousPath,
        autopatchPath,
        autopatchConfig.suffix(),
        workflow.getGeneralOptions().getEnvironment());
  }

  private void prepareDiffWithSinglePatchBaseline(
      @Nullable AutoPatchfileConfiguration autopatchConfig,
      Workflow<O, D> workflow,
      Writer<D> destinationWriter,
      Path previousPath,
      Path nextPath,
      Path patchPath,
      String regenBaseline,
      String regenTarget)
      throws ValidationException, RepoException, IOException {

    Glob patchlessDestinationFiles = workflow.getDestinationFiles();

    // download all files except for patch files
    if (autopatchConfig != null) {
      Glob autopatchGlob =
          AutoPatchUtil.getAutopatchGlob(
              autopatchConfig.directoryPrefix(), autopatchConfig.directory());
      patchlessDestinationFiles = Glob.difference(patchlessDestinationFiles, autopatchGlob);
    }

    Glob singlePatchGlob = Glob.createGlob(ImmutableList.of(workflow.fullSinglePatchPath()));
    patchlessDestinationFiles = Glob.difference(patchlessDestinationFiles, singlePatchGlob);

    // copy the baseline to one directory
    DestinationReader previousDestinationReader =
        destinationWriter.getDestinationReader(console, regenBaseline, workdir);
    previousDestinationReader.copyDestinationFilesToDirectory(
        patchlessDestinationFiles, previousPath);

    // copy the target to another directory
    DestinationReader nextDestinationReader =
        destinationWriter.getDestinationReader(console, regenTarget, workdir);
    nextDestinationReader.copyDestinationFilesToDirectory(patchlessDestinationFiles, nextPath);

    // copy singlepatch file to a third directory
    previousDestinationReader.copyDestinationFilesToDirectory(singlePatchGlob, patchPath);

    // reverse patch files on the target directory here to get a pristine import
    Path singlePatchPath = patchPath.resolve(workflow.fullSinglePatchPath());
    if (Files.exists(singlePatchPath)) {
      SinglePatch singlePatch = SinglePatch.fromBytes(Files.readAllBytes(singlePatchPath));
      singlePatch.reverseSinglePatch(previousPath, workflow.getGeneralOptions().getEnvironment());
    } else {
      console.warn("SinglePatch enabled but no SinglePatch file encountered");
    }
  }

  private Path prepareDiffWithImportBaseline(
      @Nullable AutoPatchfileConfiguration autopatchConfig,
      Workflow<O, D> workflow,
      Path workdir,
      Path nextPath,
      String regenTarget,
      Writer<D> destinationWriter)
      throws ValidationException, RepoException, IOException {
    Glob patchlessDestinationFiles = workflow.getDestinationFiles();
    if (autopatchConfig != null) {
      Glob autopatchGlob =
          AutoPatchUtil.getAutopatchGlob(
              autopatchConfig.directoryPrefix(), autopatchConfig.directory());
      patchlessDestinationFiles = Glob.difference(workflow.getDestinationFiles(), autopatchGlob);
    }

    O resolvedRef = workflow.getOrigin().resolve(sourceRef);
    WorkflowRunHelper<O, D> runHelper =
        workflow.newRunHelper(
            workdir, resolvedRef, sourceRef, (ChangeMigrationFinishedEvent e) -> {});

    O current = resolvedRef;
    O lastRev = null;

    if (WorkflowMode.isHistorySupported(runHelper)) {
      lastRev = WorkflowMode.maybeGetLastRev(runHelper);
      if (workflowOptions.importSameVersion) {
        current = lastRev;
      }
    }

    // copy the baseline to one directory
    DestinationReader previousDestinationReader =
        destinationWriter.getDestinationReader(console, lastRev.asString(), workdir);
    Path importPath =
        runHelper.importAndTransformRevision(
            console, lastRev, current, () -> previousDestinationReader);

    // copy the target to another directory
    DestinationReader nextDestinationReader =
        destinationWriter.getDestinationReader(console, regenTarget, workdir);
    nextDestinationReader.copyDestinationFilesToDirectory(patchlessDestinationFiles, nextPath);

    return importPath;
  }
}
