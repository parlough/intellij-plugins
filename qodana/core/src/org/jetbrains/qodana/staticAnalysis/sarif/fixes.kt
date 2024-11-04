package org.jetbrains.qodana.staticAnalysis.sarif

import com.intellij.codeInspection.*
import com.intellij.codeInspection.actions.CleanupInspectionUtil
import com.intellij.codeInspection.ex.*
import com.intellij.modcommand.ModCommand
import com.intellij.modcommand.ModCompositeCommand
import com.intellij.modcommand.ModUpdateFileText
import com.intellij.modcommand.ActionContext
import com.intellij.modcommand.ModCommandExecutor
import com.intellij.modcommand.ModCommandQuickFix
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.WriteIntentReadAction
import com.intellij.openapi.application.readAction
import com.intellij.openapi.application.readActionBlocking
import com.intellij.openapi.application.writeIntentReadAction
import com.intellij.openapi.command.writeCommandAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.progress.blockingContextScope
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.io.toNioPathOrNull
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiManager
import com.intellij.util.PairProcessor
import com.jetbrains.qodana.sarif.model.Result
import com.jetbrains.qodana.sarif.model.Run
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.jetbrains.qodana.QodanaBundle
import org.jetbrains.qodana.license.isFixesAvailable
import org.jetbrains.qodana.staticAnalysis.StaticAnalysisDispatchers
import org.jetbrains.qodana.staticAnalysis.inspections.config.FixesStrategy
import org.jetbrains.qodana.staticAnalysis.script.QodanaProgressIndicator
import com.intellij.platform.util.coroutines.mapConcurrent
import com.intellij.psi.PsiFile
import kotlinx.coroutines.launch
import org.jetbrains.qodana.staticAnalysis.inspections.runner.*

private val LOG = logger<QodanaRunner>()

suspend fun maybeApplyFixes(sarifRun: Run, runContext: QodanaRunContext) {
  val disableDefaultFixesStrategy = System.getProperty("qodana.disable.default.fixes.strategy", "false").toBoolean()
  val wantsFixes = runContext.config.fixesStrategy != FixesStrategy.NONE
  if (!wantsFixes) return
  val licenseType = runContext.config.license.type
  val canApplyFixes = licenseType.isFixesAvailable()
  if (!canApplyFixes) {
    runContext.messageReporter.reportMessage(1, "Fixes are not available for license type: $licenseType")
    return
  }

  runTaskAndLogTime(runContext.config.fixesStrategy.stageName) {
    try {
      QuickFixesStrategyProvider.runCustomProviders(sarifRun, runContext)
      if (!disableDefaultFixesStrategy) {
        applyFixes(sarifRun, runContext)
      }
    }
    finally {
      withContext(StaticAnalysisDispatchers.UI + NonCancellable) {
        //readaction is not enough
        writeIntentReadAction {
          PsiDocumentManager.getInstance(runContext.project).commitAllDocuments()
          FileDocumentManager.getInstance().saveAllDocuments()
        }
      }
      FixesLogger.logFixesAsJson("fixes.json")
      if (java.lang.Boolean.getBoolean(FixesLogger.INCLUDE_FIXES_DIFF_KEY)) {
        FixesLogger.logFileModificationsAsJson("files-modifications.json")
      }
    }
  }
}

private suspend fun applyFixes(sarifRun: Run, runContext: QodanaRunContext) {
  val projectDir = VfsUtil.findFile(runContext.projectPath, false)
  val grouped = sarifRun.results.groupBy { result -> result.locations[0].physicalLocation.artifactLocation.uri }
  val cleanup = runContext.config.fixesStrategy == FixesStrategy.CLEANUP
  LOG.debug("Applying fixes. ${grouped.size} files to process.")
  withContext(Dispatchers.Default) {
    grouped.forEach { (uri, results) ->
      try {
        runFixesForFile(runContext, uri, results, projectDir, cleanup)
      }
      catch (e: Exception) {
        LOG.error("Failed to apply fixes for $uri", e)
      }
    }
    if (java.lang.Boolean.getBoolean(FixesLogger.INCLUDE_FIXES_DIFF_KEY)) {
      launch {
        FixesLogger.commitFilesModificationsLog()
      }
    }
  }
}

