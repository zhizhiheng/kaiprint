package app.kai.photomark

/**
 * Motion photos as Android cameras save them: a JPEG still, sometimes a gain map, then an MP4, with an XMP packet in
 * the still that tells readers where the video sits. Both the current `MotionPhoto` directory and the older
 * `MicroVideo` offset are read, and a written file carries both so that older galleries find the video too.
 */
object MotionPhoto {
    /** Larger sources stay still photos: the file is held in memory while its video is carried over. */
    const val MAX_SOURCE = 48 * 1024 * 1024

    /** Where the video sits in the source file, and the moment of it the still shows (-1 when not recorded). */
    class Video(val offset: Int, val length: Int, val timestampUs: Long)

    /** Whether the beginning of a JPEG file announces a video behind the still. */
    fun marked(head: ByteArray): Boolean {
        val xmp = headers(head).xmp(head)?.let { text(head, it) } ?: return false
        return property(xmp, "MotionPhoto") == "1" || property(xmp, "MicroVideo") == "1" ||
            items(xmp).any { it.semantic == "MotionPhoto" }
    }

    /** The video inside [source], or null when the file names none or names a place that holds no MP4. */
    fun video(source: ByteArray): Video? {
        val xmp = headers(source).xmp(source)?.let { text(source, it) } ?: return null
        val items = items(xmp)
        val index = items.indexOfFirst { it.semantic == "MotionPhoto" }
        val legacy = property(xmp, "MicroVideoOffset")?.toLongOrNull()
        if (index < 1 && legacy == null) return null
        val stamp = (property(xmp, "MotionPhotoPresentationTimestampUs")
            ?: property(xmp, "MicroVideoPresentationTimestampUs"))?.toLongOrNull() ?: -1
        val size = source.size.toLong()
        val candidates = sequence {
            val still by lazy { imageEnd(source, 0).toLong() }
            if (index > 0) {
                val length = items[index].length
                // Counted back from the end of the file, which is how the format lays its items out.
                yield(size - items.drop(index).sumOf { it.length } to length)
                // Counted on from the still, for files that keep maker data behind the video.
                if (still > 0) yield(still + items[0].padding + items.subList(1, index).sumOf { it.length } to length)
            }
            if (legacy != null) yield(size - legacy to legacy)
            // The offsets are wrong but the file says it has a video: take the first MP4 behind the still.
            if (still > 0) {
                val start = (still.toInt()..source.size - 12).firstOrNull { source.startsWith(FTYP, it + 4) }
                if (start != null) yield(start.toLong() to (boxes(source, start, source.size).lastOrNull()?.end ?: 0) - start.toLong())
            }
        }
        val (start, length) = candidates.firstOrNull { (start, length) ->
            start > 0 && length > 12 && start + length <= size && source.startsWith(FTYP, start.toInt() + 4)
        } ?: return null
        return Video(start.toInt(), length.toInt(), stamp)
    }

    /**
     * Blanks the position a camera may have stored in the video, so that an output without GPS stays without it.
     * Sizes don't change; the box is turned into free space.
     */
    fun dropLocation(source: ByteArray, video: Video) {
        val movie = boxes(source, video.offset, video.offset + video.length).firstOrNull { it.type == "moov" } ?: return
        fun keyed(meta: Box) {
            // An ISO `meta` box opens with a version word, a QuickTime one doesn't.
            val inner = boxes(source, meta.body, meta.end).takeIf { list -> list.any { it.type == "hdlr" } }
                ?: boxes(source, meta.body + 4, meta.end)
            val keys = inner.firstOrNull { it.type == "keys" } ?: return
            val places = boxes(source, keys.body + 8, keys.end).mapIndexedNotNull { index, key ->
                (index + 1L).takeIf { String(source, key.body, key.end - key.body, Charsets.ISO_8859_1).contains("location", true) }
            }
            for (list in inner.filter { it.type == "ilst" }) {
                for (entry in boxes(source, list.body, list.end)) {
                    if (source.u32(entry.start + 4) !in places) continue
                    for (value in boxes(source, entry.body, entry.end)) {
                        if (value.type == "data" && value.body + 8 < value.end) source.fill(0, value.body + 8, value.end)
                    }
                }
            }
        }
        for (box in boxes(source, movie.body, movie.end)) {
            when (box.type) {
                "meta" -> keyed(box)
                "udta" -> for (child in boxes(source, box.body, box.end)) {
                    when (child.type) {
                        "©xyz", "loci" -> {
                            source.fill(0, child.body, child.end)
                            FREE.copyInto(source, child.start + 4)
                        }
                        "meta" -> keyed(child)
                    }
                }
            }
        }
    }

