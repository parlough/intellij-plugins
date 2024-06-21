package org.angular2.lang.expr.service

import com.intellij.lang.javascript.JavaScriptSupportLoader
import com.intellij.lang.javascript.service.protocol.JSLanguageServiceCommand
import com.intellij.lang.javascript.service.protocol.JSLanguageServiceObject
import com.intellij.lang.typescript.compiler.languageService.protocol.TypeScriptLanguageServiceCache
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager
import org.angular2.entities.Angular2EntitiesProvider
import org.angular2.lang.expr.Angular2Language
import org.angular2.lang.expr.service.protocol.commands.Angular2TranspiledTemplateCommand
import org.angular2.lang.expr.service.protocol.commands.toAngular2TranspiledTemplateRequestArgs
import org.angular2.lang.html.Angular2HtmlDialect
import org.angular2.lang.html.tcb.Angular2TranspiledComponentFileBuilder
import org.angular2.lang.html.tcb.Angular2TranspiledComponentFileBuilder.TranspiledComponentFile
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class Angular2LanguageServiceCache(project: Project) : TypeScriptLanguageServiceCache(project) {

  private val transpiledComponentCache: MutableMap<VirtualFile, TranspiledComponentInfo> = ConcurrentHashMap()

  override fun updateCacheAndGetServiceObject(input: JSLanguageServiceCommand): JSLanguageServiceObject? =
    if (input is Angular2TranspiledTemplateCommand)
      computeInNonBlockingReadAction { getUpdateTemplateServiceObject(input) }
    else
      super.updateCacheAndGetServiceObject(input)


  private fun getUpdateTemplateServiceObject(input: Angular2TranspiledTemplateCommand): ServiceObjectWithCacheUpdate? {
    val templateFile = PsiManager.getInstance(myProject).findFile(input.templateFile)
    val componentFile =
      when (templateFile?.language) {
        is Angular2HtmlDialect, is Angular2Language ->
          Angular2EntitiesProvider.findTemplateComponent(templateFile)
            ?.sourceElement
            ?.containingFile
        JavaScriptSupportLoader.TYPESCRIPT -> templateFile
        else -> null
      }

    val componentVirtualFile = componentFile?.virtualFile ?: return null
    val newContents = Angular2TranspiledComponentFileBuilder.getTranspiledComponentFile(componentFile)

    if (newContents == null) {
      transpiledComponentCache.remove(componentVirtualFile)
      return null
    }

    val newInfo = TranspiledComponentInfo(newContents)
    val oldInfo = transpiledComponentCache[componentVirtualFile]

    if (oldInfo == newInfo) {
      return null
    }

    return ServiceObjectWithCacheUpdate(
      newContents.toAngular2TranspiledTemplateRequestArgs(componentVirtualFile),
      listOf(Runnable {
        transpiledComponentCache[componentVirtualFile] = newInfo
      })
    )
  }

  private class TranspiledComponentInfo(contents: TranspiledComponentFile) {
    val contentsLength: Int = contents.generatedCode.length
    val contentsHash: Int = contents.generatedCode.hashCode()
    val timestamps: Map<String, Long> = contents.mappings.associateBy({ it.fileName }, { it.sourceFile.modificationStamp })

    override fun equals(other: Any?): Boolean {
      return other === this || other is TranspiledComponentInfo
             && contentsLength == other.contentsLength
             && contentsHash == other.contentsHash
             && timestamps == other.timestamps
    }

    override fun hashCode(): Int {
      return Objects.hash(contentsLength, contentsHash, timestamps)
    }
  }

}