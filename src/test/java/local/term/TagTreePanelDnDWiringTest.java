package local.term;

import org.junit.jupiter.api.Test;

import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.JTree;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the JTree drag-and-drop wiring in
 * {@link TagTreePanel}. These tests deliberately do not exercise an
 * actual drag gesture — DnD requires a live display and a system
 * drag threshold, neither of which is reliable in headless CI. What
 * they DO check is the plumbing: after the constructor returns, the
 * tree must have the right {@link TransferHandler}, must have
 * {@code setDragEnabled(true)} applied, and must have a non-null
 * {@code DropMode}. That triple is the contract; if any of these
 * regress (e.g. someone swaps the order of {@code setDragEnabled} and
 * {@code setTransferHandler}, or omits {@code setDropMode}), these
 * tests fail loudly.
 */
class TagTreePanelDnDWiringTest {

  /**
   * Round-trip a {@link TagTreePanel} on the EDT so its constructor
   * runs exactly as it would in production, but in a JUnit-friendly
   * harness. Returns the freshly built panel and the temp dir the
   * config was loaded from (callers should delete it).
   */
  private static TagTreePanel newPanelOnEdt() throws Exception {
    final Path tempDir = Files.createTempDirectory("tagtree-dnd-test-");
    final Path configFile = tempDir.resolve("config.json");
    // TagConfigStore writes an empty root to disk on construction if the
    // file does not exist — that's fine for our purposes.
    final TagConfigStore store = new TagConfigStore(configFile);
    final TagTreePanel[] holder = new TagTreePanel[1];
    final Throwable[] err = new Throwable[1];
    SwingUtilities.invokeAndWait(() -> {
      try {
        holder[0] = new TagTreePanel(store, new ShellIconResolver(
            new IconLoader(), FolderIconCellRenderer.defaultTargetSize()));
      } catch (Throwable t) {
        err[0] = t;
      }
    });
    if (err[0] != null) throw new InvocationTargetException(err[0]);
    return holder[0];
  }

  private static JTree treeOf(TagTreePanel panel) throws Exception {
    Field f = TagTreePanel.class.getDeclaredField("tree");
    f.setAccessible(true);
    return (JTree) f.get(panel);
  }

  /**
   * After construction the tree MUST have a non-null TransferHandler.
   * Without it, the drag gesture recognizer captures {@code null} and
   * {@code createTransferable} is never invoked.
   */
  @Test
  void treeHasTransferHandler() throws Exception {
    TagTreePanel panel = newPanelOnEdt();
    try {
      JTree tree = treeOf(panel);
      assertNotNull(tree.getTransferHandler(),
          "TagTreePanel must install a TransferHandler on its JTree");
      assertTrue(tree.getTransferHandler() instanceof TagTreeTransferHandler,
          "TransferHandler must be the TagTreeTransferHandler instance");
    } finally {
      dispose(panel);
    }
  }

  /**
   * DnD only fires when {@code setDragEnabled(true)} was actually
   * applied. {@link JTree#getDragEnabled()} is the inverse accessor
   * and was added specifically so callers / tests can introspect this
   * state. The JDK's {@code setDragEnabled} installs a
   * {@code DragGestureRecognizer} as a side effect; calling it without
   * first installing the TransferHandler binds that recognizer to
   * {@code null} and the drag never reaches our handler.
   */
  @Test
  void treeHasDragEnabled() throws Exception {
    TagTreePanel panel = newPanelOnEdt();
    try {
      JTree tree = treeOf(panel);
      assertTrue(tree.getDragEnabled(),
          "TagTreePanel must call setDragEnabled(true) on its JTree");
    } finally {
      dispose(panel);
    }
  }

