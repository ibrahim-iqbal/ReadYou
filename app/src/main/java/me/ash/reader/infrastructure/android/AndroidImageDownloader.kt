package me.ash.reader.infrastructure.android

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.webkit.URLUtil
import androidx.annotation.CheckResult
import androidx.annotation.DeprecatedSinceApi
import androidx.core.content.contentValuesOf
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import me.ash.reader.R
import me.ash.reader.infrastructure.di.IODispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.FileOutputStream
import java.io.IOException
import java.lang.Character
import javax.inject.Inject
import kotlin.io.path.Path
import kotlin.io.path.createFile
import kotlin.io.path.createParentDirectories

class AndroidImageDownloader @Inject constructor(
    @ApplicationContext private val context: Context,
    @IODispatcher private val ioDispatcher: CoroutineDispatcher,
    private val okHttpClient: OkHttpClient,
) {
    @CheckResult
    suspend fun downloadImage(imageUrl: String): Result<Uri> {
        return withContext(ioDispatcher) {
            Request.Builder().url(imageUrl).build().runCatching {
                okHttpClient.newCall(this).execute().run {

                    val fileName = sanitizeFileName(
                        URLUtil.guessFileName(
                            imageUrl, header("Content-Disposition"), body.contentType()?.toString()
                        )
                    )

                    val relativePath =
                        Environment.DIRECTORY_PICTURES + "/" + context.getString(R.string.read_you)

                    val resolver = context.contentResolver

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        val imageCollection =
                            MediaStore.Images.Media.getContentUri(
                                MediaStore.VOLUME_EXTERNAL_PRIMARY
                            )


                        val imageDetails = contentValuesOf(
                            MediaStore.Images.Media.DISPLAY_NAME to fileName,
                            MediaStore.Images.Media.RELATIVE_PATH to relativePath,
                            MediaStore.Images.Media.IS_PENDING to 1
                        )

                        val imageUri = resolver.insert(imageCollection, imageDetails)
                            ?: return@withContext Result.failure(IOException("Cannot create image"))

                        resolver.openFileDescriptor(imageUri, "w", null).use { pfd ->
                            body.byteStream().use {
                                it.copyTo(
                                    FileOutputStream(
                                        pfd?.fileDescriptor ?: return@withContext Result.failure(
                                            IOException("Null fd")
                                        )
                                    )
                                )
                            }
                        }
                        imageDetails.run {
                            clear()
                            put(MediaStore.Images.Media.IS_PENDING, 0)
                            resolver.update(imageUri, this, null, null)
                        }
                        imageUri
                    } else {
                        saveImageForAndroidP(
                            fileName,
                            Environment.getExternalStoragePublicDirectory(relativePath).path
                        )
                    }
                }
            }
        }

    }

    // ENAMETOOLONG is a limit on encoded bytes (NAME_MAX = 255 on ext4/f2fs), not on
    // Kotlin/UTF-16 character count. A filename guessed from feed content can contain
    // any script, so truncating by .length alone can still leave a >255-byte name for
    // CJK, Cyrillic, emoji, etc. — truncate by UTF-8 byte length instead, one whole
    // code point at a time so a surrogate pair or multi-byte sequence is never split.
    internal fun sanitizeFileName(fileName: String): String {
        if (fileName.toByteArray(Charsets.UTF_8).size <= MAX_FILENAME_BYTES) return fileName
        val dot = fileName.lastIndexOf('.')
        val ext = if (dot > 0 && fileName.length - dot in 2..10) fileName.substring(dot) else ""
        val base = if (ext.isEmpty()) fileName else fileName.substring(0, dot)
        val maxBaseBytes = (MAX_FILENAME_BYTES - ext.toByteArray(Charsets.UTF_8).size).coerceAtLeast(1)
        return truncateToUtf8ByteLimit(base, maxBaseBytes) + ext
    }

    private fun truncateToUtf8ByteLimit(text: String, maxBytes: Int): String {
        var byteCount = 0
        var charIndex = 0
        while (charIndex < text.length) {
            val codePoint = text.codePointAt(charIndex)
            val codePointChars = Character.charCount(codePoint)
            val codePointBytes = String(Character.toChars(codePoint)).toByteArray(Charsets.UTF_8).size
            if (byteCount + codePointBytes > maxBytes) break
            byteCount += codePointBytes
            charIndex += codePointChars
        }
        return text.substring(0, charIndex)
    }

    @DeprecatedSinceApi(29)
    private fun Response.saveImageForAndroidP(
        fileName: String,
        imageDirectory: String,
    ): Uri {
        val file = Path(imageDirectory, fileName).createParentDirectories().createFile().toFile()

        body.byteStream().use {
            it.copyTo(file.outputStream())
        }

        var contentUri: Uri = Uri.fromFile(file)

        MediaScannerConnection.scanFile(context, arrayOf(file.path), null) { _, uri ->
            contentUri = uri
        }

        return contentUri
    }

    private companion object {
        const val MAX_FILENAME_BYTES = 200
    }
}