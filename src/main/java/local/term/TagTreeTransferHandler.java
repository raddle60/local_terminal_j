package local.term;

import javax.swing.JComponent;
import javax.swing.JTree;
import javax.swing.TransferHandler;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * {@link TransferHandler} that implements drag-and-drop reordering and
 * reparenting for the tag tree. Exports the dragged node's UUID as a
 * custom {@link DataFlavor}; imports resolve the {@link JTree.DropLocation}
 * into a {@code moveNode} call on the {@link TagTreeModel}.
 *
 * <p>Drop positions (per the spec):
 * <ul>
 *   <li>{@code childIndex == -1} onto a folder → move INTO the folder
 *       (appended at the end).</li>
 *   <li>{@code childIndex == -1} onto a shell → reject (shells are leaves).</li>
 *   <li>{@code childIndex 0..n} → insert as a sibling at that index.</li>
 * </ul>
 *
 * <p>Rejected drops (self, descendant cycle, root, shell-as-into) are
 * silent no-ops; {@code canImport} returns false for these so the JTree
 * flips the cursor to "not allowed" during the drag.
 */
class TagTreeTransferHandler extends TransferHandler {

  /** Custom flavor: a UUID as a serialized string. */
  private static final DataFlavor FLAVOR =
      new DataFlavor("application/x-local-term-java-tag-uuid;class=java.lang.String",
                     "LocalTermJava tag UUID");

  /**
   * Transferable that exposes the dragged tag's UUID on our custom
   * {@link #FLAVOR} AND on {@link DataFlavor#stringFlavor}. We must
   * NOT use {@link java.awt.datatransfer.StringSelection} — that class
   * only supports {@code stringFlavor} (and, on some JDKs, plain
   * textFlavor), so {@code TransferSupport.isDataFlavorSupported(FLAVOR)}
   * would always return false, {@code canImport} would short-circuit
   * to false, and the cursor would flip to "not allowed" everywhere.
   */
  private static final class UuidTransferable implements Transferable {
    private final String id;
    UuidTransferable(UUID id) { this.id = id.toString(); }

