package local.term;

import org.apache.batik.anim.dom.SAXSVGDocumentFactory;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.apache.commons.imaging.Imaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Loads an {@link Image} from a {@link Path}, dispatching on file extension:
 * raster formats (PNG, JPEG, BMP, GIF, WBMP) via {@link ImageIO};
 * ICO via Apache Commons Imaging;
 * SVG via Apache Batik.
 *
 * <p>Caches successful results in a bounded LRU (64 entries) keyed by
 * {@link Path}, so the same file is only decoded once per JVM.
 *
 * <p>Returns {@code null} for unrecognized formats and for content that
 * is recognized but undecodable (e.g. a corrupt PNG) — the caller decides
 * the fallback. Throws {@link IOException} for I/O failures (missing file,
 * permission denied, etc.).
 *
 * <p>For SVG, two size modes are supported: {@link #loadSvg(Path)} decodes
 * at a default 32x32 (suitable for user-supplied icons that will be
 * scaled downstream), and {@link #loadSvg(Path, int)} / {@link #loadSvg(URL, int)}
 * render at an explicit size — used by the resolver to avoid the
 * "decode-large-then-downscale" quality loss for the default shell icons.
 */
public final class IconLoader {
  private static final Logger LOG = LoggerFactory.getLogger(IconLoader.class);
  private static final int CACHE_MAX_ENTRIES = 64;
  private static final int DEFAULT_SVG_SIZE = 64;

  /** Bounded LRU: at most CACHE_MAX_ENTRIES, last-access wins. */
  private final LinkedHashMap<Path, Image> cache = new LinkedHashMap<>(
      CACHE_MAX_ENTRIES, 0.75f, /* accessOrder */ true) {
    @Override
    protected boolean removeEldestEntry(Map.Entry<Path, Image> e) {
      return size() > CACHE_MAX_ENTRIES;
    }
  };

  /**
   * Loads {@code path} and returns the decoded image. Returns null when
   * the format is unrecognized; throws IOException for I/O errors.
   */
  public Image load(Path path) throws IOException {
    synchronized (cache) {
      Image cached = cache.get(path);
      if (cached != null) return cached;
    }
    Image img = doLoad(path);
    if (img != null) {
      synchronized (cache) { cache.put(path, img); }
    }
    return img;
  }

  private Image doLoad(Path path) throws IOException {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    try {
      if (name.endsWith(".svg")) return loadSvg(path);
      if (name.endsWith(".ico")) return loadIco(path);
      return ImageIO.read(path.toFile());
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      LOG.warn("Could not load icon {}: {}", path, e.getMessage());
      return null;
    }
  }

  private Image loadSvg(Path path) throws IOException {
    return loadSvg(path, DEFAULT_SVG_SIZE);
  }

  /**
   * Renders {@code path} as an SVG of side length {@code sizePx}×{@code sizePx}.
   * Used by callers that know the final on-screen size and want to skip the
   * "decode-at-32-then-downscale" quality loss.
   */
  public Image loadSvg(Path path, int sizePx) throws IOException {
    return loadSvg(Files.newInputStream(path), sizePx);
  }

  /**
   * Renders an SVG read from a classpath {@link URL} at {@code sizePx}×{@code sizePx}.
   * Used by {@link ShellIconResolver} for the default shell icons, which
   * live on the classpath rather than the filesystem.
   */
  public Image loadSvg(URL url, int sizePx) throws IOException {
    return loadSvg(url.openStream(), sizePx);
  }

  private Image loadSvg(InputStream in, int sizePx) throws IOException {
    try (in) {
      SAXSVGDocumentFactory factory = new SAXSVGDocumentFactory(null);
      Document doc = factory.createDocument(null, in);
      PNGTranscoder t = new PNGTranscoder();
      t.addTranscodingHint(PNGTranscoder.KEY_WIDTH, (float) sizePx);
      t.addTranscodingHint(PNGTranscoder.KEY_HEIGHT, (float) sizePx);
      java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
      t.transcode(new TranscoderInput(doc), new TranscoderOutput(baos));
      return ImageIO.read(new java.io.ByteArrayInputStream(baos.toByteArray()));
    } catch (org.apache.batik.transcoder.TranscoderException e) {
      throw new IOException("SVG transcoding failed: " + e.getMessage(), e);
    }
  }

  private Image loadIco(Path path) throws IOException {
    return Imaging.getBufferedImage(path.toFile());
  }
}
