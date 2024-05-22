// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.intellij.terraform.config.documentation

import com.intellij.openapi.application.readAction
import com.intellij.psi.*
import com.intellij.psi.util.childrenOfType
import org.intellij.terraform.config.Constants.HCL_DATASOURCE_IDENTIFIER
import org.intellij.terraform.config.Constants.HCL_PROVIDER_IDENTIFIER
import org.intellij.terraform.config.Constants.HCL_RESOURCE_IDENTIFIER
import org.intellij.terraform.config.Constants.LATEST_VERSION
import org.intellij.terraform.config.model.ProviderType
import org.intellij.terraform.config.model.TypeModelProvider
import org.intellij.terraform.config.psi.TerraformDocumentPsi
import org.intellij.terraform.hcl.psi.*


internal abstract class BaseTerraformDocUrlProvider {

  internal companion object {
    @JvmStatic
    val RESOURCES: String = "resources"

    @JvmStatic
    val DATASOURCES: String = "data-sources"

    @JvmStatic
    val PROVIDER: String = "provider"
  }

  internal suspend fun getDocumentationUrl(element: PsiElement?): List<String?> {
    element ?: return emptyList()

    val blockData = buildBlockData(element)
    return when (blockData.type) {
      HCL_RESOURCE_IDENTIFIER -> listOf(getDocUrl(blockData, RESOURCES))
      HCL_DATASOURCE_IDENTIFIER -> listOf(getDocUrl(blockData, DATASOURCES))
      HCL_PROVIDER_IDENTIFIER -> listOf(getDocUrl(blockData, PROVIDER))
      else -> emptyList()
    }
  }

  protected abstract suspend fun getDocUrl(blockData: BlockData, context: String): String?

  protected data class BlockData(val identifier: String?, val type: String?, val parameter: String?, val provider: ProviderData?)

  protected data class ProviderData(val org: String, val provider: String, val version: String)

  private suspend fun buildBlockData(element: PsiElement): BlockData = readAction {
    val (block, parameter) = when (element) {
      is HCLBlock -> {
        Pair(element, null)
      }
      is HCLProperty -> {
        getBlockInfoForIdentifier(element.childrenOfType<HCLIdentifier>().firstOrNull())
      }
      is HCLIdentifier -> {
        getBlockInfoForIdentifier(element)
      }
      is TerraformDocumentPsi -> {
        val relevantBlock = getBlockForDocumentationLink(element, element.name)
        relevantBlock?.let { Pair(it, null) }
      }
      else -> Pair(null, null)
    } ?: Pair(null, null)
    val type = block?.getNameElementUnquoted(0) ?: ""
    val identifier = block?.getNameElementUnquoted(1) ?: ""
    val providerData = getProviderData(element, type, identifier)
    BlockData(identifier, type, parameter, providerData)
  }

  private fun getBlockInfoForIdentifier(hclIdentifier: HCLIdentifier?): Pair<HCLBlock, String?>? {
    val parentBlock = hclIdentifier?.let { getBlockForHclIdentifier(it) }
    val paramName = hclIdentifier?.id
    return parentBlock?.let { Pair(it, paramName) }
  }

  private fun getProviderData(element: PsiElement,
                              type: String,
                              identifier: String): ProviderData? {
    val provider = getProvider(identifier, type, element) ?: return null
    return ProviderData(provider.namespace, provider.type, if (provider.version.isEmpty()) LATEST_VERSION else provider.version)
  }

  protected fun getResourceId(identifier: String): String {
    val stringList = identifier.split("_", limit = 2)
    val id = if (stringList.size < 2) identifier else stringList[1]
    return id
  }

  private fun getProvider(identifier: String, resourceType: String, element: PsiElement): ProviderType? {
    val model = TypeModelProvider.getModel(element)
    return when (resourceType) {
      HCL_RESOURCE_IDENTIFIER -> model.getResourceType(identifier)?.provider
      HCL_DATASOURCE_IDENTIFIER -> model.getDataSourceType(identifier)?.provider
      HCL_PROVIDER_IDENTIFIER -> model.getProviderType(identifier)
      else -> null
    }
  }
}