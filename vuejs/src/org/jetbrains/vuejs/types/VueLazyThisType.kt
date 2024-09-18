// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.vuejs.types

import com.intellij.lang.javascript.psi.JSType
import com.intellij.lang.javascript.psi.JSTypeSubstitutionContext
import com.intellij.lang.javascript.psi.JSTypeTextBuilder
import com.intellij.lang.javascript.psi.types.JSCodeBasedType
import com.intellij.lang.javascript.psi.types.JSTypeBaseImpl
import com.intellij.lang.javascript.psi.types.JSTypeSource
import com.intellij.util.ProcessingContext
import org.jetbrains.vuejs.model.VueInstanceOwner
import org.jetbrains.vuejs.model.VueNamedEntity

class VueLazyThisType(source: JSTypeSource,
                      private val instanceOwner: VueInstanceOwner)
  : JSTypeBaseImpl(source), JSCodeBasedType, VueCompleteType {

  constructor(instanceOwner: VueInstanceOwner) : this(createStrictTypeSource(instanceOwner.source), instanceOwner)

  override fun copyWithNewSource(source: JSTypeSource): JSType =
    VueLazyThisType(source, instanceOwner)

  override fun isEquivalentToWithSameClass(type: JSType, context: ProcessingContext?, allowResolve: Boolean): Boolean =
    type is VueLazyThisType && type.instanceOwner == instanceOwner

  override fun hashCode(allowResolve: Boolean): Int = instanceOwner.hashCode()

  override fun buildTypeTextImpl(format: JSType.TypeTextFormat, builder: JSTypeTextBuilder) {
    if (format == JSType.TypeTextFormat.SIMPLE) {
      builder.append("#VueLazyThisType: ")
        .append(instanceOwner.javaClass.simpleName)
      if (instanceOwner is VueNamedEntity) {
        builder.append("(").append(instanceOwner.defaultName).append(")")
      }
      return
    }
    substitute().buildTypeText(format, builder)
  }

  override fun substituteImpl(context: JSTypeSubstitutionContext): JSType =
    instanceOwner.thisType

}