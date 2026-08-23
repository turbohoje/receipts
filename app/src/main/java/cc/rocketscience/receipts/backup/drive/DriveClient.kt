package cc.rocketscience.receipts.backup.drive

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

@Serializable
data class DriveFile(
    val id: String,
    val name: String = "",
    val size: String? = null,
    val createdTime: String? = null,
)

@Serializable
private data class DriveFileList(val files: List<DriveFile> = emptyList())

class DriveException(message: String, val status: Int? = null) : Exception(message)

/**
 * The five Drive v3 calls this app needs, over `HttpURLConnection`.
 *
 * Deliberately not `google-api-client`: that pulls in a large transitive tree (its own HTTP
 * stack, Guava) to wrap REST endpoints that are a few lines each, and it would dominate the
 * APK for an app this size.
 */
class DriveClient(private val json: Json = Json { ignoreUnknownKeys = true }) {

    companion object {
        const val FOLDER_NAME = "Receipts Backups"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val API = "https://www.googleapis.com/drive/v3"
        private const val UPLOAD = "https://www.googleapis.com/upload/drive/v3"
        private const val BOUNDARY = "receipts-boundary-8f3a91"

        /** Newest first, so callers can prune the tail. */
        const val MAX_BACKUPS = 10
    }

    /** Finds the app's backup folder, creating it if this is the first backup. */
    suspend fun ensureFolder(token: String): String = withContext(Dispatchers.IO) {
        val query = "mimeType='$FOLDER_MIME' and name='$FOLDER_NAME' and trashed=false"
        val existing = getJson<DriveFileList>(
            token,
            "$API/files?q=${enc(query)}&fields=files(id,name)&spaces=drive",
        ).files.firstOrNull()
        existing?.id ?: createFolder(token)
    }

    private suspend fun createFolder(token: String): String = withContext(Dispatchers.IO) {
        val body = """{"name":"$FOLDER_NAME","mimeType":"$FOLDER_MIME"}"""
        postJson<DriveFile>(token, "$API/files?fields=id", body).id
    }

    suspend fun listBackups(token: String, folderId: String): List<DriveFile> =
        withContext(Dispatchers.IO) {
            val query = "'$folderId' in parents and trashed=false"
            getJson<DriveFileList>(
                token,
                "$API/files?q=${enc(query)}" +
                    "&fields=files(id,name,size,createdTime)" +
                    "&orderBy=createdTime desc&pageSize=50",
            ).files
        }

    suspend fun upload(
        token: String,
        folderId: String,
        source: File,
        name: String,
    ): DriveFile = withContext(Dispatchers.IO) {
        val metadata = """{"name":"$name","parents":["$folderId"]}"""
        val connection = open("$UPLOAD/files?uploadType=multipart&fields=id,name,size,createdTime", token)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "multipart/related; boundary=$BOUNDARY")

        connection.outputStream.buffered().use { out ->
            out.write("--$BOUNDARY\r\n".toByteArray())
            out.write("Content-Type: application/json; charset=UTF-8\r\n\r\n".toByteArray())
            out.write(metadata.toByteArray())
            out.write("\r\n--$BOUNDARY\r\n".toByteArray())
            out.write("Content-Type: application/zip\r\n\r\n".toByteArray())
            source.inputStream().use { it.copyTo(out) }
            out.write("\r\n--$BOUNDARY--\r\n".toByteArray())
        }
        json.decodeFromString(readOrThrow(connection))
    }

    suspend fun download(token: String, fileId: String, target: File) =
        withContext(Dispatchers.IO) {
            val connection = open("$API/files/$fileId?alt=media", token)
            connection.requestMethod = "GET"
            if (connection.responseCode !in 200..299) {
                throw DriveException(errorBody(connection), connection.responseCode)
            }
            connection.inputStream.use { input ->
                target.outputStream().use { input.copyTo(it) }
            }
        }

    suspend fun delete(token: String, fileId: String) = withContext(Dispatchers.IO) {
        val connection = open("$API/files/$fileId", token)
        connection.requestMethod = "DELETE"
        if (connection.responseCode !in 200..299 && connection.responseCode != 404) {
            throw DriveException(errorBody(connection), connection.responseCode)
        }
        Unit
    }

    /**
     * Deletes everything past the newest [MAX_BACKUPS]. Failing to prune is not worth failing
     * the backup that has already been uploaded, so problems are swallowed here.
     */
    suspend fun prune(token: String, folderId: String): Int = withContext(Dispatchers.IO) {
        val stale = listBackups(token, folderId).drop(MAX_BACKUPS)
        var removed = 0
        for (file in stale) {
            runCatching { delete(token, file.id) }.onSuccess { removed++ }
        }
        removed
    }

    // ---------------------------------------------------------------- plumbing

    private fun enc(value: String) = URLEncoder.encode(value, "UTF-8")

    private fun open(url: String, token: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("Authorization", "Bearer $token")
            connectTimeout = 20_000
            readTimeout = 60_000
        }

    private inline fun <reified T> getJson(token: String, url: String): T {
        val connection = open(url, token)
        connection.requestMethod = "GET"
        return json.decodeFromString(readOrThrow(connection))
    }

    private inline fun <reified T> postJson(token: String, url: String, body: String): T {
        val connection = open(url, token)
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        connection.outputStream.use { it.write(body.toByteArray()) }
        return json.decodeFromString(readOrThrow(connection))
    }

    private fun readOrThrow(connection: HttpURLConnection): String {
        if (connection.responseCode !in 200..299) {
            throw DriveException(errorBody(connection), connection.responseCode)
        }
        return connection.inputStream.bufferedReader().use { it.readText() }
    }

    private fun errorBody(connection: HttpURLConnection): String {
        val body = runCatching {
            connection.errorStream?.bufferedReader()?.use { it.readText() }
        }.getOrNull()
        // Surface Google's own message: "insufficient scope" and "API not enabled" need
        // different fixes, and a bare status code hides which one happened.
        return "HTTP ${connection.responseCode}${if (body.isNullOrBlank()) "" else ": $body"}"
    }
}
