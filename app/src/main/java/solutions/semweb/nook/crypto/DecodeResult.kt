package solutions.semweb.nook.crypto

/**
 * Structured result decoding a message
 */
data class DecodeResult(
    val original: String,
    var decoded: String,
    val scheme: String,
    val success: Boolean,
    val notes: String,
    val encoding: String
) {
    companion object {
        fun success(
            original: String = "",
            decoded: String,
            scheme: String,
            notes: String = ""
        ): DecodeResult {
            return DecodeResult(
                original = original,
                decoded = decoded,
                scheme = scheme,
                encoding = "",
                success = true,
                notes = notes,
            )
        }

        fun error(
            original: String = "",
            decoded: String,
            scheme: String,
            notes: String = ""
        ): DecodeResult {
            return DecodeResult(
                original = original,
                decoded = decoded,
                scheme = scheme,
                encoding = "",
                success = false,
                notes = notes,
            )
        }
    }
}