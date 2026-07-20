package local.term;

import javax.swing.event.TreeModelEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.UUID;

/**
 * Swing {@link javax.swing.tree.TreeModel} backed by a root {@link TagNode}.
 *
 * <p>The Swing tree (the {@code DefaultMutableTreeNode}s that the
 * {@link javax.swing.JTree} renders) IS the source of truth. Each node's
 * {@code userObject} holds its current {@link TagNode} fields; node
 * children list <em>is</em> the tree structure. {@link #rootTag()} walks
 * the Swing tree on demand to construct an immutable {@link TagNode} tree
 * — so we never have to keep a parallel userObject chain in sync.
 *
 * <p>Mutations are exposed as narrow operations that update the Swing tree
 * AND fire precise {@code treeNodesInserted}/{@code treeNodesChanged}/
 * {@code treeNodesRemoved} events scoped to the affected path. The JTree
 * keeps its selection, expansion and scroll state instead of doing a
 * {@code nodeStructureChanged} on the root and rebuilding from scratch.
 *
 * <p>The heavy {@link #replaceRoot(TagNode)} reload fires {@code
 * nodeStructureChanged} on purpose — it is what {@link TagTreePanel#reload}
 * uses after an external edit rewrites the file from disk. Live mutations
 * from the UI should NEVER call it.
 */
public class TagTreeModel extends DefaultTreeModel {

  public TagTreeModel(TagNode root) {
    super(toMutable(root));
  }

  /** Returns the root as a {@link TagNode} derived from the current Swing tree. */
  public TagNode rootTag() {
    return tagFor((DefaultMutableTreeNode) getRoot());
  }

  /**
   * Replace the entire tree and notify listeners with
   * {@code nodeStructureChanged} — used for full reload from disk after
   * an external edit. Live mutations from the UI should NOT call this;
   * use {@link #appendChild}, {@link #removeChild}, {@link #nodeChanged}.
   */
  public void replaceRoot(TagNode newRoot) {
    setRoot(toMutable(newRoot));
    nodeStructureChanged((TreeNode) getRoot());
  }

  /**
   * Find the Swing tree path (root→...→target) for a {@link TagNode} id,
   * or {@code null} when no such node exists.
   */
  public javax.swing.tree.TreePath pathFor(UUID id) {
    return findPathFor((DefaultMutableTreeNode) getRoot(), id);
  }

  /**
   * Find the {@link DefaultMutableTreeNode} whose userObject tag has the
   * given id, walking the Swing tree recursively. Returns {@code null}
   * when no such node exists.
   */
  public DefaultMutableTreeNode findMutable(UUID id) {
    return findMutable((DefaultMutableTreeNode) getRoot(), id);
  }

  /**
   * Replace the userObject of the Swing node with id {@code targetId} and
   * fire {@code treeNodesChanged} scoped to its parent's path. No-op when
   * no such id exists. For the root, fires on the root path.
   */
  public void nodeChanged(UUID targetId, TagNode newTag) {
    DefaultMutableTreeNode node = findMutable(targetId);
    if (node == null) return;
    node.setUserObject(newTag);
    DefaultMutableTreeNode parent = (DefaultMutableTreeNode) node.getParent();
    if (parent == null) {
      fireTreeNodesChanged(this, node.getPath(), null, null);
      return;
    }
    int idx = parent.getIndex(node);
    fireTreeNodesChanged(this, parent.getPath(),
        new int[]{idx}, new Object[]{node});
  }

