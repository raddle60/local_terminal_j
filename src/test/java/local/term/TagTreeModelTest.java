package local.term;

import org.junit.jupiter.api.Test;

import javax.swing.JTree;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the narrow mutators on {@link TagTreeModel}: precise Swing
 * events that preserve selection / expansion state. We attach a listener
 * that records the event type + path / indices / children so we can
 * verify the live mutation never fired {@code nodeStructureChanged} on
 * the root.
 */
class TagTreeModelTest {

  private TagNode.Shell shell(String name) {
    return new TagNode.Shell(UUID.randomUUID(), name,
        "C:\\Windows\\System32\\cmd.exe", null, "C:\\");
  }
  private TagNode.Folder folder(String name, List<TagNode> kids) {
    return new TagNode.Folder(UUID.randomUUID(), name, kids);
  }

  /** Records model events so tests can assert what fired. */
  private static final class Recorder implements TreeModelListener {
    String lastEvent;
    TreePath lastPath;
    int[] lastIndices;
    Object[] lastChildren;
    int insertCount, changeCount, removeCount, structureCount;

    @Override public void treeNodesInserted(TreeModelEvent e) {
      lastEvent = "inserted"; lastPath = e.getTreePath();
      lastIndices = e.getChildIndices(); lastChildren = e.getChildren();
      insertCount++;
    }
    @Override public void treeNodesChanged(TreeModelEvent e) {
      lastEvent = "changed"; lastPath = e.getTreePath();
      lastIndices = e.getChildIndices(); lastChildren = e.getChildren();
      changeCount++;
    }
    @Override public void treeNodesRemoved(TreeModelEvent e) {
      lastEvent = "removed"; lastPath = e.getTreePath();
      lastIndices = e.getChildIndices(); lastChildren = e.getChildren();
      removeCount++;
    }
    @Override public void treeStructureChanged(TreeModelEvent e) {
      lastEvent = "structure"; lastPath = e.getTreePath();
      structureCount++;
    }
  }

  private TagTreeModel newModel(TagNode root, Recorder rec) {
    TagTreeModel m = new TagTreeModel(root);
    m.addTreeModelListener(rec);
    return m;
  }

  @Test
  void findMutable_returnsNodeForId() {
    TagNode.Shell s = shell("leaf");
    TagNode.Folder root = folder("root", List.of(s));
    TagTreeModel m = new TagTreeModel(root);
    assertSame(m.findMutable(s.id()).getUserObject(), s);
    assertSame(m.findMutable(root.id()), m.getRoot());
  }

  @Test
  void findMutable_returnsNullForUnknownId() {
    TagTreeModel m = new TagTreeModel(folder("root", List.of()));
    assertNull(m.findMutable(UUID.randomUUID()));
  }

  @Test
  void appendChild_appendsAndFiresInserted() {
    TagNode.Shell s = shell("leaf");
    TagNode.Folder root = folder("root", List.of());
    Recorder r = new Recorder();
    TagTreeModel m = newModel(root, r);

    m.appendChild(root.id(), s);

    // rootTag reflects the change.
    assertEquals(1, m.rootTag().children().size());
    assertEquals(s.id(), m.rootTag().children().get(0).id());
    // Insertion event fired exactly once, on the root's path, with the new index.
    assertEquals(1, r.insertCount);
    // Root-parent insertions deliberately ALSO fire nodeStructureChanged so
    // a JTree with rootVisible=false actually shows the new top-level row.
    // Sub-folder insertions keep the precise-only contract (see
    // preciseMutationsNeverTriggerStructureChanged on a non-root parent).
    assertEquals(1, r.structureCount, "root insertion must refresh JTree rows");
    assertNotNull(r.lastPath);
    assertArrayEquals(new int[]{0}, r.lastIndices);
    assertEquals(1, r.lastChildren.length);
    assertSame(s, ((DefaultMutableTreeNode) r.lastChildren[0]).getUserObject());
  }

  @Test
  void appendChild_onHiddenRootRefreshesJTreeRows() {
    TagNode.Folder root = folder("root", List.of());
    TagTreeModel m = new TagTreeModel(root);
    JTree tree = new JTree(m);
    tree.setRootVisible(false);
    tree.setSize(200, 400);
    tree.doLayout();
    assertEquals(0, tree.getRowCount());

    m.appendChild(root.id(), folder("top", List.of()));
    tree.doLayout();

    assertEquals(1, tree.getRowCount());
  }

