package local.term;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GlobMatcherTest {
  @Test
  void star_matchesPrefixSuffixAndEmpty() {
    GlobMatcher m = GlobMatcher.compile("*foo*");
    assertTrue(m.matches("the foo bar"));
    assertTrue(m.matches("foo"));
    assertFalse(m.matches("bar baz"));
  }

  @Test
  void question_matchesExactlyOneChar_notZero_notMany() {
    GlobMatcher m = GlobMatcher.compile("?og");
    assertTrue(m.matches("dog"));
    assertTrue(m.matches("log"));
    assertFalse(m.matches("og"));        // 0 chars before 'og'
    assertFalse(m.matches("frog"));      // 2 chars before 'og'
  }

  @Test
  void mixedStarAndQuestion_combined() {
    GlobMatcher m = GlobMatcher.compile("h?st*.log");
    assertTrue(m.matches("host01.log"));
    assertTrue(m.matches("hast_segment_42.log"));
    assertFalse(m.matches("hst.log"));   // '?' missing
    assertFalse(m.matches("host"));      // suffix missing
  }

  @Test
  void literalOnly_autoWrappedToContains_matchesAnywhereOnLine() {
    // No '*' or '?' in the user pattern → compiled as '*welcome*'.
    // Case-insensitive to match Windows PathMatcher behavior (the underlying
    // filesystem is case-insensitive there).
    GlobMatcher m = GlobMatcher.compile("welcome");
    assertTrue(m.matches("Welcome to Linux"));
    assertTrue(m.matches("hi and welcome friend"));
    assertFalse(m.matches("goodbye"));
  }

  @Test
  void patternAlreadyWithGlob_remainsAnchored_doesNotGetExtraStars() {
    // '*' present → not auto-wrapped; '*Password*' is the literal pattern.
    GlobMatcher m = GlobMatcher.compile("*Password*");
    assertTrue(m.matches("Password:"));
    assertTrue(m.matches("Enter Password for user:"));
    assertFalse(m.matches("pass"));      // anchored: 'Password' must appear
  }

  @Test
  void emptyPattern_autoWrappedToDoubleStar_matchesAnything() {
    // Spec note: callers should treat blank as "skip wait entirely", but we
    // still verify the matcher compiles and is not pathological.
    GlobMatcher m = GlobMatcher.compile("");
    assertTrue(m.matches("anything"));
    assertTrue(m.matches(""));
  }
}