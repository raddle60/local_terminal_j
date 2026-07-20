package local.term;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AutoScriptTest {
  @Test
  void step_rejectsNullWaitPattern() {
    assertThrows(NullPointerException.class, () -> new Step(null, "cmd"));
  }

  @Test
  void step_rejectsNullCommand() {
    assertThrows(NullPointerException.class, () -> new Step("pat", null));
  }

  @Test
  void step_acceptsEmptyStringsAsNoOp() {
    Step s = new Step("", "");
    assertEquals("", s.waitPattern());
    assertEquals("", s.command());
  }

  @Test
  void autoScript_defaultsConstants() {
    assertEquals(10_000, AutoScript.DEFAULT_TIMEOUT_MS);
    assertEquals(100, AutoScript.STEP_INTERVAL_MS);
  }

  @Test
  void autoScript_nullSteps_isTreatedAsEmpty() {
    AutoScript as = new AutoScript(5000, null);
    assertTrue(as.steps().isEmpty());
  }

  @Test
  void autoScript_stepsListIsImmutable() {
    AutoScript as = new AutoScript(5000,
        List.of(new Step("p", "c")));
    assertThrows(UnsupportedOperationException.class,
        () -> as.steps().add(new Step("x", "y")));
  }

  @Test
  void autoScript_rejectsNonPositiveTimeout() {
    assertThrows(IllegalArgumentException.class,
        () -> new AutoScript(0, List.of()));
    assertThrows(IllegalArgumentException.class,
        () -> new AutoScript(-1, List.of()));
  }

  @Test
  void autoScript_legacy2ArgConstructor_defaultsEnabledToTrue() {
    // The 2-arg constructor exists so pre-existing callers (and any
    // future ones that don't care about the toggle) get the prior
    // behaviour: a constructed AutoScript is enabled by default.
    AutoScript as = new AutoScript(5000,
        List.of(new Step("login:", "user")));
    assertTrue(as.enabled(),
        "legacy 2-arg ctor must default enabled() to true");
  }

  @Test
  void autoScript_3ArgConstructor_storesEnabledFlag() {
    AutoScript on = new AutoScript(5000, List.of(), true);
    AutoScript off = new AutoScript(5000, List.of(), false);
    assertTrue(on.enabled());
    assertFalse(off.enabled());
  }
}
