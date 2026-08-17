package local.term;

import com.jediterm.core.Color;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import com.jediterm.terminal.ui.TerminalActionPresentation;
import com.jediterm.terminal.ui.settings.DefaultSettingsProvider;

import javax.swing.KeyStroke;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import static com.jediterm.terminal.ui.UtilKt.isMacOS;

/**
 * Dark-mode {@link com.jediterm.terminal.ui.settings.SettingsProvider} for JediTerm.
 *
 * Uses a near-black background (#1e1e1e) and light-gray default foreground
 * (#d4d4d4) — the same palette as VS Code's Dark+ theme. ANSI 16 colors are
 * tuned for adequate contrast on the dark background.
 *
 * The terminal font is picked from a preference list that prefers CJK-capable
 * monospace fonts. The default JediTerm font (Consolas) lacks CJK glyphs,
 * which renders non-ASCII characters as the missing-glyph "tofu" (□).
 *
 * Selection uses a translucent steel-blue so selected text is still readable.
 */
public class DarkSettingsProvider extends DefaultSettingsProvider {

  /**
   * Static font override set by the Settings dialog. When non-null, this
   * family is used regardless of the auto-detection preference list. Allows
   * the user to pick any installed font (including CJK-capable ones not on
   * our preference list).
   */
  private static volatile String overrideFontFamily;

  /**
   * Static CJK fallback Font used by {@link CompositeFontPanel} to render
   * non-ASCII characters when the primary font lacks them. Updated whenever
   * the user picks a font in Settings (so a Consolas session gets YaHei Mono
   * for Chinese, a YaHei session gets nothing because YaHei already covers
   * CJK).
   */
  private static volatile Font cjkFallbackFont;

  /**
   * Per-slot font family overrides set by the Settings dialog. Each one
   * is the family name to use for that category (CJK / Symbol / Emoji)
   * when {@link CompositeFontJediTermWidget} builds the fallback chain.
   * A {@code null} value means "auto-detect" — the widget then calls
   * the corresponding {@code FontUtils.find*Font(...)} helper. Users who
   * configure NONE of the four slots therefore still get the original
   * auto-detected fallback chain.
   */
  private static volatile String cjkFontFamily;
  private static volatile String symbolFontFamily;
  private static volatile String emojiFontFamily;

  /**
   * Terminal font size in points. Set by the Settings dialog; the
   * overridden {@link #getTerminalFontSize()} returns this value so any
   * {@link CompositeFontJediTermWidget} / {@code TerminalPanel} built
   * afterwards picks up the new size. Defaults to
   * {@link AppSettings#DEFAULT_FONT_SIZE} so unconfigured sessions use
   * the same size as before the setting was introduced.
   */
  private static volatile int terminalFontSize = AppSettings.DEFAULT_FONT_SIZE;

  /** Set the font override; pass {@code null} to revert to auto-detect. */
  public static void setOverrideFontFamily(String family) {
    overrideFontFamily = family;
  }

  public static String getOverrideFontFamily() {
    return overrideFontFamily;
  }

  /** Set the CJK fallback font used by {@link CompositeFontPanel}. */
  public static void setCjkFallbackFont(Font font) {
    cjkFallbackFont = font;
  }

  /** The CJK fallback font, or {@code null} if unset / never needed. */
  public static Font getCjkFallbackFont() {
    return cjkFallbackFont;
  }

  /**
   * User-configured CJK fallback family, or {@code null} for auto-detect.
   * Honoured by {@link CompositeFontJediTermWidget#buildFallbackChain}.
   */
  public static void setCjkFontFamily(String family) { cjkFontFamily = family; }
  public static String getCjkFontFamily() { return cjkFontFamily; }

  /**
   * User-configured Symbol fallback family (box-drawing / arrows / ✔ etc.),
   * or {@code null} for auto-detect.
   */
  public static void setSymbolFontFamily(String family) { symbolFontFamily = family; }
  public static String getSymbolFontFamily() { return symbolFontFamily; }

  /**
   * User-configured Emoji fallback family, or {@code null} for auto-detect.
   */
  public static void setEmojiFontFamily(String family) { emojiFontFamily = family; }
  public static String getEmojiFontFamily() { return emojiFontFamily; }

  /**
   * Set the terminal font size in points. Out-of-range values are clamped
   * to {@link AppSettings#MIN_FONT_SIZE}..{@link AppSettings#MAX_FONT_SIZE}.
   * Affects both new sessions (via the instance {@link #getTerminalFontSize()})
   * and existing open sessions (caller must invoke the panel's
   * {@code reinitFontAndResize()} so cell metrics are recomputed).
   */
  public static void setTerminalFontSize(int size) {
    terminalFontSize = Math.max(AppSettings.MIN_FONT_SIZE,
        Math.min(AppSettings.MAX_FONT_SIZE, size));
  }

  /**
   * Current terminal font size as configured by the static setter.
   * Named {@code getConfiguredTerminalFontSize} (not {@code getTerminalFontSize})
   * to avoid clashing with the inherited instance method on the same class.
   * Defaults to {@link AppSettings#DEFAULT_FONT_SIZE}.
   */
  public static int getConfiguredTerminalFontSize() { return terminalFontSize; }

