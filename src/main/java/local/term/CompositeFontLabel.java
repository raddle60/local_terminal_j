package local.term;

import javax.swing.JLabel;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link JLabel} that paints each text run with the font that can actually
 * render it. ASCII characters are drawn with the primary font; any character
 * above {@code 0x7F} is drawn with the first fallback font in the configured
 * chain that can render the run. This mirrors how
 * {@link CompositeFontPanel} chooses fonts at draw time so a preview label
 * can faithfully show what the user will see in the terminal without making
 * the label itself depend on running JediTerm.
 *
 * <p>Used by the Settings dialog so selecting a Latin-only monospace font
 * (Consolas, Courier New, …) doesn't render CJK / emoji / symbols as tofu
 * in the preview sample. The fallback chain is typically:
 * <ol>
 *   <li>CJK font (Microsoft YaHei Mono / Sarasa Mono).</li>
 *   <li>Color-emoji font (Segoe UI Emoji / Noto Color Emoji).</li>
 *   <li>General-symbol font (Segoe UI Symbol / Symbola).</li>
 * </ol>
 *
 * <p>If the fallback chain is empty, the label degrades to plain
 * {@link JLabel} behaviour — every char is drawn with the primary font,
 * which is fine when the primary already covers all glyphs.
 *
 * <p>Alignment / icon / HTML rendering are not handled; this label is for
 * preview text only.
 */
public class CompositeFontLabel extends JLabel {
  private final List<Font> fallbacks = new ArrayList<>();

  public CompositeFontLabel(String text) {
    super(text);
  }

  /**
   * Set the (single) fallback font used to draw non-ASCII runs. Replaces
   * any previously-configured chain. Pass {@code null} to disable
   * fallback rendering (the label then paints with its single {@code Font}
   * like a regular {@code JLabel}).
   *
   * <p>Retained for backward compatibility — new code should use
   * {@link #addFallbackFont(Font)} to build a multi-font chain that
   * covers CJK + emoji + symbols.
   */
  public void setCjkFallback(Font cjkFallback) {
    fallbacks.clear();
    if (cjkFallback != null) {
      fallbacks.add(cjkFallback);
    }
    repaint();
  }

  /**
   * Append a fallback font to the chain. Order matters: the first font
   * that can render a given run wins. Callers typically add CJK first
   * (covers Chinese / Japanese / Korean), then emoji (covers 🚀 ✨ ⭐),
   * then general-symbol (covers box-drawing / arrows / math).
   *
   * <p>Null entries are ignored. The fallback's point size is resynced to
   * the primary font's size so the label stays single-line.
   */
  public void addFallbackFont(Font font) {
    if (font == null) return;
    Font primary = getFont();
    Font sized = (primary != null && font.getSize() != primary.getSize())
        ? font.deriveFont((float) primary.getSize()) : font;
    fallbacks.add(sized);
    repaint();
  }

  /**
   * Replace the entire fallback chain in one call. Useful for wiring the
   * label from a {@link FontResolver} built elsewhere.
   */
  public void setFallbackFonts(List<Font> fonts) {
    fallbacks.clear();
    if (fonts != null) {
      Font primary = getFont();
      for (Font f : fonts) {
        if (f == null) continue;
        fallbacks.add(primary != null && f.getSize() != primary.getSize()
            ? f.deriveFont((float) primary.getSize()) : f);
      }
    }
    repaint();
  }

  /**
   * The first font in the fallback chain, or {@code null} if the chain
   * is empty. Retained for backward compatibility — new code should
   * use {@link #getFallbackFonts()}.
   */
  public Font getCjkFallback() {
    return fallbacks.isEmpty() ? null : fallbacks.get(0);
  }

  /** The configured fallback chain (immutable view). */
  public List<Font> getFallbackFonts() {
    return List.copyOf(fallbacks);
  }

