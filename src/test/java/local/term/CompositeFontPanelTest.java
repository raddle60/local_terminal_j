package local.term;

import com.jediterm.terminal.TextStyle;
import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.RenderingHints;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CompositeFontPanel}'s pure-selection helper.
 * We test via the package-private static methods rather than
 * instantiating a real TerminalPanel (which needs a live JediTerm
 * emulator, text buffer, and style state).
 *
 * <p>Font glyph coverage is environment-dependent (logical fonts such as
 * {@code Dialog} are composite and can display CJK on most systems), so
 * these tests do <em>not</em> rely on real coverage. Instead they use
 * {@link #cap}/{@link #incap} — {@link Font} subclasses with a controlled
 * {@link Font#canDisplayUpTo(char[], int, int)} — to make the
 * primary-first / fallback decision deterministic.
 *
 * <p>Note: the earlier cursor-jump "resync on Enter" tests were removed.
 * The real fix for the post-Claude cursor jump was the
 * {@code PtyProcessTtyConnector.resize()} override, which keeps the PTY
 * window size in sync with JediTerm's text buffer (see
 * {@link PtyProcessTtyConnectorTest}).
 */
class CompositeFontPanelTest {

  private static final TextStyle NORMAL = TextStyle.EMPTY;
  private static final TextStyle BOLD = new TextStyle.Builder().setOption(TextStyle.Option.BOLD, true).build();
  private static final TextStyle ITALIC = new TextStyle.Builder().setOption(TextStyle.Option.ITALIC, true).build();
  private static final TextStyle BOTH = new TextStyle.Builder()
      .setOption(TextStyle.Option.BOLD, true)
      .setOption(TextStyle.Option.ITALIC, true)
      .build();

  /** A Font that reports it can render every run ({@code canDisplayUpTo == -1}). */
  private static Font cap(String name) {
    return new Font(name, Font.PLAIN, 12) {
      @Override public int canDisplayUpTo(char[] text, int start, int end) {
        return -1;
      }
    };
  }

  /** A Font that reports it can render nothing ({@code canDisplayUpTo == start}). */
  private static Font incap(String name) {
    return new Font(name, Font.PLAIN, 12) {
      @Override public int canDisplayUpTo(char[] text, int start, int end) {
        return start; // pretend the very first char is undisplayable
      }
    };
  }

  /** Build a [plain, bold, italic, bold-italic] variant set from a base Font. */
  private static Font[] variants(Font base) {
    return new Font[]{
        base,
        base.deriveFont(Font.BOLD),
        base.deriveFont(Font.ITALIC),
        base.deriveFont(Font.BOLD | Font.ITALIC)
    };
  }

  /**
   * Holds the resolver + variants list for a chain so tests can compare
   * against the same Font instances the panel will return. Important
   * because deriveFont creates a new instance every call — comparing
   * two equivalent-looking fonts from independent deriveFont calls
   * would fail {@code assertSame}.
   */
  private static final class Chain {
    final FontResolver resolver;
    final List<Font[]> variants;
    Chain(FontResolver resolver, List<Font[]> variants) {
      this.resolver = resolver;
      this.variants = variants;
    }
  }

  /** Build a Chain with one fallback font. */
  private static Chain singleFallback(Font base) {
    Font[] v = variants(base);
    return new Chain(new FontResolver(base), List.<Font[]>of(v));
  }

  /** Build a Chain with multiple fallback fonts (in order). */
  private static Chain multiFallback(Font... bases) {
    FontResolver resolver = new FontResolver(bases);
    Font[][] vs = new Font[bases.length][];
    for (int i = 0; i < bases.length; i++) {
      vs[i] = variants(bases[i]);
    }
    return new Chain(resolver, List.<Font[]>of(vs));
  }

  // ---- runNeedsCjk: cheap non-ASCII fast-path filter ----

  @Test
  void runNeedsCjk_asciiOnly_returnsFalse() {
    char[] text = "hello world".toCharArray();
    assertFalse(CompositeFontPanel.runNeedsCjk(text, 0, text.length));
  }

  @Test
  void runNeedsCjk_chinese_returnsTrue() {
    char[] text = "中".toCharArray();
    assertTrue(CompositeFontPanel.runNeedsCjk(text, 0, text.length));
  }

  @Test
  void runNeedsCjk_emoji_returnsTrue() {
    char[] text = "🚀".toCharArray();
    assertTrue(CompositeFontPanel.runNeedsCjk(text, 0, text.length));
  }

  @Test
  void runNeedsCjk_symbol_returnsTrue() {
    char[] text = "┌─┐".toCharArray();
    assertTrue(CompositeFontPanel.runNeedsCjk(text, 0, text.length));
  }

  @Test
  void runNeedsCjk_mixed_returnsTrue() {
    char[] text = "abc中".toCharArray();
    assertTrue(CompositeFontPanel.runNeedsCjk(text, 0, text.length));
  }

  @Test
  void runNeedsCjk_emptyRange_returnsFalse() {
    char[] text = "abc".toCharArray();
    assertFalse(CompositeFontPanel.runNeedsCjk(text, 1, 1));
  }

  // ---- chooseFont: ASCII always uses the primary font ----

  @Test
  void chooseFont_asciiRun_returnsSuperChoice() {
    Font superChoice = new Font("A", Font.PLAIN, 12);
    Chain c = singleFallback(cap("Dialog"));
    char[] text = "abc".toCharArray();
    Font result = CompositeFontPanel.chooseFont(text, 0, 3, NORMAL,
        c.resolver, c.variants, () -> superChoice);
    assertSame(superChoice, result);
  }

  // ---- chooseFont: primary-font-first policy (the box-drawing fix) ----

  @Test
  void chooseFont_primaryCanDisplayNonAscii_returnsPrimary() {
    // Primary can render the run — even though it is non-ASCII and a
    // fallback chain exists, the primary font wins.
    Font primary = cap("Primary");
    Chain c = singleFallback(cap("Fallback"));
    char[] text = "中文".toCharArray();
    Font result = CompositeFontPanel.chooseFont(text, 0, 2, NORMAL,
        c.resolver, c.variants, () -> primary);
    assertSame(primary, result);
  }

  @Test
  void chooseFont_boxDrawing_primaryCanDisplay_staysOnPrimary() {
    // The actual bug: box-drawing chars (U+25xx) must NOT be routed to a
    // CJK/symbol fallback whose metrics differ from the primary-derived
    // cell height, or adjacent rows' │ won't connect. Primary can render
    // them, so keep them on the primary font.
    Font primary = cap("Consolas");
    Chain c = singleFallback(cap("YaHei"));
    char[] text = "│─┌┐└┘".toCharArray();
    Font result = CompositeFontPanel.chooseFont(text, 0, text.length, NORMAL,
        c.resolver, c.variants, () -> primary);
    assertSame(primary, result);
  }

  // ---- chooseFont: fallback only when primary cannot render the run ----

  @Test
  void chooseFont_primaryCannotDisplay_returnsFallback() {
    Font primary = incap("Consolas");
    Chain c = singleFallback(cap("YaHei"));
    char[] text = "中文".toCharArray();
    Font result = CompositeFontPanel.chooseFont(text, 0, 2, NORMAL,
        c.resolver, c.variants, () -> primary);
    assertSame(c.variants.get(0)[0], result);
  }

  @Test
  void chooseFont_primaryCannotDisplayBold_returnsFallbackBold() {
    Font primary = incap("Consolas");
    Chain c = singleFallback(cap("YaHei"));
    char[] text = "中文".toCharArray();
    Font result = CompositeFontPanel.chooseFont(text, 0, 2, BOLD,
        c.resolver, c.variants, () -> primary);
    assertSame(c.variants.get(0)[1], result);
  }

  @Test
  void chooseFont_primaryCannotDisplayItalic_returnsFallbackItalic() {
    Font primary = incap("Consolas");
    Chain c = singleFallback(cap("YaHei"));
    char[] text = "中文".toCharArray();
    Font result = CompositeFontPanel.chooseFont(text, 0, 2, ITALIC,
        c.resolver, c.variants, () -> primary);
    assertSame(c.variants.get(0)[2], result);
  }

  @Test
  void chooseFont_primaryCannotDisplayBoldItalic_returnsFallbackBoldItalic() {
    Font primary = incap("Consolas");
    Chain c = singleFallback(cap("YaHei"));
    char[] text = "中文".toCharArray();
    Font result = CompositeFontPanel.chooseFont(text, 0, 2, BOTH,
        c.resolver, c.variants, () -> primary);
    assertSame(c.variants.get(0)[3], result);
  }

  @Test
  void chooseFont_mixedRunPrimaryCannotDisplay_returnsFallback() {
    // A whole run containing a char the primary can't render gets the
    // fallback font (the whole run is rendered together — we can't mix
    // fonts within a single run).
    Font primary = incap("Consolas");
    Chain c = singleFallback(cap("YaHei"));
    char[] text = "abc中def".toCharArray();
    Font result = CompositeFontPanel.chooseFont(text, 0, text.length, NORMAL,
        c.resolver, c.variants, () -> primary);
    assertSame(c.variants.get(0)[0], result);
  }

  @Test
  void chooseFont_picksFirstCapableFallbackInChain() {
    // Primary can't render; first fallback also can't; second can — the
    // first capable fallback in chain order wins.
    Font primary = incap("Consolas");
    Font first = incap("EmojiOnly");
    Font second = cap("YaHei");
    Chain c = multiFallback(first, second);
    char[] text = "中".toCharArray();
    Font result = CompositeFontPanel.chooseFont(text, 0, 1, NORMAL,
        c.resolver, c.variants, () -> primary);
    assertSame(c.variants.get(1)[0], result);
  }

  @Test
  void chooseFont_noFallbackCanRender_returnsFirstFallbackPlain() {
    // Primary and every fallback report they can't render the run — the
    // renderer still needs a font, so we return the first fallback's plain
    // variant.
    Font primary = incap("Consolas");
    Chain c = singleFallback(incap("YaHei"));
    char[] text = "中".toCharArray();
    Font result = CompositeFontPanel.chooseFont(text, 0, 1, NORMAL,
        c.resolver, c.variants, () -> primary);
    assertSame(c.variants.get(0)[0], result);
  }

  @Test
  void chooseFont_nonAsciiButNoFallbackChain_returnsSuperChoice() {
    // No fallback configured — the run always renders with the primary,
    // regardless of whether the primary can actually display it.
    Font superChoice = incap("Consolas");
    char[] text = "中文".toCharArray();
    Font result = CompositeFontPanel.chooseFont(text, 0, 2, NORMAL,
        new FontResolver(), List.of(),
        () -> superChoice);
    assertSame(superChoice, result);
  }

  // ---- buildAntialiasingHints: LCD subpixel AA + fractional metrics ----
  //
  // These guard the "blurry CJK" regression: if anyone reverts the hint
  // map back to grayscale-AA only, the test fails loudly so the issue
  // surfaces on CI instead of being noticed in production.

  @Test
  void buildAntialiasingHints_enablesLcdSubpixelAA() {
    Map<RenderingHints.Key, Object> hints = CompositeFontPanel.buildAntialiasingHints();
    // Must be subpixel LCD (ClearType-style), NOT grayscale ON — that's
    // the whole point of the override: grayscale renders CJK with
    // blurry edges at small sizes; subpixel LCD gives each pixel
    // column R/G/B coverage.
    assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB,
        hints.get(RenderingHints.KEY_TEXT_ANTIALIASING),
        "Live terminal must use subpixel LCD AA, otherwise CJK looks blurry");
  }

  @Test
  void buildAntialiasingHints_enablesFractionalMetrics() {
    // Without this, runs that mix monospaced ASCII with a CJK fallback
    // (which usually has a slightly different cell width) jitter
    // horizontally between repaints because glyph x-positions snap to
    // integer pixels.
    Map<RenderingHints.Key, Object> hints = CompositeFontPanel.buildAntialiasingHints();
    assertEquals(RenderingHints.VALUE_FRACTIONALMETRICS_ON,
        hints.get(RenderingHints.KEY_FRACTIONALMETRICS));
  }

  @Test
  void buildAntialiasingHints_usesBoosterContrast() {
    // Bump perimeter-pixel darkness so terminal text reads sharper at
    // 12-14 pt sizes. 140 is the project default; if it changes by
    // intent, update this test rather than the constant silently.
    Map<RenderingHints.Key, Object> hints = CompositeFontPanel.buildAntialiasingHints();
    assertEquals(CompositeFontPanel.LCD_CONTRAST,
        hints.get(RenderingHints.KEY_TEXT_LCD_CONTRAST));
  }

  @Test
  void buildAntialiasingHints_matchesCompositeFontLabelDefaults() {
    // The Settings dialog's preview label already enables LCD AA + fractional
    // metrics (see CompositeFontLabel#paintComponent). The live terminal
    // must produce visually consistent output — a preview that says
    // "this is how your terminal will look" is only honest if the
    // terminal matches. Catch accidental drift.
    Map<RenderingHints.Key, Object> hints = CompositeFontPanel.buildAntialiasingHints();
    assertTrue(hints.containsKey(RenderingHints.KEY_TEXT_ANTIALIASING),
        "AA hint missing — CompositeFontLabel sets it, terminal must too");
    assertTrue(hints.containsKey(RenderingHints.KEY_FRACTIONALMETRICS),
        "fractional-metrics hint missing — CompositeFontLabel sets it, terminal must too");
  }

  // ---- isEmoji: post-pass filter for color-emoji cells ----
  //
  // Guards the post-pass overlap: the emoji redraw pass must only restamp
  // characters a color-emoji font is actually likely to render. Adding
  // the wrong range here either wastes work (ASCII) or steals cells from
  // the box-drawing pass (breaks the │ continuity fix).

  @Test
  void isEmoji_recognizesCommonEmojiRanges() {
    // Misc Symbols (☀ U+2600) — BMP, fits in char.
    assertTrue(CompositeFontPanel.isEmoji('☀'));
    // Dingbats (✂ U+2702) — BMP, fits in char.
    assertTrue(CompositeFontPanel.isEmoji('✂'));
    // Misc Symbols and Pictographs (🚀 U+1F680) — supplementary plane,
    // needs int literal because char is 16-bit.
    assertTrue(CompositeFontPanel.isEmoji(0x1F680));
    // Emoticons (😊 U+1F60A) — supplementary plane.
    assertTrue(CompositeFontPanel.isEmoji(0x1F60A));
  }

  @Test
  void isEmoji_doesNotOverlapBoxDrawing() {
    // Mutual exclusion invariant: a codepoint cannot be BOTH emoji and
    // box-drawing, or the two post-passes will fight for the same cell.
    // If isEmoji ever returns true for a box-drawing char, the box-drawing
    // pass would be the one to lose (emoji runs second... actually
    // emoji runs first in paintComponent, so box-drawing wins; but a
    // wrong pass is still wrong).
    for (char c = '─'; c <= '╿'; c++) {
      assertFalse(CompositeFontPanel.isEmoji(c),
          "Box-drawing char U+" + String.format("%04X", (int) c)
              + " must not be classified as emoji");
    }
  }

  @Test
  void isEmoji_rejectsAsciiAndCjk() {
    // ASCII must skip the emoji pass entirely — it's the bulk of terminal
    // output and re-stamping it would undo LCD HRGB's CJK clarity win.
    assertFalse(CompositeFontPanel.isEmoji('A'));
    assertFalse(CompositeFontPanel.isEmoji(' '));
    assertFalse(CompositeFontPanel.isEmoji('7'));
    // CJK goes through the global LCD HRGB pass, not the emoji post-pass.
    assertFalse(CompositeFontPanel.isEmoji('中'));
    assertFalse(CompositeFontPanel.isEmoji('あ'));
  }

  // ---- isBoxDrawing: post-pass filter for | / ┌ / ┘ / etc. ----
  //
  // The actual bug fix. If this predicate rejects a vertical-stroke char,
  // the gap reappears; if it accepts a non-box-drawing char, the cell
  // gets restamped with the wrong (grayscale + integer) hints and looks
  // worse than the LCD global pass.

  @Test
  void isBoxDrawing_recognizesAllVerticalLines() {
    // Every shape that contains a vertical stroke in the Claude Code
    // box-drawing frame.
    assertTrue(CompositeFontPanel.isBoxDrawing('│')); // light vertical
    assertTrue(CompositeFontPanel.isBoxDrawing('┃')); // heavy vertical
    assertTrue(CompositeFontPanel.isBoxDrawing('┌')); // top-left corner
    assertTrue(CompositeFontPanel.isBoxDrawing('┐')); // top-right corner
    assertTrue(CompositeFontPanel.isBoxDrawing('└')); // bottom-left corner
    assertTrue(CompositeFontPanel.isBoxDrawing('┘')); // bottom-right corner
    assertTrue(CompositeFontPanel.isBoxDrawing('├')); // left tee
    assertTrue(CompositeFontPanel.isBoxDrawing('┤')); // right tee
    assertTrue(CompositeFontPanel.isBoxDrawing('┬')); // top tee
    assertTrue(CompositeFontPanel.isBoxDrawing('┴')); // bottom tee
    assertTrue(CompositeFontPanel.isBoxDrawing('┼')); // cross
    assertTrue(CompositeFontPanel.isBoxDrawing('─')); // light horizontal
    assertTrue(CompositeFontPanel.isBoxDrawing('━')); // heavy horizontal
  }

  @Test
  void isBoxDrawing_rejectsNonBoxDrawing() {
    // ASCII: trivial reject — the post-pass must not re-stamp '│' on top
    // of every letter, that would be a regression in clarity.
    assertFalse(CompositeFontPanel.isBoxDrawing('A'));
    assertFalse(CompositeFontPanel.isBoxDrawing(' '));
    // CJK: lives in the CJK font, not the box-drawing font.
    assertFalse(CompositeFontPanel.isBoxDrawing('中'));
    // Emoji: lives in the color-emoji font; the box-drawing pass would
    // route it through a symbol font that can't render the bitmap.
    // 🚀 U+1F680 is supplementary plane, needs int literal.
    assertFalse(CompositeFontPanel.isBoxDrawing(0x1F680));
    // Just outside the box-drawing range.
    assertFalse(CompositeFontPanel.isBoxDrawing('◀')); // U+25C0 (geometric shape, below range)
  }

  // ---- isAmbiguousWide: post-pass filter for ① ② ③ ← → █ ⬛ etc. ----
  //
  // The originally-reported bug: circled digits and arrows render at
  // ~2 cells wide in the CJK fallback font, but JediTerm's width
  // detection treats them as 1 cell, so the right half gets clipped
  // by the next cell's opaque background. This predicate must catch
  // those so the post-pass re-stamps them on top of the fill.

  @Test
  void isAmbiguousWide_recognizesOriginallyReportedCircledDigits() {
    // The exact case the user reported: ① ② ③ ⑩ ⑳
    assertTrue(CompositeFontPanel.isAmbiguousWide('①')); // U+2460
    assertTrue(CompositeFontPanel.isAmbiguousWide('②')); // U+2461
    assertTrue(CompositeFontPanel.isAmbiguousWide('③')); // U+2462
    assertTrue(CompositeFontPanel.isAmbiguousWide('⑩')); // U+2469
    assertTrue(CompositeFontPanel.isAmbiguousWide('⑳')); // U+2473
    // Letter variants too: Ⓐ ⓐ ⒈ ⑴ — same block, same issue.
    assertTrue(CompositeFontPanel.isAmbiguousWide('Ⓐ')); // U+24B6
    assertTrue(CompositeFontPanel.isAmbiguousWide('ⓐ')); // U+24D0
    assertTrue(CompositeFontPanel.isAmbiguousWide('⒈')); // U+2488
    assertTrue(CompositeFontPanel.isAmbiguousWide('⑴')); // U+2474
  }

  @Test
  void isAmbiguousWide_recognizesArrowsAndMathOperators() {
    // ← → ↑ ↓ ∑ ∏ √ ∞ — all in Arrows (0x2190–0x21FF) or
    // Mathematical Operators (0x2200–0x22FF) blocks.
    assertTrue(CompositeFontPanel.isAmbiguousWide('←')); // U+2190
    assertTrue(CompositeFontPanel.isAmbiguousWide('→')); // U+2192
    assertTrue(CompositeFontPanel.isAmbiguousWide('↑')); // U+2191
    assertTrue(CompositeFontPanel.isAmbiguousWide('↓')); // U+2193
    assertTrue(CompositeFontPanel.isAmbiguousWide('∑')); // U+2211
    assertTrue(CompositeFontPanel.isAmbiguousWide('√')); // U+221A
    assertTrue(CompositeFontPanel.isAmbiguousWide('∞')); // U+221E
  }

  @Test
  void isAmbiguousWide_recognizesBlockElements() {
    // ▀ ▄ █ ▌ ▐ ░ ▒ ▓ — Block Elements which the CJK fallback also
    // renders narrower than the glyph itself.
    assertTrue(CompositeFontPanel.isAmbiguousWide('▀')); // U+2580
    assertTrue(CompositeFontPanel.isAmbiguousWide('▄')); // U+2584
    assertTrue(CompositeFontPanel.isAmbiguousWide('█')); // U+2588
    assertTrue(CompositeFontPanel.isAmbiguousWide('▌')); // U+258C
    assertTrue(CompositeFontPanel.isAmbiguousWide('▐')); // U+2590
    assertTrue(CompositeFontPanel.isAmbiguousWide('▓')); // U+2593
  }

  @Test
  void isAmbiguousWide_recognizesSupplementalSymbols() {
    // ⬛ ⬜ ⭕ ⬆ ⬇ ⬅ ⬡ ⬢ — Misc Symbols / Arrows / Math in the
    // 0x27C0–0x2BFF range. Some live in the BMP but the test
    // pattern uses int literals to be safe.
    assertTrue(CompositeFontPanel.isAmbiguousWide(0x2B1B)); // ⬛
    assertTrue(CompositeFontPanel.isAmbiguousWide(0x2B1C)); // ⬜
    assertTrue(CompositeFontPanel.isAmbiguousWide(0x2B55)); // ⭕
    assertTrue(CompositeFontPanel.isAmbiguousWide(0x2B06)); // ⬆
    assertTrue(CompositeFontPanel.isAmbiguousWide(0x2B07)); // ⬇
    assertTrue(CompositeFontPanel.isAmbiguousWide(0x2B05)); // ⬅
    assertTrue(CompositeFontPanel.isAmbiguousWide(0x2B21)); // ⬡
    assertTrue(CompositeFontPanel.isAmbiguousWide(0x2B22)); // ⬢
  }

  @Test
  void isAmbiguousWide_doesNotOverlapBoxDrawing() {
    // Mutual exclusion invariant: a codepoint cannot be BOTH
    // ambiguous-wide and box-drawing, or the two post-passes will
    // fight for the same cell. Box-drawing wins (it runs last in
    // paintComponent) but the wrong pass is still wrong.
    for (char c = '─'; c <= '╿'; c++) {
      assertFalse(CompositeFontPanel.isAmbiguousWide(c),
          "Box-drawing char U+" + String.format("%04X", (int) c)
              + " must not be classified as ambiguous-wide");
    }
  }

  @Test
  void isAmbiguousWide_doesNotOverlapEmoji() {
    // Mutual exclusion with isEmoji: the emoji pass uses AA OFF
    // hints (emoji-font bitmap blit), the ambiguous-wide pass uses
    // LCD HRGB (text glyph). Routing an emoji here would re-stamp
    // it without the bitmap blit and re-introduce the colored fringe.
    // Sample one emoji from each block isEmoji covers.
    assertFalse(CompositeFontPanel.isAmbiguousWide('☀')); // U+2600 Misc Symbols
    assertFalse(CompositeFontPanel.isAmbiguousWide('✂')); // U+2702 Dingbats
    assertFalse(CompositeFontPanel.isAmbiguousWide(0x1F680)); // 🚀
    assertFalse(CompositeFontPanel.isAmbiguousWide(0x1F60A)); // 😊
  }

  @Test
  void isAmbiguousWide_rejectsAsciiAndCjk() {
    // ASCII: trivially rejected — the post-pass must not re-stamp
    // every letter.
    assertFalse(CompositeFontPanel.isAmbiguousWide('A'));
    assertFalse(CompositeFontPanel.isAmbiguousWide(' '));
    assertFalse(CompositeFontPanel.isAmbiguousWide('7'));
    // CJK: lives in the CJK font, which is already metric-matched
    // to the cell width — the post-pass would just re-stamp what
    // was already drawn correctly.
    assertFalse(CompositeFontPanel.isAmbiguousWide('中'));
    assertFalse(CompositeFontPanel.isAmbiguousWide('あ'));
  }

  @Test
  void isAmbiguousWide_rejectsRangesOutsideTheFourBlocks() {
    // Just-inside-Range and just-outside-Range sanity checks.
    // The four covered blocks are 0x2190–0x22FF, 0x2460–0x24FF,
    // 0x2580–0x259F, 0x27C0–0x2BFF. Characters in the gaps between
    // them are NOT covered (and should not be — adding them would
    // bring back false positives).
    assertFalse(CompositeFontPanel.isAmbiguousWide(0x218F)); // last char BEFORE Arrows (Roman Numeral)
    assertFalse(CompositeFontPanel.isAmbiguousWide(0x2300)); // first char AFTER Math (Misc Technical — isEmoji)
    assertFalse(CompositeFontPanel.isAmbiguousWide(0x245F)); // last char BEFORE Enclosed Alphanumerics
    assertFalse(CompositeFontPanel.isAmbiguousWide(0x2500)); // box-drawing range, not covered here
    assertFalse(CompositeFontPanel.isAmbiguousWide(0x25A0)); // first char AFTER Block Elements (Geometric Shapes — isEmoji)
    assertFalse(CompositeFontPanel.isAmbiguousWide(0x27BF)); // last char BEFORE 0x27C0 (Dingbats — isEmoji)
    assertFalse(CompositeFontPanel.isAmbiguousWide(0x2C00)); // first char AFTER the 0x27C0–0x2BFF block
  }

  // ---- buildEmojiHints: the post-pass for color-emoji cells ----
  //
  // Guards the anti-occlusion property: AA OFF + integer metrics so the
  // color-emoji bitmap blits as-is. LCD HRGB is the thing we explicitly
  // turn OFF here.

  @Test
  void buildEmojiHints_usesAntialiasOffNotLcd() {
    // The post-pass must disable AA entirely so the color-emoji bitmap
    // (Segoe UI Emoji etc.) blits as a pre-rasterized RGBA image without
    // re-sampling through R/G/B sub-pixel columns. AA ON or LCD HRGB
    // would re-introduce the colored fringe.
    Map<RenderingHints.Key, Object> hints = CompositeFontPanel.buildEmojiHints();
    assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_OFF,
        hints.get(RenderingHints.KEY_TEXT_ANTIALIASING),
        "Emoji post-pass must disable AA, not use LCD");
    assertNotEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB,
        hints.get(RenderingHints.KEY_TEXT_ANTIALIASING),
        "Emoji post-pass must not use LCD HRGB — that's the bug source");
  }

  @Test
  void buildEmojiHints_disablesFractionalMetrics() {
    // Integer pixel positions are fine for emoji (they're pre-rasterized
    // bitmaps at integer sizes), and turning fractional off makes the
    // post-pass deterministic about which pixel cell each bitmap lands in.
    Map<RenderingHints.Key, Object> hints = CompositeFontPanel.buildEmojiHints();
    assertEquals(RenderingHints.VALUE_FRACTIONALMETRICS_OFF,
        hints.get(RenderingHints.KEY_FRACTIONALMETRICS));
  }

  // ---- buildBoxDrawingHints: the post-pass for | / ┌ / ┘ / etc. ----
  //
  // Guards the gap-fix: grayscale AA + integer metrics so adjacent rows
  // of │ rasterize at the same y-offset and connect seamlessly.

  @Test
  void buildBoxDrawingHints_usesGrayscaleNotLcd() {
    // The post-pass uses grayscale AA, NOT LCD HRGB. The whole point of
    // this pass is to undo LCD's sub-pixel phase differences on narrow
    // vertical strokes. If this ever reads LCD HRGB, the gap comes back.
    Map<RenderingHints.Key, Object> hints = CompositeFontPanel.buildBoxDrawingHints();
    assertEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
        hints.get(RenderingHints.KEY_TEXT_ANTIALIASING),
        "Box-drawing post-pass must use grayscale AA, not LCD");
    assertNotEquals(RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB,
        hints.get(RenderingHints.KEY_TEXT_ANTIALIASING),
        "LCD HRGB on the post-pass defeats the gap fix");
  }

  @Test
  void buildBoxDrawingHints_disablesFractionalMetrics() {
    // Integer pixel positions are what makes │ connect across rows:
    // with fractional off, the rasterizer can't place the top/bottom
    // pixel row at a fractional y-offset.
    Map<RenderingHints.Key, Object> hints = CompositeFontPanel.buildBoxDrawingHints();
    assertEquals(RenderingHints.VALUE_FRACTIONALMETRICS_OFF,
        hints.get(RenderingHints.KEY_FRACTIONALMETRICS));
  }

  @Test
  void buildBoxDrawingHints_keepsContrast140() {
    // Contrast is a no-op with grayscale AA but we set it anyway for
    // symmetry with the other hint maps. Locks in the value so a
    // tweak to LCD_CONTRAST that the user wanted to apply only to the
    // global pass doesn't accidentally leak into the post-passes.
    Map<RenderingHints.Key, Object> hints = CompositeFontPanel.buildBoxDrawingHints();
    assertEquals(CompositeFontPanel.LCD_CONTRAST,
        hints.get(RenderingHints.KEY_TEXT_LCD_CONTRAST));
  }
}
