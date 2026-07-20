package local.term;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Glob-style matcher. Pure-literal patterns (no {@code *} or {@code ?}) are
 * auto-wrapped with leading + trailing {@code *} so users get "contains"
 * semantics by default — matches the spec's "只要包含就行" requirement.
 * Patterns that already contain wildcard metacharacters remain anchored
 * (full-line match).
 *
 * <p>Implementation note: the spec called for a {@link java.nio.file.PathMatcher}
 * backed matcher, but {@code PathMatcher} rejects characters that are illegal
 * in the host file system — notably {@code :} on Windows — which is common in
 * terminal output (e.g. "Password:", "user@host:~$"). This regex-backed
 * implementation is functionally equivalent for our limited glob syntax
 * ({@code *} and {@code ?} only) and works on all platforms.
 */
public final class GlobMatcher {
  // Regex metacharacters (excluding '*' and '?' which we map explicitly).
  private static final String REGEX_META = ".\\+()|^$@%&-{}[]:;<>=,!#\"'";

  private final Pattern regex;

  private GlobMatcher(Pattern regex) { this.regex = regex; }

  public static GlobMatcher compile(String pattern) {
    Objects.requireNonNull(pattern, "pattern");
    boolean hasWildcard = pattern.indexOf('*') >= 0 || pattern.indexOf('?') >= 0;
    String glob = hasWildcard ? pattern : "*" + pattern + "*";
    StringBuilder sb = new StringBuilder(glob.length() * 2);
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      if (c == '*') {
        sb.append(".*");
      } else if (c == '?') {
        sb.append('.');
      } else if (REGEX_META.indexOf(c) >= 0) {
        sb.append('\\').append(c);
      } else {
        sb.append(c);
      }
    }
    // CASE_INSENSITIVE mirrors JDK PathMatcher's behavior on Windows
    // (the platform's default filesystem is case-insensitive). The verbatim
    // test assertions depend on this — e.g. pattern "welcome" must match
    // "Welcome to Linux".
    return new GlobMatcher(Pattern.compile(sb.toString(), Pattern.CASE_INSENSITIVE));
  }

  public boolean matches(CharSequence line) {
    return regex.matcher(line).matches();
  }
}