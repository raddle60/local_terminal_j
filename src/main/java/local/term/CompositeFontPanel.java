package local.term;

import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.AwtTransformers;
import com.jediterm.terminal.ui.TerminalAction;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import com.jediterm.terminal.util.CharUtils;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Color;
import java.awt.Component;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.InputEvent;
import java.awt.event.InputMethodEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * TerminalPanel that draws non-ASCII characters with a font-fallback chain
 * even when the primary font lacks those glyphs.
 *
 * <p>JediTerm's standard {@link TerminalPanel#drawChars} already segments each
 * line into runs of characters (via {@code BreakIterator.getCharacterInstance})
 * and calls {@link #getFontToDisplay(char[], int, int, TextStyle)} per run,
 * then {@code Graphics2D.setFont(font)} followed by {@code drawChars(...)}.
 * Overriding {@code getFontToDisplay} is therefore enough: for each run we
 * pick the first fallback font (in the configured order) whose
 * {@link Font#canDisplayUpTo(char[], int, int)} returns {@code -1} — i.e.
 * the first font that can render every char in the run.
 *
 * <p>The fallback chain typically contains three fonts:
 * <ol>
 *   <li>A CJK font (Microsoft YaHei Mono / Sarasa Mono) — covers Chinese,
 *       Japanese, Korean.</li>
 *   <li>A color-emoji font (Segoe UI Emoji / Noto Color Emoji) — covers
 *       🚀 ✨ ⭐ etc. that the CJK font lacks.</li>
 *   <li>A general-symbol font (Segoe UI Symbol / Symbola) — covers
 *       box-drawing (┌─┐), arrows (←→), math (π∑) that neither CJK
 *       nor emoji fonts include.</li>
 * </ol>
 * The order is significant: the CJK font is usually monospaced so we
 * prefer it for CJK runs to preserve column alignment. Emoji / symbol
 * runs have no alignment concern so font choice is purely visual.
 *
 * <p>Cell widths still come from the primary font (computed once in the
 * super-constructor's {@code establishFontMetrics}), so substituting a
 * different font mid-line only works when the CJK fallback has the same cell
 * width — i.e. both fonts are monospaced, or {@code myCharSize.width}
 * is wide enough to fit CJK double-width glyphs. The Settings dialog's
 * preview already enforces this by recommending CJK-capable monospace
 * fonts first.
 *
 * <p>When the primary font already has the glyphs, this class delegates
 * to {@code super.getFontToDisplay} as before — no overhead, no behavior
 * change.
 */
public class CompositeFontPanel extends TerminalPanel {
  private static final Logger LOG = LoggerFactory.getLogger(CompositeFontPanel.class);

  private FontResolver resolver;
  // Pre-derived bold/italic variants of every fallback font. Recomputed
  // once at construction so getFontToDisplay doesn't deriveFont on every
  // cell paint (a measurable cost on long sessions).
  private List<Font[]> variants;  // per fallback: [plain, bold, italic, boldItalic]
  // Size the fallback chain was built for. Tracked so applyFontSize knows
  // when the chain is stale (otherwise we'd rebuild on every tick).
  private int fallbackSize;

  /**
   * Legacy constructor: single CJK fallback font. Retained for backward
   * compatibility — wraps the font in a single-element
   * {@link FontResolver}. New code should use the
   * {@link #CompositeFontPanel(SettingsProvider, TerminalTextBuffer, StyleState, FontResolver)}
   * constructor to supply a full CJK + emoji + symbol chain.
   */
  public CompositeFontPanel(@NotNull SettingsProvider settingsProvider,
                            @NotNull TerminalTextBuffer terminalTextBuffer,
                            @NotNull StyleState styleState,
                            Font cjkFallback) {
    this(settingsProvider, terminalTextBuffer, styleState,
        cjkFallback == null ? new FontResolver() : new FontResolver(cjkFallback));
  }

  /**
   * Primary constructor: a {@link FontResolver} encapsulating the full
   * fallback chain (CJK, emoji, symbols).
   *
   * <p>Each font in the chain is derived up-front for the four TextStyle
   * variants so per-run selection stays cheap.
   */
  public CompositeFontPanel(@NotNull SettingsProvider settingsProvider,
                            @NotNull TerminalTextBuffer terminalTextBuffer,
                            @NotNull StyleState styleState,
                            FontResolver resolver) {
    super(settingsProvider, terminalTextBuffer, styleState);
    this.resolver = resolver != null ? resolver : new FontResolver();
    this.fallbackSize = (int) settingsProvider.getTerminalFontSize();
    this.variants = new ArrayList<>(this.resolver.fallbacks().size());
    for (Font f : this.resolver.fallbacks()) {
      this.variants.add(new Font[]{
          f,
          f.deriveFont(Font.BOLD),
          f.deriveFont(Font.ITALIC),
          f.deriveFont(Font.BOLD | Font.ITALIC)
      });
    }
    LOG.info("CompositeFontPanel constructed: fallbacks={}, primary font from settings={}",
        this.resolver.fallbacks().stream()
            .map(f -> f.getFamily()).reduce((a, b) -> a + "," + b).orElse("(none)"),
        settingsProvider.getTerminalFont().getFamily());
  }

  /**
   * Re-init the panel after the terminal font size changes. Re-runs the
   * inherited {@code reinitFontAndResize()} so the primary font and the
   * cell-metric grid (cell width / height, line spacing) are recomputed,
   * then rebuilds the CJK + symbol + emoji fallback chain at the new size
   * so CJK runs in the rebuilt fallback fonts render at the same point
   * size as the primary (otherwise CJK glyphs would visibly mismatch).
   *
   * <p>Caller is responsible for setting the new size on the shared
   * {@link DarkSettingsProvider} BEFORE invoking this method — the
   * fallback chain reads {@link DarkSettingsProvider#getCjkFontFamily()}
   * etc. and those fields are static.
   *
   * <p>Idempotent: a no-op when {@code size == fallbackSize}, so callers
   * can blindly invoke it on every spinner tick without checking.
   */
  public void applyFontSize(int size) {
    if (size == fallbackSize) return;
    fallbackSize = size;
    // Rebuild the primary font + cell metrics from the current settings.
    // reinitFontAndResize is inherited from JediTerm's TerminalPanel and
    // is the only public-ish entry point that recomputes myCharSize.width
    // / myCharSize.height — calling initFont() alone leaves the cell grid
    // at the old size and the new font would clip.
    reinitFontAndResize();
    // Rebuild the fallback chain so each fallback font is at the new
    // point size. buildFallbackChain reads the static font-family
    // overrides on DarkSettingsProvider — those were set BEFORE this
    // call so the chain reflects the user's current slot choices.
    FontResolver newResolver = CompositeFontJediTermWidget.buildFallbackChain(size);
    this.resolver = newResolver != null ? newResolver : new FontResolver();
    // Recompute bold/italic variants for the new chain.
    List<Font[]> fresh = new ArrayList<>(this.resolver.fallbacks().size());
    for (Font f : this.resolver.fallbacks()) {
      fresh.add(new Font[]{
          f,
          f.deriveFont(Font.BOLD),
          f.deriveFont(Font.ITALIC),
          f.deriveFont(Font.BOLD | Font.ITALIC)
      });
    }
    this.variants = fresh;
    // Force a repaint so the new metrics + new fonts render on the next
    // paint cycle. Without this, the change is invisible until the user
    // scrolls or the cursor blinks (whichever comes first).
    repaint();
  }

  @Override
  protected @NotNull Font getFontToDisplay(char[] text, int start, int end,
                                            @NotNull TextStyle style) {
    return chooseFont(text, start, end, style, resolver, variants,
        () -> super.getFontToDisplay(text, start, end, style));
  }

  /**
   * Replace JediTerm's grayscale-only text-antialiasing setup with LCD
   * subpixel AA + fractional metrics + a contrast bump. Without this
   * override, CJK glyphs in the live terminal render through Java2D's
   * low-quality grayscale path and look noticeably blurry at typical
   * 12-14 pt sizes — IDEA's own terminal doesn't suffer the same
   * blurriness because JetBrains Runtime's font renderer is more
   * forgiving for CJK at grayscale, and IDEA typically pairs CJK runs
   * with a tuned primary font. Plain OpenJDK 17 (what this project
   * ships with) does not give CJK the same treatment, so we have to
   * opt in explicitly.
   *
   * <p>Note: the comment in this method's earlier revisions claimed
   * "JetBrains Runtime enables LCD subpixel AA by default." That is
   * not accurate — JBR's {@code SunGraphics2D} constructor only bumps
   * {@code lcdTextContrast} from the OpenJDK default 100 to 140 (see
   * JBR master {@code src/java.desktop/share/classes/sun/java2d/SunGraphics2D.java}).
   * JBR leaves {@code KEY_TEXT_ANTIALIASING} and
   * {@code KEY_FRACTIONALMETRICS} at their default values
   * ({@code OFF}). The contrast bump is enough for IDEA's typical
   * ASCII workloads but not enough on its own to keep CJK readable on
   * plain OpenJDK 17.
   *
   * <p>Why these specific hints:
   * <ul>
   *   <li>{@code VALUE_TEXT_ANTIALIAS_LCD_HRGB} — subpixel AA on the
   *       horizontal-RGB stripe layout used by virtually every desktop
   *       LCD in landscape orientation. Renders CJK strokes with each
   *       pixel column having R/G/B subpixel coverage, which is what
   *       gives ClearType-quality text its sharp edges. On platforms /
   *       displays where LCD subpixels aren't available (older Linux
   *       configs, macOS with HiDPI+Scaled disabled), Java2D gracefully
   *       downgrades to grayscale AA — no worse than today.</li>
   *   <li>{@code VALUE_FRACTIONALMETRICS_ON} — lets the renderer
   *       position glyphs at sub-pixel x so runs that mix monospaced
   *       ASCII with a CJK fallback (which has a slightly different
   *       cell width than the primary mono font) don't show horizontal
   *       jitter between repaints. Also what
   *       {@link CompositeFontLabel}'s paint path enables — keeping
   *       the live terminal and the Settings preview visually
   *       consistent.</li>
   *   <li>{@code KEY_TEXT_LCD_CONTRAST} — bumped from the default
   *       (100) toward 140 so the LCD filter darkens the perimeter
   *       pixels harder. Terminal text is read at a glance, where a
   *       little extra weight helps crispness without going bold.</li>
   * </ul>
   *
   * <p>LCD HRGB has a known side-effect on narrow vertical strokes
   * (the {@code │} chars in Claude Code's box-drawing frames, and the
   * vertical strokes of {@code ┌┐└┘├┤┬┴┼}): each pixel column is
   * divided into R/G/B sub-pixels and shaded independently, so adjacent
   * rows' {@code │} rasterize with sub-pixel phase differences that
   * show as colored fringes / perceived gaps between rows. Color-emoji
   * bitmaps (Segoe UI Emoji, Noto Color Emoji) suffer a similar
   * occlusion where the LCD filter re-samples the RGBA bitmap through
   * its R/G/B columns and blurs the edges. To keep LCD HRGB's CJK
   * benefit while undoing the side-effects, see {@link #paintComponent}
   * which adds two selective post-passes — one for box-drawing chars,
   * one for emoji — that re-stamp the affected cells with hints chosen
   * for their content type.
   *
   * <p>{@code setupAntialiasing} is the customization point JediTerm
   * gives us — it's called from {@code paintComponent} (line 783 in
   * JediTerm's TerminalPanel.java) and is {@code protected}, so
   * overriding here is enough; nothing in JediTerm's draw path needs
   * to change.
   *
   * <p>Returns the hint map for unit testing rather than pulling
   * rendering helpers across a live Graphics2D.
   */
  @Override
  protected void setupAntialiasing(Graphics graphics) {
    if (graphics instanceof Graphics2D gfx) {
      gfx.setRenderingHints(buildAntialiasingHints());
    }
  }

  /**
   * Build the rendering-hint map applied by {@link #setupAntialiasing}.
   * Visible for testing — pure logic, no AWT state.
   *
   * <p>Always LCD on: the configured {@code SettingsProvider}
   * ({@link DarkSettingsProvider} via {@code DefaultSettingsProvider})
   * returns {@code true} for {@code useAntialiasing} and this project
   * exposes no UI to disable AA. Reading
   * {@code SettingsProvider.useAntialiasing()} from the subclass would
   * also need a {@code private} field on JediTerm's
   * {@code TerminalPanel}, which is not accessible across packages.
   */
  static Map<RenderingHints.Key, Object> buildAntialiasingHints() {
    Map<RenderingHints.Key, Object> hints = new HashMap<>();
    hints.put(RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB);
    hints.put(RenderingHints.KEY_FRACTIONALMETRICS,
        RenderingHints.VALUE_FRACTIONALMETRICS_ON);
    hints.put(RenderingHints.KEY_TEXT_LCD_CONTRAST, LCD_CONTRAST);
    return hints;
  }

  /**
   * How much extra weight the LCD subpixel filter applies to perimeter
   * pixels. Java2D's default is 100; values in 140-160 read slightly
   * crisper at terminal sizes (12-14 pt) without going bold. Matches
   * the {@code lcdTextContrastDefaultValue = 140} that JetBrains
   * Runtime uses in its {@code SunGraphics2D} constructor.
   */
  static final int LCD_CONTRAST = 140;

  /**
   * Override JediTerm's paint path so we can stamp the screen three
   * times with three different rendering-hint maps. The first pass
   * (delegated to {@code super.paintComponent}) uses LCD HRGB so CJK
   * stays crisp; the second pass re-stamps color-emoji cells with
   * {@code AA OFF} so the bitmap isn't re-sampled through R/G/B
   * sub-pixels; the third pass re-stamps box-drawing cells with
   * grayscale AA + integer metrics so {@code │} connects seamlessly
   * between adjacent rows. See {@link #buildAntialiasingHints} for why
   * the global pass is LCD HRGB in the first place.
   *
   * <p>{@code super.paintComponent} already filled the background and
   * drew the full text buffer with our LCD HRGB hints; the post-passes
   * overlay only the cells that LCD HRGB degrades. Order matters: the
   * emoji pass must run before the box-drawing pass because the two
   * predicate sets are mutually exclusive by construction but we paint
   * emoji first so any partial overlap (a cell whose fallback chain
   * puts a box-drawing glyph into an emoji font) ends up with the
   * box-drawing treatment.
   */
  @Override
  public void paintComponent(Graphics g) {
    if (!(g instanceof Graphics2D gfx)) {
      super.paintComponent(g);
      return;
    }
    gfx.setRenderingHints(buildAntialiasingHints());
    super.paintComponent(g);
    repaintEmojiChars(gfx);

    gfx.setRenderingHints(buildBoxDrawingHints());
    repaintBoxDrawingChars(gfx);
  }

  /**
   * Hints used by the color-emoji post-pass. {@code AA OFF} lets the
   * platform's color-bitmap path blit emoji bitmaps (Segoe UI Emoji,
   * Noto Color Emoji, …) without re-sampling through R/G/B sub-pixel
   * columns — that's what produces the colored fringe / occlusion
   * around emoji when they go through LCD HRGB. Contrast is a no-op
   * with AA OFF but kept for symmetry with the other hint maps.
   *
   * <p>Always on: see {@link #buildAntialiasingHints} for the same
   * "no UI to disable AA" reasoning.
   */
  static Map<RenderingHints.Key, Object> buildEmojiHints() {
    Map<RenderingHints.Key, Object> hints = new HashMap<>();
    hints.put(RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
    hints.put(RenderingHints.KEY_FRACTIONALMETRICS,
        RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
    hints.put(RenderingHints.KEY_TEXT_LCD_CONTRAST, LCD_CONTRAST);
    return hints;
  }

  /**
   * Hints used by the box-drawing post-pass. Grayscale AA at integer
   * pixel positions is what makes {@code │} connect seamlessly between
   * rows: with FRACTIONALMETRICS off, the rasterizer can't shade the
   * top/bottom pixel row at fractional y-offsets, which is the source
   * of LCD HRGB's sub-pixel phase fringes between adjacent rows. The
   * character class (U+2500–U+257F) is small and the runs short, so
   * the slight loss of sub-pixel horizontal crispness on these chars
   * is invisible compared to the win.
   */
  static Map<RenderingHints.Key, Object> buildBoxDrawingHints() {
    Map<RenderingHints.Key, Object> hints = new HashMap<>();
    hints.put(RenderingHints.KEY_TEXT_ANTIALIASING,
        RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    hints.put(RenderingHints.KEY_FRACTIONALMETRICS,
        RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
    hints.put(RenderingHints.KEY_TEXT_LCD_CONTRAST, LCD_CONTRAST);
    return hints;
  }

  /**
   * Returns {@code true} when {@code codePoint} belongs to one of the
   * Unicode blocks that a color-emoji font typically renders.
   * Conservative — includes a couple of misc-symbols /
   * geometric-shapes ranges that some emoji fonts claim. Always
   * mutually exclusive with {@link #isBoxDrawing(int)}: box-drawing
   * sub-ranges are excluded from the geometric-shapes window so the
   * two predicates never agree on the same codepoint.
   *
   * <p>Parameter is {@code int} (not {@code char}) so the predicate
   * plugs straight into {@link java.util.function.IntPredicate} as a
   * method reference from {@link #repaintByPredicate}.
   */
  static boolean isEmoji(int codePoint) {
    // Miscellaneous Technical U+2300–U+23FF (⌨ ⏏ ⏰ ⏳ …)
    if (codePoint >= 0x2300 && codePoint <= 0x23FF) return true;
    // Geometric Shapes U+25A0–U+25FF — mostly emoji-font territory, but
    // box-drawing U+2500–U+257F lives in here too, so the explicit
    // box-drawing range test runs first in repaintByPredicate and the
    // two predicates never overlap in practice.
    if (codePoint >= 0x25A0 && codePoint <= 0x25FF) return true;
    // Miscellaneous Symbols U+2600–U+26FF (☀ ☁ ☂ ☃ ☄ ★ ☆ ☇ …)
    if (codePoint >= 0x2600 && codePoint <= 0x26FF) return true;
    // Dingbats U+2700–U+27BF (✂ ✈ ✉ ✏ ✒ ✨ ❤ …)
    if (codePoint >= 0x2700 && codePoint <= 0x27BF) return true;
    // Misc Symbols & Pictographs U+1F300–U+1F5FF
    if (codePoint >= 0x1F300 && codePoint <= 0x1F5FF) return true;
    // Emoticons U+1F600–U+1F64F
    if (codePoint >= 0x1F600 && codePoint <= 0x1F64F) return true;
    // Transport & Map U+1F680–U+1F6FF
    if (codePoint >= 0x1F680 && codePoint <= 0x1F6FF) return true;
    // Supplemental Symbols & Pictographs U+1F900–U+1F9FF
    if (codePoint >= 0x1F900 && codePoint <= 0x1F9FF) return true;
    // Symbols & Pictographs Extended-A U+1FA70–U+1FAFF
    if (codePoint >= 0x1FA70 && codePoint <= 0x1FAFF) return true;
    return false;
  }

  /**
   * Returns {@code true} when {@code codePoint} is one of the Unicode
   * box-drawing characters (U+2500–U+257F). The set covers
   * {@code ─ │ ┌ ┐ └ ┘ ├ ┤ ┬ ┴ ┼} and their heavy / double variants.
   * These chars are the ones whose vertical strokes depend on
   * seamless connection between adjacent rows, so they're the ones
   * that LCD HRGB degrades — see {@link #repaintBoxDrawingChars}.
   *
   * <p>Parameter is {@code int} (not {@code char}) so the predicate
   * plugs straight into {@link java.util.function.IntPredicate} as a
   * method reference from {@link #repaintByPredicate}.
   */
  static boolean isBoxDrawing(int codePoint) {
    return codePoint >= '─' && codePoint <= '╿';
  }

  /**
   * Walk the visible screen cells and re-stamp any cell whose character
   * matches {@link #isEmoji}. Must be called after
   * {@code super.paintComponent} so the LCD-rendered content is in
   * place; the post-pass overlays the cells where LCD HRGB's
   * R/G/B sub-pixel coverage degrades color-emoji bitmaps.
   *
   * <p>Coordinates use the same formula as JediTerm's
   * {@code TerminalPanel.drawCharacters} (line 1302 in JediTerm's
   * TerminalPanel.java). We avoid the private
   * {@code mySpaceBetweenLines} by deriving it once per call from
   * {@code myCharSize.height} and the live {@code FontMetrics}.
   */
  private void repaintEmojiChars(Graphics2D gfx) {
    repaintByPredicate(gfx, CompositeFontPanel::isEmoji);
  }

  /**
   * Walk the visible screen cells and re-stamp any cell whose
   * character matches {@link #isBoxDrawing}. Must be called after
   * {@link #repaintEmojiChars} so the two passes don't fight over a
   * cell: the predicates are mutually exclusive, but the order keeps
   * box-drawing's treatment authoritative.
   *
   * <p>The grayscale AA + integer-metrics hint map is what makes
   * {@code │} connect seamlessly between rows. See
   * {@link #buildBoxDrawingHints}.
   */
  private void repaintBoxDrawingChars(Graphics2D gfx) {
    repaintByPredicate(gfx, CompositeFontPanel::isBoxDrawing);
  }

  /**
   * Shared redraw loop for the emoji and box-drawing post-passes. The
   * only difference between them is the predicate, so the rest of the
   * machinery (buffer walk, pixel coords, font selection, color
   * resolution, {@code drawChars} call) lives here.
   *
   * <p>Cells holding {@link CharUtils#DWC} are skipped — they're the
   * continuation half of a double-width character and the preceding
   * cell already painted the full glyph.
   */
  private void repaintByPredicate(Graphics2D gfx, java.util.function.IntPredicate matches) {
    TerminalTextBuffer buf = getTerminalTextBuffer();
    if (buf == null) return;
    int cols = buf.getWidth();
    int rows = buf.getHeight();
    if (cols <= 0 || rows <= 0) return;

    int charWidth = myCharSize.width;
    int charHeight = myCharSize.height;
    int insetX = getInsetX();
    int scrollOrigin = myClientScrollOrigin;
    // Derive mySpaceBetweenLines the same way JediTerm does in
    // establishFontMetrics(): from font-metrics height and the configured
    // line spacing. myCharSize.height already includes that multiplier.
    int fontMetricsHeight = gfx.getFontMetrics(getFontToDisplay("A".toCharArray(), 0, 1,
        TextStyle.EMPTY)).getHeight();
    int spaceBetweenLines = Math.max(0, ((charHeight - fontMetricsHeight) / 2) * 2);

    char[] oneChar = new char[1];
    for (int y = 0; y < rows; y++) {
      int bufferY = y + scrollOrigin;
      for (int x = 0; x < cols; x++) {
        char ch = buf.getBuffersCharAt(x, bufferY);
        if (ch == CharUtils.DWC) continue;
        if (!matches.test(ch)) continue;
        oneChar[0] = ch;
        TextStyle style = buf.getStyleAt(x, bufferY);
        TextStyle effective = style != null ? style : TextStyle.EMPTY;
        Font font = getFontToDisplay(oneChar, 0, 1, effective);
        gfx.setFont(font);
        gfx.setColor(resolveForeground(effective));
        int descent = gfx.getFontMetrics(font).getDescent();
        int xCoord = x * charWidth + insetX;
        int baseLine = (y + 1) * charHeight - spaceBetweenLines / 2 - descent;
        gfx.drawChars(oneChar, 0, 1, xCoord, baseLine);
      }
    }
    // Avoid leaving the gfx with the last cell's font / color set —
    // harmless but tidier to leave it as super left it.
    gfx.setFont(null);
  }

  /**
   * Resolve a {@link TextStyle} to its foreground AWT color. We use
   * {@link TextStyle#getForeground()} + {@link AwtTransformers#toAwtColor}
   * as a public replacement for JediTerm's private
   * {@code getStyleForeground(style)}, which has special handling for
   * selection backgrounds and {@code HyperlinkStyle} we deliberately
   * skip — the post-pass only re-stamps a small set of cell types
   * (box-drawing, emoji) where selection / hyperlink styling is rare,
   * and a slightly-wrong foreground on those rare cells is much less
   * disruptive than the LCD HRGB fringe that this post-pass exists to
   * fix. Falls back to the panel's default foreground when the style
   * has no explicit color or its color is an indexed palette entry
   * (we don't have access to the JediTerm-private palette from here
   * — the indexed case is rare in this post-pass because box-drawing
   * and emoji cells almost always carry an explicit RGB color).
   */
  private @NotNull Color resolveForeground(@NotNull TextStyle style) {
    com.jediterm.terminal.TerminalColor fg = style.getForeground();
    if (fg == null || fg.isIndexed()) {
      // getWindowForeground() returns com.jediterm.core.Color (JediTerm's
      // own type); AwtTransformers.toAwtColor converts it to java.awt.Color.
      // Fall back to default foreground for indexed colors — we don't
      // have access to JediTerm's private ColorPalette from here.
      return AwtTransformers.toAwtColor(getWindowForeground());
    }
    Color resolved = AwtTransformers.toAwtColor(fg.toColor());
    return resolved != null ? resolved : AwtTransformers.toAwtColor(getWindowForeground());
  }

  /**
   * Pure logic extracted for unit-testing without needing a live TerminalPanel.
   *
   * <p>Primary-font-first policy: for any run, if the primary font (as returned
   * by {@code superChoice}) can render every char in {@code text[start..end)}
   * — i.e. {@link Font#canDisplayUpTo(char[], int, int)} returns {@code -1} —
   * the primary font is used. This is essential for terminal graphics such as
   * box-drawing (│ ─ ┌ ┐ └ ┘) and block elements: those glyphs live in the
   * primary monospace font and are metric-matched to the cell height, so
   * routing them to a CJK/symbol fallback (whose ascent+descent differs from
   * the primary-derived cell height) leaves a vertical gap between rows.
   *
   * <p>Only when the primary font cannot render the whole run do we consult the
   * fallback chain: the first font in {@code variants} whose
   * {@code canDisplayUpTo} returns {@code -1} is picked (matching bold/italic
   * variant). This still covers CJK, emoji and symbols the primary lacks.
   *
   * <p>Visible for testing.
   */
  static Font chooseFont(char[] text, int start, int end, TextStyle style,
                         FontResolver resolver, List<Font[]> variants,
                         java.util.function.Supplier<Font> superChoice) {
    if (resolver != null && resolver.hasFallback() && runNeedsCjk(text, start, end)) {
      // Primary-font-first: if the primary font can render the whole run,
      // prefer it. Keeps box-drawing / block-element glyphs (which the primary
      // monospace font is metric-matched for) out of the fallback chain, so
      // adjacent rows connect seamlessly.
      Font primary = superChoice.get();
      if (primary != null && primary.canDisplayUpTo(text, start, end) == -1) {
        return primary;
      }
      // Primary can't render the run — find the first fallback that can.
      for (Font[] set : variants) {
        Font plain = set[0];
        if (plain != null && plain.canDisplayUpTo(text, start, end) == -1) {
          return pickStyle(set, style);
        }
      }
      // None can render the whole run — fall back to the plain variant
      // of the first fallback so the renderer still has a font.
      Font[] firstSet = variants.isEmpty() ? null : variants.get(0);
      if (firstSet != null && firstSet[0] != null) {
        return pickStyle(firstSet, style);
      }
      return primary != null ? primary : superChoice.get();
    }
    return superChoice.get();
  }

  private static Font pickStyle(Font[] variants, TextStyle style) {
    boolean bold = style.hasOption(TextStyle.Option.BOLD);
    boolean italic = style.hasOption(TextStyle.Option.ITALIC);
    if (bold && italic && variants[3] != null) return variants[3];
    if (bold && variants[1] != null) return variants[1];
    if (italic && variants[2] != null) return variants[2];
    return variants[0];
  }

  /**
   * True when {@code text[start..end)} contains at least one code point that
   * is outside printable ASCII. Used only as a cheap fast-path filter: a
   * pure-ASCII run always renders with the primary font, so we skip the
   * (more expensive) primary/fallback {@code canDisplayUpTo} probing entirely.
   * A non-ASCII run is merely a <em>candidate</em> for fallback — whether it
   * actually uses a fallback font is decided by {@link #chooseFont} after
   * checking the primary font first.
   */
  static boolean runNeedsCjk(char[] text, int start, int end) {
    for (int i = start; i < end; i++) {
      if (text[i] > 0x7F) return true;
    }
    return false;
  }

  // ---- IME composition repaint ----

  /**
   * Force a repaint when the IME updates uncommitted (composition) text.
   *
   * <p>JediTerm's {@link TerminalPanel#processInputMethodEvent} stores the
   * composition string in its {@code myInputMethodUncommittedChars} field
   * but does not call {@code repaint()} afterwards. The next paint that
   * draws those letters only happens when something else triggers a
   * repaint — typically the cursor blink on JediTerm's
   * {@code WeakRedrawTimer}, which fires every ~500 ms when the panel
   * has focus. That's why typed letters in Chinese IME candidate state
   * show severe display delay: the user types "n", "i", "h", "a", "o"
   * and only sees them roughly half a second later, by which time the
   * candidate window has opened and the user has lost their place.
   *
   * <p>This override delegates the IME bookkeeping to {@code super}
   * (which still owns the actual {@code sendString} on commit), then
   * schedules a repaint so {@code drawInputMethodUncommitedChars} runs
   * on the very next EDT paint pass. {@code repaint()} coalesces
   * concurrent calls, so a fast typing burst collapses to a single
   * paint instead of one per keystroke.
   *
   * <p>Only repaints on {@code INPUT_METHOD_TEXT_CHANGED} — caret-only
   * changes carry no visual delta so they don't need a repaint.
   *
   * <p>Why fix it here rather than upstream? JediTerm source is
   * consumed read-only via the sibling {@code ../jediterm} checkout
   * installed to local Maven. The single-line override here is the
   * local, reversible way to correct the behavior without forking
   * JediTerm.
   */
  @Override
  protected void processInputMethodEvent(InputMethodEvent e) {
    super.processInputMethodEvent(e);
    if (e.getID() == InputMethodEvent.INPUT_METHOD_TEXT_CHANGED) {
      repaint();
    }
  }

  // ---- right-click paste ----

  /**
   * Intercept right-click: paste via JediTerm's own paste action
   * (the same path Ctrl+V uses). No default popup menu is shown.
   *
   * <p>We consume both {@code MOUSE_PRESSED} and {@code MOUSE_CLICKED}
   * on BUTTON3. Only suppressing {@code MOUSE_CLICKED} is insufficient
   * when the running shell has enabled mouse tracking (DECSET 1000/1006)
   * — JediTerm forwards {@code mousePressed} to the PTY via
   * {@code TerminalPanel.addTerminalMouseListener}, which delivers an
   * SGR press escape to the TUI before our paste ever fires. TUIs like
   * Claude Code react to that press by starting a text selection, so the
   * user sees selection instead of paste. Consuming {@code MOUSE_PRESSED}
   * too blocks JediTerm's mousePressed listeners entirely (including
   * the PTY-forwarding one), keeping the right-click semantics
   * consistent across mouse-tracking and non-tracking shells.
   *
   * <p>{@code mouseReleased} still dispatches normally so JediTerm's
   * {@code repaint()} and {@code requestFocusInWindow()} run.
   */
  @Override
  protected void processMouseEvent(MouseEvent e) {
    if (e.getButton() == MouseEvent.BUTTON3) {
      if (e.getID() == MouseEvent.MOUSE_PRESSED) {
        e.consume();
        return;
      }
      if (e.getID() == MouseEvent.MOUSE_CLICKED) {
        pasteViaTerminalAction();
        e.consume();
        return;
      }
    }
    if (e.getButton() == MouseEvent.BUTTON1) {
      int id = e.getID();
      if (id == MouseEvent.MOUSE_PRESSED
          || id == MouseEvent.MOUSE_RELEASED
          || id == MouseEvent.MOUSE_CLICKED) {
        MouseEvent adjusted = snapDwcClick(e);
        if (adjusted != null) {
          e.consume();
          super.processMouseEvent(adjusted);
          return;
        }
      }
    }
    super.processMouseEvent(e);
  }

  /**
   * Mouse drag arrives here, not via {@link #processMouseEvent}.
   * Same DWC snap rule applies so a left-drag whose endpoint lands in
   * a wide-char continuation half doesn't end up as the sortPoints()
   * "top" of the selection range (which is what {@code
   * SelectionUtil.processForSelection} uses to decide whether to strip
   * DWC chars from the copied text).
   */
  @Override
  protected void processMouseMotionEvent(MouseEvent e) {
    if (e.getID() == MouseEvent.MOUSE_DRAGGED
        && (e.getModifiersEx() & InputEvent.BUTTON1_DOWN_MASK) != 0) {
      MouseEvent adjusted = snapDwcClick(e);
      if (adjusted != null) {
        e.consume();
        super.processMouseMotionEvent(adjusted);
        return;
      }
    }
    super.processMouseMotionEvent(e);
  }

  /**
   * Mirror of JediTerm's private {@code panelPointToCell()} —
   * determine which screen column the event is over, look up the
   * character there in the terminal buffer, and if that cell holds
   * {@link CharUtils#DWC} (the continuation half of a wide CJK
   * glyph), rebuild the event with its x shifted left by one
   * char-width so JediTerm resolves the same pixel back to the
   * wide-char column. The caller consumes the original event and
   * dispatches the adjusted one to JediTerm's listeners, so all of
   * JediTerm's selection bookkeeping (mousePressed start point,
   * mouseDragged end point, double/triple-click word/line selects)
   * ends up with a non-DWC column.
   *
   * <p>Returns {@code null} when no adjustment is needed (and when
   * the buffer isn't ready yet, so we never read past the current
   * scroll origin).
   */
  private MouseEvent snapDwcClick(MouseEvent e) {
    int charWidth = myCharSize.width;
    if (charWidth <= 0) return null;
    TerminalTextBuffer buf = getTerminalTextBuffer();
    if (buf == null) return null;
    int width = buf.getWidth();
    int height = buf.getHeight();
    if (width <= 0 || height <= 0) return null;

    int px = e.getX();
    int py = e.getY();
    int col = (px - getInsetX()) / charWidth;
    if (col <= 0) return null;            // column 0 can never be DWC; shifting would underflow
    if (col >= width) col = width - 1;

    int row = py / myCharSize.height;
    if (row < 0) row = 0;
    if (row >= height) row = height - 1;
    int bufferY = row + myClientScrollOrigin;
    int historyLines = buf.getHistoryLinesCount();
    if (bufferY < -historyLines || bufferY >= height) return null;

    if (buf.getBuffersCharAt(col, bufferY) != CharUtils.DWC) return null;

    int snappedX = px - charWidth;
    return new MouseEvent(
        (Component) e.getSource(), e.getID(), e.getWhen(),
        e.getModifiersEx(),
        snappedX, py, e.getClickCount(), e.isPopupTrigger(), e.getButton());
  }

  /**
   * Locate the Paste &amp; Copy {@link TerminalAction}s in this panel's
   * action list and fire them in sequence — first paste (same path as
   * Ctrl+V), then a synthetic Ctrl+C to clear the selection.
   *
   * <p>{@code TerminalPanel.handleCopy(KeyEvent)} detects Ctrl+C
   * ({@code getKeyCode()==VK_C && getModifiersEx()==CTRL_DOWN_MASK})
   * and calls {@code handleCopy(true, false)} which copies again
   * (harmless — the text is already on the clipboard from
   * {@code copyOnSelect}) and then calls {@code updateSelection(null)}
   * + {@code repaint()} to clear the highlight.
   */
  private void pasteViaTerminalAction() {
    for (TerminalAction action : getActions()) {
      if (action.getMnemonicKeyCode() != null
          && action.getMnemonicKeyCode() == KeyEvent.VK_P) {
        action.actionPerformed(null);
        break;
      }
    }
    for (TerminalAction action : getActions()) {
      if (action.getMnemonicKeyCode() != null
          && action.getMnemonicKeyCode() == KeyEvent.VK_C) {
        action.actionPerformed(new KeyEvent(
            this, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
            KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_C, KeyEvent.CHAR_UNDEFINED));
        break;
      }
    }
  }
}
