package local.term.tools;

import org.apache.batik.anim.dom.SAXSVGDocumentFactory;
import org.apache.batik.transcoder.TranscoderInput;
import org.apache.batik.transcoder.TranscoderOutput;
import org.apache.batik.transcoder.image.PNGTranscoder;
import org.w3c.dom.Document;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates the tiny test fixtures for {@link local.term.IconLoaderTest}.
 * Run once via {@code mvn test-compile} + the {@code exec:java} command
 * in the plan; the output is committed to {@code src/test/resources/fixtures/}.
 */
public final class IconLoaderTestFixtures {
  private IconLoaderTestFixtures() {}

  public static void main(String[] args) throws Exception {
    Path out = Path.of("src/test/resources/fixtures");
    Files.createDirectories(out);

    // PNG: 4x4 solid red.
    BufferedImage png = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < 4; x++)
      for (int y = 0; y < 4; y++)
        png.setRGB(x, y, 0xFF0000);
    ImageIO.write(png, "png", out.resolve("test-pixel.png").toFile());

    // JPEG: 4x4 solid blue.
    BufferedImage jpg = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < 4; x++)
      for (int y = 0; y < 4; y++)
        jpg.setRGB(x, y, 0x0000FF);
    ImageIO.write(jpg, "jpg", out.resolve("test-pixel.jpg").toFile());

    // ICO: 4x4 solid green. Use the bundled javax.imageio first; if absent,
    // fall back to writing a single-image ICO via raw bytes.
    BufferedImage ico = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
    for (int x = 0; x < 4; x++)
      for (int y = 0; y < 4; y++)
        ico.setRGB(x, y, 0x00FF00);
    File icoFile = out.resolve("test-pixel.ico").toFile();
    try {
      // TwelveMonkeys' ICOImageWriterSpi is not on the classpath; use
      // commons-imaging via Imaging.writeImage (works in 1.0.0-alpha6).
      org.apache.commons.imaging.Imaging.writeImage(ico, icoFile,
          org.apache.commons.imaging.ImageFormats.ICO);
    } catch (Exception e) {
      throw new RuntimeException("ICO generation failed: " + e.getMessage(), e);
    }

    // SVG: 4x4 magenta square. Write a valid SVG to disk first, then leave it
    // there (it's the test fixture). No transcoding needed — the SVG is
    // the fixture itself.
    Path svgFile = out.resolve("test-pixel.svg");
    Files.writeString(svgFile,
        "<svg xmlns='http://www.w3.org/2000/svg' width='4' height='4'>"
        + "<rect width='4' height='4' fill='#FF00FF'/>"
        + "</svg>");

    // not-an-image: a plain text file.
    Files.writeString(out.resolve("not-an-image.txt"), "this is not an image");

    System.out.println("Generated fixtures in " + out);
  }
}