  @Test
  void appendChild_replacesExistingChildById_andFiresChanged() {
    TagNode.Shell original = shell("leaf");
    TagNode.Folder root = folder("root", List.of(original));
    Recorder r = new Recorder();
    TagTreeModel m = newModel(root, r);

    TagNode.Shell replacement = new TagNode.Shell(original.id(), "leaf-renamed",
        original.shellPath(), original.iconPath(), original.startPath());
    m.appendChild(root.id(), replacement);

    assertEquals(1, m.rootTag().children().size());
    assertEquals("leaf-renamed", m.rootTag().children().get(0).name());
    assertEquals(1, r.changeCount);
    assertEquals(0, r.structureCount, "must not rebuild the whole tree");
    assertEquals(0, r.insertCount);
  }

  @Test
  void appendChild_isNoopOnShellParent() {
    TagNode.Folder root = folder("root", List.of());
    TagNode.Shell leaf = shell("leaf");
    TagTreeModel m = new TagTreeModel(folder("root", List.of(leaf)));
    Recorder r = new Recorder();
    m.addTreeModelListener(r);

    TagNode.Shell orphan = shell("orphan");
    m.appendChild(leaf.id(), orphan); // leaf is a shell; must no-op.

    assertEquals(0, r.insertCount);
    assertEquals(0, r.changeCount);
    assertEquals(0, r.structureCount);
  }

  @Test
  void removeChild_firesRemovedOnParentPath() {
    TagNode.Shell s1 = shell("a");
    TagNode.Shell s2 = shell("b");
    TagNode.Folder root = folder("root", List.of(s1, s2));
    Recorder r = new Recorder();
    TagTreeModel m = newModel(root, r);

    m.removeChild(root.id(), s1.id());

    assertEquals(1, m.rootTag().children().size());
    assertEquals(s2.id(), m.rootTag().children().get(0).id());
    assertEquals(1, r.removeCount);
    assertEquals(0, r.structureCount);
    assertArrayEquals(new int[]{0}, r.lastIndices);
  }

  @Test
  void removeChild_unknownIdsAreNoops() {
    TagNode.Folder root = folder("root", List.of());
    Recorder r = new Recorder();
    TagTreeModel m = newModel(root, r);

    m.removeChild(root.id(), UUID.randomUUID());
    m.removeChild(UUID.randomUUID(), UUID.randomUUID());

    assertEquals(0, r.removeCount);
    assertEquals(0, r.structureCount);
  }

  @Test
  void nodeChanged_firesChangedOnParentPath() {
    TagNode.Shell s = shell("a");
    TagNode.Folder root = folder("root", List.of(s));
    Recorder r = new Recorder();
    TagTreeModel m = newModel(root, r);

    TagNode.Shell edited = new TagNode.Shell(s.id(), "a-edited",
        s.shellPath(), s.iconPath(), s.startPath());
    m.nodeChanged(s.id(), edited);

    assertEquals("a-edited", m.rootTag().children().get(0).name());
    assertEquals(1, r.changeCount);
    assertEquals(0, r.structureCount);
    // The changed event was scoped to the parent path with the child's index.
    assertNotNull(r.lastPath);
    assertArrayEquals(new int[]{0}, r.lastIndices);
  }

  @Test
  void nodeChanged_unknownIdIsNoop() {
    TagNode.Folder root = folder("root", List.of());
    Recorder r = new Recorder();
    TagTreeModel m = newModel(root, r);

    m.nodeChanged(UUID.randomUUID(), folder("x", List.of()));

    assertEquals(0, r.changeCount);
    assertEquals(0, r.structureCount);
  }

  @Test
  void preciseMutationsNeverTriggerStructureChanged() {
    // Catch-all: any of the narrow mutators firing structureChanged would
    // collapse the JTree's selection/expansion state — the bug we're
    // fixing. This test runs every mutator in sequence and asserts.
    // Sub-folder insertions must stay precise-only; only root-parent
    // insertions get the extra nodeStructureChanged (covered by
    // appendChild_onHiddenRootRefreshesJTreeRows and the structureCount
    // assertion in appendChild_appendsAndFiresInserted).
    TagNode.Folder child = folder("child", List.of());
    TagNode.Folder root = folder("root", List.of(child, shell("a")));
    Recorder r = new Recorder();
    TagTreeModel m = newModel(root, r);

    m.nodeChanged(child.id(), child);
    m.appendChild(child.id(), shell("nested"));
    m.removeChild(child.id(), shell("nested").id());
    // Only the (synthetic-root) parent, when its parent is the Swing root
    // and root is hidden, triggers nodeStructureChanged — see class doc.
    // Here every mutation targets a non-root parent, so the count stays 0.
    assertEquals(0, r.structureCount,
        "sub-folder live mutations must never fire nodeStructureChanged");
  }