  /** Indexed ANSI palette: 0=black, 1=red, ..., 7=white, 8=bright black, ..., 15=bright white. */
  private static final Color[] ANSI = new Color[] {
      new Color(0, 0, 0),         // 0  black
      new Color(205, 49, 49),     // 1  red
      new Color(13, 188, 121),    // 2  green
      new Color(229, 187, 81),    // 3  yellow
      new Color(36, 113, 229),    // 4  blue
      new Color(188, 63, 188),    // 5  magenta
      new Color(17, 168, 205),    // 6  cyan
      new Color(212, 212, 212),   // 7  white
      new Color(102, 102, 102),   // 8  bright black
      new Color(241, 76, 76),     // 9  bright red
      new Color(35, 209, 139),    // 10 bright green
      new Color(245, 219, 122),   // 11 bright yellow
      new Color(59, 142, 234),    // 12 bright blue
      new Color(214, 112, 214),   // 13 bright magenta
      new Color(41, 184, 219),    // 14 bright cyan
      new Color(255, 255, 255),   // 15 bright white
  };

  private static final Color DEFAULT_BG = new Color(30, 30, 30);     // #1e1e1e
  private static final Color DEFAULT_FG = new Color(212, 212, 212);  // #d4d4d4

  /**
   * Font preference list — tried in order. The first one that exists on the
   * current system is used. ONLY monospaced fonts: the terminal pane renders
   * cells as fixed-width columns, and proportional fonts (Microsoft YaHei,
   * SimSun, etc.) produce visibly broken character spacing.
   */
  private static final String[] FONT_PREFERENCE = {
      // CJK-capable monospace fonts (best — render both ASCII and CJK in mono)
      "Microsoft YaHei Mono",
      "Sarasa Mono SC",
      "Sarasa Mono TC",
      "Cascadia Mono",
      "Cascadia Code",
      "JetBrains Mono",
      "Source Code Pro",
      // Latin-only monospace fallbacks — system font fallback may still
      // composite CJK via OS font substitution.
      "Consolas",
      "Courier New",
      "Monospaced"
  };

  private static final ColorPalette DARK = new ColorPalette() {
    @Override public Color getForegroundByColorIndex(int colorIndex) {
      int idx = colorIndex < ANSI.length ? colorIndex : 7;
      return ANSI[idx];
    }
    @Override protected Color getBackgroundByColorIndex(int colorIndex) {
      // Always render backgrounds as the dark default; ANSI background colors
      // would clash with the terminal pane background.
      return DEFAULT_BG;
    }
  };

  @Override
  public ColorPalette getTerminalColorPalette() {
    return DARK;
  }

  @Override
  public Font getTerminalFont() {
    int size = (int) getTerminalFontSize();
    // Return the primary family verbatim. CJK / Symbol / Emoji are
    // handled by their own dedicated slots in the fallback chain
    // (see CompositeFontJediTermWidget.buildFallbackChain), so the
    // whole-line CJK swap that FontUtils.resolveTerminalFont used to
    // do is no longer needed.
    return new Font(terminalFontFamily(), Font.PLAIN, size);
  }

  /**
   * Resolve the primary terminal font's family name — the user's override if
   * set, otherwise the first installed entry in {@link #FONT_PREFERENCE} (or
   * {@link Font#MONOSPACED} as a last resort). Extracted from
   * {@link #getTerminalFont()} so callers that only need the family (e.g.
   * {@link CompositeFontJediTermWidget#primaryCellWidth} to measure the cell
   * width) can get it without constructing a {@link DarkSettingsProvider}
   * instance.
   *
   * <p>The auto-detect branch enumerates the installed font families, which
   * is the expensive part of this method; the instance itself is cheap. Static
   * and reused so a single call site pays the enumeration once.
   */
  public static String terminalFontFamily() {
    String override = overrideFontFamily;
    if (override != null && !override.isBlank()) {
      return override;
    }
    String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .getAvailableFontFamilyNames();
    Set<String> have = new HashSet<>(Arrays.asList(available));
    for (String f : FONT_PREFERENCE) {
      if (have.contains(f)) return f;
    }
    return Font.MONOSPACED;
  }

  /**
   * Return the terminal font size as a float so the JediTerm
   * {@code SettingsProvider} contract is honoured. The float round-trip
   * is lossless for the integer sizes we expose
   * ({@link AppSettings#MIN_FONT_SIZE}..{@link AppSettings#MAX_FONT_SIZE}).
   */
  @Override
  public float getTerminalFontSize() {
    return terminalFontSize;
  }

