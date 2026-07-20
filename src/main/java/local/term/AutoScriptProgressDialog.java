package local.term;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dialog;
import java.awt.Window;
import java.awt.event.ActionEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

/**
 * Modeless dialog that visualises an {@link AutoScriptRunner}'s progress in
 * real time. Displays a determinate {@link JProgressBar}, a status label
 * describing the current step, and a read-only {@link JTable} listing every
 * step with its current state (Pending / Waiting / Sent / Failed /
 * Cancelled).
 *
 * <p>The dialog implements {@link AutoScriptRunner.ProgressListener} and
 * marshals every callback to the EDT via {@link SwingUtilities#invokeLater}.
 * The runner itself stays Swing-agnostic.
 *
 * <p>Closing rules (per product decision):
 * <ul>
 *   <li><b>While the script is running</b> — the Close button is disabled
 *       and the window-X is ignored. The user must wait for the script to
 *       finish or fail.</li>
 *   <li><b>On {@code onFinished}</b> — the dialog auto-disposes after a
 *       short grace period (~1 s) so the user sees the completed state.</li>
 *   <li><b>On {@code onFailed}</b> — the Close button is enabled and the
 *       window-X is honoured; the dialog stays open until the user closes
 *       it manually so they can read the failure details.</li>
 *   <li><b>On {@code onCancelled}</b> — disposed immediately (the terminal
 *       session is already gone, no point keeping the dialog alive).</li>
 * </ul>
 *
 * <p>Constructed but not visible — the caller decides when to
 * {@link #showIfActive} (after the launcher confirms the session
 * succeeded), preventing orphan dialogs on launch failure.
 */
