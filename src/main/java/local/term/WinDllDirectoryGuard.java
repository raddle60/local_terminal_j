package local.term;

import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Breaks the inherited Windows DLL-directory chain at the one process-spawn
 * boundary where it matters: {@link TerminalLauncher}.
 *
 * <p>jpackage's Windows launcher calls
 * {@code SetDllDirectory("<appImageRoot>\\runtime\\bin")} inside the JVM
 * process before {@code App.main} runs, so the JVM itself can locate its
 * own JDK native DLLs ({@code jli.dll}, {@code java.dll}, …). Windows
 * inherits that value into every descendant process unconditionally —
 * confirmed by both the user's
 * {@code [NativeMethods]::GetDllDirectory(...)} probe inside a launched
 * PowerShell returning
 * {@code DllDirectory = [D:\application\local_terminal\runtime\bin]}
 * and by a controlled A/B spawn in this codebase. The result is that
 * any program started inside one of the in-app terminals prefers the
 * bundled {@code runtime\bin} over the system's own DLL search order.
 *
 * <p>{@link #runUnderCleared(SpawnStep)} captures the current
 * {@code SetDllDirectory} value, clears it, runs the supplied spawn
 * step, and restores the captured value in a {@code finally} — so the
 * child process inherits an empty {@code DllDirectory} (and therefore
 * its own normal search order), while the JVM's own DLL search path is
 * unchanged once the spawn returns.
 *
 * <p>Fail-open: if JNA cannot be loaded, the {@code kernel32} bind
 * fails, or the call itself throws, the runnable is invoked unchanged
 * and a single WARN is logged (gated by an {@link AtomicBoolean} so a
 * broken JNA install does not flood the log). On non-Windows the
 * entire class is a permanent no-op.
 *
 * <p>Thread-safety: the capture/clear/spawn/restore sequence runs under
 * a static lock. There is only one {@code JTabbedPane}, but the lock
 * guards against a future caller racing a plugin or another launcher
 * variant.
 */
public final class WinDllDirectoryGuard {
  private static final Logger LOG = LoggerFactory.getLogger(WinDllDirectoryGuard.class);

  /** {@code MAX_PATH} from the Windows SDK — large enough for any
   *  realistic DLL-directory string. */
  private static final int MAX_PATH = 260;

  /** Single instance used by production callers. Tests instantiate
   *  their own with a fake {@link Kernel32} via the package-private
   *  constructor and pass it to {@link #runUnderCleared(Runnable,
   *  WinDllDirectoryGuard)} — they never touch this field, which is
   *  initialised lazily via the holder idiom so that merely loading
   *  this class (e.g. for static-method reflection) does not bind
   *  kernel32. */
  private static final class Holder {
    static final WinDllDirectoryGuard INSTANCE =
        new WinDllDirectoryGuard(detectKernel32(), detectIsWindows());
  }

  /** Guards the WARN-log so a broken JNA install spams the log at
   *  most once per JVM. */
  private static final AtomicBoolean LOGGED_BIND_FAILURE =
      new AtomicBoolean(false);

  /** Serializes capture/clear/restore around concurrent spawns. */
  private static final Object LOCK = new Object();

  private final Kernel32 kernel32;
  private final boolean isWindows;

  /** Minimal kernel32 surface we need. {@code W} variants only —
   *  matches what the rest of the JDK uses. The bundled
   *  {@code com.sun.jna.platform.win32.Kernel32} does NOT expose
   *  SetDllDirectory / GetDllDirectory (verified with {@code javap}),
   *  so we declare the two entry points we need here. */
  interface Kernel32 extends StdCallLibrary {
    /**
     * @param nBufferLength size of {@code lpBuffer} in chars
     * @param lpBuffer     receives the null-terminated path; pass
     *                     {@code char[MAX_PATH]}
     * @return number of chars copied not counting the null terminator,
     *         or the required buffer size if the buffer was too small
     */
    int GetDllDirectoryW(int nBufferLength, char[] lpBuffer);

    /**
     * @param lpPathName path to add, "" to clear with a no-op
     *                   restore, or {@code null} to remove the DLL
     *                   directory entirely from the search order.
     *                   Declared as {@link WString} (i.e. {@code LPCWSTR})
     *                   so JNA marshals a Java {@code null} as a NULL
     *                   pointer — exactly what the Win32 docs require
     *                   to clear the directory.
     * @return {@code true} on success
     */
    boolean SetDllDirectoryW(WString lpPathName);
  }

  /** Functional interface for a value-returning spawn step. */
  @FunctionalInterface
  public interface SpawnStep<T> {
    T run() throws java.io.IOException;
  }

  /** Public entry point used by {@link TerminalLauncher}. */
  public static <T> T runUnderCleared(SpawnStep<T> spawn) throws java.io.IOException {
    return runUnderCleared(spawn, Holder.INSTANCE);
  }

  /** Test-friendly overload that takes a guard instance explicitly. */
  static <T> T runUnderCleared(SpawnStep<T> spawn, WinDllDirectoryGuard guard)
      throws java.io.IOException {
    return guard.run(spawn);
  }

  /** Constructor — also the test seam: pass a fake {@link Kernel32}
   *  and a forced OS classification to exercise the guard without
   *  a real kernel32 bind. Production callers go through the
   *  no-arg {@link #runUnderCleared(Runnable)} entry point, which
   *  constructs {@link #INSTANCE} via {@link #detectKernel32()} and
   *  {@link #detectIsWindows()}. */
  WinDllDirectoryGuard(Kernel32 kernel32, boolean isWindowsForTest) {
    this.kernel32 = kernel32;
    this.isWindows = isWindowsForTest;
  }

  private <T> T run(SpawnStep<T> spawn) throws java.io.IOException {
    if (!isWindows) {
      return spawn.run();
    }
    if (kernel32 == null) {
      logBindFailureOnce();
      return spawn.run();
    }
    synchronized (LOCK) {
      char[] buf = new char[MAX_PATH];
      int copied;
      try {
        copied = kernel32.GetDllDirectoryW(MAX_PATH, buf);
      } catch (UnsatisfiedLinkError | RuntimeException e) {
        logBindFailureOnce();
        return spawn.run();
      }
      String captured = (copied > 0) ? new String(buf, 0, copied) : "";
      try {
        try {
          // null → remove DLL directory entirely from the search order
          // (per Win32 docs; JNA marshals null WString as NULL pointer).
          kernel32.SetDllDirectoryW(null);
        } catch (UnsatisfiedLinkError | RuntimeException e) {
          logBindFailureOnce();
          return spawn.run();
        }
        return spawn.run();
      } finally {
        try {
          // Restore the captured value. If we captured "", set "" too
          // so the JVM's own view is unchanged; an empty String is the
          // documented "no directory" form (a NULL would also work,
          // but matches the captured state exactly).
          kernel32.SetDllDirectoryW(new WString(captured));
        } catch (UnsatisfiedLinkError | RuntimeException ignored) {
          // Best-effort restore. Failing to restore leaves the JVM
          // without runtime\bin on its DLL search path; the next JDK
          // load that needs it will fail loudly, which is the desired
          // signal — but we don't propagate because the spawn itself
          // may have succeeded.
        }
      }
    }
  }

  private static void logBindFailureOnce() {
    if (LOGGED_BIND_FAILURE.compareAndSet(false, true)) {
      LOG.warn("WinDllDirectoryGuard: kernel32 bind or call failed — "
          + "child terminals will inherit the JVM's DLL directory. "
          + "This warning is logged at most once per JVM.");
    }
  }

  private static boolean detectIsWindows() {
    String os = System.getProperty("os.name");
    return os != null && os.toLowerCase().startsWith("windows");
  }

  private static Kernel32 detectKernel32() {
    if (!detectIsWindows()) return null;
    try {
      return Native.load("kernel32", Kernel32.class);
    } catch (UnsatisfiedLinkError | RuntimeException e) {
      logBindFailureOnce();
      return null;
    }
  }
}
