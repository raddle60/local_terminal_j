package local.term;

import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.ProcessTtyConnector;
import com.pty4j.PtyProcess;
import com.pty4j.WinSize;
import org.jetbrains.annotations.NotNull;

import java.nio.charset.Charset;
import java.util.List;

/**
 * TtyConnector for a pty4j {@link com.pty4j.PtyProcess}.
 *
 * JediTerm 3.73 does not ship a built-in PtyProcessTtyConnector, so we
 * subclass {@link ProcessTtyConnector} here. PtyProcess extends
 * {@link Process}, so the parent class' streams work directly.
 *
 * <p>Override {@link #resize(TermSize)} to push the size into the PTY via
 * {@link PtyProcess#setWinSize}. Without this, the PTY's window size stays
 * at the initial {@code setInitialColumns}/{@code setInitialRows} we passed
 * to {@link com.pty4j.PtyProcessBuilder} (120×30) while JediTerm's text
 * buffer resizes to the actual panel dimensions (typically 200×50+). The
 * mismatch is invisible until a child program writes enough output to fill
 * the PTY's internal buffer, at which point cursor Y in JediTerm's model
 * goes out of sync with the shell's cursor — characters typed after a
 * full-screen program (Claude, vim, less) exits are rendered at the wrong
 * row. The official JediTerm demo's {@code PtyProcessTtyConnector} does
 * this override; we mirror it.
 */
public class PtyProcessTtyConnector extends ProcessTtyConnector {
  private final String name;
  private final PtyProcess myProcess;

  public PtyProcessTtyConnector(@NotNull PtyProcess process, @NotNull Charset charset,
                                @NotNull List<String> commandLine) {
    super(process, charset, commandLine);
    this.myProcess = process;
    this.name = String.join(" ", commandLine);
  }

  @Override
  public String getName() {
    return name;
  }

  @Override
  public void resize(@NotNull TermSize termSize) {
    if (isConnected()) {
      myProcess.setWinSize(new WinSize(termSize.getColumns(), termSize.getRows()));
    }
  }

  @Override
  public boolean isConnected() {
    return myProcess.isAlive();
  }
}