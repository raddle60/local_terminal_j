package local.term;

import org.junit.jupiter.api.Test;

import java.awt.Font;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link CompositeFontLabel}. Most of the render logic is
 * covered by inspection of the code (per-run segmentation) plus the live
 * Settings dialog UI check; here we focus on the public API contract:
 *
 * <ul>
 *   <li>setCjkFallback(null) disables fallback (degrades to plain JLabel).</li>
 *   <li>setFont resyncs the fallback's size so the label stays single-line.</li>
 *   <li>The label's primary font and fallback are independently settable.</li>
 * </ul>
 */
class CompositeFontLabelTest {

  @Test
  void initialCjkFallbackIsNull() {
    CompositeFontLabel label = new CompositeFontLabel("Sample: 你好 abcdefg");
    assertNull(label.getCjkFallback(),
        "Default fallback should be null — caller opts in per preview.");
  }

  @Test
  void setCjkFallback_storesAndExposesFallback() {
    CompositeFontLabel label = new CompositeFontLabel("Sample: 你好");
    Font cjk = new Font("Dialog", Font.PLAIN, 14);
    label.setCjkFallback(cjk);
    assertSame(cjk, label.getCjkFallback());
  }

  @Test
  void setCjkFallback_nullDisablesFallback() {
    CompositeFontLabel label = new CompositeFontLabel("Sample: 你好");
    label.setCjkFallback(new Font("Dialog", Font.PLAIN, 14));
    assertNotNull(label.getCjkFallback());
    label.setCjkFallback(null);
    assertNull(label.getCjkFallback(),
        "Setting null must disable fallback rendering.");
  }

  @Test
  void setFont_resyncsFallbackSizeToPrimarySize() {
    // Preview size grows from 14 → 20; the CJK fallback must follow so
    // both runs render at the same height and the label stays on one line.
    CompositeFontLabel label = new CompositeFontLabel("X");
    label.setFont(new Font("Dialog", Font.PLAIN, 14));
    label.setCjkFallback(new Font("Dialog", Font.PLAIN, 10));
    assertEquals(10f, label.getCjkFallback().getSize(), 0.01f);

    label.setFont(new Font("Dialog", Font.PLAIN, 20));
    assertEquals(20f, label.getCjkFallback().getSize(), 0.01f,
        "Setting primary font must resize fallback to match.");
  }

  @Test
  void setFont_nullPrimaryDoesNotNpeFallbackReset() {
    // Setting a null font should be tolerated; we don't crash, and we
    // don't try to deriveFont from a null primary.
    CompositeFontLabel label = new CompositeFontLabel("X");
    Font cjk = new Font("Dialog", Font.PLAIN, 14);
    label.setCjkFallback(cjk);
    assertDoesNotThrow(() -> label.setFont(null));
    assertSame(cjk, label.getCjkFallback(),
        "Null primary must leave the fallback reference intact.");
  }

  @Test
  void textProperty_isPreserved() {
    CompositeFontLabel label = new CompositeFontLabel("Sample: 你好 abcdefg 0123 ▓▒░");
    assertEquals("Sample: 你好 abcdefg 0123 ▓▒░", label.getText());
  }

  @Test
  void addFallbackFont_appendsToChain() {
    CompositeFontLabel label = new CompositeFontLabel("X");
    // Set primary first so addFallbackFont's size-sync is a no-op and
    // the same Font instances are stored.
    label.setFont(new Font("Dialog", Font.PLAIN, 14));
    Font a = new Font("Dialog", Font.PLAIN, 14);
    Font b = new Font("SansSerif", Font.PLAIN, 14);
    label.addFallbackFont(a);
    label.addFallbackFont(b);
    assertEquals(2, label.getFallbackFonts().size());
    assertSame(a, label.getFallbackFonts().get(0));
    assertSame(b, label.getFallbackFonts().get(1));
  }

  @Test
  void addFallbackFont_nullIsIgnored() {
    CompositeFontLabel label = new CompositeFontLabel("X");
    label.addFallbackFont(null);
    assertTrue(label.getFallbackFonts().isEmpty(),
        "Null fonts must not be added to the chain");
  }

  @Test
  void addFallbackFont_resyncsSizeToPrimary() {
    // Adding a fallback whose size differs from the primary must
    // resize it so the label stays single-line.
    CompositeFontLabel label = new CompositeFontLabel("X");
    label.setFont(new Font("Dialog", Font.PLAIN, 20));
    label.addFallbackFont(new Font("Dialog", Font.PLAIN, 10));
    assertEquals(20f, label.getFallbackFonts().get(0).getSize(), 0.01f,
        "addFallbackFont must resize the added font to match the primary");
  }

  @Test
  void setFallbackFonts_replacesChain() {
    CompositeFontLabel label = new CompositeFontLabel("X");
    label.setFont(new Font("Dialog", Font.PLAIN, 14));
    label.setCjkFallback(new Font("Dialog", Font.PLAIN, 14));
    assertEquals(1, label.getFallbackFonts().size());

    Font a = new Font("Dialog", Font.PLAIN, 14);
    Font b = new Font("SansSerif", Font.PLAIN, 14);
    label.setFallbackFonts(java.util.Arrays.asList(a, b));
    assertEquals(2, label.getFallbackFonts().size());
    assertSame(a, label.getFallbackFonts().get(0));
    assertSame(b, label.getFallbackFonts().get(1));
  }

  @Test
  void setCjkFallback_replacesExistingChain() {
    // Backward compat: setCjkFallback must replace (not append) any
    // existing chain.
    CompositeFontLabel label = new CompositeFontLabel("X");
    label.setFont(new Font("Dialog", Font.PLAIN, 14));
    label.addFallbackFont(new Font("Dialog", Font.PLAIN, 14));
    label.addFallbackFont(new Font("SansSerif", Font.PLAIN, 14));
    assertEquals(2, label.getFallbackFonts().size());

    Font cjk = new Font("Dialog", Font.PLAIN, 14);
    label.setCjkFallback(cjk);
    assertEquals(1, label.getFallbackFonts().size());
    assertSame(cjk, label.getFallbackFonts().get(0));
  }
}
