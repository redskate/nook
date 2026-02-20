package solutions.semweb.nook.crypto

import android.content.Context
import android.os.Build
import androidx.annotation.RequiresApi
import solutions.semweb.nook.LogUtils
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * Utility for (de)coding Base(n), fo n in (32, 64, 128, 256, 512, 1024, 2048)
 */
object BaseXXXUtils {

    private val alphabetCache = ConcurrentHashMap<Int, String>()

    private val validationStats = ConcurrentHashMap<Int, ValidationResult>()


    private data class ValidationResult(
        val isValid: Boolean,
        val length: Int,
        val duplicates: Set<Char> = emptySet(),
        val missingChars: Int = 0,
        val generatedTime: Long = System.currentTimeMillis()
    )

    // Original alphabet > 1024  (but not yet 2048)
    // Never use #'" in alphabet!
    private const val alphabet =
        "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz!\$%&()*+,-./:;<=>?@[]^_`{|}~¡¢£¥§©ª«¬®°±²³µ¶·¸¹º»¼½¾¿ÀÁÂÃÄÅÆÇÈÉÊËÌÍÎÏÐÑÒÓÔÕÖ×ØÙÚÛÜÝÞßàáâãäåæçèéêëìíîïðñòóôõö÷øùúûüýþÿĀāĂăĄąĆćĈĉĊċČčĎďĐđĒēĔĕĖėĘęĚěĜĝĞğĠġĢģĤĥĦħĨĩĪīĬĭĮįİıĲĳĴĵĶķĸĹĺĻļĽľĿŀŁłŃńŅņŇňŉŊŋŌōŎŏŐőŒœŔŕŖŗŘřŚśŜŝŞşŠšŢţŤťŦŧŨũŪūŬŭŮůŰűŲųŴŵŶŷŸŹźŻżŽžſƀƁƂƃƄƅƆƇƈƉƊƋƌƍƎƏƐƑƒƓƔƕƖƗƘƙƚƛƜƝƞƟƠơƢƣƤƥƦƧƨƩƪƫƬƭƮƯưƱƲƳƴƵƶƷƸƹƺƻƼƽƾƿǀǁǂǃǄǅǆǇǈǉǊǋǌǍǎǏǐǑǒǓǔǕǖǗǘǙǚǛǜǝǞǟǠǡǢǣǤǥǦǧǨǩǪǫǬǭǮǯǰǱǲǳǴǵǶǷǸǹǺǻǼǽǾǿȀȁȂȃȄȅȆȇȈȉȊȋȌȍȎȏȐȑȒȓȔȕȖȗȘșȚțȜȝȞȟȠȡȢȣȤȥȦȧȨȩȪȫȬȭȮȯȰȱȲȳȴȵȶȷȸȹȺȻȼȽȾȿɀɁɂɃɄɅɆɇɈɉɊɋɌɍɎɏɐɑɒɓɔɕɖɗɘəɚɛɜɝɞɟɠɡɢɣɤɥɦɧɨɩɪɫɬɭɮɯɰɱɲɳɴɵɶɷɸɹɺɻɼɽɾɿʀʁʂʃʄʅʆʇʈʉʊʋʌʍʎʏʐʑʒʓʔʕʖʗʘʙʚʛʜʝʞʟʠʡʢʣʤʥʦʧʨʩʪʫʬʭЀЁЂЃЄЅІЇЈЉЊЋЌЍЎЏАБВГДЕЖЗИЙКЛМНОПРСТУФХЦЧШЩЪЫЬЭЮЯабвгдежзийклмнопрстуфхцчшщъыьэюяѐёђѓєѕіїјљњћќѝўџѠѡѢѣѤѥѦѧѨѩѪѫѬѭѮѯѰѱѲѳѴѵѶѷѸѹѺѻѼѽѾѿҀҁ҂҃҄ҊҋҌҍҎҏҐґҒғҔҕҖҗҘҙҚқҜҝҞҟҠҡҢңҤҥҦҧҨҩҪҫҬҭҮүҰұҲҳҴҵҶҷҸҹҺһҼҽҾҿӀӁӂӃӄӅӆӇӈӉӊӋӌӍӎӏӐӑӒӓӔӕӖӗӘәӚӛӜӝӞӟӠӡӢӣӤӥӦӧӨөӪӫӬӭӮӯӰӱӲӳӴӵӶӷӸӹӺӻӼӽӾӿԀԁԂԃԄԅԆԇԈԉԊԋԌԍԎԏԐԑԒԓԔԕԖԗԘԙԚԛԜԝԞԟԠԡԢԣԤԥԦԧԨԩԪԫԬԭԮԯᎠᎡᎢᎣᎤᎥᎦᎧᎨᎩᎪᎫᎬᎭᎮᎯᎰᎱᎲᎳᎴᎵᎶᎷᎸᎹᎺᎻᎼᎽᎾᎿᏀᏁᏂᏃᏄᏅᏆᏇᏈᏉᏊᏋᏌᏍᏎᏏᏐᏑᏒᏓᏔᏕᏖᏗᏘᏙᏚᏛᏜᏝᏞᏟᏠᏡᏢᏣᏤᏥᏦᏧᏨᏩᏪᏫᏬᏭᏮᏯᏰᏱᏲᏳᏴᏵ\u13F6\u13F7ᏸᏹᏺᏻᏼᏽḀḁḂḃḄḅḆḇḈḉḊḋḌḍḎḏḐḑḒḓḔḕḖḗḘḙḚḛḜḝḞḟḠḡḢḣḤḥḦḧḨḩḪḫḬḭḮḯḰḱḲḳḴḵḶḷḸḹḺḻḼḽḾḿṀṁṂṃṄṅṆṇṈṉṊṋṌṍṎṏṐṑṒṓṔṕṖṗṘṙṚṛṜṝṞṟṠṡṢṣṤṥṦṧṨṩṪṫṬṭṮṯṰṱṲṳṴṵṶṷṸṹṺṻṼṽṾṿẀẁẂẃẄẅẆẇẈẉẊẋẌẍẎẏẐẑẒẓẔẕẖẗẘẙẚẛẜẝẞẟẠạẢảẤấẦầẨẩẪẫẬậẮắẰằẲẳẴẵẶặẸẹẺẻẼẽẾếỀềỂểỄễỆệỈỉỊịỌọỎỏỐốỒồỔổỖỗỘộỚớỜờỞởỠỡỢợỤụỦủỨứỪừỬửỮữỰựỲỳỴỵỶỷỸỹỺỻỼỽỾỿἀἁἂἃἄἅἆἇἈἉἊἋἌἍἎἏἐἑἒἓἔἕἘἙἚἛἜἝἠἡἢἣἤἥἦἧἨἩἪἫἬἭἮἯἰἱἲἳἴἵἶἷἸἹἺἻἼἽἾἿὀὁὂὃὄὅὈὉὊὋὌὍὐὑὒὓὔὕὖὗὙὛὝὟὠὡὢὣὤὥὦὧὨὩὪὫὬὭὮὯὰάὲέὴήὶίὸόὺύὼώᾀᾁᾂᾃᾄᾅᾆᾇᾈᾉᾊᾋᾌᾍᾎᾏᾐᾑᾒᾓᾔᾕᾖᾗᾘᾙᾚᾛᾜᾝᾞᾟᾠᾡᾢᾣᾤᾥᾦᾧᾨᾩᾪᾫᾬᾭᾮᾯᾰᾱᾲᾳᾴᾶᾷᾸᾹᾺΆᾼ᾽ι῀῁ῂῃῄῆῇῈΈῊΉῌ῍῎῏ῐῑῒΐῖῗῘῙῚΊ῝῞῟ῠῡῢΰῤῥῦῧῨῩῪΎῬ῭΅ῲῳῴῶῷῸΌῺΏῼ"


