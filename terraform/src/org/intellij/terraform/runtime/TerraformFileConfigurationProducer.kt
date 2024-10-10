// Copyright 2000-2023 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.intellij.terraform.runtime

import com.intellij.execution.actions.ConfigurationContext
import com.intellij.execution.actions.ConfigurationFromContext
import com.intellij.execution.actions.LazyRunConfigurationProducer
import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.util.Ref
import com.intellij.psi.PsiElement
import org.intellij.terraform.config.model.getTerraformModule
import org.intellij.terraform.config.patterns.TerraformPatterns
import org.intellij.terraform.hcl.psi.HCLBlock
import org.intellij.terraform.hcl.psi.HCLElement

sealed class TerraformFileConfigurationProducer(private val type: TfMainCommand) : LazyRunConfigurationProducer<TerraformRunConfiguration>(), DumbAware {
  override fun getConfigurationFactory(): ConfigurationFactory {
    return tfRunConfigurationType().createFactory(type)
  }

  override fun setupConfigurationFromContext(configuration: TerraformRunConfiguration, context: ConfigurationContext, sourceElement: Ref<PsiElement>): Boolean {
    val target = getModuleTarget(context) ?: return false
    configuration.workingDirectory = target.first
    configuration.name = type.title + " " + target.second
    configuration.setNameChangedByUser(false)
    return true
  }

  override fun isConfigurationFromContext(configuration: TerraformRunConfiguration, context: ConfigurationContext): Boolean {
    val target = getModuleTarget(context) ?: return false
    val wd = configuration.workingDirectory
    if (target.first != wd) return false
    if (configuration.name != type.title + " " + target.second) return false

    val parameters = configuration.programParameters ?: return true
    return !parameters.contains("-target")
  }

  override fun isPreferredConfiguration(self: ConfigurationFromContext?, other: ConfigurationFromContext?): Boolean {
    if (other == null) return true
    val configuration = other.configuration as? TerraformRunConfiguration ?: return true
    return configuration.programParameters?.contains("-target=") != true
  }

  companion object {
    class Init : TerraformFileConfigurationProducer(TfMainCommand.INIT)
    class Validate : TerraformFileConfigurationProducer(TfMainCommand.VALIDATE)
    class Plan : TerraformFileConfigurationProducer(TfMainCommand.PLAN)
    class Apply : TerraformFileConfigurationProducer(TfMainCommand.APPLY)
    class Destroy : TerraformFileConfigurationProducer(TfMainCommand.DESTROY)

    fun getModuleTarget(context: ConfigurationContext): Pair<String, String>? {
      val location = context.location ?: return null
      val block = location.psiElement.containingFile?.children?.firstOrNull { it is HCLBlock } ?: return null
      return getModuleTarget(block)
    }

    private fun getModuleTarget(element: PsiElement): Pair<String, String>? {
      if (element !is HCLElement) return null
      val file = element.containingFile.originalFile
      if (!TerraformPatterns.TerraformConfigFile.accepts(file)) return null

      val module = element.getTerraformModule()
      if (!module.moduleRoot.isDirectory) {
        return null
      }

      val virtualFile = module.moduleRoot.virtualFile
      return virtualFile.path to virtualFile.name
    }
  }
}