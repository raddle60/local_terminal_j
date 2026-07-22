package local.term;

import com.formdev.flatlaf.FlatDarkLaf;
import java.awt.Font;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.FontUIResource;
import org.slf4j.LoggerFactory;

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
      lockFonts();
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

  /**
   * Lock Swing font defaults against post-setup re-scaling.
   *
   * <p>FlatLaf stores fonts as {@link FontUIResource} in UIManager defaults.
   * Swing's {@code UIManager.getFont()} applies
   * {@code scaleFont()} to every {@code FontUIResource} it reads, multiplying
   * the point size by the current system scale factor. Normally this is
   * idempotent (the stored value is the base size, and the scale factor is
   * constant), but after a Windows screen-lock/unlock cycle the display
   * subsystem can briefly report a transient scale factor. Swing's internal
   * display-change handler then reads the {@code FontUIResource}, multiplies
   * it by the transient factor, and writes the enlarged value BACK into
   * UIManager. On the next display change the same font is scaled AGAIN on
   * top of the already-enlarged size, and menu/button text grows visibly.
   *
   * <p>Terminal fonts are unaffected because JediTerm sets them as plain
   * {@link Font} (via {@code DarkSettingsProvider}), not {@code FontUIResource},
   * so they bypass {@code scaleFont()} entirely.
   *
   * <p>The fix: after L&F install, convert every {@code FontUIResource} in
   * UIManager to a plain {@code Font} at the same size. Plain {@code Font}
   * fails the {@code instanceof FontUIResource} check inside
   * {@code scaleFont()}, so it is returned as-is — no further scaling,
   * regardless of display change events or transient scale factor values.
   *
   * <p>We use {@link Font#deriveFont(float)} rather than
   * {@code new Font(family, style, size)} so the derived {@code Font}
   * reuses the original {@code Font2D} handle. A fresh
   * {@code new Font(family, ...)} constructs a new {@code Font2D}, which
   * rebuilds the composite-font fallback chain — and characters outside
   * the primary font's glyph set (e.g. {@code ≪} U+226A, which needs
   * Symbol fallback) would render as a box.
   */
  private static void lockFonts() {
    int converted = 0;
    for (Object key : UIManager.getDefaults().keySet().toArray()) {
      Object value = UIManager.getDefaults().get(key);
      if (value instanceof FontUIResource fur) {
        // deriveFont preserves the original Font2D (composite fallbacks
        // included); only the point size is re-bound to the same value.
        Font plain = fur.deriveFont(fur.getSize2D());
        UIManager.put(key, plain);
        converted++;
      }
    }
    // Debug log: confirms how many font entries were converted.
    // If FlatLaf changes its defaults structure, a sudden drop in this
    // count would flag that the conversion is no longer catching the
    // right entries.
    LoggerFactory.getLogger(App.class)
        .debug("lockFonts: converted {} FontUIResource entries to Font", converted);
  }
}