  /**
   * Default text style used for unstyled characters and as the reset
   * target for SGR 0. The JediTerm default is
   * {@code TextStyle(TerminalColor.BLACK, TerminalColor.WHITE)} —
   * {@code TerminalColor.BLACK == index(0)} — which would route every
   * unstyled character through {@link com.jediterm.terminal.emulator.ColorPalette#getForegroundByColorIndex(int)}.
   * With the palette's tuned index-0 entry that means black on dark, so
   * the default text would be invisible.
   *
   * <p>Returning RGB values here bypasses the indexed lookup (JediTerm's
   * {@code ColorPalette.getForeground(TerminalColor)} returns
   * {@code color.toColor()} directly for non-indexed colors). Explicit
   * SGR sequences ({@code CSI 30..37 m}) still go through the indexed
   * palette and render as their proper ANSI colours — this override only
   * affects the unset / reset case.
   */
  @Override
  public TextStyle getDefaultStyle() {
    return new TextStyle(
        com.jediterm.terminal.TerminalColor.rgb(DEFAULT_FG.getRed(), DEFAULT_FG.getGreen(), DEFAULT_FG.getBlue()),
        com.jediterm.terminal.TerminalColor.rgb(DEFAULT_BG.getRed(), DEFAULT_BG.getGreen(), DEFAULT_BG.getBlue()));
  }

  @Override
  public com.jediterm.terminal.TerminalColor getDefaultForeground() {
    return com.jediterm.terminal.TerminalColor.rgb(DEFAULT_FG.getRed(), DEFAULT_FG.getGreen(), DEFAULT_FG.getBlue());
  }

  @Override
  public com.jediterm.terminal.TerminalColor getDefaultBackground() {
    return com.jediterm.terminal.TerminalColor.rgb(DEFAULT_BG.getRed(), DEFAULT_BG.getGreen(), DEFAULT_BG.getBlue());
  }

  @Override
  public TextStyle getSelectionColor() {
    return new TextStyle(
        com.jediterm.terminal.TerminalColor.rgb(220, 220, 220),
        com.jediterm.terminal.TerminalColor.rgb(70, 130, 180));
  }

  /**
   * Auto-copy selected text to the clipboard the moment the user finishes
   * a selection (drag-release, double-click word, triple-click line). The
   * underlying {@link com.jediterm.terminal.ui.TerminalPanel} already wires
   * {@code copyOnSelect} into its mouse handlers — flipping this flag on
   * is enough to enable the feature with no UI plumbing on our side.
   *
   * <p>Mirrors the behaviour of every native terminal on Windows / macOS
   * and most Linux distros (gnome-terminal, Windows Terminal, iTerm2,
   * WezTerm, …). The system-selection clipboard is used on X11 / Wayland
   * (middle-click-paste is wired to it) and the regular clipboard on
   * Windows / macOS, so Ctrl+V always picks up the latest selection.
   */
  @Override
  public boolean copyOnSelect() {
    return true;
  }

  /**
   * Copy action gets two accelerators:
   * <ul>
   *   <li>{@code Ctrl+Shift+C} (jediterm default, kept for parity)</li>
   *   <li>{@code Ctrl+C} — copies the current selection if there is one,
   *       and otherwise falls through to the default key handler which
   *       sends SIGINT ({@code 0x03}). This matches Windows Terminal /
   *       WezTerm / iTerm2 behaviour: copy when text is highlighted,
   *       interrupt when nothing is selected. {@link
   *       com.jediterm.terminal.ui.TerminalPanel#handleCopy(KeyEvent)}
   *       already implements that branching — it returns {@code false}
   *       when Ctrl+C is pressed with no selection, which makes
   *       {@code TerminalAction.processEvent} continue down the chain
   *       to {@code processTerminalKeyPressed} where {@code Ctrl+C}
   *       becomes the ETX byte the shell interprets as SIGINT.</li>
   * </ul>
   */
  @Override
  public TerminalActionPresentation getCopyActionPresentation() {
    int ctrl = isMacOS() ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
    return new TerminalActionPresentation(
        "Copy",
        Collections.unmodifiableList(Arrays.asList(
            KeyStroke.getKeyStroke(KeyEvent.VK_C, ctrl | InputEvent.SHIFT_DOWN_MASK),
            KeyStroke.getKeyStroke(KeyEvent.VK_C, ctrl))));
  }

  /**
   * Paste action also gets two accelerators:
   * <ul>
   *   <li>{@code Ctrl+Shift+V} (jediterm default, kept for parity)</li>
   *   <li>{@code Ctrl+V} — the conventional paste shortcut on every
   *       desktop OS. Without this, users hitting Ctrl+V (the universal
   *       paste shortcut) accidentally send {@code 0x16} (SYN) to the
   *       shell, which most shells silently ignore — so Ctrl+V did
   *       nothing in the terminal even though it works everywhere else.</li>
   * </ul>
   */
  @Override
  public TerminalActionPresentation getPasteActionPresentation() {
    int ctrl = isMacOS() ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
    return new TerminalActionPresentation(
        "Paste",
        Collections.unmodifiableList(Arrays.asList(
            KeyStroke.getKeyStroke(KeyEvent.VK_V, ctrl | InputEvent.SHIFT_DOWN_MASK),
            KeyStroke.getKeyStroke(KeyEvent.VK_V, ctrl))));
  }
}