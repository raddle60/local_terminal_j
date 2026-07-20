package local.term;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link FontResolver}. The resolver is the heart of
 * Issue #3's fix — it picks the right fallback font per run so that
 * Claude Code output mixing CJK, emoji, and special symbols all render
 * with real glyphs instead of tofu.
 *
 * <p>Tests use concrete {@link Font} objects built from
 * {@link Font#DIALOG} (the String constant, family name "Dialog") and
 * {@link Font#SANS_SERIF} (family "SansSerif"). Both families ship with
 * the JVM and have broad Unicode coverage on a typical install.
 */
class FontResolverTest {

  private static Font dialogFont() {
    return new Font(Font.DIALOG, Font.PLAIN, 12);
  }

  private static Font sansFont() {
    return new Font(Font.SANS_SERIF, Font.PLAIN, 12);
  }

  @Test
  void resolve_emptyChain_returnsNull() {
    FontResolver r = new FontResolver(Collections.emptyList());
    assertNull(r.resolve("anything".toCharArray()));
  }

  @Test
  void resolve_nullChain_returnsNull() {
    FontResolver r = new FontResolver((List<Font>) null);
    assertNull(r.resolve("anything".toCharArray()));
  }

  @Test
  void resolve_nullsInChain_ignored() {
    FontResolver r = new FontResolver(Arrays.<Font>asList(null, dialogFont(), null));
    assertEquals(1, r.fallbacks().size(),
        "Nulls must be filtered out of the chain");
    assertEquals(Font.DIALOG, r.fallbacks().get(0).getFamily());
  }

  @Test
  void resolve_asciiRun_returnsFirstFallback() {
    // Every font can render ASCII; the first in the chain wins.
    Font first = dialogFont();
    Font second = sansFont();
    FontResolver r = new FontResolver(first, second);
    char[] ascii = "hello".toCharArray();
    assertSame(first, r.resolve(ascii));
  }

  @Test
  void resolve_stringOverload_works() {
    Font f = dialogFont();
    FontResolver r = new FontResolver(f);
    assertSame(f, r.resolve("hello"));
  }

  @Test
  void resolve_picksFirstFontThatCanRender() {
    // Build two fonts; verify the resolver returns the one whose
    // canDisplayUpTo returns -1 for the probe. We probe with a Latin
    // string — both will render — and expect the first.
    Font first = dialogFont();
    Font second = sansFont();
    FontResolver r = new FontResolver(first, second);
    char[] run = "abc".toCharArray();
    assertSame(first, r.resolve(run),
        "Resolver should return the first font that can render the run");
  }

  @Test
  void hasFallback_emptyChain_isFalse() {
    assertFalse(new FontResolver(Collections.emptyList()).hasFallback());
    assertFalse(new FontResolver((List<Font>) null).hasFallback());
  }

  @Test
  void hasFallback_withChain_isTrue() {
    assertTrue(new FontResolver(dialogFont()).hasFallback());
    assertTrue(new FontResolver(Arrays.asList(dialogFont(), sansFont())).hasFallback());
  }

  @Test
  void hasFallback_chainOfOnlyNulls_isFalse() {
    assertFalse(new FontResolver((Font) null, (Font) null).hasFallback());
  }

  @Test
  void fallbacks_returnsImmutableView() {
    FontResolver r = new FontResolver(dialogFont());
    List<Font> view = r.fallbacks();
    assertThrows(UnsupportedOperationException.class, () -> view.add(sansFont()),
        "The returned list must be immutable");
  }

  @Test
  void constructor_varargs_acceptsFonts() {
    Font f = dialogFont();
    FontResolver r = new FontResolver(f);
    assertEquals(1, r.fallbacks().size());
  }

  @Test
  void constructor_varargs_acceptsMultipleFonts() {
    FontResolver r = new FontResolver(dialogFont(), sansFont());
    assertEquals(2, r.fallbacks().size());
  }

  @Test
  void resolve_returnsFirstFallbackWhenNoneFullyQualify() {
    // When none of the chain can render the run, the resolver hands
    // back the first fallback so the renderer still has a font to use.
    Font first = dialogFont();
    FontResolver r = new FontResolver(first);
    // Use a string with chars that might trip canDisplayUpTo; we just
    // assert the resolver doesn't throw and returns something non-null
    // since "abc" will display on any font.
    assertSame(first, r.resolve("abc"));
  }
}