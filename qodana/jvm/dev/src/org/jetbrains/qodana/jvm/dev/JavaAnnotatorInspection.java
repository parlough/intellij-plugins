// Copyright 2000-2017 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.qodana.jvm.dev;

import com.intellij.codeInspection.InspectionsBundle;
import com.intellij.dev.codeInsight.internal.GoodCodeRedVisitor;
import com.intellij.dev.codeInsight.internal.LanguageGoodCodeRedVisitors;
import com.intellij.java.dev.codeInsight.internal.GoodCodeRedInspectionTool;
import com.intellij.lang.java.JavaLanguage;
import com.intellij.psi.PsiFile;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

final class JavaAnnotatorInspection extends GoodCodeRedInspectionTool {
  @Override
  public @Nls @NotNull String getGroupDisplayName() {
    return InspectionsBundle.message("group.names.probable.bugs");
  }

  @Override
  public @Nullable GoodCodeRedVisitor getGoodCodeRedVisitor(@NotNull PsiFile file) {
    return LanguageGoodCodeRedVisitors.INSTANCE.forLanguage(JavaLanguage.INSTANCE);
  }
}
