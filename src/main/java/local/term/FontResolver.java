package local.term;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Font;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Resolves a fallback font for a text run from a chain of candidate fonts.
 *
 * <p>{@link CompositeFontPanel} and {@link CompositeFontLabel} call
 * {@link #resolve(char[], int, int)} once per non-ASCII run and render the
 * whole run with whichever fallback in the chain reports
 * {@code canDisplayUpTo(...) == -1} first. This lets a single chain cover
 * CJK (Microsoft YaHei Mono / Sarasa Mono), color emoji (Segoe UI Emoji /
 * Noto Color Emoji), and general symbols / box-drawing (Segoe UI Symbol /
 * DejaVu Sans) so Claude Code output like 🚀 ✨ ┌──┐ π ∑ all render with
 * real glyphs instead of tofu (□).
 *
 * <p>Fallbacks are tried in the order supplied; the first one that can
 * render every char in the run wins. If none can render the whole run,
 * the first non-null fallback is returned so the renderer still has a
 * font to use (the user will see partial glyphs, never a crash).
 *
 * <p>Bold / italic variants are derived by callers from the resolved
 * fallback via {@link Font#deriveFont(int)}; the resolver only deals
 * with the plain-style chain.
 */
public final class FontResolver {
  private static final Logger LOG = LoggerFactory.getLogger(FontResolver.class);

  /** Ordered list of candidate fonts (nulls allowed and skipped). */
  private final List<Font> fallbacks;

  public FontResolver(Font... fallbacks) {
    this(fallbacks == null ? Collections.emptyList() : Arrays.asList(fallbacks));
  }

  public FontResolver(List<Font> fallbacks) {
    if (fallbacks == null) {
      this.fallbacks = Collections.emptyList();
    } else {
      List<Font> copy = new ArrayList<>(fallbacks.size());
      for (Font f : fallbacks) if (f != null) copy.add(f);
      this.fallbacks = Collections.unmodifiableList(copy);
    }
    if (this.fallbacks.isEmpty()) {
      LOG.debug("FontResolver constructed with no fallbacks; resolve() will return null");
    } else {
      LOG.debug("FontResolver constructed with {} fallbacks: {}",
          this.fallbacks.size(),
          this.fallbacks.stream().map(f -> f.getFamily()).reduce((a, b) -> a + "," + b).orElse(""));
    }
  }

  /**
   * @return an immutable view of the configured fallbacks (nulls removed).
   */
  public List<Font> fallbacks() {
    return fallbacks;
  }

  /**
   * True when at least one fallback was supplied.
   */
  public boolean hasFallback() {
    return !fallbacks.isEmpty();
  }

  /**
   * Pick the first fallback that can render every char in {@code text[start..end)}.
   *
   * <p>If no fallback can render the whole run, returns the first fallback
   * (so the renderer still has something to use). Returns {@code null} only
   * when the resolver was constructed with no fallbacks at all.
   *
   * <p>Iteration order matches the constructor order, so callers control
   * preference by ordering (e.g. CJK before emoji so a mixed
   * "中文🚀" run prefers the CJK font — it usually covers the CJK half
   * AND can render the emoji glyph, but if it can't, the emoji font
   * is tried next).
   */
  public Font resolve(char[] text, int start, int end) {
    if (fallbacks.isEmpty()) return null;
    Font first = fallbacks.get(0);
    for (Font f : fallbacks) {
      if (f.canDisplayUpTo(text, start, end) == -1) {
        return f;
      }
    }
    return first;
  }

  /**
   * Convenience overload: resolve for an entire char array.
   */
  public Font resolve(char[] text) {
    return resolve(text, 0, text.length);
  }

  /**
   * Convenience overload: resolve for a String run.
   */
  public Font resolve(String run) {
    char[] chars = run.toCharArray();
    return resolve(chars, 0, chars.length);
  }
}