private suspend fun runFixesForFile(runContext: QodanaRunContext,
                                    uri: String,
                                    results: List<Result>,
                                    projectDir: VirtualFile?,
                                    cleanup: Boolean) {
  runContext.messageReporter.reportMessage(1, "Checking fixes for $uri")
  val problems = reconstructProblems(results, uri, projectDir, runContext, cleanup)

  if (problems.isEmpty()) {
    LOG.debug("Applying fixes. Problems for $uri not found.")
    return
  }

  val (cleanupProblems, otherProblems) = problems.partition { it.first.isCleanupTool }
  LOG.debug("Applying fixes. $uri has ${cleanupProblems.size} cleanup problems and ${otherProblems.size} other problems.")
  val appliedCleanupFixes = applyCleanup(runContext, cleanupProblems, uri)
  val appliedOtherFixes = applyOther(runContext, otherProblems, uri)
  val appliedFixes = appliedCleanupFixes + appliedOtherFixes
  runContext.messageReporter.reportMessage(1, "Number of fixes applied for $uri: $appliedFixes")
}

private suspend fun reconstructProblems(results: List<Result>,
                                        uri: String,
                                        projectDir: VirtualFile?,
                                        runContext: QodanaRunContext,
                                        cleanup: Boolean): List<Pair<InspectionToolWrapper<*, *>, MutableList<ProblemDescriptor>>> {
  val inspections = results.map { it.ruleId }.toSet()
  val file = VfsUtil.findRelativeFile(uri, projectDir) ?: return emptyList()
  val psiFile = readAction { PsiManager.getInstance(runContext.project).findFile(file) } ?: return emptyList()
  val psiFileTextLength = readAction { psiFile.textLength }

  val textRange = TextRange(0, psiFileTextLength)
  val toolWrappers = runContext.qodanaProfile.effectiveProfile.tools
  val toolWrappersEnabled = toolWrappers
    .filter { inspections.contains(it.shortName) }
    .mapNotNull { it.getEnabledTool(psiFile) }
    .filter { !cleanup || it.isCleanupTool }

  val localToolWrappers = toolWrappersEnabled.filterIsInstance<LocalInspectionToolWrapper>()

  val globalSimpleToolWrappers = toolWrappersEnabled.filter { it.tool is GlobalSimpleInspectionTool }

  val problems = mutableListOf<Pair<InspectionToolWrapper<*, *>, MutableList<ProblemDescriptor>>>()

  val localProblems = readActionBlocking {
    InspectionEngine.inspectEx(
      localToolWrappers,
      psiFile,
      textRange,
      textRange,
      false,
      runContext.scope.isAnalyzeInjectedCode,
      true,
      QodanaProgressIndicator(runContext.messageReporter),
      PairProcessor.alwaysTrue()
    )
  }.toList()
  problems.addAll(localProblems)

  val globalSimpleProblems = inspectGlobalSimpleTools(globalSimpleToolWrappers, runContext.project, psiFile)
  problems.addAll(globalSimpleProblems)

  return problems
}

private suspend fun inspectGlobalSimpleTools(globalToolWrappers: List<InspectionToolWrapper<*, *>>,
                                             project: Project,
                                             psiFile: PsiFile): List<Pair<InspectionToolWrapper<*, *>, MutableList<ProblemDescriptor>>> {
  if (globalToolWrappers.isEmpty()) return emptyList()

  val managerEx = InspectionManagerEx.getInstance(project)
  val globalContext = managerEx.createNewGlobalContext() as GlobalInspectionContextEx

  return globalToolWrappers.mapConcurrent { wrapper ->
    val globalSimpleTool = wrapper.tool as GlobalSimpleInspectionTool
    val problemsHolder = ProblemsHolder(managerEx, psiFile, false)
    val problemDescriptionsProcessor = DefaultInspectionToolResultExporter(wrapper, globalContext)
    readActionBlocking {
      globalSimpleTool.checkFile(psiFile, managerEx, problemsHolder, globalContext, problemDescriptionsProcessor)
    }
    Pair(wrapper, problemsHolder.results)
  }.toList()
}

