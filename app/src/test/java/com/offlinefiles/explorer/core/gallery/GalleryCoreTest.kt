package com.offlinefiles.explorer.core.gallery

import com.offlinefiles.explorer.core.FileItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

class GalleryCoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val seoul = ZoneId.of("Asia/Seoul")

    private fun at(text: String, zone: ZoneId = seoul): Long =
        LocalDateTime.parse(text).atZone(zone).toInstant().toEpochMilli()

    private fun media(path: String, date: Long, video: Boolean = false) =
        MediaItem(id = 1, path = path, name = path.substringAfterLast('/'), isVideo = video, mime = "", size = 100, dateModified = date, date = date)

    private fun File.bytes(n: Int, modified: Long = 1_700_000_000_000L): File = also {
        parentFile?.mkdirs()
        writeBytes(ByteArray(n) { 1 })
        setLastModified(modified)
    }

    @Test fun albumsCameraFirstThenVideosThenByNewestItem() {
        val cam1 = media("/s/DCIM/Camera/IMG_1.jpg", at("2026-10-10T10:00"))
        val cam2 = media("/s/DCIM/Camera/VID_2.mp4", at("2026-10-12T10:00"), video = true)
        val shot = media("/s/Pictures/Screenshots/s.png", at("2026-10-15T10:00"))
        val movie = media("/s/Movies/m.mp4", at("2026-10-11T10:00"), video = true)
        val download = media("/s/Download/d.jpg", at("2026-10-09T10:00"))
        val index = GalleryIndex.build(listOf(download, cam1, movie, shot, cam2))

        assertEquals(listOf(shot, cam2, movie, cam1, download), index.items)
        assertEquals(
            listOf("/s/DCIM/Camera", GalleryIndex.VIDEOS, "/s/Pictures/Screenshots", "/s/Movies", "/s/Download"),
            index.albums.map { it.key },
        )
        val camera = index.album("/s/DCIM/Camera")!!
        assertEquals(AlbumType.CAMERA, camera.type)
        assertEquals(2, camera.count)
        assertEquals(1, camera.videoCount)
        assertEquals(cam2, camera.cover)
        assertEquals(listOf(cam2, cam1), index.itemsOf("/s/DCIM/Camera"))
        assertEquals(listOf(cam2, movie), index.itemsOf(GalleryIndex.VIDEOS))
        assertEquals(AlbumType.VIDEOS, index.album(GalleryIndex.VIDEOS)!!.type)
        assertEquals(index.items, index.itemsOf(GalleryIndex.ALL))
        assertEquals(3, index.photoCount)
        assertEquals(2, index.videoCount)
        assertTrue(index.itemsOf("/nowhere").isEmpty())
        assertNull(index.album("/nowhere"))
    }

    @Test fun noVideoAlbumWithoutVideosAndStableOrderForTies() {
        val t = at("2026-10-01T12:00")
        val b = media("/s/P/b.jpg", t)
        val a = media("/s/P/a.jpg", t)
        val index = GalleryIndex.build(listOf(b, a))
        assertEquals(listOf(a, b), index.items)
        assertEquals(listOf("/s/P"), index.albums.map { it.key })
        assertTrue(GalleryIndex.isCameraFolder("/storage/1234-ABCD/DCIM/Camera"))
        assertFalse(GalleryIndex.isCameraFolder("/storage/emulated/0/DCIM/Camera/Sub"))
        assertTrue(GalleryIndex.build(emptyList()).albums.isEmpty())
        // The same file listed twice by MediaStore shows up once.
        assertEquals(listOf(a, b), GalleryIndex.build(listOf(b, a, a.copy(id = 2))).items)
    }

    @Test fun sectionsFollowTheLocalCalendarDay() {
        val late = media("/s/a.jpg", at("2026-10-27T23:30"))
        val early = media("/s/b.jpg", at("2026-10-27T00:10"))
        val dayBefore = media("/s/c.jpg", at("2026-10-26T23:59"))
        val items = GalleryIndex.build(listOf(dayBefore, early, late)).items

        val inSeoul = GalleryIndex.sections(items, seoul.rules)
        assertEquals(2, inSeoul.size)
        assertEquals(listOf(late, early), inSeoul[0].items)
        assertEquals(listOf(dayBefore), inSeoul[1].items)
        assertEquals(java.time.LocalDate.of(2026, 10, 27).toEpochDay(), inSeoul[0].epochDay)

        // 00:10 in Seoul is still the 26th in UTC, so it joins the day before there.
        val inUtc = GalleryIndex.sections(items, ZoneOffset.UTC.rules)
        assertEquals(listOf(listOf(late), listOf(early, dayBefore)), inUtc.map { it.items })
        assertTrue(GalleryIndex.sections(emptyList(), seoul.rules).isEmpty())
    }

    @Test fun datesAndDurations() {
        val now = at("2026-10-27T12:00")
        val modified = at("2026-10-01T09:00")
        assertEquals(modified, MediaDates.choose(0, modified, now))
        assertEquals(modified, MediaDates.choose(1_700_000_000L, modified, now)) // seconds, not millis
        assertEquals(modified, MediaDates.choose(now + 10L * 86_400_000L, modified, now))
        val taken = at("2019-05-05T05:05")
        assertEquals(taken, MediaDates.choose(taken, modified, now))

        assertEquals("0:07", MediaDates.duration(7_000))
        assertEquals("0:01", MediaDates.duration(600))
        assertEquals("12:05", MediaDates.duration(725_000))
        assertEquals("1:02:03", MediaDates.duration(3_723_000))
    }

    @Test fun folderScanSkipsHiddenNomediaAndAppFolders() {
        val root = tmp.newFolder("storage")
        File(root, "DCIM/Camera/IMG_1.jpg").bytes(10)
        File(root, "Movies/clip.mp4").bytes(20)
        File(root, "Pictures/.thumbnails/t.jpg").bytes(10)
        File(root, "Pictures/.hidden.jpg").bytes(10)
        File(root, "Pictures/logo.svg").bytes(10)
        File(root, "Pictures/empty.jpg").bytes(0)
        File(root, "Download/.nomedia").bytes(0)
        File(root, "Download/inside.jpg").bytes(10)
        File(root, "Android/data/com.example/cache.jpg").bytes(10)
        File(root, "Documents/notes.txt").bytes(10)

        val found = MediaScan.scan(listOf(root)).sortedBy { it.path }
        assertEquals(listOf("IMG_1.jpg", "clip.mp4"), found.map { it.name })
        assertFalse(found[0].isVideo)
        assertTrue(found[1].isVideo)
        assertEquals(-1L, found[0].id)
        assertEquals(10L, found[0].size)
        assertEquals(1, MediaScan.scan(listOf(root), limit = 1).size)
    }

    @Test fun fileListItemsBecomeViewerItems() {
        val photo = FileItem("/x/a.JPG", "a.JPG", false, 10, 5, false).toMediaItem()
        assertNotNull(photo)
        assertFalse(photo!!.isVideo)
        assertEquals("/x", photo.folder)
        assertTrue(FileItem("/x/b.mkv", "b.mkv", false, 10, 5, false).toMediaItem()!!.isVideo)
        assertNull(FileItem("/x/c.txt", "c.txt", false, 10, 5, false).toMediaItem())
        assertNull(FileItem("/x/d.jpg", "d.jpg", true, 0, 5, false).toMediaItem())
    }
}
