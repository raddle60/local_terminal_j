package local.term;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.border.EmptyBorder;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.List;
import java.util.UUID;

/**
 * Modal dialog for adding or editing a {@link TagNode.Folder}.
 *
 * <p>A folder carries only a name; the UI exposes just the name field plus
 * OK/Cancel. On edit, the folder's id and existing children are preserved
 * by the editor — the caller can drop the result straight back into the
 * tree at the same id.
 */
public class FolderEditorDialog extends JDialog {
  private final JTextField nameField = new JTextField(30);
  private final JLabel errorLabel = new JLabel(" ");

  private final boolean isEdit;
  private final UUID id;
  /** Children of the original folder, preserved on edit; empty for new. */
  private final List<TagNode> preservedChildren;
  private TagNode.Folder result;

  public FolderEditorDialog(JFrame owner, TagNode.Folder existing) {
    super(owner, true);
    this.isEdit = existing != null;
    this.id = existing != null ? existing.id() : UUID.randomUUID();
    this.preservedChildren = existing != null ? existing.children() : List.of();
    setTitle(isEdit ? "Edit folder" : "Add folder");
    buildUi();
    if (isEdit) nameField.setText(existing.name());
    pack();
    setLocationRelativeTo(owner);
  }

  public TagNode.Folder getResult() { return result; }

  private void buildUi() {
    JPanel form = new JPanel(new GridBagLayout());
    form.setBorder(new EmptyBorder(12, 12, 12, 12));
    GridBagConstraints c = new GridBagConstraints();
    c.insets = new Insets(4, 4, 4, 4);
    c.anchor = GridBagConstraints.WEST;

    int row = 0;
    c.gridx = 0; c.gridy = row; c.gridwidth = 1; c.weightx = 0;
    form.add(new JLabel("Folder name *"), c);
    c.gridx = 1; c.weightx = 1;
    form.add(nameField, c);

    row++;
    c.gridx = 0; c.gridy = row; c.gridwidth = 2;
    errorLabel.setForeground(java.awt.Color.RED);
    form.add(errorLabel, c);

    JButton ok = new JButton(isEdit ? "Save" : "Add");
    JButton cancel = new JButton("Cancel");
    ok.addActionListener(e -> onOk());
    cancel.addActionListener(e -> { result = null; dispose(); });
    JPanel buttons = new JPanel();
    buttons.add(ok); buttons.add(cancel);

    add(form, BorderLayout.CENTER);
    add(buttons, BorderLayout.SOUTH);
    getRootPane().setDefaultButton(ok);
  }

  private void onOk() {
    String name = nameField.getText().trim();
    if (name.isEmpty()) {
      errorLabel.setText("Folder name is required");
      return;
    }
    result = new TagNode.Folder(id, name, preservedChildren);
    dispose();
  }
}
