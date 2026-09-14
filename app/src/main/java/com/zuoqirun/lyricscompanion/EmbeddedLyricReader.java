package com.zuoqirun.lyricscompanion;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** Reads common embedded lyric tags without sending the media file anywhere. */
final class EmbeddedLyricReader {
    private static final int MAX_SCAN_BYTES = 8 * 1024 * 1024;

    private EmbeddedLyricReader() { }

    static String read(InputStream input, String displayName) throws IOException {
        if (input == null) return "";
        InputStream stream = input.markSupported() ? input : new BufferedInputStream(input);
        stream.mark(16);
        byte[] header = new byte[12];
        int headerLength = 0;
        while (headerLength < header.length) {
            int count = stream.read(header, headerLength, header.length - headerLength);
            if (count < 0) break;
            if (count > 0) headerLength += count;
        }
        stream.reset();
        String extension = extension(displayName);
        if ("m4a".equals(extension) || "mp4".equals(extension)
                || headerLength >= 8 && matches(header, 4, "ftyp")) return readMp4Stream(stream);
        byte[] bytes = readLimited(stream);
        if ("flac".equals(extension)) return readFlac(bytes);
        if ("mp3".equals(extension)) return readId3(bytes);
        if ("ogg".equals(extension) || "opus".equals(extension)) return readOgg(bytes);
        // Content providers do not always expose a filename. Detect the container as a fallback.
        if (startsWith(bytes, "fLaC")) return readFlac(bytes);
        if (startsWith(bytes, "ID3")) return readId3(bytes);
        if (startsWith(bytes, "OggS")) return readOgg(bytes);
        return readMp4(bytes);
    }

    private static String readMp4Stream(InputStream input) throws IOException {
        try (InputStream stream = input) {
            return scanMp4Boxes(stream, Long.MAX_VALUE, 0);
        }
    }

    private static String scanMp4Boxes(InputStream input, long remaining, int depth)
            throws IOException {
        if (depth > 8) {
            skipFully(input, remaining);
            return "";
        }
        while (remaining >= 8L) {
            Mp4Box box = readMp4Box(input, remaining);
            if (box == null) return "";
            if (box.size > remaining && remaining != Long.MAX_VALUE) return "";
            if ("©lyr".equals(box.type)) {
                String lyric = readMp4LyricAtom(input, box.payloadSize);
                if (!lyric.isEmpty()) return lyric;
            } else if (isMp4Container(box.type)) {
                long content = box.payloadSize;
                if ("meta".equals(box.type)) {
                    if (content < 4L) return "";
                    skipFully(input, 4L);
                    content -= 4L;
                }
                String lyric = scanMp4Boxes(input, content, depth + 1);
                if (!lyric.isEmpty()) return lyric;
            } else {
                skipFully(input, box.payloadSize);
            }
            if (remaining != Long.MAX_VALUE) remaining -= box.size;
        }
        return "";
    }

    private static String readMp4LyricAtom(InputStream input, long remaining) throws IOException {
        while (remaining >= 8L) {
            Mp4Box box = readMp4Box(input, remaining);
            if (box == null || box.size > remaining) return "";
            if ("data".equals(box.type) && box.payloadSize >= 8L
                    && box.payloadSize - 8L <= MAX_SCAN_BYTES) {
                skipFully(input, 8L); // type/locale
                byte[] value = readExactly(input, (int) (box.payloadSize - 8L));
                return new String(value, StandardCharsets.UTF_8).trim();
            }
            skipFully(input, box.payloadSize);
            remaining -= box.size;
        }
        return "";
    }

    private static Mp4Box readMp4Box(InputStream input, long remaining) throws IOException {
        byte[] header = new byte[8];
        if (!readHeader(input, header)) return null;
        long size = unsignedInt(header, 0);
        String type = new String(header, 4, 4, StandardCharsets.ISO_8859_1);
        int headerSize = 8;
        if (size == 1L) {
            byte[] extended = readExactly(input, 8);
            size = unsignedLong(extended, 0);
            headerSize = 16;
        } else if (size == 0L) {
            if (remaining == Long.MAX_VALUE) return null;
            size = remaining;
        }
        if (size < headerSize) return null;
        return new Mp4Box(type, size, size - headerSize);
    }

