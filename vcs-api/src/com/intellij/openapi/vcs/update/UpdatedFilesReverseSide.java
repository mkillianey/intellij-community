package com.intellij.openapi.vcs.update;

import com.intellij.openapi.vfs.VirtualFile;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UpdatedFilesReverseSide {
  // just list of same groups = another presentation/container of same
  private final UpdatedFiles myFiles;

  // children are also here
  private final Map<String, FileGroup> myGroupHolder;

  // file path, group
  private final Map<String, FileGroup> myFileIdx;

  private final static List<String> ourStoppingGroups = Arrays.asList(
      FileGroup.MERGED_WITH_CONFLICT_ID, FileGroup.UNKNOWN_ID, FileGroup.SKIPPED_ID);

  public UpdatedFilesReverseSide(final UpdatedFiles files) {
    myFiles = files;
    myGroupHolder = new HashMap<String, FileGroup>();
    myFileIdx = new HashMap<String, FileGroup>();
  }

  /**
   * removes file from group that previously contained it
   */
  public void addFileToGroup(final String groupId, final VirtualFile file, final DuplicateLevel duplicateLevel) {
    addFileToGroup(groupId, file.getPresentableUrl(), duplicateLevel);
  }

  public boolean isEmpty() {
    return myFileIdx.isEmpty();
  }

  public FileGroup getGroup(final String id) {
    return myGroupHolder.get(id);
  }

  public void addFileToGroup(final String groupId, final String file, final DuplicateLevel duplicateLevel) {
    final FileGroup newGroup = myGroupHolder.get(groupId);
    addFileToGroup(newGroup, file, duplicateLevel);
  }

  public void addFileToGroup(final FileGroup group, final String file, final DuplicateLevel duplicateLevel) {
    if (duplicateLevel.searchPreviousContainment(group.getId())) {
      final FileGroup oldGroup = myFileIdx.get(file);
      if (oldGroup != null) {
        oldGroup.remove(file);
      }
    }

    if (group != null) {
      group.add(file);
    }
    myFileIdx.put(file, group);
  }

  public UpdatedFiles getUpdatedFiles() {
    return myFiles;
  }

  public void rebuildFromUpdatedFiles() {
    myFileIdx.clear();
    myGroupHolder.clear();
    
    for (FileGroup group : myFiles.getTopLevelGroups()) {
      addGroupToIndexes(group);
    }
  }

  private void addGroupToIndexes(final FileGroup fromGroup) {
    myGroupHolder.put(fromGroup.getId(), fromGroup);

    for (String file : fromGroup.getFiles()) {
      myFileIdx.put(file, fromGroup);
    }

    for (FileGroup fromChild : fromGroup.getChildren()) {
      addGroupToIndexes(fromChild);
    }
  }

  /*private void copyGroup(final FileGroup from, final FileGroup to) {
    for (String file : from.getFiles()) {
      addFileToGroup(to, file);
    }
    for (FileGroup fromChild : from.getChildren()) {
      final FileGroup ownChild = createOrGet(new GroupParent(to), fromChild);
      copyGroup(fromChild, ownChild);
    }
  }*/

  private void copyGroup(final Parent parent, final FileGroup from, final DuplicateLevel duplicateLevel) {
    final FileGroup to = createOrGet(parent, from);

    for (String file : from.getFiles()) {
      addFileToGroup(to, file, duplicateLevel);
    }
    for (FileGroup fromChild : from.getChildren()) {
      copyGroup(new GroupParent(to), fromChild, duplicateLevel);
    }
  }

  private interface Parent {
    void accept(FileGroup group);
  }

  private class TopLevelParent implements Parent {
    public void accept(final FileGroup group) {
      myFiles.getTopLevelGroups().add(group);
    }
  }

  private static class GroupParent implements Parent {
    private final FileGroup myGroup;

    private GroupParent(final FileGroup group) {
      myGroup = group;
    }

    public void accept(final FileGroup group) {
      myGroup.addChild(group);
    }
  }

  private FileGroup createOrGet(final Parent possibleParent, final FileGroup fromGroup) {
    FileGroup ownGroup = myGroupHolder.get(fromGroup.getId());
    if (ownGroup == null) {
      ownGroup = new FileGroup(fromGroup.getUpdateName(), fromGroup.getStatusName(), fromGroup.getSupportsDeletion(),
                               fromGroup.getId(), fromGroup.myCanBeAbsent);
      possibleParent.accept(ownGroup);
      myGroupHolder.put(fromGroup.getId(), ownGroup);
    }
    return ownGroup;
  }

  public void accomulateFiles(final UpdatedFiles from, final DuplicateLevel duplicateLevel) {
    final Parent topLevel = new TopLevelParent();
    for (FileGroup fromGroup : from.getTopLevelGroups()) {
      copyGroup(topLevel, fromGroup, duplicateLevel);
  }
  }

  public boolean containErrors() {
    for (String groupId : ourStoppingGroups) {
      final FileGroup group = myGroupHolder.get(groupId);
      if ((group != null) && (! group.isEmpty())) {
        return true;
      }
    }
    return false;
  }

  public abstract static class DuplicateLevel {
    private final static List<String> ourErrorGroups = Arrays.asList(FileGroup.UNKNOWN_ID, FileGroup.SKIPPED_ID);

    abstract boolean searchPreviousContainment(final String groupId);

    private DuplicateLevel() {
    }

    public static final DuplicateLevel NO_DUPLICATES = new DuplicateLevel() {
      boolean searchPreviousContainment(final String groupId) {
        return true;
      }
    };
    public static final DuplicateLevel DUPLICATE_ERRORS = new DuplicateLevel() {
      boolean searchPreviousContainment(final String groupId) {
        return ! ourErrorGroups.contains(groupId);
      }
    };
    public static final DuplicateLevel ALLOW_DUPLICATES = new DuplicateLevel() {
      boolean searchPreviousContainment(final String groupId) {
        return false;
      }
    };
  }

  /*private static void accomulateFiles(final UpdatedFiles from, final UpdatedFiles to) {
    for (FileGroup fromGroup : from.getTopLevelGroups()) {
      final FileGroup group = to.registerGroup(fromGroup);
      // compare referencies intentionally
      if (group != fromGroup) {
        for (String file : fromGroup.getFiles()) {
          group.add(file);
        }

        for (FileGroup fromChild : fromGroup.getChildren()) {
          boolean found = false;
          for (FileGroup existingGroup : group.getChildren()) {
            if (existingGroup.getId().equals(fromChild.getId())) {
              found = true;
              for (String path : fromChild.getFiles()) {
                existingGroup.add(path);
              }
              break;
            }
          }

          if (! found) {
            group.addChild(fromChild);
          }
        }
      }
    }
  }*/
}