    init {
        try {
            listOf(32, 64, 128, 256, 512, 1024).forEach { base ->
                getAlphabet(base)
            }
            LogUtils.d(null, "BaseXXXUtils", "✅ Alphabets preloaded: ${alphabetCache.keys}")
        } catch (e: Exception) {
            LogUtils.e(null, "BaseXXXUtils", "⚠️  Error preloading alphabets: ${e.message}")
            // all alphabets generated at APP start
            // do not throw exceptions
        }
    }


    private fun getAlphabet(base: Int, scramblePasswd: String = ""): String {
        // Create a unique key for combination base+password
        val cacheKey = if (scramblePasswd.isEmpty()) {
            base
        } else {
            // Use password hash as key part
            val passwordHash = scramblePasswd.hashCode()
            // Combine base and hash deterministically
            base * 31 + passwordHash
        }

        // Check it is already in cache
        alphabetCache[cacheKey]?.let { return it }

        var alphabet = when (base) {
            32  -> alphabet.take(32)
            64  -> alphabet.take(64)
            128 -> alphabet.take(128)
            256 -> alphabet.take(256)
            512 -> alphabet.take(512)
            1024 -> alphabet.take(1024)
            2048 -> alphabet.take(2048)
            else -> generateCustomalphabet(base) // TODO
        }

        // Validate alphabet
        val validation = validatealphabet(alphabet, base)

        if (!validation.isValid) {
            val errorMsg = "alphabet per base $base non valido: " +
                    "lunghezza=${validation.length} (attesa=$base), " +
                    "duplicati=${validation.duplicates.size}, " +
                    "mancanti=${validation.missingChars}"
            LogUtils.e(null, "BaseXXXUtils", errorMsg)
            throw IllegalArgumentException(errorMsg)
        }

        // se password allora scramble
        if (scramblePasswd.isNotEmpty())
            alphabet = scrambleAlphabet(alphabet, scramblePasswd)

        // Memorizza in cache con la chiave unica base+password
        alphabetCache[cacheKey] = alphabet
        validationStats[cacheKey] = validation

        LogUtils.d(null, "BaseXXXUtils",
            "📊 alphabet base $base${if (scramblePasswd.isNotEmpty()) " (scrambled)" else ""} " +
                    "generato e validato: lunghezza=${validation.length}, " +
                    "duplicati=${validation.duplicates.size}")

        return alphabet
    }

