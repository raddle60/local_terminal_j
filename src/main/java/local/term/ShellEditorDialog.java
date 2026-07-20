package local.term;

import javax.swing.BorderFactory;
import javax.swing.DefaultCellEditor;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Modal dialog for adding or editing a {@link TagNode.Shell}.
 *
 * <p>A shell carries name + shellPath (+ optional iconPath) + startPath +
 * optional {@link AutoScript}. Edit preserves the shell's id; on a
 * successful OK the caller drops the returned Shell back into the tree
 * at that id.
 */
public class ShellEditorDialog extends JDialog {
  private final JTextField nameField = new JTextField(30);
  private final JTextField shellField = new JTextField(30);
  private final JTextField argsField = new JTextField(30);
  private final JTextField iconField = new JTextField(30);
  private final JTextField startField = new JTextField(30);

  // Auto Script section
  private final JCheckBox autoScriptEnabled = new JCheckBox("Enable auto script");
  private final JSpinner timeoutSpinner = new JSpinner(new SpinnerNumberModel(
      AutoScript.DEFAULT_TIMEOUT_MS, 1000, 60_000, 1000));
  private final DefaultTableModel stepsModel = new DefaultTableModel(
      new Object[]{"Wait pattern", "Command"}, 0) {
    @Override public boolean isCellEditable(int row, int col) { return true; }
  };
  private final JTable stepsTable = new JTable(stepsModel);
  private final java.util.List<JPanel> stepButtonRows = new ArrayList<>();
  private JPanel stepButtonsBar;

  private final JLabel errorLabel = new JLabel(" ");

  private final Mode mode;
  private final UUID id;
  private TagNode.Shell result;

  /** Why this dialog is open — drives the title bar and id-handling. */
  public enum Mode {
    /** Brand-new shell, no id yet — OK returns a fresh shell under the chosen parent. */
    ADD,
    /** Existing shell being tweaked in place — OK replaces the same id. */
    EDIT,
    /** Existing shell cloned under the same parent with a fresh id — OK inserts a sibling. */
    DUPLICATE
  }

  /**
   * Backward-compatible constructor: {@code null} {@code existing} ⇒ ADD,
   * non-null ⇒ EDIT. New callers should use {@link #ShellEditorDialog(JFrame, Mode, TagNode.Shell)}.
   */
  public ShellEditorDialog(JFrame owner, TagNode.Shell existing) {
    this(owner, existing == null ? Mode.ADD : Mode.EDIT, existing);
  }

  public ShellEditorDialog(JFrame owner, Mode mode, TagNode.Shell existing) {
    super(owner, true);
    this.mode = mode;
    // ADD / DUPLICATE produce a brand-new entry: caller will appendChild, not
    // nodeChanged. EDIT preserves the original id so the in-tree splice is
    // unambiguous.
    this.id = (mode == Mode.EDIT) ? existing.id() : UUID.randomUUID();
    setTitle(switch (mode) {
      case ADD -> "Add shell";
      case EDIT -> "Edit shell";
      case DUPLICATE -> "Duplicate shell";
    });
    buildUi();
    if (existing != null) populate(existing);
    pack();
    setLocationRelativeTo(owner);
  }

  public TagNode.Shell getResult() { return result; }

  private void buildUi() {
    JPanel form = new JPanel(new GridBagLayout());
    form.setBorder(new EmptyBorder(12, 12, 12, 12));
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(4, 4, 4, 4);
    c.anchor = GridBagConstraints.WEST;
    c.fill = GridBagConstraints.HORIZONTAL;

    int row = 0;
    addRow(form, c, row++, "Name *", nameField, null);
    addRow(form, c, row++, "Shell *", shellField,
        () -> pickFile("Select shell", shellField));
    addRow(form, c, row++, "Shell arguments", argsField, null);
    addRow(form, c, row++, "Icon path", iconField,
        () -> pickFile("Select icon", iconField));
    addRow(form, c, row++, "Start path", startField,
        () -> pickDirectory("Select start directory", startField));

    // Auto Script section.
    c.gridx = 0; c.gridy = row++; c.gridwidth = 3; c.weightx = 1;
    JPanel autoPanel = buildAutoScriptPanel();
    form.add(autoPanel, c);

    c.gridx = 0; c.gridy = row++; c.gridwidth = 3;
    errorLabel.setForeground(java.awt.Color.RED);
    form.add(errorLabel, c);

    JButton ok = new JButton(mode == Mode.EDIT ? "Save" : (mode == Mode.DUPLICATE ? "Duplicate" : "Add"));
    JButton cancel = new JButton("Cancel");
    ok.addActionListener(e -> onOk());
    cancel.addActionListener(e -> { result = null; dispose(); });
    JPanel buttons = new JPanel();
    buttons.add(ok); buttons.add(cancel);

    add(form, BorderLayout.CENTER);
    add(buttons, BorderLayout.SOUTH);
    getRootPane().setDefaultButton(ok);
  }

