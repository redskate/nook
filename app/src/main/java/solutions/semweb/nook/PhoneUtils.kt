package solutions.semweb.nook

object PhoneUtils {
    fun normalizePhoneNumber(number: String): String {
        return number
            .replace("[^0-9+]".toRegex(), "")
            .let {
                if (it.startsWith("00")) "+${it.substring(2)}"
                else it
            }
    }
}