private suspend fun applyCleanup(
  runContext: QodanaRunContext,
  problems: List<Pair<InspectionToolWrapper<*, *>, MutableList<ProblemDescriptor>>>,
  uri: String
): Int {
  if (problems.isEmpty()) return 0
  return withContext(Dispatchers.EDT) {
    blockingContextScope {
      //readaction is not enough
      WriteIntentReadAction.compute<Int> {
        var counter = 0
        LOG.debug("Applying cleanup ${problems.size} fixes for $uri")
        problems.forEach { (tool, problemDescriptors) ->
          problemDescriptors.forEach { problem ->
            val textBefore: CharSequence? =  if (java.lang.Boolean.getBoolean(FixesLogger.INCLUDE_FIXES_DIFF_KEY))
              problem.containingFileText(true) else null

            val fixesTask = CleanupInspectionUtil.getInstance().applyFixesNoSort(
              runContext.project, QodanaBundle.message("apply.fixes.command"), listOf(problem), null, true)

            if (fixesTask.numberOfSucceededFixes > 0) {
              val problemMessage = problem.messageWithLine()
              FixesLogger.logAppliedFix(runContext.messageReporter, tool, problemMessage, uri, problem, uri)

              if (java.lang.Boolean.getBoolean(FixesLogger.INCLUDE_FIXES_DIFF_KEY)) {
                FixesLogger.addFileModificationToQueue(uri, problemMessage, textBefore!!, problem.containingFileText())
              }
            }
            counter += fixesTask.numberOfSucceededFixes
          }
        }

        LOG.debug("Cleanup fixes for $uri applied")
        counter
      }
    }
  }
}

private suspend fun applyOther(
  runContext: QodanaRunContext,
  problems: List<Pair<InspectionToolWrapper<*, *>, MutableList<ProblemDescriptor>>>,
  uri: String
): Int {
  if (problems.isEmpty()) return 0

  return withContext(Dispatchers.EDT) {
    LOG.debug("Applying fixes for $uri")
    writeCommandAction(runContext.project, QodanaBundle.message("apply.fixes.command")) {
      val fixed = fixProblems(runContext, problems, uri)
      LOG.debug("Applying fixes for $uri is finished. $fixed problems were fixed.")
      fixed
    }
  }
}


private fun fixProblems(
  runContext: QodanaRunContext,
  problems: List<Pair<InspectionToolWrapper<*, *>, MutableList<ProblemDescriptor>>>,
  uri: String
): Int {
  var counter = 0
  problems.forEach { (tool, problemDescriptors) ->
    problemDescriptors.forEach { problem ->
      val descriptionMessage = problem.messageWithLine()
      try {
        if (tryToFixProblem(runContext, problem, descriptionMessage, tool, uri)) {
          counter++
        }
      }
      catch (e: Exception) {
        LOG.error("Fixes apply error for problem '$descriptionMessage'", e)
      }
    }
  }
  return counter
}