  private JPanel buildAutoScriptPanel() {
    JPanel panel = new JPanel(new BorderLayout(4, 4));
    panel.setBorder(new TitledBorder("Auto Script (optional)"));

    // Top: enable checkbox.
    JPanel north = new JPanel(new BorderLayout());
    north.add(autoScriptEnabled, BorderLayout.WEST);
    autoScriptEnabled.addActionListener(e -> syncAutoScriptEnabled());
    panel.add(north, BorderLayout.NORTH);

    // Center: timeout + table + buttons.
    JPanel center = new JPanel(new BorderLayout(4, 4));
    JPanel timeoutRow = new JPanel(new BorderLayout(4, 4));
    timeoutRow.add(new JLabel("Timeout (ms) per step:"), BorderLayout.WEST);
    timeoutRow.add(timeoutSpinner, BorderLayout.CENTER);
    center.add(timeoutRow, BorderLayout.NORTH);

    stepsTable.setDefaultEditor(Object.class,
        new DefaultCellEditor(new JTextField()));
    stepsTable.getColumnModel().getColumn(0).setPreferredWidth(200);
    stepsTable.getColumnModel().getColumn(1).setPreferredWidth(200);
    // Make newly-added rows visible. Default FlatLaf renders selection with
    // a near-identical background to the row, so users couldn't tell that
    // "Add row" did anything. Colours match the project-wide dark theme
    // (DarkSettingsProvider #1e1e1e) — light-gray alternation would clash
    // with the rest of the dark UI. Visible grid + alternating dark stripes
    // + a steel-blue selection keeps the table legible on dark mode.
    java.awt.Color rowA = new java.awt.Color(43, 43, 46);
    java.awt.Color rowB = new java.awt.Color(54, 54, 58);
    java.awt.Color gridC = new java.awt.Color(80, 80, 85);
    stepsTable.setBackground(rowA);
    stepsTable.setForeground(new java.awt.Color(220, 220, 220));
    stepsTable.setSelectionBackground(
        new java.awt.Color(70, 130, 180));   // steel blue, matches DarkSettingsProvider
    stepsTable.setSelectionForeground(java.awt.Color.WHITE);
    stepsTable.setShowGrid(true);
    stepsTable.setGridColor(gridC);
    stepsTable.setRowHeight(22);
    stepsTable.setIntercellSpacing(new java.awt.Dimension(1, 1));
    stepsTable.setFillsViewportHeight(true);
    stepsTable.setDefaultRenderer(Object.class,
        new javax.swing.table.DefaultTableCellRenderer() {
          @Override
          public java.awt.Component getTableCellRendererComponent(
              JTable table, Object value, boolean isSelected,
              boolean hasFocus, int row, int column) {
            java.awt.Component c = super.getTableCellRendererComponent(
                table, value, isSelected, hasFocus, row, column);
            if (!isSelected) {
              c.setBackground(row % 2 == 0 ? rowA : rowB);
              c.setForeground(new java.awt.Color(220, 220, 220));
            }
            return c;
          }
        });
    JScrollPane stepsScroll = new JScrollPane(stepsTable);
    // BorderLayout.CENTER will otherwise expand the scroll pane to fill all
    // remaining vertical space in the dialog. Constrain its preferred and
    // maximum height so the Auto Script section stays compact — roughly half
    // of what an unconstrained BorderLayout would give it. Width is left at
    // the scroll pane's natural preferred width.
    java.awt.Dimension scrollPref = stepsScroll.getPreferredSize();
    java.awt.Dimension scrollMax = stepsScroll.getMaximumSize();
    int compactHeight = Math.max(80, scrollPref.height / 2);
    stepsScroll.setPreferredSize(new java.awt.Dimension(scrollPref.width, compactHeight));
    stepsScroll.setMaximumSize(new java.awt.Dimension(scrollMax.width, compactHeight));
    center.add(stepsScroll, BorderLayout.CENTER);

    JPanel controls = new JPanel();
    stepButtonsBar = controls;
    JButton add = new JButton("Add row");
    JButton del = new JButton("Delete selected");
    JButton up = new JButton("Move up");
    JButton down = new JButton("Move down");
    add.addActionListener(e -> stepsModel.addRow(new Object[]{"", ""}));
    del.addActionListener(e -> {
      int r = stepsTable.getSelectedRow();
      if (r >= 0) stepsModel.removeRow(r);
    });
    up.addActionListener(e -> moveRow(-1));
    down.addActionListener(e -> moveRow(+1));
    controls.add(add); controls.add(del); controls.add(up); controls.add(down);
    center.add(controls, BorderLayout.SOUTH);

    panel.add(center, BorderLayout.CENTER);
    syncAutoScriptEnabled();
    return panel;
  }

