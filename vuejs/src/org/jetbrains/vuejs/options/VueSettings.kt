// Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package org.jetbrains.vuejs.options

import com.intellij.lang.typescript.compiler.TypeScriptCompilerSettings
import com.intellij.lang.typescript.lsp.createPackageRef
import com.intellij.lang.typescript.lsp.defaultPackageKey
import com.intellij.lang.typescript.lsp.extractRefText
import com.intellij.lang.typescript.lsp.restartTypeScriptServicesAsync
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import org.jetbrains.annotations.ApiStatus.Obsolete
import org.jetbrains.annotations.TestOnly
import org.jetbrains.vuejs.lang.typescript.service.lsp.VueLspExecutableDownloader

fun getVueSettings(project: Project): VueSettings = project.service<VueSettings>()

@TestOnly
fun configureVueService(project: Project, disposable: Disposable, serviceSettings: VueServiceSettings) {
  val vueSettings = getVueSettings(project)
  val old = vueSettings.serviceType
  vueSettings.serviceType = serviceSettings

  Disposer.register(disposable) {
    vueSettings.serviceType = old
  }
}

@Service(Service.Level.PROJECT)
@State(name = "VueSettings", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class VueSettings(val project: Project) : SimplePersistentStateComponent<VueSettingsState>(VueSettingsState()) {

  var serviceType: VueServiceSettings
    get() = state.innerServiceType
    set(value) {
      val changed = state.innerServiceType != value
      state.innerServiceType = value
      if (changed) restartTypeScriptServicesAsync(project)
    }

  var packageRef
    get() = createPackageRef(state.packageName, VueLspExecutableDownloader.packageDescriptor.serverPackage)
    set(value) {
      val refText = extractRefText(value)
      val changed = state.packageName != refText
      state.packageName = refText
      if (changed) restartTypeScriptServicesAsync(project)
    }

  var useTypesFromServer: Boolean
    get() {
      val useTypesFromServerInTests = TypeScriptCompilerSettings.isUseTypesFromServerInTests()
      return useTypesFromServerInTests ?: state.useTypesFromServer
    }
    set(value) {
      state.useTypesFromServer = value
    }
}

class VueSettingsState : BaseState() {
  var innerServiceType by enum(VueServiceSettings.AUTO)
  var packageName by string(defaultPackageKey)
  var useTypesFromServer by property(false)
}

enum class VueServiceSettings {
  AUTO,
  /**
   * Must work exactly the same as AUTO, kept for settings deserialization compatibility, aka Vue LSP
   */
  @Obsolete
  VOLAR,
  TS_SERVICE,
  DISABLED
}