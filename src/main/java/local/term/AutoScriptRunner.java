package local.term;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Runs an {@link AutoScript} against a live terminal session: each
 * {@link Step} blocks until a line since script start matches its
 * {@code waitPattern} (or the script's {@code timeoutMs} elapses), then
 * writes its {@code command} to the {@link Sink} and pauses for
 * {@code intervalMs} before the next step.
 *
 * <p>On timeout the runner halts the script and invokes the configured
 * {@link Consumer onTimeout} handler (if any) with a {@link TimeoutInfo}
 * record describing the failure. The session is intentionally left open
 * so the user can investigate — they will see a dialog popup and can
 * decide whether to close the tab themselves.
 *
 * <p>Optional {@link ProgressListener} receives lifecycle events
 * (started / stepStarted / stepCompleted / finished / failed / cancelled).
 * The runner invokes callbacks on its own daemon thread — listeners are
 * responsible for hopping to the EDT if they need to touch Swing widgets.
 * Listener exceptions are isolated and never abort the runner.
 */
public class AutoScriptRunner {

  private static final Logger LOG = LoggerFactory.getLogger(AutoScriptRunner.class);

  /** Adapter surface the runner needs from a {@link TerminalSession}. */
  public interface Sink {
    void write(byte[] bytes) throws IOException;
    void close();
    boolean isClosed();
  }

  /** Details passed to the timeout handler when a step times out. */
  public record TimeoutInfo(int stepIdx, String tagName, String waitPattern, int timeoutMs) {}

  /** Why a step failed. */
  public enum FailureKind { TIMEOUT, WRITE_ERROR, INTERNAL_ERROR }

  /**
   * Receives lifecycle events as the script runs. All methods have empty
   * defaults so callers only override what they care about. The runner
   * invokes callbacks sequentially on its daemon thread — listeners that
   * touch Swing must marshal to the EDT themselves.
   *
   * <p>Listener exceptions are caught and logged by the runner; they do
   * not abort the script.
   */
  public interface ProgressListener {
    /** Fired once at the start of {@link #run()} with the total step count. */
    default void onStarted(int totalSteps, String tagName) {}
    /** Fired when the runner enters the wait phase for step {@code stepIdx}. */
    default void onStepStarted(int stepIdx, Step step) {}
    /** Fired only after the step's command was successfully written to the sink. */
    default void onStepCompleted(int stepIdx, Step step) {}
    /** Fired after the last step successfully completes. */
    default void onFinished(int totalSteps) {}
    /** Fired when the script halts mid-flight (timeout / write failure / internal). */
    default void onFailed(int stepIdx, Step step, FailureKind kind, String reason) {}
    /** Fired when the underlying sink was closed externally (e.g. tab closed). */
    default void onCancelled() {}
  }

  private final AutoScript script;
  private final Sink sink;
  private final OutputBuffer buffer;
  private final int intervalMs;
  private final String tagName;
  private final Consumer<TimeoutInfo> onTimeout;
  private final ProgressListener progress;

  private final AtomicInteger cursor = new AtomicInteger(0);
  // Created lazily in run() so a runner that is never started (tests) does
  // not spawn an executor. Held as a field so waitForFirstShellOutput()
  // (called inside run()) can reuse it instead of constructing a second.
  private ScheduledExecutorService timer;

  public AutoScriptRunner(AutoScript script, Sink sink, OutputBuffer buffer) {
    this(script, sink, buffer, AutoScript.STEP_INTERVAL_MS, "anon", null, null);
  }

  /** Test-friendly constructor that injects a smaller interval for fast tests. */
  AutoScriptRunner(AutoScript script, Sink sink, OutputBuffer buffer, int intervalMs) {
    this(script, sink, buffer, intervalMs, "test", null, null);
  }

  /** Production wiring knows the tag name so the runner thread is identifiable. */
  public AutoScriptRunner(AutoScript script, Sink sink, OutputBuffer buffer, String tagName) {
    this(script, sink, buffer, AutoScript.STEP_INTERVAL_MS, tagName, null, null);
  }

  /** Production wiring with a timeout handler (called on the runner's daemon thread). */
  public AutoScriptRunner(AutoScript script, Sink sink, OutputBuffer buffer,
                          String tagName, Consumer<TimeoutInfo> onTimeout) {
    this(script, sink, buffer, AutoScript.STEP_INTERVAL_MS, tagName, onTimeout, null);
  }

  /**
   * Production wiring with both a timeout handler and a progress listener.
   * Either callback may be {@code null}.
   */
  public AutoScriptRunner(AutoScript script, Sink sink, OutputBuffer buffer,
                          String tagName, Consumer<TimeoutInfo> onTimeout,
                          ProgressListener progress) {
    this(script, sink, buffer, AutoScript.STEP_INTERVAL_MS, tagName, onTimeout, progress);
  }

  /** Full-control constructor used internally and by tests. */
  AutoScriptRunner(AutoScript script, Sink sink, OutputBuffer buffer,
                   int intervalMs, String tagName, Consumer<TimeoutInfo> onTimeout,
                   ProgressListener progress) {
    this.script = Objects.requireNonNull(script, "script");
    this.sink = Objects.requireNonNull(sink, "sink");
    this.buffer = Objects.requireNonNull(buffer, "buffer");
    this.intervalMs = intervalMs;
    this.tagName = tagName;
    this.onTimeout = onTimeout;
    this.progress = progress;
  }

  public void start() {
    Thread t = new Thread(this::run, "AutoScript-" + tagName);
    t.setDaemon(true);
    t.start();
  }

  private void run() {
    final int total = script.steps().size();
    LOG.info("AutoScript starting for tag '{}' ({} step(s), timeout={}ms)",
        tagName, total, script.timeoutMs());

    // Pre-existing output from the shell banner is fair game as a match
    // target — start the cursor at the beginning of the buffer. Each step
    // advances the cursor past its matched line so subsequent steps scan
    // forward.
    cursor.set(0);
    safeAccept(p -> p.onStarted(total, tagName));

    ScheduledExecutorService timer =
        Executors.newSingleThreadScheduledExecutor(r -> {
          Thread tt = new Thread(r, "AutoScript-timer-" + tagName);
          tt.setDaemon(true);
          return tt;
        });
    this.timer = timer;
    Step currentStep = null;
    int currentStepIdx = -1;
    try {
      int stepIdx = 0;
      for (Step step : script.steps()) {
        currentStep = step;
        currentStepIdx = stepIdx;
        // Snapshot the loop index — lambdas below need an effectively-final
        // local (JLS §16.1.7) and stepIdx is mutated across loop iterations.
        final int thisStep = stepIdx;
        if (sink.isClosed()) {
          safeAccept(ProgressListener::onCancelled);
          return;
        }
        // Inter-step interval: every step except the first honors it. The
        // first step sends (or waits) right after start, no preceding pause.
        if (thisStep > 0) {
          sleepInterruptibly(intervalMs);
          if (sink.isClosed()) {
            safeAccept(ProgressListener::onCancelled);
            return;
          }
        }
        LOG.info("AutoScript step {} for '{}': waiting for '{}', then '{}'",
            thisStep, tagName,
            step.waitPattern().isBlank() ? "(none)" : step.waitPattern(),
            step.command());
        safeAccept(p -> p.onStepStarted(thisStep, step));
        if (step.waitPattern().isBlank()) {
          // Blank pattern = send as soon as possible. For step 0, "as soon as
          // possible" must NOT mean "the instant the PTY exists" — JediTerm
          // still has to send its init sequences (ESC[1t de-iconify, ESC[c
          // device-attrs, ESC[?1004h focus events, ESC[?9001h win32-input,
          // hide-cursor, cursor-position, color resets, ~hundreds of ms on
          // Windows). PowerShell PSReadLine reads raw bytes during this
          // window and treats them as continuation input, leaving the shell
          // stuck in the `>> ` prompt after the auto-script completes.
          //
          // Wait for the shell to actually emit a non-empty line (its banner
          // or first prompt) — by then JediTerm init is done and the shell
          // is ready. Steps N>0 don't need this gate; the inter-step interval
          // + the previous step's output already settled the shell.
          if (thisStep == 0) {
            if (!waitForFirstShellOutput()) {
              safeAccept(ProgressListener::onCancelled);
              return;
            }
            if (sink.isClosed()) {
              safeAccept(ProgressListener::onCancelled);
              return;
            }
          }
          if (!send(step.command())) {
            // WRITE_ERROR: send() already closed the sink and logged.
            safeAccept(p -> p.onFailed(thisStep, step, FailureKind.WRITE_ERROR,
                "failed to write command to PTY"));
            return;
          }
          safeAccept(p -> p.onStepCompleted(thisStep, step));
          stepIdx++;
          continue;
        }
        GlobMatcher matcher = GlobMatcher.compile(step.waitPattern());
        int[] matched = { -1 };
        // Capture the loop index in an effectively-final local so the
        // listener lambda can use it without violating JLS §16.1.7.
        final int currentStepFinal = thisStep;

        // CRITICAL: register the listener BEFORE the initial scan. JediTerm
        // can fire linesChanged on the PTY reader thread at any moment; if
        // we did the scan first and only then registered the listener, any
        // event that landed between the two would be missed forever, and
        // the runner would wait until the timeout (which halts the script).
        CountDownLatch tick = new CountDownLatch(1);
        OutputBuffer.Listener stepListener = n -> {
          int idx = scanForMatch(cursor.get(), matcher);
          if (idx >= 0 && matched[0] < 0) {
            matched[0] = idx;
            tick.countDown();
            LOG.debug("AutoScript step {} matched at line {} (truncated): {}",
                currentStepFinal, idx, abbreviate(buffer.getLineText(idx)));
          }
        };
        buffer.addListener(stepListener);
        ScheduledFuture<?> to = timer.schedule(
            tick::countDown, script.timeoutMs(), TimeUnit.MILLISECONDS);
        boolean cancelled = false;
        boolean timedOut = false;
        try {
          int found = scanForMatch(cursor.get(), matcher);
          if (found >= 0) {
            matched[0] = found;
            tick.countDown();
            LOG.debug("AutoScript step {} initial-scan matched at line {} "
                + "(truncated): {}", currentStepFinal, found,
                abbreviate(buffer.getLineText(found)));
          }
          // Short-slice await so sink.close() during the wait is noticed
          // within ~100 ms instead of being misreported as a timeout when
          // the timer finally fires.
          long deadline = System.nanoTime()
              + TimeUnit.MILLISECONDS.toNanos(script.timeoutMs());
          while (tick.getCount() > 0) {
            long remainingNs = deadline - System.nanoTime();
            if (remainingNs <= 0) break;
            if (sink.isClosed()) {
              cancelled = true;
              break;
            }
            tick.await(Math.min(100, TimeUnit.NANOSECONDS.toMillis(remainingNs)),
                TimeUnit.MILLISECONDS);
          }
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          return;
        } finally {
          buffer.removeListener(stepListener);
          to.cancel(false);
        }
        if (cancelled) {
          safeAccept(ProgressListener::onCancelled);
          return;
        }
        if (matched[0] < 0) {
          timedOut = true;
        }
        if (timedOut) {
          handleTimeout(currentStepFinal, step.waitPattern(), step);
          return;  // halt the script; session is left open for the user.
        }
        cursor.set(matched[0] + 1);
        if (!send(step.command())) {
          safeAccept(p -> p.onFailed(currentStepFinal, step, FailureKind.WRITE_ERROR,
              "failed to write command to PTY"));
          return;
        }
        safeAccept(p -> p.onStepCompleted(currentStepFinal, step));
        stepIdx++;
      }
      LOG.info("AutoScript completed for tag '{}' after {} step(s)", tagName, stepIdx);
      safeAccept(p -> p.onFinished(total));
    } catch (RuntimeException e) {
      // Anything we didn't anticipate (e.g. GlobMatcher.compile blew up on
      // a weird pattern). Treat as INTERNAL_ERROR so the listener can show
      // a failure rather than letting the script silently die.
      LOG.error("AutoScript internal error for '{}': {}", tagName, e.getMessage(), e);
      Step s = currentStep;
      int idx = currentStepIdx;
      if (s != null) {
        safeAccept(p -> p.onFailed(idx, s, FailureKind.INTERNAL_ERROR,
            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
      }
    } finally {
      timer.shutdownNow();
    }
  }

  /**
   * Halts the script with a logged error, an optional UI callback, and
   * an {@link ProgressListener#onFailed} notification. The session is
   * intentionally NOT closed — the user gets a popup and the tab stays
   * open so they can inspect what went wrong.
   */
  private void handleTimeout(int stepIdx, String waitPattern, Step step) {
    String reason = "timeout: pattern '" + waitPattern
        + "' not seen within " + script.timeoutMs() + "ms";
    LOG.error("AutoScript timed out at step {} for '{}': {} — script halted, "
        + "session left open", stepIdx, tagName, reason);
    safeAccept(p -> p.onFailed(stepIdx, step, FailureKind.TIMEOUT, reason));
    if (onTimeout == null) return;
    TimeoutInfo info = new TimeoutInfo(stepIdx, tagName, waitPattern, script.timeoutMs());
    try {
      onTimeout.accept(info);
    } catch (RuntimeException e) {
      LOG.warn("onTimeout handler threw: {}", e.getMessage(), e);
    }
  }

  private int scanForMatch(int fromCursor, GlobMatcher matcher) {
    int end = buffer.getLineCount();
    for (int i = fromCursor; i < end; i++) {
      if (matcher.matches(buffer.getLineText(i))) return i;
    }
    return -1;
  }

  /**
   * Returns {@code true} if the command was written, {@code false} on
   * I/O failure (in which case the sink has been closed to halt the
   * script — the caller must NOT continue with subsequent steps).
   */
  private boolean send(String command) {
    if (sink.isClosed()) return false;
    try {
      // Use bare CR (\r). It is the universal "Enter" terminator across:
      //   * Windows ConPTY: input bytes are mapped to keyboard events; CR
      //     is the Enter key, LF is a continuation newline that drops
      //     PowerShell into the `>> ` continuation prompt.
      //   * Linux PTY (pty4j): the terminal driver in canonical mode
      //     treats CR as a line terminator (same as LF) and delivers the
      //     line to the shell's read().
      //   * bash / zsh / sh: readline treats CR as Enter.
      //   * cmd.exe / PowerShell: readline treats CR as Enter.
      // Sending \r\n would also work on Windows but on Linux the extra \n
      // becomes a literal newline in the buffer between commands.
      byte[] payload = (command + "\r").getBytes(StandardCharsets.UTF_8);
      sink.write(payload);
      LOG.info("AutoScript sent {} byte(s) to PTY: {}",
          payload.length, abbreviate(command));
      return true;
    } catch (IOException e) {
      LOG.warn("AutoScript write failed: {}", e.getMessage());
      sink.close();
      return false;
    }
  }

  /** Truncate long strings for log readability. */
  private static String abbreviate(String s) {
    if (s == null) return "(null)";
    String oneLine = s.replace('\n', ' ').replace('\r', ' ');
    return oneLine.length() <= 120 ? oneLine : oneLine.substring(0, 117) + "...";
  }

  /**
   * Wait until the shell has produced at least one non-empty line, or
   * until either the script's timeout fires OR the sink is closed
   * externally. Returns {@code true} if the script should proceed,
   * {@code false} if the sink was closed (caller should emit
   * {@code onCancelled}).
   *
   * <p>Used by blank-pattern step 0 to avoid sending bytes while
   * JediTerm's init sequences and the shell's startup banner are still
   * being processed.
   */
  private boolean waitForFirstShellOutput() {
    // Register the listener FIRST (before the initial scan), so a line
    // arriving between this method entry and the listener registration
    // is still observed. Re-check inside the listener — the latch will
    // already be counted down by it for the initial state too.
    CountDownLatch tick = new CountDownLatch(1);
    OutputBuffer.Listener l = n -> {
      if (hasNonEmptyLine()) tick.countDown();
    };
    buffer.addListener(l);
    try {
      if (hasNonEmptyLine()) {
        LOG.info("AutoScript step 0: shell already produced output, proceeding");
        return !sink.isClosed();
      }
      LOG.info("AutoScript step 0: waiting for shell's first output line "
          + "(blank wait pattern)");
      ScheduledFuture<?> to = timer.schedule(
          tick::countDown, script.timeoutMs(), TimeUnit.MILLISECONDS);
      try {
        long deadline = System.nanoTime()
            + TimeUnit.MILLISECONDS.toNanos(script.timeoutMs());
        while (tick.getCount() > 0) {
          long remainingNs = deadline - System.nanoTime();
          if (remainingNs <= 0) break;
          if (sink.isClosed()) return false;
          tick.await(Math.min(100, TimeUnit.NANOSECONDS.toMillis(remainingNs)),
              TimeUnit.MILLISECONDS);
        }
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
        return false;
      } finally {
        to.cancel(false);
      }
    } catch (RuntimeException e) {
      throw e;
    } finally {
      buffer.removeListener(l);
    }
    if (hasNonEmptyLine()) {
      LOG.info("AutoScript step 0: shell produced output, proceeding");
      return !sink.isClosed();
    } else {
      LOG.warn("AutoScript step 0: no shell output within {}ms — sending "
          + "anyway (may race with shell startup)", script.timeoutMs());
      return !sink.isClosed();
    }
  }

  private boolean hasNonEmptyLine() {
    int n = buffer.getLineCount();
    for (int i = 0; i < n; i++) {
      String t = buffer.getLineText(i);
      if (t != null && !t.trim().isEmpty()) return true;
    }
    return false;
  }

  /**
   * Invoke a callback on the registered {@link ProgressListener} while
   * isolating listener exceptions so a buggy UI cannot abort the script.
   */
  private void safeAccept(java.util.function.Consumer<ProgressListener> action) {
    ProgressListener p = this.progress;
    if (p == null) return;
    try {
      action.accept(p);
    } catch (RuntimeException e) {
      LOG.warn("ProgressListener threw: {}", e.getMessage(), e);
    }
  }

  private static void sleepInterruptibly(int ms) {
    try { Thread.sleep(ms); }
    catch (InterruptedException e) { Thread.currentThread().interrupt(); }
  }
}