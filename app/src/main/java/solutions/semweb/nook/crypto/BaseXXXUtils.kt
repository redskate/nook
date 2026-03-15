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
 * Utility for (de)coding Base(n), for n in (32, 64, 256)
 * NB: Due to alignement/padding problems using other bases
 * we concentrate on just 32, 64, 256 scrambling deterministically
 * the alphabet up to n (32, 64, 256)
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
            listOf(32, 64, 256).forEach { base ->
                getAlphabet(base)
            }
            LogUtils.d(null, "BaseXXXUtils", "✅ Alphabets preloaded: ${alphabetCache.keys}")
        } catch (e: Exception) {
            LogUtils.e(null, "BaseXXXUtils", "⚠️  Error preloading alphabets: ${e.message}")
        }
    }

    private fun validateAlphabet(alphabet: String, expectedBase: Int): ValidationResult {
        val length = alphabet.length

        if (length != expectedBase) {
            return ValidationResult(
                isValid = false,
                length = length,
                missingChars = expectedBase - length
            )
        }

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

    private fun getAlphabet(base: Int, scramblePasswd: String = ""): String {
        val cacheKey = if (scramblePasswd.isEmpty()) {
            base
        } else {
            val passwordHash = getDeterministicHash(scramblePasswd)
            base * 31 + passwordHash
        }

        alphabetCache[cacheKey]?.let { return it }

        val masterAlphabet = alphabet
        val selectedChars = mutableSetOf<Char>()
        val result = StringBuilder()

        val seed = if (scramblePasswd.isNotEmpty()) {
            getDeterministicHash(scramblePasswd)
        } else {
            0
        }

        val indices = generateDeterministicIndices(masterAlphabet.length, base * 2, seed)

        for (index in indices) {
            if (result.length >= base) break
            val char = masterAlphabet[index]
            if (!selectedChars.contains(char)) {
                selectedChars.add(char)
                result.append(char)
            }
        }

        if (result.length < base) {
            LogUtils.w(null, "BaseXXXUtils", "⚠️ Could only select ${result.length}/$base unique chars, filling remaining")
            fillRemainingChars(result, selectedChars, base)
        }

        val baseAlphabet = result.toString()

        val validation = validateAlphabet(baseAlphabet, base)
        if (!validation.isValid) {
            val errorMsg = "Alphabet for base $base is invalid: " +
                    "length=${validation.length} (expected=$base), " +
                    "duplicates=${validation.duplicates.size}"
            LogUtils.e(null, "BaseXXXUtils", errorMsg)
            throw IllegalArgumentException(errorMsg)
        }

        val finalAlphabet = if (scramblePasswd.isNotEmpty()) {
            scrambleAlphabet(baseAlphabet, scramblePasswd)
        } else {
            baseAlphabet
        }

        val normalizedAlphabet = normalizeAlphabet(finalAlphabet)

        alphabetCache[cacheKey] = normalizedAlphabet
        validationStats[cacheKey] = validation

        LogUtils.d(null, "BaseXXXUtils",
            "📊 Alphabet base $base${if (scramblePasswd.isNotEmpty()) " (scrambled)" else ""} " +
                    "generated and validated: length=${validation.length}")

        return normalizedAlphabet
    }

    private fun generateDeterministicIndices(maxSize: Int, count: Int, seed: Int): List<Int> {
        val indices = mutableListOf<Int>()
        var state = if (seed == 0) 12345 else seed

        for (i in 0 until count) {
            state = (state * 1103515245 + 12345) and 0x7FFFFFFF
            val index = state % maxSize
            indices.add(index)
        }

        return indices
    }

    private fun fillRemainingChars(result: StringBuilder, selectedChars: Set<Char>, base: Int) {
        val fallback = alphabet.takeLast(base)
        for (char in fallback) {
            if (result.length >= base) break
            if (!selectedChars.contains(char)) {
                result.append(char)
            }
        }

        var ascii = 33
        while (result.length < base) {
            val char = ascii.toChar()
            if (!selectedChars.contains(char) && !Character.isISOControl(ascii)) {
                result.append(char)
            }
            ascii++
        }
    }

    fun scrambleAlphabet(baseAlphabet: String, password: String): String {
        val chars = baseAlphabet.toMutableList()

        var number = 1
        for (char in password) {
            number = (number * 31 + char.code) and 0x7FFFFFFF
        }

        for (i in chars.indices.reversed()) {
            number = (number * 1103515245 + 12345) and 0x7FFFFFFF
            val partner = number % (i + 1)
            val temp = chars[i]
            chars[i] = chars[partner]
            chars[partner] = temp
        }

        return chars.joinToString("")
    }

    private fun getDeterministicHash(password: String): Int {
        var hash = 0
        for (char in password) {
            hash = hash * 31 + char.code
        }
        return hash and 0x7FFFFFFF
    }

    private fun normalizeAlphabet(alphabet: String): String {
        return java.text.Normalizer.normalize(alphabet, java.text.Normalizer.Form.NFC)
    }

    private fun generateCustomAlphabet(base: Int): String {
        LogUtils.w(null, "BaseXXXUtils", "⚠️ Generating custom alphabet for base $base")

        val builder = StringBuilder()
        val usedChars = mutableSetOf<Char>()
        var codePoint = 33

        while (builder.length < base) {
            if (isValidCodePoint(codePoint) && !usedChars.contains(codePoint.toChar())) {
                builder.appendCodePoint(codePoint)
                usedChars.add(codePoint.toChar())
            }

            codePoint++

            if (codePoint > 0x10FFFF) {
                codePoint = 33
                LogUtils.w(null, "BaseXXXUtils", "↩️ Restarting alphabet generation for base $base")
            }

            if (builder.length > base * 2) {
                LogUtils.e(null, "BaseXXXUtils", "⏱️ Timeout generating alphabet for base $base")
                break
            }
        }

        return builder.toString().take(base)
    }

    private fun isValidCodePoint(codePoint: Int): Boolean {
        return (!Character.isISOControl(codePoint) &&
                codePoint !in 0xD800..0xDFFF &&
                codePoint != 0xFFFD &&
                Character.isDefined(codePoint) &&
                !Character.isWhitespace(codePoint) &&
                codePoint != 0x00AD)
    }

    // ========== PUBLIC ENCODING/DECODING API ==========
    // SOLID IMPLEMENTATIONS FOR BASES 32, 64, 256

    private const val DEFAULT_BASE = 256

    /**
     * ENCODE: Convert byte array to string using specified base and password
     */
    fun encode(bytes: ByteArray, base: Int = DEFAULT_BASE, password: String = ""): String {
        val alphabet = getAlphabet(base, password)

        return when (base) {
            256 -> encodeBase256(bytes, alphabet)
            64 -> encodeBase64(bytes, alphabet)
            32 -> encodeBase32(bytes, alphabet)
            else -> encodeGeneric(bytes, base, alphabet)
        }
    }

    /**
     * DECODE: Convert string to byte array using specified base and password
     */
    fun decodeToBytes(encoded: String, base: Int = DEFAULT_BASE, password: String = ""): ByteArray {
        val alphabet = getAlphabet(base, password)

        return when (base) {
            256 -> decodeBase256(encoded, alphabet)
            64 -> decodeBase64(encoded, alphabet)
            32 -> decodeBase32(encoded, alphabet)
            else -> decodeGeneric(encoded, base, alphabet)
        }
    }

    // ========== BASE256 - PERFECT 1:1 MAPPING ==========

    private fun encodeBase256(bytes: ByteArray, alphabet: String): String {
        require(alphabet.length == 256) { "Base256 alphabet must have 256 characters" }

        val result = CharArray(bytes.size)
        for (i in bytes.indices) {
            val byteValue = bytes[i].toInt() and 0xFF
            result[i] = alphabet[byteValue]
        }
        return String(result)
    }

    private fun decodeBase256(encoded: String, alphabet: String): ByteArray {
        require(alphabet.length == 256) { "Base256 alphabet must have 256 characters" }

        val reverseMap = alphabet.mapIndexed { index, char -> char to index }.toMap()

        val result = ByteArray(encoded.length)
        for (i in encoded.indices) {
            val char = encoded[i]
            val byteValue = reverseMap[char]
            require(byteValue != null) { "Invalid character for base256: $char" }
            result[i] = byteValue.toByte()
        }
        return result
    }

    // ========== BASE64 - 6 BITS PER CHAR ==========

    private fun encodeBase64(bytes: ByteArray, alphabet: String): String {
        require(alphabet.length == 64) { "Base64 alphabet must have 64 characters" }

        val bitsPerChar = 6
        val output = StringBuilder()
        var buffer = 0
        var bitsInBuffer = 0

        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsInBuffer += 8

            while (bitsInBuffer >= bitsPerChar) {
                val value = (buffer ushr (bitsInBuffer - bitsPerChar)) and 0x3F
                output.append(alphabet[value])
                bitsInBuffer -= bitsPerChar
                buffer = buffer and ((1 shl bitsInBuffer) - 1)
            }
        }

        if (bitsInBuffer > 0) {
            val value = (buffer shl (bitsPerChar - bitsInBuffer)) and 0x3F
            output.append(alphabet[value])
        }

        return output.toString()
    }

    private fun decodeBase64(encoded: String, alphabet: String): ByteArray {
        require(alphabet.length == 64) { "Base64 alphabet must have 64 characters" }

        val reverseMap = alphabet.mapIndexed { index, char -> char to index }.toMap()
        val bitsPerChar = 6

        val result = mutableListOf<Byte>()
        var buffer = 0
        var bitsInBuffer = 0

        for (char in encoded) {
            val value = reverseMap[char]
            require(value != null) { "Invalid character for base64: $char" }

            buffer = (buffer shl bitsPerChar) or value
            bitsInBuffer += bitsPerChar

            while (bitsInBuffer >= 8) {
                val byte = (buffer ushr (bitsInBuffer - 8)) and 0xFF
                result.add(byte.toByte())
                bitsInBuffer -= 8
                buffer = buffer and ((1 shl bitsInBuffer) - 1)
            }
        }

        return result.toByteArray()
    }

    // ========== BASE32 - 5 BITS PER CHAR ==========

    private fun encodeBase32(bytes: ByteArray, alphabet: String): String {
        require(alphabet.length == 32) { "Base32 alphabet must have 32 characters" }

        val bitsPerChar = 5
        val output = StringBuilder()
        var buffer = 0
        var bitsInBuffer = 0

        for (byte in bytes) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsInBuffer += 8

            while (bitsInBuffer >= bitsPerChar) {
                val value = (buffer ushr (bitsInBuffer - bitsPerChar)) and 0x1F
                output.append(alphabet[value])
                bitsInBuffer -= bitsPerChar
                buffer = buffer and ((1 shl bitsInBuffer) - 1)
            }
        }

        if (bitsInBuffer > 0) {
            val value = (buffer shl (bitsPerChar - bitsInBuffer)) and 0x1F
            output.append(alphabet[value])
        }

        return output.toString()
    }

    private fun decodeBase32(encoded: String, alphabet: String): ByteArray {
        require(alphabet.length == 32) { "Base32 alphabet must have 32 characters" }

        val reverseMap = alphabet.mapIndexed { index, char -> char to index }.toMap()
        val bitsPerChar = 5

        val result = mutableListOf<Byte>()
        var buffer = 0
        var bitsInBuffer = 0

        for (char in encoded) {
            val value = reverseMap[char]
            require(value != null) { "Invalid character for base32: $char" }

            buffer = (buffer shl bitsPerChar) or value
            bitsInBuffer += bitsPerChar

            while (bitsInBuffer >= 8) {
                val byte = (buffer ushr (bitsInBuffer - 8)) and 0xFF
                result.add(byte.toByte())
                bitsInBuffer -= 8
                buffer = buffer and ((1 shl bitsInBuffer) - 1)
            }
        }

        return result.toByteArray()
    }

    // ========== GENERIC ENCODE/DECODE FOR OTHER BASES ==========
    // Keep for backward compatibility

    private fun encodeGeneric(bytes: ByteArray, base: Int, alphabet: String): String {
        val bitsPerChar = getBitsPerChar(base)
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

        if (bitsInBuffer > 0) {
            val value = (buffer shl (bitsPerChar - bitsInBuffer)) and (base - 1)
            output.append(alphabet[value])
        }

        return output.toString()
    }

    private fun decodeGeneric(encoded: String, base: Int, alphabet: String): ByteArray {
        val bitsPerChar = getBitsPerChar(base)
        val reverseMap = alphabet.mapIndexed { index, char -> char to index }.toMap()

        val result = mutableListOf<Byte>()
        var buffer = 0
        var bitsInBuffer = 0

        for (char in encoded) {
            val value = reverseMap[char]
            require(value != null) { "Invalid character for base$base: $char" }

            buffer = (buffer shl bitsPerChar) or value
            bitsInBuffer += bitsPerChar

            while (bitsInBuffer >= 8) {
                val byte = (buffer ushr (bitsInBuffer - 8)) and 0xFF
                result.add(byte.toByte())
                bitsInBuffer -= 8
                buffer = buffer and ((1 shl bitsInBuffer) - 1)
            }
        }

        return result.toByteArray()
    }

    // ========== LEGACY METHODS (Keep for compatibility) ==========

    fun decodeBasic(encoded: String, base: Int, encodingPassword: String): ByteArray {
        return decodeToBytes(encoded, base, encodingPassword)
    }

    fun decode(
        encodedMessage: String,
        base: Int,
        encodingPassword: String = ""
    ): DecodeResult {
        return try {
            LogUtils.d(null, "BaseXXXUtils", "🛠️ Decoding Base$base...")

            val alphabet = getAlphabet(base, encodingPassword)

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

    fun isLikelyBaseXXX(message: String, base: Int = DEFAULT_BASE): Boolean {
        val alphabet = getAlphabet(base)
        val clean = message.replace("\\s".toRegex(), "")
        return clean.all { alphabet.contains(it) }
    }

    fun isValidBaseXXX(str: String, base: Int = DEFAULT_BASE): Boolean {
        return try {
            val alphabet = getAlphabet(base)
            str.isNotEmpty() && str.all { alphabet.contains(it) }
        } catch (e: Exception) {
            false
        }
    }

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

    private fun isTextReadable(text: String): Boolean {
        if (text.isEmpty()) return false

        val printableChars = text.count {
            it in ' '..'~' ||
                    it == '\n' || it == '\r' || it == '\t' ||
                    it.code > 127
        }

        return (printableChars.toFloat() / text.length) > 0.7f
    }

    // ========== TIMESTAMP FUNCTIONS ==========
    // All timestamp functions remain exactly as they were

    fun encodeTimestampFixed(number: Long, fixedLength: Int, base: Int): String {
        require(fixedLength > 0) { "A length must be positiv" }

        val maxValue = Math.pow(base.toDouble(), fixedLength.toDouble()).toLong() - 1
        if (number > maxValue) {
            throw IllegalArgumentException(
                "Number $number too big for base $base with width $fixedLength. " +
                        "Max: $maxValue"
            )
        }

        val encoded = encodeTimestamp(number, fixedLength, base)

        return if (encoded.length < fixedLength) {
            val paddingChar = '0'
            paddingChar.toString().repeat(fixedLength - encoded.length) + encoded
        } else {
            encoded
        }
    }

    fun encodeTimestamp(number: Long, length: Int, base: Int): String {
        val alphabet = getAlphabet(base)
        var temp = number
        val result = CharArray(length)

        for (position in length - 1 downTo 0) {
            result[position] = alphabet[(temp % base).toInt()]
            temp /= base
        }

        return String(result)
    }

    fun decodeTimestamp(encoded: String, base: Int = DEFAULT_BASE): Long {
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

    // ========== SECOND TIMESTAMP UTILS ==========
    // Keep the entire SecondTimestamp object as is
    object SecondTimestamp {
        private val EPOCH_2026 = 1735689600000L
        private const val GRANULARITY_MS = 1000L

        fun encodeLong(timestamp: Long, length: Int = 5, base: Int = 128): String {
            val seconds = (timestamp - EPOCH_2026) / GRANULARITY_MS
            require(seconds >= 0) {
                "Timestamp $timestamp should be after epoch 2026 ($EPOCH_2026)"
            }
            val maxValue = Math.pow(base.toDouble(), length.toDouble()).toLong() - 1
            require(seconds <= maxValue) {
                "Timestamp too big for $length caratteri Base$base. " +
                        "Seconds: $seconds, Max: $maxValue"
            }
            return encodeTimestampFixed(seconds, length, base)
        }

        fun decodeToLong(encoded: String, length: Int = 5, base: Int = 128): Long {
            require(encoded.length == length) {
                "Timestamp should be $length chars, got: ${encoded.length}"
            }

            LogUtils.d(null, "TimestampDebug", "Decoding timestamp: '$encoded'")

            val alphabet = getAlphabet(base)
            encoded.forEachIndexed { index, char ->
                val pos = alphabet.indexOf(char)
                LogUtils.d(null, "TimestampDebug", "  char[$index] = '$char' (U+${char.code.toString(16)}) -> position $pos")
            }

            val seconds = decodeTimestamp(encoded, base)
            LogUtils.d(null, "TimestampDebug", "  -> seconds: $seconds")

            return EPOCH_2026 + (seconds * GRANULARITY_MS)
        }

        fun isEncodable(timestamp: Long, length: Int = 5, base: Int = 128): Boolean {
            val seconds = (timestamp - EPOCH_2026) / GRANULARITY_MS
            val maxValue = Math.pow(base.toDouble(), length.toDouble()).toLong() - 1
            return seconds >= 0 && seconds <= maxValue
        }

        fun getMaxRepresentableYears(length: Int = 5, base: Int = 128): Double {
            val maxSeconds = Math.pow(base.toDouble(), length.toDouble()) - 1
            return maxSeconds / (365.25 * 24 * 3600)
        }

        fun getMaxRepresentableDate(length: Int = 5, base: Int = 128): java.util.Date {
            val maxSeconds = Math.pow(base.toDouble(), length.toDouble()).toLong() - 1
            val maxTimestamp = EPOCH_2026 + (maxSeconds * GRANULARITY_MS)
            return java.util.Date(maxTimestamp)
        }

        fun calculateRequiredLength(base: Int, desiredYears: Int = 100): Int {
            val yearsInSeconds = desiredYears.toLong() * 365 * 24 * 3600
            var length = 0
            var value = yearsInSeconds
            while (value > 0) {
                length++
                value /= base
            }
            return maxOf(length, 1)
        }

        fun getTimestampWidthForSeconds(base: Int): Int {
            val secondsIn100Years = 100L * 365 * 24 * 3600
            var width = 0
            var value = secondsIn100Years
            while (value > 0) {
                width++
                value /= base
            }
            return maxOf(width, 1)
        }

        fun getCurrentTimezoneOffsetHours(context: Context? = null): Int {
            return try {
                val timeZone = TimeZone.getDefault()
                val offsetMillis = timeZone.getOffset(System.currentTimeMillis())
                offsetMillis / (1000 * 60 * 60)
            } catch (e: Exception) {
                LogUtils.e(null, "SecondTimestamp", "Error getting timezone", e)
                0
            }
        }

        fun convertUtcToLocal(utcTimestamp: Long, context: Context? = null): Long {
            return try {
                val timeZone = TimeZone.getDefault()
                val offsetMillis = timeZone.getOffset(utcTimestamp)
                utcTimestamp + offsetMillis
            } catch (e: Exception) {
                LogUtils.e(null, "SecondTimestamp", "Error converting timezone", e)
                utcTimestamp
            }
        }

        fun convertLocalToUtc(localTimestamp: Long, context: Context? = null): Long {
            return try {
                val timeZone = TimeZone.getDefault()
                val offsetMillis = timeZone.getOffset(localTimestamp)
                localTimestamp - offsetMillis
            } catch (e: Exception) {
                LogUtils.e(null, "SecondTimestamp", "Errore conversione timezone", e)
                localTimestamp
            }
        }

        fun decodeToLocal(encoded: String, context: Context? = null, length: Int = 5, base: Int = 128): Long {
            val utcTimestamp = decodeToLong(encoded, length, base)
            return convertUtcToLocal(utcTimestamp, context)
        }

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

        @RequiresApi(Build.VERSION_CODES.O)
        fun formatUtcToLocal(utcTimestamp: Long, pattern: String = "dd.MM.yyyy HH:mm"): String {
            val localTimestamp = convertUtcToLocal(utcTimestamp)
            val formatter = DateTimeFormatter.ofPattern(pattern)
            val zoneId = ZoneId.systemDefault()
            val dateTime = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(localTimestamp),
                zoneId
            )
            return dateTime.format(formatter)
        }

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

            println("\nTest length/width computation:")
            println("Base128 for 100 years: ${calculateRequiredLength(128, 100)} chars")
            println("Base128 for 1000 years: ${calculateRequiredLength(128, 1000)} chars")

            println("\nTest completed ${if (diff < GRANULARITY_MS) "✅" else "❌"}")
        }
    }

    data class DecodeResult(
        val original: String,
        val decoded: String,
        val scheme: String,
        val encoding: String,
        val success: Boolean,
        val notes: String = ""
    )
}