package wemew.lrc.sync;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Regression cover for the watcher's logcat rules, using the lines this tablet actually produced
 * (WeSing 11.0.38.278, captured 2026-10-19).
 *
 * <p>Why this exists: {@code logcat -v time} puts the pid between the tag and the colon
 * ({@code KaraRecordService( 5944):}), so a rule written as {@code "KaraRecordService: "} matches
 * nothing and fails silently. Three rules died that way on this device — pause, stop and the final
 * score — while the watcher's own log claimed the events had been handled.</p>
 */
public class WeSingLogcatWatcherTest {

    // ---- lifecycle lines that must be recognised ----------------------------------

    @Test public void recordingPageEnterCarriesSongIdAndTitle() {
        assertTrue(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:11:54.496 I/NewRecordingFragment( 5944): [, , 0]:[UI]enterRecordData=:"
                        + " mSongId = 002MGHrs4UXdcv; mSongTitle = 只要有你; mFileId = null;"
                        + " nHaveMid = 1; iActivityId = 0; mChorusUgcId = null;"));
    }

    @Test public void dealQrcCarriesTheMirroredQrcPathAndId() {
        assertTrue(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:11:59.498 I/SingLoadHelper( 5944): [, , 0]:dealQrc begin :"
                        + " /storage/emulated/0/Android/data/com.tencent.karaoke/files/qrc/"
                        + "002MGHrs4UXdcv_original.qrc"));
    }

    @Test public void padBuildEnterParamCarriesMidAndName() {
        assertTrue(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:00.440 I/RecordDataSourceModule( 5944): [, , 0]:[UI]after transfer,"
                        + "enterParam=:RecordEnterParam(songMid='002MGHrs4UXdcv', songName=只要有你,"
                        + " songQuality=-1, recordModeType=solo_audio,"));
    }

    @Test public void padBuildV8DataCarriesSongMid() {
        assertTrue(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:00.782 I/[RECORD_LOG_V8]( 5944): [, , 0]:[UI][I]"
                        + "|[TAG:RecordDataSourceModuleV8]|[DESC:prePareData mRecordDataV8:"
                        + " RecordDataV8(obbpath=/storage/emulated/0/Android/data/"
                        + "com.tencent.karaoke/files/obbligato/003vnG7W0YaNp6.tkm,"
                        + " mRecordEnterParam=RecordEnterParamV8(songMid=002MGHrs4UXdcv"));
    }

    @Test public void loadingModuleCarriesTheQqMetadata() {
        assertTrue(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:00.319 I/RecordLoadingModule( 5944): [, , 0]:[UI]prepareData:"
                        + " loadingMoredata=RecordLoadingModeData(halfStarUgcInfo=null,"
                        + " songMid=002MGHrs4UXdcv, songName=只要有你, singerName=轻烟大叔,"
                        + " singerMid=002ZUERf0GAwdp, isMultiSi"));
    }

    @Test public void startRecordOpensTheTake() {
        assertTrue(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:47.666 I/RecordService( 5944): [, , 0]:startRecord,"
                        + "recordServiceData=RecordServiceData(serviceSingInfo="
                        + "KaraServiceSingInfo[SingMode:10, ObbFilePath:/storage/emulated/0/"
                        + "Android/data/com.tencent.karaoke/files/obbligato/x.tkm],"
                        + " recordDelay=3000"));
    }

    /** startRecord arrives once per take inside a page-load burst; the mic cannot lie. */
    @Test public void microphoneStartIsASecondTakeSignal() {
        assertTrue(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:50.045 I/SimpleKaraRecorder.SimpleRecordThread( 5944): [, , 0]:"
                        + "run -> AudioRecord.startRecording, before startCost = 22"));
    }

    @Test public void resumeSingIsAThirdTakeSignal() {
        assertTrue(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:50.060 I/KaraRecordService( 5944): [, , 0]:"
                        + "[UI]resume sing with listener: 0"));
    }

    @Test public void scorerSentenceCarriesTheAccompanimentPosition() {
        assertTrue(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:31.564 I/Module::Scorer( 5944): [, , 0]:"
                        + "updateScoreResult seek:false,timeStamp:26133.65"));
        assertTrue(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:01.858 I/Module::Scorer( 5944): [, , 0]:"
                        + "updateScoreResult seek:true,timeStamp:0.0"));
    }

    @Test public void playerStartAndPositionLinesAreRecognised() {
        assertTrue(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:05.601 I/StreamPlayerV2( 5944): [, , 0]:"
                        + "[UI]start begin. recordDelay : 0"));
        assertTrue(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:05.586 I/KaraRecordService( 5944): [, , 0]:"
                        + "[UI]seekToSing, position: 944, delay: 0"));
        assertTrue(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:05.587 I/AccompanySingController( 5944): [, , 0]:"
                        + "seek: positionMs = 940"));
    }

    @Test public void everyPauseSignalOnThisBuildIsRecognised() {
        assertTrue(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:03.310 I/KaraRecordService( 5944): [, , 0]:[UI]pause sing"));
        assertTrue(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:03.317 I/SimpleKaraRecorder.SimpleRecordThread( 5944): [, , 0]:"
                        + "run: enter into pause"));
        assertTrue(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:03.344 I/StreamPlayerV2( 5944): [, , 0]:run -> pause play"));
    }

    @Test public void stopSingEndsTheTake() {
        assertTrue(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:17:23.614 I/KaraRecordService( 5944): [, , 0]:stop sing"));
    }

    @Test public void finalScoreLineIsRecognised() {
        assertTrue(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:17:25.380 I/RecordScoreModule( 5944): [, , 0]:"
                        + "[UI]songId = 004J8n3k2NXDeL, totalScore =101"));
    }

    @Test public void bluetoothSessionCarriesIdentityForPadBuilds() {
        assertTrue(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:00.458 I/BluetoothMusicManager_BluetoothMediaManager( 5944):"
                        + " [, , 0]:try onStart: $SongInfo(songName=只要有你, singerName=轻烟大叔,"
                        + " album=, duration=254, lyric=ig2.c@36cec1), but unAuthed!"));
    }

    // ---- noise that must stay noise --------------------------------------------------

    @Test public void ourOwnDiagnosticsNeverFeedTheParser() {
        assertFalse(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:04.264 I/WeSingWatch(17858): pause sing pos=2404ms"));
        assertFalse(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:04.300 I/WeSingWatch(17858): take opened by scorer"));
    }

    /** The recording page's chorus preview runs the same player with no take behind it. */
    @Test public void previewPlayerThreadsAreNotTakeSignals() {
        assertFalse(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:01.719 I/StreamPlayerV2( 5944): [, , 0]:"
                        + "KaraM4aPlayer-PlayThread-1792379521718 begin"));
        assertFalse(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:41.739 I/StreamPlayerV2( 5944): [, , 0]:"
                        + "KaraM4aPlayer-PlayThread-1792379521718 exit"));
    }

    @Test public void unrelatedPageLoadChatterIsIgnored() {
        assertFalse(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:00.440 I/BluetoothMusicManager_QMAgent( 5944): [, , 0]:"
                        + "[UI]startMonitor"));
        assertFalse(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:10:42.816 I/[RECORD_LOG_V8]( 5944): [, , 0]:[UI][I]"
                        + "|[TAG:RecordPreviewAiMultiSummaryView]|[DESC:"
                        + "startScorePanelCloseAnimation->isFromInitAutoShow:true,"
                        + " singReportEntryState:DEFAULT]"));
    }

    @Test public void rulesAreAnchoredOnWeSingsOwnTags() {
        // Same words, another process: none of the rules may fire on it.
        assertFalse(WeSingLogcatWatcher.matchesLifecycleRule(
                "10-19 11:12:03.310 I/SomeOtherService( 5944): [, , 0]:[UI]pause sing"));
    }
}
