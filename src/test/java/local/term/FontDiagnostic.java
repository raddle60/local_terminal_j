package local.term;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * 一次性诊断工具：枚举系统已安装字体，验证
 * {@link GraphicsEnvironment#getAvailableFontFamilyNames()} 是否能看到
 * "Noto Color Emoji"，探测 Java 的 {@code canDisplayUpTo} 对该字体的
 * emoji 覆盖判定，并实际绘制几个 emoji 探测 Java 在 Windows 上是否能真的渲染
 * COLR/CBDT 彩色 emoji 字形。
 *
 * <p>运行方式（Maven exec，test 作用域）：
 * <pre>
 * mvn test-compile exec:java \
 *     -Dexec.mainClass=local.term.FontDiagnostic \
 *     -Dexec.classpathScope=test
 * </pre>
 */
public final class FontDiagnostic {

  private FontDiagnostic() {}

  /** 与 {@code FontUtils.EMOJI_PROBE} 一致：😀🚀✨❤⭐ */
  private static final String EMOJI_PROBE = "😀🚀✨❤⭐";
  private static final String TARGET = "Noto Color Emoji";

  public static void main(String[] args) {
    String[] families = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .getAvailableFontFamilyNames();
    List<String> familyList = Arrays.asList(families);

    System.out.println("== Step 1: enumerate families containing 'noto' or 'emoji' ==");
    long matched = 0;
    for (String f : families) {
      String lower = f.toLowerCase();
      if (lower.contains("noto") || lower.contains("emoji")) {
        System.out.println("  - " + f);
        matched++;
      }
    }
    System.out.println("  (" + matched + " match)");

    System.out.println();
    System.out.println("== Step 2: exact-name lookup ==");
    boolean exact = familyList.contains(TARGET);
    System.out.println("  '" + TARGET + "' in family list: " + exact);

    Font requested = new Font(TARGET, Font.PLAIN, 12);
    System.out.println();
    System.out.println("== Step 3: Java's resolved family for Font(\"" + TARGET + "\", 12) ==");
    System.out.println("  font.getFamily()  = " + requested.getFamily());
    System.out.println("  font.getFontName() = " + requested.getFontName());

    System.out.println();
    System.out.println("== Step 4: per-character canDisplay on requested Font ==");
    char[] chars = {'😀', '🚀', '✨', '❤', '️', '⭐'};
    String[] names = {"U+0001F600 grinning face", "U+0001F680 rocket",
        "U+00002728 sparkles",       "U+00002764 heavy black heart",
        "U+0000FE0F variation selector-16", "U+00002B50 white medium star"};
    for (int i = 0; i < chars.length; i++) {
      System.out.println("  canDisplay " + names[i] + " = " + requested.canDisplay(chars[i]));
    }

    System.out.println();
    System.out.println("== Step 5: canDisplayUpTo on FontUtils EMOJI_PROBE ==");
    int idx = requested.canDisplayUpTo(EMOJI_PROBE);
    System.out.println("  canDisplayUpTo result = " + idx
        + (idx == -1 ? "  -> FontUtils.hasEmojiGlyphs returns TRUE"
                     : "  -> FontUtils.hasEmojiGlyphs returns FALSE (filtered out)"));

    System.out.println();
    System.out.println("== Step 6: actually paint emoji under each rendering-hint strategy ==");
    tryLoadedFont();
    Graphics2D scratch = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
    try {
        for (String family : new String[]{"Noto Color Emoji", "Segoe UI Emoji"}) {
          System.out.println("  -- family: " + family + " --");
          drawAndCount(scratch, family, "drawString default", family, null, true);
          drawAndCount(scratch, family, "drawString AA ON",    family, Map.of(
              RenderingHints.KEY_TEXT_ANTIALIASING,  RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
              RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON), true);
          drawAndCount(scratch, family, "drawString AA OFF",   family, Map.of(
              RenderingHints.KEY_TEXT_ANTIALIASING,  RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
              RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF), true);
          drawAndCount(scratch, family, "drawChars   AA OFF",  family, Map.of(
              RenderingHints.KEY_TEXT_ANTIALIASING,  RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
              RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF), false);
        }
      } finally {
        scratch.dispose();
      }
  }

  /**
   * Render the emoji probe via {@code drawString} or {@code drawChars} into an
   * ARGB image with the supplied hint map (null = no hint map). Counts pixels
   * significantly different from the background — zero means "Java drew
   * nothing visible". Lets us tell which hint strategy actually engages the
   * color-bitmap blit path for COLR/CBDT emoji fonts on Windows.
   */
  private static void drawAndCount(Graphics2D scratch,
                                   String family,
                                   String label,
                                   String fontFamily,
                                   Map<RenderingHints.Key, Object> hints,
                                   boolean useDrawString) {
    String probe = "🚀✨⭐"; // 🚀✨⭐
    Font font = new Font(fontFamily, Font.PLAIN, 24);
    FontMetrics fm = scratch.getFontMetrics(font);
    int w = Math.max(8, fm.stringWidth(probe)) + 4;
    int h = Math.max(8, fm.getHeight()) + 4;
    BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    try {
      g.setColor(new Color(0x1e, 0x1e, 0x1e));
      g.fillRect(0, 0, w, h);
      g.setColor(Color.WHITE);
      if (hints != null) {
        for (var e : hints.entrySet()) g.setRenderingHint(e.getKey(), e.getValue());
      }
      g.setFont(font);
      if (useDrawString) {
        g.drawString(probe, 2, h - 4);
      } else {
        char[] cs = probe.toCharArray();
        g.drawChars(cs, 0, cs.length, 2, h - 4);
      }
    } finally {
      g.dispose();
    }
    int painted = 0;
    int colored = 0;
    for (int y = 0; y < h; y++) {
      for (int x = 0; x < w; x++) {
        int rgb = img.getRGB(x, y) & 0xFFFFFF;
        int rch = (rgb >> 16) & 0xFF;
        int gch = (rgb >>  8) & 0xFF;
        int bch =  rgb        & 0xFF;
        int dr = Math.abs(rch - 0x1e);
        int dg = Math.abs(gch - 0x1e);
        int db = Math.abs(bch - 0x1e);
        if (dr + dg + db > 30) {
          painted++;
          // "Colored" = max(R,G,B) - min(R,G,B) is large enough that the
          // pixel cannot have been produced by equal-channel (grayscale)
          // AA. Saturation >= 40 is a safe lower bound for typical emoji
          // primaries (red 0xE0, green 0xB0, blue 0x40, etc.).
          int sat = Math.max(rch, Math.max(gch, bch)) - Math.min(rch, Math.min(gch, bch));
          if (sat >= 40) colored++;
        }
      }
    }
    String verdict;
    if (painted < 5) verdict = "BLANK";
    else if (colored == 0) verdict = "GRAYSCALE-ONLY (color path not engaged)";
    else if (colored < painted / 4) verdict = "MOSTLY-GRAYSCALE";
    else verdict = "colored (visible)";
    System.out.println("    " + label
        + ": painted=" + painted + "/" + (w * h)
        + "  colored=" + colored
        + "  -> " + verdict);
  }

  /**
   * Try loading Noto Color Emoji via {@code Font.createFont()} directly from the
   * .ttf file and registering it. Bypasses Java's font-name → font mapping
   * (which is the path that drops COLR/CBDT bitmaps on Windows). If
   * createFont produces a font that actually renders, the workaround for the
   * preview label would be to register it once at startup and look up by name
   * thereafter.
   *
   * <p>Looks in the two standard Windows install locations.
   */
  private static void tryLoadedFont() {
    System.out.println();
    System.out.println("== Step 7: Font.createFont() workaround ==");
    Path[] candidates = {
        Paths.get(System.getProperty("user.home"),
            "AppData", "Local", "Microsoft", "Windows", "Fonts", "NotoColorEmoji_WindowsCompatible.ttf"),
        Paths.get("C:", "Windows", "Fonts", "NotoColorEmoji_WindowsCompatible.ttf"),
        Paths.get("C:", "Windows", "Fonts", "NotoColorEmoji.ttf"),
    };
    Font loaded = null;
    for (Path p : candidates) {
      if (!Files.isRegularFile(p)) continue;
      try {
        loaded = Font.createFont(Font.TRUETYPE_FONT, p.toFile()).deriveFont(24f);
        System.out.println("  loaded from: " + p);
        System.out.println("  font.getFamily() = " + loaded.getFamily());
        System.out.println("  font.getFontName() = " + loaded.getFontName());
        break;
      } catch (Exception e) {
        System.out.println("  failed to load " + p + ": " + e.getMessage());
      }
    }
    if (loaded == null) {
      System.out.println("  no Noto Color Emoji file found in standard locations.");
      return;
    }
    GraphicsEnvironment.getLocalGraphicsEnvironment().registerFont(loaded);
    Graphics2D g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).createGraphics();
    try {
      drawAndCount(g, loaded.getFamily(), "loaded font, drawString AA ON", loaded.getFamily(), Map.of(
          RenderingHints.KEY_TEXT_ANTIALIASING,  RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
          RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON), true);
      drawAndCount(g, loaded.getFamily(), "loaded font, drawChars AA OFF", loaded.getFamily(), Map.of(
          RenderingHints.KEY_TEXT_ANTIALIASING,  RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
          RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF), false);
    } finally {
      g.dispose();
    }
  }
}