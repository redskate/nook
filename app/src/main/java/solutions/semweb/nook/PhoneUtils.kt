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

    /**
     * Checks if a string represents a valid phone number after normalization
     *
     * Valid formats:
     * - International: +12293449505 (with country code)
     * - Local: 2293449505 (without country code)
     *
     * Rules:
     * - At least 6 digits total for private sms numbers
     * - If starts with '+', must have at least 7 digits total (country code + at least 6 digits)
     * - If doesn't start with '+', must be all digits
     * - Maximum reasonable length: 15 digits (international standard)
     */
    fun isPhoneNumber(input: String): Boolean {
        val normalized = normalizePhoneNumber(input)

        // Empty check
        if (normalized.isEmpty()) return false

        // Check for invalid characters (should be handled by normalize, but double-check)
        if (!normalized.matches(Regex("^[0-9+]+$"))) return false

        return when {
            // International format: starts with '+'
            normalized.startsWith("+") -> {
                val digits = normalized.substring(1) // Remove the '+'
                digits.length in 7..14 && // Max 14 digits after + (total 15 with +)
                        digits.all { it.isDigit() }
            }
            // Local format: all digits
            else -> {
                normalized.length in 6..14 && // Max 14 digits for local numbers
                        normalized.all { it.isDigit() }
            }
        }
    }

    /**
     * More lenient version that only checks minimum requirements
     * Useful for validation where you don't want to be too strict
     */
    fun isPhoneNumberLenient(input: String): Boolean {
        val normalized = normalizePhoneNumber(input)

        if (normalized.isEmpty()) return false

        val digitsOnly = normalized.replace("+", "")
        return digitsOnly.length >= 6 && digitsOnly.all { it.isDigit() }
    }

    /**
     * Gets the country code from an international number
     * Returns null if not an international number or invalid
     */
    fun extractCountryCode(phoneNumber: String): String? {
        val normalized = normalizePhoneNumber(phoneNumber)

        if (!normalized.startsWith("+")) return null

        // Country code is between '+' and the next 1-3 digits
        val match = Regex("^\\+(\\d{1,3})").find(normalized)
        return match?.groupValues?.get(1)
    }

    /**
     * Gets the national number (without country code)
     * For international numbers, removes the country code
     * For local numbers, returns as-is
     */
    fun getNationalNumber(phoneNumber: String): String {
        val normalized = normalizePhoneNumber(phoneNumber)

        if (!normalized.startsWith("+")) return normalized

        // Remove country code (1-3 digits after +)
        val match = Regex("^\\+\\d{1,3}(\\d+)$").find(normalized)
        return match?.groupValues?.get(1) ?: normalized
    }

    /**
     * Returns a user-friendly validation message
     */
    fun getValidationMessage(phoneNumber: String): String {
        val normalized = normalizePhoneNumber(phoneNumber)

        when {
            normalized.isEmpty() -> "Phone number cannot be empty"
            !normalized.matches(Regex("^[0-9+]+$")) -> "Phone number contains invalid characters"
            normalized.startsWith("+") -> {
                val digits = normalized.substring(1)
                when {
                    digits.length < 7 -> "International number must have at least 6 digits after country code"
                    digits.length > 14 -> "International number is too long (max 14 digits after country code)"
                    !digits.all { it.isDigit() } -> "International number must contain only digits after +"
                    else -> "Valid international number"
                }
            }
            else -> {
                when {
                    normalized.length < 6 -> "Phone number must have at least 6 digits"
                    normalized.length > 14 -> "Phone number is too long (max 14 digits)"
                    !normalized.all { it.isDigit() } -> "Phone number must contain only digits"
                    else -> "Valid local number"
                }
            }
        }
        return ""
    }

    /**
     * Strict validation for trusted contacts
     * Requires either international format or valid local number
     */
    fun isValidForTrustedContact(phoneNumber: String): Boolean {
        val normalized = normalizePhoneNumber(phoneNumber)

        // Must have at least 6 digits
        val digitsOnly = normalized.replace("+", "")
        if (digitsOnly.length < 6) return false

        // If international, must have reasonable country code
        if (normalized.startsWith("+")) {
            val countryCode = extractCountryCode(normalized)
            if (countryCode == null || countryCode.length !in 1..3) return false

            // Check that the remaining part (national number) has at least 6 digits
            val nationalNumber = getNationalNumber(normalized)
            return nationalNumber.length >= 6 && nationalNumber.all { it.isDigit() }
        }

        // Local number: just digits
        return normalized.all { it.isDigit() }
    }
}