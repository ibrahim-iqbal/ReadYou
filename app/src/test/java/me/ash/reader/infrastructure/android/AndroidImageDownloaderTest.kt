package me.ash.reader.infrastructure.android

import android.content.Context
import kotlinx.coroutines.Dispatchers
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.junit.MockitoJUnitRunner

@RunWith(MockitoJUnitRunner::class)
class AndroidImageDownloaderTest {

    private val downloader = AndroidImageDownloader(
        context = mock(Context::class.java),
        ioDispatcher = Dispatchers.Unconfined,
        okHttpClient = mock(OkHttpClient::class.java),
    )

    private fun byteLength(name: String) = name.toByteArray(Charsets.UTF_8).size

    @Test
    fun `short ascii filename is returned unchanged`() {
        val name = "photo.jpg"
        assertEquals(name, downloader.sanitizeFileName(name))
    }

    @Test
    fun `long ascii filename is truncated by character count`() {
        val name = "a".repeat(300) + ".jpg"
        val result = downloader.sanitizeFileName(name)
        assertTrue(byteLength(result) <= 200)
        assertTrue(result.endsWith(".jpg"))
    }

    @Test
    fun `long multibyte filename is truncated by UTF-8 byte length, not character count`() {
        // Each character here is 3 bytes in UTF-8. 100 of them alone is already
        // 300 bytes — well past the 200-byte budget even though only 100 chars.
        val name = "文".repeat(100) + ".jpg"
        val result = downloader.sanitizeFileName(name)
        assertTrue(byteLength(result) <= 200)
        assertTrue(result.endsWith(".jpg"))
    }

    @Test
    fun `truncation never splits a surrogate pair`() {
        // U+1F600 GRINNING FACE is a surrogate pair in UTF-16 and 4 bytes in UTF-8.
        val emoji = "😀"
        val name = emoji.repeat(80) + ".png"
        val result = downloader.sanitizeFileName(name)
        assertTrue(byteLength(result) <= 200)
        // A split surrogate pair would make this string invalid / throw on re-encoding.
        assertEquals(result, String(result.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun `filename with no extension is truncated as-is`() {
        val name = "b".repeat(300)
        val result = downloader.sanitizeFileName(name)
        assertTrue(byteLength(result) <= 200)
    }

    @Test
    fun `implausibly long suffix after the last dot is not treated as an extension`() {
        val name = "a".repeat(250) + "." + "b".repeat(50)
        val result = downloader.sanitizeFileName(name)
        assertTrue(byteLength(result) <= 200)
    }
}
