package edu.skope.attachment;

import edu.skope.service.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import static org.junit.jupiter.api.Assertions.*;

/** Pure image tests: no Spring, no DB, no network. */
class ImageSanitizerTest {
    final ImageSanitizer sanitizer = new ImageSanitizer();
    static final Color RED = Color.RED, GREEN = Color.GREEN, BLUE = Color.BLUE, YELLOW = Color.YELLOW;
    static final int W = 64, H = 32;

    /** Stored pixels: top-left RED, top-right GREEN, bottom-left BLUE, bottom-right YELLOW. */
    static BufferedImage quadrants(int type) {
        var img = new BufferedImage(W, H, type);
        var g = img.createGraphics();
        g.setColor(RED); g.fillRect(0, 0, W / 2, H / 2);
        g.setColor(GREEN); g.fillRect(W / 2, 0, W / 2, H / 2);
        g.setColor(BLUE); g.fillRect(0, H / 2, W / 2, H / 2);
        g.setColor(YELLOW); g.fillRect(W / 2, H / 2, W / 2, H / 2);
        g.dispose();
        return img;
    }

    static byte[] write(BufferedImage img, String format) throws Exception {
        var out = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(img, format, out));
        return out.toByteArray();
    }

    /** Inserts an APP1 EXIF block right after SOI: IFD0 with Orientation and, optionally, a GPS IFD. */
    static byte[] withExif(byte[] jpeg, int orientation, boolean little, boolean gps) {
        var order = little ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN;
        int entries = gps ? 2 : 1;
        int ifd0Size = 2 + entries * 12 + 4;
        var tiff = ByteBuffer.allocate(8 + ifd0Size + (gps ? 2 + 12 + 4 : 0)).order(order);
        tiff.put(little ? (byte) 'I' : (byte) 'M').put(little ? (byte) 'I' : (byte) 'M').putShort((short) 42).putInt(8);
        tiff.putShort((short) entries);
        tiff.putShort((short) 0x0112).putShort((short) 3).putInt(1).putShort((short) orientation).putShort((short) 0);
        if (gps) tiff.putShort((short) 0x8825).putShort((short) 4).putInt(1).putInt(8 + ifd0Size);
        tiff.putInt(0);
        if (gps) tiff.putShort((short) 1).putShort((short) 1).putShort((short) 2).putInt(2).put((byte) 'N').put((byte) 0).putShort((short) 0).putInt(0);
        byte[] exif = ByteBuffer.allocate(6 + tiff.capacity()).put("Exif\0\0".getBytes(StandardCharsets.ISO_8859_1)).put(tiff.array()).array();
        int length = exif.length + 2;
        var out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);
        out.write(0xFF); out.write(0xE1); out.write(length >> 8); out.write(length & 0xFF);
        out.write(exif, 0, exif.length);
        out.write(jpeg, 2, jpeg.length - 2);
        return out.toByteArray();
    }

    static BufferedImage read(byte[] bytes) throws Exception { return ImageIO.read(new ByteArrayInputStream(bytes)); }

    static void assertColor(Color expected, BufferedImage img, int x, int y) {
        var actual = new Color(img.getRGB(x, y), true);
        int diff = Math.abs(actual.getRed() - expected.getRed()) + Math.abs(actual.getGreen() - expected.getGreen())
                + Math.abs(actual.getBlue() - expected.getBlue());
        assertTrue(diff < 60, "pixel (" + x + "," + y + ") expected " + expected + " but was " + actual);
    }

    /**
     * Expected corners from the EXIF spec wording, independent of the production formulas:
     * each orientation says on which visual side the stored first row (row0) and first column (col0) lie.
     */
    static String[] sides(int orientation) {
        return switch (orientation) {
            case 1 -> new String[]{"top", "left"};
            case 2 -> new String[]{"top", "right"};
            case 3 -> new String[]{"bottom", "right"};
            case 4 -> new String[]{"bottom", "left"};
            case 5 -> new String[]{"left", "top"};
            case 6 -> new String[]{"right", "top"};
            case 7 -> new String[]{"right", "bottom"};
            case 8 -> new String[]{"left", "bottom"};
            default -> throw new IllegalArgumentException();
        };
    }
    static String opposite(String side) {
        return switch (side) { case "top" -> "bottom"; case "bottom" -> "top"; case "left" -> "right"; default -> "left"; };
    }
    /** Pixel a few steps inside the visual corner touching both sides. */
    static int[] corner(BufferedImage img, String a, String b) {
        boolean right = a.equals("right") || b.equals("right"), bottom = a.equals("bottom") || b.equals("bottom");
        return new int[]{right ? img.getWidth() - 4 : 3, bottom ? img.getHeight() - 4 : 3};
    }

    @ParameterizedTest @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void everyExifOrientationIsAppliedToThePixels(int orientation) throws Exception {
        byte[] input = withExif(write(quadrants(BufferedImage.TYPE_INT_RGB), "jpeg"), orientation, false, false);
        var result = sanitizer.sanitize(input);
        var img = read(result.bytes());
        boolean swapped = orientation >= 5;
        assertEquals(swapped ? H : W, img.getWidth());
        assertEquals(swapped ? W : H, img.getHeight());
        assertEquals(img.getWidth(), result.width());
        assertEquals(img.getHeight(), result.height());

        String[] s = sides(orientation);
        String row0 = s[0], col0 = s[1], lastRow = opposite(row0), lastCol = opposite(col0);
        int[] p;
        p = corner(img, row0, col0); assertColor(RED, img, p[0], p[1]);        // stored top-left
        p = corner(img, row0, lastCol); assertColor(GREEN, img, p[0], p[1]);   // stored top-right
        p = corner(img, lastRow, col0); assertColor(BLUE, img, p[0], p[1]);    // stored bottom-left
        p = corner(img, lastRow, lastCol); assertColor(YELLOW, img, p[0], p[1]); // stored bottom-right
    }

    @Test void littleEndianExifIsReadToo() throws Exception {
        byte[] input = withExif(write(quadrants(BufferedImage.TYPE_INT_RGB), "jpeg"), 6, true, false);
        assertEquals(6, ExifOrientation.read(input));
        var img = read(sanitizer.sanitize(input).bytes());
        assertEquals(H, img.getWidth());
        assertColor(RED, img, img.getWidth() - 4, 3);
    }

    @Test void exifAndGpsAreNotCopiedToTheStoredImage() throws Exception {
        byte[] input = withExif(write(quadrants(BufferedImage.TYPE_INT_RGB), "jpeg"), 6, false, true);
        assertTrue(new String(input, StandardCharsets.ISO_8859_1).contains("Exif"));
        var result = sanitizer.sanitize(input);
        assertEquals("image/jpeg", result.contentType());
        assertFalse(new String(result.bytes(), StandardCharsets.ISO_8859_1).contains("Exif"), "EXIF/GPS block must be gone");
        assertEquals(1, ExifOrientation.read(result.bytes()), "already upright, so no rotation note remains");
    }

    @Test void pngIsReEncodedAsPngAndKeepsTransparency() throws Exception {
        var img = quadrants(BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, 0x00000000);
        var result = sanitizer.sanitize(write(img, "png"));
        assertEquals("image/png", result.contentType());
        assertEquals(W, result.width()); assertEquals(H, result.height());
        var out = read(result.bytes());
        assertEquals(0, out.getRGB(0, 0) >>> 24, "transparent pixel stays transparent");
        assertColor(GREEN, out, W - 4, 3);
    }

    @Test void greyscaleJpegBecomesPlainRgb() throws Exception {
        var grey = new BufferedImage(20, 10, BufferedImage.TYPE_BYTE_GRAY);
        var result = sanitizer.sanitize(write(grey, "jpeg"));
        assertEquals(20, result.width()); assertEquals(10, result.height());
    }

    static void assertRejected(int status, String code, Runnable action) {
        var e = assertThrows(ApiException.class, action::run);
        assertEquals(status, e.status); assertEquals(code, e.code);
    }

    @Test void nonImageFormatsAreRejectedByContentNotByName() throws Exception {
        assertRejected(415, "ATTACHMENT_UNSUPPORTED_TYPE", () -> sanitizer.sanitize("GIF89a....".getBytes(StandardCharsets.ISO_8859_1)));
        assertRejected(415, "ATTACHMENT_UNSUPPORTED_TYPE", () -> sanitizer.sanitize("<script>alert(1)</script>".getBytes(StandardCharsets.UTF_8)));
        var bmp = write(quadrants(BufferedImage.TYPE_INT_RGB), "bmp");
        assertRejected(415, "ATTACHMENT_UNSUPPORTED_TYPE", () -> sanitizer.sanitize(bmp));
    }

    @Test void emptyAndBrokenImagesAreRejected() throws Exception {
        assertRejected(400, "ATTACHMENT_INVALID_IMAGE", () -> sanitizer.sanitize(new byte[0]));
        byte[] jpeg = write(quadrants(BufferedImage.TYPE_INT_RGB), "jpeg");
        byte[] truncated = java.util.Arrays.copyOf(jpeg, 20);
        assertRejected(400, "ATTACHMENT_INVALID_IMAGE", () -> sanitizer.sanitize(truncated));
        byte[] fakeHeader = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0, 1, 2, 3};
        assertRejected(400, "ATTACHMENT_INVALID_IMAGE", () -> sanitizer.sanitize(fakeHeader));
    }

    /** A tiny PNG whose header claims 8000x6000 = 48MP. It must be refused before any pixel is decoded. */
    @Test void pixelCountIsCheckedBeforeDecoding() {
        var ihdr = ByteBuffer.allocate(13).putInt(8000).putInt(6000).put((byte) 8).put((byte) 2).put((byte) 0).put((byte) 0).put((byte) 0).array();
        var crc = new CRC32(); crc.update("IHDR".getBytes(StandardCharsets.ISO_8859_1)); crc.update(ihdr);
        var png = ByteBuffer.allocate(8 + 4 + 4 + 13 + 4)
            .put(new byte[]{(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A})
            .putInt(13).put("IHDR".getBytes(StandardCharsets.ISO_8859_1)).put(ihdr).putInt((int) crc.getValue()).array();
        assertRejected(400, "ATTACHMENT_TOO_MANY_PIXELS", () -> sanitizer.sanitize(png));
    }

    @Test void malformedExifFallsBackToStoredOrientation() throws Exception {
        byte[] jpeg = write(quadrants(BufferedImage.TYPE_INT_RGB), "jpeg");
        byte[] broken = withExif(jpeg, 6, false, false);
        broken[4] = (byte) 0x7F; // APP1 length now points far past the end of the file
        assertEquals(1, ExifOrientation.read(broken));
        assertEquals(1, ExifOrientation.read(withExif(jpeg, 9, false, false)), "out-of-range value is ignored");
        assertEquals(1, ExifOrientation.read(jpeg), "no EXIF at all");
    }
}
