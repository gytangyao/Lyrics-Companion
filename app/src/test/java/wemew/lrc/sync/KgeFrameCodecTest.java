package wemew.lrc.sync;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

/** Byte-level cases from the K62 on-device capture documented in nbox@52f74ea. */
public class KgeFrameCodecTest {

    @org.junit.Test public void deviceTokenIsTheExact92ByteClone() {
        assertEquals(92, KgeFrameCodec.DEVICE_TOKEN.length);
        // Serial field: len 0x13 then "7073510248273018880".
        assertEquals(0x13, KgeFrameCodec.DEVICE_TOKEN[0] & 0xFF);
        assertEquals("7073510248273018880",
                new String(KgeFrameCodec.DEVICE_TOKEN, 1, 19,
                        java.nio.charset.StandardCharsets.US_ASCII));
        // 32-byte sign field at offset 1+19+1+10+1+10+1 = 43.
        int signAt = 1 + 19 + 1 + 10 + 1 + 10 + 1;
        assertEquals(0x20, KgeFrameCodec.DEVICE_TOKEN[signAt - 1] & 0xFF);
        assertEquals(32, 92 - signAt - 1 - 16);
    }

    @org.junit.Test public void deviceInfoTlvMatchesTheK62Capture() {
        assertEquals(41, KgeFrameCodec.DEVICE_INFO_PAYLOAD.length);
        // protocolVersion tag=01 len=04 value 1 LE.
        assertEquals(0x01, KgeFrameCodec.DEVICE_INFO_PAYLOAD[0] & 0xFF);
        assertEquals(0x04, KgeFrameCodec.DEVICE_INFO_PAYLOAD[1] & 0xFF);
        assertEquals(1, KgeFrameCodec.DEVICE_INFO_PAYLOAD[2] & 0xFF);
        // deviceName "K62" at tag 04.
        String text = new String(KgeFrameCodec.DEVICE_INFO_PAYLOAD,
                java.nio.charset.StandardCharsets.US_ASCII);
        assertTrue(text.contains("K62"));
    }

    @org.junit.Test public void parsesAuthVersionRequest() {
        // 00 80 01 00 01 01 — the exact first request from the capture.
        KgeFrameCodec.Frame frame = KgeFrameCodec.parseFrame(
                new byte[]{0x00, (byte) 0x80, 0x01, 0x00, 0x01, 0x01});
        assertNotNull(frame);
        assertEquals(0x80, frame.commandId);
        assertEquals(KgeFrameCodec.TYPE_REQUEST, frame.commandType);
        assertEquals(1, frame.total);
        assertEquals(1, frame.payload.length);
        assertTrue(!KgeFrameCodec.isDeviceInfoRequest(frame.payload));
    }

    @org.junit.Test public void distinguishesDeviceInfoMagicRequest() {
        byte[] payload = KgeFrameCodec.DEVICE_INFO_MAGIC;
        assertEquals(13, payload.length);
        assertTrue(KgeFrameCodec.isDeviceInfoRequest(payload));
        assertTrue(!KgeFrameCodec.isDeviceInfoRequest(new byte[]{0x01}));
    }

    @org.junit.Test public void lyricFramesDecode() {
        assertEquals("夜空中最亮的星",
                KgeFrameCodec.decodeLyricText(
                        "夜空中最亮的星".getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals(62400L, KgeFrameCodec.decodeLyricStartMs(
                "62400".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
        assertEquals(-1L, KgeFrameCodec.decodeLyricStartMs(new byte[0]));
    }

    @org.junit.Test public void multiFrameAssemblyJoinsInOrder() {
        KgeFrameCodec.Assembler assembler = new KgeFrameCodec.Assembler();
        byte[] part1 = {0x00, (byte) 0xB5, 0x03, 0x10, 0x03, 0x01, 0x02, 0x03};
        byte[] part2 = {0x01, (byte) 0xB5, 0x03, 0x11, 0x02, 0x04, 0x05};
        assertNull(assembler.feed(KgeFrameCodec.parseFrame(part1)));
        KgeFrameCodec.Message message = assembler.feed(KgeFrameCodec.parseFrame(part2));
        assertNotNull(message);
        assertEquals(0xB5, message.commandId);
        assertEquals(KgeFrameCodec.TYPE_NOTIFY, message.commandType);
        assertEquals(5, message.payload.length);
        assertEquals(1, message.payload[0]);
        assertEquals(5, message.payload[4]);
    }

    @org.junit.Test public void encodeMatchesHeaderLayout() {
        List<byte[]> frames = KgeFrameCodec.encode(0xF1, KgeFrameCodec.TYPE_RESPONSE,
                KgeFrameCodec.ACK_SUCCESS, 7);
        assertEquals(1, frames.size());
        byte[] frame = frames.get(0);
        // 00 F1 02 00 01 01 — the exact ACK from the capture (seq differs, RX ignores it).
        assertEquals(0xF1, frame[1] & 0xFF);
        assertEquals(0x02, frame[2] & 0xFF);
        assertEquals(0x00, frame[3] & 0xFF);
        assertEquals(0x01, frame[4] & 0xFF);
        assertEquals(0x01, frame[5] & 0xFF);
    }

    @org.junit.Test public void shortWritesAreDropped() {
        assertNull(KgeFrameCodec.parseFrame(null));
        assertNull(KgeFrameCodec.parseFrame(new byte[]{0x00, (byte) 0x80}));
        assertNull(KgeFrameCodec.parseFrame(
                new byte[]{0x00, (byte) 0x80, 0x01, 0x00, 0x05, 0x01}));
    }
}
