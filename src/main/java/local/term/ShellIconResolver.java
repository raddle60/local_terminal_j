package local.term;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.awt.Image;
import java.io.IOException;
import java.net.URL;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Resolves a {@link TagNode.Shell} to an {@link Icon}. Resolution order:
 *
 * <ol>
 *   <li>If {@code shell.iconPath()} is non-null and non-blank, load that
 *       file via {@link IconLoader}. On failure (missing file, unsupported
 *       format), log a warning and fall through.</li>
 *   <li>Match {@code shell.shellPath()} via {@link ShellNameExtractor};
 *       render the matching default SVG from
 *       {@code /shell-icons/&lt;name&gt;.svg} on the classpath at the
 *       resolver's configured {@code targetSize}. Cache by name (one
 *       icon per name for the JVM's lifetime) so the SVG is parsed and
 *       rasterised at most once per name.</li>
 *   <li>If no rule matches, use {@code /shell-icons/default.svg}.</li>
 *   <li>If the resource is missing or the SVG fails to render, return an
 *       empty {@link ImageIcon} — never null.</li>
 * </ol>
 *
 * <p>Why render the default SVGs at the target size rather than at 32×32?
 * The previous pipeline rasterised the SVGs once into 32×32 PNGs and then
 * downscaled them with bilinear interpolation to the L&amp;F folder-icon
 * size (typically 16×16). Downscaling a 32-px raster to 16-px blurs
 * text and thin strokes badly. Rendering the SVG at the final target
 * size keeps all the source vector detail and produces a crisp result
 * for every L&amp;F (Windows / Metal / Nimbus).
 */
public final class ShellIconResolver {
  private static final Logger LOG = LoggerFactory.getLogger(ShellIconResolver.class);

  private final IconLoader loader;
  private final int targetSize;
  private final ConcurrentMap<String, ImageIcon> defaultCache = new ConcurrentHashMap<>();

  public ShellIconResolver(IconLoader loader, int targetSize) {
    this.loader = loader;
    this.targetSize = targetSize;
  }

  public Icon getIcon(TagNode.Shell shell) {
    String userPath = shell.iconPath();
    if (userPath != null && !userPath.isBlank()) {
      try {
        // Path.of may throw InvalidPathException (a RuntimeException) for
        // paths with characters illegal on the host OS; catch it alongside
        // IOException so a malformed iconPath falls through to the default
        // rather than escaping getIcon and breaking the cell renderer.
        Image img = loadUserIcon(userPath);
        if (img != null) return new ImageIcon(img);
      } catch (IOException e) {
        LOG.warn("Could not load user icon {}: {}; using default",
            userPath, e.getMessage());
      }
    }
    String name = ShellNameExtractor.extract(shell.shellPath());
    return defaultIconFor(name == null ? "default" : name);
  }

  /**
   * Loads a user-supplied icon. SVGs are rendered at the resolver's
   * target size (same path as the default icons) so the user-supplied
   * vector also escapes the "decode-large-then-downscale" loss; raster
   * formats (PNG, JPEG, BMP, GIF, ICO) are decoded at native size and
   * the renderer scales them on the fly.
   */
  private Image loadUserIcon(String userPath) throws IOException {
    Path path = Path.of(userPath);
    if (isSvgPath(userPath)) {
      return loader.loadSvg(path, targetSize);
    }
    return loader.load(path);
  }

  private ImageIcon defaultIconFor(String name) {
    return defaultCache.computeIfAbsent(name, n -> {
      String resource = "/shell-icons/" + n + ".svg";
      URL url = ShellIconResolver.class.getResource(resource);
      if (url == null) {
        LOG.warn("Default icon resource missing: {}", resource);
        return new ImageIcon();
      }
      try {
        return new ImageIcon(loader.loadSvg(url, targetSize));
      } catch (IOException e) {
        LOG.warn("Could not render default icon {}: {}", resource, e.getMessage());
        return new ImageIcon();
      }
    });
  }

  private static boolean isSvgPath(String p) {
    if (p == null) return false;
    String lower = p.toLowerCase(java.util.Locale.ROOT);
    return lower.endsWith(".svg");
  }

  // Visible to tests: the rendered size the resolver uses for default icons.
  int targetSize() {
    return targetSize;
  }
}
