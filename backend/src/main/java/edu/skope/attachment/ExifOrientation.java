package edu.skope.attachment;

import java.nio.charset.StandardCharsets;

/**
 * Reads only the EXIF Orientation tag (0x0112) from a JPEG. Anything malformed or missing means 1 (as stored),
 * because a wrong guess is worse than showing the photo the way its pixels are stored.
 *
 * JPEG = segments [FF xx][length][data]. EXIF lives in APP1 (FF E1) as "Exif\0\0" + a TIFF block:
 * byte order ("II" little / "MM" big endian), 42, offset of IFD0, then IFD0 entries of 12 bytes each.
 */
final class ExifOrientation {
    private static final int TAG_ORIENTATION = 0x0112, TYPE_SHORT = 3;

    private ExifOrientation() {}

    static int read(byte[] jpeg) {
        try {
            int pos = 2; // after SOI (FF D8)
            while (pos + 4 <= jpeg.length) {
                if (u8(jpeg, pos) != 0xFF) return 1;
                int marker = u8(jpeg, pos + 1);
                if (marker == 0xFF) { pos++; continue; }          // fill byte
                if (marker == 0xDA || marker == 0xD9) return 1;   // image data or end: no EXIF before it
                int length = (u8(jpeg, pos + 2) << 8) | u8(jpeg, pos + 3);
                if (length < 2 || pos + 2 + length > jpeg.length) return 1;
                if (marker == 0xE1 && length >= 8 && "Exif\0\0".equals(new String(jpeg, pos + 4, 6, StandardCharsets.ISO_8859_1)))
                    return fromTiff(jpeg, pos + 10, pos + 2 + length);
                pos += 2 + length;
            }
        } catch (RuntimeException ignored) {
            // Malformed metadata never blocks an otherwise valid photo.
        }
        return 1;
    }

    private static int fromTiff(byte[] b, int start, int end) {
        if (start + 8 > end) return 1;
        boolean little;
        if (b[start] == 'I' && b[start + 1] == 'I') little = true;
        else if (b[start] == 'M' && b[start + 1] == 'M') little = false;
        else return 1;
        if (u16(b, start + 2, little) != 42) return 1;
        long ifd = start + u32(b, start + 4, little);
        if (ifd + 2 > end) return 1;
        int count = u16(b, (int) ifd, little);
        for (int i = 0; i < count; i++) {
            int entry = (int) ifd + 2 + i * 12;
            if (entry + 12 > end) return 1;
            if (u16(b, entry, little) == TAG_ORIENTATION) {
                if (u16(b, entry + 2, little) != TYPE_SHORT) return 1;
                int value = u16(b, entry + 8, little);
                return value >= 1 && value <= 8 ? value : 1;
            }
        }
        return 1;
    }

    private static int u8(byte[] b, int i) { return b[i] & 0xFF; }
    private static int u16(byte[] b, int i, boolean little) {
        return little ? u8(b, i) | (u8(b, i + 1) << 8) : (u8(b, i) << 8) | u8(b, i + 1);
    }
    private static long u32(byte[] b, int i, boolean little) {
        return little ? (u16(b, i, true) | ((long) u16(b, i + 2, true) << 16))
                      : (((long) u16(b, i, false) << 16) | u16(b, i + 2, false));
    }
}
