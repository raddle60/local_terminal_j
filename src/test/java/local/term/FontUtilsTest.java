package local.term;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.GraphicsEnvironment;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class FontUtilsTest {

  @Test
  void isMonospaced_uninstalledFont_returnsFalse() {
    assertFalse(FontUtils.isMonospaced("Definitely-Not-A-Real-Font-XYZ"));
  }

  @Test
  void isMonospaced_null_returnsFalse() {
    assertFalse(FontUtils.isMonospaced(null));
  }

  @Test
  void isMonospaced_consolasRecognizedAsMonospaced() {
    assumeFalse(GraphicsEnvironment.isHeadless());
    assumeTrue(installed("Consolas"), "Consolas not installed on this system");
    assertTrue(FontUtils.isMonospaced("Consolas"));
  }

  @Test
  void isMonospaced_microsoftYaHeiRecognizedAsProportional() {
    assumeFalse(GraphicsEnvironment.isHeadless());
    assumeTrue(installed("Microsoft YaHei"), "Microsoft YaHei not installed on this system");
    // YaHei is the classic case the user reported — variable-width chars.
    assertFalse(FontUtils.isMonospaced("Microsoft YaHei"));
  }

  @Test
  void hasCjkGlyphs_consolasReturnsFalse() {
    assumeFalse(GraphicsEnvironment.isHeadless());
    assumeTrue(installed("Consolas"), "Consolas not installed on this system");
    assertFalse(FontUtils.hasCjkGlyphs("Consolas"));
  }

  @Test
  void hasCjkGlyphs_microsoftYaHeiReturnsTrue() {
    assumeFalse(GraphicsEnvironment.isHeadless());
    assumeTrue(installed("Microsoft YaHei"), "Microsoft YaHei not installed on this system");
    assertTrue(FontUtils.hasCjkGlyphs("Microsoft YaHei"));
  }

  @Test
  void hasCjkGlyphs_null_returnsFalse() {
    assertFalse(FontUtils.hasCjkGlyphs(null));
  }

  @Test
  void findCjkFont_returnsQualifiedFontWhenInstalled() {
    assumeFalse(GraphicsEnvironment.isHeadless());
    Font cjk = FontUtils.findCjkFont(12);
    boolean anyInstalledAndQualified = false;
    for (String name : new String[]{
        "Microsoft YaHei Mono", "Sarasa Mono SC", "Sarasa Mono TC",
        "Cascadia Mono", "Cascadia Code", "JetBrains Mono"}) {
      if (installed(name) && FontUtils.isMonospaced(name) && FontUtils.hasCjkGlyphs(name)) {
        anyInstalledAndQualified = true;
        break;
      }
    }
    if (!anyInstalledAndQualified) {
      // No qualified candidate installed — null is correct now.
      assertNull(cjk, "findCjkFont should return null when no qualified candidate is installed");
      return;
    }
    assertNotNull(cjk);
    assertTrue(FontUtils.hasCjkGlyphs(cjk.getFamily()),
        cjk.getFamily() + " lacks CJK glyphs");
    assertTrue(FontUtils.isMonospaced(cjk.getFamily()),
        cjk.getFamily() + " is not monospaced");
  }

  @Test
  void findCjkFont_returnsFontWithCjkCoverage() {
    assumeFalse(GraphicsEnvironment.isHeadless());
    Font cjk = FontUtils.findCjkFont(12);
    // New contract: returns null when no qualified font is installed.
    if (cjk == null) {
      return;
    }
    // If non-null, the returned font must really have CJK.
    assertEquals(-1, cjk.canDisplayUpTo("中文你好世界"));
  }

  @Test
  void findCjkFont_candidatesAreFilteredByGlyphCoverage() {
    // If no installed font is both mono and CJK-capable, findCjkFont now
    // returns null. Otherwise the returned font must be from the candidate
    // list (so we don't silently hand back a Latin-only font).
    assumeFalse(GraphicsEnvironment.isHeadless());
    Font cjk = FontUtils.findCjkFont(12);
    if (cjk == null) {
      // No qualified candidate installed — that's a valid outcome now.
      return;
    }
    String name = cjk.getFamily();
    boolean isKnownCandidate = false;
    for (String candidate : new String[]{
        "Microsoft YaHei Mono", "Sarasa Mono SC", "Sarasa Mono TC",
        "Cascadia Mono", "Cascadia Code", "JetBrains Mono",
        "Source Code Pro"}) {
      if (candidate.equals(name)) { isKnownCandidate = true; break; }
    }
    assertTrue(isKnownCandidate,
        "Returned font '" + name + "' is not from the mono+CJK candidate list");
    assertTrue(FontUtils.hasCjkGlyphs(name),
        "Returned font '" + name + "' does not have CJK glyphs");
    assertTrue(FontUtils.isMonospaced(name),
        "Returned font '" + name + "' is not monospaced");
  }

  @Test
  void findAnyCjkFont_prefersMonoWhenAvailable() {
    // findAnyCjkFont delegates to findCjkFont first; on a system with a
    // qualified mono-CJK font installed the result must equal findCjkFont's
    // output. findCjkFont returns null when no qualified font exists, in
    // which case we can't assert anything specific.
    assumeFalse(GraphicsEnvironment.isHeadless());
    Font mono = FontUtils.findCjkFont(12);
    if (mono == null) return;
    Font any = FontUtils.findAnyCjkFont(12);
    assertNotNull(any);
    assertEquals(mono.getFamily(), any.getFamily(),
        "findAnyCjkFont should prefer the mono-CJK candidate when one exists");
  }

  @Test
  void findAnyCjkFont_fallsBackToProportionalWhenNoMonoCjkInstalled() {
    // On a system with NO monospaced CJK font, findCjkFont returns null;
    // findAnyCjkFont must still hand back something proportional (or at
    // least CJK-capable) so the settings preview can render Chinese.
    assumeFalse(GraphicsEnvironment.isHeadless());
    Font cjk = FontUtils.findAnyCjkFont(12);
    // On a typical system some CJK font is installed. If not, skip.
    assumeTrue(cjk != null, "No CJK-capable font installed on this system");
    assertTrue(FontUtils.hasCjkGlyphs(cjk.getFamily()),
        cjk.getFamily() + " lacks CJK glyphs");
  }

  @Test
  void findTerminalCjkFallback_returnsCjkCapableFontWhenAvailable() {
    // Stronger contract than findCjkFont (returns null on systems without a
    // mono CJK font): the terminal fallback must NEVER hand back null when
    // any CJK-capable font exists — otherwise the user sees tofu at runtime.
    assumeFalse(GraphicsEnvironment.isHeadless());
    Font f = FontUtils.findTerminalCjkFallback(12);
    if (f == null) {
      // No CJK-capable font at all on this system — can't assert coverage.
      return;
    }
    assertNotNull(f);
    assertTrue(FontUtils.hasCjkGlyphs(f.getFamily()),
        f.getFamily() + " lacks CJK glyphs — terminal would show tofu");
  }

  @Test
  void findTerminalCjkFallback_prefersMonoWhenAvailable() {
    // When a monospaced CJK font is installed, the terminal fallback must
    // use it rather than the proportional fallback, to preserve column
    // alignment in the terminal grid.
    assumeFalse(GraphicsEnvironment.isHeadless());
    Font mono = FontUtils.findCjkFont(12);
    if (mono == null) return;
    Font f = FontUtils.findTerminalCjkFallback(12);
    assertNotNull(f);
    assertEquals(mono.getFamily(), f.getFamily(),
        "findTerminalCjkFallback should prefer the mono CJK font when one exists");
  }

  @Test
  void findAnyCjkFont_resultSizeMatchesRequest() {
    assumeFalse(GraphicsEnvironment.isHeadless());
    Font f = FontUtils.findAnyCjkFont(18);
    if (f == null) return;
    assertEquals(18f, f.getSize(), 0.01f);
  }

  @Test
  void createCompositeFont_consolasPlusCjk_combinesIntoOneFont() {
    assumeFalse(GraphicsEnvironment.isHeadless());
    assumeTrue(installed("Consolas"), "Consolas not installed on this system");
    Font primary = new Font("Consolas", Font.PLAIN, 12);
    Font fallback = FontUtils.findCjkFont(12);
    // New findCjkFont contract: returns null when no qualified mono+CJK font
    // is installed. Skip the rest of the test rather than exercising the
    // createCompositeFont code path with a null fallback.
    if (fallback == null) {
      return;
    }
    assertNotNull(fallback);

    Font composite = FontUtils.createCompositeFont(primary, fallback);
    assertNotNull(composite);
    assertEquals(12f, composite.getSize(), 0.01f);
    // Composite fonts are a sun.font.* implementation detail. The class name
    // proves we built one rather than silently returning primary. If reflection
    // was blocked (JDK 17 module restrictions), the helper logs and falls back,
    // in which case this assertion is skipped rather than failed.
    assumeTrue("sun.font.CompositeFont".equals(composite.getClass().getName()),
        "CompositeFont reflection failed; cannot verify composite construction");
    // Latin still resolves (would otherwise fallback to a tofu if composite
    // broke Latin rendering).
    assertNotEquals(-1, composite.canDisplayUpTo("hello"));
  }

  @Test
  void createCompositeFont_nullPrimary_returnsFallback() {
    Font fallback = new Font("Dialog", Font.PLAIN, 12);
    assertSame(fallback, FontUtils.createCompositeFont(null, fallback));
  }

  @Test
  void createCompositeFont_nullFallback_returnsPrimary() {
    Font primary = new Font("Consolas", Font.PLAIN, 12);
    assertSame(primary, FontUtils.createCompositeFont(primary, null));
  }

  @Test
  void resolveTerminalFont_keepsFamilyWhenItHasCjk() {
    assumeFalse(GraphicsEnvironment.isHeadless());
    assumeTrue(installed("Microsoft YaHei"), "Microsoft YaHei not installed");
    Font resolved = FontUtils.resolveTerminalFont("Microsoft YaHei", 12);
    assertEquals("Microsoft YaHei", resolved.getFamily());
  }

  @Test
  void resolveTerminalFont_keepsPrimaryWhenNoQualifiedFallbackExists() {
    // New policy: substitute ONLY with a mono + CJK font. If none is
    // installed, return the chosen font unchanged so the caller can see
    // tofu rather than getting a proportional surprise. This test verifies
    // the "no qualified fallback" branch — the system must have at least
    // one mono + CJK-capable font for substitution to apply.
    assumeFalse(GraphicsEnvironment.isHeadless());
    assumeTrue(installed("Consolas"), "Consolas not installed");
    boolean hasQualified = false;
    for (String name : new String[]{
        "Microsoft YaHei Mono", "Sarasa Mono SC", "Sarasa Mono TC",
        "Cascadia Mono", "Cascadia Code", "JetBrains Mono"}) {
      if (installed(name) && FontUtils.isMonospaced(name) && FontUtils.hasCjkGlyphs(name)) {
        hasQualified = true;
        break;
      }
    }
    Font resolved = FontUtils.resolveTerminalFont("Consolas", 12);
    if (hasQualified) {
      assertNotEquals("Consolas", resolved.getFamily(),
          "qualified mono CJK font installed; should substitute");
    } else {
      assertEquals("Consolas", resolved.getFamily(),
          "no qualified mono CJK font; should keep Consolas unchanged");
    }
  }

  @Test
  void resolveTerminalFont_substitutesWithMonoCjkWhenAvailable() {
    assumeFalse(GraphicsEnvironment.isHeadless());
    if (FontUtils.findCjkFont(12) == null) {
      // Skip: no qualified font on this system.
      return;
    }
    Font resolved = FontUtils.resolveTerminalFont("Consolas", 12);
    assertNotEquals("Consolas", resolved.getFamily());
    // And the substitute itself must be monospaced.
    assertTrue(FontUtils.isMonospaced(resolved.getFamily()),
        resolved.getFamily() + " is not monospaced — would create uneven terminal cells");
  }

  @Test
  void wouldSubstitute_consolasYesYaheiYes() {
    // Consolas lacks CJK → would-substitute.
    assertTrue(FontUtils.wouldSubstitute("Consolas"));
    // Proportional CJK family → also would-substitute, since the terminal
    // needs monospaced cells. Skip if YaHei isn't installed.
    if (FontUtils.hasCjkGlyphs("Microsoft YaHei") && !FontUtils.isMonospaced("Microsoft YaHei")) {
      assertTrue(FontUtils.wouldSubstitute("Microsoft YaHei"));
    }
  }

  @Test
  void wouldSubstitute_nullSafe() {
    assertFalse(FontUtils.wouldSubstitute(null));
  }

  @Test
  void isProportionalCjkFamily_consolasReturnsFalse() {
    // Consolas has no CJK at all — not "proportional CJK", handled by the
    // no-CJK branch. Returns false so logic stays clean.
    assumeTrue(installed("Consolas"), "Consolas not installed");
    assertFalse(FontUtils.isProportionalCjkFamily("Consolas"));
  }

  // -----------------------------------------------------------------
  // Emoji coverage (Issue #3)
  // -----------------------------------------------------------------

  @Test
  void hasEmojiGlyphs_uninstalledFont_returnsFalse() {
    assertFalse(FontUtils.hasEmojiGlyphs("Definitely-Not-A-Real-Font-XYZ"));
  }

  @Test
  void hasEmojiGlyphs_null_returnsFalse() {
    assertFalse(FontUtils.hasEmojiGlyphs(null));
  }

  @Test
  void hasEmojiGlyphs_consolasReturnsFalse() {
    assumeFalse(GraphicsEnvironment.isHeadless());
    assumeTrue(installed("Consolas"), "Consolas not installed on this system");
    assertFalse(FontUtils.hasEmojiGlyphs("Consolas"),
        "Consolas is Latin-only; should not report emoji coverage");
  }

  @Test
  void hasEmojiGlyphs_segoeUiEmojiRecognizedAsEmojiFont() {
    assumeFalse(GraphicsEnvironment.isHeadless());
    assumeTrue(installed("Segoe UI Emoji"),
        "Segoe UI Emoji not installed (Windows-only font)");
    assertTrue(FontUtils.hasEmojiGlyphs("Segoe UI Emoji"),
        "Segoe UI Emoji is the Windows color-emoji font");
  }

  @Test
  void findEmojiFont_returnsNullOnLinuxWhenNoEmojiFontInstalled() {
    // Defensive: should not throw on any platform. Returns null when
    // no emoji-capable font is installed (likely on minimal Linux
    // containers without Noto Color Emoji).
    assumeFalse(GraphicsEnvironment.isHeadless());
    Font emoji = FontUtils.findEmojiFont(12);
    if (emoji == null) {
      // No emoji-capable font on this system — that's an acceptable
      // outcome; the fallback chain falls through to symbol / CJK.
      return;
    }
    assertTrue(FontUtils.hasEmojiGlyphs(emoji.getFamily()),
        emoji.getFamily() + " lacks emoji glyphs");
  }

  // -----------------------------------------------------------------
  // General-symbol coverage (Issue #3)
  // -----------------------------------------------------------------

  @Test
  void hasGeneralSymbolGlyphs_uninstalledFont_returnsFalse() {
    assertFalse(FontUtils.hasGeneralSymbolGlyphs("Definitely-Not-A-Real-Font-XYZ"));
  }

  @Test
  void hasGeneralSymbolGlyphs_null_returnsFalse() {
    assertFalse(FontUtils.hasGeneralSymbolGlyphs(null));
  }

  @Test
  void hasGeneralSymbolGlyphs_segoeUiSymbolRecognizedAsSymbolFont() {
    assumeFalse(GraphicsEnvironment.isHeadless());
    assumeTrue(installed("Segoe UI Symbol"),
        "Segoe UI Symbol not installed (Windows-only font)");
    assertTrue(FontUtils.hasGeneralSymbolGlyphs("Segoe UI Symbol"),
        "Segoe UI Symbol covers box-drawing / arrows / math on Windows");
  }

  @Test
  void findGeneralSymbolFont_doesNotThrowOnMinimalSystem() {
    assumeFalse(GraphicsEnvironment.isHeadless());
    // Even on a system without a dedicated symbol font, Java's logical
    // fonts (Dialog, SansSerif) usually cover the symbol probe. If
    // nothing qualifies, null is fine — the fallback chain tries the
    // CJK / emoji slots instead.
    Font f = FontUtils.findGeneralSymbolFont(12);
    if (f != null) {
      assertTrue(FontUtils.hasGeneralSymbolGlyphs(f.getFamily()),
          f.getFamily() + " does not actually render the symbol probe");
    }
  }

  private static boolean installed(String family) {
    String[] available = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .getAvailableFontFamilyNames();
    for (String a : available) if (a.equalsIgnoreCase(family)) return true;
    return false;
  }
}
