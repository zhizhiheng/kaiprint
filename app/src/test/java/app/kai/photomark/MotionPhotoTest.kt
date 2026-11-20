package app.kai.photomark

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionPhotoTest {
    private val latin = Charsets.ISO_8859_1

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun int(value: Int) = bytes(value ushr 24, value ushr 16, value ushr 8, value)
    private fun segment(marker: Int, body: ByteArray) = bytes(0xFF, marker, (body.size + 2) shr 8, body.size + 2) + body
    private fun xmp(xml: String) = segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(latin) + xml.toByteArray())
    private val exif = segment(0xE1, "Exif\u0000\u0000MM\u0000*".toByteArray(latin) + ByteArray(40))
    private val jfif = segment(0xE0, "JFIF\u0000".toByteArray(latin) + ByteArray(9))

    /** A JPEG whose image data holds stuffed bytes, restart markers and fill bytes, as real scans do. */
    private fun jpeg(vararg headers: ByteArray, seed: Int = 7): ByteArray {
        val scan = bytes(seed, 0xFF, 0x00, 0x34, 0xFF, 0xD0, 0x56, 0xFF, 0x00, 0xFF, 0xD7, 0x11, 0x22)
        return bytes(0xFF, 0xD8) + headers.fold(ByteArray(0)) { all, next -> all + next } +
            segment(0xDB, ByteArray(65)) + segment(0xC0, ByteArray(15)) + segment(0xC4, ByteArray(28)) +
            segment(0xDA, ByteArray(10)) + scan + segment(0xC4, ByteArray(20)) + segment(0xDA, ByteArray(8)) + scan +
            bytes(0xFF, 0xFF, 0xD9)
    }

    private fun box(type: String, body: ByteArray) = int(body.size + 8) + type.toByteArray(latin) + body

    private fun mp4(metadata: ByteArray = ByteArray(0), size: Int = 300) =
        box("ftyp", "isom\u0000\u0000\u0002\u0000isommp42".toByteArray(latin)) +
            box("mdat", ByteArray(size) { (it * 31).toByte() }) +
            box("moov", box("mvhd", ByteArray(100) { 1 }) + metadata)

    private fun item(semantic: String, mime: String, length: Int, padding: Int = 0) = """
          <rdf:li rdf:parseType="Resource">
            <Container:Item
              Item:Mime="$mime"
              Item:Semantic="$semantic"
              Item:Length="$length"
              Item:Padding="$padding"/>
          </rdf:li>"""

    private fun directory(video: Int, gainmap: Int = 0, padding: Int = 0, flag: String = "GCamera:MotionPhoto=\"1\"") = """
<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.0-jc003">
  <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
    <rdf:Description rdf:about=""
        xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
        xmlns:Container="http://ns.google.com/photos/1.0/container/"
        xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
      $flag
      GCamera:MotionPhotoVersion="1"
      GCamera:MotionPhotoPresentationTimestampUs="968644">
      <Container:Directory>
        <rdf:Seq>${item("Primary", "image/jpeg", 0, padding)}${
        if (gainmap > 0) item("GainMap", "image/jpeg", gainmap) else ""}${item("MotionPhoto", "video/mp4", video)}
        </rdf:Seq>
      </Container:Directory>
    </rdf:Description>
  </rdf:RDF>
</x:xmpmeta>"""

    private fun ByteArray.videoBytes() = MotionPhoto.video(this)!!.let { copyOfRange(it.offset, it.offset + it.length) }
    private fun ByteArray.text() = String(this, latin)
    private fun ByteArray.count(text: String) = text().windowed(text.length).count { it == text }

    @Test fun aStillWithoutTheMarkIsNotAMotionPhoto() {
        val plain = jpeg(exif)
        assertFalse(MotionPhoto.marked(plain))
        assertNull(MotionPhoto.video(plain))
        assertFalse(MotionPhoto.marked(ByteArray(0)))
        assertFalse(MotionPhoto.marked("not a picture".toByteArray()))
        // A video behind a still that doesn't announce it is somebody else's data and stays out.
        assertNull(MotionPhoto.video(plain + mp4()))
    }

    @Test fun theMarkIsReadFromTheStartOfTheFileAlone() {
        val video = mp4()
        val packet = xmp(directory(video.size))
        val file = jpeg(exif, packet) + video
        assertTrue(MotionPhoto.marked(file.copyOf(2 + exif.size + packet.size)))
        assertTrue(MotionPhoto.marked(file))
        // The packet is cut off: nothing to read, and nothing breaks.
        assertFalse(MotionPhoto.marked(file.copyOf(2 + exif.size + packet.size - 1)))
    }

    @Test fun theVideoIsTheLastItemOfTheDirectory() {
        val video = mp4()
        val still = jpeg(exif, xmp(directory(video.size)))
        val found = MotionPhoto.video(still + video)!!
        assertEquals(still.size, found.offset)
        assertEquals(video.size, found.length)
        assertEquals(968644, found.timestampUs)
        assertArrayEquals(video, (still + video).videoBytes())
    }

    @Test fun aGainMapSitsBetweenTheStillAndTheVideo() {
        val video = mp4()
        val gainmap = jpeg(seed = 9)
        val file = jpeg(exif, xmp(directory(video.size, gainmap.size))) + gainmap + video
        assertArrayEquals(video, file.videoBytes())
    }

    @Test fun makerDataBehindTheVideoIsSteppedAround() {
        val video = mp4()
        val padding = ByteArray(24) { 0x5A }
        val file = jpeg(exif, xmp(directory(video.size, padding = padding.size))) + padding + video +
            "SEFHmaker trailer SEFT".toByteArray()
        assertArrayEquals(video, file.videoBytes())
    }

    @Test fun theOlderMicroVideoOffsetCountsFromTheEnd() {
        val video = mp4()
        val packet = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description xmlns:GCamera="http://ns.google.com/photos/1.0/camera/" GCamera:MicroVideo="1"
            GCamera:MicroVideoVersion="1" GCamera:MicroVideoOffset="${video.size}"
            GCamera:MicroVideoPresentationTimestampUs="-1"/></rdf:RDF></x:xmpmeta>"""
        val file = jpeg(jfif, exif, xmp(packet)) + video
        assertTrue(MotionPhoto.marked(file))
        assertArrayEquals(video, file.videoBytes())
        assertEquals(-1, MotionPhoto.video(file)!!.timestampUs)
    }

    @Test fun aDirectoryRewrittenAsElementsIsStillRead() {
        val video = mp4()
        val packet = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
            <rdf:Description rdf:about="" xmlns:GCamera="http://ns.google.com/photos/1.0/camera/"
              xmlns:GContainer="http://ns.google.com/photos/1.0/container/"
              xmlns:GItem="http://ns.google.com/photos/1.0/container/item/">
            <GCamera:MotionPhoto>1</GCamera:MotionPhoto>
            <GCamera:MotionPhotoPresentationTimestampUs>1500</GCamera:MotionPhotoPresentationTimestampUs>
            <GContainer:Directory><rdf:Seq>
              <rdf:li rdf:parseType="Resource"><GContainer:Item rdf:parseType="Resource">
                <GItem:Mime>image/jpeg</GItem:Mime><GItem:Semantic>Primary</GItem:Semantic></GContainer:Item></rdf:li>
              <rdf:li rdf:parseType="Resource"><GContainer:Item rdf:parseType="Resource">
                <GItem:Mime>video/mp4</GItem:Mime><GItem:Semantic>MotionPhoto</GItem:Semantic>
                <GItem:Length>${video.size}</GItem:Length></GContainer:Item></rdf:li>
            </rdf:Seq></GContainer:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""
        val file = jpeg(exif, xmp(packet)) + video
        assertTrue(MotionPhoto.marked(file))
        assertArrayEquals(video, file.videoBytes())
        assertEquals(1500, MotionPhoto.video(file)!!.timestampUs)
    }

    @Test fun wrongLengthsFallBackToTheFirstVideoBehindTheStill() {
        val video = mp4()
        val file = jpeg(exif, xmp(directory(video.size + 77))) + ByteArray(5) + video
        assertArrayEquals(video, file.videoBytes())
    }

    @Test fun aMarkWithNoVideoBehindItGivesNothing() {
        assertNull(MotionPhoto.video(jpeg(exif, xmp(directory(500))) + ByteArray(500) { 3 }))
        assertNull(MotionPhoto.video(jpeg(exif, xmp(directory(500)))))
    }

    @Test fun theFinishedStillCarriesTheVideoAndSaysWhereItIs() {
        val video = mp4()
        val source = jpeg(exif, xmp(directory(video.size))) + video
        val still = jpeg(exif, jfif, seed = 42)
        val result = MotionPhoto.attach(still, source, MotionPhoto.video(source)!!)!!

        assertArrayEquals(video, result.videoBytes())
        assertArrayEquals(video, result.copyOfRange(result.size - video.size, result.size))
        assertTrue(MotionPhoto.marked(result.copyOf(2000)))
        assertEquals(968644, MotionPhoto.video(result)!!.timestampUs)
        // The picture itself is the still, byte for byte, with one packet added behind its EXIF and JFIF blocks.
        val head = 2 + exif.size + jfif.size
        val added = result.size - video.size - still.size
        assertArrayEquals(still.copyOf(head), result.copyOf(head))
        assertArrayEquals(still.copyOfRange(head, still.size), result.copyOfRange(head + added, head + added + still.size - head))
        val packet = result.copyOfRange(head, head + added).text()
        assertTrue("Item:Semantic=\"MotionPhoto\" Item:Length=\"${video.size}\"" in packet)
        assertTrue("GCamera:MicroVideoOffset=\"${video.size}\"" in packet)
        assertFalse("GainMap" in packet)
    }

    @Test fun anEarlierPacketIsReplacedRatherThanDoubled() {
        val video = mp4()
        val source = jpeg(exif, xmp(directory(video.size))) + video
        val still = jpeg(exif, xmp("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><old/></x:xmpmeta>"), segment(0xE2, ByteArray(30)))
        val result = MotionPhoto.attach(still, source, MotionPhoto.video(source)!!)!!
        assertEquals(1, result.count("http://ns.adobe.com/xap/1.0/"))
        assertFalse("<old/>" in result.text())
        assertArrayEquals(video, result.videoBytes())
    }

    private fun gainmapXmp(length: Int) = """<x:xmpmeta xmlns:x="adobe:ns:meta/" x:xmptk="Adobe XMP Core 5.1.2">
        <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description
        xmlns:Container="http://ns.google.com/photos/1.0/container/" xmlns:Item="http://ns.google.com/photos/1.0/container/item/"
        xmlns:hdrgm="http://ns.adobe.com/hdr-gain-map/1.0/" hdrgm:Version="1.0"><Container:Directory><rdf:Seq>
        <rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="Primary" Item:Mime="image/jpeg"/></rdf:li>
        <rdf:li rdf:parseType="Resource"><Container:Item Item:Semantic="GainMap" Item:Mime="image/jpeg" Item:Length="$length"/></rdf:li>
        </rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>"""

    /** A multi-picture index with two images, in either byte order. */
    private fun index(little: Boolean, primary: Int, offset: Int, gainmap: Int): ByteArray {
        fun short(value: Int) = if (little) bytes(value, value shr 8) else bytes(value shr 8, value)
        fun long(value: Int) = if (little) int(value).reversedArray() else int(value)
        fun field(tag: Int, type: Int, count: Int, value: ByteArray) = short(tag) + short(type) + long(count) + value
        val body = (if (little) bytes(0x49, 0x49, 0x2A, 0x00) else bytes(0x4D, 0x4D, 0x00, 0x2A)) + long(8) + short(3) +
            field(0xB000, 7, 4, "0100".toByteArray()) + field(0xB001, 4, 1, long(2)) + field(0xB002, 7, 32, long(50)) +
            long(0) + long(0x20030000) + long(primary) + long(0) + long(0) + long(0) + long(gainmap) + long(offset) + long(0)
        return segment(0xE2, "MPF\u0000".toByteArray(latin) + body)
    }

    private fun attachOverGainmap(little: Boolean) {
        val video = mp4()
        val source = jpeg(exif, xmp(directory(video.size))) + video
        val gainmap = jpeg(seed = 9)
        fun still(primary: Int, offset: Int) = jpeg(exif, xmp(gainmapXmp(gainmap.size)), index(little, primary, offset, gainmap.size))
        val base = 2 + exif.size + xmp(gainmapXmp(gainmap.size)).size + 8
        val primary = still(0, 0).size
        val still = still(primary, primary - base) + gainmap
        val result = MotionPhoto.attach(still, source, MotionPhoto.video(source)!!)!!

        assertArrayEquals(video, result.videoBytes())
        val grown = result.size - video.size - gainmap.size
        assertArrayEquals(gainmap, result.copyOfRange(grown, grown + gainmap.size))
        val packet = result.copyOf(grown).text()
        assertTrue("hdrgm:Version=\"1.0\"" in packet)
        assertTrue("Item:Semantic=\"GainMap\" Item:Length=\"${gainmap.size}\"" in packet)
        assertTrue(packet.indexOf("\"GainMap\"") < packet.indexOf("\"MotionPhoto\""))
        // The index names the still's new length and where the gain map now begins, counted from its own header.
        val header = packet.indexOf("MPF\u0000") + 4
        val expected = index(little, grown, grown - header, gainmap.size)
        assertArrayEquals(expected, result.copyOfRange(header - 8, header - 8 + expected.size))
    }

    @Test fun aGainMapStaysBehindTheStillAndItsIndexFollows() {
        attachOverGainmap(little = false)
        attachOverGainmap(little = true)
    }

    @Test fun unknownDataBehindTheStillIsNotBuriedUnderAVideo() {
        val video = mp4()
        val source = jpeg(exif, xmp(directory(video.size))) + video
        val video1 = MotionPhoto.video(source)!!
        assertNull(MotionPhoto.attach(jpeg(exif) + ByteArray(40) { 9 }, source, video1))
        assertNull(MotionPhoto.attach(jpeg(exif) + jpeg(seed = 9), source, video1))
        assertNull(MotionPhoto.attach("not a picture".toByteArray(), source, video1))
        assertNull(MotionPhoto.attach(jpeg(exif).copyOf(120), source, video1))
        // A gain map whose index can't be followed would end up pointing into the video.
        val gainmap = jpeg(seed = 9)
        val broken = jpeg(exif, xmp(gainmapXmp(gainmap.size)), segment(0xE2, "MPF\u0000garbage".toByteArray(latin))) + gainmap
        assertNull(MotionPhoto.attach(broken, source, video1))
    }

    @Test fun thePositionInsideTheVideoIsBlankedWithoutMovingAnything() {
        val place = "+31.2304+121.4737/"
        val android = box("\u00A9xyz", bytes(0, place.length, 0x15, 0xC7) + place.toByteArray())
        val keys = box("keys", int(0) + int(2) + box("mdta", "com.android.version".toByteArray()) +
            box("mdta", "com.apple.quicktime.location.ISO6709".toByteArray()))
        fun value(text: String) = box("data", int(1) + int(0) + text.toByteArray())
        fun entry(key: Int, text: String) = int(8 + value(text).size) + int(key) + value(text)
        val notes = box("hdlr", ByteArray(25)) + keys + box("ilst", entry(1, "android-14") + entry(2, place))
        // QuickTime writes the notes box bare, ISO files put a version word in front.
        for (meta in listOf(box("meta", notes), box("meta", int(0) + notes))) {
            val video = mp4(box("udta", android + box("auth", "someone".toByteArray())) + meta)
            val source = jpeg(exif, xmp(directory(video.size))) + video
            val found = MotionPhoto.video(source)!!
            assertEquals(2, source.count(place))

            val cleaned = source.copyOf()
            MotionPhoto.dropLocation(cleaned, found)
            assertEquals(0, cleaned.count(place))
            assertEquals(0, cleaned.count("\u00A9xyz"))
            // Everything else is where it was: the other notes, the tracks and the picture data.
            assertTrue("someone" in cleaned.text())
            assertTrue("android-14" in cleaned.text())
            assertEquals(4 + 3 + place.length * 2, source.indices.count { source[it] != cleaned[it] })
            assertNotNull(MotionPhoto.video(cleaned))
            assertEquals(found.length, MotionPhoto.video(cleaned)!!.length)
        }
    }
}
