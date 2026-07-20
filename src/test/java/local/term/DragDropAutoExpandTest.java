package local.term;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.TreePath;
import java.awt.BorderLayout;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Regression test for the "drag shell into folder, restart, shell gone" bug.
 * Uses the real {@link TagTreePanel} + {@link TagTreeTransferHandler} and
 * mirrors the exact importData sequence:
 *
 * <ol>
 *   <li>{@code target.setUserObject(targetFolder.withExpanded(true))}
 *       then {@code tree.expandPath(...)} — fires the TreeExpansionListener
 *       (which runs {@code persistExpansion}, which writes to disk).</li>
 *   <li>{@code doMove(...)} — runs {@code applyWithPreciseUiUpdate}, which
 *       uses {@code model.moveNode(sourceId, newParentId, ...)} to mutate
 *       the Swing tree, then saves to disk.</li>
 * </ol>
 *
 * <p>Bug observed before fix: after step 2, the file shows the shell removed
 * from its source folder but NOT present in the target folder, so reloading
 * loses the shell entirely. Root cause: persistExpansion's save used the
 * stale userObject.children (captured at setUserObject time, before
 * model.moveNode mutated the Swing tree). The fix walks the live Swing tree
 * via {@code model.rootTag()} in both save paths so the on-disk state always
 * matches the in-memory tree.
 */
class DragDropAutoExpandTest {

  private Path tempDir;
  private Path configFile;
  private JFrame frame;

  // Exact shape of the user's config: root → [test, test2] where
  // test has [b84f2e2d, 84c54ada] and test2 has [test2_3 (collapsed)].
  private static final UUID TEST_ID  = new UUID(0x4b6582f4a5574882L, 0xa63e2514b46c76eeL);
  private static final UUID TEST2_ID = new UUID(0x5b919a8407124623L, 0x8f1126053ac7adddL);
  private static final UUID T23_ID   = new UUID(0x0f7855dc39e04bedL, 0xb9f1549fc73afcb3L);
  private static final UUID SHELL_POWERSHELL = new UUID(0xb84f2e2d1a04412fL, 0x957d9df7c4a622e5L);
  private static final UUID SHELL_DRAGGED    = new UUID(0x84c54adaba8f4ddeL, 0x89c22f31d986b786L);

  @BeforeEach
  void setUp() throws Exception {
    tempDir = Files.createTempDirectory("dragdrop-autoexpand-test-");
    configFile = tempDir.resolve("config.json");
  }

  @AfterEach
  void tearDown() throws Exception {
    SwingUtilities.invokeAndWait(() -> {
      if (frame != null) { frame.dispose(); frame = null; }
    });
    if (Files.exists(tempDir)) {
      Files.walk(tempDir).sorted((a, b) -> b.getNameCount() - a.getNameCount())
          .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
    }
  }

  private TagTreePanel buildPanel() throws Exception {
    TagNode.Shell pow = new TagNode.Shell(SHELL_POWERSHELL, "powershell",
        "powershell.exe", null, "d:\\tmp");
    TagNode.Shell dragged = new TagNode.Shell(SHELL_DRAGGED, "dragged",
        "cmd.exe", null, "C:\\");
    TagNode.Folder test = new TagNode.Folder(TEST_ID, "test",
        List.of(pow, dragged), true);
    TagNode.Folder t23 = new TagNode.Folder(T23_ID, "test2_3", List.of(), false);
    TagNode.Folder test2 = new TagNode.Folder(TEST2_ID, "test2", List.of(t23), true);
    TagNode root = new TagNode.Folder(TagConfigStore.ROOT_ID, "root",
        List.of(test, test2));

    TagConfigStore store = new TagConfigStore(configFile);
    store.save(root);

    final TagTreePanel[] holder = new TagTreePanel[1];
    final Throwable[] err = new Throwable[1];
    SwingUtilities.invokeAndWait(() -> {
      try {
        holder[0] = new TagTreePanel(store, new ShellIconResolver(
            new IconLoader(), FolderIconCellRenderer.defaultTargetSize()));
        JFrame jf = new JFrame();
        jf.getContentPane().add(holder[0], BorderLayout.CENTER);
        jf.pack();
        jf.setVisible(true);
        frame = jf;
      } catch (Throwable t) {
        err[0] = t;
      }
    });
    if (err[0] != null) throw new InvocationTargetException(err[0]);
    return holder[0];
  }

  @Test
  void dragShellIntoCollapsedFolder_persistsAcrossReload() throws Exception {
    TagTreePanel panel = buildPanel();
    java.lang.reflect.Field treeField = TagTreePanel.class.getDeclaredField("tree");
    treeField.setAccessible(true);
    JTree tree = (JTree) treeField.get(panel);

    SwingUtilities.invokeAndWait(() -> {
      try {
        TagTreeModel model = (TagTreeModel) tree.getModel();
        DefaultMutableTreeNode shellNode = model.findMutable(SHELL_DRAGGED);
        assertNotNull(shellNode, "shell must be in the model before the drag");
        tree.setSelectionPath(new TreePath(shellNode.getPath()));

        DefaultMutableTreeNode targetFolder = model.findMutable(T23_ID);
        assertNotNull(targetFolder, "test2_3 must be in the model");
        TagNode.Folder tfBefore = (TagNode.Folder) targetFolder.getUserObject();
        assertEquals(false, tfBefore.expanded(),
            "test2_3 must start COLLAPSED to exercise the auto-expand path");

        // Mirror importData's auto-expand sequence verbatim.
        targetFolder.setUserObject(tfBefore.withExpanded(true));
        tree.expandPath(new TreePath(targetFolder.getPath()));

        // Run doMove via reflection (it's package-private in production).
        Method doMove = TagTreeTransferHandler.class.getDeclaredMethod(
            "doMove", JTree.class, UUID.class, UUID.class, int.class);
        doMove.setAccessible(true);
        boolean moved = (boolean) doMove.invoke(
            ((TagTreeTransferHandler) tree.getTransferHandler()),
            tree, SHELL_DRAGGED, T23_ID, -1);
        assertEquals(true, moved, "doMove should report success");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    });

    // Reload from disk via a fresh store to simulate restart.
    TagConfigStore fresh = new TagConfigStore(configFile);
    TagNode reloaded = fresh.load();

    TagNode.Folder test2 = (TagNode.Folder) reloaded.children().stream()
        .filter(c -> c.id().equals(TEST2_ID)).findFirst().orElseThrow();
    TagNode.Folder t23 = (TagNode.Folder) test2.children().get(0);

    assertEquals(T23_ID, t23.id());
    assertEquals(1, t23.children().size(),
        "test2_3 should contain the moved shell after reload");
    assertEquals(SHELL_DRAGGED, t23.children().get(0).id(),
        "moved shell should be the only child of test2_3");
  }
}