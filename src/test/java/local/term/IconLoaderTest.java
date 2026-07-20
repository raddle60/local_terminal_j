package local.term;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Image;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link IconLoader}. Covers format dispatch, cache hit/evict,
 * and error paths.
 */
class IconLoaderTest {

  private IconLoader loader;

  @BeforeEach void setUp() {
    loader = new IconLoader();
  }

  private Path fixture(String name) {
    return Paths.get("src/test/resources/fixtures").resolve(name);
  }

  @Test void load_rasterPng_returnsImage() throws Exception {
    Image img = loader.load(fixture("test-pixel.png"));
    assertNotNull(img);
    assertTrue(img.getWidth(null) > 0);
  }

  @Test void load_rasterJpeg_returnsImage() throws Exception {
    Image img = loader.load(fixture("test-pixel.jpg"));
    assertNotNull(img);
  }

  @Test void load_ico_returnsImage() throws Exception {
    Image img = loader.load(fixture("test-pixel.ico"));
    assertNotNull(img);
  }

  @Test void load_svg_returnsImage() throws Exception {
    Image img = loader.load(fixture("test-pixel.svg"));
    assertNotNull(img);
  }

  @Test void loadSvg_pathWithSize_rendersAtRequestedSize() throws Exception {
    // loadSvg(Path, int) is the size-aware variant used by the
    // ShellIconResolver to render default shell SVGs at the L&F
    // folder-icon size (typically 16x16) rather than at the default 32.
    Image img = loader.loadSvg(fixture("test-pixel.svg"), 16);
    assertNotNull(img);
    assertEquals(16, img.getWidth(null),
        "loadSvg(path, size) must rasterise at exactly the requested size");
  }

  @Test void loadSvg_urlWithSize_rendersAtRequestedSize() throws Exception {
    // loadSvg(URL, int) is the size-aware variant used by the
    // ShellIconResolver for classpath resources (the default shell icons).
    java.net.URL url = fixture("test-pixel.svg").toUri().toURL();
    Image img = loader.loadSvg(url, 24);
    assertNotNull(img);
    assertEquals(24, img.getWidth(null),
        "loadSvg(url, size) must rasterise at exactly the requested size");
  }

  @Test void load_missingFile_throwsIOException() {
    assertThrows(IOException.class, () -> loader.load(fixture("does-not-exist.png")));
  }

  @Test void load_unsupportedFormat_returnsNull() throws Exception {
    // .txt is not a recognized image format; loader must return null, not throw.
    assertNull(loader.load(fixture("not-an-image.txt")));
  }

  @Test void load_samePathTwice_returnsSameCachedInstance() throws Exception {
    Image a = loader.load(fixture("test-pixel.png"));
    Image b = loader.load(fixture("test-pixel.png"));
    assertSame(a, b, "second load must hit the cache");
  }

  @Test void load_moreThan64DistinctPaths_stillReturnsImages() throws Exception {
    // Exercise eviction by loading 100 distinct Paths. After the 65th,
    // the cache's removeEldestEntry kicks in; we verify every load still
    // returns a non-null image. (The cache can't be enumerated from
    // outside, so we test the externally-observable property: eviction
    // is not a footgun that throws or corrupts the loader.)
    Path source = fixture("test-pixel.png");
    Path tmpDir = Files.createTempDirectory("icon-loader-evict-test-");
    try {
      for (int i = 0; i < 100; i++) {
        Path p = tmpDir.resolve("pixel-" + i + ".png");
        Files.copy(source, p, StandardCopyOption.REPLACE_EXISTING);
        Image img = loader.load(p);
        assertNotNull(img, "load #" + i + " must succeed even after eviction");
      }
    } finally {
      // Best-effort cleanup; the JVM exits shortly after the test suite.
      try (var stream = Files.list(tmpDir)) {
        stream.forEach(child -> {
          try { Files.deleteIfExists(child); } catch (IOException ignored) { /* best effort */ }
        });
      } catch (IOException ignored) { /* best effort */ }
      try { Files.deleteIfExists(tmpDir); } catch (IOException ignored) { /* best effort */ }
    }
  }
}
