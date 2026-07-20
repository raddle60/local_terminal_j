package local.term;

import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.model.TextBufferChangesListener;
import com.jediterm.terminal.ui.JediTermWidget;
import com.jediterm.terminal.ui.TerminalWidget;
import com.jediterm.terminal.ui.TerminalWidgetListener;
import com.jediterm.terminal.ui.settings.SettingsProvider;
import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.Font;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Builds a {@link TerminalSession} from a {@link TagNode}.
 *
 * Returns a {@link LaunchResult}: Success wraps the live session; Failure
 * wraps a user-facing error message (shown in a dialog by the caller).
 *
 * Spec deviations from docs/superpowers/plans/2026-07-13-java-terminal-manager.md
 * (Task 8):
 *  - JediTerm 3.73 does not provide com.jediterm.pty.PtyProcessTtyConnector,
 *    so a local {@link PtyProcessTtyConnector} subclass of
 *    {@link com.jediterm.terminal.ProcessTtyConnector} is used instead.
 *  - {@link JediTermWidget#getTerminal()} returns the parent {@code Terminal}
 *    type (not {@code JediTerminal}); {@code createTerminalSession} is a
 *    method on the widget itself, so the cast / separate call was dropped.
 *  - In JediTerm 3.73, {@code createTerminalSession} only installs the
 *    connector; the emulator read loop is submitted by an explicit call to
 *    {@link JediTermWidget#start()} after construction.
 */
public class TerminalLauncher {
  private static final Logger LOG = LoggerFactory.getLogger(TerminalLauncher.class);

  /**
   * Split a shellPath into a command array. Simple whitespace tokenizer;
   * does NOT honor quoted arguments in v1.
   */
  public static String[] splitCommand(String shellPath) {
    if (shellPath == null) return new String[0];
    String trimmed = shellPath.trim();
    if (trimmed.isEmpty()) return new String[0];
    return trimmed.split("\\s+");
  }

  /**
   * Tokenize an optional shellArgs string and append each token to {@code base}.
   * Returns {@code base} unchanged when {@code shellArgs} is null or empty
   * after trimming. Whitespace-separated, same semantics as
   * {@link #splitCommand(String)} — does NOT honor quoted arguments in v1.
   */
  public static String[] appendArgs(String[] base, String shellArgs) {
    if (base == null) base = new String[0];
    if (shellArgs == null) return base;
    String trimmed = shellArgs.trim();
    if (trimmed.isEmpty()) return base;
    String[] extra = trimmed.split("\\s+");
    String[] out = new String[base.length + extra.length];
    System.arraycopy(base, 0, out, 0, base.length);
    System.arraycopy(extra, 0, out, base.length, extra.length);
    return out;
  }

  /**
   * Build env map for a shell process.
   *
   * <p>Always sets {@code TERM=xterm-256color} and {@code COLORTERM=truecolor}
   * — the former tells the shell to enable terminal features, the latter is
   * a hint many modern CLI tools (bat, fd, delta, ls --color) check to
   * decide on color output. Both are harmless for cmd / PowerShell, which
   * ignore them.
   *
   * <p>If the binary at {@code command[0]} looks like MSYS2 / Git Bash /
   * Cygwin bash (path contains {@code /msys/} or {@code /cygwin/}, or
   * ends with {@code /bash} / {@code /bash.exe}), {@code MSYS=enable_pcon}
   * is appended to any inherited {@code MSYS} value. Without it, MSYS2
   * programs running under Windows ConPTY detect "no console" and suppress
   * ANSI output entirely — so even raw {@code echo -e "\e[31m..."} comes
   * out uncoloured. The same setting is also needed for child programs
   * like {@code ls} / {@code grep} to emit color through ConPTY.
   *
   * <p>The detection covers user-installed bash variants such as
   * Git for Windows ({@code C:/Program Files/Git/usr/bin/bash.exe}) and
   * MSYS2 ({@code D:/msys64/usr/bin/bash.exe}).
   */
  public static Map<String, String> resolveEnvironment(Map<String, String> inherited,
                                                       String[] command) {
    Map<String, String> env = new HashMap<>(inherited);
    env.put("TERM", "xterm-256color");
    env.put("COLORTERM", "truecolor");
    if (command != null && command.length > 0 && isMsysLikeShell(command[0])) {
      String existing = env.get("MSYS");
      if (existing == null || existing.isEmpty()) {
        env.put("MSYS", "enable_pcon");
      } else if (!existing.contains("enable_pcon")) {
        env.put("MSYS", existing + " enable_pcon");
      }
    }
    return env;
  }

  /** Backward-compatible single-arg overload (no MSYS hint). */
  public static Map<String, String> resolveEnvironment(Map<String, String> inherited) {
    return resolveEnvironment(inherited, new String[0]);
  }

  /**
   * True when the binary path looks like an MSYS2 / Git Bash / Cygwin bash.
   * Detection is path-based — we don't exec the binary — and the check is
   * intentionally lenient so user-installed bash variants under
   * {@code D:/Program Files/Git/usr/bin} or similar are picked up.
   */
  static boolean isMsysLikeShell(String binaryPath) {
    if (binaryPath == null) return false;
    String lower = binaryPath.toLowerCase().replace('\\', '/');
    if (lower.contains("/msys/") || lower.contains("/cygwin/")) return true;
    return lower.endsWith("/bash") || lower.endsWith("/bash.exe");
  }

  /**
   * Spawn a shell for {@code shell} and assemble a TerminalSession.
   * Returns Failure (not throw) on any pty/spawn error.
   *
   * @param onTimeout invoked on the runner's daemon thread if an
   *                  auto-script step exceeds its wait timeout. May be
   *                  {@code null} if the caller doesn't care about
   *                  timeouts (e.g. tests, or shells without an auto-script).
   */
  public static LaunchResult launch(TagNode.Shell shell,
                                    java.util.function.Consumer<AutoScriptRunner.TimeoutInfo> onTimeout) {
    return launch(shell, onTimeout, null);
  }

  /**
   * Full-control overload: same as {@link #launch(TagNode.Shell, Consumer)}
   * but additionally wires a {@link AutoScriptRunner.ProgressListener}.
   *
   * <p>The listener receives step lifecycle events on the runner's daemon
   * thread. Listeners that touch Swing must marshal to the EDT themselves.
   * Pass {@code null} when no listener is needed.
   *
   * <p>The launcher fires {@link AutoScriptRunner.ProgressListener#onStarted}
   * before {@link AutoScriptRunner#start()} returns, so the listener can
   * safely prepare its UI in that callback.
   */
  public static LaunchResult launch(TagNode.Shell shell,
                                    java.util.function.Consumer<AutoScriptRunner.TimeoutInfo> onTimeout,
                                    AutoScriptRunner.ProgressListener progressListener) {
    String[] command = appendArgs(splitCommand(shell.shellPath()), shell.shellArgs());
    if (command.length == 0) {
      return new LaunchResult.Failure("Shell path is empty");
    }
    Map<String, String> env = resolveEnvironment(System.getenv(), command);

    PtyProcess process = null;
    TtyConnector connector = null;
    JediTermWidget widget = null;
    try {
      LOG.info("Launching terminal: command={} cwd={}", String.join(" ", command), shell.startPath());
      PtyProcessBuilder builder = new PtyProcessBuilder()
          .setCommand(command)
          .setDirectory(shell.startPath())
          .setEnvironment(env)
          .setUseWinConPty(true)
          .setInitialColumns(120)
          .setInitialRows(30);

      process = builder.start();
      // Always decode as UTF-8. cmd/PowerShell/bash all render correctly
      // when the terminal pane is set up to display UTF-8.
      connector = new PtyProcessTtyConnector(process, StandardCharsets.UTF_8,
          Arrays.asList(command));

      SettingsProvider settings = new DarkSettingsProvider();
      widget = new CompositeFontJediTermWidget(settings);
      widget.createTerminalSession(connector);
      widget.start();

      TerminalSession session = new TerminalSession(widget, connector, () -> {
        // No-op default; caller overrides via setOnClosed before tabs register.
      });
      // Kick off auto-script runner BEFORE registering widget listeners:
      // the runner needs its own TextBufferChangesListener installed and the
      // pty reader thread to start delivering output.
      // The user can store steps but disable the script (AutoScript.enabled
      // == false); in that case we must not start the runner even though the
      // script is non-null.
      if (shell.autoScript() != null && shell.autoScript().enabled()) {
        com.jediterm.terminal.ui.JediTermWidget finalWidget = widget;
        TtyConnector finalConnector = connector;
        TerminalSession finalSession = session;
        AutoScriptRunner.Sink sink = new AutoScriptRunner.Sink() {
          @Override public void write(byte[] bytes) throws java.io.IOException {
            finalConnector.write(bytes);
          }
          @Override public void close() { finalSession.close(); }
          @Override public boolean isClosed() { return finalSession.isClosed(); }
        };
        OutputBuffer outBuf = new JediOutputBuffer(finalWidget.getTerminalTextBuffer());
        AutoScriptRunner runner = new AutoScriptRunner(shell.autoScript(), sink,
            outBuf, shell.name(), onTimeout, progressListener);
        runner.start();
      }
      // Register output hook so TerminalPanel can drive per-tab top-accent
      // highlights (and the window-title ball aggregate) off real PTY bytes.
      // JediTerm fires TextBufferChangesListener.linesChanged(int) on the PTY
      // reader thread after each processed batch; linesDiscardedFromHistory,
      // historyCleared and widthResized don't constitute new shell output, so
      // we override only linesChanged and gate on n > 0 (a zero-count call
      // happens for scrolls without new content).
      widget.getTerminalTextBuffer().addChangesListener(
          new TextBufferChangesListener() {
            @Override public void linesChanged(int n) {
              if (n > 0) session.notifyOutput();
            }
          });
      // Register EOF listener so an exiting shell removes its tab.
      widget.addListener(new TerminalWidgetListener() {
        @Override
        public void allSessionsClosed(TerminalWidget w) {
          session.markExternallyClosed();
        }
      });

      return new LaunchResult.Success(session);
    } catch (Exception | LinkageError e) {
      LOG.error("Failed to launch terminal for tag {}: {}", shell.name(), e.getMessage(), e);
      // Best-effort cleanup of partially-initialized resources.
      try {
        if (widget != null) widget.close();
      } catch (Exception ignored) { }
      try {
        if (connector != null) connector.close();
      } catch (Exception ignored) { }
      if (process != null && process.isAlive()) {
        process.destroy();
      }
      return new LaunchResult.Failure(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
    }
  }

  /**
   * Outcome of a {@link #launch(TagNode)} call. {@link Success} wraps the live
   * session; {@link Failure} wraps a user-facing message — the process was
   * NOT started.
   */
  public sealed interface LaunchResult {
    record Success(TerminalSession session) implements LaunchResult {}
    record Failure(String message) implements LaunchResult {}
  }
}