    /**
     * [still] with the video of [source] behind it and an XMP packet that points at the video, or null when the
     * still can't carry one. A gain map behind the still stays where it is and is listed ahead of the video.
     */
    fun attach(still: ByteArray, source: ByteArray, video: Video): ByteArray? {
        val end = imageEnd(still, 0)
        if (end < 0 || video.offset < 0 || video.length <= 0 || video.offset + video.length > source.size) return null
        val segments = headers(still)
        val old = segments.xmp(still)
        val trailing = still.size - end
        // Whatever follows the still has to be the gain map the encoder wrote, because the directory must name it.
        if (trailing > 0 && !(still.startsWith(START, end) && old != null && "hdrgm:Version" in text(still, old))) return null
        val packet = packet(video, trailing)
        val from = old?.start
            ?: segments.takeWhile { it.marker == 0xE0 || it.marker == 0xE1 && still.startsWith(EXIF, it.start + 4) }
                .lastOrNull()?.end ?: 2
        val to = old?.end ?: from
        val primary = end - (to - from) + packet.size
        val result = ByteArray(primary + trailing + video.length)
        still.copyInto(result, 0, 0, from)
        packet.copyInto(result, from)
        still.copyInto(result, from + packet.size, to, still.size)
        source.copyInto(result, primary + trailing, video.offset, video.offset + video.length)
        return result.takeIf { trailing == 0 || repoint(result, primary, trailing) }
    }

    /** Brings the multi-picture index in line with the still's new length. False when the index can't be followed. */
    private fun repoint(file: ByteArray, primary: Int, gainmap: Int): Boolean {
        val index = headers(file).firstOrNull { it.marker == 0xE2 && file.startsWith(MPF, it.start + 4) } ?: return true
        val base = index.start + 8
        val little = when {
            file.startsWith(byteArrayOf(0x49, 0x49, 0x2A, 0x00), base) -> true
            file.startsWith(byteArrayOf(0x4D, 0x4D, 0x00, 0x2A), base) -> false
            else -> return false
        }
        fun short(at: Int) = if (little) file.u8(at) or (file.u8(at + 1) shl 8) else file.u16(at)
        fun long(at: Int) = if (little) (0..3).sumOf { file.u8(at + it).toLong() shl (8 * it) } else file.u32(at)
        fun put(at: Int, value: Int) {
            for (i in 0..3) file[at + i] = (value ushr (8 * if (little) i else 3 - i)).toByte()
        }
        val directory = base + long(base + 4)
        if (directory + 2 > index.end) return false
        val fields = (0 until short(directory.toInt())).map { directory.toInt() + 2 + it * 12 }
        if (fields.any { it + 12 > index.end }) return false
        val images = fields.firstOrNull { short(it) == 0xB001 }?.let { long(it + 8) }
        val entries = fields.firstOrNull { short(it) == 0xB002 }?.let { base + long(it + 8) } ?: return false
        if (images != 2L || entries + 32 > index.end) return false
        val entry = entries.toInt()
        put(entry + 4, primary)
        put(entry + 20, gainmap)
        put(entry + 24, primary - base)
        return true
    }

