package com.zuoqirun.lyricscompanion;

import org.junit.Test;
import static org.junit.Assert.*;

public class NotificationTrackParserTest {
    @Test public void separatesArtistAndAlbum() {
        NotificationTrackParser.Track track = NotificationTrackParser.parse(
                "晴天", "周杰伦 · 叶惠美", "", "QQ 音乐");
        assertEquals("晴天", track.title);
        assertEquals("周杰伦", track.artist);
    }

    @Test public void handlesAppHeadingAndCompositeBodyWithoutGuessingOrder() {
        NotificationTrackParser.Track track = NotificationTrackParser.parse(
                "网易云音乐", "晴天 - 周杰伦", "", "网易云音乐");
        assertEquals("晴天 - 周杰伦", track.title);
        assertEquals("", track.artist);
        assertFalse(LocalTrackQueryRules.fallbackQueries("netease", track.title, track.artist).isEmpty());
    }

    @Test public void keepsUsbPathForSidecarLookup() {
        NotificationTrackParser.Track track = NotificationTrackParser.parse(
                "/storage/usb1/01. 周杰伦 - 晴天.flac", "未知歌手", "", "系统音乐");
        assertEquals("/storage/usb1/01. 周杰伦 - 晴天.flac", track.mediaUri);
        assertEquals("", track.artist);
    }

    @Test public void rejectsStatusAndPromotionalNotifications() {
        assertNull(NotificationTrackParser.parse("QQ 音乐", "正在缓冲", "", "QQ 音乐"));
        assertNull(NotificationTrackParser.parse("下载完成", "", "", "QQ 音乐"));
        assertNull(NotificationTrackParser.parse("点击领取会员", "", "", "QQ 音乐"));
    }

    @Test public void ignoresTimeAsArtist() {
        assertEquals("", NotificationTrackParser.parse("晴天", "01:23 / 04:29", "", "音乐").artist);
    }
}