    init {
        try {
            listOf(32, 64, 128, 256, 512, 1024).forEach { base ->
                getAlphabet(base)
            }
            LogUtils.d(null, "BaseXXXUtils", "✅ Preloaded alphabets: ${alphabetCache.keys}")
        } catch (e: Exception) {
            LogUtils.e(null, "BaseXXXUtils", "⚠️  Error during alphabet preloading: ${e.message}")
            // Do not throw exceptions here
        }
    }


    fun scrambleAlphabet(baseAlphabet: String, password: String): String {
        val chars = baseAlphabet.toMutableList()

        // Calculate/Derive number from password (always positive)
        var number = 1
        for (char in password) {
            number = (number * 31 + char.code) and 0x7FFFFFFF
        }

        // Shuffle
        for (i in chars.indices.reversed()) { // From last one to first one
            // Partner between 0 e i
            number = (number * 1103515245 + 12345) and 0x7FFFFFFF
            val partner = number % (i + 1)
           // exchange
            val temp = chars[i]
            chars[i] = chars[partner]
            chars[partner] = temp
        }

        return chars.joinToString("")
    }

    /**
     * Generate personalized alphabet for non standard bases
     */
    private fun generateCustomalphabet(base: Int): String {
        LogUtils.w(null, "BaseXXXUtils", "⚠️  Generate personalized alphabet for base $base")

        val builder = StringBuilder()
        val usedChars = mutableSetOf<Char>()
        var codePoint = 33 // Inizia da '!'

        while (builder.length < base) {
            // avoid control and problematic chars
            if (isValidCodePoint(codePoint) && !usedChars.contains(codePoint.toChar())) {
                builder.appendCodePoint(codePoint)
                usedChars.add(codePoint.toChar())
            }

            codePoint++

            // Loop security
            if (codePoint > 0x10FFFF) {
                codePoint = 33 // Restart
                LogUtils.w(null, "BaseXXXUtils", "↩️  Restart alphabet generation for base $base")
            }

            // Timeout safety
            if (builder.length > base * 2) {
                LogUtils.e(null, "BaseXXXUtils", "⏱️  Timeout alphabet generation for base $base")
                break
            }
        }

        return builder.toString().take(base)
    }