  private TagNode.Shell shell(UUID id, String name) {
    return new TagNode.Shell(id, name, "C:\\bin\\cmd.exe", null, "C:\\");
  }

  @Test
  void moveNode_withinSameFolder_insertsAtIndex() {
    UUID parentId = new UUID(0L, 1L);
    UUID aId = new UUID(0L, 2L);
    UUID bId = new UUID(0L, 3L);
    UUID cId = new UUID(0L, 4L);
    TagNode.Folder parent = new TagNode.Folder(parentId, "P",
        java.util.List.of(shell(aId, "a"), shell(bId, "b"), shell(cId, "c")), true);
    TagNode.Folder root = new TagNode.Folder(new UUID(0L, 0L), "root",
        java.util.List.of(parent));
    TagTreeModel model = new TagTreeModel(root);

    model.moveNode(cId, parentId, 0);  // move c to the front

    TagNode newRoot = model.rootTag();
    TagNode.Folder newParent = (TagNode.Folder) newRoot.children().get(0);
    assertEquals(cId, newParent.children().get(0).id());
    assertEquals(aId, newParent.children().get(1).id());
    assertEquals(bId, newParent.children().get(2).id());
  }

  @Test
  void moveNode_acrossFolders_firesRemoveAndInsert() {
    UUID p1Id = new UUID(0L, 1L);
    UUID p2Id = new UUID(0L, 2L);
    UUID movingId = new UUID(0L, 3L);
    UUID aId = new UUID(0L, 4L);
    TagNode.Folder p1 = new TagNode.Folder(p1Id, "P1",
        java.util.List.of(shell(movingId, "moving"), shell(aId, "a")), true);
    TagNode.Folder p2 = new TagNode.Folder(p2Id, "P2", java.util.List.of(), true);
    TagNode.Folder root = new TagNode.Folder(new UUID(0L, 0L), "root",
        java.util.List.of(p1, p2));
    Recorder r = new Recorder();
    TagTreeModel model = newModel(root, r);

    model.moveNode(movingId, p2Id, 0);

    TagNode newRoot = model.rootTag();
    TagNode.Folder newP1 = (TagNode.Folder) newRoot.children().get(0);
    TagNode.Folder newP2 = (TagNode.Folder) newRoot.children().get(1);
    assertEquals(1, newP1.children().size(), "source folder should have one less child");
    assertEquals(aId, newP1.children().get(0).id());
    assertEquals(1, newP2.children().size(), "destination folder should have the moved child");
    assertEquals(movingId, newP2.children().get(0).id());

    assertEquals(1, r.removeCount, "expected one treeNodesRemoved");
    assertEquals(1, r.insertCount, "expected one treeNodesInserted");
  }

  @Test
  void moveNode_sameIdAndParentAndIndex_isNoOp() {
    UUID parentId = new UUID(0L, 1L);
    UUID aId = new UUID(0L, 2L);
    TagNode.Folder parent = new TagNode.Folder(parentId, "P",
        java.util.List.of(shell(aId, "a")), true);
    TagNode.Folder root = new TagNode.Folder(new UUID(0L, 0L), "root",
        java.util.List.of(parent));
    Recorder r = new Recorder();
    TagTreeModel model = newModel(root, r);

    model.moveNode(aId, parentId, 0);  // a is already at index 0 of parent

    assertEquals(0, r.insertCount, "no-op move must fire zero events");
    assertEquals(0, r.removeCount);
    assertEquals(0, r.changeCount);
    assertEquals(0, r.structureCount);
  }

  // -----------------------------------------------------------------
  // Regression tests: persistent expansion state (Bug 1 + Bug 2)
  // -----------------------------------------------------------------

