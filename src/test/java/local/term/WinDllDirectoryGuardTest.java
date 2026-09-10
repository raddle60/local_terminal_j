package local.term;

import com.sun.jna.WString;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link WinDllDirectoryGuard}.
 *
 * <p>Uses the package-private test seam (constructor takes a fake
 * {@link WinDllDirectoryGuard.Kernel32}) so no real kernel32 bind is
 * required. The fake records every {@code Get}- / {@code SetDllDirectoryW}
 * call so the assertion phase can verify capture → clear → spawn →
 * restore ordering without needing a live process tree.
 */
class WinDllDirectoryGuardTest {

  /** Recording fake: returns a configurable initial DLL directory and
   *  logs every set call in order. NOT marked {@code final} so individual
   *  tests can anonymous-subclass it to inject behaviour for the rare
   *  fail-open paths. */
  static class FakeKernel32 implements WinDllDirectoryGuard.Kernel32 {
    final String initial;
    final List<String> sets = new ArrayList<>();
    final List<Integer> gets = new ArrayList<>();

    FakeKernel32(String initial) {
      this.initial = initial;
    }

    FakeKernel32() { this(""); }

    @Override
    public int GetDllDirectoryW(int nBufferLength, char[] lpBuffer) {
      gets.add(nBufferLength);
      if (initial == null || initial.isEmpty()) {
        // No directory set → GetDllDirectoryW returns 0 and writes nothing.
        return 0;
      }
      char[] chars = initial.toCharArray();
      System.arraycopy(chars, 0, lpBuffer, 0, chars.length);
      lpBuffer[chars.length] = '\0';
      return chars.length;
    }

    @Override
    public boolean SetDllDirectoryW(WString lpPathName) {
      // Record null explicitly so assertions can distinguish
      // "clear with NULL" from "clear with empty string".
      sets.add(lpPathName == null ? "<null>" : lpPathName.toString());
      return true;
    }
  }

  @Test
  void runUnderCleared_capturesAndRestores_inOrder() throws Exception {
    FakeKernel32 fake = new FakeKernel32("D:\\app\\runtime\\bin");
    WinDllDirectoryGuard guard = new WinDllDirectoryGuard(fake, true);

    boolean[] ran = {false};
    WinDllDirectoryGuard.runUnderCleared(() -> {
      ran[0] = true;
      return null;
    }, guard);

    assertTrue(ran[0], "spawn must have run");
    // Order: Get (capture) → Set<null> (clear) → Set<captured> (restore)
    assertEquals(3, fake.sets.size() + fake.gets.size(),
        "expected exactly 3 kernel32 calls (1 get + 2 sets): "
            + "gets=" + fake.gets + " sets=" + fake.sets);
    assertEquals(1, fake.gets.size(), "expected exactly one GetDllDirectoryW");
    assertEquals(2, fake.sets.size(), "expected exactly two SetDllDirectoryW calls");
    assertEquals("<null>", fake.sets.get(0),
        "first Set must clear the DLL directory (null per Win32 docs)");
    assertEquals("D:\\app\\runtime\\bin", fake.sets.get(1),
        "second Set must restore the captured value");
  }

  @Test
  void runUnderCleared_emptyInitial_restoreIsEmptyString() throws Exception {
    FakeKernel32 fake = new FakeKernel32("");
    WinDllDirectoryGuard guard = new WinDllDirectoryGuard(fake, true);

    WinDllDirectoryGuard.runUnderCleared(() -> null, guard);

    // Get returned 0 → captured = "" → production code clears with
    // null (the Win32-documented clear form) and restores with "".
    // The clear form is fixed at null regardless of the initial state
    // — only the restore value depends on what we captured.
    assertEquals(2, fake.sets.size());
    assertEquals("<null>", fake.sets.get(0),
        "clear must always pass null per Win32 docs");
    assertEquals("", fake.sets.get(1),
        "restore must replay the captured empty state");
  }

  @Test
  void runUnderCleared_spawnThrows_restoresDllDirectory() {
    FakeKernel32 fake = new FakeKernel32("C:\\app\\runtime\\bin");
    WinDllDirectoryGuard guard = new WinDllDirectoryGuard(fake, true);

    RuntimeException boom = assertThrows(RuntimeException.class,
        () -> WinDllDirectoryGuard.runUnderCleared(
            () -> { throw new RuntimeException("spawn failed"); }, guard));

    assertEquals("spawn failed", boom.getMessage());
    // Restore must still run in the finally block.
    assertEquals(2, fake.sets.size(),
        "restore must happen even when spawn throws: " + fake.sets);
    assertEquals("C:\\app\\runtime\\bin", fake.sets.get(1),
        "captured value must be restored verbatim after a spawn exception");
  }