    /**
     * Validate an alphabet
     */
    private fun validatealphabet(alphabet: String, expectedBase: Int): ValidationResult {
        val length = alphabet.length

        if (length != expectedBase) {
            return ValidationResult(
                isValid = false,
                length = length,
                missingChars = expectedBase - length
            )
        }

        // Check duplicates
        val charSet = mutableSetOf<Char>()
        val duplicates = mutableSetOf<Char>()

        for (char in alphabet) {
            if (!charSet.add(char)) {
                duplicates.add(char)
            }
        }

        return ValidationResult(
            isValid = duplicates.isEmpty() && length == expectedBase,
            length = length,
            duplicates = duplicates,
            missingChars = if (length < expectedBase) expectedBase - length else 0
        )
    }


    private fun isValidCodePoint(codePoint: Int): Boolean {
        return (!Character.isISOControl(codePoint) &&
                codePoint !in 0xD800..0xDFFF && // surrogate pairs
                codePoint != 0xFFFD && // substitution char
                Character.isDefined(codePoint) &&
                !Character.isWhitespace(codePoint) &&
                codePoint != 0x00AD) // Soft hyphen
    }

    /**
     * get alphabet for a certain base (public for debug)
     */
    fun getalphabet(base: Int): String {
        return getAlphabet(base)
    }

    /**
     * Validation Statistics (for debug)
     */
    fun getValidationStats(base: Int): String {
        val stats = validationStats[base]
        return if (stats != null) {
            "Base $base: length=${stats.length}, " +
                    "valid=${stats.isValid}, " +
                    "duplicates=${stats.duplicates.size}, " +
                    "generated=${stats.generatedTime}"
        } else {
            "Base $base: not validated"
        }
    }

    // ========== PUBLIC API ==========

    private const val DEFAULT_BASE = 1024

    /**
     * Code timestamp with specific base
     * We want to code trasmission timestamps using a base, starting from epoche 2026
     * and at every minutes (less values = shorter timestamp width)
     */
    fun encodeTimestampFixed(
        number: Long,
        fixedLength: Int,
        base: Int
    ): String {
        require(fixedLength > 0) { "A length must be positiv" }

        // Check number be representable
        val maxValue = Math.pow(base.toDouble(), fixedLength.toDouble()).toLong() - 1
        if (number > maxValue) {
            throw IllegalArgumentException(
                "Number $number too big for base $base with width $fixedLength. " +
                        "Max: $maxValue"
            )
        }

        val encoded = encodeTimestamp(number, fixedLength, base)

        // If shorter, add padding left
        return if (encoded.length < fixedLength) {
            val paddingChar = '0'  // Use '0' instead of space ' '
            paddingChar.toString().repeat(fixedLength - encoded.length) + encoded
        } else {
            encoded
        }
    }

    fun encodeTimestamp(
        number: Long,
        length: Int,
        base: Int
    ): String {
        val alphabet = getAlphabet(base)

        var temp = number
        val result = CharArray(length)

        for (position in length - 1 downTo 0) {
            result[position] = alphabet[(temp % base).toInt()]
            temp /= base
        }

        return String(result)
    }

    /**
     * Decode timestamp with a specific base
     */
    fun decodeTimestamp(
        encoded: String,
        base: Int = DEFAULT_BASE
    ): Long {
        val alphabet = getAlphabet(base)

        var result = 0L

        for (char in encoded) {
            val index = alphabet.indexOf(char)
            if (index == -1 || index >= base) {
                throw IllegalArgumentException("Char '$char' invalid for base $base")
            }
            result = result * base + index
        }

        return result
    }