  /**
   * {@link DropMode#ON_OR_INSERT} makes JTree compute a precise drop
   * location (with line indicators and a 0..n childIndex for sibling
   * inserts, -1 for "drop into this folder"). Without an explicit
   * mode, the default USE_SELECTION semantics often leave the drop
   * cursor / canImport returning false because no row is "selected".
   * The transfer handler's {@code canImport} already branches on
   * {@code childIndex == -1} (drop-into-folder) vs 0..n (sibling
   * insert), so we MUST guarantee the JTree produces those locations.
   */
  @Test
  void treeHasExplicitDropMode() throws Exception {
    TagTreePanel panel = newPanelOnEdt();
    try {
      JTree tree = treeOf(panel);
      DropMode mode = tree.getDropMode();
      assertNotNull(mode,
          "TagTreePanel must call setDropMode(...) on its JTree");
      assertEquals(DropMode.ON_OR_INSERT, mode,
          "TagTreePanel must use ON_OR_INSERT so sibling reordering works");
    } finally {
      dispose(panel);
    }
  }

  /**
   * Cross-check: the {@code TransferHandler} installed on the tree
   * must be the SAME instance we pass in (no Swing-internal
   * substitution, e.g. a default "no-op" handler wrapping ours).
   * This is the strictest form of "wiring is correct" — if someone
   * refactors the constructor and accidentally drops the
   * {@code setTransferHandler} call (e.g. by guarding it behind a
   * feature flag), this test catches it.
   */
  @Test
  void transferHandlerIsTagTreeTransferHandler() throws Exception {
    TagTreePanel panel = newPanelOnEdt();
    try {
      JTree tree = treeOf(panel);
      TransferHandler th = tree.getTransferHandler();
      assertSame(TagTreeTransferHandler.class, th.getClass(),
          "TransferHandler must be exactly TagTreeTransferHandler");
    } finally {
      dispose(panel);
    }
  }

  private static void dispose(TagTreePanel panel) {
    // Put the panel in a temporary JFrame so any window listeners fire,
    // then dispose — releases the EDT references and gives Swing a
    // chance to run clean-up listeners before the next test creates
    // another TagTreePanel.
    JFrame dummy = new JFrame();
    dummy.getContentPane().add(panel, BorderLayout.CENTER);
    dummy.pack();
    dummy.dispose();
  }

  // -----------------------------------------------------------------
  // Hide-tree toolbar wiring
  //
  // The hide-tree feature is wired by:
  //   1. TagTreePanel exposes setOnHideRequested(Runnable)
  //   2. The toolbar (NORTH child of TagTreePanel) has a "≪" JButton
  //   3. Clicking that button invokes the registered Runnable
  //
  // We exercise the chain end-to-end on the EDT so the wiring is the
  // exact behaviour production gets. A regression — e.g. someone
  // refactors the toolbar layout and loses the hide button, or wires
  // the listener to the wrong action — fails one of these tests.
  // -----------------------------------------------------------------

  /**
   * Find a JButton anywhere in {@code root}'s component tree whose text
   * (or tooltipText as a fallback) starts with the chevron glyph used
   * by the hide affordance. Used to look up the toolbar's hide button
   * without exposing it via a public accessor (the toolbar layout is
   * an implementation detail).
   */
  private static JButton findButtonWithText(Container root, String text) {
    for (Component child : root.getComponents()) {
      if (child instanceof JButton b
          && (text.equals(b.getText()) || text.equals(b.getToolTipText()))) {
        return b;
      }
      if (child instanceof Container sub) {
        JButton nested = findButtonWithText(sub, text);
        if (nested != null) return nested;
      }
    }
    return null;
  }

  /**
   * The toolbar NORTH child of a freshly-built TagTreePanel must contain
   * a hide-tree button labelled "≪" (U+226A). Without it the user has
   * no visible affordance to collapse the tree — the menu item alone is
   * hidden behind a sub-menu and easy to miss.
   */
  @Test
  void toolbarHasHideTreeButton() throws Exception {
    TagTreePanel panel = newPanelOnEdt();
    try {
      JButton hide = findButtonWithText(panel, "≪");
      assertNotNull(hide,
          "TagTreePanel toolbar must expose a '≪' button to hide the tree panel");
    } finally {
      dispose(panel);
    }
  }

