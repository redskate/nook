package solutions.semweb.nook.chat

import android.content.Context
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import solutions.semweb.nook.ChatConversation
import solutions.semweb.nook.ChatMessage
import solutions.semweb.nook.Constants
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.MainActivity
import solutions.semweb.nook.R
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.abs

class ChatImportTask(
    private val context: Context,
    private val fileUri: Uri,
    private val filePath: String,
    private val password: String?, // null = no encryption, !null = decrypt with password
    private val onImportComplete: (Boolean, String?, Int?) -> Unit // (success, errorMessage, messageCount)
) {

    fun execute() {
        Thread {
            try {
                LogUtils.d(context, "ImportChatTask", "🔧 Start importing car for: $filePath")

                val inputFile = File(filePath)
                if (!inputFile.exists()) {
                    MainActivity.showToast(context.getString(R.string.file_not_found))
                    onImportComplete(false, context.getString(R.string.file_not_found), null)
                    return@Thread
                }

                val fileName = inputFile.name
                val isEncrypted = fileName.endsWith(".nook")
                val isZip = fileName.endsWith(".zip")

                if (!isEncrypted && !isZip) {
                    MainActivity.showToast(context.getString(R.string.import_invalid_file_format))
                    onImportComplete(false, context.getString(R.string.import_invalid_file_format), null)
                    return@Thread
                }

                if (isEncrypted && (password == null || password.length < 16)) {
                    MainActivity.showToast(context.getString(R.string.import_password_required))
                    onImportComplete(false, context.getString(R.string.import_password_required), null)
                    return@Thread
                }

                val tempDir = createTempDirectory()
                if (tempDir == null) {
                    MainActivity.showToast(context.getString(R.string.import_error_temp_folder))
                    onImportComplete(false, context.getString(R.string.import_error_temp_folder), null)
                    return@Thread
                }

                val extractedSuccess = if (isEncrypted) {
                    // File criptato: leggi, decripta in memoria e decomprimi
                    val inputStream = context.contentResolver.openInputStream(fileUri)
                    if (inputStream == null) {
                        LogUtils.e(context, "ImportChatTask", "❌ Impossibile aprire input stream")
                        false
                    } else {
                        decryptAndDecompressFromStream(inputStream, password!!, tempDir)
                    }
                } else {
                    val inputStream = context.contentResolver.openInputStream(fileUri)
                    if (inputStream == null) {
                        LogUtils.e(context, "ImportChatTask", "❌ Impossibile aprire input stream")
                        false
                    } else {
                        decompressZipFromStream(inputStream, tempDir)
                    }
                }

                if (!extractedSuccess) {
                    MainActivity.showToast(context.getString(R.string.import_decompression_failed))
                    onImportComplete(false, context.getString(R.string.import_decompression_failed), null)
                    deleteDirectory(tempDir)
                    return@Thread
                }

                val jsonFile = findJsonFile(tempDir)
                if (jsonFile == null) {
                    MainActivity.showToast(context.getString(R.string.import_no_json_found))
                    onImportComplete(false, context.getString(R.string.import_no_json_found), null)
                    deleteDirectory(tempDir)
                    return@Thread
                }

                val exportData = readAndParseJson(jsonFile)
                if (exportData == null) {
                    MainActivity.showToast(context.getString(R.string.import_invalid_json))
                    onImportComplete(false, context.getString(R.string.import_invalid_json), null)
                    deleteDirectory(tempDir)
                    return@Thread
                }

                val importedCount = importMessages(exportData)

                deleteDirectory(tempDir)

                if (importedCount > 0) {
                    LogUtils.d(context, "ImportChatTask", "✅ Importazione completata: $importedCount messaggi importati")
                    MainActivity.showToast(context.getString(R.string.import_success, importedCount))
                    onImportComplete(true, null, importedCount)
                } else {
                    MainActivity.showToast(context.getString(R.string.import_no_messages_imported))
                    onImportComplete(false, context.getString(R.string.import_no_messages_imported), 0)
                }

            } catch (e: Exception) {
                LogUtils.e(context, "ImportChatTask", "❌ Errore durante l'importazione", e)
                MainActivity.showToast("${context.getString(R.string.import_failed)}: ${e.localizedMessage}")
                onImportComplete(false, e.localizedMessage, null)
            }
        }.start()
    }

    private fun decryptAndDecompressFromStream(
        inputStream: InputStream,
        password: String,
        outputDir: File
    ): Boolean {
        return try {
            LogUtils.d(context, "ImportChatTask", "🔓 Decrypting from file in main memory")

            // Generate keys from password (same as export method)
            val keySpec = SecretKeySpec(
                MessageDigest.getInstance("SHA-256").digest(password.toByteArray(StandardCharsets.UTF_8)),
                "AES"
            )

            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")

            val iv = ByteArray(16)
            inputStream.read(iv)

            cipher.init(Cipher.DECRYPT_MODE, keySpec, IvParameterSpec(iv))

            val cis = CipherInputStream(inputStream, cipher)
            val success = decompressZipFromStream(cis, outputDir)

            cis.close()
            success

        } catch (e: Exception) {
            LogUtils.e(context, "ImportChatTask", "❌ Errore decriptazione da stream", e)
            false
        } finally {
            inputStream.close()
        }
    }

    private fun decompressZipFromStream(inputStream: InputStream, outputDir: File): Boolean {
        return try {
            LogUtils.d(context, "ImportChatTask", "📦 Decompressing ZIP from stream")

            ZipInputStream(inputStream).use { zis ->
                var entry: ZipEntry?
                val buffer = ByteArray(8192)

                while (zis.nextEntry.also { entry = it } != null) {
                    val entryName = entry!!.name
                    val outputFile = File(outputDir, entryName)

                    if (entryName.contains("/")) {
                        outputFile.parentFile?.mkdirs()
                    }

                    FileOutputStream(outputFile).use { fos ->
                        var length: Int
                        while (zis.read(buffer).also { length = it } > 0) {
                            fos.write(buffer, 0, length)
                        }
                    }

                    zis.closeEntry()
                }
            }

            LogUtils.d(context, "ImportChatTask", "✅ File unzipped")
            true
        } catch (e: Exception) {
            LogUtils.e(context, "ImportChatTask", "❌ Error unzipping from stream", e)
            false
        }
    }


    private fun createTempDirectory(): File? {
        return try {
            val tempDir = File(context.cacheDir, "import_${System.currentTimeMillis()}")
            if (tempDir.exists()) {
                tempDir.deleteRecursively()
            }
            if (tempDir.mkdirs()) {
                tempDir
            } else {
                null
            }
        } catch (e: Exception) {
            LogUtils.e(context, "ImportChatTask", "❌ Error creation tmp folder", e)
            null
        }
    }

    private fun findJsonFile(directory: File): File? {
        return try {
            val files = directory.listFiles { file ->
                file.isFile && (file.name.equals("chat.json", ignoreCase = true) ||
                        file.name.endsWith(".json", ignoreCase = true))
            }

            files?.firstOrNull()
        } catch (e: Exception) {
            LogUtils.e(context, "ImportChatTask", "❌ Errore searching for JSON file in export folder", e)
            null
        }
    }

    private fun readAndParseJson(jsonFile: File): ChatExportData? {
        return try {
            val jsonContent = jsonFile.readText(StandardCharsets.UTF_8)

            val gson = Gson()
            val exportData = gson.fromJson(jsonContent, ChatExportData::class.java)

            // Verifica versione
            if (exportData.version < 2) {
                LogUtils.w(context, "ImportChatTask", "⚠️ Old file version: ${exportData.version}")
                MainActivity.showToast(context.getString(R.string.import_old_version_warning))
            }

            LogUtils.d(context, "ImportChatTask",
                "📊 JSON parsed: ${exportData.messages.size} messages, " +
                        "Chat: ${exportData.contactName} (${exportData.phoneNumber}), " +
                        "Version: ${exportData.version}")

            exportData
        } catch (e: JsonSyntaxException) {
            LogUtils.e(context, "ImportChatTask", "❌ JSON invalid", e)
            null
        } catch (e: Exception) {
            LogUtils.e(context, "ImportChatTask", "❌ Error reading JSON", e)
            null
        }
    }

    private fun importMessages(exportData: ChatExportData): Int {
        return try {
            val chatManager = ChatManager(context)
            val messages = exportData.messages

            LogUtils.d(context, "ImportChatTask",
                "💾 Importing ${messages.size} messages for chat: ${exportData.phoneNumber}")

            var conversation = chatManager.getConversation(exportData.phoneNumber)

            //This should not happen, since you need a conversation to import a chat
            if (conversation == null) {
                LogUtils.d(context, "ImportChatTask", "➕ Create new conversazione: ${exportData.phoneNumber}")

                chatManager.createNormalChat(
                    phoneNumber = exportData.phoneNumber,
                    contactName = exportData.contactName,
                    encoding = Constants.DEFAULT_encoding
                )
                conversation = chatManager.getConversation(exportData.phoneNumber)
                if (conversation != null)
                {
                    // Dummy message to init conversation
                    val dummyMessage = ChatMessage(
                        text = context.getString(R.string.imported_chat),
                        sender = exportData.phoneNumber,
                        timestamp = System.currentTimeMillis(),
                        isDecoded = true,
                        isOutgoing = false,
                        isYMessage = exportData.isYChat
                    )

                    chatManager.addMessageInChat( dummyMessage, conversation )

                    conversation = chatManager.getConversation(exportData.phoneNumber)

                    if (conversation != null && exportData.contactName.isNotEmpty()) {
                        chatManager.updateChatName(exportData.phoneNumber, exportData.contactName)
                    }
                }
            }

            // Import all messages
            var importedCount = 0
            val existingMessages = chatManager.getAllMessagesForConversation(exportData.phoneNumber)

            for (message in messages) {
                val isDuplicate = existingMessages.any { existing ->
                    existing.text == message.text &&
                            abs(existing.timestamp - message.timestamp) < 1000
                }

                if (!isDuplicate) {
                    val importMessage = message.copy(
                        id = chatManager.generateMessageId()
                    )
                    if (conversation != null)
                    {
                        chatManager.addMessageInChat( importMessage, conversation, saveConversation = false)
                        importedCount++
                    }
                    // Log every 50 messages
                    if (importedCount % 50 == 0) {
                        LogUtils.d(context, "ImportChatTask", "📥 Imported $importedCount messages...")
                    }
                }
            }

            chatManager.saveConversations(listOf(conversation) as List<ChatConversation>)

            LogUtils.d(context, "ImportChatTask",
                "✅ Import completed: $importedCount new messages (${messages.size - importedCount} duplicates ignored)")

            importedCount

        } catch (e: Exception) {
            LogUtils.e(context, "ImportChatTask", "❌ Error importing messages", e)
            0
        }
    }

    private fun deleteDirectory(dir: File): Boolean {
        return try {
            dir.deleteRecursively()
        } catch (e: Exception) {
            LogUtils.e(context, "ImportChatTask", "❌ Errore eliminazione cartella", e)
            false
        }
    }



}

/**
 * Interface to start import
 */
object ChatImporter {

    fun requiresPassword(fileName: String): Boolean {
        return fileName.endsWith(".nook", ignoreCase = true)
    }

    fun isPasswordValidForFile(fileName: String, password: String?): Boolean {
        return if (requiresPassword(fileName)) {
            // File .nook richiede password di almeno 16 caratteri
            password != null && password.length >= 16
        } else {
            // File .zip non richiede password
            true
        }
    }

    fun getPasswordErrorMessage(fileName: String, password: String?, context: Context): String? {
        return if (requiresPassword(fileName) && (password == null || password.length < 16)) {
            context.getString(R.string.password_for_import_must_have_16)
        } else {
            null
        }
    }

    fun importChat(
        context: Context,
        fileUri: Uri,
        filePath: String,
        password: String?,
        callback: (Boolean, String?, Int?) -> Unit
    ) {
        val fileName = File(filePath).name

        // Validazione pre-import
        if (requiresPassword(fileName) && !isPasswordValidForFile(fileName, password)) {
            callback(false, getPasswordErrorMessage(fileName, password, context), null)
            return
        }

        ChatImportTask(context, fileUri, filePath, password, callback).execute()
    }
}