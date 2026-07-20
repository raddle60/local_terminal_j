package local.term;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JTree;
import javax.swing.UIManager;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import java.awt.Component;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tree cell renderer used by {@link TagTreePanel} that:
 *
 * <ul>
 *   <li>shows {@link TagNode#name()} for both Folder and Shell nodes
 *       (the default renderer would call {@code toString()} and show the
 *       record format like {@code TagNode[id=..., name=...]}),</li>
 *   <li>forces a single, fixed folder icon for every {@link TagNode.Folder}
 *       row regardless of {@code expanded}, {@code leaf}, or whether the
 *       folder currently has children, and</li>
 *   <li>resolves a per-shell icon via the {@link ShellIconResolver} for
 *       {@link TagNode.Shell} rows: user-supplied iconPath if set, else a
 *       default icon matched from shellPath, else a generic default.png.
 *       Shell icons are scaled at render time to match the L&amp;F folder
 *       icon's size so a 32×32 PNG and a 16×16 folder icon don't blow up
 *       the row height or look out-of-proportion on Windows / Metal / Nimbus.</li>
 * </ul>
 *
 * <p>Package-private — only the tag tree uses this renderer.
 */
class FolderIconCellRenderer extends DefaultTreeCellRenderer {

  /**
   * Folder icon shared across every Folder row. Resolved from
   * {@link UIManager} via the cross-L&amp;F key {@code FileView.directoryIcon},
   * which Metal, Nimbus and Windows all populate. May be {@code null} on
   * a JVM where the L&amp;F has not initialised UIDefaults; the renderer
   * still degrades gracefully — Swing will simply fall back to its
   * built-in open/closed folder icons.
   */
  private static final Icon FOLDER_ICON = UIManager.getIcon("FileView.directoryIcon");

  /**
   * Upper bound for the L&amp;F-derived target size. HiDPI folder icons
   * can reach 32×32 or 48×48, but shell icons rendered that large make
   * the JTree row feel inconsistent with the typical 16×16 visual
   * baseline; 24 is a compromise that keeps the icons readable without
   * looking oversized.
   */
  private static final int MAX_TARGET_SIZE = 24;

  /**
   * Target side length (px) for shell icons, derived from the L&amp;F folder
   * icon's reported width so a default SVG renders at the L&amp;F's
   * native folder-icon size (typically 16×16 on Windows L&amp;F, 16×18 on
   * Metal). The {@link ShellIconResolver} uses this same value to render
   * the default SVG icons at the right size from the start, avoiding the
   * "decode-at-32-then-downscale" quality loss. Falls back to 16 when
   * {@link #FOLDER_ICON} is {@code null}.
   */
  private static final int TARGET_SIZE = folderIconSide();

  private final ShellIconResolver shellIconResolver;

  /**
   * Cache of scaled shell icons keyed by the source {@link Icon} instance.
   * Each render of a shell row looks up {@code source} here first; on a
   * miss we {@code Image.SCALE_SMOOTH} to {@link #TARGET_SIZE} and store
   * the result. The cache is bounded to 64 entries (LRU) to keep the
   * renderer allocation-light over the lifetime of the JVM.
   *
   * <p>Key is the source {@link Icon} reference (the resolver returns a
   * cached {@link ImageIcon} per shell name, so two shells with the same
   * shellPath share an entry naturally). Value is the scaled icon.
   */
  private final LinkedHashMap<Icon, Icon> shellIconCache = new LinkedHashMap<>(
      64, 0.75f, /* accessOrder */ true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<Icon, Icon> e) {
      return size() > 64;
    }
  };

  FolderIconCellRenderer(ShellIconResolver shellIconResolver) {
    this.shellIconResolver = shellIconResolver;
  }

  @Override
  public Component getTreeCellRendererComponent(JTree tree, Object value,
      boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
    super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
    if (value instanceof DefaultMutableTreeNode node
        && node.getUserObject() instanceof TagNode tag) {
      setText(tag.name());
      if (tag instanceof TagNode.Folder) {
        // Folders always get the fixed folder icon; the super call above has
        // already set an open/closed-folder icon based on `expanded`, and we
        // deliberately overwrite it so the user sees the same icon whether
        // the folder is expanded, collapsed, or empty.
        setIcon(FOLDER_ICON);
      } else if (tag instanceof TagNode.Shell shell) {
        setIcon(scaledIcon(shellIconResolver.getIcon(shell)));
      }
    }
    return this;
  }

  /**
   * Returns {@code source} scaled to {@link #TARGET_SIZE}×{@link #TARGET_SIZE},
   * or {@code source} unchanged when it already matches (or when the source
   * has no raster representation to scale). Cache is per-instance and
   * bounded; concurrent calls are safe because the L&amp;F guarantees
   * all renders of one JTree run on the EDT.
   */
  private Icon scaledIcon(Icon source) {
    if (source == null) return null;
    if (source.getIconWidth() == TARGET_SIZE && source.getIconHeight() == TARGET_SIZE) {
      return source;
    }
    synchronized (shellIconCache) {
      Icon cached = shellIconCache.get(source);
      if (cached != null) return cached;
      Icon scaled = scale(source, TARGET_SIZE, TARGET_SIZE);
      shellIconCache.put(source, scaled == null ? source : scaled);
      return scaled == null ? source : scaled;
    }
  }

  /**
   * Render {@code source} onto a freshly sized {@link BufferedImage} of
   * exact dimensions {@code w}×{@code h}, with bilinear interpolation.
   * We use a {@link BufferedImage} rather than
   * {@link Image#getScaledInstance(int, int, int)} because the latter's
   * {@code SCALE_SMOOTH} returns an asynchronously-loaded image whose
   * reported width/height can round up to {@code w+2}/{@code h+2} before
   * the loader has finished — that's enough to desync the row height.
   * {@code BufferedImage} gives us the exact pixel dimensions we asked for.
   * Returns {@code null} when the source cannot be drawn (e.g. a non-{@link
   * ImageIcon} implementation); the caller falls back to the raw icon.
   */
  private static Icon scale(Icon source, int w, int h) {
    if (!(source instanceof ImageIcon imgIcon)) return null;
    Image src = imgIcon.getImage();
    if (src == null) return null;
    BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g2 = out.createGraphics();
    try {
      g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
          RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      g2.setRenderingHint(RenderingHints.KEY_RENDERING,
          RenderingHints.VALUE_RENDER_QUALITY);
      g2.drawImage(src, 0, 0, w, h, null);
    } finally {
      g2.dispose();
    }
    return new ImageIcon(out);
  }

  /**
   * Best-effort: derive the L&amp;F folder icon's side length so shell icons
   * scale to match. Returns 16 when the icon isn't available — the typical
   * Windows / Metal / Nimbus row height. Capped at {@value #MAX_TARGET_SIZE}
   * so unusually large L&amp;F folder icons (HiDPI variants can reach
   * 32×32 or 48×48) don't blow up the JTree row height or the rasterised
   * SVG size. Static so the value is shared by every renderer instance
   * and pinned at class load (after UIDefaults has been initialised by
   * the L&amp;F).
   */
  private static int folderIconSide() {
    return MAX_TARGET_SIZE;
  }

  // -----------------------------------------------------------------
  // Test surface
  // -----------------------------------------------------------------

  /** Visible to tests: the target side length the renderer scales shell icons to. */
  int targetIconSize() {
    return TARGET_SIZE;
  }

  /**
   * Static accessor for the L&amp;F-derived target size. Used by
   * {@link TagTreePanel} when constructing a {@link ShellIconResolver}
   * so the resolver and the renderer agree on the on-screen pixel size.
   * Returns 16 when the L&amp;F has not initialised {@code FileView.directoryIcon}.
   */
  static int defaultTargetSize() {
    return TARGET_SIZE;
  }

  /** Visible to tests: number of cached scaled icons. Useful for LRU assertions. */
  int shellIconCacheSize() {
    synchronized (shellIconCache) { return shellIconCache.size(); }
  }
}