    private static boolean isMp4Container(String type) {
        return "moov".equals(type) || "udta".equals(type) || "meta".equals(type)
                || "ilst".equals(type);
    }

    private static boolean readHeader(InputStream input, byte[] header) throws IOException {
        int first = input.read();
        if (first < 0) return false;
        header[0] = (byte) first;
        for (int index = 1; index < header.length; index++) {
            int value = input.read();
            if (value < 0) throw new IOException("Truncated MP4 box header");
            header[index] = (byte) value;
        }
        return true;
    }

    private static byte[] readExactly(InputStream input, int length) throws IOException {
        byte[] bytes = new byte[length];
        int offset = 0;
        while (offset < length) {
            int count = input.read(bytes, offset, length - offset);
            if (count < 0) throw new IOException("Truncated MP4 box");
            if (count > 0) offset += count;
        }
        return bytes;
    }

    private static void skipFully(InputStream input, long count) throws IOException {
        byte[] buffer = null;
        while (count > 0L) {
            long skipped = input.skip(count);
            if (skipped > 0L) {
                count -= skipped;
                continue;
            }
            if (buffer == null) buffer = new byte[4096];
            int read = input.read(buffer, 0, (int) Math.min(buffer.length, count));
            if (read < 0) throw new IOException("Truncated MP4 box");
            if (read > 0) count -= read;
        }
    }

    private static final class Mp4Box {
        final String type;
        final long size;
        final long payloadSize;

        Mp4Box(String type, long size, long payloadSize) {
            this.type = type;
            this.size = size;
            this.payloadSize = payloadSize;
        }
    }