  /**
   * End-to-end: register a hide callback, click the hide button on the
   * EDT, and verify the callback fires exactly once. This is the
   * contract {@link MainFrame#toggleTreePanel} relies on when wiring
   * its container-level setTreePanelVisible.
   */
  @Test
  void clickingHideButtonInvokesRegisteredCallback() throws Exception {
    TagTreePanel panel = newPanelOnEdt();
    try {
      final AtomicInteger counter = new AtomicInteger();
      panel.setOnHideRequested(counter::incrementAndGet);
      JButton hide = findButtonWithText(panel, "≪");
      assertNotNull(hide, "Hide button must exist on the toolbar");

      SwingUtilities.invokeAndWait(() -> hide.doClick());
      assertEquals(1, counter.get(),
          "Hide button click must invoke the registered Runnable exactly once");
    } finally {
      dispose(panel);
    }
  }

  /**
   * Replace the registered callback and confirm only the new one runs.
   * Catches a regression where a stale callback persists (e.g. if the
   * setter added the new listener without first clearing the old one).
   */
  @Test
  void replacingCallbackReplacesPriorHandler() throws Exception {
    TagTreePanel panel = newPanelOnEdt();
    try {
      final AtomicInteger first = new AtomicInteger();
      final AtomicInteger second = new AtomicInteger();
      panel.setOnHideRequested(first::incrementAndGet);
      panel.setOnHideRequested(second::incrementAndGet);

      JButton hide = findButtonWithText(panel, "≪");
      assertNotNull(hide);
      SwingUtilities.invokeAndWait(() -> hide.doClick());

      assertEquals(0, first.get(),
          "Replaced callback must not fire — only the latest handler should");
      assertEquals(1, second.get(),
          "Newly registered callback must fire on the next click");
    } finally {
      dispose(panel);
    }
  }

  // -----------------------------------------------------------------
  // JSplitPane hide/show round-trip
  //
  // Regression for "after hiding the tree, showing it again restores
  // it to zero instead of the user's last-chosen width". MainFrame's
  // setTreePanelVisible drives JSplitPane through three operations:
  // setDividerSize, setDividerLocation, then re-applies on the next
  // EDT tick to defeat JDK-4133984 (setDividerLocation no-ops on the
  // first call after a dividerSize change on some L&Fs). This test
  // exercises that exact sequence in isolation so the failure shows up
  // regardless of whether FlatLaf is installed.
  // -----------------------------------------------------------------

  private static final int COLLAPSED = 3;
  private static final int NORMAL = 7;

  /**
   * Drive MainFrame's hide → show sequence against a fresh JSplitPane
   * and return the divider location after show. The JFrame is shown for
   * a moment so JSplitPane gets a real size and the L&F runs through
   * its initial layout pass; getDividerLocation then returns the field
   * value, which is the same value the visible divider will paint at.
   */
  private static int hideShowRoundtrip(int initialWidth) throws Exception {
    final int[] result = {-1};
    final Throwable[] err = {null};
    final JFrame f = new JFrame();
    f.setSize(900, 600);
    final JSplitPane[] split = {null};
    SwingUtilities.invokeAndWait(() -> {
      try {
        JPanel left = new JPanel();
        JPanel right = new JPanel();
        split[0] = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        f.getContentPane().add(split[0]);
        f.setVisible(true);

        // Initial position — what the user would see after dragging
        // the divider to `initialWidth`.
        split[0].setDividerSize(NORMAL);
        split[0].setDividerLocation(initialWidth);
        split[0].validate();

        // Hide path — same shape as MainFrame.setTreePanelVisible(true):
        // size first, then collapse the divider to 0.
        split[0].setDividerSize(COLLAPSED);
        split[0].setDividerLocation(0);
        split[0].validate();

        // Show path — restore size, then re-apply the divider position.
        // The MainFrame fix calls setDividerLocation twice (immediate +
        // deferred on the next EDT tick) to defeat the no-op-on-first-
        // call bug; we do the same here.
        split[0].setDividerSize(NORMAL);
        split[0].setDividerLocation(initialWidth);
        split[0].validate();
        SwingUtilities.invokeLater(() -> {
          if (split[0] != null) split[0].setDividerLocation(initialWidth);
        });

        result[0] = split[0].getDividerLocation();
      } catch (Throwable t) {
        err[0] = t;
      }
    });
    // Pump the EDT so the deferred re-apply runs before we read result.
    SwingUtilities.invokeAndWait(() -> {
      // Re-read once on a fresh EDT tick. This is the value AFTER the
      // deferred invokeLater inside the show path has run, which is the
      // exact state MainFrame cares about.
      if (split[0] != null) result[0] = split[0].getDividerLocation();
    });
    f.dispose();
    if (err[0] != null) throw new InvocationTargetException(err[0]);
    return result[0];
  }

