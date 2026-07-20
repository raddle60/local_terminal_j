package local.term.tools;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * One-off setup tool: produces a 32x32 generic-terminal icon
 * ({@code default.png}) for the shell tree. Run once; the output is
 * committed to the repo.
 */
public final class DefaultIconGenerator {
  private DefaultIconGenerator() {}

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      System.err.println("Usage: DefaultIconGenerator <outputPngPath>");
      System.exit(1);
    }
    BufferedImage img = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
    Graphics2D g = img.createGraphics();
    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
    g.setColor(new Color(0x60, 0x60, 0x60));
    g.fillRoundRect(3, 6, 26, 20, 4, 4);
    g.setColor(new Color(0x20, 0x20, 0x20));
    g.fillRect(13, 28, 6, 2);
    g.fillRect(10, 30, 12, 1);
    g.setColor(new Color(0xE0, 0xE0, 0xE0));
    g.fillRect(5, 8, 22, 2);
    g.fillRect(5, 12, 22, 2);
    g.fillRect(5, 16, 22, 2);
    g.fillRect(5, 20, 14, 2);
    g.dispose();
    File out = new File(args[0]);
    if (out.getParentFile() != null) out.getParentFile().mkdirs();
    ImageIO.write(img, "PNG", out);
    System.out.println("Wrote " + out);
  }
}