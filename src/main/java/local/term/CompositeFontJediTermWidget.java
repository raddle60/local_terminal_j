package local.term;

import com.jediterm.terminal.model.StyleState;
import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalPanel;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import org.jetbrains.annotations.NotNull;

import java.awt.Font;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link JediTermWidget} that builds a {@link CompositeFontPanel} so the
 * terminal pane renders non-ASCII characters with a font-fallback chain
 * (CJK + color-emoji + general-symbol) when the user's chosen font
 * doesn't have those glyphs.
 *
 * <p>The fallback chain is assembled at {@code createTerminalPanel} time
 * by {@link #buildFallbackChain(int)}, which fills a CJK, a general-symbol,
 * and a color-emoji slot (in that order) from the user's configured
 * families or, when unset, auto-detected system fonts. The per-run font is
 * then chosen at paint time in {@link CompositeFontPanel} — primary font
 * first, falling through the chain via {@link java.awt.Font#canDisplayUpTo}.
 *
 * <p>Existing sessions keep the chain they were launched with; a
 * Settings change picks up on the next {@code openSession}.
 */
public class CompositeFontJediTermWidget extends JediTermWidget {
  public CompositeFontJediTermWidget(@NotNull SettingsProvider settingsProvider) {
    super(settingsProvider);
  }

  @Override
  protected TerminalPanel createTerminalPanel(@NotNull SettingsProvider settingsProvider,
                                              @NotNull StyleState styleState,
                                              @NotNull TerminalTextBuffer terminalTextBuffer) {
    int size = (int) settingsProvider.getTerminalFontSize();
    FontResolver resolver = buildFallbackChain(size);
    return new CompositeFontPanel(settingsProvider, terminalTextBuffer, styleState, resolver);
  }

  /**
   * Build the terminal-pane fallback chain. Order matters: CJK first
   * (preserves grid alignment for Chinese-heavy output), then general
   * symbols (covers box-drawing / arrows / ✔ — text-presentation symbol
   * fonts render these close to one cell wide, which avoids the opaque
   * background overflow that truncated ✔ in the original chain), then
   * color emoji (covers 🚀 ✨ ⭐). Putting emoji BEFORE symbol caused
   * ✔ U+2714 to be drawn by Segoe UI Emoji at ~2.4 cells wide, which
   * then got its right half covered by the next run's background fill.
   *
   * <p>Each slot honours a user-configured family from the Settings
   * dialog when set; otherwise it auto-detects via the corresponding
   * {@link FontUtils} helper. Users who configure NONE of the four
   * slots therefore get the original auto-detected chain.
   *
   * <p>Each candidate is added only when {@link FontUtils} returns a
   * non-null font for the category — i.e. the OS actually has an
   * installed, glyph-capable font for that category. On a system with
   * none of them, the resolver ends up empty and the panel falls
   * through to the primary font (preserving the OS's own font-linking
   * fallback for any glyph the primary can locate via system
   * substitution).
   *
   * <p>Also publishes the resolved CJK fallback to
   * {@link DarkSettingsProvider#setCjkFallbackFont(Font)} so a
   * subsequent Settings save / override sees the same fallback used at
   * runtime (kept for backward compatibility — the static CJK fallback
   * is consulted by other code paths).
   */
  /**
   * Measure the primary font's single-cell advance (the width JediTerm's
   * {@code establishFontMetrics} derives from {@code charWidth('W')}) for the
   * terminal font that {@link DarkSettingsProvider#getTerminalFont()} will
   * return at {@code size}. This is the {@code cellWidth} that
   * {@link FontUtils#scaleCjkToGrid} must match the CJK fallback against.
   *
   * <p>Reads the family via {@link DarkSettingsProvider#terminalFontFamily()}
   * — a static call, so no provider instance is constructed and the
   * installed-font enumeration happens at most once per call (and
   * {@link #buildFallbackChain} runs once per session open / font-size
   * change, so it is nowhere near a hot path). Falls back to {@code 0} when
   * the family isn't installed, which the scale guard in {@link FontUtils}
   * tolerates gracefully (scale is skipped).
   */
  static int primaryCellWidth(int size) {
    return FontUtils.cellWidth(size, DarkSettingsProvider.terminalFontFamily());
  }

  static FontResolver buildFallbackChain(int size) {
    List<Font> chain = new ArrayList<>(3);

    // CJK slot — pad the fallback's advance so a CJK ideograph occupies
    // exactly two primary cells, defeating JediTerm's "centre the Unicode
    // symbol" shift that nudges every CJK run right (and inconsistently so
    // during selection) when the fallback's ideograph advance is narrower
    // than 2 * cellWidth (e.g. Microsoft YaHei UI under a JetBrains Mono
    // primary). Padding is a no-op for a true monospaced CJK fallback and
    // never deforms the glyph outline (TextAttribute.TRACKING adds advance
    // only).
    String cjkFamily = DarkSettingsProvider.getCjkFontFamily();
    Font cjk;
    if (cjkFamily != null && !cjkFamily.isBlank()) {
      cjk = new Font(cjkFamily, Font.PLAIN, size);
    } else {
      cjk = FontUtils.findTerminalCjkFallback(size);
    }
    cjk = FontUtils.scaleCjkToGrid(cjk, size, primaryCellWidth(size));
    if (cjk != null) chain.add(cjk);

    // Symbol slot — placed BEFORE emoji so ✔ uses the narrower text-
    // presentation symbol font rather than the ~2.4-cell color emoji.
    String symbolFamily = DarkSettingsProvider.getSymbolFontFamily();
    Font symbol;
    if (symbolFamily != null && !symbolFamily.isBlank()) {
      symbol = new Font(symbolFamily, Font.PLAIN, size);
    } else {
      symbol = FontUtils.findGeneralSymbolFont(size);
    }
    if (symbol != null) chain.add(symbol);

    // Emoji slot
    String emojiFamily = DarkSettingsProvider.getEmojiFontFamily();
    Font emoji;
    if (emojiFamily != null && !emojiFamily.isBlank()) {
      emoji = new Font(emojiFamily, Font.PLAIN, size);
    } else {
      emoji = FontUtils.findEmojiFont(size);
    }
    if (emoji != null) chain.add(emoji);

    FontResolver resolver = new FontResolver(chain);

    // Publish the CJK piece (if any) to the legacy static so callers
    // that still consult it (Settings save flow) see the same font.
    if (cjk != null) {
      DarkSettingsProvider.setCjkFallbackFont(cjk);
    }
    return resolver;
  }
}