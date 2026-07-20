package local.term;

import com.jediterm.terminal.model.TerminalTextBuffer;
import com.jediterm.terminal.model.TextBufferChangesListener;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * {@link OutputBuffer} backed by a JediTerm {@link TerminalTextBuffer}.
 * Wraps each registered {@link OutputBuffer.Listener} in a JDK listener and
 * keeps the wrappers in a set so {@link #removeListener} can find and
 * unregister the right one.
 */
public final class JediOutputBuffer implements OutputBuffer {

  private final TerminalTextBuffer buffer;
  private final Set<JediListener> wrappers = new HashSet<>();

  public JediOutputBuffer(TerminalTextBuffer buffer) {
    this.buffer = Objects.requireNonNull(buffer, "buffer");
  }

  @Override
  public void addListener(Listener listener) {
    Objects.requireNonNull(listener, "listener");
    JediListener w = new JediListener(listener);
    synchronized (wrappers) {
      wrappers.add(w);
    }
    buffer.addChangesListener(w);
  }

  @Override
  public void removeListener(Listener listener) {
    Objects.requireNonNull(listener, "listener");
    JediListener target = null;
    synchronized (wrappers) {
      for (JediListener w : wrappers) {
        if (w.user == listener) { target = w; break; }
      }
      if (target != null) wrappers.remove(target);
    }
    if (target != null) buffer.removeChangesListener(target);
  }

  @Override
  public int getLineCount() {
    return buffer.getScreenLinesCount();
  }

  @Override
  public String getLineText(int index) {
    // JediTerm throws if the index is out of bounds — let it bubble up. The
    // runner guards its cursor against the current getLineCount() before
    // calling this.
    return buffer.getLine(index).getText();
  }

  private static final class JediListener implements TextBufferChangesListener {
    final Listener user;
    JediListener(Listener user) { this.user = user; }
    @Override public void linesChanged(int count) { user.linesChanged(count); }
  }
}