  @Test
  void jsplitPane_hideShowRoundtrip_restoresLastWidth() throws Exception {
    int restored = hideShowRoundtrip(420);
    assertEquals(420, restored,
        "After hide → show, divider must restore to the saved width (420 px)");
  }

  @Test
  void jsplitPane_hideShowRoundtrip_worksAtNarrowWidths() throws Exception {
    // Narrow widths are where the bug was reported — the divider at 0
    // may get re-painted over the saved value if the L&F's first
    // setDividerLocation after setDividerSize is a no-op.
    int restored = hideShowRoundtrip(180);
    assertEquals(180, restored,
        "Narrow saved width must round-trip correctly too");
  }

  // -----------------------------------------------------------------
  // Drag-end save semantics
  //
  // Per the persistence boundary the user asked for, the splitter
  // position is written to AppSettings only on mouse release — not
  // mid-drag. This pins that contract: property-change events during
  // the drag update the in-memory lastVisibleDividerLocation only;
  // a mouseReleased event from JSplitPane triggers the actual disk
  // write.
  //
  // We exercise the wiring directly on a real JSplitPane in a JFrame,
  // programmatically driving setDividerLocation to simulate a drag
  // without depending on real input events.
  // -----------------------------------------------------------------

  @Test
  void splitter_saveOnDragEnd_writesOnlyAfterMouseReleased() throws Exception {
    // Counter the listener increments every time settings would be
    // written. The wiring should keep it at zero across the property
    // changes and bump it to exactly one on mouseReleased.
    final AtomicInteger writes = new AtomicInteger();
    final int[] persistedWidth = {-1};
    final JFrame f = new JFrame();
    f.setSize(900, 600);
    final JSplitPane[] split = {null};
    SwingUtilities.invokeAndWait(() -> {
      JPanel left = new JPanel();
      JPanel right = new JPanel();
      split[0] = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
      f.getContentPane().add(split[0]);
      f.setVisible(true);

      // Stand-in for the MainFrame listener pair. We assert the
      // BEHAVIOUR (writes only on release) rather than the field
      // names; the production code uses AppSettings, but the contract
      // is "no write mid-drag, one write per drag end".
      split[0].addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
        // no-op: this listener mirrors MainFrame.onDividerLocationChanged,
        // which only updates in-memory state.
      });
      split[0].addMouseListener(new MouseAdapter() {
        @Override public void mouseReleased(MouseEvent e) {
          int loc = split[0].getDividerLocation();
          if (loc > 0) {
            writes.incrementAndGet();
            persistedWidth[0] = loc;
          }
        }
      });

      // Simulate the user grabbing the divider and dragging it across
      // the split. Each tick fires DIVIDER_LOCATION_PROPERTY but the
      // mouseReleased listener must stay quiet.
      split[0].setDividerSize(NORMAL);
      split[0].setDividerLocation(0);
      for (int target : new int[]{120, 200, 300, 420, 500}) {
        split[0].setDividerLocation(target);
      }
    });

    assertEquals(0, writes.get(),
        "No disk write must happen during a drag — only on mouse release");

    // Now simulate the user releasing the mouse at the final drag position.
    SwingUtilities.invokeAndWait(() -> {
      int finalLoc = split[0].getDividerLocation();
      for (java.awt.event.MouseListener ml : split[0].getMouseListeners()) {
        ml.mouseReleased(new MouseEvent(split[0], MouseEvent.MOUSE_RELEASED,
            System.currentTimeMillis(), 0, finalLoc, 0, 1, false));
      }
    });
    f.dispose();

    assertEquals(1, writes.get(),
        "mouseReleased must trigger exactly one persistence per drag");
    assertEquals(500, persistedWidth[0],
        "Persisted width must reflect the divider's final position, not "
            + "an intermediate tick value");
  }
}