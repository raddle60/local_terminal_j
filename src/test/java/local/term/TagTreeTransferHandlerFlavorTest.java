package local.term;

import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the FLAVOR contract used by
 * {@link TagTreeTransferHandler}. A previous version of the handler used
 * {@code java.awt.datatransfer.StringSelection} as its export
 * {@link Transferable}, which only exposes
 * {@link DataFlavor#stringFlavor} (and on some JDKs,
 * {@code plainTextFlavor}). Our custom FLAVOR was therefore not
 * supported by the returned Transferable, {@code canImport} always
 * returned false on {@code support.isDataFlavorSupported(FLAVOR)}, and
 * the JTree flipped the cursor to "not allowed" everywhere during the
 * drag — DnD appeared totally broken.
 *
 * <p>These tests pin the contract: the Transferable returned by
 * {@code createTransferable} MUST advertise our FLAVOR, MUST answer
 * {@code true} to {@code isDataFlavorSupported(FLAVOR)}, and MUST
 * return the source node's UUID via {@code getTransferData(FLAVOR)}.
 *
 * <p>These tests deliberately do not exercise an actual drag gesture
 * — DnD requires a live display and a system drag threshold, neither
 * of which is reliable in headless CI. They check the contract that
 * {@code canImport} relies on.
 */
class TagTreeTransferHandlerFlavorTest {

  private static TagTreePanel newPanelWithSelectedShell(UUID shellId) throws Exception {
    final Path tempDir = Files.createTempDirectory("tagtree-flavor-test-");
    final Path configFile = tempDir.resolve("config.json");
    // Hand-build a config: root > folder > shell, then persist it.
    TagNode.Folder folder = new TagNode.Folder(new UUID(0L, 1L), "folder",
        List.of(new TagNode.Shell(shellId, "bash",
            "C:\\Program Files\\Git\\bin\\bash.exe", null, "C:\\")));
    TagNode.Folder root = new TagNode.Folder(TagConfigStore.ROOT_ID, "root",
        List.of(folder));
    final TagConfigStore store = new TagConfigStore(configFile);
    store.save(root);

    final TagTreePanel[] holder = new TagTreePanel[1];
    final Throwable[] err = new Throwable[1];
    SwingUtilities.invokeAndWait(() -> {
      try {
        holder[0] = new TagTreePanel(store, new ShellIconResolver(
            new IconLoader(), FolderIconCellRenderer.defaultTargetSize()));
      } catch (Throwable t) {
        err[0] = t;
      }
    });
    if (err[0] != null) throw new InvocationTargetException(err[0]);
    TagTreePanel panel = holder[0];

    // Set the selection to the shell so createTransferable has a source.
    JTree tree = treeOf(panel);
    TagTreeModel model = (TagTreeModel) tree.getModel();
    DefaultMutableTreeNode shellSwingNode = model.findMutable(shellId);
    TreePath shellPath = new TreePath(shellSwingNode.getPath());
    SwingUtilities.invokeAndWait(() -> tree.setSelectionPath(shellPath));
    return panel;
  }

  private static JTree treeOf(TagTreePanel panel) throws Exception {
    Field f = TagTreePanel.class.getDeclaredField("tree");
    f.setAccessible(true);
    return (JTree) f.get(panel);
  }

  private static DataFlavor flavorViaField() throws Exception {
    Field f = TagTreeTransferHandler.class.getDeclaredField("FLAVOR");
    f.setAccessible(true);
    return (DataFlavor) f.get(null);
  }

  /**
   * Wrapper to call {@code createTransferable} via a public method.
   * {@link TransferHandler#createTransferable} is declared
   * {@code protected} on the superclass — but {@code TagTreeTransferHandler}
   * widens it to {@code public} so unit tests (in the same package) can
   * drive it without reflection. The DnD gesture recognizer is the
   * production call site; this helper exists only for the tests.
   */
  private static Transferable createTransferable(TransferHandler th, JTree tree) {
    return ((TagTreeTransferHandler) th).createTransferable(tree);
  }

  private static void dispose(TagTreePanel panel) {
    JFrame dummy = new JFrame();
    dummy.getContentPane().add(panel, BorderLayout.CENTER);
    dummy.pack();
    dummy.dispose();
  }

  @Test
  void createTransferable_advertisesCustomFlavor() throws Exception {
    UUID shellId = new UUID(0L, 99L);
    TagTreePanel panel = newPanelWithSelectedShell(shellId);
    try {
      JTree tree = treeOf(panel);
      Transferable t = createTransferable(tree.getTransferHandler(), tree);
      assertNotNull(t, "createTransferable must return a non-null Transferable for a selected leaf");
      DataFlavor[] flavors = t.getTransferDataFlavors();
      DataFlavor custom = flavorViaField();
      boolean found = false;
      for (DataFlavor f : flavors) {
        if (custom.equals(f)) { found = true; break; }
      }
      assertTrue(found,
          "Transferable MUST advertise the custom FLAVOR (was: "
          + java.util.Arrays.toString(flavors) + ")");
    } finally {
      dispose(panel);
    }
  }

  @Test
  void createTransferable_isDataFlavorSupportedCustom_returnsTrue() throws Exception {
    UUID shellId = new UUID(0L, 100L);
    TagTreePanel panel = newPanelWithSelectedShell(shellId);
    try {
      JTree tree = treeOf(panel);
      Transferable t = createTransferable(tree.getTransferHandler(), tree);
      assertNotNull(t);
      DataFlavor custom = flavorViaField();
      assertTrue(t.isDataFlavorSupported(custom),
          "Transferable returned by createTransferable must support the custom FLAVOR; "
          + "otherwise canImport short-circuits to false and the cursor flips to 'not allowed'");
    } finally {
      dispose(panel);
    }
  }

  @Test
  void createTransferable_dataOnCustomFlavor_isSourceUuidString() throws Exception {
    UUID shellId = new UUID(0L, 101L);
    TagTreePanel panel = newPanelWithSelectedShell(shellId);
    try {
      JTree tree = treeOf(panel);
      Transferable t = createTransferable(tree.getTransferHandler(), tree);
      assertNotNull(t);
      DataFlavor custom = flavorViaField();
      Object data = t.getTransferData(custom);
      assertEquals(shellId.toString(), (String) data,
          "Data on custom FLAVOR must be the source's UUID serialized as a String");
    } finally {
      dispose(panel);
    }
  }
}
