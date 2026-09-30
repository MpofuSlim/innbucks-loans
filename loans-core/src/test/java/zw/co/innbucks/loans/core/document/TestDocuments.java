package zw.co.innbucks.loans.core.document;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Base64;

/**
 * Documents drawn for tests: a "page" of text-like marks, a signature, blank and blurred variants, and
 * PDFs. Drawn with shapes, never fonts, so they come out the same on any machine.
 */
public final class TestDocuments {

    private TestDocuments() {
    }

    /** A page of dark word-shaped marks on white, like a printed payslip. */
    public static BufferedImage page(int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        g.setColor(Color.BLACK);
        int glyph = Math.max(4, height / 90);
        for (int y = glyph * 4; y < height - glyph * 4; y += glyph * 3) {
            for (int x = glyph * 3, word = 0; x < width - glyph * 12; word++) {
                int wordWidth = glyph * (3 + (word * 7 + y) % 6);
                for (int letter = x; letter < x + wordWidth; letter += glyph) {
                    g.fillRect(letter, y, glyph - 2, glyph + ((letter / glyph) % 3 == 0 ? glyph / 2 : 0));
                }
                x += wordWidth + glyph * 2;
            }
        }
        g.dispose();
        return image;
    }

    /** A signature stroke; on a transparent background when {@code transparent}, as signature pads export. */
    public static BufferedImage signature(int width, int height, boolean transparent) {
        BufferedImage image = new BufferedImage(width, height,
                transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        if (!transparent) {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
        }
        g.setColor(Color.BLACK);
        g.setStroke(new BasicStroke(3));
        for (int x = 10; x < width - 20; x += 15) {
            g.drawLine(x, height / 2 + (x % 30) - 15, x + 15, height / 2 - (x % 25) + 10);
        }
        g.dispose();
        return image;
    }

    /** An image of one colour. */
    public static BufferedImage flat(int width, int height, Color colour, boolean transparent) {
        BufferedImage image = new BufferedImage(width, height,
                transparent ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        if (!transparent) {
            Graphics2D g = image.createGraphics();
            g.setColor(colour);
            g.fillRect(0, 0, width, height);
            g.dispose();
        }
        return image;
    }

    /** A Gaussian blur of the given radius (sigma), as an out-of-focus photo. */
    public static BufferedImage blurred(BufferedImage source, double sigma) {
        int radius = (int) Math.ceil(sigma * 3);
        float[] kernel = new float[2 * radius + 1];
        float sum = 0;
        for (int i = -radius; i <= radius; i++) {
            kernel[i + radius] = (float) Math.exp(-(i * i) / (2 * sigma * sigma));
            sum += kernel[i + radius];
        }
        for (int i = 0; i < kernel.length; i++) {
            kernel[i] /= sum;
        }
        ConvolveOp horizontal = new ConvolveOp(new Kernel(kernel.length, 1, kernel), ConvolveOp.EDGE_NO_OP, null);
        ConvolveOp vertical = new ConvolveOp(new Kernel(1, kernel.length, kernel), ConvolveOp.EDGE_NO_OP, null);
        return vertical.filter(horizontal.filter(source, null), null);
    }

    public static byte[] encode(BufferedImage image, String format) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            if (!ImageIO.write(image, format, out)) {
                throw new IllegalStateException("No writer for " + format);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A PDF of the given number of pages, each with a filled box on it. */
    public static byte[] pdf(int pages) {
        try (PDDocument document = new PDDocument()) {
            for (int i = 0; i < pages; i++) {
                PDPage page = new PDPage();
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.addRect(50, 600, 300, 20);
                    content.fill();
                }
            }
            return save(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A one-page PDF protected with an owner password and, when given, a password needed to open it. */
    public static byte[] protectedPdf(String userPassword) {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.protect(new StandardProtectionPolicy("owner-secret", userPassword, new AccessPermission()));
            return save(document);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static byte[] save(PDDocument document) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        document.save(out);
        return out.toByteArray();
    }

    public static String base64(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
