package solutions.semweb.nook.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.text.format.DateFormat
import androidx.core.content.FileProvider
import com.google.gson.Gson
import solutions.semweb.nook.ChatMessage
import solutions.semweb.nook.Constants
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.MainActivity
import solutions.semweb.nook.R
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.CipherOutputStream
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Task used to export a chat in HTML+JSON, zip it and optionally encrypt it
 */
class ChatExportTask(
    private val context: Context,
    private val myName: String,
    private val phoneNumber: String,
    private val contactName: String,
    private val isYChat: Boolean,
    private val yUserId: String?,
    private val password: String?, // null = no encryption, !null = encrypt with password
    private val onExportComplete: (Boolean, String?) -> Unit
) {

    fun execute() {
        Thread {
            try {
                LogUtils.d(context, "ExportChatTask", "🔧 Start exporting chat for: $phoneNumber")

                val chatManager = ChatManager(context)
                val messages = chatManager.getAllMessagesForConversation(phoneNumber)

                if (messages.isEmpty()) {
                    MainActivity.showToast(context.getString(R.string.export_no_messages))
                    onExportComplete(false, null)
                    return@Thread
                }

                val tempDir = createTempDirectory()
                if (tempDir == null) {
                    MainActivity.showToast(context.getString(R.string.chatesport_errorfolder))
                    onExportComplete(false, null)
                    return@Thread
                }

                val jsonFile = createJsonFile(tempDir, messages)
                val htmlFile = createHtmlFile(tempDir, messages, context)

                if (jsonFile == null || htmlFile == null) {
                    MainActivity.showToast(context.getString(R.string.chatexport_nofilescreated)) //"Errore: impossibile creare files")
                    onExportComplete(false, null)
                    return@Thread
                }

                val baseName = getSafeFileName(contactName, phoneNumber)
                val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
                val should_encrypt = (password != null && password.length >= 16)
                // zip folder
                val zipFileName = if (should_encrypt) {
                    "${baseName}_${timestamp}" // pure for nook crypt
                } else {
                    "${baseName}_${timestamp}.nook.zip" // plain not crypted
                }

                val zipFile = createZipFile(tempDir, zipFileName)
                if (zipFile == null) {
                    MainActivity.showToast(context.getString(R.string.chatexport_nozipscreated))
                    onExportComplete(false, null)
                    return@Thread
                }

                // If pw set, encrypt the zip and name it .nook
                val finalFile = if (should_encrypt) {
                    val encryptedFileName = "${baseName}_${timestamp}.nook"
                    encryptFile(zipFile, encryptedFileName, password)
                } else {
                    zipFile
                }

                deleteDirectory(tempDir)

                if (finalFile != null && finalFile.exists()) {
                    LogUtils.d(context, "ExportChatTask", "✅ Chat exporting completed with success: ${finalFile.absolutePath}")

                    // Opzionale: condividi il file
                    shareExportedFile(finalFile)

                    onExportComplete(true, finalFile.absolutePath)
                } else {

                    MainActivity.showToast(context.getString(R.string.file_not_found))
                    onExportComplete(false, null)
                }

            } catch (e: Exception) {
                LogUtils.e(context, "ExportChatTask", "❌ Error during exporting chat", e)
                MainActivity.showToast("${context.getString(R.string.export_failed)}: ${e.localizedMessage}")
                onExportComplete(false, e.localizedMessage)
            }
        }.start()
    }


    private fun createTempDirectory(): File? {
        return try {
            val tempDir = File(context.cacheDir, "export_${System.currentTimeMillis()}")
            if (tempDir.exists()) {
                tempDir.deleteRecursively()
            }
            if (tempDir.mkdirs()) {
                tempDir
            } else {
                null
            }
        } catch (e: Exception) {
            LogUtils.e(context, "ExportChatTask", "❌ Error on creation of tmp folder", e)
            null
        }
    }


    private fun createJsonFile(directory: File, messages: List<ChatMessage>): File? {
        return try {
            val exportData = ChatExportData(
                myName = myName,
                phoneNumber = phoneNumber,
                contactName = contactName,
                isYChat = isYChat,
                yUserId = yUserId,
                exportTimestamp = System.currentTimeMillis(),
                messages = messages,
                version = 2
            )

            val gson = Gson()
            val json = gson.toJson(exportData)

            val file = File(directory, "chat.json")
            FileOutputStream(file).use { fos ->
                fos.write(json.toByteArray(StandardCharsets.UTF_8))
            }

            file
        } catch (e: Exception) {
            LogUtils.e(context, "ExportChatTask", "❌ Error while creation of JSON file", e)
            null
        }
    }


    private fun createHtmlFile(directory: File, messages: List<ChatMessage>, context: Context): File? {
        return try {
            LogUtils.d(context, "ExportChatTask", "📄 HTML file creation")

            val htmlContent = buildHtmlContent(messages, context)
            val file = File(directory, "chat.html")

            FileOutputStream(file).use { fos ->
                fos.write(htmlContent.toByteArray(StandardCharsets.UTF_8))
            }

            file
        } catch (e: Exception) {
            LogUtils.e(context, "ExportChatTask", "❌ Error while HTML creation", e)
            null
        }
    }



    private fun buildHtmlContent(messages: List<ChatMessage>, context: Context): String {
        val sb = StringBuilder()
        val arrow_out = "▶"
        val arrow_in = "◀"
        val nookchatexport = context.getString(R.string.chatexport_html_title)

        // Ottieni il formatter per data e ora in base alla locale corrente
        val dateFormat = DateFormat.getDateFormat(context)  // Formato data
        val timeFormat = DateFormat.getTimeFormat(context)  // Formato ora

        sb.append("""
    <!DOCTYPE html>
    <html lang="it">
    <head>
        <meta charset="UTF-8">
        <meta name="viewport" content="width=device-width, initial-scale=1.0">
        <title>$nookchatexport: $myName - $contactName</title>
        <style>
            body {
                font-family: 'Segoe UI', Tahoma, Geneva, Verdana, sans-serif;
                max-width: 800px;
                margin: 0 auto;
                padding: 20px;
                background-color: #f5f5f5;
                color: #333;
            }
            .header {
                background: linear-gradient(135deg, #095508, #063a06);
                color: white;
                padding: 20px;
                border-radius: 10px;
                margin-bottom: 30px;
                box-shadow: 0 4px 6px rgba(0,0,0,0.1);
            }
            .message {
                margin-bottom: 15px;
                padding: 15px;
                border-radius: 10px;
                position: relative;
                animation: fadeIn 0.5s ease-in;
            }
            .outgoing {
                background-color: #DCF8C6;
                margin-left: 20%;
                border-left: 5px solid #4CAF50;
            }
            .incoming {
                background-color: #FFFFFF;
                margin-right: 20%;
                border-left: 5px solid #2196F3;
            }
            .timestamp {
                font-size: 0.8em;
                color: #666;
                margin-bottom: 5px;
            }
            .message-text {
                font-size: 1.1em;
                line-height: 1.5;
            } 
            .warning {
                background-color: #FFF3CD;
                border-left: 5px solid #FFC107;
                padding: 10px;
                margin: 10px 0;
                border-radius: 5px;
            }
            .y-message {
                background-color: #E8F5E9;
                border-left: 5px solid #8BC34A;
            }
            @keyframes fadeIn {
                from { opacity: 0; transform: translateY(10px); }
                to { opacity: 1; transform: translateY(0); }
            }
        </style>
    </head>
    <body>
        <div class="header">
            <h1 style="margin: 0 0 10px 0;">$nookchatexport</h1>
            <p><strong>${context.getString(R.string.appowner)} APP:</strong> $myName ${arrow_out}</p>
            <p><strong>${context.getString(R.string.interlocutor)}:</strong> $contactName ${arrow_in}</p>
            <p><strong>${context.getString(R.string.date_of_esport)}:</strong><br> ${formatDateTime(dateFormat, timeFormat, Date())}</p>
            <p><strong>${context.getString(R.string.number_or_messages)}:</strong> ${messages.size}</p>
        </div>
        
        <div class="warning">
            ⚠️ <strong>${context.getString(R.string.export_security_warning)} 
            
        </div>
        
        <div id="messages">
""".trimIndent())

        // Order messages using timestamp
        val sortedMessages = messages.sortedBy { it.timestamp }

        sortedMessages.forEach { message ->
            val date = formatDateTime(dateFormat, timeFormat, Date(message.timestamp))
            val messageClass = when {
                message.isOutgoing -> "message outgoing"
                !message.isDecoded -> "message warning"
                else -> "message incoming"
            }

            val icon = when {
                message.isOutgoing -> arrow_out
                message.isYMessage -> "🟢"
                !message.isDecoded -> "⚠️"
                else -> arrow_in
            }

            val typeBadge = when {
                message.isYMessage -> " <span style='background:#8BC34A;color:white;padding:2px 6px;border-radius:3px;font-size:0.8em;'>Y</span>"
                !message.isDecoded -> " <span style='background:#FFC107;color:white;padding:2px 6px;border-radius:3px;font-size:0.8em;'>Plain</span>"
                else -> ""
            }

            sb.append("""
            <div class="$messageClass">
                <div class="timestamp">
                    $icon $date $typeBadge
                </div>
                <div class="message-text">
                    ${escapeHtml(message.text)}
                </div>
            </div>
        """.trimIndent())
        }

        sb.append("""
        </div>
        <div style="margin-top: 30px; padding: 20px; background-color: #E3F2FD; border-radius: 10px; text-align: center;">
            <p>${context.getString(R.string.exported_by)} <strong>${context.getString(R.string.nook_messenger)}</strong></p>
        </div>
    </body>
    </html>
    """.trimIndent())

        return sb.toString()
    }


    private fun formatDateTime(dateFormat: java.text.DateFormat, timeFormat: java.text.DateFormat, date: Date): String {
        return "${dateFormat.format(date)} ${timeFormat.format(date)}"
    }

    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .replace("\n", "<br>")
    }


    private fun createZipFile(sourceDir: File, zipFileName: String): File? {
        return try {
            val downloadsDir = getDownloadsDirectory()
            if (downloadsDir == null) return null

            val zipFile = File(downloadsDir, zipFileName)

            FileOutputStream(zipFile).use { fos ->
                ZipOutputStream(BufferedOutputStream(fos)).use { zos ->
                    sourceDir.listFiles()?.forEach { file ->
                        if (file.isFile) {
                            val zipEntry = ZipEntry(file.name)
                            zos.putNextEntry(zipEntry)

                            FileInputStream(file).use { fis ->
                                fis.copyTo(zos)
                            }

                            zos.closeEntry()
                        }
                    }
                }
            }

            zipFile
        } catch (e: Exception) {
            LogUtils.e(context, "ExportChatTask", "❌ Error ZIP creation", e)
            null
        }
    }

    private fun encryptFile(inputFile: File, outputFileName: String, password: String): File? {
        return try {
            val downloadsDir = getDownloadsDirectory()
            if (downloadsDir == null) return null

            val outputFile = File(downloadsDir, outputFileName)

            // Generate key from password
            val keySpec = SecretKeySpec(
                MessageDigest.getInstance("SHA-256").digest(password.toByteArray(StandardCharsets.UTF_8)),
                "AES"
            )

            // Encrypt with AES-256 CBC
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            val iv = ByteArray(16)
            SecureRandom().nextBytes(iv)

            cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(iv))

            FileOutputStream(outputFile).use { fos ->
                // Scrivi IV all'inizio del file
                fos.write(iv)

                // Write encrypted data
                CipherOutputStream(fos, cipher).use { cos ->
                    FileInputStream(inputFile).use { fis ->
                        fis.copyTo(cos)
                    }
                }
            }

            // Delete original ZIP file
            inputFile.delete()

            outputFile
        } catch (e: Exception) {
            LogUtils.e(context, "ExportChatTask", "❌ Error while encrypting file", e)
            null
        }
    }


    private fun getDownloadsDirectory(): File? {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            } else {
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            }
        } catch (e: Exception) {
            LogUtils.e(context, "ExportChatTask", "❌ Error getting Downloads folder", e)
            null
        }
    }

    private fun getSafeFileName(contactName: String, phoneNumber: String): String {
        return if (contactName.isNotBlank() && contactName != phoneNumber) {
            contactName
                .replace("[^a-zA-Z0-9À-ÿ_\\- ]".toRegex(), "-")
                .replace(" ", "-")
                .trim()
                .take(50)
        } else {
            phoneNumber.replace("[^0-9+]".toRegex(), "")
        }
    }

    private fun deleteDirectory(dir: File): Boolean {
        return dir.deleteRecursively()
    }

    private fun shareExportedFile(file: File) {
        try {
            val fileUri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file
                )
            } else {
                Uri.fromFile(file)
            }

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = if (file.name.endsWith(".nook")) {
                    "application/octet-stream"
                } else {
                    "application/zip"
                }
                putExtra(Intent.EXTRA_STREAM, fileUri)
                putExtra(Intent.EXTRA_SUBJECT, "NooK Chat Export: $contactName")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            context.startActivity(Intent.createChooser(shareIntent, context.getString(R.string.share_exported_chatfile)))

        } catch (e: Exception) {
            LogUtils.e(context, "ExportChatTask", "❌ Error sharing chat", e)
        }
    }



}

/**
 * JSON export data class
 */
data class ChatExportData(
    val myName: String,
    val phoneNumber: String,
    val contactName: String,
    val isYChat: Boolean,
    val yUserId: String?,
    val exportTimestamp: Long,
    val messages: List<ChatMessage>,
    val version: Int = 2,
    val appVersion: String = Constants.VERSION
)