  /**
   * Regression for Bug 1: {@link TagTreeModel#rootTag()} previously used
   * the 3-arg {@link TagNode.Folder} constructor, which silently reset
   * every folder's {@code expanded} flag to {@code true}. That meant the
   * user's collapse choice was dropped the moment any other code asked
   * the model for the current root — e.g. {@link TagTreePanel}'s
   * {@code persistExpansion} before every save.
   *
   * <p>The fix uses the 4-arg constructor and threads {@code expanded}
   * through {@code rootTag()}. This test pins the contract on the Swing
   * tree path.
   */
  @Test
  void rootTag_preservesExpansionFlagThroughTree() {
    UUID aId = new UUID(0L, 1L);
    UUID bId = new UUID(0L, 2L);
    UUID rootId = new UUID(0L, 0L);
    TagNode.Folder a = new TagNode.Folder(aId, "A", List.of(), false);
    TagNode.Folder b = new TagNode.Folder(bId, "B", List.of(), true);
    TagNode.Folder root = new TagNode.Folder(rootId, "root", List.of(a, b));
    TagTreeModel model = new TagTreeModel(root);

    // Build the same kind of tree the Swing panel sees: a synthetic
    // DefaultMutableTreeNode root whose userObject is `root`.
    DefaultMutableTreeNode aNode = (DefaultMutableTreeNode) model.findMutable(aId);
    DefaultMutableTreeNode bNode = (DefaultMutableTreeNode) model.findMutable(bId);
    assertFalse(((TagNode.Folder) aNode.getUserObject()).expanded(),
        "sanity: A was constructed expanded=false");
    assertTrue(((TagNode.Folder) bNode.getUserObject()).expanded(),
        "sanity: B was constructed expanded=true");

    // rootTag() must preserve both flags.
    TagNode rebuilt = model.rootTag();
    assertTrue(rebuilt instanceof TagNode.Folder);
    TagNode.Folder rebuiltA = (TagNode.Folder) rebuilt.children().get(0);
    TagNode.Folder rebuiltB = (TagNode.Folder) rebuilt.children().get(1);
    assertFalse(rebuiltA.expanded(), "rootTag() must NOT reset A.expanded to true");
    assertTrue(rebuiltB.expanded(), "rootTag() must preserve B.expanded=true");
  }

  /**
   * Regression for Bug 1: every narrow mutator ({@code appendChild},
   * {@code removeChild}, {@code moveNode}) routes through {@code rootTag()}
   * for the persisted snapshot. None of them must drop a folder's
   * {@code expanded} flag.
   */
  @Test
  void rootTag_preservesExpansionFlag_afterMutations() {
    UUID aId = new UUID(0L, 1L);
    UUID bId = new UUID(0L, 2L);
    UUID shellId = new UUID(0L, 3L);
    UUID rootId = new UUID(0L, 0L);
    TagNode.Folder a = new TagNode.Folder(aId, "A", List.of(), false);
    TagNode.Folder b = new TagNode.Folder(bId, "B", List.of(), true);
    TagNode.Folder root = new TagNode.Folder(rootId, "root", List.of(a, b));
    TagTreeModel model = new TagTreeModel(root);
    TagNode.Shell leaf = shell(shellId, "leaf");

    // appendChild
    model.appendChild(aId, leaf);
    // removeChild
    model.removeChild(bId, /* unknown id to avoid touching b.children() */ UUID.randomUUID());
    // moveNode: no-op because shell isn't where we say; this still forces
    // rootTag() to be re-walked. Use a real move between two folders.
    TagNode.Folder c = new TagNode.Folder(new UUID(0L, 4L), "C", List.of(leaf), true);
    model.appendChild(rootId, c);
    model.moveNode(leaf.id(), c.id(), 0); // already there — no-op
    // Make a real move: relocate the shell from A to C.
    model.appendChild(aId, leaf);
    model.moveNode(leaf.id(), c.id(), 0);

    TagNode rebuilt = model.rootTag();
    TagNode.Folder rebuiltA = (TagNode.Folder) findChildById(rebuilt, aId);
    TagNode.Folder rebuiltB = (TagNode.Folder) findChildById(rebuilt, bId);
    assertNotNull(rebuiltA, "A must still exist after the mutations");
    assertNotNull(rebuiltB, "B must still exist after the mutations");
    assertFalse(rebuiltA.expanded(),
        "A.expanded must remain false across appendChild/removeChild/moveNode");
    assertTrue(rebuiltB.expanded(),
        "B.expanded must remain true across appendChild/removeChild/moveNode");
  }