    private fun packet(video: Video, gainmap: Int): ByteArray {
        fun item(mime: String, semantic: String, length: Int) = "<rdf:li rdf:parseType=\"Resource\">" +
            "<Container:Item Item:Mime=\"$mime\" Item:Semantic=\"$semantic\" Item:Length=\"$length\" Item:Padding=\"0\"/></rdf:li>"
        val xml = buildString {
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\" x:xmptk=\"Adobe XMP Core 5.1.0-jc003\">")
            append("<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\"")
            append(" xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\"")
            append(" xmlns:Container=\"http://ns.google.com/photos/1.0/container/\"")
            append(" xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\"")
            if (gainmap > 0) append(" xmlns:hdrgm=\"http://ns.adobe.com/hdr-gain-map/1.0/\" hdrgm:Version=\"1.0\"")
            append(" GCamera:MotionPhoto=\"1\" GCamera:MotionPhotoVersion=\"1\"")
            append(" GCamera:MotionPhotoPresentationTimestampUs=\"${video.timestampUs}\"")
            append(" GCamera:MicroVideo=\"1\" GCamera:MicroVideoVersion=\"1\" GCamera:MicroVideoOffset=\"${video.length}\"")
            append(" GCamera:MicroVideoPresentationTimestampUs=\"${video.timestampUs}\">")
            append("<Container:Directory><rdf:Seq>")
            append(item("image/jpeg", "Primary", 0))
            if (gainmap > 0) append(item("image/jpeg", "GainMap", gainmap))
            append(item("video/mp4", "MotionPhoto", video.length))
            append("</rdf:Seq></Container:Directory></rdf:Description></rdf:RDF></x:xmpmeta>")
        }
        val body = XMP + xml.toByteArray(Charsets.UTF_8)
        val length = body.size + 2
        return byteArrayOf(0xFF.toByte(), 0xE1.toByte(), (length shr 8).toByte(), length.toByte()) + body
    }

    private class Item(val semantic: String, val length: Long, val padding: Long)

    /** The container directory. Cameras write its entries as attributes, metadata editors as elements. */
    private fun items(xmp: String) = xmp.split("<rdf:li").drop(1).mapNotNull { entry ->
        val semantic = property(entry, "Semantic") ?: return@mapNotNull null
        Item(semantic, property(entry, "Length")?.toLongOrNull()?.coerceAtLeast(0) ?: 0,
            property(entry, "Padding")?.toLongOrNull()?.coerceAtLeast(0) ?: 0)
    }

    private fun property(xmp: String, name: String): String? =
        (Regex("""[:\s]$name\s*=\s*["']([^"']*)["']""").find(xmp)
            ?: Regex("""<[\w.-]+:$name>\s*([^<]*?)\s*</""").find(xmp))?.groupValues?.get(1)

    private class Segment(val marker: Int, val start: Int, val end: Int)

    /** The marker segments ahead of the image data, as far as [data] holds them whole. */
    private fun headers(data: ByteArray): List<Segment> {
        val found = ArrayList<Segment>()
        if (!data.startsWith(START, 0)) return found
        var at = 2
        while (at + 4 <= data.size && data.u8(at) == 0xFF) {
            val marker = data.u8(at + 1)
            if (marker == 0xFF) {
                at++
                continue
            }
            if (marker == 0xD9 || marker == 0xDA) break
            val end = at + 2 + data.u16(at + 2)
            if (end < at + 4 || end > data.size) break
            found += Segment(marker, at, end)
            at = end
        }
        return found
    }

    private fun List<Segment>.xmp(data: ByteArray) = firstOrNull { it.marker == 0xE1 && data.startsWith(XMP, it.start + 4) }

    private fun text(data: ByteArray, segment: Segment) =
        String(data, segment.start + 4 + XMP.size, segment.end - segment.start - 4 - XMP.size, Charsets.UTF_8)

    /** The offset just past the end marker of the JPEG that starts at [from], or -1 when the image is cut short. */
    private fun imageEnd(data: ByteArray, from: Int): Int {
        if (!data.startsWith(START, from)) return -1
        var at = from + 2
        while (at + 1 < data.size) {
            if (data.u8(at) != 0xFF) return -1
            val marker = data.u8(at + 1)
            when {
                marker == 0xFF -> at++
                marker == 0xD9 -> return at + 2
                marker in 0xD0..0xD7 || marker == 0x01 -> at += 2
                else -> {
                    if (at + 4 > data.size) return -1
                    at += 2 + data.u16(at + 2).coerceAtLeast(2)
                    if (marker != 0xDA) continue
                    // Image data runs up to the next marker that is neither a stuffed byte nor a restart.
                    while (at + 1 < data.size) {
                        val next = if (data.u8(at) == 0xFF) data.u8(at + 1) else -1
                        if (next == 0x00 || next in 0xD0..0xD7) at += 2
                        else if (next == -1 || next == 0xFF) at++
                        else break
                    }
                }
            }
        }
        return -1
    }

    private class Box(val type: String, val start: Int, val body: Int, val end: Int)

    /** The run of well-formed MP4 boxes between [from] and [to]. */
    private fun boxes(data: ByteArray, from: Int, to: Int): List<Box> {
        val found = ArrayList<Box>()
        var at = from
        while (at >= 0 && at + 8 <= to) {
            var size = data.u32(at)
            var body = at + 8
            if (size == 1L) {
                if (at + 16 > to) break
                size = (data.u32(at + 8) shl 32) or data.u32(at + 12)
                body = at + 16
            } else if (size == 0L) {
                size = (to - at).toLong()
            }
            if (size < body - at || size > to - at) break
            found += Box(String(data, at + 4, 4, Charsets.ISO_8859_1), at, body, at + size.toInt())
            at += size.toInt()
        }
        return found
    }

    private val START = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    private val XMP = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
    private val EXIF = "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1)
    private val MPF = "MPF\u0000".toByteArray(Charsets.ISO_8859_1)
    private val FTYP = "ftyp".toByteArray(Charsets.ISO_8859_1)
    private val FREE = "free".toByteArray(Charsets.ISO_8859_1)

    private fun ByteArray.u8(at: Int) = this[at].toInt() and 0xFF
    private fun ByteArray.u16(at: Int) = (u8(at) shl 8) or u8(at + 1)
    private fun ByteArray.u32(at: Int) = (u16(at).toLong() shl 16) or u16(at + 2).toLong()

    private fun ByteArray.startsWith(prefix: ByteArray, at: Int): Boolean {
        if (at < 0 || at + prefix.size > size) return false
        return prefix.indices.all { this[at + it] == prefix[it] }
    }
}
