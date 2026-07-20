package local.term;

import com.jediterm.core.Color;
import com.jediterm.terminal.TerminalColor;
import com.jediterm.terminal.TextStyle;
import com.jediterm.terminal.emulator.ColorPalette;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Pins the {@link DarkSettingsProvider} colour-palette behaviour. The
 * palette previously swallowed ANSI indices 0..7 (returned the default
 * foreground for all of them), which made every index-form SGR sequence
 * render as the default colour — invisible to the user. cmd / PowerShell
 * happened to look correct only because ConPTY translates their Win32
 * console output to RGB-form SGR ({@code CSI 38;2;R;G;B m}), which
 * bypasses the indexed-palette lookup. MSYS2 bash writes raw bytes, so
 * its index-form SGR sequences went straight through the broken lookup
 * and showed no colour.
 */
class DarkSettingsProviderTest {
  private final ColorPalette palette = new DarkSettingsProvider().getTerminalColorPalette();

  /** Default foreground is #d4d4d4 (212, 212, 212). */
  private static final Color DEFAULT_FG = new Color(212, 212, 212);

  @Test
  void indexedForeground_chromaticColours_areNotDefaultForeground() {
    // 1=red, 2=green, 3=yellow, 4=blue, 5=magenta, 6=cyan — the colours
    // a user would expect to see when programs (ls, git status, etc.)
    // emit SGR sequences. Indices 0 (black) and 7 (white) are excluded:
    // the dark theme intentionally maps "white" to #d4d4d4 so it matches
    // the default foreground (no visible change for CSI 37 m, by design).
    for (int i = 1; i <= 6; i++) {
      Color c = palette.getForeground(TerminalColor.index(i));
      // The default foreground is #d4d4d4 (212, 212, 212). The tuned
      // chromatic colours must not be that exact shade — otherwise the
      // SGR colour change would be invisible (the original bug).
      assertNotEquals(DEFAULT_FG, c,
          "ANSI index " + i + " must not collapse to the default foreground");
    }
  }

  @Test
  void indexedForeground_redIsRedDominant() {
    // The original bug: index 1 (red) was overridden to DEFAULT_FG so
    // `echo -e "\e[31m..."` showed grey text instead of red.
    Color red = palette.getForeground(TerminalColor.index(1));
    assertNotEquals(DEFAULT_FG, red);
    int r = red.getRed(), g = red.getGreen(), b = red.getBlue();
    // Red-dominant: R channel strictly greater than G and B (no equal-channel grey/white).
    assertEquals(true, r > g && r > b,
        "ANSI 1 (red) must be red-dominant; got rgb(" + r + "," + g + "," + b + ")");
  }

  @Test
  void indexedForeground_brightColours_resolveFromPalette() {
    // 8=bright black, 9=bright red, ... 15=bright white. These were the
    // only path that worked before; assert they're still distinct from
    // DEFAULT_FG so we don't accidentally regress.
    for (int i = 8; i < 16; i++) {
      Color c = palette.getForeground(TerminalColor.index(i));
      assertNotEquals(DEFAULT_FG, c,
          "bright ANSI index " + i + " must not collapse to the default foreground");
    }
  }

  @Test
  void defaultStyle_foregroundIsRgbNotIndexed() {
    // JediTerm's UserSettingsProvider default is TextStyle(BLACK, WHITE)
    // where BLACK == index(0). That makes every unstyled character route
    // through the indexed palette and — after fixing the palette to be
    // honest — render as actual palette black. On a dark theme that's
    // invisible. DarkSettingsProvider overrides this to return an RGB
    // TerminalColor so the default foreground bypasses the indexed
    // lookup entirely.
    DarkSettingsProvider provider = new DarkSettingsProvider();
    TextStyle style = provider.getDefaultStyle();
    assertNotNull(style);
    TerminalColor fg = style.getForeground();
    assertNotNull(fg);
    assertFalse(fg.isIndexed(),
        "default style foreground must not be indexed (would render as "
            + "palette index 0 = black); got " + fg);
    // The resolved colour must match the theme's DEFAULT_FG.
    assertEquals(DEFAULT_FG, palette.getForeground(fg));
  }

  @Test
  void defaultStyle_backgroundIsRgbNotIndexed() {
    // Same reasoning as the foreground test — see comment above.
    DarkSettingsProvider provider = new DarkSettingsProvider();
    TerminalColor bg = provider.getDefaultStyle().getBackground();
    assertNotNull(bg);
    assertFalse(bg.isIndexed(),
        "default style background must not be indexed; got " + bg);
  }

  @Test
  void defaultForeground_andBackground_matchDefaultStyle() {
    // The newer getDefaultForeground / getDefaultBackground methods
    // should agree with the deprecated getDefaultStyle entry points,
    // so callers using either path see the same colours.
    DarkSettingsProvider provider = new DarkSettingsProvider();
    TextStyle style = provider.getDefaultStyle();
    assertEquals(style.getForeground(), provider.getDefaultForeground());
    assertEquals(style.getBackground(), provider.getDefaultBackground());
  }

  @Test
  void getTerminalFontSize_defaultsToAppSettingsDefault() {
    // Read-only assertion — tests that read this static should not
    // assume a particular value, only that whatever it is matches
    // AppSettings.DEFAULT_FONT_SIZE.
    assertEquals(AppSettings.DEFAULT_FONT_SIZE,
        DarkSettingsProvider.getConfiguredTerminalFontSize());
  }

  @Test
  void setTerminalFontSize_clampsAndExposesViaGetter() {
    int previous = DarkSettingsProvider.getConfiguredTerminalFontSize();
    try {
      DarkSettingsProvider.setTerminalFontSize(2);
      assertEquals(AppSettings.MIN_FONT_SIZE,
          DarkSettingsProvider.getConfiguredTerminalFontSize());
      DarkSettingsProvider.setTerminalFontSize(100);
      assertEquals(AppSettings.MAX_FONT_SIZE,
          DarkSettingsProvider.getConfiguredTerminalFontSize());
      DarkSettingsProvider.setTerminalFontSize(20);
      assertEquals(20, DarkSettingsProvider.getConfiguredTerminalFontSize());
    } finally {
      // Restore so other tests in this JVM aren't surprised.
      DarkSettingsProvider.setTerminalFontSize(previous);
    }
  }

  @Test
  void getTerminalFontSize_isHonouredByGetTerminalFont() {
    int previous = DarkSettingsProvider.getConfiguredTerminalFontSize();
    try {
      DarkSettingsProvider.setTerminalFontSize(24);
      DarkSettingsProvider provider = new DarkSettingsProvider();
      assertEquals(24, provider.getTerminalFont().getSize());
      assertEquals(24f, provider.getTerminalFontSize(), 0.0001f);
    } finally {
      DarkSettingsProvider.setTerminalFontSize(previous);
    }
  }
}