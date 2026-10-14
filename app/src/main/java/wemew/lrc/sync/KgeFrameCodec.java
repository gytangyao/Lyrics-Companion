package wemew.lrc.sync;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * KGe K62 frame codec, ported byte-for-byte from nbox's BlueZ implementation
 * (nbox@52f74ea, KgeFrameCodec.cs / KgeGattServer.cs — "K62 真机 logcat 实测 2026-06-16").
 *
 * <p>Frame layout (no CRC): {@code [seq][cmdId][cmdType][frameInfo][payloadLen][payload]} —
 * seq is ignored on receive; frameInfo high nibble = totalFrames-1, low nibble = index; int32
 * values inside TLV payloads are little-endian. Lyric pushes arrive as commandType 3:
 * 0xB5 = current line (UTF-8), 0xB4 = line start ms (ASCII decimal).</p>
 */
final class KgeFrameCodec {
    static final int CMD_AUTH = 0x80;
    static final int CMD_WRITE_TOKEN = 0xC0;
    static final int CMD_TOKEN_UPLOAD = 0xF1;
    static final int CMD_STATE_SYNC = 0xF2;
    static final int CMD_LYRIC_LINE_TITLE = 0xB5;
    static final int CMD_LYRIC_LINE_STATE = 0xB4;

    static final int TYPE_REQUEST = 0x01;
    static final int TYPE_RESPONSE = 0x02;
    static final int TYPE_NOTIFY = 0x03;

    private static final int HEADER_LEN = 5;
    /** MTU assumption nbox shipped with; Android re-segments notifies to the real ATT MTU. */
    private static final int DEFAULT_MTU = 512;

    /** DeviceInfo magic bytes 01..0D distinguish the second 0x80 request from the first. */
    static final byte[] DEVICE_INFO_MAGIC =
            {0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D};

    /** Response to the first 0x80: TLV tag=protocolVersion, int32 LE = 1. */
    static final byte[] AUTH_INFO_PAYLOAD = {0x01, 0x04, 0x01, 0x00, 0x00, 0x00};

    /** Response to 0x80+magic: the exact 41-byte DeviceInfo TLV a real K62 returns. */
    static final byte[] DEVICE_INFO_PAYLOAD = {
            0x01, 0x04, 0x01, 0x00, 0x00, 0x00,             // protocolVersion = 1
            0x02, 0x04, 0x01, 0x00, 0x00, 0x10,             // featureFlag = 0x10000001
            0x03, 0x04, 0x01, 0x00, 0x00, 0x00,             // unknown = 1
            0x04, 0x03, 'K', '6', '2',                      // deviceName = "K62"
            0x05, 0x01, 0x05,                               // = 5
            0x06, 0x01, 0x01,                               // = 1
            0x07, 0x01, 0x00,                               // = 0
            0x08, 0x01, 0x00,                               // avrcpSupport = 0
            0x09, 0x02, 0x00, 0x00,                         // bleSupport = 0 (len 2)
            0x0D, 0x00                                      // empty tag
    };

    /** Success ACK payload shared by 0xF1/0xF2 (real K62 returns 1-byte 0x01, not empty). */
    static final byte[] ACK_SUCCESS = {0x01};

    /**
     * The fixed 92-byte cloned-K62 device token served on READ: five length-prefixed fields
     * (serial / productId / rand / 32B sign / 16B token). The app ships these to Tencent's
     * AuthDevice; whether the live service still honours this clone is exactly what the
     * on-device test answers.
     */
    static final byte[] DEVICE_TOKEN = buildDeviceToken();

    private static byte[] buildDeviceToken() {
        return concat(
                lengthPrefixed("7073510248273018880".getBytes(StandardCharsets.US_ASCII)),
                lengthPrefixed("2000000791".getBytes(StandardCharsets.US_ASCII)),
                lengthPrefixed("1232056041".getBytes(StandardCharsets.US_ASCII)),
                lengthPrefixed(hex(
                        "B793FD2C665586F6776FF225B04D3018A293E74CD749C72854017719D5D78156")),
                lengthPrefixed("AZLQSVZQQSCYTDQN".getBytes(StandardCharsets.US_ASCII)));
    }

    private static byte[] lengthPrefixed(byte[] field) {
        byte[] out = new byte[field.length + 1];
        out[0] = (byte) field.length;
        System.arraycopy(field, 0, out, 1, field.length);
        return out;
    }

