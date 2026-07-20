package local.term;

import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.ImageIcon;
import java.io.IOException;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the fallback chain: user iconPath > name-matched default > default.svg > empty icon.
 *
 * <p>The default SVGs are present in the test classpath (committed to
 * {@code src/main/resources/shell-icons/}), so all "default" branches
 * resolve to real images. The user-icon branches use the fixtures in
 * {@code src/test/resources/fixtures/}.
 */
class ShellIconResolverTest {

  private static final int TARGET_SIZE = 16;

  private final IconLoader loader = new IconLoader();
  private final ShellIconResolver resolver = new ShellIconResolver(loader, TARGET_SIZE);

  private TagNode.Shell shell(String shellPath, String iconPath) {
    return new TagNode.Shell(java.util.UUID.randomUUID(), "name",
        shellPath, iconPath, "C:\\");
  }

  @Test void getIcon_userIconPathSet_loadsThatFile() throws IOException {
    TagNode.Shell s = shell("C:\\bin\\cmd.exe",
        Paths.get("src/test/resources/fixtures/test-pixel.png").toAbsolutePath().toString());
    Icon icon = resolver.getIcon(s);
    assertNotNull(icon);
    // ImageIcon with non-zero dimensions.
    assertTrue(icon.getIconWidth() > 0);
  }

  @Test void getIcon_userIconPathSetButFileMissing_fallsBackToDefault() {
    TagNode.Shell s = shell("C:\\bin\\cmd.exe",
        Paths.get("src/test/resources/fixtures/does-not-exist.png").toAbsolutePath().toString());
    Icon icon = resolver.getIcon(s);
    assertNotNull(icon, "fallback must never return null");
  }

  @Test void getIcon_iconPathNull_usesShellPathMatch() {
    TagNode.Shell s = shell("C:\\Program Files\\Git\\bin\\bash.exe", null);
    Icon icon = resolver.getIcon(s);
    assertNotNull(icon);
  }

  @Test void getIcon_iconPathBlank_usesShellPathMatch() {
    TagNode.Shell s = shell("C:\\Program Files\\Git\\bin\\bash.exe", "   ");
    Icon icon = resolver.getIcon(s);
    assertNotNull(icon);
  }

  @Test void getIcon_unknownShell_returnsDefaultIcon() {
    TagNode.Shell s = shell("D:\\tools\\custom-shell.exe", null);
    Icon icon = resolver.getIcon(s);
    assertNotNull(icon);
  }

  @Test void getIcon_defaultIconCached_returnsSameIconForSameName() {
    // Two shells with no iconPath and the same shellPath produce the same
    // default icon (cached on the resolver).
    TagNode.Shell s1 = shell("C:\\bin\\cmd.exe", null);
    TagNode.Shell s2 = shell("C:\\bin\\cmd.exe", null);
    Icon a = resolver.getIcon(s1);
    Icon b = resolver.getIcon(s2);
    assertSame(a, b, "default icons are cached on the resolver");
  }

  @Test void getIcon_defaultIcon_isRenderedAtTargetSize() {
    // The whole point of refactoring the resolver: the default SVG is
    // rendered at the configured target size, not at a hard-coded 32x32
    // and then downscaled by the renderer. The icon we get back from
    // getIcon() must already be the right on-screen size.
    TagNode.Shell s = shell("C:\\Program Files\\Git\\bin\\bash.exe", null);
    Icon icon = resolver.getIcon(s);
    assertNotNull(icon);
    if (icon instanceof ImageIcon img) {
      assertEquals(TARGET_SIZE, img.getIconWidth(),
          "default icon must be rendered at the resolver's target size");
      assertEquals(TARGET_SIZE, img.getIconHeight());
    }
  }
}