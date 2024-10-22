// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.intellij.terraform

import com.intellij.internal.statistic.beans.MetricEvent
import com.intellij.internal.statistic.eventLog.EventLogGroup
import com.intellij.internal.statistic.eventLog.events.EventFields
import com.intellij.internal.statistic.service.fus.collectors.ProjectUsagesCollector
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.LanguageFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.search.FileTypeIndex
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.util.CommonProcessors
import org.intellij.terraform.hcl.HCLLanguage

internal class TerraformUsageCollector : ProjectUsagesCollector() {

  private val GROUP = EventLogGroup(
    id = "terraform.project.metrics",
    version = 1,
  )

  private val TERRAGRUNT = GROUP.registerEvent(
    "terragrunt.found", EventFields.Boolean("exists"),
    "if \"terragrunt.hcl\" file exists in the project among other terraform files")

  override fun getGroup(): EventLogGroup = GROUP

  override fun requiresReadAccess(): Boolean = true

  override fun requiresSmartMode(): Boolean = true

  override fun getMetrics(project: Project): Set<MetricEvent> {
    val result = mutableSetOf<MetricEvent>()

    val hasTerraformFiles = FileTypeManager.getInstance()
      .registeredFileTypes
      .asSequence()
      .filter { type -> (type as? LanguageFileType)?.language?.isKindOf(HCLLanguage) == true }
      .any { ft -> FileTypeIndex.containsFileOfType(ft, GlobalSearchScope.allScope(project)) }

    if (hasTerraformFiles) {
      val terragruntSearch = CommonProcessors.FindFirstProcessor<VirtualFile>()
      FilenameIndex.processFilesByName("terragrunt.hcl", false, GlobalSearchScope.allScope(project), terragruntSearch)
      result.add(TERRAGRUNT.metric(terragruntSearch.isFound))
    }

    return result
  }
}

