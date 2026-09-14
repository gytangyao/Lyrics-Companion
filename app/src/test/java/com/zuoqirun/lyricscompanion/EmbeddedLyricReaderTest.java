package com.zuoqirun.lyricscompanion;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;

public class EmbeddedLyricReaderTest {
    @Test public void readsFlacVorbisLyrics() throws Exception {
        byte[] comments = vorbisComments("LYRICS=[00:01.20]FLAC 歌词");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("fLaC".getBytes(StandardCharsets.US_ASCII));
        out.write(0x84); // final metadata block, VORBIS_COMMENT
        write24(out, comments.length);
        out.write(comments);
        assertEquals("[00:01.20]FLAC 歌词", EmbeddedLyricReader.read(
                new ByteArrayInputStream(out.toByteArray()), "song.flac"));
    }

    @Test public void readsId3UnsynchronisedLyrics() throws Exception {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        payload.write(3); // UTF-8
        payload.write("eng".getBytes(StandardCharsets.US_ASCII));
        payload.write(0);
        payload.write("[00:02.00]MP3 歌词".getBytes(StandardCharsets.UTF_8));
        ByteArrayOutputStream tag = new ByteArrayOutputStream();
        tag.write("USLT".getBytes(StandardCharsets.US_ASCII));
        writeSyncSafe(tag, payload.size());
        tag.write(new byte[]{0, 0});
        tag.write(payload.toByteArray());
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        file.write("ID3".getBytes(StandardCharsets.US_ASCII));
        file.write(new byte[]{4, 0, 0});
        writeSyncSafe(file, tag.size());
        file.write(tag.toByteArray());
        assertEquals("[00:02.00]MP3 歌词", EmbeddedLyricReader.read(
                new ByteArrayInputStream(file.toByteArray()), "song.mp3"));
    }

    @Test public void readsM4aLyricAtom() throws Exception {
        byte[] value = "[00:03.00]M4A 歌词".getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        write32(data, 16 + value.length);
        data.write("data".getBytes(StandardCharsets.US_ASCII));
        data.write(new byte[8]);
        data.write(value);
        ByteArrayOutputStream atom = new ByteArrayOutputStream();
        write32(atom, 8 + data.size());
        atom.write(new byte[]{(byte) 0xa9, 'l', 'y', 'r'});
        atom.write(data.toByteArray());
        assertEquals("[00:03.00]M4A 歌词", EmbeddedLyricReader.read(
                new ByteArrayInputStream(atom.toByteArray()), "song.m4a"));
    }

    @Test public void readsOpusVorbisComments() throws Exception {
        byte[] packet = concat("OpusTags".getBytes(StandardCharsets.US_ASCII),
                vorbisComments("UNSYNCEDLYRICS=[00:04.00]OGG 歌词"));
        ByteArrayOutputStream page = new ByteArrayOutputStream();
        page.write("OggS".getBytes(StandardCharsets.US_ASCII));
        page.write(new byte[22]);
        page.write(1);
        page.write(packet.length);
        page.write(packet);
        assertEquals("[00:04.00]OGG 歌词", EmbeddedLyricReader.read(
                new ByteArrayInputStream(page.toByteArray()), "song.opus"));
    }

    private static byte[] vorbisComments(String comment) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] vendor = "test".getBytes(StandardCharsets.UTF_8);
        writeLittleEndian(out, vendor.length); out.write(vendor);
        writeLittleEndian(out, 1);
        byte[] item = comment.getBytes(StandardCharsets.UTF_8);
        writeLittleEndian(out, item.length); out.write(item);
        return out.toByteArray();
    }

    private static void write24(ByteArrayOutputStream out, int value) {
        out.write(value >>> 16); out.write(value >>> 8); out.write(value);
    }
    private static void write32(ByteArrayOutputStream out, int value) {
        out.write(value >>> 24); out.write(value >>> 16); out.write(value >>> 8); out.write(value);
    }
    private static void writeLittleEndian(ByteArrayOutputStream out, int value) {
        out.write(value); out.write(value >>> 8); out.write(value >>> 16); out.write(value >>> 24);
    }
    private static void writeSyncSafe(ByteArrayOutputStream out, int value) {
        out.write((value >>> 21) & 0x7f); out.write((value >>> 14) & 0x7f);
        out.write((value >>> 7) & 0x7f); out.write(value & 0x7f);
    }
    private static byte[] concat(byte[] first, byte[] second) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(first); out.write(second);
        return out.toByteArray();
    }
}
