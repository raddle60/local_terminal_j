package local.term;

import com.formdev.flatlaf.FlatDarkLaf;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Entry point. Builds the MainFrame on the Event Dispatch Thread.
 *
 * <p>Dark theme is provided by {@link FlatDarkLaf#setup()}. FlatLaf themes
 * every Swing component — including {@code JOptionPane} dialog titles, the
 * settings dialog, file chooser, etc. — in one install. The previous
 * Nimbus + manual UIManager overrides approach missed popup/dialog title
 * colours, which is why we moved to FlatLaf.
 *
 * <p>On Windows 10/11 FlatLaf additionally calls the native
 * {@code DWMWA_USE_IMMERSIVE_DARK_MODE} Dwm attribute so the OS-painted
 * title bar uses the user's "Dark" mode automatically — no custom title
 * bar needed.
 */
public class App {
  public static void main(String[] args) {
    try {
      // Use the L&F's own window decorations when the platform supports
      // them (FlatLaf on macOS/Linux, plus the Dwm dark-mode hint on
      // Windows 10/11). When false, the native chrome stays — but
      // FlatLaf sets the Dwm hint either way for the title bar.
      JFrame.setDefaultLookAndFeelDecorated(true);
      FlatDarkLaf.setup();
    } catch (Exception ex) {
      // FlatDarkLaf doesn't throw, but keep a defensive fallback so the
      // app still launches.
      try {
        UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
      } catch (Exception ignored) { /* fall back to cross-platform L&F */ }
    }

    SwingUtilities.invokeLater(() -> {
      MainFrame frame = new MainFrame();
      frame.setVisible(true);
    });
  }
}