  /**
   * Append {@code child} to the folder with id {@code parentId}. If the
   * folder already has a child with the same id the existing Swing child
   * is replaced in place (mirrors {@link TagNode.Folder#addOrReplaceChild}).
   * Fires {@code treeNodesInserted} (or {@code treeNodesChanged} for the
   * in-place replace). No-op when no folder with that id exists, or when
   * {@code parentId} identifies a {@link TagNode.Shell} (shells are leaves,
   * so attaching to them would be a programmer error — we silently drop
   * the request rather than throw, to keep the UI from blowing up on a
   * stale structure).
   */
  public void appendChild(UUID parentId, TagNode child) {
    DefaultMutableTreeNode parentNode = findMutable(parentId);
    if (parentNode == null) return;
    if (!(parentNode.getUserObject() instanceof TagNode.Folder)) return;

    int existingIdx = indexOfChildWithId(parentNode, child.id());
    if (existingIdx >= 0) {
      DefaultMutableTreeNode existing = (DefaultMutableTreeNode) parentNode.getChildAt(existingIdx);
      existing.setUserObject(child);
      existing.removeAllChildren();
      rebuildChildren(existing, child);
      fireTreeNodesChanged(this, parentNode.getPath(),
          new int[]{existingIdx}, new Object[]{existing});
      return;
    }

    DefaultMutableTreeNode childNode = new DefaultMutableTreeNode(child);
    rebuildChildren(childNode, child);
    parentNode.add(childNode);
    int idx = parentNode.getChildCount() - 1;
    fireTreeNodesInserted(this, parentNode.getPath(),
        new int[]{idx}, new Object[]{childNode});

    // When the parent IS the Swing root and the JTree has rootVisible=false,
    // the precise treeNodesInserted event alone does NOT make the JTree
    // add a visible row for the new child — a known Swing quirk: the
    // invisible root's row mapping is not refreshed by an insertion event
    // on the root path. Fire an extra nodeStructureChanged on the root so
    // the JTree rebuilds its row map (and finally shows the new top-level
    // entry). The precise inserted event above still fires first, so any
    // non-JTree listener that wants the precise mutation still gets it.
    // We deliberately keep this scoped to root-parent insertions only:
    // sub-folder additions use the precise event exclusively, preserving
    // selection/expansion state per the class-level contract.
    if (parentNode.getParent() == null) {
      nodeStructureChanged(parentNode);
    }
  }

  /**
   * Remove the child whose id matches {@code childId} from the folder with
   * id {@code parentId}; fires {@code treeNodesRemoved} on the parent's
   * path. No-op when either id is unknown or the parent is a Shell.
   */
  public void removeChild(UUID parentId, UUID childId) {
    DefaultMutableTreeNode parentNode = findMutable(parentId);
    if (parentNode == null) return;
    if (!(parentNode.getUserObject() instanceof TagNode.Folder)) return;

    int idx = indexOfChildWithId(parentNode, childId);
    if (idx < 0) return;

    DefaultMutableTreeNode removed = (DefaultMutableTreeNode) parentNode.getChildAt(idx);
    parentNode.remove(idx);
    fireTreeNodesRemoved(this, parentNode.getPath(),
        new int[]{idx}, new Object[]{removed});
  }

  /**
   * Move the node with id {@code sourceId} to become a child of the folder
   * with id {@code newParentId} at the given {@code insertIndex}.
   *
   * <p>No-op when either id is unknown, when the source is the synthetic
   * root, when the new parent is a Shell, or when the source is already
   * at the requested position (idempotent). The caller (the transfer
   * handler) is expected to have validated the move for higher-level
   * constraints (no self-drop, no cycle); this method does structural
   * work only.
   *
   * <p>Fires a precise {@code treeNodesRemoved} on the old parent path
   * and a {@code treeNodesInserted} on the new parent path.
   */
  public void moveNode(UUID sourceId, UUID newParentId, int insertIndex) {
    DefaultMutableTreeNode source = findMutable(sourceId);
    DefaultMutableTreeNode newParent = findMutable(newParentId);
    if (source == null || newParent == null) return;
    if (!(newParent.getUserObject() instanceof TagNode.Folder)) return;
    if (sourceId.equals(TagConfigStore.ROOT_ID)) return;

    DefaultMutableTreeNode oldParent = (DefaultMutableTreeNode) source.getParent();
    if (oldParent == null) return;  // source is the synthetic root

    int oldIndex = oldParent.getIndex(source);
    if (oldParent == newParent && oldIndex == insertIndex) return;

    // 1. Update Swing structure: detach from old, attach to new.
    Object[] removedChild = { source };
    int[] removedIndex = { oldIndex };
    oldParent.remove(oldIndex);
    fireTreeNodesRemoved(this, oldParent.getPath(), removedIndex, removedChild);

    int safeIndex = Math.max(0, Math.min(insertIndex, newParent.getChildCount()));
    newParent.insert(source, safeIndex);
    int[] insertedIndex = { safeIndex };
    Object[] insertedChild = { source };
    fireTreeNodesInserted(this, newParent.getPath(), insertedIndex, insertedChild);

    // 2. Special case: if the new parent is the synthetic root, JTree's
    // invisible-root row mapping is not refreshed by a precise inserted
    // event (the same Swing quirk addressed in appendChild). Fire an
    // additional nodeStructureChanged on the new parent so the row map
    // rebuilds. The precise event above still fires first.
    if (newParent.getParent() == null) {
      nodeStructureChanged(newParent);
    }
  }

