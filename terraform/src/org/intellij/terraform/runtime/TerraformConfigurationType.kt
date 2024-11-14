// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.intellij.terraform.runtime

import com.intellij.execution.configurations.ConfigurationFactory
import com.intellij.openapi.project.Project
import org.intellij.terraform.TerraformIcons
import org.intellij.terraform.hcl.HCLBundle
import org.intellij.terraform.install.TfToolType
import javax.swing.Icon

internal class TerraformConfigurationType : TfToolConfigurationTypeBase() {

  private val TF_RUN_CONFIGURATION_ID = "TerraformConfigurationType"

  override val baseFactory: ConfigurationFactory = createFactory(TfToolType.TERRAFORM, TfCommand.CUSTOM)
  override val initFactory: ConfigurationFactory = createFactory(TfToolType.TERRAFORM,TfCommand.INIT)
  override val validateFactory: ConfigurationFactory = createFactory(TfToolType.TERRAFORM,TfCommand.VALIDATE)
  override val planFactory: ConfigurationFactory = createFactory(TfToolType.TERRAFORM,TfCommand.PLAN)
  override val applyFactory: ConfigurationFactory = createFactory(TfToolType.TERRAFORM,TfCommand.APPLY)
  override val destroyFactory: ConfigurationFactory = createFactory(TfToolType.TERRAFORM,TfCommand.DESTROY)

  override val actionGroupId: String = "TerraformRunConfigurationActions"

  override fun getDisplayName(): String = HCLBundle.message("terraform.name")
  override fun getConfigurationTypeDescription(): String = HCLBundle.message("terraform.configuration.type.description", TfToolType.TERRAFORM.displayName)
  override fun getIcon(): Icon = TerraformIcons.Terraform
  override fun getConfigurationFactories(): Array<ConfigurationFactory> = arrayOf(
    baseFactory, initFactory, validateFactory, planFactory, applyFactory, destroyFactory
  )

  override fun getId(): String = TF_RUN_CONFIGURATION_ID

  override fun ConfigurationFactory.createConfiguration(project: Project): TfToolsRunConfigurationBase {
    return TerraformRunConfiguration(project, this, "", emptyList())
  }

}