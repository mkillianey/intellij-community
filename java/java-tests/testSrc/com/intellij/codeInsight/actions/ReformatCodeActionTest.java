/*
 * Copyright 2000-2013 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.codeInsight.actions;

import com.intellij.openapi.module.Module;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiFile;
import com.intellij.util.containers.ContainerUtil;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;

/**
 * @author Lepenkin Y.A.
 */
public class ReformatCodeActionTest extends AbstractLayoutCodeProcessorTest {

  private static String[] classNames = {"Vasya", "Main", "Oiie", "Ololo"};

  public void testReformatAndOptimizeMultipleFiles() throws IOException {
    List<PsiFile> files = createTestFiles(getTempRootDirectory(), classNames);

    //todo mock this guy
    //LastRunReformatCodeOptionsProvider provider = new LastRunReformatCodeOptionsProvider(PropertiesComponent.getInstance());

    performReformatActionOnSelectedFiles(files);
    checkFormationAndImportsOptimizationFor(files);
  }

  public void testReformatSingleSelected_FileWithoutEditor() throws IOException {
    //todo fill
  }

  public void testReformatAndOptimizeFileFromEditor() throws IOException {
    List<PsiFile> files = createTestFiles(getTempRootDirectory(), classNames);
    injectMockDialogFlags(new MockReformatFileSettings().setOptimizeImports(true));

    PsiFile fileToFormat = files.get(0);
    List<PsiFile> shouldNotBeFormatted = files.subList(1, files.size());

    performReformatActionOnFileInEditor(fileToFormat);
    checkFormationAndImportsOptimizationFor(Arrays.asList(fileToFormat));
    checkNoProcessingWasPerformedOn(shouldNotBeFormatted);
  }

  public void testOptimizeAndReformatOnlySelectedFiles() throws IOException {
    List<PsiFile> files = createTestFiles(getTempRootDirectory(), classNames);
    List<PsiFile> forProcessing = ContainerUtil.newArrayList(files.get(0), files.get(1));
    List<PsiFile> noProcessing = ContainerUtil.newArrayList(files.get(2), files.get(3));

    injectMockDialogFlags(new MockReformatFileSettings().setOptimizeImports(true));

    performReformatActionOnSelectedFiles(forProcessing);

    checkFormationAndImportsOptimizationFor(forProcessing);
    checkNoProcessingWasPerformedOn(noProcessing);
  }

  public void testOptimizeAndReformat_AllFilesInDirectoryIncludeSubdirs() throws IOException {
    //todo create tests for AbstractLayoutCodeProcessor
  }

  public void testOptimizeAndReformatInModule() throws IOException {
    Module module = createModuleWithSourceRoot("newModule");
    VirtualFile srcDir = ModuleRootManager.getInstance(module).getSourceRoots()[0];
    List<PsiFile> files = createTestFiles(srcDir, classNames);
    injectMockDialogFlags(new MockReformatFileSettings().setOptimizeImports(true));

    performReformatActionOnModule(module, files.subList(0, 1));

    checkFormationAndImportsOptimizationFor(files);
  }
}
