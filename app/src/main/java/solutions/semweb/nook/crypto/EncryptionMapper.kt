package solutions.semweb.nook.crypto

/**
 * HELPER map with setting and utilities for encryption and coding
 */
object EncryptionMapper {

    //////////////////////////////////////////////////////////
    // ENCODING (BaseXXX)
    //////////////////////////////////////////////////////////

    const val MINENCODINGPWLEN = 6
    // Valori di encoding
    const val ENCODING_BASE32 = "base32"
    const val ENCODING_BASE64 = "base64"
    const val ENCODING_BASE128 = "base128"
    const val ENCODING_BASE256 = "base256"
    const val ENCODING_BASE512 = "base512"
    const val ENCODING_BASE1024 = "base1024"
    const val ENCODING_BASE2048 = "base2048"
    const val ENCODING_PLAIN = "text"  // "text" instead of "plain"
    const val ENCODING_AUTO = "auto"

    var DEFAULT_ENCRYPTION_SCHEME = ""  // Empty = global use
    var DEFAULT_ENCODING = ""  // Empty = global use
    var ENCRYPTION_SCHEME_TEXT = "text"
    var ENCRYPTION_SCHEME_NONE = "none"
    val ENCRYPTION_SCHEME_SISA = "sisa"

    // Menu encoding
    val encodingSchemes = arrayOf("Base32", "Base64", "Base128", "Base256", "Base512", "Base1024")
    val encodingValues = arrayOf(
        ENCODING_BASE32,   // 0
        ENCODING_BASE64,   // 1
        ENCODING_BASE128,  // 2
        ENCODING_BASE256,  // 3
        ENCODING_BASE512,  // 4
        ENCODING_BASE1024, // 5
    )

    //////////////////////////////////////////////////////////
    // ENCRYPTION
    //////////////////////////////////////////////////////////

    const val ENCRYPTION_SISA = "sisa"
    const val ENCRYPTION_TEXT = "text"
    const val ENCRYPTION_AUTO = "auto"

    // Menu cifratura (solo SiSa e Text)
    val encryptionSchemes = arrayOf("SiSa","Text") // TODO: internationalize?
    val encryptionValues = arrayOf(
        ENCRYPTION_SISA, // 1
        ENCRYPTION_TEXT  // 0
    )

    const val SISA_ENCR_PREFIX = "#0"

    //NB: Only encryption scheme is shown, encoding is set but not shown in message

    private val schemaToPrefix = mapOf(
        ENCRYPTION_SISA to "#e${SISA_ENCR_PREFIX}",
    )

    private val prefixToSchema = mapOf(
        "#e${SISA_ENCR_PREFIX}" to ENCRYPTION_SISA,
    )

    /**
     * helper to extract a base from an encoding scheme - returns 128 if text
     */
    fun extractEncodingBase(input: String): Int {
        val regex = """(?i)Base(\d+)""".toRegex()
        val match = regex.find(input)

        return match?.groupValues?.get(1)?.toIntOrNull()
            ?: 128 // standard default
    }

    fun extractShortForEncoding(enc: String): String {
        if (enc=="")
            return ""
        else
            return "b"+extractEncodingBase(enc)
    }

    fun extractShortForEncrScheme(sch: String?): String {
        return if (sch==this.ENCRYPTION_SCHEME_SISA)
            "sisa"
        else
            ""
    }

    fun getPrefixForScheme(scheme: String?): String? {
        return schemaToPrefix[scheme]
    }


}