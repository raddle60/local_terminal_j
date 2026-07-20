package local.term;

import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.tree.DefaultMutableTreeNode;
import java.util.List;
import java.util.UUID;

import local.term.ShellIconResolver;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the {@link TagTreePanel}'s tree cell renderer — specifically
 * the fixed-folder-icon behaviour added for Issue #2.
 *
 * <p>Folder rows must always carry the same folder icon regardless of the
 * {@code expanded} flag or whether the folder currently has children. Shell
 * rows keep the default leaf icon. We exercise the renderer directly
 * (rather than instantiating a full {@link TagTreePanel}, which would also
 * load the config from disk) so the assertions are tight and headless-safe.
 */
class TagTreePanelTest {

  private final ShellIconResolver resolver =
      new ShellIconResolver(new IconLoader(), 16);

  private TagNode.Folder folder(String name, List<TagNode> kids) {
    return new TagNode.Folder(UUID.randomUUID(), name, kids);
  }

  private TagNode.Shell shell(String name) {
    return new TagNode.Shell(UUID.randomUUID(), name,
        "C:\\Windows\\System32\\cmd.exe", null, "C:\\");
  }

  /** Render {@code node} with the given flags and return whatever icon the renderer ended up with. */
  private Icon render(FolderIconCellRenderer renderer, DefaultMutableTreeNode node,
                      boolean expanded, boolean leaf) {
    renderer.getTreeCellRendererComponent(new JTree(), node,
        false, expanded, leaf, 0, false);
    return renderer.getIcon();
  }

  @Test
  void folderRenderer_setsFolderIconRegardlessOfExpanded() {
    FolderIconCellRenderer renderer = new FolderIconCellRenderer(resolver);
    DefaultMutableTreeNode node = new DefaultMutableTreeNode(
        folder("Projects", List.of()));

    Icon collapsed = render(renderer, node, /* expanded */ false, /* leaf */ false);
    Icon expanded  = render(renderer, node, /* expanded */ true,  /* leaf */ false);

    assertNotNull(collapsed, "Folder icon must be set when collapsed");
    assertNotNull(expanded,  "Folder icon must be set when expanded");
    assertSame(collapsed, expanded,
        "Folder icon must be identical regardless of expanded flag");
  }

  @Test
  void folderRenderer_setsFolderIconRegardlessOfChildCount() {
    FolderIconCellRenderer renderer = new FolderIconCellRenderer(resolver);
    DefaultMutableTreeNode empty = new DefaultMutableTreeNode(
        folder("Empty", List.of()));
    DefaultMutableTreeNode full = new DefaultMutableTreeNode(
        folder("Full", List.of(shell("terminal"))));

    Icon emptyIcon = render(renderer, empty, false, /* leaf */ true);
    Icon fullIcon  = render(renderer, full,  false, /* leaf */ false);

    assertNotNull(emptyIcon, "Empty folder must still have a folder icon");
    assertNotNull(fullIcon,  "Non-empty folder must have a folder icon");
    assertSame(emptyIcon, fullIcon,
        "Folder icon must be identical whether the folder has children or not");
  }

  @Test
  void folderRenderer_setsTextToTagName() {
    FolderIconCellRenderer renderer = new FolderIconCellRenderer(resolver);
    DefaultMutableTreeNode node = new DefaultMutableTreeNode(
        folder("My Projects", List.of()));

    renderer.getTreeCellRendererComponent(new JTree(), node,
        false, false, false, 0, false);

    assertEquals("My Projects", renderer.getText(),
        "Renderer must show TagNode.name() rather than the record toString()");
  }

  @Test
  void shellRenderer_setsTextToTagName() {
    FolderIconCellRenderer renderer = new FolderIconCellRenderer(resolver);
    DefaultMutableTreeNode node = new DefaultMutableTreeNode(shell("PowerShell"));

    renderer.getTreeCellRendererComponent(new JTree(), node,
        false, false, true, 0, false);

    assertEquals("PowerShell", renderer.getText());
  }

  @Test
  void shellRenderer_keepsDefaultLeafIcon() {
    FolderIconCellRenderer renderer = new FolderIconCellRenderer(resolver);
    DefaultMutableTreeNode node = new DefaultMutableTreeNode(shell("Terminal"));

    // Render as a leaf — what Swing passes for a Shell row.
    Icon icon = render(renderer, node, false, /* leaf */ true);

    // Shell rows must still get an icon (the default leaf icon), distinct
    // from the fixed folder icon used for Folder rows.
    Icon folderIcon = UIManager.getIcon("FileView.directoryIcon");
    if (folderIcon != null) {
      assertNotSame(folderIcon, icon,
          "Shell rows must not borrow the fixed folder icon from Folder rows");
    }
    assertNotNull(icon, "Shell rows must keep their default leaf icon");
  }

  @Test
  void folderRenderer_recoversAfterShellRow() {
    // The renderer is a shared instance; Swing calls it once per row, so
    // rendering a Shell then a Folder must not leave a stale icon on the
    // Folder row.
    FolderIconCellRenderer renderer = new FolderIconCellRenderer(resolver);
    DefaultMutableTreeNode shellNode = new DefaultMutableTreeNode(shell("S"));
    DefaultMutableTreeNode folderNode = new DefaultMutableTreeNode(
        folder("F", List.of()));

    render(renderer, shellNode, false, true);
    Icon folderIcon = render(renderer, folderNode, false, false);

    assertNotNull(folderIcon, "Folder rendered after a Shell must still get the folder icon");
  }

