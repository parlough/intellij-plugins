// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.angular2.lang.expr.service

import com.intellij.lang.javascript.service.JSLanguageService
import com.intellij.lang.javascript.service.JSLanguageServiceProvider
import com.intellij.lang.typescript.compiler.TypeScriptLanguageServiceProvider
import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile

internal class AngularLanguageServiceProvider(project: Project) : JSLanguageServiceProvider {
  private val tsLanguageService by lazy(LazyThreadSafetyMode.PUBLICATION) { project.service<AngularServiceWrapper>() }

  override fun getAllServices(): List<JSLanguageService> = listOf(tsLanguageService.service)

  override fun getService(file: VirtualFile): JSLanguageService? = allServices.firstOrNull { it.isAcceptable(file) }

  override fun isHighlightingCandidate(file: VirtualFile): Boolean {
    return TypeScriptLanguageServiceProvider.isJavaScriptOrTypeScriptFileType(file.fileType)
  }
}

@Service(Service.Level.PROJECT)
private class AngularServiceWrapper(project: Project) : Disposable {
  val service = AngularTypeScriptService(project)

  override fun dispose() {
    Disposer.dispose(service)
  }
}