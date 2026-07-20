package local.term;

import com.jediterm.terminal.TtyConnector;
import com.jediterm.terminal.ui.JediTermWidget;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Wraps a JediTermWidget + its TtyConnector. {@link #close()} shuts down the
 * underlying process and fires the {@code onClosed} listener exactly once.
 *
 * <p>Per-shell activity hooks: {@link #setOnOutput(Runnable)} is invoked on the
 * EDT every time new shell bytes land in the {@code TerminalTextBuffer}; after
 * 1 second with no further output, {@link #setOnHighlightCleared(Runnable)}
 * is invoked on the EDT. Both fire-and-forget — listeners are responsible for
 * their own state. Used by {@link TerminalPanel} to drive the per-tab top
 * accent strip and (via aggregate count) the window-title ball badge.
 */
public class TerminalSession {
  private static final Logger LOG = LoggerFactory.getLogger(TerminalSession.class);

  /** Debounce window after the last output before we declare silence. */
  private static final int ACTIVITY_TIMEOUT_MS = 1000;

  private final JediTermWidget widget;
  private final TtyConnector connector;
  private final AtomicBoolean closed = new AtomicBoolean(false);
  private volatile Runnable onClosed;
  private volatile Runnable onOutput;
  private volatile Runnable onHighlightCleared;

  /**
   * Non-repeating 1-second timer; {@link #notifyOutput()} calls {@code restart()}
   * to push the timeout forward on every batch of bytes. When the timer fires
   * (i.e., 1 s elapsed with no further output), {@link #fireHighlightCleared()}
   * runs on the EDT. {@code restart()} of a non-repeating Timer cancels any
   * pending fire and re-arms it — exactly the debounce we need.
   */
  private final Timer clearTimer = new Timer(ACTIVITY_TIMEOUT_MS, e -> fireHighlightCleared());
  {
    clearTimer.setRepeats(false);
  }

  public TerminalSession(JediTermWidget widget, TtyConnector connector, Runnable onClosed) {
    this.widget = widget;
    this.connector = connector;
    this.onClosed = onClosed;
  }

  public JediTermWidget getWidget() { return widget; }

  /**
   * Underlying {@link CompositeFontPanel} when this session was built on
   * top of one (the standard path through {@link TerminalLauncher}).
   * Returns {@code null} for legacy / test paths that use plain
   * {@link JediTermWidget} without a composite-font subclass.
   *
   * <p>Used by {@code MainFrame} to push live font-size changes into
   * already-open sessions without restarting them.
   */
  public CompositeFontPanel getCompositePanel() {
    return (widget instanceof CompositeFontJediTermWidget)
        ? (CompositeFontPanel) widget.getTerminalPanel()
        : null;
  }

  public void setOnClosed(Runnable handler) {
    this.onClosed = handler;
    if (closed.get() && handler != null) {
      handler.run();
    }
  }

  /**
   * Fires on the EDT on every batch of new shell bytes. Calls the registered
   * {@link #setOnOutput(Runnable) onOutput} handler (if any) and pushes the
   * clear-timer forward by {@link #ACTIVITY_TIMEOUT_MS} so the highlight
   * lingers that long after the last visible output.
   *
   * <p>JediTerm invokes its {@code TextBufferChangesListener} on the PTY
   * reader thread, so this is the entry point that hops back to the EDT.
   */
  public void notifyOutput() {
    SwingUtilities.invokeLater(() -> {
      Runnable out = this.onOutput;
      if (out != null) out.run();
      clearTimer.restart();
    });
  }

  private void fireHighlightCleared() {
    SwingUtilities.invokeLater(() -> {
      Runnable clear = this.onHighlightCleared;
      if (clear != null) clear.run();
    });
  }

  public void setOnOutput(Runnable handler) { this.onOutput = handler; }
  public void setOnHighlightCleared(Runnable handler) { this.onHighlightCleared = handler; }

  public void close() {
    if (!closed.compareAndSet(false, true)) return;
    Runnable handler = this.onClosed;  // capture under JMM
    // Stop the debounce timer — the underlying Timer holds a non-daemon thread
    // that would otherwise keep the JVM alive past the natural window close.
    clearTimer.stop();
    try {
      widget.close();
    } catch (Exception e) {
      LOG.warn("widget.close failed", e);
    }
    try {
      connector.close();
    } catch (Exception e) {
      LOG.warn("connector.close failed", e);
    }
    if (handler != null) handler.run();
  }

  /**
   * Called when JediTerm signals that the underlying PTY has closed
   * (e.g., the user ran {@code exit}). Fires the close path if not
   * already closed. The AtomicBoolean in {@link #close()} prevents
   * double-cleanup.
   */
  public void markExternallyClosed() {
    close();
  }

  public boolean isClosed() { return closed.get(); }
}
