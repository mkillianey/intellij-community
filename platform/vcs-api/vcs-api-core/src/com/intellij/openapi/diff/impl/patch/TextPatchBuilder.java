/*
 * Copyright 2000-2015 JetBrains s.r.o.
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
package com.intellij.openapi.diff.impl.patch;

import com.intellij.openapi.diff.ex.DiffFragment;
import com.intellij.openapi.diff.impl.ComparisonPolicy;
import com.intellij.openapi.diff.impl.fragments.LineFragment;
import com.intellij.openapi.diff.impl.processing.DiffCorrection;
import com.intellij.openapi.diff.impl.processing.DiffFragmentsProcessor;
import com.intellij.openapi.diff.impl.processing.DiffPolicy;
import com.intellij.openapi.diff.impl.string.DiffString;
import com.intellij.openapi.diff.impl.util.TextDiffTypeEnum;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.util.BeforeAfter;
import com.intellij.util.diff.FilesTooBigForDiffException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class TextPatchBuilder {
  private static final int CONTEXT_LINES = 3;
  @NonNls private static final String REVISION_NAME_TEMPLATE = "(revision {0})";
  @NonNls private static final String DATE_NAME_TEMPLATE = "(date {0})";

  @NotNull private final String myBasePath;
  private final boolean myIsReversePath;
  private final boolean myIsCaseSensitive;
  @Nullable private final Runnable myCancelChecker;

  private TextPatchBuilder(@NotNull String basePath,
                           boolean isReversePath,
                           boolean isCaseSensitive,
                           @Nullable Runnable cancelChecker) {
    myBasePath = basePath;
    myIsReversePath = isReversePath;
    myIsCaseSensitive = isCaseSensitive;
    myCancelChecker = cancelChecker;
  }

  private void checkCanceled() {
    if (myCancelChecker != null) {
      myCancelChecker.run();
    }
  }

  @NotNull
  public static List<FilePatch> buildPatch(@NotNull Collection<BeforeAfter<AirContentRevision>> changes,
                                           @NotNull String basePath,
                                           boolean reversePatch,
                                           boolean isCaseSensitive,
                                           @Nullable Runnable cancelChecker,
                                           boolean includeBaseText) throws VcsException {
    TextPatchBuilder builder = new TextPatchBuilder(basePath, reversePatch, isCaseSensitive, cancelChecker);
    return builder.build(changes);
  }

  @NotNull
  private List<FilePatch> build(@NotNull Collection<BeforeAfter<AirContentRevision>> changes) throws VcsException {
    List<FilePatch> result = new ArrayList<FilePatch>();
    for (BeforeAfter<AirContentRevision> c : changes) {
      checkCanceled();

      AirContentRevision beforeRevision;
      AirContentRevision afterRevision;
      if (myIsReversePath) {
        beforeRevision = c.getAfter();
        afterRevision = c.getBefore();
      }
      else {
        beforeRevision = c.getBefore();
        afterRevision = c.getAfter();
      }
      if (beforeRevision != null && beforeRevision.getPath().isDirectory()) {
        continue;
      }
      if (afterRevision != null && afterRevision.getPath().isDirectory()) {
        continue;
      }

      if ((beforeRevision != null) && beforeRevision.isBinary() || (afterRevision != null) && afterRevision.isBinary()) {
        result.add(buildBinaryPatch(myBasePath, beforeRevision, afterRevision));
        continue;
      }

      if (beforeRevision == null) {
        result.add(buildAddedFile(myBasePath, afterRevision));
        continue;
      }
      if (afterRevision == null) {
        result.add(buildDeletedFile(myBasePath, beforeRevision));
        continue;
      }

      DiffString beforeContent = getContent(beforeRevision);
      DiffString afterContent = getContent(afterRevision);
      DiffString[] beforeLines = tokenize(beforeContent);
      DiffString[] afterLines = tokenize(afterContent);

      DiffFragment[] woFormattingBlocks;
      DiffFragment[] step1lineFragments;
      try {
        woFormattingBlocks = DiffPolicy.LINES_WO_FORMATTING.buildFragments(beforeContent, afterContent);
        step1lineFragments = new DiffCorrection.TrueLineBlocks(ComparisonPolicy.DEFAULT).correctAndNormalize(woFormattingBlocks);
      }
      catch (FilesTooBigForDiffException e) {
        throw new VcsException("File '" + myBasePath + "' is too big and there are too many changes to build diff", e);
      }
      ArrayList<LineFragment> fragments = new DiffFragmentsProcessor().process(step1lineFragments);

      if (fragments.size() > 1 ||
          (fragments.size() == 1 && fragments.get(0).getType() != null && fragments.get(0).getType() != TextDiffTypeEnum.NONE)) {
        TextFilePatch patch = buildPatchHeading(myBasePath, beforeRevision, afterRevision);
        result.add(patch);

        int lastLine1 = 0;
        int lastLine2 = 0;

        while (fragments.size() > 0) {
          checkCanceled();

          List<LineFragment> adjacentFragments = getAdjacentFragments(fragments);
          if (adjacentFragments.size() > 0) {
            LineFragment first = adjacentFragments.get(0);
            LineFragment last = adjacentFragments.get(adjacentFragments.size() - 1);

            int start1 = first.getStartingLine1();
            int start2 = first.getStartingLine2();
            int end1 = last.getStartingLine1() + last.getModifiedLines1();
            int end2 = last.getStartingLine2() + last.getModifiedLines2();
            int contextStart1 = Math.max(start1 - CONTEXT_LINES, lastLine1);
            int contextStart2 = Math.max(start2 - CONTEXT_LINES, lastLine2);
            int contextEnd1 = Math.min(end1 + CONTEXT_LINES, beforeLines.length);
            int contextEnd2 = Math.min(end2 + CONTEXT_LINES, afterLines.length);

            PatchHunk hunk = new PatchHunk(contextStart1, contextEnd1, contextStart2, contextEnd2);
            patch.addHunk(hunk);

            for (LineFragment fragment : adjacentFragments) {
              checkCanceled();

              for (int i = contextStart1; i < fragment.getStartingLine1(); i++) {
                addLineToHunk(hunk, beforeLines[i], PatchLine.Type.CONTEXT);
              }
              for (int i = fragment.getStartingLine1(); i < fragment.getStartingLine1() + fragment.getModifiedLines1(); i++) {
                addLineToHunk(hunk, beforeLines[i], PatchLine.Type.REMOVE);
              }
              for (int i = fragment.getStartingLine2(); i < fragment.getStartingLine2() + fragment.getModifiedLines2(); i++) {
                addLineToHunk(hunk, afterLines[i], PatchLine.Type.ADD);
              }
              contextStart1 = fragment.getStartingLine1() + fragment.getModifiedLines1();
            }
            for (int i = contextStart1; i < contextEnd1; i++) {
              addLineToHunk(hunk, beforeLines[i], PatchLine.Type.CONTEXT);
            }
          }
        }

        checkPathEndLine(patch, c.getAfter());
      }
      else if (!beforeRevision.getPath().equals(afterRevision.getPath())) {
        TextFilePatch movedPatch = buildMovedFile(myBasePath, beforeRevision, afterRevision);
        checkPathEndLine(movedPatch, c.getAfter());
        result.add(movedPatch);
      }
    }
    return result;
  }

  private static void checkPathEndLine(@NotNull TextFilePatch filePatch, @Nullable AirContentRevision cr) throws VcsException {
    if (cr == null) return;
    if (filePatch.isDeletedFile() || filePatch.getAfterName() == null) return;
    List<PatchHunk> hunks = filePatch.getHunks();
    if (hunks.isEmpty()) return;
    PatchHunk hunk = hunks.get(hunks.size() - 1);
    List<PatchLine> lines = hunk.getLines();
    if (lines.isEmpty()) return;
    String contentAsString = cr.getContentAsString();
    if (contentAsString == null) return;
    if (!contentAsString.endsWith("\n")) {
      lines.get(lines.size() - 1).setSuppressNewLine(true);
    }
  }

  @NotNull
  private static DiffString[] tokenize(@NotNull DiffString text) {
    return text.length() == 0 ? new DiffString[]{text} : text.tokenize();
  }

  private FilePatch buildBinaryPatch(@NotNull String basePath,
                                     @Nullable AirContentRevision beforeRevision,
                                     @Nullable AirContentRevision afterRevision) throws VcsException {
    assert beforeRevision != null || afterRevision != null;
    AirContentRevision headingBeforeRevision = beforeRevision != null ? beforeRevision : afterRevision;
    AirContentRevision headingAfterRevision = afterRevision != null ? afterRevision : beforeRevision;
    byte[] beforeContent = beforeRevision != null ? beforeRevision.getContentAsBytes() : null;
    byte[] afterContent = afterRevision != null ? afterRevision.getContentAsBytes() : null;
    BinaryFilePatch patch = new BinaryFilePatch(beforeContent, afterContent);
    setPatchHeading(patch, basePath, headingBeforeRevision, headingAfterRevision);
    return patch;
  }

  private static void addLineToHunk(@NotNull PatchHunk hunk, @NotNull DiffString line, @NotNull PatchLine.Type type) {
    PatchLine patchLine;
    if (!line.endsWith('\n')) {
      patchLine = new PatchLine(type, line.toString());
      patchLine.setSuppressNewLine(true);
    }
    else {
      patchLine = new PatchLine(type, line.substring(0, line.length() - 1).toString());
    }
    hunk.addLine(patchLine);
  }

  @NotNull
  private TextFilePatch buildMovedFile(@NotNull String basePath,
                                       @NotNull AirContentRevision beforeRevision,
                                       @NotNull AirContentRevision afterRevision) throws VcsException {
    TextFilePatch result = buildPatchHeading(basePath, beforeRevision, afterRevision);
    PatchHunk hunk = new PatchHunk(0, 0, 0, 0);
    result.addHunk(hunk);
    return result;
  }

  @NotNull
  private TextFilePatch buildAddedFile(@NotNull String basePath,
                                       @NotNull AirContentRevision afterRevision) throws VcsException {
    DiffString content = getContent(afterRevision);
    DiffString[] lines = tokenize(content);
    TextFilePatch result = buildPatchHeading(basePath, afterRevision, afterRevision);
    PatchHunk hunk = new PatchHunk(-1, -1, 0, lines.length);
    for (DiffString line : lines) {
      checkCanceled();
      addLineToHunk(hunk, line, PatchLine.Type.ADD);
    }
    result.addHunk(hunk);
    return result;
  }

  @NotNull
  private TextFilePatch buildDeletedFile(@NotNull String basePath,
                                         @NotNull AirContentRevision beforeRevision) throws VcsException {
    DiffString content = getContent(beforeRevision);
    DiffString[] lines = tokenize(content);
    TextFilePatch result = buildPatchHeading(basePath, beforeRevision, beforeRevision);
    PatchHunk hunk = new PatchHunk(0, lines.length, -1, -1);
    for (DiffString line : lines) {
      checkCanceled();
      addLineToHunk(hunk, line, PatchLine.Type.REMOVE);
    }
    result.addHunk(hunk);
    return result;
  }

  @NotNull
  private static List<LineFragment> getAdjacentFragments(@NotNull ArrayList<LineFragment> fragments) {
    List<LineFragment> result = new ArrayList<LineFragment>();
    int endLine = -1;
    while (!fragments.isEmpty()) {
      LineFragment fragment = fragments.get(0);
      if (fragment.getType() == null || fragment.getType() == TextDiffTypeEnum.NONE) {
        fragments.remove(0);
        continue;
      }

      if (result.isEmpty() || endLine + CONTEXT_LINES >= fragment.getStartingLine1() - CONTEXT_LINES) {
        result.add(fragment);
        fragments.remove(0);
        endLine = fragment.getStartingLine1() + fragment.getModifiedLines1();
      }
      else {
        break;
      }
    }
    return result;
  }

  @NotNull
  private String getRelativePath(@NotNull String basePath, @NotNull String secondPath) {
    String baseModified = FileUtil.toSystemIndependentName(basePath);
    String secondModified = FileUtil.toSystemIndependentName(secondPath);

    String relPath = FileUtil.getRelativePath(baseModified, secondModified, '/', myIsCaseSensitive);
    if (relPath == null) return secondModified;
    return relPath;
  }

  @NotNull
  private static String getRevisionName(@NotNull AirContentRevision revision) {
    String revisionName = revision.getRevisionNumber();
    if (revisionName != null) {
      return MessageFormat.format(REVISION_NAME_TEMPLATE, revisionName);
    }
    return MessageFormat.format(DATE_NAME_TEMPLATE, Long.toString(revision.getPath().lastModified()));
  }

  @NotNull
  private TextFilePatch buildPatchHeading(@NotNull String basePath,
                                          @NotNull AirContentRevision beforeRevision,
                                          @NotNull AirContentRevision afterRevision) {
    TextFilePatch result = new TextFilePatch(afterRevision.getCharset());
    setPatchHeading(result, basePath, beforeRevision, afterRevision);
    return result;
  }

  private void setPatchHeading(@NotNull FilePatch result,
                               @NotNull String basePath,
                               @NotNull AirContentRevision beforeRevision,
                               @NotNull AirContentRevision afterRevision) {
    result.setBeforeName(getRelativePath(basePath, beforeRevision.getPath().getPath()));
    result.setBeforeVersionId(getRevisionName(beforeRevision));

    result.setAfterName(getRelativePath(basePath, afterRevision.getPath().getPath()));
    result.setAfterVersionId(getRevisionName(afterRevision));
  }

  @NotNull
  private static DiffString getContent(@NotNull AirContentRevision revision) throws VcsException {
    String beforeContent = revision.getContentAsString();
    if (beforeContent == null) {
      throw new VcsException("Failed to fetch old content for file " + revision.getPath().getPath());
    }
    return DiffString.create(beforeContent);
  }
}