    private static byte[] hex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }

    static byte[] concat(byte[]... parts) {
        int total = 0;
        for (byte[] part : parts) total += part.length;
        byte[] out = new byte[total];
        int at = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, out, at, part.length);
            at += part.length;
        }
        return out;
    }

    /** A fully-assembled inbound message (all fragments joined). */
    static final class Message {
        final int commandId;
        final int commandType;
        final byte[] payload;

        Message(int commandId, int commandType, byte[] payload) {
            this.commandId = commandId;
            this.commandType = commandType;
            this.payload = payload;
        }
    }

    /** One parsed wire frame (a single GATT write). */
    static final class Frame {
        final int commandId;
        final int commandType;
        final int total;
        final int index;
        final byte[] payload;

        Frame(int commandId, int commandType, int total, int index, byte[] payload) {
            this.commandId = commandId;
            this.commandType = commandType;
            this.total = total;
            this.index = index;
            this.payload = payload;
        }
    }

    /** Parses a single write; null when shorter than the header claims. */
    static Frame parseFrame(byte[] data) {
        if (data == null || data.length < HEADER_LEN) return null;
        int payloadLen = data[4] & 0xFF;
        if (data.length < HEADER_LEN + payloadLen) return null;
        int frameInfo = data[3] & 0xFF;
        byte[] payload = new byte[payloadLen];
        System.arraycopy(data, HEADER_LEN, payload, 0, payloadLen);
        return new Frame(data[1] & 0xFF, data[2] & 0xFF,
                (frameInfo >> 4) + 1, frameInfo & 0x0F, payload);
    }

    /** Multi-frame assembler keyed by (cmdId, cmdType, totalFrames); per-connection. */
    static final class Assembler {
        private static final class Key {
            final int commandId;
            final int commandType;
            final int total;

            Key(int commandId, int commandType, int total) {
                this.commandId = commandId;
                this.commandType = commandType;
                this.total = total;
            }

            @Override public boolean equals(Object other) {
                if (!(other instanceof Key)) return false;
                Key key = (Key) other;
                return commandId == key.commandId && commandType == key.commandType
                        && total == key.total;
            }

            @Override public int hashCode() {
                return commandId * 31 * 31 + commandType * 31 + total;
            }
        }

        private final Map<Key, byte[][]> pending = new HashMap<>();

        /** Feeds one frame; returns the completed message or null. */
        Message feed(Frame frame) {
            if (frame.total <= 1) {
                return new Message(frame.commandId, frame.commandType, frame.payload);
            }
            Key key = new Key(frame.commandId, frame.commandType, frame.total);
            byte[][] slots = pending.get(key);
            if (slots == null) {
                slots = new byte[frame.total][];
                pending.put(key, slots);
            }
            if (frame.index < frame.total) slots[frame.index] = frame.payload;
            for (byte[] slot : slots) {
                if (slot == null) return null;
            }
            pending.remove(key);
            int total = 0;
            for (byte[] slot : slots) total += slot.length;
            byte[] joined = new byte[total];
            int at = 0;
            for (byte[] slot : slots) {
                System.arraycopy(slot, 0, joined, at, slot.length);
                at += slot.length;
            }
            return new Message(frame.commandId, frame.commandType, joined);
        }

        void reset() {
            pending.clear();
        }
    }

    /** Encodes a message into one or more frames within the MTU budget. */
    static List<byte[]> encode(int commandId, int commandType, byte[] payload, int seqStart) {
        byte[] body = payload == null ? new byte[0] : payload;
        int chunk = Math.max(1, Math.min(255, DEFAULT_MTU - HEADER_LEN));
        int total = Math.max(1, (body.length + chunk - 1) / chunk);
        List<byte[]> frames = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int from = i * chunk;
            int length = Math.min(chunk, body.length - from);
            byte[] frame = new byte[HEADER_LEN + Math.max(0, length)];
            frame[0] = (byte) ((seqStart + i) & 0xFF);
            frame[1] = (byte) commandId;
            frame[2] = (byte) commandType;
            frame[3] = (byte) (((total - 1) << 4) | i);
            frame[4] = (byte) Math.max(0, length);
            if (length > 0) {
                System.arraycopy(body, from, frame, HEADER_LEN, length);
            }
            frames.add(frame);
        }
        return frames;
    }

    /** Decodes a lyric line title push (0xB5, UTF-8 raw). */
    static String decodeLyricText(byte[] payload) {
        return payload == null ? "" : new String(payload, StandardCharsets.UTF_8).trim();
    }

    /** Decodes a line start push (0xB4, ASCII decimal ms); -1 when unparsable. */
    static long decodeLyricStartMs(byte[] payload) {
        if (payload == null || payload.length == 0) return -1L;
        try {
            return Long.parseLong(new String(payload, StandardCharsets.US_ASCII).trim());
        } catch (NumberFormatException error) {
            return -1L;
        }
    }

    /** True when the 0x80 request payload is the 13-byte magic, not the version probe. */
    static boolean isDeviceInfoRequest(byte[] payload) {
        if (payload == null || payload.length != DEVICE_INFO_MAGIC.length) return false;
        for (int i = 0; i < payload.length; i++) {
            if (payload[i] != DEVICE_INFO_MAGIC[i]) return false;
        }
        return true;
    }

    private KgeFrameCodec() {}
}