  /**
   * Regression for Bug 1 + Bug 2 end-to-end. Simulates the user collapsing
   * a folder, then performing an unrelated mutation (append a child to a
   * different folder), then persisting, then reloading from disk.
   *
   * <p>Before the fixes:
   * <ul>
   *   <li>Bug 2 meant {@code persistExpansion} never called
   *       {@code setUserObject(updated)}, so the Swing tree kept its old
   *       {@code expanded=true} on the userObject.</li>
   *   <li>Bug 1 meant {@code rootTag()} always rebuilt the tree with
   *       {@code expanded=true}, regardless of the Swing tree's state.</li>
   * </ul>
   * Combined, the collapse was silently lost on the next save. With both
   * fixes, the collapse survives a save → mutate → save → reload cycle.
   */
  @Test
  void expansion_collapsedFolderSurvivesMutateAndReload() throws IOException {
    UUID aId = new UUID(0L, 1L);
    UUID bId = new UUID(0L, 2L);
    UUID rootId = new UUID(0L, 0L);
    TagNode.Folder a = new TagNode.Folder(aId, "A", List.of(), false);
    TagNode.Folder b = new TagNode.Folder(bId, "B", List.of(), true);
    TagNode.Folder root = new TagNode.Folder(rootId, "root", List.of(a, b));
    Path tmp = Files.createTempFile("expansion-regression-", ".json");
    try {
      TagConfigStore store = new TagConfigStore(tmp);

      // Step 1: write A.expanded=false, B.expanded=true to disk.
      store.save(root);

      // Step 2: reload into a TagTreeModel — the panel's load path.
      TagTreeModel model = new TagTreeModel(store.load());
      TagNode.Folder loadedA =
          (TagNode.Folder) model.rootTag().children().get(0);
      assertFalse(loadedA.expanded(), "A must be collapsed on reload");

      // Step 3: simulate persistExpansion collapsing a sibling path by
      // updating the Swing userObject directly (mirrors the panel's
      // persistExpansion, including the Bug 2 fix: setUserObject keeps
      // the tree in sync before rootTag() is called again).
      DefaultMutableTreeNode bNode = model.findMutable(bId);
      TagNode.Folder currentB = (TagNode.Folder) bNode.getUserObject();
      bNode.setUserObject(currentB.withExpanded(true)); // already true → identity
      // Force a real change elsewhere, mirroring what the listener would
      // do for A when it gets collapsed.
      DefaultMutableTreeNode aNode = model.findMutable(aId);
      TagNode.Folder currentA = (TagNode.Folder) aNode.getUserObject();
      // currentA was loaded with expanded=false (preserved by parseNode);
      // re-asserting it is an identity short-circuit, not the regression
      // we want. Drive the *different* mutation we care about: collapse B.
      // (TagNode.Folder.withExpanded returns `this` when unchanged, so the
      //  identity shortcut still works after both fixes.)
      bNode.setUserObject(currentB.withExpanded(false));

      // Step 4: append a child to A — the kind of unrelated mutation that
      // historically won the race and overwrote A's collapsed flag.
      TagNode.Shell newLeaf = shell("new-leaf");
      model.appendChild(aId, newLeaf);

      // Step 5: persist via the same path the panel uses — rootTag() then
      // store.save(). Bug 1 fix is what keeps A.expanded=false here.
      store.save((TagNode.Folder) model.rootTag());

      // Step 6: reload from disk and assert A is still collapsed.
      TagNode reloaded = store.load();
      TagNode.Folder reloadedA = (TagNode.Folder) reloaded.children().get(0);
      TagNode.Folder reloadedB = (TagNode.Folder) reloaded.children().get(1);
      assertFalse(reloadedA.expanded(),
          "A must STILL be collapsed after appendChild + save + reload");
      assertFalse(reloadedB.expanded(),
          "B must be collapsed after the persistExpansion simulation");
    } finally {
      Files.deleteIfExists(tmp);
    }
  }

  /** Walk {@code root} and return the immediate or descendant Folder with id {@code id}. */
  private static TagNode findChildById(TagNode root, UUID id) {
    if (root.id().equals(id)) return root;
    if (!root.isFolder()) return null;
    for (TagNode c : root.children()) {
      TagNode hit = findChildById(c, id);
      if (hit != null) return hit;
    }
    return null;
  }
}
