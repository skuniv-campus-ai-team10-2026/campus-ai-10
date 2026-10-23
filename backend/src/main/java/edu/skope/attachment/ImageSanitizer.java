package edu.skope.attachment;

import edu.skope.service.ApiException;
import org.springframework.stereotype.Component;

import javax.imageio.*;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.concurrent.Semaphore;

/**
 * Turns an uploaded file into a clean JPEG/PNG: the real format is read from the bytes, the pixel count is
 * checked before decoding, phone rotation (EXIF Orientation) is applied to the pixels, and the image is
 * re-encoded so no EXIF/GPS or other metadata from the original survives.
 */
@Component
public class ImageSanitizer {
    public static final long MAX_PIXELS = 40_000_000L;
    private static final float JPEG_QUALITY = 0.9f;
    /** A 40MP image needs ~160MB while decoded and twice that while rotated, so only two run at once. */
    private final Semaphore slots = new Semaphore(2, true);

    public record Sanitized(byte[] bytes, String contentType, int width, int height) {}

    enum Format {
        JPEG("jpeg", "image/jpeg"), PNG("png", "image/png");
        final String imageIoName, contentType;
        Format(String imageIoName, String contentType) { this.imageIoName = imageIoName; this.contentType = contentType; }
    }

    public Sanitized sanitize(byte[] input) {
        Format format = detect(input);
        slots.acquireUninterruptibly();
        try {
            BufferedImage decoded = decode(input, format);
            int orientation = format == Format.JPEG ? ExifOrientation.read(input) : 1;
            BufferedImage upright = applyOrientation(decoded, orientation, format == Format.PNG);
            return new Sanitized(encode(upright, format), format.contentType, upright.getWidth(), upright.getHeight());
        } finally {
            slots.release();
        }
    }

    static Format detect(byte[] b) {
        if (b == null || b.length == 0) throw invalid();
        if (b.length >= 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return Format.JPEG;
        byte[] png = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
        if (b.length >= png.length) {
            boolean match = true;
            for (int i = 0; i < png.length; i++) if (b[i] != png[i]) { match = false; break; }
            if (match) return Format.PNG;
        }
        throw new ApiException(415, "ATTACHMENT_UNSUPPORTED_TYPE", "JPEG 또는 PNG 사진만 올릴 수 있습니다.");
    }

    private static BufferedImage decode(byte[] input, Format format) {
        try (var stream = new MemoryCacheImageInputStream(new ByteArrayInputStream(input))) {
            var readers = ImageIO.getImageReadersByFormatName(format.imageIoName);
            if (!readers.hasNext()) throw invalid();
            ImageReader reader = readers.next();
            try {
                reader.setInput(stream, true, true);
                long width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1) throw invalid();
                if (width * height > MAX_PIXELS)
                    throw new ApiException(400, "ATTACHMENT_TOO_MANY_PIXELS", "사진 해상도가 너무 큽니다. 4,000만 화소 이하로 올려 주세요.");
                return reader.read(0);
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException api) throw api;
            throw invalid();
        }
    }

    /** EXIF orientation 1..8 → the upright image. For 5..8 the result is height x width. */
    static BufferedImage applyOrientation(BufferedImage src, int orientation, boolean keepAlpha) {
        int w = src.getWidth(), h = src.getHeight();
        boolean swap = orientation >= 5 && orientation <= 8;
        int dw = swap ? h : w, dh = swap ? w : h;
        var dst = new BufferedImage(dw, dh, keepAlpha ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB);
        if (orientation < 2 || orientation > 8) {
            // Upright already: one draw also converts grey/palette images to plain RGB(A).
            var g = dst.createGraphics();
            try { g.drawImage(src, 0, 0, null); } finally { g.dispose(); }
            return dst;
        }
        for (int y = 0; y < dh; y++) {
            for (int x = 0; x < dw; x++) {
                int sx, sy;
                switch (orientation) {
                    case 2 -> { sx = w - 1 - x; sy = y; }          // mirrored
                    case 3 -> { sx = w - 1 - x; sy = h - 1 - y; }  // rotated 180
                    case 4 -> { sx = x; sy = h - 1 - y; }          // flipped vertically
                    case 5 -> { sx = y; sy = x; }                  // transposed
                    case 6 -> { sx = y; sy = h - 1 - x; }          // needs 90 clockwise
                    case 7 -> { sx = w - 1 - y; sy = h - 1 - x; }  // transverse
                    case 8 -> { sx = w - 1 - y; sy = x; }          // needs 90 counter-clockwise
                    default -> { sx = x; sy = y; }                 // 1 or unknown: as stored
                }
                dst.setRGB(x, y, src.getRGB(sx, sy));
            }
        }
        return dst;
    }

    private static byte[] encode(BufferedImage image, Format format) {
        var out = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName(format.imageIoName).next();
        try (var stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (format == Format.JPEG) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            // No metadata is passed, so only the encoder's minimal header is written.
            writer.write(null, new IIOImage(image, null, null), param);
        } catch (IOException e) {
            throw new ApiException(500, "ATTACHMENT_ENCODING_FAILED", "사진을 처리하지 못했습니다. 다시 시도하세요.");
        } finally {
            writer.dispose();
        }
        return out.toByteArray();
    }

    private static ApiException invalid() {
        return new ApiException(400, "ATTACHMENT_INVALID_IMAGE", "사진 파일을 읽을 수 없습니다. 다른 사진을 올려 주세요.");
    }
}