private fun tryToFixProblem(
  runContext: QodanaRunContext,
  descriptor: ProblemDescriptor,
  descriptionMessage: String,
  toolWrapper: InspectionToolWrapper<*, *>,
  uri: String
): Boolean {
  val element = descriptor.psiElement
  if (element == null || !element.isValid) {
    LOG.debug("Element for problem '$descriptionMessage' is invalid. Fix won't be applied.")
    return false
  }
  val project = element.project
  if (tryToApplyModCommandFixes(runContext, descriptor, descriptionMessage, toolWrapper, uri)) return true

  if (!java.lang.Boolean.getBoolean("qodana.allow.non.batch.fixes")) return false
  val nonModCommandFix = descriptor.fixes?.firstOrNull { it !is ModCommandQuickFix }
  if (nonModCommandFix == null) {
    LOG.debug("No batch fix for problem '$descriptionMessage' is found.")
    return false
  }
  LOG.debug("Non-batch fix for problem '$descriptionMessage' is found.")
  nonModCommandFix.applyFix(project, descriptor)
  LOG.debug("Non-batch fix for problem '$descriptionMessage' is applied successfully.")
  PsiDocumentManager.getInstance(project).apply {
    if (uncommittedDocuments.isEmpty()) {
      FixesLogger.logAppliedFix(runContext.messageReporter, toolWrapper, descriptionMessage, uri, nonModCommandFix.name,
                                uri)
    }
    uncommittedDocuments.forEach { uncommitedDoc ->
      val psiFile = getPsiFile(uncommitedDoc)
      psiFile?.virtualFile?.let { file ->
        FixesLogger.logAppliedFix(runContext.messageReporter, toolWrapper, descriptionMessage, uri,
                                  nonModCommandFix.name, file.path.absolutePathToRelative(runContext.project.basePath))
      }
      if (java.lang.Boolean.getBoolean(FixesLogger.INCLUDE_FIXES_DIFF_KEY) && psiFile != null) {
        FixesLogger.addFileModificationToQueue(psiFile.virtualFile.path.absolutePathToRelative(runContext.project.basePath),
                                               descriptionMessage, descriptor.containingFileText(), uncommitedDoc.charsSequence)
      }
    }
  }
  PsiDocumentManager.getInstance(project).commitAllDocuments()
  return true
}

private fun tryToApplyModCommandFixes(
  runContext: QodanaRunContext,
  descriptor: ProblemDescriptor,
  descriptionMessage: String,
  toolWrapper: InspectionToolWrapper<*, *>,
  uri: String
): Boolean {
  val fixes = descriptor.fixes?.filterIsInstance<ModCommandQuickFix>() ?: emptyList()
  LOG.debug("${fixes.size} fixes for problem '$descriptionMessage' are found.")
  fixes.forEach {
    val modCommand = it.perform(runContext.project, descriptor)
    val result = ModCommandExecutor.getInstance().executeInBatch(ActionContext.from(descriptor), modCommand)
    if (result == ModCommandExecutor.Result.SUCCESS) {
      LOG.debug("Fix for problem '$descriptionMessage' is applied successfully.")
      logModCommand(runContext, toolWrapper, descriptionMessage, uri, it.name, modCommand)
      return true
    } else {
      LOG.debug("Fix attempt for problem '$descriptionMessage' failed with message '${result.message}'.")
    }
  }
  return false
}

private fun logModCommand(
  runContext: QodanaRunContext,
  toolWrapper: InspectionToolWrapper<*, *>,
  problemMessage: String,
  problemOriginFilePath: String,
  fixText: String,
  modCommand: ModCommand
) {
  when (modCommand) {
    is ModUpdateFileText -> {
      modCommand.modifiedFiles().forEach {
        FixesLogger.logAppliedFix(runContext.messageReporter, toolWrapper, problemMessage, problemOriginFilePath, fixText,
                                  it.path.absolutePathToRelative(runContext.project.basePath))
      }
      if (java.lang.Boolean.getBoolean(FixesLogger.INCLUDE_FIXES_DIFF_KEY)) {
        FixesLogger.addFileModificationToQueue(modCommand.file.path.absolutePathToRelative(runContext.project.basePath), problemMessage,
                                               modCommand.oldText, modCommand.newText)
      }
    }
    is ModCompositeCommand -> {
      modCommand.commands.forEach {
        logModCommand(runContext, toolWrapper, problemMessage, problemOriginFilePath, fixText, it)
      }
    }
    else -> {
      FixesLogger.logAppliedFix(runContext.messageReporter, toolWrapper, problemMessage, problemOriginFilePath, fixText, FixesLogger.NOT_CAPTURED_MESSAGE)
    }
  }
}

private fun String.absolutePathToRelative(base: String?) =
  toNioPathOrNull()?.let { base?.toNioPathOrNull()?.relativize(it)?.toString() } ?: this

private fun ProblemDescriptor.messageWithLine() =
  "${lineNumber}: ${ProblemDescriptorUtil.renderDescriptionMessage(this, psiElement)}"

private fun ProblemDescriptor.containingFileText(copy: Boolean = false) =
  psiElement?.containingFile?.fileDocument?.let { if (copy) it.text else it.charsSequence } ?: FixesLogger.NOT_CAPTURED_MESSAGE