// Copyright (C) 2015 The Android Open Source Project
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.gerrit.server.git;

import static com.google.common.base.Preconditions.checkState;
import static org.eclipse.jgit.revwalk.RevFlag.UNINTERESTING;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import com.google.common.collect.Multimaps;
import com.google.common.collect.SetMultimap;
import com.google.common.collect.Sets;
import com.google.gerrit.reviewdb.client.Change;
import com.google.gerrit.reviewdb.client.PatchSet;
import com.google.gerrit.reviewdb.server.ReviewDb;
import com.google.gerrit.server.change.RevisionResource;
import com.google.gwtorm.server.OrmException;

import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.revwalk.RevCommit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Helper for assigning groups to commits during {@link ReceiveCommits}.
 * <p>
 * For each commit encountered along a walk between the branch tip and the tip
 * of the push, the group of a commit is defined as follows:
 * <ul>
 *   <li>If the commit is an existing patch set of a change, the group is read
 *   from the group field in the corresponding {@link PatchSet} record.</li>
 *   <li>If all of a commit's parents are merged into the branch, then its group
 *   is its own SHA-1.</li>
 *   <li>If the commit has a single parent that is not yet merged into the
 *   branch, then its group is the same as the parent's group.<li>
 *   <li>For a merge commit, choose a parent and use that parent's group. If one
 *   of the parents has a group from a patch set, use that group, otherwise, use
 *   the group from the first parent. In addition to setting this merge commit's
 *   group, use the chosen group for all commits that would otherwise use a
 *   group from the parents that were not chosen.</li>
 *   <li>If a merge commit has multiple parents whose group comes from separate
 *   patch sets, concatenate the groups from those parents together. This
 *   indicates two side branches were pushed separately, followed by the merge.
 *   <li>
 * </ul>
 * <p>
 * Callers must call {@link #visit(RevCommit)} on all commits between the
 * current branch tip and the tip of a push, in reverse topo order (parents
 * before children). Once all commits have been visited, call {@link
 * #getGroups()} for the result.
 */
public class GroupCollector {
  private static final Logger log =
      LoggerFactory.getLogger(GroupCollector.class);

  public static List<String> getCurrentGroups(ReviewDb db, Change c)
      throws OrmException {
    PatchSet ps = db.patchSets().get(c.currentPatchSetId());
    return ps != null ? ps.getGroups() : null;
  }

  public static List<String> getDefaultGroups(PatchSet ps) {
    return ImmutableList.of(ps.getRevision().get());
  }

  public static List<String> getGroups(RevisionResource rsrc) {
    if (rsrc.getEdit().isPresent()) {
      // Groups for an edit are just the base revision's groups, since they have
      // the same parent.
      return rsrc.getEdit().get().getBasePatchSet().getGroups();
    }
    return rsrc.getPatchSet().getGroups();
  }

  private static interface Lookup {
    List<String> lookup(PatchSet.Id psId) throws OrmException;
  }

  private final Multimap<ObjectId, PatchSet.Id> patchSetsBySha;
  private final Multimap<ObjectId, String> groups;
  private final SetMultimap<String, String> groupAliases;
  private final Lookup groupLookup;

  private boolean done;

  private GroupCollector(
      Multimap<ObjectId, PatchSet.Id> patchSetsBySha,
      Lookup groupLookup) {
    this.patchSetsBySha = patchSetsBySha;
    this.groupLookup = groupLookup;
    groups = ArrayListMultimap.create();
    groupAliases = HashMultimap.create();
  }

  public GroupCollector(
      Multimap<ObjectId, Ref> changeRefsById,
      final ReviewDb db) {
    this(
        Multimaps.transformValues(
            changeRefsById,
            new Function<Ref, PatchSet.Id>() {
              @Override
              public PatchSet.Id apply(Ref in) {
                return PatchSet.Id.fromRef(in.getName());
              }
            }),
        new Lookup() {
          @Override
          public List<String> lookup(PatchSet.Id psId) throws OrmException {
            PatchSet ps = db.patchSets().get(psId);
            return ps != null ? ps.getGroups() : null;
          }
        });
  }

  @VisibleForTesting
  GroupCollector(
      Multimap<ObjectId, PatchSet.Id> patchSetsBySha,
      final ListMultimap<PatchSet.Id, String> groupLookup) {
    this(
        patchSetsBySha,
        new Lookup() {
          @Override
          public List<String> lookup(PatchSet.Id psId) {
            List<String> groups = groupLookup.get(psId);
            return !groups.isEmpty() ? groups : null;
          }
        });
  }

  public void visit(RevCommit c) {
    checkState(!done, "visit() called after getGroups()");
    Set<RevCommit> interestingParents = getInterestingParents(c);

    if (interestingParents.size() == 0) {
      // All parents are uninteresting: treat this commit as the root of a new
      // group of related changes.
      groups.put(c, c.name());
      return;
    } else if (interestingParents.size() == 1) {
      // Only one parent is new in this push. If it is the only parent, just use
      // that parent's group. If there are multiple parents, perhaps this commit
      // is a merge of a side branch. This commit belongs in that parent's group
      // in that case.
      groups.putAll(c, groups.get(interestingParents.iterator().next()));
      return;
    }

    // Multiple parents, merging at least two branches containing new commits in
    // this push.
    Set<String> thisCommitGroups = new TreeSet<>();
    Set<String> parentGroupsNewInThisPush =
        Sets.newLinkedHashSetWithExpectedSize(interestingParents.size());
    for (RevCommit p : interestingParents) {
      Collection<String> parentGroups = groups.get(p);
      if (parentGroups.isEmpty()) {
        throw new IllegalStateException(String.format(
            "no group assigned to parent %s of commit %s", p.name(), c.name()));
      }

      for (String parentGroup : parentGroups) {
        if (isGroupFromExistingPatchSet(p, parentGroup)) {
          // This parent's group is from an existing patch set, i.e. the parent
          // not new in this push. Use this group for the commit.
          thisCommitGroups.add(parentGroup);
        } else {
          // This parent's group is new in this push.
          parentGroupsNewInThisPush.add(parentGroup);
        }
      }
    }

    Iterable<String> toAlias;
    if (thisCommitGroups.isEmpty()) {
      // All parent groups were new in this push. Pick the first one and alias
      // other parents' groups to this first parent.
      String firstParentGroup = parentGroupsNewInThisPush.iterator().next();
      thisCommitGroups = ImmutableSet.of(firstParentGroup);
      toAlias = Iterables.skip(parentGroupsNewInThisPush, 1);
    } else {
      // For each parent group that was new in this push, alias it to the actual
      // computed group(s) for this commit.
      toAlias = parentGroupsNewInThisPush;
    }
    groups.putAll(c, thisCommitGroups);
    for (String pg : toAlias) {
      groupAliases.putAll(pg, thisCommitGroups);
    }
  }

  public SetMultimap<ObjectId, String> getGroups() throws OrmException {
    done = true;
    SetMultimap<ObjectId, String> result = MultimapBuilder
        .hashKeys(groups.keySet().size())
        .treeSetValues()
        .build();
    for (Map.Entry<ObjectId, Collection<String>> e
        : groups.asMap().entrySet()) {
      ObjectId id = e.getKey();
      result.putAll(id.copy(), resolveGroups(id, e.getValue()));
    }
    return result;
  }

  private Set<RevCommit> getInterestingParents(RevCommit commit) {
    Set<RevCommit> result =
        Sets.newLinkedHashSetWithExpectedSize(commit.getParentCount());
    for (RevCommit p : commit.getParents()) {
      if (!p.has(UNINTERESTING)) {
        result.add(p);
      }
    }
    return result;
  }

  private boolean isGroupFromExistingPatchSet(RevCommit commit, String group) {
    ObjectId id = parseGroup(commit, group);
    return id != null && patchSetsBySha.containsKey(id);
  }

  private Set<String> resolveGroups(ObjectId forCommit,
      Collection<String> candidates) throws OrmException {
    Set<String> actual = Sets.newTreeSet();
    Set<String> done = Sets.newHashSetWithExpectedSize(candidates.size());
    Set<String> seen = Sets.newHashSetWithExpectedSize(candidates.size());
    Deque<String> todo = new ArrayDeque<>(candidates);
    // BFS through all aliases to find groups that are not aliased to anything
    // else.
    while (!todo.isEmpty()) {
      String g = todo.removeFirst();
      if (!seen.add(g)) {
        continue;
      }
      Set<String> aliases = groupAliases.get(g);
      if (aliases.isEmpty()) {
        if (!done.contains(g)) {
          Iterables.addAll(actual, resolveGroup(forCommit, g));
          done.add(g);
        }
      } else {
        todo.addAll(aliases);
      }
    }
    return actual;
  }

  private ObjectId parseGroup(ObjectId forCommit, String group) {
    try {
      return ObjectId.fromString(group);
    } catch (IllegalArgumentException e) {
      // Shouldn't happen; some sort of corruption or manual tinkering?
      log.warn("group for commit {} is not a SHA-1: {}",
          forCommit.name(), group);
      return null;
    }
  }

  private Iterable<String> resolveGroup(ObjectId forCommit, String group)
      throws OrmException {
    ObjectId id = parseGroup(forCommit, group);
    if (id != null) {
      PatchSet.Id psId = Iterables.getFirst(patchSetsBySha.get(id), null);
      if (psId != null) {
        List<String> groups = groupLookup.lookup(psId);
        // Group for existing patch set may be missing, e.g. if group has not
        // been migrated yet.
        if (groups != null && !groups.isEmpty()) {
          return groups;
        }
      }
    }
    return ImmutableList.of(group);
  }
}
