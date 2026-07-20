package local.term;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the substring-based shell-name matcher. The rule order must
 * match {@code local_terminal/src/renderer/components/ProfileTree.ts} so
 * the two apps stay visually consistent.
 */
class ShellNameExtractorTest {

  @Test void cmd_path_extracts_cmd() {
    assertEquals("cmd", ShellNameExtractor.extract("C:\\Windows\\System32\\cmd.exe"));
  }

  @Test void pwsh_path_extracts_powershell() {
    assertEquals("powershell",
        ShellNameExtractor.extract("C:\\Program Files\\PowerShell\\7\\pwsh.exe"));
  }

  @Test void windows_powershell_path_extracts_powershell() {
    assertEquals("powershell",
        ShellNameExtractor.extract(
            "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"));
  }

  @Test void bash_path_extracts_bash() {
    assertEquals("bash",
        ShellNameExtractor.extract("C:\\Program Files\\Git\\bin\\bash.exe"));
  }

  @Test void sh_only_path_extracts_sh() {
    assertEquals("sh",
        ShellNameExtractor.extract("C:\\Program Files\\Git\\usr\\bin\\sh.exe"));
  }

  @Test void wsl_path_extracts_bash() {
    assertEquals("bash",
        ShellNameExtractor.extract("C:\\Program Files\\WSL\\wsl.exe"));
  }

  @Test void ubuntu_path_extracts_bash() {
    assertEquals("bash",
        ShellNameExtractor.extract("C:\\Program Files\\Ubuntu\\ubuntu.exe"));
  }

  @Test void fish_path_extracts_fish() {
    assertEquals("fish",
        ShellNameExtractor.extract("C:\\Program Files\\fish\\fish.exe"));
  }

  @Test void nushell_nu_path_extracts_nushell() {
    assertEquals("nushell", ShellNameExtractor.extract("C:\\nushell\\nu.exe"));
  }

  @Test void xonsh_path_extracts_xonsh() {
    assertEquals("xonsh", ShellNameExtractor.extract("C:\\xonsh\\xonsh.exe"));
  }

  @Test void unknown_shell_returns_null() {
    // 'myapp.exe' contains no shell-name substring (the original
    // 'custom-shell.exe' would match the final 'sh' fallback rule).
    assertNull(ShellNameExtractor.extract("C:\\tools\\myapp.exe"));
  }

  @Test void null_input_returns_null() {
    assertNull(ShellNameExtractor.extract(null));
  }

  @Test void case_insensitive() {
    assertEquals("bash", ShellNameExtractor.extract("C:\\BIN\\BASH.EXE"));
  }
}