  private void moveRow(int delta) {
    int r = stepsTable.getSelectedRow();
    if (r < 0) return;
    int target = r + delta;
    if (target < 0 || target >= stepsModel.getRowCount()) return;
    Object[] rowData = new Object[stepsModel.getColumnCount()];
    for (int c = 0; c < rowData.length; c++) rowData[c] = stepsModel.getValueAt(r, c);
    stepsModel.removeRow(r);
    stepsModel.insertRow(target, rowData);
    stepsTable.setRowSelectionInterval(target, target);
  }

  private void syncAutoScriptEnabled() {
    boolean enabled = autoScriptEnabled.isSelected();
    timeoutSpinner.setEnabled(enabled);
    stepsTable.setEnabled(enabled);
    if (stepButtonsBar != null) {
      for (java.awt.Component comp : stepButtonsBar.getComponents()) {
        comp.setEnabled(enabled);
      }
    }
  }

  private void addRow(JPanel form, GridBagConstraints c, int row, String label,
                      JTextField field, Runnable picker) {
    c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0;
    form.add(new JLabel(label), c);
    c.gridx = 1; c.weightx = 1;
    form.add(field, c);
    if (picker != null) {
      JButton browse = new JButton("...");
      browse.addActionListener(e -> picker.run());
      c.gridx = 2; c.weightx = 0;
      form.add(browse, c);
    }
  }