    /**
     * Coding from byte array in BASEXXX
     */
    fun encode(
        bytes: ByteArray,
        base: Int = DEFAULT_BASE,
        password: String = ""
    ): String {
        val alphabet = getAlphabet(base,password)
        val bitsPerChar = getBitsPerChar(base)

        if (bytes.isEmpty()) return ""

        val output = StringBuilder()
        var buffer = 0
        var bitsInBuffer = 0

        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsInBuffer += 8

            while (bitsInBuffer >= bitsPerChar) {
                val value = (buffer ushr (bitsInBuffer - bitsPerChar)) and (base - 1)
                output.append(alphabet[value])
                bitsInBuffer -= bitsPerChar
                buffer = buffer and ((1 shl bitsInBuffer) - 1)
            }
        }

        // Manage remaining bits
        if (bitsInBuffer > 0) {
            val value = (buffer shl (bitsPerChar - bitsInBuffer)) and (base - 1)
            output.append(alphabet[value])
        }

        return output.toString()
    }

    /**
     * Decode string BASEXXX returning byte array
     */
    fun decodeBasic(
        encoded: String,
        base: Int,
        encodingPassword: String
    ): ByteArray {
        val alphabet = getAlphabet(base,encodingPassword)
        val bitsPerChar = getBitsPerChar(base)

        require(encoded.isNotEmpty()) { "Empty string" }

        val byteList = mutableListOf<Byte>()
        var buffer = 0
        var bitsInBuffer = 0

        for (char in encoded) {
            val value = alphabet.indexOf(char)
            require(value != -1) { "Invalid chat for base $base: '$char'" }

            buffer = (buffer shl bitsPerChar) or value
            bitsInBuffer += bitsPerChar

            while (bitsInBuffer >= 8) {
                val byte = (buffer ushr (bitsInBuffer - 8)) and 0xFF
                byteList.add(byte.toByte())
                bitsInBuffer -= 8
                buffer = buffer and ((1 shl bitsInBuffer) - 1)
            }
        }

        return byteList.toByteArray()
    }


    /**
     * Decode BASEXXX returning byte array without text validation
     * (raw version for internal use)
     */
    fun decodeToBytes(
        encoded: String,
        base: Int = DEFAULT_BASE
    ): ByteArray {
        val alphabet = getAlphabet(base)
        val bitsPerChar = getBitsPerChar(base)

        require(encoded.isNotEmpty()) { "Empty string" }

        val byteList = mutableListOf<Byte>()
        var buffer = 0
        var bitsInBuffer = 0

        for (char in encoded) {
            val value = alphabet.indexOf(char)
            require(value != -1) { "Invalid char for base $base: '$char'" }

            buffer = (buffer shl bitsPerChar) or value
            bitsInBuffer += bitsPerChar

            while (bitsInBuffer >= 8) {
                val byte = (buffer ushr (bitsInBuffer - 8)) and 0xFF
                byteList.add(byte.toByte())
                bitsInBuffer -= 8
                buffer = buffer and ((1 shl bitsInBuffer) - 1)
            }
        }

        return byteList.toByteArray()
    }


    /**
     * Decoding with error management and structured result
     */
    fun decode(
        encodedMessage: String,
        base: Int,
        encodingPassword: String = ""
    ): DecodeResult {
        return try {
            LogUtils.d(null, "BaseXXXUtils", "🛠️ Decoding Base$base...")

            val alphabet = getAlphabet(base,encodingPassword)

            // Cleanup the message
            val cleanBaseXXX = encodedMessage
                .replace("\\s".toRegex(), "")
                .filter { alphabet.contains(it) }

            if (cleanBaseXXX.isEmpty()) {
                return DecodeResult(
                    original = encodedMessage,
                    decoded = "ERROR: Decoding Base$base failed - empty data",
                    scheme = "n/d",
                    encoding = "BASE$base",
                    success = false,
                    notes = "BASE$base empty after cleaning",
                )
            }

            // Decoding
            val decodedBytes = decodeBasic(cleanBaseXXX, base, encodingPassword)
            if (decodedBytes.isEmpty()) {
                return DecodeResult(
                    original = encodedMessage,
                    decoded = "ERROR: Decoding BASE$base failed - invalid data",
                    scheme = "n/d",
                    encoding = "BASE$base",
                    success = false,
                    notes = "BASE$base invalid (empty)",
                )
            }

            val decodedText = String(decodedBytes, Charsets.UTF_8)

            // Verify readability
            if (!isTextReadable(decodedText)) {
                return DecodeResult(
                    original = encodedMessage,
                    decoded = "ERROR: Decoded text not readable",
                    scheme = "n/d",
                    encoding = "BASE$base",
                    success = false,
                    notes = "Output does not seem to be valid text",
                )
            }

            DecodeResult(
                original = encodedMessage,
                decoded = decodedText,
                scheme = "n/d",
                encoding = "BASE$base",
                success = true,
                notes = "Base$base decoded (${decodedBytes.size} byte)",
            )

        } catch (e: Exception) {
            LogUtils.e(null, "BaseXXXUtils", "❌ Errore decodifica Base$base", e)
            DecodeResult(
                original = encodedMessage,
                decoded = "ERROR BASE$base: ${e.message}",
                scheme = "n/d",
                encoding = "BASE$base",
                success = false,
                notes = "Decoding failed: ${e.javaClass.simpleName}",
            )
        }
    }

    /**
     * Helper
     */
    fun isLikelyBaseXXX(
        message: String,
        base: Int = DEFAULT_BASE
    ): Boolean {
        val alphabet = getAlphabet(base)

        val clean = message.replace("\\s".toRegex(), "")
        return clean.all { alphabet.contains(it) }
    }

    /**
     * Helper
     */
    fun isValidBaseXXX(
        str: String,
        base: Int = DEFAULT_BASE
    ): Boolean {
        return try {
            val alphabet = getAlphabet(base)
            str.isNotEmpty() && str.all { alphabet.contains(it) }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Gets bits per char depending on base
     */
    private fun getBitsPerChar(base: Int): Int {
        return when (base) {
            32 -> 5
            64 -> 6
            128 -> 7
            256 -> 8
            512 -> 9
            1024 -> 10
            2048 -> 11
            else -> {
                // For non standard bases, compute necessary bits
                var bits = 0
                var temp = base - 1
                while (temp > 0) {
                    temp = temp shr 1
                    bits++
                }
                bits
            }
        }
    }

    /**
     * Verify whether decoded text is readable
     */
    private fun isTextReadable(text: String): Boolean {
        if (text.isEmpty()) return false

        val printableChars = text.count {
            it in ' '..'~' ||
                    it == '\n' || it == '\r' || it == '\t' ||
                    it.code > 127
        }

        return (printableChars.toFloat() / text.length) > 0.7f
    }

    // ========== SECOND TIMESTAMP UTILS ==========

    /**
     * Utility for (de)coding timestamps with 1 second granularity and
     * epoch (2026-01-01)
     *
     * NOTE: The timestamp are ALWAYS in UTC. To order messages, use always UTC.
     * To visualize them, convert timestamp at locale timezone with the helper methods.
     */
    object SecondTimestamp {
        // Epoch: 2026.01.01 00:00:00 UTC
        private val EPOCH_2026 = 1735689600000L

        // Granularity: 1 unit = 1 second = 1000 milliseconds
        private const val GRANULARITY_MS = 1000L

        /**
         * Coding timestamp (millisecondi) to string BaseXXX
         * @param timestamp Timestamp in milliseconds (in UTC)
         * @param length Length of coded string
         * @param base Base to use
         * @return coded string
         */
        fun encodeLong(
            timestamp: Long,
            length: Int = 5,
            base: Int = 128
        ): String {
            // Secondi from epoch 2026
            val seconds = (timestamp - EPOCH_2026) / GRANULARITY_MS

            require(seconds >= 0) {
                "Timestamp $timestamp should be after epoch 2026 ($EPOCH_2026)"
            }

            // Verifica che sia rappresentabile
            val maxValue = Math.pow(base.toDouble(), length.toDouble()).toLong() - 1
            require(seconds <= maxValue) {
                "Timestamp too big for $length caratteri Base$base. " +
                        "Seconds: $seconds, Max: $maxValue"
            }

            return encodeTimestampFixed(seconds, length, base)
        }

        /**
         * Decoding string to timestamp (milliseconds UTC)
         * @param encoded coded string
         * @param length expected string width
         * @param base Base used for decoding
         * @return Timestamp in milliseconds UTC
         */
        fun decodeToLong(
            encoded: String,
            length: Int = 5,
            base: Int = 128
        ): Long {
            require(encoded.length == length) {
                "The code for timestamp should be $length chars long, got: ${encoded.length}"
            }

            val seconds = decodeTimestamp(encoded, base)
            return EPOCH_2026 + (seconds * GRANULARITY_MS)
        }


        /**
         * Helper - Verify a timestamp is codifiable
         */
        fun isEncodable(
            timestamp: Long,
            length: Int = 5,
            base: Int = 128
        ): Boolean {
            val seconds = (timestamp - EPOCH_2026) / GRANULARITY_MS
            val maxValue = Math.pow(base.toDouble(), length.toDouble()).toLong() - 1
            return seconds >= 0 && seconds <= maxValue
        }


        fun getMaxRepresentableYears(
            length: Int = 5,
            base: Int = 128
        ): Double {
            val maxSeconds = Math.pow(base.toDouble(), length.toDouble()) - 1
            return maxSeconds / (365.25 * 24 * 3600)
        }


        fun getMaxRepresentableDate(
            length: Int = 5,
            base: Int = 128
        ): java.util.Date {
            val maxSeconds = Math.pow(base.toDouble(), length.toDouble()).toLong() - 1
            val maxTimestamp = EPOCH_2026 + (maxSeconds * GRANULARITY_MS)
            return java.util.Date(maxTimestamp)
        }


        fun calculateRequiredLength(
            base: Int,
            desiredYears: Int = 100
        ): Int {
            val yearsInSeconds = desiredYears.toLong() * 365 * 24 * 3600
            // compute necessary chars: ceil(log_base(yearsInSeconds + 1))
            var length = 0
            var value = yearsInSeconds
            while (value > 0) {
                length++
                value /= base
            }

            return maxOf(length, 1)  // Almeno 1 carattere
        }

        /**
         * Computes the necessary width (length) to codify a timestamp in seconds from epoch 2026,
         * guaranteeing at least 100 years covering
         */
        fun getTimestampWidthForSeconds(base: Int): Int {
            // Seconds in 100 years
            val secondsIn100Years = 100L * 365 * 24 * 3600

            // Computes necesary chars (witch): ceil(log_base(secondsIn100Years + 1))
            var width = 0
            var value = secondsIn100Years
            while (value > 0) {
                width++
                value /= base
            }

            return maxOf(width, 1)  // At least 1 char
        }


        // ========== HELPER FOR TIMEZONE ==========
        /**
         * Get offset for timezone in hours
         * @param context Context Android (optional)
         * @return Offset hours (e.g. +1 for Italy, -5 for EST)
         */
        fun getCurrentTimezoneOffsetHours(context: Context? = null): Int {
            return try {
                val timeZone = TimeZone.getDefault()
                val offsetMillis = timeZone.getOffset(System.currentTimeMillis())
                offsetMillis / (1000 * 60 * 60)
            } catch (e: Exception) {
                LogUtils.e(null, "SecondTimestamp", "Error getting timezone", e)
                0 // Default UTC
            }
        }

        /**
         * Converts UTC timestamp to local timestamp (Long)
         * @param utcTimestamp Timestamp in UTC
         * @param context Context Android (optional)
         * @return Local Timestamp
         */
        fun convertUtcToLocal(
            utcTimestamp: Long,
            context: Context? = null
        ): Long {
            return try {
                val timeZone = TimeZone.getDefault()
                val offsetMillis = timeZone.getOffset(utcTimestamp)
                utcTimestamp + offsetMillis
            } catch (e: Exception) {
                LogUtils.e(null, "SecondTimestamp", "Error converting timezone", e)
                utcTimestamp // Fallback to UTC
            }
        }

        /**
         * Converts timestamp locale to UTC
         * @param localTimestamp Timestamp nel timezone locale
         * @param context Context Android (opzionale)
         * @return Timestamp in UTC
         */
        fun convertLocalToUtc(
            localTimestamp: Long,
            context: Context? = null
        ): Long {
            return try {
                val timeZone = TimeZone.getDefault()
                val offsetMillis = timeZone.getOffset(localTimestamp)
                localTimestamp - offsetMillis
            } catch (e: Exception) {
                LogUtils.e(null, "SecondTimestamp", "Errore conversione timezone", e)
                localTimestamp // Fallback
            }
        }

        /**
         * Decode and converts locally
         * @param encoded coded string
         * @param context Context Android (optional)
         * @return Timestamp in locale timezone
         */
        fun decodeToLocal(
            encoded: String,
            context: Context? = null,
            length: Int = 5,
            base: Int = 128
        ): Long {
            val utcTimestamp = decodeToLong(encoded, length, base)
            return convertUtcToLocal(utcTimestamp, context)
        }

        /**
         * Decode and format in locale timezone
         */
        @RequiresApi(Build.VERSION_CODES.O)
        fun decodeAndFormatLocal(
            encoded: String,
            context: Context? = null,
            length: Int = 5,
            base: Int = 128,
            pattern: String = "dd.MM.yyyy HH:mm"
        ): String {
            val localTimestamp = decodeToLocal(encoded, context, length, base)

            val formatter = DateTimeFormatter.ofPattern(pattern)
            val zoneId = ZoneId.systemDefault()
            val dateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(localTimestamp),
                zoneId
            )

            return dateTime.format(formatter)
        }

        /**
         * Format UTC timestamp to locals timezone
         */
        @RequiresApi(Build.VERSION_CODES.O)
        fun formatUtcToLocal(
            utcTimestamp: Long,
            pattern: String = "dd.MM.yyyy HH:mm"
        ): String {
            val localTimestamp = convertUtcToLocal(utcTimestamp)

            val formatter = DateTimeFormatter.ofPattern(pattern)
            val zoneId = ZoneId.systemDefault()
            val dateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(localTimestamp),
                zoneId
            )

            return dateTime.format(formatter)
        }

        /**
         * Test helper
         */
        fun test() {
            println("🧪 Test SecondTimestamp")
            println("Epoch: ${java.util.Date(EPOCH_2026)}")
            println("Granularity: ${GRANULARITY_MS}ms")

            val now = System.currentTimeMillis()
            println("\nCurrent Timestamp: $now (${java.util.Date(now)})")

            val encoded = encodeLong(now)
            println("Coded: '$encoded'")

            val decoded = decodeToLong(encoded)
            println("Decoded $decoded (${java.util.Date(decoded)})")

            val diff = Math.abs(now - decoded)
            println("Difference: ${diff}ms (should be < ${GRANULARITY_MS}ms)")

            val maxYears = getMaxRepresentableYears()
            println("\nMax years representable: ${String.format("%.1f", maxYears)} years")

            val maxDate = getMaxRepresentableDate()
            println("Max representable date: $maxDate")

            // Length Formula Test
            println("\nTest length/width computation:")
            println("Base128 for 100 years: ${calculateRequiredLength(128, 100)} chars")
            println("Base128 for 1000 years: ${calculateRequiredLength(128, 1000)} chars")

            println("\nTest completed ${if (diff < GRANULARITY_MS) "✅" else "❌"}")
        }
    }

    /**
     * Class for structured return:
     */
    data class DecodeResult(
        val original: String,
        val decoded: String,
        val scheme: String,
        val encoding: String,
        val success: Boolean,
        val notes: String = ""
    )
}