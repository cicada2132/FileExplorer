package com.offlinefiles.explorer.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.charset.Charset

class PlayerCoreTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun subRipAndWebVtt() {
        val srt = """
            1
            00:00:01,000 --> 00:00:03,500
            <i>안녕하세요</i>
            두 번째 줄

            2
            00:00:04,000 --> 00:00:05,000 X1:10 Y1:20
            {\an8}Top line &amp; more

            3
            01:02:03,004 --> 01:02:04,000
            Late
        """.trimIndent().replace("\n", "\r\n")
        val cues = Subtitles.parseSrt(srt)
        assertEquals(3, cues.size)
        assertEquals(Cue(1000, 3500, "안녕하세요\n두 번째 줄"), cues[0])
        assertEquals("Top line & more", cues[1].text)
        assertEquals(3_723_004L, cues[2].start)

        val vtt = """
            WEBVTT

            NOTE ignored

            00:01.500 --> 00:02.000 align:start
            Short form

            00:00:03.000 --> 00:00:04.250
            <c.yellow>Long form</c>
        """.trimIndent()
        val v = Subtitles.parseSrt(vtt)
        assertEquals(listOf(Cue(1500, 2000, "Short form"), Cue(3000, 4250, "Long form")), v)
        assertNull(Subtitles.clock("12"))
        assertNull(Subtitles.clock("1:2:3:4"))
        assertEquals(61_200L, Subtitles.clock("1:01.2"))
    }

    @Test
    fun samiWithTwoLanguages() {
        val smi = """
            <SAMI>
            <HEAD><STYLE TYPE="text/css"><!--
            P { margin-left:8pt; }
            .KRCC { Name:한국어; lang:ko-KR; }
            .ENCC { Name:English; lang:en-US; }
            --></STYLE></HEAD>
            <BODY>
            <SYNC Start=1000><P Class=KRCC>안녕<br>세상
            <SYNC Start=1000><P Class=ENCC>Hello<BR>world
            <SYNC Start=2500><P Class=KRCC>&nbsp;
            <SYNC Start=3000><P Class=ENCC>&nbsp;
            <SYNC Start=4000><P Class=KRCC><font color="#ffff00">노란
            자막</font>
            </BODY></SAMI>
        """.trimIndent()
        val tracks = Subtitles.parse(smi, "smi", "movie.smi")
        assertEquals(listOf("movie.smi · 한국어", "movie.smi · English"), tracks.map { it.label })
        val ko = tracks[0]
        assertEquals(listOf(Cue(1000, 2500, "안녕\n세상"), Cue(4000, 9000, "노란 자막")), ko.cues)
        assertEquals("Hello\nworld", tracks[1].textAt(2900))
        assertNull(tracks[1].textAt(3000))

        // A single-language file keeps the plain file name.
        val single = Subtitles.parse("<SAMI><BODY><SYNC Start=0><P>하나<SYNC Start=900><P>&nbsp;</BODY></SAMI>", "smi", "a.smi")
        assertEquals(listOf("a.smi"), single.map { it.label })
        assertEquals(listOf(Cue(0, 900, "하나")), single[0].cues)
    }

    @Test
    fun subStationAlpha() {
        val ass = """
            [Script Info]
            Title: test

            [V4+ Styles]
            Format: Name, Fontname, Fontsize
            Style: Default,Arial,20

            [Events]
            Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text
            Dialogue: 0,0:00:02.50,0:00:04.00,Default,,0,0,0,,{\b1}Bold{\b0}, with comma\Nsecond\hline
            Dialogue: 0,0:00:01.00,0:00:02.00,Default,,0,0,0,,First
        """.trimIndent()
        val cues = Subtitles.parseAss(ass)
        assertEquals(listOf(Cue(1000, 2000, "First"), Cue(2500, 4000, "Bold, with comma\nsecond line")), cues)
    }

    @Test
    fun encodingsAndOverlappingCues() {
        val text = "1\n00:00:01,000 --> 00:00:02,000\n한글 자막\n"
        val cp949 = text.toByteArray(Charset.forName("MS949"))
        assertEquals(text, Subtitles.decode(cp949))
        assertEquals(text, Subtitles.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + text.toByteArray()))
        assertEquals(text, Subtitles.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + text.toByteArray(Charsets.UTF_16LE)))
        assertEquals(text, Subtitles.decode(text.toByteArray()))

        val track = SubtitleTrack(
            "t",
            listOf(Cue(5000, 9000, "long"), Cue(0, 1000, "first"), Cue(6000, 7000, "overlap"), Cue(8000, 8000, "empty")),
        )
        assertEquals(3, track.cues.size)
        assertNull(track.textAt(-1))
        assertEquals("first", track.textAt(0))
        assertNull(track.textAt(1000))
        assertEquals("long\noverlap", track.textAt(6500))
        assertEquals("long", track.textAt(8500))
        assertNull(track.textAt(9000))
    }

    @Test
    fun subtitleFilesNextToTheVideo() {
        val dir = tmp.newFolder("movies")
        val video = File(dir, "Movie.2024.mp4").apply { writeText("x") }
        val names = listOf("Movie.2024.en.srt", "Movie.2024.smi", "Movie.2024.ko.srt", "other.ass", "Movie.2024.txt", "notes.srt")
        names.forEach { File(dir, it).writeText("x") }
        val found = Subtitles.findFor(video, dir.listFiles()!!.toList())
        assertEquals(listOf("Movie.2024.smi", "Movie.2024.en.srt", "Movie.2024.ko.srt", "notes.srt", "other.ass"), found.map { it.name })
        assertEquals("Movie.2024.ko.srt", Subtitles.preferred(video, found, "ko")?.name)
        assertEquals("Movie.2024.en.srt", Subtitles.preferred(video, found, "en")?.name)
        assertEquals("Movie.2024.smi", Subtitles.preferred(video, found, "ja")?.name)
        assertNull(Subtitles.preferred(File(dir, "clip.mp4"), found, "ko"))

        File(dir, "Movie.2024.smi").writeBytes(
            "<SAMI><BODY><SYNC Start=500><P>파일에서 읽기<SYNC Start=1500><P>&nbsp;</BODY></SAMI>".toByteArray(Charset.forName("MS949")),
        )
        val loaded = Subtitles.load(File(dir, "Movie.2024.smi"))
        assertEquals("파일에서 읽기", loaded.single().textAt(1000))
        assertTrue(Subtitles.load(File(dir, "missing.srt")).isEmpty())
    }

    @Test
    fun videoFitsTheScreen() {
        // 1920×1080 video on a 2400×1080 landscape phone.
        assertEquals(1920f to 1080f, VideoLayout.size(AspectMode.FIT, 1920, 1080, 2400f, 1080f))
        assertEquals(2400f to 1350f, VideoLayout.size(AspectMode.FILL, 1920, 1080, 2400f, 1080f))
        assertEquals(2400f to 1080f, VideoLayout.size(AspectMode.STRETCH, 1920, 1080, 2400f, 1080f))
        assertEquals(640f to 360f, VideoLayout.size(AspectMode.ORIGINAL, 640, 360, 2400f, 1080f))
        assertEquals(1440f to 1080f, VideoLayout.size(AspectMode.RATIO_4_3, 1920, 1080, 2400f, 1080f))
        // Portrait phone: 16:9 is limited by the width.
        assertEquals(1080f to 607.5f, VideoLayout.size(AspectMode.RATIO_16_9, 1920, 1080, 1080f, 2400f))
        // Size not known yet: fill the screen.
        assertEquals(1080f to 2400f, VideoLayout.size(AspectMode.FIT, 0, 0, 1080f, 2400f))
        assertEquals(AspectMode.FIT, AspectMode.RATIO_4_3.next())
    }

    @Test
    fun queueOrderAndRepeat() {
        val order = listOf(0, 1, 2)
        assertEquals(1, PlayQueue.step(order, 0, 1, RepeatMode.OFF))
        assertNull(PlayQueue.step(order, 2, 1, RepeatMode.OFF))
        assertNull(PlayQueue.step(order, 0, -1, RepeatMode.ONE))
        assertEquals(0, PlayQueue.step(order, 2, 1, RepeatMode.ALL))
        assertEquals(2, PlayQueue.step(order, 0, -1, RepeatMode.ALL))
        assertEquals(1, PlayQueue.afterCompletion(order, 1, RepeatMode.ONE))
        assertNull(PlayQueue.afterCompletion(order, 2, RepeatMode.OFF))
        assertEquals(0, PlayQueue.afterCompletion(listOf(0), 0, RepeatMode.ALL))

        val shuffled = PlayQueue.shuffled(10, 4, java.util.Random(7))
        assertEquals(4, shuffled.first())
        assertEquals((0 until 10).toSet(), shuffled.toSet())
        assertEquals(shuffled[1], PlayQueue.step(shuffled, 4, 1, RepeatMode.OFF))
        assertEquals(RepeatMode.ALL, RepeatMode.OFF.next())
        assertEquals(RepeatMode.ONE, RepeatMode.ALL.next())
        assertEquals(RepeatMode.OFF, RepeatMode.ONE.next())
    }

    @Test
    fun timesSpeedsAndResume() {
        assertEquals("0:07", PlayerTime.format(7_999))
        assertEquals("1:02:03", PlayerTime.format(3_723_000))
        assertEquals("0:01:23", PlayerTime.format(83_000, hours = true))
        assertEquals("+0:15", PlayerTime.signed(15_000))
        assertEquals("−1:05", PlayerTime.signed(-65_000))
        assertEquals(30_000L, PlayerTime.swipeSeek(1f, 30_000))
        assertEquals(60_000L, PlayerTime.swipeSeek(0.5f, 7_200_000))
        assertEquals(-5_000L, PlayerTime.swipeSeek(-0.5f, 4_000))

        assertEquals("1x", PlaybackSpeed.label(1f))
        assertEquals("1.25x", PlaybackSpeed.label(1.25f))
        assertEquals("0.5x", PlaybackSpeed.label(0.5f))
        assertEquals(4f, PlaybackSpeed.clamp(9f))
        assertEquals(1.35f, PlaybackSpeed.clamp(1.3333f))

        assertFalse(Resume.shouldRemember(3_000, 600_000))
        assertTrue(Resume.shouldRemember(300_000, 600_000))
        assertFalse(Resume.shouldRemember(595_000, 600_000))
        assertTrue(Resume.shouldRemember(30_000, 0))
        assertTrue(Resume.applies(60_000, isVideo = true))
        assertFalse(Resume.applies(240_000, isVideo = false))
        assertTrue(Resume.applies(3_600_000, isVideo = false))
    }
}
