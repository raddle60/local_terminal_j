package local.term;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.font.TextAttribute;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Font helpers.
 *
 * - {@link #isMonospaced(String)} — probes glyph metrics to detect fixed-width.
 * - {@link #hasCjkGlyphs(String)} — probes whether a font has Chinese/Japanese
 *   glyphs (so we know whether to suggest a CJK-capable font as a substitute).
 * - {@link #hasEmojiGlyphs(String)} — probes whether a font can render emoji.
 * - {@link #hasGeneralSymbolGlyphs(String)} — probes whether a font can render
 *   general Unicode symbols (box-drawing, arrows, math, etc.).
 * - {@link #findCjkFont(int)} — picks a system CJK font to use as fallback.
 * - {@link #findEmojiFont(int)} — picks a system emoji font.
 * - {@link #findGeneralSymbolFont(int)} — picks a system symbol font.
 * - {@link #createCompositeFont(Font, Font)} — currently a no-op pass-through;
 *   retained as the extension point for true composite-font construction if a
 *   reliable JDK-17-safe path is found.
 */
public final class FontUtils {
  private static final Logger LOG = LoggerFactory.getLogger(FontUtils.class);

  private FontUtils() {}

  /** ASCII chars known to differ in width on proportional fonts. */
  private static final char[] MONOSPACE_PROBE = {'i', 'M', 'W', 'a', '.', ':', '0'};

  /**
   * Chinese characters used to detect whether a font has CJK coverage.
   * Kept Chinese-only on purpose: {@link Font#canDisplayUpTo} returns -1 only
   * when the font has every character in the probe, so mixing in Japanese
   * kana or Korean Hangul makes fonts that only ship Chinese (e.g. Microsoft
   * YaHei) look like they have no CJK at all.
   *
   * <p>A handful of common Simplified-Chinese glyphs spanning common and
   * less-common code points (广, 繁) so a font with only the basic
   * Simplified-Chinese subset is also caught.
   */
  private static final String CJK_PROBE = "中文你好世界繁简体";

  /**
   * A single common CJK ideograph used to measure the double-width advance
   * of a candidate CJK fallback font (see {@link #cjkAdvance(int, String)}).
   * Every CJK-capable font ships this glyph, so it is a reliable metric
   * probe regardless of which CJK block the terminal output happens to use.
   */
  private static final char CJK_WIDTH_PROBE = '界';

  /**
   * The maximum extra advance (in whole pixels) we will add to a CJK fallback
   * when matching it to the primary font's cell grid (see
   * {@link #fitCjkToGrid}). Matching a proportional CJK fallback (e.g.
   * Microsoft YaHei UI, 14px ideograph) to a narrower Latin mono primary
   * (e.g. Consolas / JetBrains Mono at 8px cell width) needs only +2px. A need
   * for more than this many pixels almost certainly signals a measurement
   * anomaly, so we refuse to pad and leave the font as-is.
   */
  private static final int MAX_CJK_EXTRA_PX = 6;

  /**
   * The maximum vertical shift (in whole pixels) we will apply to a CJK
   * fallback to align its baseline with the primary font. The difference
   * between two well-formed fonts' descents is 0 or 1px at terminal sizes, so
   * a need for more than a few pixels means the fonts are grossly mismatched
   * and we leave the fallback as-is rather than mis-place it.
   */
  private static final int MAX_CJK_VERTICAL_SHIFT = 4;

  /**
   * The largest ascent mismatch (in whole pixels) we tolerate before skipping
   * the vertical baseline alignment. Descent alignment is only meaningful when
   * the CJK fallback and the primary have comparable em-box ascents — a legacy
   * CJK font such as SimSun / KaiTi has ascent 12 while a modern mono primary
   * has ascent 15, so forcing their descents to match would float the Chinese
   * glyph ~3px too high. When the ascent gap exceeds this bound we leave the
   * vertical axis untouched (horizontal advance padding is unaffected).
   */
  private static final int MAX_CJK_ASCENT_GAP = 2;

  /**
   * Emoji probe — covers simple emoji, modifier sequences (skin tone), and
   * ZWJ sequences (family emoji). Kept as plain BMP/SMP code points so the
   * probe itself is platform-independent; we rely on
   * {@link Font#canDisplayUpTo} to tell us whether the font ships the
   * corresponding color glyphs.
   */
  private static final String EMOJI_PROBE = "😀🚀✨❤️⭐";

  /**
   * General-symbol probe — box-drawing, arrows, geometric shapes, math
   * symbols. Things Claude Code or generic CLI output routinely prints and
   * that Microsoft YaHei / Consolas typically lack.
   */
  private static final String SYMBOL_PROBE = "┌─┐│└┘←→↑↓π∑∞±≠";

  /** System CJK fonts to try, ordered by preference. */
  private static final String[] CJK_FONT_CANDIDATES = {
      // CJK monospace (best — keeps columns aligned)
      "Microsoft YaHei Mono", "Sarasa Mono SC", "Sarasa Mono TC",
      "Cascadia Mono", "Cascadia Code",
      // CJK proportional (always present on Chinese Windows)
      "Microsoft YaHei UI", "Microsoft YaHei",
      "PingFang SC", "Hiragino Sans GB",
      // Last-resort logical fonts that ship with Java and have CJK fallback
      "Dialog", "SansSerif"
  };

  /**
   * System color-emoji fonts to try, ordered by preference. Color-emoji
   * fonts ship on most desktop OS installs but are platform-specific;
   * {@link #hasEmojiGlyphs(String)} silently skips missing ones.
   */
  private static final String[] EMOJI_FONT_CANDIDATES = {
      // Windows 8+
      "Segoe UI Emoji",
      // macOS
      "Apple Color Emoji",
      // Linux / cross-platform
      "Noto Color Emoji", "Noto Emoji",
      // Last-resort: many modern CJK fonts (YaHei UI, Sarasa) include
      // a small set of monochrome emoji. Useful as a last-resort that
      // renders SOMETHING instead of tofu when no color-emoji font is
      // installed.
      "Segoe UI Symbol", "Symbola", "DejaVu Sans"
  };

  /**
   * System general-symbol fonts to try. These cover box-drawing chars,
   * arrows, geometric shapes, math symbols — the kind of thing Claude
   * Code and most CLI tools emit that is neither Latin nor CJK nor emoji.
   */
  private static final String[] SYMBOL_FONT_CANDIDATES = {
      // Windows
      "Segoe UI Symbol",
      // Cross-platform fallback fonts (Symbola is the de-facto "I have
      // every Unicode glyph" font; DejaVu Sans has broad coverage on
      // Linux).
      "Symbola", "DejaVu Sans", "DejaVu Sans Condensed",
      // Last-resort: Java's logical fonts always exist and have Unicode
      // fallback built into the runtime.
      "Dialog", "SansSerif"
  };

  public static boolean isMonospaced(String family) {
    if (family == null) return false;
    Set<String> available = availableFamilies();
    if (!available.contains(family)) return false;

    Font font = new Font(family, Font.PLAIN, 12);
    Graphics2D g = scratchGraphics();
    try {
      FontMetrics fm = g.getFontMetrics(font);
      int first = fm.charWidth(MONOSPACE_PROBE[0]);
      for (int i = 1; i < MONOSPACE_PROBE.length; i++) {
        if (fm.charWidth(MONOSPACE_PROBE[i]) != first) return false;
      }
      return true;
    } finally {
      g.dispose();
    }
  }

  /**
   * True if the named font can render all probe CJK characters
   * ({@code canDisplayUpTo} returns -1). Reliable across platforms.
   */
  public static boolean hasCjkGlyphs(String family) {
    if (family == null) return false;
    if (!availableFamilies().contains(family)) return false;
    Font font = new Font(family, Font.PLAIN, 12);
    return font.canDisplayUpTo(CJK_PROBE) == -1;
  }

  /**
   * Find a font that BOTH is installed, has CJK glyphs, AND is monospaced.
   *
   * <p>Returns {@code null} when no monospace CJK-capable font is installed.
   * Callers (the Settings dialog, the font picker dropdown) treat null as
   * "user must install Microsoft YaHei Mono / Sarasa Mono SC / Cascadia
   * Code 2404+ before picking a terminal font".
   *
   * <p>Returns null (never a proportional fallback) because substituting a
   * Latin-only font like Consolas with a proportional CJK font like
   * Microsoft YaHei UI produces visibly uneven spacing in a fixed-width
   * terminal grid — exactly what the user complained about. Better to
   * surface "no qualified font" than to silently degrade.
   */
  public static Font findCjkFont(int size) {
    Set<String> have = availableFamilies();
    for (String name : CJK_FONT_CANDIDATES) {
      if (have.contains(name) && hasCjkGlyphs(name) && isMonospaced(name)) {
        return new Font(name, Font.PLAIN, size);
      }
    }
    return null;
  }

  /**
   * Find any installed CJK-capable font, ignoring the monospaced requirement.
   *
   * <p>Used by callers that just need to <em>render</em> CJK characters —
   * notably the Settings dialog preview label — where cell-grid alignment
   * isn't a concern. Preferring a monospaced CJK font keeps preview-text
   * alignment tidy (the sample label is easier to scan when each glyph sits
   * in a fixed-width column), so we still try mono first via
   * {@link #findCjkFont}; only fall through to proportional candidates when
   * that returns {@code null}.
   *
   * <p>Returns {@code null} only when no font on the system has CJK glyphs,
   * which on a real Chinese-localised install is essentially impossible —
   * Java's logical fonts ({@code Dialog}, {@code SansSerif}) have CJK
   * fallback even on minimal containers.
   *
   * <p>Do NOT use this for the terminal pane: per-run substitution with a
   * proportional fallback breaks grid alignment. Use {@link #findCjkFont}
   * for that and surface a clear "no qualified font, install …" message
   * instead.
   */
  public static Font findAnyCjkFont(int size) {
    Font mono = findCjkFont(size);
    if (mono != null) return mono;
    Set<String> have = availableFamilies();
    for (String name : CJK_FONT_CANDIDATES) {
      if (have.contains(name) && hasCjkGlyphs(name)) {
        return new Font(name, Font.PLAIN, size);
      }
    }
    return null;
  }

  /**
   * Resolves the CJK fallback for the <em>terminal pane</em> at runtime.
   * Prefers a monospaced CJK font (kept by {@link #findCjkFont}); falls back
   * to <em>any</em> installed CJK-capable font ({@link #findAnyCjkFont})
   * when no mono option exists.
   *
   * <p>The proportional fallback exists because the user reported tofu in the
   * live terminal — the strict "mono CJK or nothing" policy left the renderer
   * with nothing to draw non-ASCII characters when Consolas was the chosen
   * font and no qualified mono CJK font was installed. The trade-off:
   * rendering CJK at all beats tofu. Yes, a proportional font per-run
   * produces slightly uneven column widths in a fixed-cell grid, and the
   * Settings dialog explains this in the warning text. The threshold for
   * "needs fallback" is {@code > 0x7F} (see
   * {@link CompositeFontPanel#runNeedsCjk}), so pure-ASCII lines still use
   * the primary mono font and stay aligned.
   *
   * <p>Returns {@code null} only on a system where no font installed has
   * CJK glyphs — vanishingly rare in practice.
   */
  public static Font findTerminalCjkFallback(int size) {
    Font mono = findCjkFont(size);
    if (mono != null) return mono;
    return findAnyCjkFont(size);
  }

  /**
   * Fit {@code cjk} to the primary font's terminal grid in two independent
   * axes, without deforming the glyph outline:
   *
   * <ol>
   *   <li><b>Horizontal</b> — pad the advance so a CJK ideograph occupies
   *       exactly {@code 2 * cellWidth}, defeating the "centre a Unicode
   *       symbol" nudge that JediTerm's {@code drawChars} applies to any run
   *       narrower than its cell allocation (the originally-reported selection
   *       offset). The advance padding is added via {@link TextAttribute#TRACKING}
   *       (trailing blank space only), and a matching rightward
   *       {@link java.awt.geom.AffineTransform} translation re-centres the glyph
   *       in its two-cell box so the leading gap before a CJK char (e.g.
   *       {@code "abc中"}) is preserved instead of collapsed by left-alignment.</li>
   *   <li><b>Vertical</b> — shift the baseline so the CJK fallback's descent
   *       equals the primary font's descent, fixing the pre-existing "Chinese
   *       sits a hair lower than English on the same line" misalignment.
   *       JediTerm positions each run's baseline at
   *       {@code rowBottom - font.descent}, so two fonts with different
   *       descents (JetBrains Mono descent 5 vs Microsoft YaHei UI descent 4)
   *       land on different baselines.</li>
   * </ol>
   *
   * <p>The horizontal correction uses {@link TextAttribute#TRACKING} (advance-only
   * — adds blank space) combined with a rightward translate, and the vertical
   * correction a vertical {@link java.awt.geom.AffineTransform} translation (shifts
   * the whole glyph). Neither distorts the outline the way a horizontal affine
   * <em>scale</em> would. The corrections are skipped when the font
   * already matches the grid, so a true monospaced CJK font (which
   * {@link #findCjkFont} would normally have returned first) passes through
   * unchanged.
   *
   * <p>Returns {@code cjk} unchanged when it is null, has no CJK glyphs, or
   * the required correction is out of {@link #MAX_CJK_EXTRA_PX} /
   * {@link #MAX_CJK_VERTICAL_SHIFT} bounds. Visible for testing.
   */
  static Font fitCjkToGrid(Font cjk, int size, int cellWidth, int primaryDescent, int primaryAscent) {
    if (cjk == null || cellWidth <= 0 || primaryDescent < 0 || primaryAscent < 0) return cjk;
    if (cjk.canDisplayUpTo(CJK_PROBE) != -1) return cjk; // no CJK coverage
    int advance = cjkAdvance(size, cjk.getFamily());
    if (advance <= 0) return cjk;
    int cjkDescent = descentOf(size, cjk.getFamily());
    int cjkAscent = ascentOf(size, cjk.getFamily());
    if (cjkDescent < 0 || cjkAscent < 0) return cjk;

    int extra = 2 * cellWidth - advance;           // horizontal pad needed (px)
    int shift = primaryDescent - cjkDescent;       // +ve => shift glyph up

    boolean padH = Math.abs(extra) > 1 && extra >= 0 && extra <= MAX_CJK_EXTRA_PX;
    // Vertical alignment is only valid when the two fonts have comparable
    // em-box heights; otherwise forcing the descents to match floats/drops the
    // whole glyph. A legacy CJK font (SimSun/KaiTi, ascent 12) paired with a
    // modern mono primary (ascent 15) would drift ~3px, so we skip it there.
    boolean padV = Math.abs(shift) > 0
        && Math.abs(shift) <= MAX_CJK_VERTICAL_SHIFT
        && Math.abs(cjkAscent - primaryAscent) <= MAX_CJK_ASCENT_GAP;

    if (!padH && !padV) return cjk; // nothing to correct

    Map<TextAttribute, Object> attrs = new HashMap<>(cjk.getAttributes());
    double tx = 0;
    if (padH) {
      // TRACKING adds blank space in em units: a CJK ideograph's advance is
      // exactly 1 em == the point size (full-width glyphs are 1 em wide by
      // definition), so the fraction that adds `extra` pixels is extra/size.
      // This pads the advance to 2*cellWidth so JediTerm's drawChars no longer
      // centres the run (emptySpace becomes 0) and the selection highlight
      // aligns. But TRACKING pads only the trailing side, which would leave the
      // glyph left-aligned in its two cells and crowd the Latin text that
      // precedes it ("abc中" would touch). A matching rightward translate
      // re-centres the glyph — same visual position as JediTerm's old
      // emptySpace/2 centring, but carried by the font so it applies
      // consistently in the selection re-draw path too.
      attrs.put(TextAttribute.TRACKING, (float) extra / size);
      tx = extra / 2.0;
    }
    double ty = 0;
    if (padV) {
      // Negative y translates the glyph upward (toward the primary baseline).
      ty = -shift;
    }
    if (tx != 0 || ty != 0) {
      attrs.put(TextAttribute.TRANSFORM,
          java.awt.geom.AffineTransform.getTranslateInstance(tx, ty));
    }
    return cjk.deriveFont(attrs);
  }

  /**
   * Measure the ideograph advance (in whole pixels) of the named family at
   * {@code size}. Returns {@code 0} when the family is not installed or lacks
   * the {@link #CJK_WIDTH_PROBE} glyph, so callers can skip scaling.
   */
  static int cjkAdvance(int size, String family) {
    if (family == null) return 0;
    if (!availableFamilies().contains(family)) return 0;
    Font font = new Font(family, Font.PLAIN, size);
    if (!font.canDisplay(CJK_WIDTH_PROBE)) return 0;
    Graphics2D g = scratchGraphics();
    try {
      return g.getFontMetrics(font).charWidth(CJK_WIDTH_PROBE);
    } finally {
      g.dispose();
    }
  }

  /**
   * Measure a family's {@code FontMetrics.getDescent()} at {@code size} — the
   * value JediTerm subtracts from the row bottom to place the baseline. Returns
   * {@code -1} when the family is not installed, signalling callers to skip any
   * vertical adjustment.
   */
  static int descentOf(int size, String family) {
    if (family == null) return -1;
    if (!availableFamilies().contains(family)) return -1;
    Graphics2D g = scratchGraphics();
    try {
      return g.getFontMetrics(new Font(family, Font.PLAIN, size)).getDescent();
    } finally {
      g.dispose();
    }
  }

  /**
   * Measure a family's {@code FontMetrics.getAscent()} at {@code size}. Returns
   * {@code -1} when the family is not installed. Used to gate vertical baseline
   * alignment: two fonts with very different em-box ascents (a legacy CJK font
   * vs a modern mono primary) cannot be baseline-aligned by descent alone.
   */
  static int ascentOf(int size, String family) {
    if (family == null) return -1;
    if (!availableFamilies().contains(family)) return -1;
    Graphics2D g = scratchGraphics();
    try {
      return g.getFontMetrics(new Font(family, Font.PLAIN, size)).getAscent();
    } finally {
      g.dispose();
    }
  }

  /**
   * Measure a family's single Latin cell width at {@code size}, mirroring
   * JediTerm's {@code establishFontMetrics} which uses {@code charWidth('W')}.
   * Returns {@code 0} when the family is not installed (or has no ASCII
   * glyphs, which never happens for a real terminal font), signalling callers
   * to skip any metric-matching.
   */
  static int cellWidth(int size, String family) {
    if (family == null) return 0;
    if (!availableFamilies().contains(family)) return 0;
    Graphics2D g = scratchGraphics();
    try {
      return g.getFontMetrics(new Font(family, Font.PLAIN, size)).charWidth('W');
    } finally {
      g.dispose();
    }
  }

  /**
   * True when the named font can render all probe emoji characters
   * ({@link #EMOJI_PROBE}). False on platforms where the font isn't
   * installed or doesn't ship emoji glyphs (e.g. a Latin-only Consolas
   * variant).
   *
   * <p>{@link Font#canDisplayUpTo} returning -1 means every char in the
   * probe is displayable — i.e. the font really has the emoji glyphs.
   */
  public static boolean hasEmojiGlyphs(String family) {
    if (family == null) return false;
    if (!availableFamilies().contains(family)) return false;
    Font font = new Font(family, Font.PLAIN, 12);
    return font.canDisplayUpTo(EMOJI_PROBE) == -1;
  }

  /**
   * True when the named font can render all probe general-symbol characters
   * ({@link #SYMBOL_PROBE}): box-drawing, arrows, geometric shapes, math.
   * Used to detect fonts that can render Unicode-symbol CLI output that
   * neither Latin nor CJK fonts typically cover.
   */
  public static boolean hasGeneralSymbolGlyphs(String family) {
    if (family == null) return false;
    if (!availableFamilies().contains(family)) return false;
    Font font = new Font(family, Font.PLAIN, 12);
    return font.canDisplayUpTo(SYMBOL_PROBE) == -1;
  }

  /**
   * Find an installed font that ships the {@link #EMOJI_PROBE} emoji
   * glyphs. Returns {@code null} when no emoji-capable font is installed
   * (rare on Windows / macOS, possible on minimal Linux containers).
   *
   * <p>Color-emoji fonts are platform-specific:
   * <ul>
   *   <li>Windows — Segoe UI Emoji (color glyphs).</li>
   *   <li>macOS — Apple Color Emoji.</li>
   *   <li>Linux — Noto Color Emoji (Debian / Ubuntu) or Noto Emoji
   *       (Fedora / older).</li>
   * </ul>
   * Java's logical fonts ({@code Dialog}, {@code SansSerif}) generally
   * do NOT ship color emoji, so they're intentionally not in
   * {@link #EMOJI_FONT_CANDIDATES}. If you need color emoji on Linux,
   * install Noto Color Emoji.
   */
  public static Font findEmojiFont(int size) {
    Set<String> have = availableFamilies();
    for (String name : EMOJI_FONT_CANDIDATES) {
      if (have.contains(name) && hasEmojiGlyphs(name)) {
        return new Font(name, Font.PLAIN, size);
      }
    }
    return null;
  }

  /**
   * Find an installed font that ships the {@link #SYMBOL_PROBE} general
   * symbols. Returns {@code null} when no symbol-capable font is installed
   * (very rare; Java's logical fonts ship broad Unicode coverage and are
   * tried as last-resort).
   *
   * <p>Used as a fallback for non-ASCII runs that aren't CJK and aren't
   * emoji: box-drawing chars (┌─┐), arrows (←→↑↓), math symbols (π∑),
   * geometric shapes (●■◆). Claude Code and most CLI tools emit these
   * freely and a Latin-only or CJK-only font will render them as tofu.
   */
  public static Font findGeneralSymbolFont(int size) {
    Set<String> have = availableFamilies();
    for (String name : SYMBOL_FONT_CANDIDATES) {
      if (have.contains(name) && hasGeneralSymbolGlyphs(name)) {
        return new Font(name, Font.PLAIN, size);
      }
    }
    return null;
  }

  /**
   * Combine {@code primary} (used for Latin) with {@code fallback} (used for
   * everything else) into a single Font. Enables the terminal pane to render
   * CJK characters via the fallback font even when the user picks a Latin-only
   * font like Consolas.
   *
   * <p>Construction would require {@code sun.font.CompositeFont}, an internal
   * class that does not extend {@code java.awt.Font} on JDK 17+ (it extends
   * {@code Font2D}), so the constructed object cannot be returned as a Font
   * without a cast the module system blocks. Rather than dance around the
   * reflection, we return the {@code primary} font and rely on Java2D's
   * native glyph-level font fallback at draw time — Windows font linking
   * substitutes CJK glyphs on the fly when {@code Graphics2D.drawChars}
   * encounters a missing glyph.
   *
   * <p>This means the user's chosen font is used verbatim; if it really is
   * missing a CJK glyph, the OS finds a substitute (typically SimSun on
   * Chinese Windows). If that doesn't render the way the user expects, the
   * Settings dialog's "preview" line shows the actual rendering for any font.
   *
   * @return always {@code primary}; method is retained for API stability.
   */
  public static Font createCompositeFont(Font primary, Font fallback) {
    if (primary == null) return fallback;
    if (fallback == null) return primary;
    LOG.debug("createCompositeFont: returning primary={}, fallback={} (relying on OS font linking)",
        primary.getFamily(), fallback.getFamily());
    return primary;
  }

  /**
   * Resolve a terminal font. The terminal pane renders text in fixed-width
   * cells, so the picked family goes through three substitutions in order:
   *
   * <ol>
   *   <li><b>No CJK glyphs</b> — pick a CJK-capable font ({@link #findCjkFont}).
   *       Required so non-ASCII text renders at all on a Latin-only pick like
   *       Consolas / Courier New.</li>
   *   <li><b>CJK glyphs but proportional</b> — also substitute. Microsoft
   *       YaHei, PingFang SC, etc. ship CJK at full width and Latin at
   *       proportional width, which in a fixed-cell terminal looks like wide
   *       uneven gaps between Chinese and Latin characters. Pick a
   *       monospace CJK font ({@link #findCjkFont}, which prefers
   *       Microsoft YaHei Mono / Sarasa Mono / Cascadia Mono).</li>
   *   <li><b>Otherwise</b> — keep the picked font verbatim.</li>
   * </ol>
   *
   * <p>The result is always a single {@link Font} (composite-font
   * construction via {@code sun.font} is fragile on JDK 17; per-run font
   * selection is handled at the paint layer in
   * {@link CompositeFontPanel}).
   *
   * <p>Callers can compare {@link Font#getFamily} of the result against the
   * input to detect substitution.
   */
  public static Font resolveTerminalFont(String family, int size) {
    if (family == null) family = Font.MONOSPACED;
    Font chosen = new Font(family, Font.PLAIN, size);
    if (!hasCjkGlyphs(family) || isProportionalCjkFamily(family)) {
      Font substitute = findCjkFont(size);
      if (substitute != null && !substitute.getFamily().equalsIgnoreCase(family)) {
        return substitute;
      }
    }
    return chosen;
  }

  /**
   * True when {@code family} has CJK glyphs but isn't monospaced — i.e.
   * picking it for a terminal will produce visibly uneven CJK-vs-Latin
   * spacing. Used by {@link #resolveTerminalFont} to decide whether to
   * pick a monospace CJK substitute.
   *
   * <p>Families that lack CJK glyphs return {@code false} (handled by the
   * "no CJK" branch instead). Mono CJK families (YaHei Mono, Sarasa Mono,
   * Cascadia Mono) return {@code false}.
   */
  public static boolean isProportionalCjkFamily(String family) {
    if (family == null) return false;
    if (!hasCjkGlyphs(family)) return false;
    // isMonospaced returns false when the family isn't installed; combined
    // with the hasCjkGlyphs check above, that means "not installed" — treat
    // as not proportional (it'll fall back via findCjkFont for other reasons).
    return !isMonospaced(family);
  }

  /**
   * True when {@code resolveTerminalFont} would substitute the requested
   * family (because it lacks CJK glyphs, or because it's CJK-proportional
   * and looks bad in a terminal). Callers can use this to decide whether
   * to show the user a warning.
   */
  public static boolean wouldSubstitute(String family) {
    if (family == null) return false;
    if (!hasCjkGlyphs(family)) return true;
    return isProportionalCjkFamily(family);
  }

  private static Set<String> availableFamilies() {
    return new HashSet<>(Arrays.asList(
        GraphicsEnvironment.getLocalGraphicsEnvironment()
            .getAvailableFontFamilyNames()));
  }

  // ---- Per-category family listings for the Settings dialog ----
  //
  // Each returns installed families that can render the given category,
  // preference candidates floated to the top and the rest sorted
  // alphabetically. Results are cached because probing every installed
  // family with canDisplayUpTo loads each font (a few hundred fonts × a
  // short probe), which is wasteful to repeat every time the dialog opens.

  private static final java.util.Map<String, List<String>> CATEGORY_CACHE =
      new java.util.concurrent.ConcurrentHashMap<>();

  /**
   * Monospaced families — for the primary/Latin terminal font. Monospace is
   * required so ASCII cells stay a fixed width.
   */
  public static List<String> monospaceFamilies() {
    return CATEGORY_CACHE.computeIfAbsent("mono",
        k -> familiesMatching(FontUtils::isMonospaced, MONO_PREFS));
  }

  /**
   * CJK-capable families — for the Chinese/Japanese/Korean fallback slot.
   * Monospace CJK candidates are floated to the top so double-width glyphs
   * land on exact 2-cell boundaries.
   */
  public static List<String> cjkFamilies() {
    return CATEGORY_CACHE.computeIfAbsent("cjk",
        k -> familiesMatching(FontUtils::hasCjkGlyphs, CJK_FONT_CANDIDATES));
  }

  /**
   * Families that render the general-symbol probe (box-drawing, arrows,
   * math, dingbats like ✔ ✗) — for the symbol fallback slot. Text-presentation
   * symbol fonts are preferred here; they render these glyphs close to one
   * cell wide instead of the ~2.4-cell color-emoji rendering.
   */
  public static List<String> symbolFamilies() {
    return CATEGORY_CACHE.computeIfAbsent("symbol",
        k -> familiesMatching(FontUtils::hasGeneralSymbolGlyphs, SYMBOL_FONT_CANDIDATES));
  }

  /** Families that render the emoji probe — for the emoji fallback slot. */
  public static List<String> emojiFamilies() {
    return CATEGORY_CACHE.computeIfAbsent("emoji",
        k -> familiesMatching(FontUtils::hasEmojiGlyphs, EMOJI_FONT_CANDIDATES));
  }

  /** Preferred monospace families for the primary-font picker. */
  private static final String[] MONO_PREFS = {
      "Microsoft YaHei Mono", "Sarasa Mono SC", "Sarasa Mono TC",
      "Cascadia Mono", "Cascadia Code", "JetBrains Mono", "Source Code Pro",
      "Consolas", "Courier New"
  };

  /**
   * List installed families satisfying {@code predicate}, with {@code prefs}
   * (those that are installed and match) floated to the top in the given
   * order, and the remaining matches sorted case-insensitively.
   */
  private static List<String> familiesMatching(java.util.function.Predicate<String> predicate,
                                               String[] prefs) {
    Set<String> available = availableFamilies();
    java.util.List<String> matches = new java.util.ArrayList<>();
    for (String f : available) {
      if (predicate.test(f)) matches.add(f);
    }
    java.util.List<String> ordered = new java.util.ArrayList<>();
    for (String p : prefs) {
      if (matches.contains(p) && !ordered.contains(p)) ordered.add(p);
    }
    matches.sort(String.CASE_INSENSITIVE_ORDER);
    for (String f : matches) {
      if (!ordered.contains(f)) ordered.add(f);
    }
    return java.util.Collections.unmodifiableList(ordered);
  }

  private static Graphics2D scratchGraphics() {
    BufferedImage img = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
    return img.createGraphics();
  }
}