  private void pickFile(String title, JTextField target) {
    JFileChooser fc = new JFileChooser();
    fc.setDialogTitle(title);
    if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      File f = fc.getSelectedFile();
      target.setText(f.getAbsolutePath());
    }
  }

  private void pickDirectory(String title, JTextField target) {
    JFileChooser fc = new JFileChooser();
    fc.setDialogTitle(title);
    fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
    if (fc.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
      File f = fc.getSelectedFile();
      target.setText(f.getAbsolutePath());
    }
  }

  private void populate(TagNode.Shell s) {
    nameField.setText(s.name());
    shellField.setText(s.shellPath());
    if (s.shellArgs() != null) argsField.setText(s.shellArgs());
    if (s.iconPath() != null) iconField.setText(s.iconPath());
    startField.setText(s.startPath());
    if (s.autoScript() != null) {
      // Restore the checkbox from the persisted enabled flag, not from
      // mere presence — a saved-but-disabled script (user un-checked it
      // after editing) must come back un-checked, while its rows must
      // still be in the table for re-enabling.
      autoScriptEnabled.setSelected(s.autoScript().enabled());
      timeoutSpinner.setValue(s.autoScript().timeoutMs());
      stepsModel.setRowCount(0);
      for (Step step : s.autoScript().steps()) {
        stepsModel.addRow(new Object[]{step.waitPattern(), step.command()});
      }
    }
    syncAutoScriptEnabled();
  }

  private void onOk() {
    String name = nameField.getText().trim();
    String shell = shellField.getText().trim();
    String args = argsField.getText().trim();
    String icon = iconField.getText().trim();
    String start = startField.getText().trim();

    if (name.isEmpty()) { errorLabel.setText("Name is required"); return; }
    if (shell.isEmpty()) { errorLabel.setText("Shell path is required"); return; }
    if (resolveShellPath(shell) == null) {
      errorLabel.setText("Shell path not found: " + shell
          + " (not in PATH and not an existing file path)");
      return;
    }
    // startPath is optional — default to the user's home directory.
    if (start.isEmpty()) start = System.getProperty("user.home");

    // Three-way save:
    //   checked + ≥1 step → AutoScript(timeout, steps, true)
    //   unchecked + ≥1 step → AutoScript(timeout, steps, false)  (preserve rows
    //                       so the user can re-enable without re-typing)
    //   unchecked + 0 steps → null  (don't persist a useless empty record)
    boolean enabled = autoScriptEnabled.isSelected();
    int timeout = (Integer) timeoutSpinner.getValue();
    List<Step> steps = new ArrayList<>();
    for (int i = 0; i < stepsModel.getRowCount(); i++) {
      String wp = asString(stepsModel.getValueAt(i, 0));
      String cmd = asString(stepsModel.getValueAt(i, 1));
      steps.add(new Step(wp, cmd));
    }
    AutoScript autoScript;
    if (enabled && steps.isEmpty()) {
      errorLabel.setText("Add at least one auto-script step");
      return;
    } else if (steps.isEmpty()) {
      autoScript = null;
    } else {
      try {
        autoScript = new AutoScript(timeout, steps, enabled);
      } catch (IllegalArgumentException e) {
        errorLabel.setText("Invalid timeout: " + e.getMessage());
        return;
      }
    }

    result = new TagNode.Shell(id, name, shell,
        args.isEmpty() ? null : args,
        icon.isEmpty() ? null : icon, start, autoScript);
    dispose();
  }

  private static String asString(Object o) {
    return o == null ? "" : o.toString();
  }

  /**
   * Resolve a shell-path entry to an existing file.
   *
   * <p>If {@code input} looks like a literal path (contains a {@code /} or
   * {@code \}, or starts with a Windows drive letter like {@code C:}), it
   * is checked directly with {@link File#isFile()}. Otherwise it is treated
   * as a bare command name and looked up in the {@code PATH} environment
   * variable; on Windows, each extension from {@code PATHEXT} is appended
   * in turn (defaulting to {@code .exe .bat .cmd .com} if {@code PATHEXT}
   * is unset).
   *
   * <p>Returns the absolute path of the resolved file, or {@code null} if
   * no match was found. The user's input string is left untouched — we
   * just confirm it points at something real.
   */
  static String resolveShellPath(String input) {
    if (input == null) return null;
    String trimmed = input.trim();
    if (trimmed.isEmpty()) return null;

    boolean looksLikePath = trimmed.contains("/") || trimmed.contains("\\")
        || (trimmed.length() >= 2
            && Character.isLetter(trimmed.charAt(0))
            && trimmed.charAt(1) == ':');

    if (looksLikePath) {
      File f = new File(trimmed);
      return f.isFile() ? f.getAbsolutePath() : null;
    }

    String pathEnv = System.getenv("PATH");
    if (pathEnv == null || pathEnv.isEmpty()) return null;
    String[] dirs = pathEnv.split(File.pathSeparator);

    String[] extensions;
    boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
    if (windows) {
      String pathext = System.getenv("PATHEXT");
      if (pathext != null && !pathext.isEmpty()) {
        // PATHEXT is ";.EXE;.BAT;..." — split on ';' (== File.pathSeparator on Windows).
        String[] raw = pathext.toLowerCase().split(File.pathSeparator);
        extensions = new String[raw.length + 1];
        extensions[0] = "";   // try the bare name first (handles PATHEXT-less cases)
        System.arraycopy(raw, 0, extensions, 1, raw.length);
      } else {
        extensions = new String[]{"", ".exe", ".bat", ".cmd", ".com"};
      }
    } else {
      extensions = new String[]{""};
    }

    for (String dir : dirs) {
      if (dir == null || dir.isEmpty()) continue;
      for (String ext : extensions) {
        File candidate = new File(dir, trimmed + ext);
        if (candidate.isFile()) return candidate.getAbsolutePath();
      }
    }
    return null;
  }
}