public class AutoScriptProgressDialog extends JDialog
    implements AutoScriptRunner.ProgressListener {

  private static final Logger LOG = LoggerFactory.getLogger(AutoScriptProgressDialog.class);

  /** Visual + semantic state of a single step in the table. */
  enum StepStatus { PENDING, WAITING, SENT, FAILED, CANCELLED }

  // Visual palette — picked to read clearly on FlatLaf Dark.
  private static final Color COLOR_PENDING  = new Color(0xB0, 0xB0, 0xB0);
  private static final Color COLOR_WAITING  = new Color(0x5B, 0x9B, 0xD9);
  private static final Color COLOR_SENT     = new Color(0x6A, 0x99, 0x55);
  private static final Color COLOR_FAILED   = new Color(0xF4, 0x87, 0x71);
  private static final Color COLOR_CANCELLED = new Color(0x80, 0x80, 0x80);

  private final String tagName;
  private final StepRow[] rows;
  private final StepTableModel tableModel;
  private final JProgressBar progressBar;
  private final JLabel statusLabel;
  private final JButton closeButton;

  /**
   * True once the script has settled to a state where the user may
   * close the dialog (failed). False while the runner is still
   * executing, or after a successful auto-dispose. Volatile so
   * reads from any thread stay correct.
   */
  private volatile boolean closable;
  /** Set after the dialog has been disposed — used to short-circuit late callbacks. */
  private volatile boolean disposed;

  /**
   * Builds the dialog modeless and invisible. Call {@link #showIfActive}
   * after the surrounding terminal session is confirmed registered so a
   * failed launch never leaves an orphan window.
   */
  public AutoScriptProgressDialog(Window owner, String tagName, AutoScript script) {
    super(owner, "Auto-script: " + tagName, Dialog.ModalityType.MODELESS);
    this.tagName = tagName;
    this.rows = new StepRow[script.steps().size()];
    for (int i = 0; i < rows.length; i++) {
      rows[i] = new StepRow(script.steps().get(i));
    }
    this.tableModel = new StepTableModel(rows);

    setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
    addWindowListener(new WindowAdapter() {
      @Override public void windowClosing(WindowEvent e) {
        // X click is ignored until the script has settled to a state
        // where closing is meaningful (failed). Successful runs dispose
        // themselves; cancelled runs dispose themselves too.
        if (closable) disposeNow();
      }
    });

    JPanel root = new JPanel(new BorderLayout(8, 8));
    root.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

    // --- North: header + status -------------------------------------------
    JLabel headerLabel = new JLabel("Auto-script: " + tagName);
    headerLabel.setFont(headerLabel.getFont().deriveFont(java.awt.Font.BOLD));
    statusLabel = new JLabel("Starting auto-script...");
    statusLabel.setForeground(COLOR_WAITING);
    JPanel north = new JPanel(new BorderLayout(0, 4));
    north.add(headerLabel, BorderLayout.NORTH);
    north.add(statusLabel, BorderLayout.CENTER);

    progressBar = new JProgressBar(0, Math.max(1, rows.length));
    progressBar.setValue(0);
    progressBar.setStringPainted(true);
    progressBar.setString("0 / " + rows.length);
    north.add(progressBar, BorderLayout.SOUTH);
    root.add(north, BorderLayout.NORTH);

    // --- Center: step table ----------------------------------------------
    JTable table = new JTable(tableModel);
    table.setFillsViewportHeight(true);
    table.setRowSelectionAllowed(false);
    table.setColumnSelectionAllowed(false);
    table.setEnabled(false);
    table.setShowGrid(false);
    table.setIntercellSpacing(new java.awt.Dimension(0, 0));
    table.getColumnModel().getColumn(0).setPreferredWidth(40);   // #
    table.getColumnModel().getColumn(0).setMaxWidth(60);
    table.getColumnModel().getColumn(1).setPreferredWidth(90);   // Status
    table.getColumnModel().getColumn(1).setMaxWidth(110);
    table.getColumnModel().getColumn(2).setPreferredWidth(180);  // Wait pattern
    table.getColumnModel().getColumn(3).setPreferredWidth(220);  // Command
    StatusRenderer renderer = new StatusRenderer();
    table.setDefaultRenderer(Object.class, renderer);
    table.getColumnModel().getColumn(1).setCellRenderer(renderer);
    JScrollPane scroll = new JScrollPane(table);
    scroll.setPreferredSize(new java.awt.Dimension(560, Math.min(220,
        24 + 22 * Math.max(1, rows.length))));
    root.add(scroll, BorderLayout.CENTER);

    // --- South: hint + close button --------------------------------------
    // Close button starts disabled — the runner is still executing and the
    // user must wait for it to finish or fail. onFailed is the only event
    // that flips it on (the user is then invited to dismiss the dialog
    // themselves after reading the failure).
    closeButton = new JButton("Close");
    closeButton.setEnabled(false);
    closeButton.addActionListener(e -> {
      if (closable) disposeNow();
    });
    closeButton.setToolTipText("Available once the auto-script settles "
        + "(succeeds or fails)");
    JPanel south = new JPanel(new BorderLayout());
    JLabel hint = new JLabel("Auto-script is running — please wait.");
    hint.setForeground(new Color(0xA0, 0xA0, 0xA0));
    south.add(hint, BorderLayout.WEST);
    south.add(closeButton, BorderLayout.EAST);
    root.add(south, BorderLayout.SOUTH);

    setContentPane(root);
    pack();
    setLocationRelativeTo(owner);
  }

  /** Show the dialog if not already disposed. Safe to call repeatedly. */
  public void showIfActive() {
    if (disposed) return;
    if (!isVisible()) setVisible(true);
  }

  /**
   * True after the user has closed the dialog (only possible after a
   * failure — successful / cancelled runs dispose themselves). Used by
   * the fallback timeout popup to decide whether to surface another
   * notification.
   */
  public boolean wasDismissed() { return closable && disposed; }

  /** True after the dialog has been disposed (final state). */
  public boolean isDisposed() { return disposed; }

  /** Dispose the dialog (release Swing resources). Idempotent. */
  public void disposeNow() {
    runOnEdt(() -> {
      if (disposed) return;
      disposed = true;
      try { dispose(); } catch (RuntimeException ignored) { }
    });
  }

  // ----------------------------------------------------------------------
  // ProgressListener — invoked from the runner's daemon thread.
  // Every callback hops to the EDT before mutating Swing state.
  // ----------------------------------------------------------------------

  @Override
  public void onStarted(int totalSteps, String tagName) {
    runOnEdt(() -> {
      if (disposed) return;
      progressBar.setMaximum(Math.max(1, totalSteps));
      progressBar.setValue(0);
      progressBar.setString("0 / " + totalSteps);
      statusLabel.setText("Starting auto-script for '" + tagName + "'...");
      statusLabel.setForeground(COLOR_WAITING);
      // Mark any steps after totalSteps (shouldn't happen, defensive) as Pending.
      for (int i = totalSteps; i < rows.length; i++) {
        rows[i].status = StepStatus.PENDING;
      }
      tableModel.fireTableRowsUpdated(0, rows.length);
    });
  }

  @Override
  public void onStepStarted(int stepIdx, Step step) {
    runOnEdt(() -> {
      if (disposed || stepIdx < 0 || stepIdx >= rows.length) return;
      rows[stepIdx].status = StepStatus.WAITING;
      tableModel.fireTableRowsUpdated(stepIdx, stepIdx);
      String desc;
      if (step.waitPattern().isBlank()) {
        desc = (stepIdx == 0)
            ? "Step " + (stepIdx + 1) + "/" + rows.length
                + ": waiting for initial shell output"
            : "Step " + (stepIdx + 1) + "/" + rows.length
                + ": sending without a pattern wait";
      } else {
        desc = "Step " + (stepIdx + 1) + "/" + rows.length
            + ": waiting for '" + step.waitPattern() + "'";
      }
      statusLabel.setText(desc);
      statusLabel.setForeground(COLOR_WAITING);
    });
  }

  @Override
  public void onStepCompleted(int stepIdx, Step step) {
    runOnEdt(() -> {
      if (disposed || stepIdx < 0 || stepIdx >= rows.length) return;
      rows[stepIdx].status = StepStatus.SENT;
      progressBar.setValue(stepIdx + 1);
      progressBar.setString((stepIdx + 1) + " / " + rows.length);
      tableModel.fireTableRowsUpdated(stepIdx, stepIdx);
      String cmd = abbreviate(step.command());
      statusLabel.setText("Step " + (stepIdx + 1) + "/" + rows.length
          + ": sent '" + cmd + "'");
      statusLabel.setForeground(COLOR_SENT);
    });
  }

  @Override
  public void onFinished(int totalSteps) {
    runOnEdt(() -> {
      if (disposed) return;
      // Mark any still-pending rows as sent — the script completed so
      // every queued step must have run. This handles a script where the
      // final step is sent without an onStepCompleted (shouldn't happen
      // given current runner logic, but defensive).
      for (StepRow r : rows) {
        if (r.status == StepStatus.PENDING || r.status == StepStatus.WAITING) {
          r.status = StepStatus.SENT;
        }
      }
      progressBar.setValue(totalSteps);
      progressBar.setString(totalSteps + " / " + totalSteps);
      tableModel.fireTableRowsUpdated(0, rows.length);
      statusLabel.setText("Auto-script finished; all commands were sent.");
      statusLabel.setForeground(COLOR_SENT);
      // Successful run: auto-dispose after ~1 s so the user sees the
      // completed state. Close button stays disabled — there's nothing
      // left to read, the dialog will be gone momentarily.
      if (!disposed) {
        Timer t = new Timer(1000, e -> disposeNow());
        t.setRepeats(false);
        t.start();
      }
    });
  }

  @Override
  public void onFailed(int stepIdx, Step step,
                       AutoScriptRunner.FailureKind kind, String reason) {
    runOnEdt(() -> {
      if (disposed) return;
      if (stepIdx >= 0 && stepIdx < rows.length) {
        rows[stepIdx].status = StepStatus.FAILED;
        tableModel.fireTableRowsUpdated(stepIdx, stepIdx);
      }
      String tag = (kind == null) ? "ERROR" : kind.name();
      statusLabel.setText("Failed at step " + (stepIdx + 1) + "/"
          + rows.length + " (" + tag + "): " + reason);
      statusLabel.setForeground(COLOR_FAILED);
      progressBar.setString(progressBar.getValue() + " / " + rows.length
          + " — failed");
      // Failed: open the dialog for user dismissal. X / Close both dispose
      // now; the dialog stays visible until the user reads the error and
      // clicks.
      closable = true;
      closeButton.setEnabled(true);
      closeButton.setText("Close");
      closeButton.setToolTipText("Close this dialog");
      // Find and update the hint label (it's the first WEST component).
      updateHint("The auto-script failed — close this dialog when you have "
          + "read the error above.");
    });
  }

  @Override
  public void onCancelled() {
    runOnEdt(() -> {
      if (disposed) return;
      for (StepRow r : rows) {
        if (r.status == StepStatus.PENDING || r.status == StepStatus.WAITING) {
          r.status = StepStatus.CANCELLED;
        }
      }
      statusLabel.setText("Auto-script cancelled (terminal closed).");
      statusLabel.setForeground(COLOR_CANCELLED);
      tableModel.fireTableRowsUpdated(0, rows.length);
      // The terminal session itself is gone, so dispose the dialog
      // immediately — nothing to read, nothing for the user to do.
      disposeNow();
    });
  }

  /** Walk the south panel to find the hint JLabel and update its text. */
  private void updateHint(String text) {
    java.awt.Component c = getContentPane();
    if (!(c instanceof JPanel root)) return;
    java.awt.Component south = ((BorderLayout) root.getLayout())
        .getLayoutComponent(BorderLayout.SOUTH);
    if (!(south instanceof JPanel southPanel)) return;
    java.awt.Component west = ((BorderLayout) southPanel.getLayout())
        .getLayoutComponent(BorderLayout.WEST);
    if (west instanceof JLabel label) label.setText(text);
  }

  // ----------------------------------------------------------------------

  private void runOnEdt(Runnable action) {
    if (SwingUtilities.isEventDispatchThread()) {
      try { action.run(); }
      catch (RuntimeException e) {
        LOG.warn("Progress dialog action threw: {}", e.getMessage(), e);
      }
    } else {
      SwingUtilities.invokeLater(() -> {
        try { action.run(); }
        catch (RuntimeException e) {
          LOG.warn("Progress dialog action threw: {}", e.getMessage(), e);
        }
      });
    }
  }

  private static String abbreviate(String s) {
    if (s == null) return "(null)";
    String one = s.replace('\n', ' ').replace('\r', ' ');
    return one.length() <= 80 ? one : one.substring(0, 77) + "...";
  }

  /** Read-only row backing the table model. Mutated only on the EDT. */
  static final class StepRow {
    final Step step;
    volatile StepStatus status;
    StepRow(Step step) { this.step = step; this.status = StepStatus.PENDING; }
  }

  /** Minimal table model exposing the StepStatus + step fields. */
  private static final class StepTableModel extends AbstractTableModel {
    private final StepRow[] rows;
    private final String[] columns = {"#", "Status", "Wait pattern", "Command"};
    StepTableModel(StepRow[] rows) { this.rows = rows; }
    @Override public int getRowCount() { return rows.length; }
    @Override public int getColumnCount() { return columns.length; }
    @Override public String getColumnName(int column) { return columns[column]; }
    @Override public Object getValueAt(int rowIndex, int columnIndex) {
      StepRow r = rows[rowIndex];
      Step s = r.step;
      switch (columnIndex) {
        case 0: return rowIndex + 1;
        case 1: return label(r.status);
        case 2: return s.waitPattern().isBlank() ? "(none)" : s.waitPattern();
        case 3: return s.command().isEmpty() ? "(no-op)" : s.command();
        default: return "";
      }
    }
    private static String label(StepStatus s) {
      switch (s) {
        case PENDING:   return "Pending";
        case WAITING:   return "Waiting";
        case SENT:      return "Sent";
        case FAILED:    return "Failed";
        case CANCELLED: return "Cancelled";
        default: return s.toString();
      }
    }
  }

  /** Colours the Status cell to match its semantic state. */
  private static final class StatusRenderer extends DefaultTableCellRenderer {
    @Override
    public Component getTableCellRendererComponent(JTable table, Object value,
        boolean isSelected, boolean hasFocus, int row, int column) {
      Component c = super.getTableCellRendererComponent(table, value,
          isSelected, hasFocus, row, column);
      // Reset to default then override per column.
      c.setForeground(table.getForeground());
      c.setBackground(table.getBackground());
      if (column == 1 && value instanceof String label) {
        Color color;
        switch (label) {
          case "Waiting":   color = COLOR_WAITING; break;
          case "Sent":      color = COLOR_SENT; break;
          case "Failed":    color = COLOR_FAILED; break;
          case "Cancelled": color = COLOR_CANCELLED; break;
          default:          color = COLOR_PENDING;
        }
        c.setForeground(color);
        setFont(getFont().deriveFont(java.awt.Font.BOLD));
      } else {
        setFont(getFont().deriveFont(java.awt.Font.PLAIN));
      }
      return c;
    }
  }

  // ----------------------------------------------------------------------
  // Test-only hooks (package-private) so AutoScriptRunnerTest can assert
  // the dialog-side state machine without instantiating Swing on headless CI.
  // ----------------------------------------------------------------------

  /**
   * Apply a callback directly to the EDT — exposed for tests that need to
   * drive the listener methods synchronously. Production callers should
   * rely on {@link AutoScriptRunner.ProgressListener} dispatch from the
   * runner thread, which already hops to the EDT.
   */
  void applyOnEdtForTest(Runnable action) { runOnEdt(action); }

  /** Visible-for-tests: the row statuses after the latest EDT update. */
  StepStatus[] snapshotStatusesForTest() {
    StepStatus[] out = new StepStatus[rows.length];
    for (int i = 0; i < rows.length; i++) out[i] = rows[i].status;
    return out;
  }

  /** Visible-for-tests: read the progress bar value. */
  int progressValueForTest() { return progressBar.getValue(); }
}