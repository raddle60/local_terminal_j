package local.term;

import java.util.Locale;

/**
 * Substring-based shell-name matcher. Returns the icon-bucket name
 * (e.g. {@code "bash"}, {@code "powershell"}) for the default icon, or
 * {@code null} when no rule applies.
 *
 * <p>Rule order matches the sibling TypeScript project
 * ({@code local_terminal/src/renderer/components/ProfileTree.ts}) so
 * the two apps stay visually consistent.
 *
 * <p><b>Order matters.</b> The final {@code sh} fallback is intentionally
 * last because {@code sh} is a substring of {@code bash}, {@code zsh},
 * {@code fish}, {@code powershell}, {@code pwsh}, {@code ksh}, {@code tcsh},
 * {@code xonsh}, and {@code nushell}. Reordering the rules breaks the
 * more-specific names that appear earlier in the chain.
 */
public final class ShellNameExtractor {
  private ShellNameExtractor() {}

  public static String extract(String shellPath) {
    if (shellPath == null) return null;
    // Locale.ROOT for locale-independent case folding (avoids the
    // Turkish "I → ı" gotcha on JVMs with a Turkish default locale).
    String s = shellPath.toLowerCase(Locale.ROOT);
    if (s.contains("bash"))  return "bash";
    if (s.contains("zsh"))   return "zsh";
    if (s.contains("fish"))  return "fish";
    if (s.contains("powershell") || s.contains("pwsh")) return "powershell";
    if (s.contains("cmd"))   return "cmd";
    if (s.contains("dash"))  return "dash";
    if (s.contains("ksh"))   return "ksh";
    if (s.contains("tcsh"))  return "tcsh";
    if (s.contains("xonsh")) return "xonsh";
    if (s.contains("nushell") || s.contains("nu")) return "nushell";
    if (s.contains("wsl") || s.contains("ubuntu")) return "bash";
    if (s.contains("sh"))    return "sh";
    return null;
  }
}