  // -----------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------

  /**
   * Build a fresh {@link TagNode} tree from the Swing tree. Walking the
   * JTree's structure means we don't need to maintain a separate
   * userObject chain — every parent that has children gets a freshly
   * constructed Folder wrapping the current Swing children list.
   */
  private static TagNode tagFor(DefaultMutableTreeNode node) {
    TagNode tag = (TagNode) node.getUserObject();
    if (tag instanceof TagNode.Shell) return tag;
    TagNode.Folder f = (TagNode.Folder) tag;
    List<TagNode> kids = new ArrayList<>(node.getChildCount());
    for (int i = 0; i < node.getChildCount(); i++) {
      kids.add(tagFor((DefaultMutableTreeNode) node.getChildAt(i)));
    }
    return new TagNode.Folder(f.id(), f.name(), kids, f.expanded());
  }

  private static javax.swing.tree.TreePath findPathFor(DefaultMutableTreeNode node, UUID id) {
    TagNode tag = (TagNode) node.getUserObject();
    if (tag.id().equals(id)) return new javax.swing.tree.TreePath(node.getPath());
    Enumeration<?> e = node.children();
    while (e.hasMoreElements()) {
      DefaultMutableTreeNode child = (DefaultMutableTreeNode) e.nextElement();
      javax.swing.tree.TreePath hit = findPathFor(child, id);
      if (hit != null) return hit;
    }
    return null;
  }

  private static DefaultMutableTreeNode findMutable(DefaultMutableTreeNode node, UUID id) {
    TagNode tag = (TagNode) node.getUserObject();
    if (tag.id().equals(id)) return node;
    Enumeration<?> e = node.children();
    while (e.hasMoreElements()) {
      DefaultMutableTreeNode hit = findMutable(
          (DefaultMutableTreeNode) e.nextElement(), id);
      if (hit != null) return hit;
    }
    return null;
  }

  private static int indexOfChildWithId(DefaultMutableTreeNode parent, UUID childId) {
    for (int i = 0; i < parent.getChildCount(); i++) {
      DefaultMutableTreeNode c = (DefaultMutableTreeNode) parent.getChildAt(i);
      TagNode t = (TagNode) c.getUserObject();
      if (t.id().equals(childId)) return i;
    }
    return -1;
  }

  /** Mirror {@code tag}'s structural children onto {@code swingNode}. */
  private static void rebuildChildren(DefaultMutableTreeNode swingNode, TagNode tag) {
    swingNode.removeAllChildren();
    if (tag instanceof TagNode.Folder f) {
      for (TagNode c : f.children()) {
        DefaultMutableTreeNode child = new DefaultMutableTreeNode(c);
        rebuildChildren(child, c);
        swingNode.add(child);
      }
    }
    // Shells have no children; nothing to add.
  }

  private static DefaultMutableTreeNode toMutable(TagNode tag) {
    DefaultMutableTreeNode node = new DefaultMutableTreeNode(tag);
    rebuildChildren(node, tag);
    return node;
  }

  /**
   * Quick smoke check that the event fired carries the indices and children
   * we passed in. Package-private so tests can hook a listener via
   * {@link #addTreeModelListener}.
   */
  @SuppressWarnings("unused")
  static TreeModelEvent mkEvent(Object source, TreeNode[] path,
                                 int[] childIndices, Object[] children) {
    return new TreeModelEvent(source, path, childIndices, children);
  }
}