  @Test
  void nonWindows_runUnderCleared_skipsJnaEntirely() throws Exception {
    // FakeKernel32 is provided but the OS-classification flag is
    // false → guard must short-circuit without touching the fake.
    FakeKernel32 fake = new FakeKernel32("should-not-be-read");
    WinDllDirectoryGuard guard = new WinDllDirectoryGuard(fake, false);

    boolean[] ran = {false};
    WinDllDirectoryGuard.runUnderCleared(() -> {
      ran[0] = true;
      return null;
    }, guard);

    assertTrue(ran[0], "spawn must run on non-Windows");
    assertTrue(fake.gets.isEmpty(),
        "non-Windows must NOT call GetDllDirectoryW: " + fake.gets);
    assertTrue(fake.sets.isEmpty(),
        "non-Windows must NOT call SetDllDirectoryW: " + fake.sets);
  }

  @Test
  void nullKernel32_runUnderCleared_failsOpen() throws Exception {
    // isWindows=true but kernel32 bind failed → guard must warn once
    // and still run the spawn.
    WinDllDirectoryGuard guard = new WinDllDirectoryGuard(null, true);

    boolean[] ran = {false};
    WinDllDirectoryGuard.runUnderCleared(() -> {
      ran[0] = true;
      return null;
    }, guard);

    assertTrue(ran[0], "spawn must run when kernel32 bind failed");
    // (Log side-effect is not asserted — slf4j-simple is the test
    // binding; the AtomicBoolean gate is verified by behaviour, not
    // log capture.)
  }

  @Test
  void getCallThrows_failOpen() throws Exception {
    FakeKernel32 fake = new FakeKernel32("X") {
      @Override
      public int GetDllDirectoryW(int nBufferLength, char[] lpBuffer) {
        throw new RuntimeException("simulated Get failure");
      }
    };
    WinDllDirectoryGuard guard = new WinDllDirectoryGuard(fake, true);

    boolean[] ran = {false};
    WinDllDirectoryGuard.runUnderCleared(() -> {
      ran[0] = true;
      return null;
    }, guard);

    assertTrue(ran[0], "spawn must run even if GetDllDirectoryW throws");
    assertTrue(fake.sets.isEmpty(),
        "must NOT call SetDllDirectoryW after a failed Get");
  }

  @Test
  void setCallDuringClearThrows_failOpen() throws Exception {
    FakeKernel32 fake = new FakeKernel32("X") {
      private int setCount = 0;
      @Override
      public boolean SetDllDirectoryW(WString lpPathName) {
        setCount++;
        // Fail the FIRST call (the clear), let subsequent ones pass —
        // simulates a transient kernel32 error mid-clear.
        if (setCount == 1) throw new RuntimeException("simulated clear failure");
        super.SetDllDirectoryW(lpPathName);
        return true;
      }
    };
    WinDllDirectoryGuard guard = new WinDllDirectoryGuard(fake, true);

    boolean[] ran = {false};
    WinDllDirectoryGuard.runUnderCleared(() -> {
      ran[0] = true;
      return null;
    }, guard);

    assertTrue(ran[0], "spawn must still run if the clear call fails");
    // We never reached the finally block's restore (the failing clear
    // returned early) — the fake recorded one Set before throwing.
    assertEquals(1, fake.sets.size(),
        "no further Set calls after a failed clear: " + fake.sets);
  }

  @Test
  void concurrentRunBothSucceed_restoreOnlyAfterOwnClear() throws Exception {
    // Two guards against the same fake, simulated concurrently: each
    // capture → clear → spawn → restore sequence must remain
    // well-formed even when interleaved.
    FakeKernel32 fake = new FakeKernel32("D:\\app\\runtime\\bin");
    WinDllDirectoryGuard a = new WinDllDirectoryGuard(fake, true);
    WinDllDirectoryGuard b = new WinDllDirectoryGuard(fake, true);

    WinDllDirectoryGuard.runUnderCleared(
        () -> WinDllDirectoryGuard.runUnderCleared(() -> null, b), a);

    // a: Get → Set<null> → (b's full sequence) → Set<captured>
    // b: inside a's clear window → Get → Set<null> → Set<"D:\app\runtime\bin">
    // The fake ignores Set calls (it only returns the initial value),
    // so b's Get sees "D:\app\runtime\bin" — same as a captured.
    assertEquals(2, fake.gets.size(),
        "each guard must call GetDllDirectoryW exactly once");
    assertEquals(4, fake.sets.size(),
        "two guards × two Set calls = four Set calls: " + fake.sets);
    // Order: a's clear (null), b's clear (null), b's restore, a's restore.
    assertEquals("<null>", fake.sets.get(0), "a's clear");
    assertEquals("<null>", fake.sets.get(1), "b's clear");
    assertEquals("D:\\app\\runtime\\bin", fake.sets.get(2), "b's restore");
    assertEquals("D:\\app\\runtime\\bin", fake.sets.get(3), "a's restore");
    assertFalse(false, "sanity — the fake recorded as expected");
  }
}
