package local.term;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeExpansionListener;
import javax.swing.event.TreeModelEvent;
import javax.swing.event.TreeModelListener;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Left panel: tag tree + toolbar with "Add top-level" button + right-click menu.
 *
 * <p>Node model: each entry is one of {@link TagNode.Folder} (organisational,
 * just a name) or {@link TagNode.Shell} (launchable, shell/paths). Top-level
 * entries MUST be folders — the empty-area context menu only offers
 * "Add top-level folder…". Sub-trees may mix folders and shells freely.
 *
 * <p>Operations:
 * <ul>
 *   <li>Double-click on a {@link TagNode.Shell} opens a terminal session.</li>
 *   <li>Right-click on a folder: Add folder / Add shell / Edit / Delete.</li>
 *   <li>Right-click on a shell: Edit / Delete (shells are leaves).</li>
 *   <li>Right-click on empty area: Add top-level folder.</li>
 *   <li>Every mutation is persisted via {@link TagConfigStore#save(TagNode)}.</li>
 * </ul>
 *
 * <p>JTree updates are <em>precise</em>: each mutation calls a single
 * {@link TagTreeModel} mutator that fires {@code treeNodesInserted},
 * {@code treeNodesChanged} or {@code treeNodesRemoved} scoped to the
 * affected path. We never call {@link TagTreeModel#replaceRoot} from a live
 * mutation — that fires {@code nodeStructureChanged} and collapses the
 * tree (selection, expansion, scroll) back to its initial state, which
 * is what the user reported as "the UI reverts to its initial look"
 * after editing.
 */
public class TagTreePanel extends JPanel {
  private static final Logger LOG = LoggerFactory.getLogger(TagTreePanel.class);

  private final TagConfigStore store;
  private TagTreeModel model;
  private final JTree tree;
  private final ShellIconResolver iconResolver;

  private Consumer<TagNode.Shell> onLeafActivated;
  /**
   * Invoked when the user clicks the "≪ Hide tree" button on the toolbar.
   * {@link MainFrame} wires this to a container-level {@code setTreePanelVisible(false)}
   * so the whole tree panel slides out of view; re-showing is then driven
   * from a separate "≫ Show tree" affordance placed on the terminal pane.
   */
  private Runnable onHideRequested;

  public TagTreePanel(TagConfigStore store, ShellIconResolver iconResolver) {
    super(new BorderLayout());
    this.store = store;
    // Load the model eagerly: the TransferHandler is constructed with a
    // reference to {@code model}, so the field MUST be non-null at that
    // point. Loading here (vs. deferring to {@link #refreshViewFromModel})
    // also lets us pass it to the JTree constructor so the tree starts
    // attached to the model — no separate {@code tree.setModel} call
    // needed.
    this.model = new TagTreeModel(store.load());
    // Resolver is constructed in MainFrame and shared with TerminalPanel
    // so the SVG/ICO/raster cache is single — opening N terminals doesn't
    // re-decode /shell-icons/*.svg and the icon shown here matches the
    // tab header pixel-for-pixel.
    this.iconResolver = iconResolver;

    // Listener-order trap: register our expansion-restore TreeModelListener
    // on the model BEFORE the JTree registers its own. JTree.setModel
    // internally calls {@code model.addTreeModelListener(this)}, putting
    // JTree as a listener; if we register ours after that, our
    // treeStructureChanged runs AFTER JTree's, which has already wiped the
    // row cache for the root path. We'd see a "collapse then re-expand"
    // flicker. By registering first, our listener fires before JTree's,
    // gets a chance to call {@code tree.expandPath(...)} (which records
    // the path as expanded in {@code treeState.setExpandedState} while
    // rows are still mapped), and then JTree's handler invalidates row
    // entries — but {@code expandedState} (a separate field) survives, so
    // the next paint rebuilds rows already fully expanded.
    registerExpansionRestoreListener();

    this.tree = new JTree(model);
    tree.setRootVisible(false);
    tree.setShowsRootHandles(true);
    // The shell icons are rasterised at FolderIconCellRenderer.defaultTargetSize()
    // (e.g. 24 px). The JTree's default row height is derived from the L&F
    // font and is typically smaller (16 px on Windows), which would clip
    // the taller icon vertically and make it appear as a rectangle (full
    // width × clipped height). Pin the row height to the target icon size
    // so shell icons stay square and the row is tall enough for both the
    // icon and the tag-name text on every L&F.
    int targetSize = FolderIconCellRenderer.defaultTargetSize();
    tree.setRowHeight(Math.max(targetSize, tree.getFont().getSize() + 4));
    // Render each row as just the tag's display name; default renderer would
    // call TagNode.toString() (record format) and show "TagNode[id=..., ...]".
    // FolderIconCellRenderer also pins the folder icon for Folder rows so it
    // does not flicker between open/closed-folder icons when the user expands
    // or collapses the row (see Issue #2).
    tree.setCellRenderer(new FolderIconCellRenderer(iconResolver));
    // DnD wiring order matters: the TransferHandler MUST be installed
    // before setDragEnabled(true). JTree.setDragEnabled installs a
    // DragGestureRecognizer that captures getTransferHandler() at the
    // moment it's called — calling setDragEnabled first would bind the
    // recognizer to the default JComponent handler (effectively null),
    // and the gesture would never invoke our custom createTransferable.
    // setDropMode(ON_OR_INSERT) makes JTree compute a precise drop
    // location with line indicators (childIndex 0..n for sibling insert,
    // -1 for "drop into this folder") instead of the default
    // USE_SELECTION semantics, which canImport/importData expect.
    tree.setTransferHandler(new TagTreeTransferHandler(this, model));
    tree.setDropMode(DropMode.ON_OR_INSERT);
    tree.setDragEnabled(true);

    add(buildToolbar(), BorderLayout.NORTH);
    add(tree, BorderLayout.CENTER);
    wireMouse();
    wireExpansionListener();

    // Refresh the view from the current model — applies persisted
    // expansion state to the JTree. Runs AFTER the tree is on screen so
    // the JTree's row map is built. {@link #reload} routes through the
    // same helper so there's exactly one place expansion restoration
    // is implemented, not two.
    refreshViewFromModel();
  }

  private JPanel buildToolbar() {
    JButton addTopLevel = new JButton("+ Add top-level folder...");
    addTopLevel.addActionListener(e -> addTopLevelFolder());
    // "Hide tree" button — paired with the menu View → Toggle tree panel.
    // The affordance lives on the tree's own toolbar so it's discoverable
    // without a separate strip; the matching "Show tree" affordance when
    // hidden is mounted on MainFrame's terminal-side strip (so the user
    // always has a one-click path to bring the tree back, even though the
    // Ctrl+B menu accelerator is the primary way).
    JButton hideButton = new JButton("≪");
    hideButton.setToolTipText("Hide tree panel (Ctrl+B)");
    // Tight margins keep the button visually compact next to the wide
    // "+ Add top-level folder..." label; default FlowLayout-style padding
    // would dominate the toolbar.
    hideButton.setMargin(new Insets(2, 6, 2, 6));
    hideButton.addActionListener(e -> {
      if (onHideRequested != null) onHideRequested.run();
    });
    JPanel bar = new JPanel(new BorderLayout());
    bar.add(addTopLevel, BorderLayout.WEST);
    bar.add(hideButton, BorderLayout.EAST);
    return bar;
  }

  public void setOnLeafActivated(Consumer<TagNode.Shell> handler) {
    this.onLeafActivated = handler;
  }

  /**
   * Wire a callback invoked when the user clicks the toolbar "Hide tree"
   * button. {@link MainFrame} attaches its container-level
   * {@code setTreePanelVisible(false)} here so the tree collapses
   * without each side having to know about the other's identity.
   */
  public void setOnHideRequested(Runnable handler) {
    this.onHideRequested = handler;
  }

  /**
   * Force-replace the tree from disk (File → Reload tree from disk).
   * Routes through {@link #refreshViewFromModel} so the persisted
   * expansion state is restored, matching what the initial load does.
   * Without this, reload would leave every folder collapsed because
   * {@link TagTreeModel#replaceRoot} fires {@code nodeStructureChanged}
   * which rebuilds the JTree's row map with default collapsed rows.
   */
  public void reload() {
    model.replaceRoot(store.load());
    refreshViewFromModel();
  }

  /**
   * Apply the persisted expansion/collapse state from the current
   * {@link TagTreeModel} to the JTree. Called from BOTH the initial
   * constructor and {@link #reload} so there's a single place that
   * knows how to read the {@code TagNode.Folder.expanded} flag and turn
   * it into {@code expandPath}/{@code collapsePath} calls.
   */
  private void refreshViewFromModel() {
    applyExpansionFromModel((DefaultMutableTreeNode) model.getRoot(),
        new TreePath(model.getRoot()));
  }

  private void wireMouse() {
    // Popup detection varies by L&F (Windows: pressed; Linux/macOS: released).
    // Handle both for reliability, dedup via "wasShown" guard per click.
    // Double-click is handled ONLY in mouseClicked (fires once after release,
    // not in both mousePressed and mouseReleased) — otherwise a double-click
    // would open two tabs.
    //
    // Hit-testing: match on Y alone instead of X+Y. JTree's row width
    // is the width of the deepest visible node's text/icon, NOT the
    // full tree width, so both getRowForLocation and getPathForLocation
    // return "no row" for clicks in the wide blank band to the right of
    // a short row's label. Matching on Y alone (see pathAt below) makes
    // the entire horizontal span of the tree clickable per row.
    tree.addMouseListener(new MouseAdapter() {
      private boolean popupWasShown;

      @Override public void mousePressed(MouseEvent e) {
        popupWasShown = false;
        handlePopup(e);
      }

      @Override public void mouseReleased(MouseEvent e) {
        if (popupWasShown) return;
        handlePopup(e);
      }

      @Override public void mouseClicked(MouseEvent e) {
        if (e.getClickCount() == 2 && e.getButton() == MouseEvent.BUTTON1) {
          TreePath path = pathAt(e.getX(), e.getY());
          if (path == null) return;
          DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
          TagNode tag = (TagNode) node.getUserObject();
          tree.setSelectionPath(path);
          if (tag.isLeaf() && onLeafActivated != null) {
            // isLeaf() in our sealed model means "is a Shell" — folders are
            // never leaves, and double-click only triggers launches.
            onLeafActivated.accept((TagNode.Shell) tag);
          }
        }
      }

      private void handlePopup(MouseEvent e) {
        if (!(e.isPopupTrigger() || e.getButton() == MouseEvent.BUTTON3)) return;
        TreePath path = pathAt(e.getX(), e.getY());
        if (path != null) {
          DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
          TagNode tag = (TagNode) node.getUserObject();
          tree.setSelectionPath(path);
          showContextMenu(tag, e.getX(), e.getY());
        } else {
          // Empty area: only "Add top-level folder" is valid; root is
          // the synthetic container and is never user-editable.
          showEmptyAreaMenu(e.getX(), e.getY());
        }
        popupWasShown = true;
      }

      /**
       * Resolve a click position to a TreePath by walking each row's
       * vertical bounds and returning the row whose Y range contains the
       * click. We deliberately ignore X: unlike {@link JList} rows,
       * {@link JTree} row widths come from the rendered text/icon of the
       * deepest visible node, so {@link JTree#getRowForLocation} returns
       * -1 for clicks in the wide blank band to the right of a short
       * row's label, even though the user clearly intends to click that
       * row. Matching on Y alone makes the entire horizontal span of the
       * tree clickable per row, matching how IDE-style tree panels
       * behave.
       *
       * <p>Returns null when no row's Y range contains the click (i.e.
       * the click is in the empty area below the last row).
       */
      private TreePath pathAt(int x, int y) {
        for (int i = 0; i < tree.getRowCount(); i++) {
          java.awt.Rectangle b = tree.getRowBounds(i);
          if (b == null) continue;
          if (y >= b.y && y < b.y + b.height) {
            return tree.getPathForRow(i);
          }
        }
        return null;
      }
    });
  }

  private void showContextMenu(TagNode tag, int x, int y) {
    JPopupMenu menu = new JPopupMenu();

    if (tag.isFolder()) {
      JMenuItem addFolder = new JMenuItem("Add folder...");
      addFolder.addActionListener(ev -> addChildFolder((TagNode.Folder) tag));
      menu.add(addFolder);

      JMenuItem addShell = new JMenuItem("Add shell...");
      addShell.addActionListener(ev -> addChildShell((TagNode.Folder) tag));
      menu.add(addShell);

      menu.addSeparator();
    }

    JMenuItem edit = new JMenuItem("Edit...");
    edit.addActionListener(ev -> editNode(tag));
    menu.add(edit);

    if (!tag.isFolder()) {
      JMenuItem duplicate = new JMenuItem("Duplicate...");
      duplicate.addActionListener(ev -> duplicateShell((TagNode.Shell) tag));
      menu.add(duplicate);
    }

    JMenuItem delete = new JMenuItem("Delete");
    delete.addActionListener(ev -> deleteNode(tag));
    menu.add(delete);

    menu.show(tree, x, y);
  }

  private void showEmptyAreaMenu(int x, int y) {
    JPopupMenu menu = new JPopupMenu();
    JMenuItem addTop = new JMenuItem("Add top-level folder...");
    addTop.addActionListener(ev -> addTopLevelFolder());
    menu.add(addTop);
    menu.show(tree, x, y);
  }

  // -----------------------------------------------------------------
  // Live mutations: precise event + save-to-disk
  // -----------------------------------------------------------------

  /** Add a fresh top-level folder under the synthetic root. */
  private void addTopLevelFolder() {
    FolderEditorDialog dlg = new FolderEditorDialog(frame(), null);
    dlg.setVisible(true);
    TagNode.Folder folder = dlg.getResult();
    if (folder == null) return;
    TagNode root = model.rootTag();
    if (!(root instanceof TagNode.Folder currentRoot)) return;
    // Build the next root purely for disk save; the live Swing tree
    // receives a precise treeNodesInserted instead of a full reload.
    TagNode.Folder newRoot = currentRoot.addOrReplaceChild(folder);
    applyWithPreciseUiUpdate(newRoot,
        () -> model.appendChild(currentRoot.id(), folder));
  }

  /** Add a folder under an existing folder. */
  private void addChildFolder(TagNode.Folder parent) {
    FolderEditorDialog dlg = new FolderEditorDialog(frame(), null);
    dlg.setVisible(true);
    TagNode.Folder folder = dlg.getResult();
    if (folder == null) return;
    applyReplace(parent.id(),
        p -> p.addOrReplaceChild(folder),
        () -> model.appendChild(parent.id(), folder));
  }

  /** Add a shell under an existing folder. */
  private void addChildShell(TagNode.Folder parent) {
    ShellEditorDialog dlg = new ShellEditorDialog(frame(), null);
    dlg.setVisible(true);
    TagNode.Shell shell = dlg.getResult();
    if (shell == null) return;
    applyReplace(parent.id(),
        p -> p.addOrReplaceChild(shell),
        () -> model.appendChild(parent.id(), shell));
  }

  /**
   * Open the shell editor pre-filled with {@code source}'s fields (a fresh
   * UUID is generated so save adds a new sibling under the same folder
   * rather than replacing the source). The user may tweak any field before
   * confirming; cancel discards the duplicate.
   *
   * <p>Reusing {@link ShellEditorDialog} keeps the duplicate path identical
   * to "Add shell…" — same validation, same field layout — except the
   * dialog now starts populated.
   */
  private void duplicateShell(TagNode.Shell source) {
    TagNode root = model.rootTag();
    UUID parentId = parentIdOf(root, source.id());
    if (parentId == null) return;
    // 7-arg form preserves shellArgs and autoScript — using shorter overloads
    // would silently drop them on duplicate, which surprised users.
    TagNode.Shell seed = new TagNode.Shell(
        UUID.randomUUID(), source.name(), source.shellPath(), source.shellArgs(),
        source.iconPath(), source.startPath(), source.autoScript());
    // Duplicate mode: title is "Duplicate shell" (not "Edit shell"), and the
    // dialog allocates a fresh id on OK so it appends as a sibling rather
    // than replacing the source.
    ShellEditorDialog dlg = new ShellEditorDialog(
        frame(), ShellEditorDialog.Mode.DUPLICATE, seed);
    dlg.setVisible(true);
    TagNode.Shell result = dlg.getResult();
    if (result == null) return;
    applyReplace(parentId,
        p -> p.addOrReplaceChild(result),
        () -> model.appendChild(parentId, result));
  }

  /**
   * Edit any node: open the right editor (folder or shell), then look up
   * the node in the tree by id and replace it. The editor preserves the
   * original id, so the splice is unambiguous regardless of where the
   * node lives in the hierarchy. We never manipulate the parent slot.
   */
  private void editNode(TagNode tag) {
    TagNode edited;
    if (tag instanceof TagNode.Folder folder) {
      FolderEditorDialog dlg = new FolderEditorDialog(frame(), folder);
      dlg.setVisible(true);
      edited = dlg.getResult();
    } else if (tag instanceof TagNode.Shell shell) {
      ShellEditorDialog dlg = new ShellEditorDialog(frame(), shell);
      dlg.setVisible(true);
      edited = dlg.getResult();
    } else {
      return; // no other kinds; static check, defensive guard.
    }
    if (edited == null) return;
    TagNode root = model.rootTag();
    TagNode newRoot = replaceInTree(root, tag.id(), p -> edited);
    if (newRoot == root) return;
    applyWithPreciseUiUpdate(newRoot,
        () -> model.nodeChanged(tag.id(), edited));
  }

  private void deleteNode(TagNode tag) {
    int confirm = JOptionPane.showConfirmDialog(SwingUtilities.getWindowAncestor(this),
        "Delete '" + tag.name() + "'?", "Confirm delete",
        JOptionPane.YES_NO_OPTION);
    if (confirm != JOptionPane.YES_OPTION) return;
    // Walk down to find the actual parent of this id and drop the child.
    // (Synthetic root itself is undeletable.)
    UUID target = tag.id();
    TagNode root = model.rootTag();
    UUID parentId = parentIdOf(root, target);
    if (parentId == null) return;
    applyReplace(parentId,
        p -> p.removeChild(target),
        () -> model.removeChild(parentId, target));
  }

  // -----------------------------------------------------------------
  // Generic tree mutation — id-based, kind-aware at the leaves only.
  // -----------------------------------------------------------------

  /**
   * Apply a folder mutation {@code op} to the folder with id
   * {@code parentId}, persist the resulting root, then run {@code uiUpdate}
   * to apply the precise JTree event. The {@code uiUpdate} runs only if
   * the save succeeds — otherwise the on-disk state and the in-memory
   * state would diverge.
   */
  private void applyReplace(UUID parentId,
                            Function<TagNode.Folder, TagNode.Folder> op,
                            Runnable uiUpdate) {
    TagNode root = model.rootTag();
    // Bridge: replaceInTree takes a Function<TagNode,TagNode>; we have a
    // function on Folder only. Since the target id is supposed to be a
    // folder and we no-op otherwise, the cast is safe at the call site.
    TagNode newRoot = replaceInTree(root, parentId,
        p -> op.apply((TagNode.Folder) p));
    if (newRoot == root) return;
    applyWithPreciseUiUpdate(newRoot, uiUpdate);
  }

  void applyWithPreciseUiUpdate(TagNode newRoot, Runnable uiUpdate) {
    try {
      uiUpdate.run();
      // Save AFTER the Swing tree mutation. Saving before would compute
      // the post-move state from a tree that hasn't been mutated yet, and
      // a subsequent listener (e.g. the TreeExpansionListener firing on
      // the just-added child landing in a folder that auto-expanded) can
      // overwrite our save with a state derived from a stale userObject.
      // Walking the live Swing tree after uiUpdate is the only source of
      // truth — `newRoot` was an optimisation for the no-listener case.
      store.save((TagNode.Folder) model.rootTag());
    } catch (IOException e) {
      JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(this),
          "Could not save config: " + e.getMessage(),
          "Save error", JOptionPane.ERROR_MESSAGE);
      LOG.error("Save failed", e);
    }
  }

  /**
   * Find the node with id {@code targetId} in {@code node} and replace it
   * with {@code op.apply(found)}. Walks {@code Folder.children} recursively.
   * Shells have no children so the walk stops at leaf replacements.
   * Returns {@code node} unchanged when no matching id exists.
   */
  private static TagNode replaceInTree(TagNode node, UUID targetId,
                                       Function<TagNode, TagNode> op) {
    if (node.id().equals(targetId)) return op.apply(node);
    if (!node.isFolder()) return node;
    TagNode.Folder f = (TagNode.Folder) node;
    List<TagNode> next = new ArrayList<>(f.children().size());
    boolean changed = false;
    for (TagNode c : f.children()) {
      TagNode r = replaceInTree(c, targetId, op);
      next.add(r);
      if (r != c) changed = true;
    }
    if (!changed) return f;
    return f.withChildren(next);
  }

  /**
   * Returns the id of the node whose immediate children contain
   * {@code childId}, or {@code null} when {@code childId} is the root
   * itself or no such parent exists.
   */
  private static UUID parentIdOf(TagNode node, UUID childId) {
    if (!node.isFolder()) return null;
    TagNode.Folder f = (TagNode.Folder) node;
    for (TagNode c : f.children()) {
      if (c.id().equals(childId)) return node.id();
      UUID hit = parentIdOf(c, childId);
      if (hit != null) return hit;
    }
    return null;
  }

  private JFrame frame() {
    return (JFrame) javax.swing.SwingUtilities.getWindowAncestor(this);
  }

  // -----------------------------------------------------------------
  // Expansion state — listener + restoration
  // -----------------------------------------------------------------

  /**
   * Persist the user's expansion/collapse action. The JTree's own event
   * is the source of truth for the UI state; we just need to update
   * the userObject in-place and save. We do NOT fire a treeNodesChanged
   * event because the JTree already repainted itself.
   */
  private void wireExpansionListener() {
    // User-driven expand/collapse: persist the flag back to the model and
    // to disk. The structural-event listener that restores expansion
    // after a model rebuild lives in {@link #registerExpansionRestoreListener}
    // and is registered separately, BEFORE the JTree (see comment there).
    tree.addTreeExpansionListener(new TreeExpansionListener() {
      @Override public void treeExpanded(TreeExpansionEvent e) {
        persistExpansion(e.getPath(), true);
      }
      @Override public void treeCollapsed(TreeExpansionEvent e) {
        persistExpansion(e.getPath(), false);
      }
    });
  }

  /**
   * Register the {@link TreeModelListener} that re-applies persisted
   * expansion when the model fires a structural change on the synthetic
   * root. Must be called BEFORE {@code new JTree(model)} so that the
   * listener's order in {@code DefaultTreeModel}'s listener list is
   * ahead of JTree's own — see the block comment in the constructor.
   */
  private void registerExpansionRestoreListener() {
    model.addTreeModelListener(new TreeModelListener() {
      @Override public void treeNodesChanged(TreeModelEvent e) { }
      @Override public void treeNodesInserted(TreeModelEvent e) { }
      @Override public void treeNodesRemoved(TreeModelEvent e) { }
      @Override public void treeStructureChanged(TreeModelEvent e) {
        Object last = e.getTreePath().getLastPathComponent();
        if (last != model.getRoot()) return;
        // Synchronous: by registering ahead of JTree, our listener fires
        // FIRST. {@code tree.expandPath(...)} writes to
        // {@code TreeState.setExpandedState} — a separate field from the
        // row entries JTree is about to invalidate. Subsequent paint
        // rebuilds rows reading the still-correct expandedState, so no
        // "collapse then re-expand" flicker is visible.
        refreshViewFromModel();
      }
    });
  }

  private void persistExpansion(TreePath path, boolean expanded) {
    DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
    if (!(node.getUserObject() instanceof TagNode.Folder f)) return;
    TagNode updated = f.withExpanded(expanded);
    node.setUserObject(updated);
    // Persist the LIVE Swing tree state. userObject.children is captured
    // at setUserObject time and can be stale after a later mutation like
    // model.moveNode adds a child (we hit this in the DnD-reparent
    // regression where importData setUserObject(targetFolder.withExpanded(true))
    // with the pre-move empty children list, then model.moveNode added the
    // shell, then tree.setSelectionPath fired this listener with stale
    // userObject.children=[] still in scope via the closure above).
    // model.rootTag() walks the Swing tree so its output is authoritative.
    try {
      store.save((TagNode.Folder) model.rootTag());
    } catch (IOException ex) {
      JOptionPane.showMessageDialog(SwingUtilities.getWindowAncestor(this),
          "Could not save config: " + ex.getMessage(),
          "Save error", JOptionPane.ERROR_MESSAGE);
      LOG.error("Save failed", ex);
    }
  }

  /**
   * Walk the model after load and apply the persisted expansion flags
   * to the JTree. Must run after the JTree is on screen.
   */
  private void applyExpansionFromModel(DefaultMutableTreeNode node, TreePath path) {
    if (node.getUserObject() instanceof TagNode.Folder f) {
      if (f.expanded() && node.getChildCount() > 0) {
        tree.expandPath(path);
      } else if (!f.expanded()) {
        tree.collapsePath(path);
      }
    }
    for (int i = 0; i < node.getChildCount(); i++) {
      DefaultMutableTreeNode child = (DefaultMutableTreeNode) node.getChildAt(i);
      applyExpansionFromModel(child, path.pathByAddingChild(child));
    }
  }
}