  @Override
  public void setFont(Font font) {
    super.setFont(font);
    // Resync every fallback's size to the primary font's size so all
    // runs render at the same height and the label stays on one line.
    // Sizing is independent of family so deriveFont is enough.
    //
    // The fallbacks guard handles the case where JLabel's super-constructor
    // calls setFont(null) transitively (via updateUI() → setUI() →
    // installDefaults()) BEFORE our field initializers run. Without the
    // guard, that path would NPE in the field initializer order. After
    // construction completes, fallbacks is always non-null so the guard
    // is a one-time bootstrap concern.
    if (font != null && fallbacks != null) {
      for (int i = 0; i < fallbacks.size(); i++) {
        Font f = fallbacks.get(i);
        if (f != null && f.getSize() != font.getSize()) {
          fallbacks.set(i, f.deriveFont((float) font.getSize()));
        }
      }
    }
    repaint();
  }

  @Override
  protected void paintComponent(Graphics g) {
    String text = getText();
    Font primary = getFont();
    if (text == null || text.isEmpty() || primary == null
        || fallbacks == null || fallbacks.isEmpty()) {
      super.paintComponent(g);
      return;
    }

    Graphics2D g2 = (Graphics2D) g.create();
    try {
      // Enable LCD subpixel antialiasing so color-emoji glyphs render
      // with the platform color-bitmap path on Windows (DirectWrite
      // surface) — without these hints, drawString downgrades color
      // emoji to grayscale bitmaps. Fractional metrics lets runs that
      // mix proportional emoji with monospaced ASCII align cleanly.
      g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
          RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
      g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
          RenderingHints.VALUE_FRACTIONALMETRICS_ON);

      Rectangle clip = g2.getClipBounds();
      FontRenderContext frc = g2.getFontRenderContext();

      // Walk the runs once first to pick each run's font, then compute
      // ascent against the font that actually owns the FIRST visible
      // glyph (not the primary). Color emoji fonts often have a much
      // larger ascent than the primary mono font, so using the primary's
      // height clipped the emoji rows in the Settings dialog.
      char[] chars = text.toCharArray();
      Font firstRunFont = pickFontForRun(chars, 0, chars.length, primary);
      int ascent = (int) Math.ceil(firstRunFont.getLineMetrics(text, frc).getAscent());
      int x = getInsets().left;
      int y = getInsets().top + ascent;
      Color fg = getForeground();
      g2.setColor(fg);

      int i = 0;
      while (i < chars.length) {
        boolean needsFallback = chars[i] > 0x7F;
        int j = i + 1;
        while (j < chars.length && (chars[j] > 0x7F) == needsFallback) j++;
        Font runFont = pickFontForRun(chars, i, j, primary);
        g2.setFont(runFont);
        String run = new String(chars, i, j - i);
        g2.drawString(run, x, y);
        x += (int) Math.ceil(runFont.createGlyphVector(frc, run)
            .getVisualBounds().getWidth());
        i = j;
      }

      // Honour any clip rectangle JLabel would normally paint into (selection
      // backgrounds, focus ring, etc.) by repainting the rest as-is.
      if (clip != null) {
        g2.setClip(clip.x, clip.y, clip.width, clip.height);
      }
    } finally {
      g2.dispose();
    }
  }

  /**
   * Pick the right font for {@code chars[start..end)}:
   * <ol>
   *   <li>The primary if it can render every char in the run.</li>
   *   <li>Otherwise, the first fallback whose {@code canDisplayUpTo}
   *       returns {@code -1} for the run.</li>
   *   <li>Otherwise, the first fallback (so the renderer still has a
   *       font — better tofu-only than a crash).</li>
   * </ol>
   *
   * <p>For ASCII runs we always use the primary regardless of whether
   * a fallback also covers them, so the user's chosen primary always
   * owns its own glyphs and a different fallback never silently
   * replaces it.
   */
  private Font pickFontForRun(char[] chars, int start, int end, Font primary) {
    boolean needsFallback = false;
    for (int k = start; k < end; k++) {
      if (chars[k] > 0x7F) { needsFallback = true; break; }
    }
    if (!needsFallback) return primary;
    if (primary != null && primary.canDisplayUpTo(chars, start, end) == -1) {
      return primary;
    }
    Font first = fallbacks.get(0);
    for (Font f : fallbacks) {
      if (f != null && f.canDisplayUpTo(chars, start, end) == -1) {
        return f;
      }
    }
    return first;
  }
}