    private static byte[] readLimited(InputStream input) throws IOException {
        try (InputStream stream = input; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = stream.read(buffer)) >= 0) {
                if (count == 0) continue;
                if (output.size() + count > MAX_SCAN_BYTES) break;
                output.write(buffer, 0, count);
            }
            return output.toByteArray();
        }
    }

    private static String readFlac(byte[] bytes) {
        if (!startsWith(bytes, "fLaC")) return "";
        int position = 4;
        while (position + 4 <= bytes.length) {
            int header = bytes[position++] & 0xff;
            boolean last = (header & 0x80) != 0;
            int type = header & 0x7f;
            int length = unsigned24(bytes, position);
            position += 3;
            if (length < 0 || position + length > bytes.length) return "";
            if (type == 4) {
                String lyric = readVorbisComments(bytes, position, length);
                if (!lyric.isEmpty()) return lyric;
            }
            position += length;
            if (last) break;
        }
        return "";
    }

    private static String readOgg(byte[] bytes) {
        byte[] packet = new byte[Math.min(bytes.length, MAX_SCAN_BYTES)];
        int packetLength = 0;
        int position = 0;
        while (position + 27 <= bytes.length) {
            if (!matches(bytes, position, "OggS")) return "";
            int segments = bytes[position + 26] & 0xff;
            int table = position + 27;
            if (table + segments > bytes.length) return "";
            int body = table + segments;
            for (int index = 0; index < segments; index++) {
                int length = bytes[table + index] & 0xff;
                if (body + length > bytes.length || packetLength + length > packet.length) return "";
                System.arraycopy(bytes, body, packet, packetLength, length);
                packetLength += length;
                body += length;
                if (length < 255) {
                    String lyric = readOggPacket(packet, packetLength);
                    if (!lyric.isEmpty()) return lyric;
                    packetLength = 0;
                }
            }
            position = body;
        }
        return "";
    }

    private static String readOggPacket(byte[] packet, int length) {
        if (length > 7 && matches(packet, 0, "\u0003vorbis")) {
            return readVorbisComments(packet, 7, length - 7);
        }
        if (length > 8 && matches(packet, 0, "OpusTags")) {
            return readVorbisComments(packet, 8, length - 8);
        }
        return "";
    }

    private static String readVorbisComments(byte[] bytes, int offset, int length) {
        int end = offset + length;
        if (offset + 4 > end) return "";
        int vendorLength = littleEndianInt(bytes, offset);
        int position = offset + 4 + vendorLength;
        if (vendorLength < 0 || position + 4 > end) return "";
        int count = littleEndianInt(bytes, position);
        position += 4;
        for (int index = 0; index < count && position + 4 <= end; index++) {
            int itemLength = littleEndianInt(bytes, position);
            position += 4;
            if (itemLength < 0 || position + itemLength > end) return "";
            String item = new String(bytes, position, itemLength, StandardCharsets.UTF_8);
            position += itemLength;
            int equals = item.indexOf('=');
            if (equals <= 0) continue;
            String key = item.substring(0, equals).trim().toUpperCase(Locale.ROOT);
            if ("LYRICS".equals(key) || "UNSYNCEDLYRICS".equals(key)) {
                return item.substring(equals + 1).trim();
            }
        }
        return "";
    }

    private static String readId3(byte[] bytes) {
        if (bytes.length < 10 || !startsWith(bytes, "ID3")) return "";
        int version = bytes[3] & 0xff;
        if (version < 3 || version > 4) return "";
        int tagEnd = Math.min(bytes.length, 10 + syncSafeInt(bytes, 6));
        int position = 10;
        while (position + 10 <= tagEnd) {
            String id = new String(bytes, position, 4, StandardCharsets.ISO_8859_1);
            int size = version == 4 ? syncSafeInt(bytes, position + 4)
                    : bigEndianInt(bytes, position + 4);
            position += 10;
            if (size <= 0 || position + size > tagEnd) break;
            if ("USLT".equals(id)) {
                String lyric = readUslt(bytes, position, size);
                if (!lyric.isEmpty()) return lyric;
            } else if ("SYLT".equals(id)) {
                String lyric = readSylt(bytes, position, size);
                if (!lyric.isEmpty()) return lyric;
            }
            position += size;
        }
        return "";
    }

    private static String readUslt(byte[] bytes, int offset, int size) {
        if (size < 4) return "";
        int encoding = bytes[offset] & 0xff;
        int text = skipEncodedString(bytes, offset + 4, offset + size, encoding);
        return decode(bytes, text, offset + size - text, encoding).trim();
    }

    private static String readSylt(byte[] bytes, int offset, int size) {
        if (size < 6) return "";
        int encoding = bytes[offset] & 0xff;
        int timestampFormat = bytes[offset + 4] & 0xff;
        int position = skipEncodedString(bytes, offset + 6, offset + size, encoding);
        StringBuilder lrc = new StringBuilder();
        while (position < offset + size) {
            int end = encodedStringEnd(bytes, position, offset + size, encoding);
            String text = decode(bytes, position, end - position, encoding).trim();
            position = end + terminatorLength(encoding);
            if (position + 4 > offset + size) break;
            long value = ((long) (bytes[position] & 0xff) << 24)
                    | ((long) (bytes[position + 1] & 0xff) << 16)
                    | ((long) (bytes[position + 2] & 0xff) << 8)
                    | (bytes[position + 3] & 0xffL);
            position += 4;
            if (!text.isEmpty() && timestampFormat == 1) {
                long seconds = value / 1000L;
                long centiseconds = (value % 1000L) / 10L;
                lrc.append(String.format(Locale.ROOT, "[%02d:%02d.%02d]", seconds / 60L,
                        seconds % 60L, centiseconds)).append(text).append('\n');
            }
        }
        return lrc.toString().trim();
    }

    private static String readMp4(byte[] bytes) {
        for (int position = 0; position + 8 <= bytes.length;) {
            long size = unsignedInt(bytes, position);
            boolean extended = size == 1;
            if (extended && position + 16 <= bytes.length) size = unsignedLong(bytes, position + 8);
            int header = extended ? 16 : 8;
            if (size == 0) size = bytes.length - position;
            if (size < header || size > bytes.length - position) return "";
            if (matches(bytes, position + 4, "©lyr")) {
                String lyric = readMp4LyricAtom(bytes, position + header, (int) size - header);
                if (!lyric.isEmpty()) return lyric;
            }
            position += (int) size;
        }
        // The lyric atom is normally nested under moov/udta/meta/ilst. Scanning is bounded and
        // makes the reader tolerant of a metadata atom inside less common box layouts.
        for (int position = 4; position + 4 <= bytes.length; position++) {
            if (matches(bytes, position, "©lyr")) {
                int atomStart = position - 4;
                if (atomStart < 0) continue;
                long size = unsignedInt(bytes, atomStart);
                if (size >= 8 && size <= bytes.length - atomStart) {
                    String lyric = readMp4LyricAtom(bytes, atomStart + 8, (int) size - 8);
                    if (!lyric.isEmpty()) return lyric;
                }
            }
        }
        return "";
    }

    private static String readMp4LyricAtom(byte[] bytes, int offset, int length) {
        int end = offset + length;
        for (int position = offset; position + 16 <= end;) {
            long size = unsignedInt(bytes, position);
            if (size < 16 || size > end - position) return "";
            if (matches(bytes, position + 4, "data")) {
                int text = position + 16;
                return new String(bytes, text, (int) size - 16, StandardCharsets.UTF_8).trim();
            }
            position += (int) size;
        }
        return "";
    }

    private static int skipEncodedString(byte[] bytes, int offset, int end, int encoding) {
        int stringEnd = encodedStringEnd(bytes, offset, end, encoding);
        return Math.min(end, stringEnd + terminatorLength(encoding));
    }

    private static int encodedStringEnd(byte[] bytes, int offset, int end, int encoding) {
        int step = terminatorLength(encoding);
        for (int position = offset; position + step <= end; position += step) {
            if (step == 1 ? bytes[position] == 0 : bytes[position] == 0 && bytes[position + 1] == 0) {
                return position;
            }
        }
        return end;
    }

    private static String decode(byte[] bytes, int offset, int length, int encoding) {
        if (length <= 0) return "";
        Charset charset;
        switch (encoding) {
            case 1: charset = StandardCharsets.UTF_16; break;
            case 2: charset = StandardCharsets.UTF_16BE; break;
            case 3: charset = StandardCharsets.UTF_8; break;
            default: charset = StandardCharsets.ISO_8859_1; break;
        }
        return new String(bytes, offset, length, charset);
    }

    private static int terminatorLength(int encoding) { return encoding == 1 || encoding == 2 ? 2 : 1; }
    private static String extension(String name) {
        if (name == null) return "";
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
    private static boolean startsWith(byte[] bytes, String value) { return matches(bytes, 0, value); }
    private static boolean matches(byte[] bytes, int offset, String value) {
        if (offset < 0 || offset + value.length() > bytes.length) return false;
        for (int index = 0; index < value.length(); index++) if (bytes[offset + index] != (byte) value.charAt(index)) return false;
        return true;
    }
    private static int unsigned24(byte[] bytes, int offset) {
        return offset + 3 <= bytes.length ? ((bytes[offset] & 0xff) << 16) | ((bytes[offset + 1] & 0xff) << 8) | (bytes[offset + 2] & 0xff) : -1;
    }
    private static int littleEndianInt(byte[] bytes, int offset) {
        return offset + 4 <= bytes.length ? (bytes[offset] & 0xff) | ((bytes[offset + 1] & 0xff) << 8) | ((bytes[offset + 2] & 0xff) << 16) | ((bytes[offset + 3] & 0xff) << 24) : -1;
    }
    private static int bigEndianInt(byte[] bytes, int offset) {
        return offset + 4 <= bytes.length ? ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16) | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff) : -1;
    }
    private static int syncSafeInt(byte[] bytes, int offset) {
        return offset + 4 <= bytes.length ? ((bytes[offset] & 0x7f) << 21) | ((bytes[offset + 1] & 0x7f) << 14) | ((bytes[offset + 2] & 0x7f) << 7) | (bytes[offset + 3] & 0x7f) : -1;
    }
    private static long unsignedInt(byte[] bytes, int offset) { return bigEndianInt(bytes, offset) & 0xffffffffL; }
    private static long unsignedLong(byte[] bytes, int offset) {
        if (offset + 8 > bytes.length) return -1L;
        long high = unsignedInt(bytes, offset);
        long low = unsignedInt(bytes, offset + 4);
        return high > 0x7fffffffL ? Long.MAX_VALUE : (high << 32) | low;
    }
}
