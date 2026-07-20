package local.term;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TerminalLauncherTest {
  @Test
  void splitCommand_singleBinary_returnsSingleElementArray() {
    String[] result = TerminalLauncher.splitCommand("C:\\Windows\\System32\\cmd.exe");
    assertArrayEquals(new String[]{"C:\\Windows\\System32\\cmd.exe"}, result);
  }

  @Test
  void splitCommand_binaryWithFlagsAndArgs_splitsOnWhitespace() {
    String[] result = TerminalLauncher.splitCommand("cmd.exe /k echo hi");
    assertArrayEquals(new String[]{"cmd.exe", "/k", "echo", "hi"}, result);
  }

  @Test
  void splitCommand_collapsesMultipleSpaces() {
    String[] result = TerminalLauncher.splitCommand("a   b\tc");
    assertArrayEquals(new String[]{"a", "b", "c"}, result);
  }

  @Test
  void splitCommand_emptyOrBlank_returnsEmptyArray() {
    assertArrayEquals(new String[]{}, TerminalLauncher.splitCommand(""));
    assertArrayEquals(new String[]{}, TerminalLauncher.splitCommand("   "));
  }

  @Test
  void resolveEnvironment_addsTERM_xterm_256color() {
    Map<String, String> env = TerminalLauncher.resolveEnvironment(Map.of());
    assertEquals("xterm-256color", env.get("TERM"));
  }

  @Test
  void resolveEnvironment_preservesInheritedEnv() {
    Map<String, String> env = TerminalLauncher.resolveEnvironment(
        Map.of("USERPROFILE", "C:\\Users\\<your-username>", "PATH", "C:\\Windows"));
    assertEquals("C:\\Users\\<your-username>", env.get("USERPROFILE"));
    assertEquals("C:\\Windows", env.get("PATH"));
    assertEquals("xterm-256color", env.get("TERM"));
  }

  @Test
  void appendArgs_nullOrEmpty_leavesBaseUnchanged() {
    String[] base = new String[]{"cmd.exe"};
    assertArrayEquals(base, TerminalLauncher.appendArgs(base, null));
    assertArrayEquals(base, TerminalLauncher.appendArgs(base, ""));
    assertArrayEquals(base, TerminalLauncher.appendArgs(base, "   "));
  }

  @Test
  void appendArgs_tokensAreAppendedInOrder() {
    String[] base = new String[]{"C:\\bin\\bash.exe"};
    String[] out = TerminalLauncher.appendArgs(base, "-l -i  /tmp/start.sh");
    assertArrayEquals(new String[]{"C:\\bin\\bash.exe", "-l", "-i", "/tmp/start.sh"}, out);
  }

  @Test
  void appendArgs_nullBaseTreatedAsEmpty() {
    String[] out = TerminalLauncher.appendArgs(null, "-x");
    assertArrayEquals(new String[]{"-x"}, out);
  }

  @Test
  void resolveEnvironment_alwaysSetsTermAndColorterm() {
    Map<String, String> env = TerminalLauncher.resolveEnvironment(Map.of());
    assertEquals("xterm-256color", env.get("TERM"));
    assertEquals("truecolor", env.get("COLORTERM"));
  }

  @Test
  void resolveEnvironment_msysShell_appendsEnablePconToInheritedMsys() {
    // User may have inherited MSYS=winsymlinks:native — must be preserved.
    Map<String, String> env = TerminalLauncher.resolveEnvironment(
        Map.of("MSYS", "winsymlinks:native"),
        new String[]{"D:\\msys64\\usr\\bin\\bash.exe"});
    String msys = env.get("MSYS");
    assertNotNull(msys);
    assertTrue(msys.contains("winsymlinks:native"),
        "inherited MSYS option must survive: " + msys);
    assertTrue(msys.contains("enable_pcon"),
        "MSYS=enable_pcon must be added for MSYS2 bash: " + msys);
  }

  @Test
  void resolveEnvironment_msysShell_setsEnablePconWhenInheritedMsysMissing() {
    Map<String, String> env = TerminalLauncher.resolveEnvironment(
        Map.of(),
        new String[]{"D:\\msys64\\usr\\bin\\bash.exe"});
    assertEquals("enable_pcon", env.get("MSYS"));
  }

  @Test
  void resolveEnvironment_msysShell_doesNotDuplicateEnablePcon() {
    Map<String, String> env = TerminalLauncher.resolveEnvironment(
        Map.of("MSYS", "enable_pcon"),
        new String[]{"C:/Program Files/Git/usr/bin/bash.exe"});
    assertEquals("enable_pcon", env.get("MSYS"),
        "repeated launch must not double the token");
  }

  @Test
  void resolveEnvironment_nonMsysShell_doesNotTouchMsys() {
    Map<String, String> env = TerminalLauncher.resolveEnvironment(
        Map.of(),
        new String[]{"C:\\Windows\\System32\\cmd.exe"});
    assertNull(env.get("MSYS"));
  }

  @Test
  void isMsysLikeShell_recognisesCommonVariants() {
    assertTrue(TerminalLauncher.isMsysLikeShell("D:\\msys64\\usr\\bin\\bash.exe"));
    assertTrue(TerminalLauncher.isMsysLikeShell("C:/msys64/usr/bin/bash"));
    assertTrue(TerminalLauncher.isMsysLikeShell("C:\\Program Files\\Git\\usr\\bin\\bash.exe"));
    assertTrue(TerminalLauncher.isMsysLikeShell("/usr/bin/bash"));
    assertTrue(TerminalLauncher.isMsysLikeShell("/usr/bin/bash.exe"));
    assertTrue(TerminalLauncher.isMsysLikeShell("C:/cygwin64/bin/bash.exe"));
  }

  @Test
  void isMsysLikeShell_rejectsOtherShells() {
    assertFalse(TerminalLauncher.isMsysLikeShell("C:\\Windows\\System32\\cmd.exe"));
    assertFalse(TerminalLauncher.isMsysLikeShell("C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe"));
    assertFalse(TerminalLauncher.isMsysLikeShell("pwsh.exe"));
    assertFalse(TerminalLauncher.isMsysLikeShell(null));
  }

  }