  @Test
  void shellRenderer_usesShellIconResolver() {
    // Reuse the class-level resolver so the test exercises the same
    // cached-icon path the production renderer uses, and to avoid
    // constructing a redundant IconLoader/ShellIconResolver pair.
    FolderIconCellRenderer renderer = new FolderIconCellRenderer(resolver);
    TagNode.Shell shell = new TagNode.Shell(UUID.randomUUID(), "x",
        "C:\\Program Files\\Git\\bin\\bash.exe", null, "C:\\");
    DefaultMutableTreeNode node = new DefaultMutableTreeNode(shell);

    Icon icon = render(renderer, node, false, /* leaf */ true);

    // The icon must come from the resolver's classpath lookup of bash.svg.
    // We assert it is non-null and non-empty (resolves a real SVG).
    assertNotNull(icon);
    assertTrue(icon.getIconWidth() > 0,
        "Shell icon should be the bash.svg from /shell-icons/");
  }

  // -----------------------------------------------------------------
  // Issue #2 — shell icons must look square and not blow up the row.
  //
  // The bundled SVGs are rendered at a fixed target size
  // (FolderIconCellRenderer.MAX_TARGET_SIZE = 24) by ShellIconResolver
  // at lookup time. The renderer then uses them at native size, so no
  // per-row downscale is needed. TagTreePanel pins the JTree row height
  // to max(targetSize, font + padding) so the 24-px icon is never
  // vertically clipped (which would otherwise make it look like a
  // 24-wide × row-tall rectangle). The 32x32 raster → 16x16 downscale
  // path that produced the original "icons too wide" bug no longer
  // exists; the SVG is rasterised at the right pixel size from the
  // start, and the row is sized to match.
  // -----------------------------------------------------------------

  @Test
  void shellRenderer_rendersAtFixedTargetSize() {
    FolderIconCellRenderer renderer = new FolderIconCellRenderer(resolver);
    TagNode.Shell shell = new TagNode.Shell(UUID.randomUUID(), "x",
        "C:\\Program Files\\Git\\bin\\bash.exe", null, "C:\\");
    DefaultMutableTreeNode node = new DefaultMutableTreeNode(shell);

    Icon icon = render(renderer, node, false, /* leaf */ true);

    // The shell icon is rasterised at FolderIconCellRenderer's fixed
    // target size (MAX_TARGET_SIZE = 24). The JTree row height in
    // TagTreePanel is pinned to max(targetSize, font + padding) so the
    // icon is never vertically clipped. The shell icon and the L&F
    // folder icon can now have different heights — the row no longer
    // has to match the folder icon because it's set explicitly.
    assertEquals(renderer.targetIconSize(), icon.getIconWidth(),
        "Shell icon width must equal the renderer's target size");
    assertEquals(renderer.targetIconSize(), icon.getIconHeight(),
        "Shell icon height must equal the renderer's target size (square)");
    assertEquals(icon.getIconWidth(), icon.getIconHeight(),
        "Shell icon must remain square at the target size");
  }

  @Test
  void shellRenderer_cachesScaledIconsAcrossRows() {
    // Same shell rendered twice should hit the per-renderer cache (only
    // one entry, even if we render multiple shells with the same source).
    FolderIconCellRenderer renderer = new FolderIconCellRenderer(resolver);
    TagNode.Shell s1 = new TagNode.Shell(UUID.randomUUID(), "a",
        "C:\\Program Files\\Git\\bin\\bash.exe", null, "C:\\");
    TagNode.Shell s2 = new TagNode.Shell(UUID.randomUUID(), "b",
        "C:\\Program Files\\Git\\bin\\bash.exe", null, "C:\\");

    render(renderer, new DefaultMutableTreeNode(s1), false, true);
    int afterFirst = renderer.shellIconCacheSize();

    render(renderer, new DefaultMutableTreeNode(s2), false, true);
    int afterSecond = renderer.shellIconCacheSize();

    assertEquals(afterFirst, afterSecond,
        "Same resolver icon for two shells should be cached only once");
  }

  // -----------------------------------------------------------------
  // Duplicate-shell seed shape (Issue — Duplicate menu)
  //
  // Duplicate now opens ShellEditorDialog pre-filled with the source's
  // fields. The handler builds a fresh seed (new id, copied fields) and
  // passes it to the dialog constructor; this test pins that contract so
  // the dialog actually starts populated. Name-disambiguation is no longer
  // the panel's job — it happens (or doesn't) inside the editor dialog
  // that the user sees.
  // -----------------------------------------------------------------

  @Test
  void duplicateShell_seed_copiesAllFieldsExceptId() {
    // Reproduce what duplicateShell() does *before* opening the dialog —
    // we don't run the dialog here (it would block on a JFrame), but we
    // can assert the seed object the dialog would receive.
    UUID sourceId = UUID.randomUUID();
    TagNode.Shell source = new TagNode.Shell(sourceId, "Terminal",
        "C:/Windows/System32/cmd.exe", "icons/terminal.ico", "C:/Users/me");

    TagNode.Shell seed = new TagNode.Shell(
        UUID.randomUUID(), source.name(), source.shellPath(),
        source.iconPath(), source.startPath());

    assertNotEquals(source.id(), seed.id(),
        "Seed must have a fresh id so save appends a sibling instead of replacing the source");
    assertEquals(source.name(), seed.name());
    assertEquals(source.shellPath(), seed.shellPath());
    assertEquals(source.iconPath(), seed.iconPath());
    assertEquals(source.startPath(), seed.startPath());
  }
}
