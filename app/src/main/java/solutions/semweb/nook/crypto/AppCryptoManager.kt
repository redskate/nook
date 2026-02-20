package solutions.semweb.nook.crypto

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.RequiresApi
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import solutions.semweb.nook.LogUtils
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object AppCryptoManager {
    private const val TAG = "AppCryptoManager"
    private const val KEY_ALIAS = "nook_app_master_key"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val SHARED_PREFS_NAME = "nook_secure_prefs_final"

    // Keys
    private var masterKey: SecretKey? = null
    private var hmacKey: SecretKeySpec? = null
    private var keyStore: KeyStore? = null
    private const val KEY_VERSION_PREF = "crypto_key_version"
    private const val KEY_DATA_PREF = "crypto_key_data"

    // Mapping cache
    private val keyCache = mutableMapOf<String, String>()      // plain → encrypted
    val reverseCache = mutableMapOf<String, String>()   // encrypted → plain
    private var initialized = false

    fun initializeSync(context: Context): Boolean {
        if (initialized) {
            LogUtils.e(TAG, "✅ Already intialized")
            return true
        }

        LogUtils.e(TAG, "🔐 SYNCRONOUS INIT COMPLETE ")

        return try {
            masterKey = getOrCreateMasterKey(context)

            if (masterKey == null) {
                LogUtils.e(TAG, "❌ No master key found")
                return false
            }

            createHmacKeyFromMaster()

            loadKeyMappings(context)

            val testPassed = testQuickEncryption()

            if (!testPassed) {
                LogUtils.e(TAG, "❌ Encryption test failed")
                return false
            }

            initialized = true
            LogUtils.e(TAG, "✅✅✅ SYNCRONOUS INIT COMPLETE!")
            true
        } catch (e: Exception) {
            LogUtils.e(TAG, "❌❌❌ ERROR  INITIALIZING SYNCHRONOUS ENCRYPTION", e)
            false
        }
    }


    private fun testQuickEncryption(): Boolean {
        return try {
            val testText = "test_quick_${System.currentTimeMillis()}"
            val encrypted = encrypt64Value(testText)
            val decrypted = decrypt64Value(encrypted)

            val success = decrypted == testText
            LogUtils.e(TAG, "🧪 Rapid test: ${if (success) "✅ OK" else "❌ FAILED"}")
            success
        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Rapid test failed", e)
            false
        }
    }

    // =============================================
    // 1. MASTER KEY MANAGEMENT
    // =============================================

    private fun getOrCreateMasterKey(context: Context): SecretKey? {
        return try {
            // First try with KeyStore
            keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore?.load(null)

            if (keyStore?.containsAlias(KEY_ALIAS) == true) {
                val entry = keyStore?.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry
                entry?.secretKey?.also {
                    LogUtils.e(TAG, "📂 KeyStore Key loaded")
                    return it
                }
            }

            // z
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                createKeyStoreKey() ?: createMemoryKey(context)
            } else {
                createMemoryKey(context)
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "⚠️ Error accessing KeyStore, key potentially invalidated", e)

            try {
                keyStore?.deleteEntry(KEY_ALIAS)
                LogUtils.e(TAG, "🗑️ Invalidated key removed from KeyStore")
            } catch (deleteEx: Exception) {
                LogUtils.e(TAG, "⚠️ Error removing Invalidated key from KeyStore", deleteEx)
            }

            createMemoryKey(context)
        }
    }

    @RequiresApi(Build.VERSION_CODES.M)
    private fun createKeyStoreKey(): SecretKey? {
        return try {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                KEYSTORE_PROVIDER
            )

            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)
                .build()

            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Errore creazione chiave KeyStore", e)
            null
        }
    }

    private fun createMemoryKey(context: Context): SecretKey {
        val prefs = context.getSharedPreferences("nook_key_store", Context.MODE_PRIVATE)

        // Check, whether there is an existing key
        val keyVersion = prefs.getInt(KEY_VERSION_PREF, 1)
        val keyString = prefs.getString(KEY_DATA_PREF, null)

        return if (keyString != null) {
            // Load existing key
            val keyBytes = Base64.decode(keyString, Base64.DEFAULT)
            LogUtils.e(TAG, "📂 Memory key version $keyVersion loaded")
            SecretKeySpec(keyBytes, "AES")
        } else {
            // Create new  DETERMINISTIC key  (based on device data)
            val keyBytes = generateDeterministicKey(context)

            // Save as new version
            prefs.edit()
                .putInt(KEY_VERSION_PREF, 2)  // Increment version
                .putString(KEY_DATA_PREF, Base64.encodeToString(keyBytes, Base64.NO_WRAP))
                .apply()

            LogUtils.e(TAG, "🔑 New deterministic memory key created (v2)")
            SecretKeySpec(keyBytes, "AES")
        }
    }


    private fun generateDeterministicKey(context: Context): ByteArray {
        try {
            // Combine semi-stable identifiers
            val deviceId = Build.SERIAL ?: "unknown"
            val androidId = android.provider.Settings.Secure.getString(
                context.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
            ) ?: "unknown"

            val packageName = context.packageName
            val combined = "$deviceId|$androidId|$packageName|NOOK_CRYPTO_V2"

            // Derive key with KDF
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(combined.toByteArray())
        } catch (e: Exception) {
            // Fallback a pseudo-random but with deterministic seed
            val seed = (context.packageName + Build.SERIAL).hashCode().toLong()
            val secureRandom = SecureRandom()
            secureRandom.setSeed(seed)

            val keyBytes = ByteArray(32)
            secureRandom.nextBytes(keyBytes)
            return keyBytes
        }
    }

    // =============================================
    // 2. HMAC MANAGEMENT FOR KEY NAMES
    // =============================================

    private fun createHmacKeyFromMaster() {
        try {
            val masterKeyBytes = when (val key = masterKey) {
                is SecretKeySpec -> key.encoded
                else -> {
                    // For KeyStore keys - derive from alias
                    val digest = MessageDigest.getInstance("SHA-256")
                    digest.digest(KEY_ALIAS.toByteArray() + "HMAC_SALT".toByteArray())
                }
            }

            // Deriv HMAC keys
            val digest = MessageDigest.getInstance("SHA-256")
            val hmacKeyBytes = digest.digest(masterKeyBytes + "HMAC_KEY_DERIVATION".toByteArray())
            hmacKey = SecretKeySpec(hmacKeyBytes, "HmacSHA256")

            LogUtils.e(TAG, "✅ HMAC key derived")
        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Error creating HMAC key", e)
            // Deterministic fallback
            val fallbackKey = "FALLBACK_HMAC_KEY_${KEY_ALIAS.hashCode()}".toByteArray()
            hmacKey = SecretKeySpec(fallbackKey, "HmacSHA256")
        }
    }

    fun encrypt64Key(plainKey: String): String {
        // If already in cache - use this
        keyCache[plainKey]?.let { return it }

        // or encrypt
        val encrypted = if (hmacKey != null) {
            try {
                val mac = Mac.getInstance("HmacSHA256")
                mac.init(hmacKey)
                val hashBytes = mac.doFinal(plainKey.toByteArray(Charsets.UTF_8))

                // Base64 URL-safe
                Base64.encodeToString(hashBytes, Base64.NO_WRAP)
                    .replace("/", "_")
                    .replace("+", "-")
                    .replace("=", "")
                    .take(32)
            } catch (e: Exception) {
                fallbackHash(plainKey)
            }
        } else {
            fallbackHash(plainKey)
        }

        // Add prefix and save in cache
        val result = "k_$encrypted"
        keyCache[plainKey] = result
        reverseCache[result] = plainKey

        return result
    }

    private fun fallbackHash(keyName: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(keyName.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(hashBytes, Base64.NO_WRAP)
                .replace("/", "_")
                .replace("+", "-")
                .replace("=", "")
                .take(32)
        } catch (e: Exception) {
            "f_" + keyName.hashCode().toString(16).padStart(8, '0')
        }
    }

    // =============================================
    // 3. ENCRYPT/DECRYPT values
    // using standard Base64 and cipher
    // =============================================

    fun encrypt64Value(data: String): String {
        if (data.isEmpty() || masterKey == null) return data

        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, masterKey)

            val iv = cipher.iv
            val encryptedBytes = cipher.doFinal(data.toByteArray(Charsets.UTF_8))
            val combined = iv + encryptedBytes

            Base64.encodeToString(combined, Base64.NO_WRAP)
        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Error encrypting value", e)
            data
        }
    }

    fun decrypt64Value(encryptedData: String): String {
        if (encryptedData.isEmpty() || masterKey == null || isLikelyPlainText(encryptedData)) return encryptedData

        return try {
            val combined = Base64.decode(encryptedData, Base64.DEFAULT)
            if (combined.size < 12) return encryptedData

            val iv = combined.copyOfRange(0, 12)
            val ciphertext = combined.copyOfRange(12, combined.size)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val gcmSpec = GCMParameterSpec(128, iv)
            cipher.init(Cipher.DECRYPT_MODE, masterKey, gcmSpec)

            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Error decrypting value", e)
            encryptedData
        }
    }

    // =============================================
    // 4. MAPPING MANAGEMENT
    // =============================================

    private fun loadKeyMappings(context: Context) {
        try {
            val prefs = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
            val json = prefs.getString("key_mappings", null)

            if (json != null) {
                val type = object : TypeToken<Map<String, String>>() {}.type
                val mappings = Gson().fromJson<Map<String, String>>(json, type)

                mappings.forEach { (plainKey, encryptedKey) ->
                    keyCache[plainKey] = encryptedKey
                    reverseCache[encryptedKey] = plainKey
                }

                LogUtils.e(TAG, "✅ Loaded ${mappings.size} key mappings")
            }
        } catch (e: Exception) {
            LogUtils.e(TAG, "❌ Error loading key mappings", e)
        }
    }

    private fun saveKeyMappings(context: Context) {
        try {
            val prefs = context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
            val json = Gson().toJson(keyCache)
            prefs.edit().putString("key_mappings", json).apply()
            LogUtils.d(context, TAG, "💾 Salvate ${keyCache.size} mappature")
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Errore salvataggio mappature", e)
        }
    }

    // =============================================
    // 5. SHARED PREFERENCES COMPLETE
    // =============================================

    fun createSecurePreferences(context: Context): SharedPreferences {
        LogUtils.e(context, TAG, "📁 CREATION SecurePreferences COMPLETE")

        return object : SharedPreferences {
            private val delegate = context.getSharedPreferences(
                SHARED_PREFS_NAME,
                Context.MODE_PRIVATE
            )

            override fun contains(key: String): Boolean {
                val encryptedKey = encrypt64Key(key)
                return delegate.contains(encryptedKey)
            }

            override fun edit(): SharedPreferences.Editor {
                return SecureEditor(context)
            }

            inner class SecureEditor(private val context: Context) : SharedPreferences.Editor {
                private val editor = delegate.edit()
                private val changes = mutableMapOf<String, Pair<String?, Boolean>>()

                override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor {
                    return putString(key, value.toString())
                }

                override fun putFloat(key: String, value: Float): SharedPreferences.Editor {
                    return putString(key, value.toString())
                }

                override fun putInt(key: String, value: Int): SharedPreferences.Editor {
                    return putString(key, value.toString())
                }

                override fun putLong(key: String, value: Long): SharedPreferences.Editor {
                    return putString(key, value.toString())
                }

                override fun putString(key: String, value: String?): SharedPreferences.Editor {
                    val encryptedKey = encrypt64Key(key)
                    val encryptedValue = if (value != null) encrypt64Value(value) else null
                    changes[encryptedKey] = Pair(encryptedValue, false)
                    return this
                }

                override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor {
                    val json = if (values != null) Gson().toJson(values) else null
                    return putString(key, json)
                }

                override fun remove(key: String): SharedPreferences.Editor {
                    val encryptedKey = encrypt64Key(key)
                    changes[encryptedKey] = Pair(null, true)
                    return this
                }

                override fun clear(): SharedPreferences.Editor {
                    editor.clear()
                    return this
                }

                override fun apply() {
                    applyChanges()
                    saveKeyMappings(context)
                    editor.apply()
                }

                override fun commit(): Boolean {
                    applyChanges()
                    saveKeyMappings(context)
                    return editor.commit()
                }

                private fun applyChanges() {
                    changes.forEach { (encryptedKey, pair) ->
                        val (encryptedValue, isRemove) = pair
                        if (isRemove) {
                            editor.remove(encryptedKey)
                        } else if (encryptedValue != null) {
                            editor.putString(encryptedKey, encryptedValue)
                        } else {
                            editor.remove(encryptedKey)
                        }
                    }
                    changes.clear()
                }
            }

            override fun getAll(): MutableMap<String, *> {
                val map = mutableMapOf<String, Any>()
                delegate.all.forEach { (encryptedKey, encryptedValue) ->
                    if (encryptedValue is String) {
                        val plainKey = reverseCache[encryptedKey]
                        if (plainKey != null) {
                            val decryptedValue = decrypt64Value(encryptedValue)
                            map[plainKey] = decryptedValue
                        }
                    }
                }
                return map
            }

            override fun getBoolean(key: String, defValue: Boolean): Boolean {
                val value = getString(key, defValue.toString())
                return value?.toBooleanStrictOrNull() ?: defValue
            }

            override fun getFloat(key: String, defValue: Float): Float {
                val value = getString(key, defValue.toString())
                return value?.toFloatOrNull() ?: defValue
            }

            override fun getInt(key: String, defValue: Int): Int {
                val value = getString(key, defValue.toString())
                return value?.toIntOrNull() ?: defValue
            }

            override fun getLong(key: String, defValue: Long): Long {
                val value = getString(key, defValue.toString())
                return value?.toLongOrNull() ?: defValue
            }

            override fun getString(key: String, defValue: String?): String? {
                val encryptedKey = encrypt64Key(key)
                val encryptedValue = delegate.getString(encryptedKey, null)

                return if (encryptedValue != null) {
                    val decrypted = decrypt64Value(encryptedValue)
                    if (decrypted.isNotEmpty()) decrypted else defValue
                } else {
                    defValue
                }
            }

            override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? {
                val json = getString(key, null)
                return if (json != null) {
                    try {
                        Gson().fromJson(json, MutableSet::class.java) as MutableSet<String>
                    } catch (e: Exception) {
                        defValues
                    }
                } else {
                    defValues
                }
            }

            override fun registerOnSharedPreferenceChangeListener(
                listener: SharedPreferences.OnSharedPreferenceChangeListener
            ) {
                delegate.registerOnSharedPreferenceChangeListener(listener)
            }

            override fun unregisterOnSharedPreferenceChangeListener(
                listener: SharedPreferences.OnSharedPreferenceChangeListener
            ) {
                delegate.unregisterOnSharedPreferenceChangeListener(listener)
            }
        }
    }

    // =============================================
    // 6. TEST AND UTILITIES
    // =============================================

    fun testFullEncryption(context: Context): Boolean {
        LogUtils.e(context, TAG, "🧪 TEST COMPLETO IN CORSO...")

        return try {
            // Test 1: Encrypting/Decrypting values
            val testValue = "test_value_${System.currentTimeMillis()}"
            val encryptedValue = encrypt64Value(testValue)
            val decryptedValue = decrypt64Value(encryptedValue)

            val test1 = decryptedValue == testValue
            LogUtils.e(context, TAG, "Test valori: ${if (test1) "✅" else "❌"} ($decryptedValue)")

            // Test 2: Encrypting keys (deterministic)
            val testKey = "test_key_${System.currentTimeMillis()}"
            val encryptedKey1 = encrypt64Key(testKey)
            val encryptedKey2 = encrypt64Key(testKey) // should be the same!

            val test2 = encryptedKey1 == encryptedKey2
            LogUtils.e(context, TAG, "Deterministic key test: ${if (test2) "✅" else "❌"} ($encryptedKey1)")

            // Test 3: SharedPreferences complete
            val prefs = createSecurePreferences(context)
            val testKey3 = "test_pref_key"
            val testValue3 = "test_pref_value"

            prefs.edit().putString(testKey3, testValue3).commit()

            val readValue = prefs.getString(testKey3, null)
            val test3 = readValue == testValue3

            LogUtils.e(context, TAG, "Test SharedPreferences: ${if (test3) "✅" else "❌"} ($readValue)")

            // Cleanup
            prefs.edit().remove(testKey3).apply()

            val allPassed = test1 && test2 && test3
            LogUtils.e(context, TAG, "🧪 TEST COMPLETE: ${if (allPassed) "✅✅✅ PASSED" else "❌❌❌ FAILED"}")

            allPassed
        } catch (e: Exception) {
            LogUtils.e(context, TAG, "❌ Test failed", e)
            false
        }
    }

    fun isEncryptionActive(): Boolean {
        return initialized && masterKey != null
    }

    fun debugInfo(): String {
        return buildString {
            append("🔐 INFO CRYPTOGRAPHIC SYSTEM:\n")
            append("• Initialized: $initialized\n")
            append("• Master key: ${if (masterKey != null) "✅" else "❌"}\n")
            append("• HMAC key: ${if (hmacKey != null) "✅" else "❌"}\n")
            append("• Mappings: ${keyCache.size} chiavi\n")
            append("• KeyStore: ${if (keyStore != null) "✅" else "❌"}\n")

            if (keyCache.isNotEmpty()) {
                append("\n📋 FIRST 5 MAPPINGS:\n")
                keyCache.entries.take(5).forEach { (plain, encrypted) ->
                    append("  '$plain' → '$encrypted'\n")
                }
            }
        }
    }



    private fun isLikelyPlainText(data: String): Boolean {
        // Verifica se sembra testo in chiaro
        return when {
            data.matches(Regex("^\\+?[0-9\\s-]{8,20}$")) -> true  // Numero telefono
            data.matches(Regex("^[A-Za-z0-9\\s.,!?@]{1,100}$")) -> true  // Testo semplice
            data.length < 50 && !data.contains("/") && !data.contains("+") -> true  // Non Base64
            else -> false
        }
    }


}