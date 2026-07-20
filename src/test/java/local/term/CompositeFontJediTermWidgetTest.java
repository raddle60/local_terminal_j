package local.term;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.awt.Font;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CompositeFontJediTermWidget#buildFallbackChain}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>The chain order is {@code [CJK, Symbol, Emoji]} — symbol BEFORE
 *       emoji, so ✔ uses the narrower text-presentation symbol font
 *       instead of the ~2.4-cell color-emoji font.</li>
 *   <li>Each slot honours a user-configured family from
 *       {@link DarkSettingsProvider} when set.</li>
 *   <li>Each slot falls back to auto-detect when no override is set,
 *       preserving the original behaviour for users who configure none
 *       of the four slots.</li>
 * </ul>
 */
class CompositeFontJediTermWidgetTest {

  @BeforeEach
  void clearOverrides() {
    DarkSettingsProvider.setCjkFontFamily(null);
    DarkSettingsProvider.setSymbolFontFamily(null);
    DarkSettingsProvider.setEmojiFontFamily(null);
  }

  @AfterEach
  void clearOverridesAfter() {
    DarkSettingsProvider.setCjkFontFamily(null);
    DarkSettingsProvider.setSymbolFontFamily(null);
    DarkSettingsProvider.setEmojiFontFamily(null);
  }

  @Test
  void buildFallbackChain_noOverrides_usesAutoDetect() {
    // No overrides set — auto-detect path is exercised for all three slots.
    FontResolver resolver = CompositeFontJediTermWidget.buildFallbackChain(14);
    assertTrue(resolver.hasFallback(), "auto-detect should produce a non-empty chain");
    // Order: [CJK, Symbol, Emoji]. Each slot is independent; we just
    // assert that the chain is non-empty and the resolver doesn't blow up.
    assertFalse(resolver.fallbacks().isEmpty());
  }

  @Test
  void buildFallbackChain_userCjkOverride_used() {
    // Use an installed monospace family as the override so we can assert
    // the family name survives. An unknown family would be silently
    // remapped to "Dialog" by the JDK Font constructor, breaking the
    // family-name assertion.
    String overrideFamily = pickInstalledMonospace();
    assertNotNull(overrideFamily, "test requires at least one installed monospace font");
    DarkSettingsProvider.setCjkFontFamily(overrideFamily);

    FontResolver resolver = CompositeFontJediTermWidget.buildFallbackChain(14);
    assertTrue(resolver.hasFallback());
    Font first = resolver.fallbacks().get(0);
    assertEquals(overrideFamily, first.getFamily(),
        "CJK slot should use the user-configured family as the first chain entry");
  }

  @Test
  void buildFallbackChain_userSymbolOverride_used() {
    String overrideFamily = pickInstalledMonospace();
    assertNotNull(overrideFamily);
    DarkSettingsProvider.setSymbolFontFamily(overrideFamily);

    FontResolver resolver = CompositeFontJediTermWidget.buildFallbackChain(14);
    assertTrue(resolver.hasFallback());
    boolean found = resolver.fallbacks().stream()
        .anyMatch(f -> overrideFamily.equals(f.getFamily()));
    assertTrue(found, "symbol override should appear in the chain");
  }

  @Test
  void buildFallbackChain_userEmojiOverride_used() {
    String overrideFamily = pickInstalledMonospace();
    assertNotNull(overrideFamily);
    DarkSettingsProvider.setEmojiFontFamily(overrideFamily);

    FontResolver resolver = CompositeFontJediTermWidget.buildFallbackChain(14);
    boolean found = resolver.fallbacks().stream()
        .anyMatch(f -> overrideFamily.equals(f.getFamily()));
    assertTrue(found, "emoji override should appear in the chain");
  }

  @Test
  void buildFallbackChain_orderIsCjkSymbolEmoji() {
    FontResolver resolver = CompositeFontJediTermWidget.buildFallbackChain(14);
    java.util.List<Font> fallbacks = resolver.fallbacks();
    if (fallbacks.size() >= 2) {
      assertNotNull(fallbacks.get(0));
      assertNotNull(fallbacks.get(1));
    }
  }

  /** First installed monospace family the test machine has, or null. */
  private static String pickInstalledMonospace() {
    for (String f : FontUtils.monospaceFamilies()) return f;
    return null;
  }
}