    @Override public DataFlavor[] getTransferDataFlavors() {
      return new DataFlavor[]{ FLAVOR, DataFlavor.stringFlavor };
    }
    @Override public boolean isDataFlavorSupported(DataFlavor flavor) {
      return FLAVOR.equals(flavor) || DataFlavor.stringFlavor.equals(flavor);
    }
    @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
      if (!isDataFlavorSupported(flavor)) {
        throw new UnsupportedFlavorException(flavor);
      }
      return id;
    }
  }

  private final TagTreePanel panel;
  private final TagTreeModel model;

  TagTreeTransferHandler(TagTreePanel panel, TagTreeModel model) {
    this.panel = panel;
    this.model = model;
  }

  // ---- export ----

  @Override
  public Transferable createTransferable(JComponent c) {
    JTree tree = (JTree) c;
    TreePath path = tree.getSelectionPath();
    if (path == null) return null;
    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
    TagNode tag = (TagNode) node.getUserObject();
    if (tag.id().equals(TagConfigStore.ROOT_ID)) return null;
    return new UuidTransferable(tag.id());
  }

  @Override
  public int getSourceActions(JComponent c) { return MOVE; }

  // ---- import ----

  @Override
  public boolean canImport(TransferSupport support) {
    if (!support.isDataFlavorSupported(FLAVOR)) return false;
    // Read the drop location from the TransferSupport, NOT from
    // JTree.getDropLocation(). Swing's DropTargetListener.handleDrag
    // calls canImport BEFORE it pushes the freshly-computed drop
    // location into JTree's `dropLocation` field — that update happens
    // a few lines later, inside setComponentDropLocation(...). So
    // JTree.getDropLocation() is always one event behind (and null on
    // the very first dragOver), which made the cursor flip to
    // "not allowed" everywhere. JTree overrides dropLocationForPoint,
    // and TransferHandler.SwingDropTargetAdapter.setDNDVariables calls
    // it on every drag event to populate support.getDropLocation().
    JTree.DropLocation dl = (JTree.DropLocation) support.getDropLocation();
    if (dl == null || dl.getPath() == null) return false;
    DefaultMutableTreeNode target = (DefaultMutableTreeNode) dl.getPath().getLastPathComponent();
    TagNode targetTag = (TagNode) target.getUserObject();
    if (targetTag.id().equals(TagConfigStore.ROOT_ID)) return false;  // root is not a drop target

    UUID sourceId = parseSourceId(support);
    if (sourceId == null) return false;
    if (sourceId.equals(targetTag.id())) return false;  // self-drop

    // Reject drops onto shells; shells are leaves.
    if (dl.getChildIndex() == -1 && !(targetTag instanceof TagNode.Folder)) return false;

    // Cycle prevention: a folder cannot be dropped into one of its own
    // descendants. Only relevant when targetTag is a folder and the
    // dropped node is a folder — for sibling-reorder, the childIndex
    // is 0..n and the target is the parent folder, not the descendant.
    if (dl.getChildIndex() == -1 && targetTag instanceof TagNode.Folder) {
      if (isDescendant(sourceId, targetTag.id())) return false;
    }

    return true;
  }

  @Override
  public boolean importData(TransferSupport support) {
    if (!canImport(support)) return false;
    JTree tree = (JTree) support.getComponent();
    // Same trap as in canImport: drop location lives on the support, not
    // on the JTree field, at the moment Swing calls us.
    JTree.DropLocation dl = (JTree.DropLocation) support.getDropLocation();
    if (dl == null || dl.getPath() == null) return false;
    UUID sourceId = parseSourceId(support);
    if (sourceId == null) return false;

    DefaultMutableTreeNode target = (DefaultMutableTreeNode) dl.getPath().getLastPathComponent();
    TagNode targetTag = (TagNode) target.getUserObject();
    int childIndex = dl.getChildIndex();

    UUID newParentId;
    int insertIndex;
    if (childIndex == -1) {
      // "Onto" the target. If target is a folder, move INTO it at the end.
      // If target is a shell, canImport already returned false, so this
      // is always a folder here.
      if (!(targetTag instanceof TagNode.Folder)) return false;
      newParentId = targetTag.id();
      insertIndex = -1;  // sentinel: append at end
    } else {
      // Sibling reorder: childIndex is the position within target's
      // children. target is the parent folder.
      if (!(targetTag instanceof TagNode.Folder)) return false;
      newParentId = targetTag.id();
      insertIndex = childIndex;
    }

    // Auto-expand: if dropping into a collapsed folder, expand it so the
    // user sees the dropped node. The TreeExpansionListener in
    // TagTreePanel will persist the new expansion state.
    if (childIndex == -1 && targetTag instanceof TagNode.Folder targetFolder
        && !targetFolder.expanded()) {
      target.setUserObject(targetFolder.withExpanded(true));
      tree.expandPath(new TreePath(target.getPath()));
    }

    return doMove(tree, sourceId, newParentId, insertIndex);
  }

  /** Performs the move via TagTreePanel's existing save+UI helper. */
  private boolean doMove(JTree tree, UUID sourceId, UUID newParentId, int insertIndex) {
    TagNode root = model.rootTag();
    TagNode newRoot = moveInTree(root, sourceId, newParentId, insertIndex);
    if (newRoot == root) return false;
    panel.applyWithPreciseUiUpdate(newRoot, () -> {
      if (insertIndex == -1) {
        // Append-at-end sentinel: convert to a real index from the Swing
        // tree (the model mutator needs a real index).
        DefaultMutableTreeNode newParent = model.findMutable(newParentId);
        int realIndex = newParent == null ? 0 : newParent.getChildCount();
        model.moveNode(sourceId, newParentId, realIndex);
      } else {
        model.moveNode(sourceId, newParentId, insertIndex);
      }
    });
    // Re-set selection to the moved node's new path after the events fire.
    TreePath newPath = model.pathFor(sourceId);
    if (newPath != null) tree.setSelectionPath(newPath);
    return true;
  }

  private static UUID parseSourceId(TransferSupport support) {
    try {
      Transferable t = support.getTransferable();
      String s = (String) t.getTransferData(FLAVOR);
      return UUID.fromString(s);
    } catch (UnsupportedFlavorException | IOException | IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * True if {@code potentialDescendantId} is in the subtree rooted at
   * {@code ancestorId} (or is {@code ancestorId} itself).
   */
  private boolean isDescendant(UUID ancestorId, UUID potentialDescendantId) {
    TagNode root = model.rootTag();
    TagNode node = root.findById(ancestorId);
    if (node == null) return false;
    return node.findById(potentialDescendantId) != null;
  }

  /**
   * Two-pass move: first remove {@code sourceId} from its current parent,
   * then insert it under {@code newParentId} at {@code insertIndex}
   * (or at the end when {@code insertIndex == -1}). Returns {@code root}
   * unchanged when the source isn't found.
   */
  private static TagNode moveInTree(TagNode root, UUID sourceId,
                                     UUID newParentId, int insertIndex) {
    TagNode source = root.findById(sourceId);
    if (source == null) return root;
    TagNode withoutSource = removeChildById(root, sourceId);
    return addChildById(withoutSource, newParentId, source, insertIndex);
  }

  private static TagNode removeChildById(TagNode node, UUID childId) {
    if (!node.isFolder()) return node;
    TagNode.Folder f = (TagNode.Folder) node;
    List<TagNode> next = new ArrayList<>(f.children().size());
    boolean changed = false;
    for (TagNode c : f.children()) {
      if (c.id().equals(childId)) { changed = true; continue; }
      TagNode r = removeChildById(c, childId);
      next.add(r);
      if (r != c) changed = true;
    }
    return changed ? f.withChildren(next) : f;
  }

  private static TagNode addChildById(TagNode node, UUID parentId,
                                      TagNode child, int insertIndex) {
    if (!node.isFolder()) return node;
    TagNode.Folder f = (TagNode.Folder) node;
    if (!f.id().equals(parentId)) {
      // Recurse into children to find the parent.
      List<TagNode> next = new ArrayList<>(f.children().size());
      boolean changed = false;
      for (TagNode c : f.children()) {
        TagNode r = addChildById(c, parentId, child, insertIndex);
        next.add(r);
        if (r != c) changed = true;
      }
      return changed ? f.withChildren(next) : f;
    }
    // This folder IS the parent. Insert child at insertIndex (or at end).
    List<TagNode> next = new ArrayList<>(f.children());
    int realIndex = insertIndex == -1 ? next.size() : Math.min(insertIndex, next.size());
    next.add(realIndex, child);
    return f.withChildren(next);
  }
}
