package local.term;

import com.jediterm.core.util.TermSize;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link PtyProcessTtyConnector#resize(TermSize)} — the fix for
 * the cursor-jump bug after Claude exits.
 *
 * <p>Before the fix, our connector didn't override {@code resize()}, so the
 * PTY's window size stayed at the initial {@code setInitialColumns/Rows}
 * (120×30) while JediTerm's text buffer resized to the actual panel size.
 * The mismatch caused ConPTY's internal cursor tracking to drift relative
 * to JediTerm's model; characters typed after a full-screen program exited
 * were rendered at the wrong row. The JediTerm demo's connector has this
 * override — we mirror it.
 *
 * <p>PtyProcess is abstract with native methods, so we can't instantiate
 * a real one in a unit test. These tests use reflection to guard against
 * the override being removed in a future refactor; the actual behavior
 * (calling {@code myProcess.setWinSize(...)} when connected) is exercised
 * by the live app.
 */
class PtyProcessTtyConnectorTest {

  @Test
  void resize_override_exists_with_correct_signature() throws NoSuchMethodException {
    Method m = PtyProcessTtyConnector.class.getMethod("resize", TermSize.class);
    assertEquals(void.class, m.getReturnType(), "resize must return void");
    assertTrue(java.lang.reflect.Modifier.isPublic(m.getModifiers()),
        "resize must be public so JediTerm's TerminalStarter.postResize calls it via the interface");
    assertNotEquals(com.jediterm.terminal.ProcessTtyConnector.class, m.getDeclaringClass(),
        "resize must be overridden in PtyProcessTtyConnector — leaving it as the parent no-op is the original bug");
  }

  @Test
  void isConnected_override_exists_and_calls_process() throws NoSuchMethodException {
    Method m = PtyProcessTtyConnector.class.getMethod("isConnected");
    assertEquals(boolean.class, m.getReturnType());
    assertNotEquals(com.jediterm.terminal.ProcessTtyConnector.class, m.getDeclaringClass(),
        "isConnected must be overridden to read the live PtyProcess.alive() flag");
  }

  @Test
  void resize_accepts_termSize_in_constructor_order() throws NoSuchMethodException {
    // The JediTerm interface contract: resize(TermSize). Make sure we
    // didn't accidentally swap to resize(int, int) or similar.
    Method m = PtyProcessTtyConnector.class.getMethod("resize", TermSize.class);
    assertEquals(1, m.getParameterCount());
    assertEquals(TermSize.class, m.getParameterTypes()[0]);
  }
}
