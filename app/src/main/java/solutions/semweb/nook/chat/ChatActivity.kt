package solutions.semweb.nook.chat

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.runBlocking
import solutions.semweb.nook.AppStateTracker
import solutions.semweb.nook.ChatConversation
import solutions.semweb.nook.ChatMessage
import solutions.semweb.nook.Constants
import solutions.semweb.nook.LogUtils
import solutions.semweb.nook.MainActivity
import solutions.semweb.nook.PhoneUtils
import solutions.semweb.nook.R
import solutions.semweb.nook.SharedPreferencesManager
import solutions.semweb.nook.crypto.AppCryptoManager
import solutions.semweb.nook.crypto.ChatSafeCopyManager
import solutions.semweb.nook.crypto.EncryptionMapper
import solutions.semweb.nook.data.database.ChatConversationEntity
import solutions.semweb.nook.data.database.DatabaseActor
import solutions.semweb.nook.data.database.DatabaseManager
import solutions.semweb.nook.keyboards.KeyboardManagementActivity
import solutions.semweb.nook.keyboards.KeyboardSafetyManager

class ChatActivity : AppCompatActivity() {

    // === MAIN VARS ===
    private lateinit var phoneNumber: String
    private lateinit var chatManager: ChatManager
    private lateinit var adapter: ChatMessagesAdapter
    private lateinit var chatTitle: TextView
    private lateinit var messageInput: EditText
    private lateinit var sendButton: MaterialButton
    private lateinit var recyclerView: RecyclerView
    private lateinit var prefs: SharedPreferencesManager
    private lateinit var keyboardSafetyManager: KeyboardSafetyManager

    // === STATUS VARS ===
    private var conversation: ChatConversation? = null
    private var isYChat = false
    private var yUserId: String? = null
    private var currentMsgSeq = Constants.MSG_SEQ
    private var conversationId: Long? = null  // ✅ MEMORIZZA L'ID CONVERSAZIONE!
    private var isChatForeground = false // Chat aperta

    // === LOADING VARS ===
    private var isLoadingMessages = false
    private var isLoadingMore = false

    // === PAGING VARS  ===
    private var hasOlderMessages = false
    private var currentMessageOffset = 0
    private var totalMessageCount = 0
    private var remainingMessages = 0
    private lateinit var swipeRefreshLayout: SwipeRefreshLayout

    // === FONT ZOOM VARS ===
    private var currentFontSize = 14f
    private val MIN_FONT_SIZE = 10f
    private val MAX_FONT_SIZE = 24f
    private val FONT_SIZE_STEP = 2f
    private lateinit var scaleDetector: ScaleGestureDetector
    private lateinit var databaseActor: DatabaseActor

    // === UI VARS ===
    private var sortOrderButton: ImageView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_chat)
        keyboardSafetyManager = KeyboardSafetyManager(this)

        conversationId = intent.getLongExtra("conversation_id", 0L)

        if (conversationId != null && conversationId!! > 0L) {
            LogUtils.d(this, "ChatActivity", "✅ Conversation Id received: $conversationId")

            conversation = getConversationById(conversationId!!)

            if (conversation != null) {
                phoneNumber = conversation!!.phoneNumber
                LogUtils.d(this, "ChatActivity",
                    "✅ Conversation loaded: ${conversation!!.phoneNumber}")
            } else {
                // Fallback: usa il numero di telefono
                phoneNumber = intent.getStringExtra("phone_number") ?: run {
                    LogUtils.e(this, "ChatActivity", "❌ No phone number!")
                    finish()
                    return
                }
                LogUtils.w(this, "ChatActivity",
                    "⚠️ Conversation not found by ID - fallback to number: $phoneNumber")
            }
        } else {
            // 3. Fallback: old method
            phoneNumber = intent.getStringExtra("phone_number") ?: run {
                LogUtils.e(this, "ChatActivity", "❌ No ID and no phone number!")
                finish()
                return
            }
            LogUtils.w(this, "ChatActivity",
                "⚠️ No valid ID - use phone number: $phoneNumber")
        }

        if (conversation == null) {
            chatManager = ChatManager(this)
            conversation = chatManager.getConversation(phoneNumber)

            if (conversation == null) {
                LogUtils.e(this, "ChatActivity", "💥 ERROR: Conversation not found")
                showErrorDialog()
                return
            }

            conversationId = conversation!!.id
        }

        if (!::chatManager.isInitialized) {
            chatManager = ChatManager(this)
        }
        databaseActor = DatabaseActor.getInstance(this)

        // 6. Normally continue
        prefs = SharedPreferencesManager.getInstance(this)
        currentMsgSeq = prefs.msgSeq
        isYChat = conversation?.isYChat ?: phoneNumber.startsWith("Y_")
        yUserId = if (isYChat) phoneNumber.removePrefix("Y_") else null

        // 7. Setup UI
        setupUI()
        setChatTitle()
        loadMessages()

        LogUtils.d(this, "ChatActivity",
            "📱 Chat aperta: ID=$conversationId, " +
                    "Phone=$phoneNumber, " +
                    "YChat=$isYChat")
    }

    private fun showErrorDialog() {
        AlertDialog.Builder(this)
            .setTitle("Errore")
            .setMessage("Impossibile aprire la chat. Conversazione non trovata.")
            .setPositiveButton("OK") { _, _ -> finish() }
            .setCancelable(false)
            .show()
    }


    private fun getConversationById(conversationId: Long): ChatConversation? {
        LogUtils.d(this, "ChatActivity", "🔍 getConversationById called with ID: $conversationId")

        var result: ChatConversation? = null

        val thread = Thread {
            try {
                LogUtils.d(this@ChatActivity, "ChatActivity", "🧵 Thread query database started")

                // 1. Query database
                val databaseManager = DatabaseManager.getInstance(this@ChatActivity)
                val entity = databaseManager.database.chatConversationDao()
                    .findById(conversationId)

                LogUtils.d(this@ChatActivity, "ChatActivity", "📊 Query result: ${entity != null}")

                if (entity == null) {
                    LogUtils.w(this@ChatActivity, "ChatActivity", "⚠️ Entity NULL for ID: $conversationId")
                    return@Thread
                }

                // 2. Decrypt data
                val decryptedPhone = try {
                    AppCryptoManager.decrypt64Value(entity.phoneNumber)
                } catch (e: Exception) {
                    LogUtils.e(this@ChatActivity, "ChatActivity", "❌ Error decrypting phone", e)
                    "ERROR"
                }

                val decryptedContactName = entity.contactName?.let { name ->
                    try {
                        AppCryptoManager.decrypt64Value(name)
                    } catch (e: Exception) {
                        LogUtils.e(this@ChatActivity, "ChatActivity", "❌ Error name decryption", e)
                        null
                    }
                }

                val decryptedLastMessage = try {
                    AppCryptoManager.decrypt64Value(entity.lastMessage)
                } catch (e: Exception) {
                    LogUtils.e(this@ChatActivity, "ChatActivity", "❌ Error message decryption", e)
                    "[ERROR DECRYPT]"
                }

                // 3. Crea oggetto
                result = ChatConversation(
                    id = entity.id,
                    phoneNumber = decryptedPhone,
                    contactName = decryptedContactName,
                    lastMessage = decryptedLastMessage,
                    lastTimestamp = entity.lastTimestamp,
                    unreadCount = entity.unreadCount,
                    isYChat = entity.isYChat,
                    encryptionScheme = entity.encryptionScheme,
                    createdAt = entity.createdAt
                )

                LogUtils.d(this@ChatActivity, "ChatActivity", "✅ Conversation created in thread")

            } catch (e: Exception) {
                LogUtils.e(this@ChatActivity, "ChatActivity", "❌ Error in thread", e)
            }
        }

        thread.start()
        thread.join()

        LogUtils.d(this, "ChatActivity", "🏁 getConversationById ended, result=${result != null}")
        return result
    }


     private fun createNewConversationWithId(phoneNumber: String): Long? {
        return try {
            LogUtils.d(this, "ChatActivity", "🔨 Creazione FORZATA nuova conversazione per: $phoneNumber")

            val chatManager = ChatManager(this)
            val contactName = chatManager.getContactNameFromPhone(phoneNumber) ?: phoneNumber

            val newConversation = ChatConversation(
                phoneNumber = phoneNumber,
                contactName = contactName,
                lastMessage = "",
                lastTimestamp = System.currentTimeMillis(),
                unreadCount = 0,
                isYChat = phoneNumber.startsWith("Y_"),
                encryptionScheme = ""
            )

            val databaseManager = DatabaseManager.getInstance(this)
            val entity = ChatConversationEntity.fromDomain(newConversation, this)

            val newId = databaseManager.database.chatConversationDao().insert(entity)

            LogUtils.d(this, "ChatActivity", "✅ New conversation created with ID: $newId")
            newId

        } catch (e: Exception) {
            LogUtils.e(this, "ChatActivity", "❌ Error forced conversation creation", e)
            null
        }
    }

    private fun debugSwipeConfiguration() {
        LogUtils.d(this, "ChatActivity", "🔍 DEBUG SWIPE CONFIGURATION:")
        LogUtils.d(this, "ChatActivity", "  MSG_SEQ: $currentMsgSeq")
        LogUtils.d(this, "ChatActivity", "  hasOlderMessages: $hasOlderMessages")
        LogUtils.d(this, "ChatActivity", "  isLoadingMore: $isLoadingMore")
        LogUtils.d(this, "ChatActivity", "  swipeRefreshLayout.isEnabled: ${swipeRefreshLayout.isEnabled}")
        LogUtils.d(this, "ChatActivity", "  swipeRefreshLayout.isRefreshing: ${swipeRefreshLayout.isRefreshing}")
    }

    @SuppressLint("ClickableViewAccessibility", "UnspecifiedRegisterReceiverFlag")
    private fun setupUI() {
        LogUtils.d(this, "ChatActivity", "🔧 setupUI() chiamato")

        syncMsgSeqFromPrefs()
        LogUtils.d(this, "ChatActivity", "✅ MSG_SEQ synchronized: $currentMsgSeq")

        chatTitle = findViewById(R.id.chat_title)
        messageInput = findViewById(R.id.message_input)
        sendButton = findViewById<MaterialButton>(R.id.send_button)
        recyclerView = findViewById(R.id.chat_recycler_view)
        swipeRefreshLayout = findViewById(R.id.swipe_refresh_layout)

        // ⭐⭐ FORZA il posizionamento dell'indicatore di refresh ⭐⭐
        swipeRefreshLayout.setProgressViewEndTarget(true, 200)

        configureSwipeRefreshLayout()
        setupTouchDebugging()

        LogUtils.d(this, "ChatActivity", "✅ SwipeRefreshLayout configured for MSG_SEQ: $currentMsgSeq")

        recyclerView.layoutManager = LinearLayoutManager(this)
        LogUtils.d(this, "ChatActivity", "✅ LayoutManager configured")

        LogUtils.d(this, "ChatActivity", "=== DEBUG UI COMPONENTS ===")
        LogUtils.d(this, "ChatActivity", "recyclerView found: ${recyclerView != null}")
        LogUtils.d(this, "ChatActivity", "swipeRefreshLayout found: ${swipeRefreshLayout != null}")

        // DEBUG: Verifica visibilità SwipeRefreshLayout
        LogUtils.d(this, "ChatActivity", "swipeRefreshLayout visibility: ${swipeRefreshLayout.visibility}")
        LogUtils.d(this, "ChatActivity", "swipeRefreshLayout height: ${swipeRefreshLayout.height}")

        // DEBUG: Verifica layout hierarchy
        val parent = swipeRefreshLayout.parent
        LogUtils.d(this, "ChatActivity", "SwipeRefreshLayout parent: ${parent?.javaClass?.simpleName}")


        LogUtils.d(this, "ChatActivity", "✅ SwipeRefreshLayout configured for MSG_SEQ: $currentMsgSeq")
        debugSwipeConfiguration()

        adapter = ChatMessagesAdapter(
            emptyList(),
            this,
            onMessageLongClick = { message ->
                showMessageOptions(message)
            },
            onLoadMoreClick = {
                handleSwipeToLoadMore()
            }
        )

        adapter.setMsgSeq(currentMsgSeq)
        LogUtils.d(this, "ChatActivity", "✅ Adapter configured with MSG_SEQ: $currentMsgSeq")

        recyclerView.adapter = adapter

        adapter.onContinuationStateChanged = { show, loading ->
            runOnUiThread {
                LogUtils.d(this, "ChatActivity",
                    "🔄 Continuation state changed: show=$show, loading=$loading")
                updateSwipeRefreshVisibility()
            }
        }

        currentFontSize = prefs.getChatFontSize() ?: 14f
        adapter.setFontSize(currentFontSize)
        LogUtils.d(this, "ChatActivity", "✅ Font size loaded: $currentFontSize")

        messageInput.setOnLongClickListener {
            LogUtils.d(this, "ChatActivity", "📋 Show pase options")
            showPasteOptions()
            true
        }

        findViewById<View>(R.id.chat_title)?.setOnClickListener {
            LogUtils.d(this, "ChatActivity", "ℹ️ Show chat info")
            showChatInfo()
        }

        sendButton.setOnClickListener {
            LogUtils.d(this, "ChatActivity", "📤 Start button clicked")
            chatActSendMessage()
        }

        findViewById<View>(R.id.back_button).setOnClickListener {
            LogUtils.d(this, "ChatActivity", "⬅️ Back button clicked")
            onBackPressed()
        }

        findViewById<ImageView>(R.id.zoom_in_button).setOnClickListener {
            LogUtils.d(this, "ChatActivity", "🔍 Zoom in clicked")
            increaseFontSize()
        }

        findViewById<ImageView>(R.id.zoom_out_button).setOnClickListener {
            LogUtils.d(this, "ChatActivity", "🔎 Zoom out clicked")
            decreaseFontSize()
        }

        sortOrderButton = findViewById(R.id.sort_order_button)
        if (sortOrderButton != null) {
            setupSortOrderButton()
            LogUtils.d(this, "ChatActivity", "✅ Ordering button configured")
        } else {
            LogUtils.w(this, "ChatActivity", "⚠️ Ordering button not found")
        }

        setupPinchToZoom()
        LogUtils.d(this, "ChatActivity", "✅ Pinch-to-zoom configured")

        val scanSmsButton: ImageButton = findViewById(R.id.scan_sms_button)
        scanSmsButton.setOnClickListener {
            LogUtils.d(this, "ChatActivity", "🔍 SMS Scan button clicked")
            showScanOptionsDialogForThisChat()
        }

        messageInput.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus && view is EditText) {
                LogUtils.d(this, "ChatActivity", "⌨️ Message input has focus")
                Handler(Looper.getMainLooper()).postDelayed({
                    val status = keyboardSafetyManager.checkKeyboardSafety()
                    LogUtils.d(this, "ChatActivity", "🔐 Keyboard state: $status")
                    if (status == KeyboardSafetyManager.KeyboardSafetyStatus.REJECTED ||
                        status == KeyboardSafetyManager.KeyboardSafetyStatus.IGNORED) {
                        LogUtils.w(this, "ChatActivity", "⚠️ Usafe keyboard detected")
                        showKeyboardWarning()
                    }
                }, 300)
            } else if (!hasFocus) {
                LogUtils.d(this, "ChatActivity", "⌨️ Message input loose focus")
            }
        }

        val chatUpdateReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                LogUtils.d(this@ChatActivity, "ChatActivity", "📡 Broadcast received: CHAT_UPDATED")
                runOnUiThread {
                    syncMsgSeqFromPrefs()
                    loadMessages()
                    recyclerView.postDelayed({
                        scrollToAppropriatePosition()
                        updateSwipeRefreshVisibility()
                    }, 200)
                }
            }
        }

        val filter = IntentFilter(Constants.mainpackage + ".CHAT_UPDATED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(chatUpdateReceiver, filter, RECEIVER_NOT_EXPORTED)
            LogUtils.d(this, "ChatActivity", "✅ Broadcast receiver registered (API >= TIRAMISU)")
        } else {
            registerReceiver(chatUpdateReceiver, filter)
            LogUtils.d(this, "ChatActivity", "✅ Broadcast receiver registered (API < TIRAMISU)")
        }

        val messageDeletedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                LogUtils.d(this@ChatActivity, "ChatActivity", "📡 Broadcast registered: MESSAGE_DELETED")
                runOnUiThread {
                    loadMessages()
                }
            }
        }

        val deleteFilter = IntentFilter("${Constants.mainpackage}.MESSAGE_DELETED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(messageDeletedReceiver, deleteFilter, RECEIVER_NOT_EXPORTED)
            LogUtils.d(this, "ChatActivity", "✅ Message deleted receiver registrato")
        } else {
            registerReceiver(messageDeletedReceiver, deleteFilter)
            LogUtils.d(this, "ChatActivity", "✅ Message deleted receiver registrato")
        }

        runOnUiThread {
            updateSwipeRefreshVisibility()
            LogUtils.d(this, "ChatActivity", "🔄 Visibility swipe updated")
        }

        LogUtils.d(this, "ChatActivity", "🎉 setupUI() finished with success!")
    }

    private fun setupSortOrderButton() {
        val button = sortOrderButton ?: return
        currentMsgSeq = prefs.msgSeq
        updateSortOrderButton()

        button.setOnClickListener {
            toggleSortOrder()
        }
    }

    private fun toggleSortOrder() {
        val newMsgSeq = if (prefs.msgSeq == 1) 0 else 1
        prefs.msgSeq = newMsgSeq

        currentMsgSeq = newMsgSeq

        updateSortOrderButton()

        Toast.makeText(this,
            if (currentMsgSeq == 1) getString(R.string.sort_order_on)
            else getString(R.string.sort_order_off),
            Toast.LENGTH_SHORT).show()

        loadMessages()
        updateSwipeRefreshVisibility()
    }

    private fun updateSortOrderButton() {
        val button = sortOrderButton ?: return
        val iconRes = if (currentMsgSeq == 1) {
            R.drawable.ic_arrow_downward
        } else {
            R.drawable.ic_arrow_upward
        }
        button.setImageResource(iconRes)
    }

    private fun setupPinchToZoom() {
        scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val scaleFactor = detector.scaleFactor
                if (scaleFactor > 1.1f) {
                    increaseFontSize()
                    return true
                } else if (scaleFactor < 0.9f) {
                    decreaseFontSize()
                    return true
                }
                return false
            }
        })

        recyclerView.setOnTouchListener { _, event ->
            scaleDetector.onTouchEvent(event)
            false
        }
    }

    private fun loadMessages() {
        if (isLoadingMessages) return

        if (conversationId == null) {
            LogUtils.e(this, "ChatActivity", "❌ conversationId is null, messageloading impossible")
            return
        }

        isLoadingMessages = true
        currentMessageOffset = 0
        hasOlderMessages = false
        totalMessageCount = 0
        remainingMessages = 0

        Thread {
            try {
                LogUtils.d(this, "ChatActivity", "📥 Initial message loading...")
                LogUtils.d(this, "ChatActivity", "🔍 ConversationId: $conversationId")

                val latestMessages = chatManager.getLatestMessagesByConversationId(
                    conversationId!!,
                    Constants.MESSAGES_LIMIT
                )

                totalMessageCount = chatManager.getTotalMessageCountByConversationId(conversationId!!)

                LogUtils.d(this, "ChatActivity",
                    "📊 Loaded messages: ${latestMessages.size}, Total: $totalMessageCount")

                currentMessageOffset = latestMessages.size
                remainingMessages = maxOf(0, totalMessageCount - currentMessageOffset)
                hasOlderMessages = remainingMessages > 0

                runOnUiThread {
                    adapter.updateMessages(latestMessages)
                    adapter.setShowContinuation(
                        show = hasOlderMessages,
                        loading = false,
                        allLoaded = !hasOlderMessages,
                        remainingCount = remainingMessages
                    )

                    if (latestMessages.isNotEmpty()) {
                        scrollToAppropriatePosition()
                    }

                    isLoadingMessages = false

                    LogUtils.d(this, "ChatActivity",
                        "✅ Loaded ${latestMessages.size} messages, " +
                                "total in DB: $totalMessageCount, " +
                                "offset: $currentMessageOffset, " +
                                "remaining: $remainingMessages")
                }

            } catch (e: Exception) {
                LogUtils.e(this, "ChatActivity", "❌ Error loadMessages", e)
                runOnUiThread {
                    isLoadingMessages = false
                    MainActivity.showToast(this.getString(R.string.errorloadingmessages) )
                }
            }
        }.start()
    }

    private fun loadMoreMessages() {
        if (!hasOlderMessages || isLoadingMore) {
            LogUtils.d(this, "ChatActivity",
                "⚠️ loadMoreMessages SKIP: hasOlderMessages=$hasOlderMessages, isLoadingMore=$isLoadingMore")
            swipeRefreshLayout.isRefreshing = false
            return
        }

        isLoadingMore = true
        swipeRefreshLayout.isEnabled = false

        LogUtils.d(this, "ChatActivity", "🔄 loadMoreMessages chiamato")

        adapter.setShowContinuation(
            show = true,
            loading = true,
            allLoaded = false,
            remainingCount = remainingMessages
        )

        Thread {
            try {
                if (conversationId == null) {
                    LogUtils.e(this, "ChatActivity", "❌ No Conversation ID!")
                    conversationId = conversation?.id
                    if (conversationId == null) {
                        LogUtils.e(this, "ChatActivity", "❌ Impossible to get Conversation ID!")
                        runOnUiThread {
                            adapter.setShowContinuation(
                                show = false,
                                loading = false,
                                allLoaded = true,
                                remainingCount = 0
                            )
                            adapter.setLoadingState(false)
                            isLoadingMore = false
                            swipeRefreshLayout.isRefreshing = false
                            updateSwipeRefreshVisibility()
                        }
                        return@Thread
                    }
                }

                val offsetForQuery = currentMessageOffset

                LogUtils.d(this, "ChatActivity",
                    "📜 Loading more old messages for ID: $conversationId\n" +
                            "📊 Already loaded messages: $currentMessageOffset\n" +
                            "📊 Total in DB: $totalMessageCount\n" +
                            "📊 Remaining: $remainingMessages\n" +
                            "📊 Offset for query: $offsetForQuery")

                val olderMessages = chatManager.getOlderMessagesByConversationId(
                    conversationId!!,
                    Constants.MESSAGES_LIMIT,
                    offsetForQuery
                )

                LogUtils.d(this, "ChatActivity",
                    "✅ Messages loaded by query: ${olderMessages.size}")

                runOnUiThread {
                    if (olderMessages.isEmpty()) {
                        hasOlderMessages = false
                        remainingMessages = 0

                        adapter.setShowContinuation(
                            show = false,
                            loading = false,
                            allLoaded = true,
                            remainingCount = 0
                        )
                        adapter.setLoadingState(false)

                        Toast.makeText(this@ChatActivity,
                            getString(R.string.no_more_messages_to_load),
                            Toast.LENGTH_SHORT).show()

                        LogUtils.d(this, "ChatActivity",
                            "📭 No further message to load")
                    } else {
                        val messagesLoaded = olderMessages.size
                        currentMessageOffset += messagesLoaded
                        remainingMessages = maxOf(0, totalMessageCount - currentMessageOffset)
                        hasOlderMessages = remainingMessages > 0

                        LogUtils.d(this, "ChatActivity",
                            "📈 After loading:\n" +
                                    "  Messages now loaded: $messagesLoaded\n" +
                                    "  New total loaded ones: $currentMessageOffset\n" +
                                    "  New remaining ones: $remainingMessages")

                        adapter.addMoreMessages(olderMessages, currentMsgSeq)
                        adapter.setLoadingState(false)

                        adapter.setShowContinuation(
                            show = hasOlderMessages,
                            loading = false,
                            allLoaded = !hasOlderMessages,
                            remainingCount = remainingMessages
                        )

                        LogUtils.d(this, "ChatActivity",
                            "✅ ${messagesLoaded} older messages added with success")
                    }

                    isLoadingMore = false
                    updateSwipeRefreshVisibility()
                    swipeRefreshLayout.isRefreshing = false
                }

            } catch (e: Exception) {
                LogUtils.e(this, "ChatActivity", "❌ Error loadMoreMessages", e)
                runOnUiThread {
                    adapter.setShowContinuation(
                        show = true,
                        loading = false,
                        allLoaded = false,
                        remainingCount = remainingMessages
                    )
                    adapter.setLoadingState(false)
                    isLoadingMore = false

                    updateSwipeRefreshVisibility()
                    swipeRefreshLayout.isRefreshing = false

                    MainActivity.showToast(this.getString(R.string.errorloadingmessages))
                }
            }
        }.start()
    }


    private fun scrollToAppropriatePosition() {
        val itemCount = adapter.itemCount
        if (itemCount > 0) {
            when (currentMsgSeq) {
                1 -> {
                    recyclerView.smoothScrollToPosition(itemCount - 1)
                    LogUtils.d(this, "ChatActivity",
                        "📜 Scroll at bottom at position: ${itemCount - 1} (MSG_SEQ=1)")
                }
                0 -> {
                    recyclerView.smoothScrollToPosition(0)
                    LogUtils.d(this, "ChatActivity",
                        "📜 Scroll at top at: 0 (MSG_SEQ=0)")
                }
                else -> {
                    recyclerView.smoothScrollToPosition(itemCount - 1)
                }
            }
        }
    }

    private fun increaseFontSize() {
        if (currentFontSize < MAX_FONT_SIZE) {
            currentFontSize += FONT_SIZE_STEP
            applyFontSize()
        } else {
            Toast.makeText(this, getString(R.string.max_font_size_reached), Toast.LENGTH_SHORT).show()
        }
    }

    private fun decreaseFontSize() {
        if (currentFontSize > MIN_FONT_SIZE) {
            currentFontSize -= FONT_SIZE_STEP
            applyFontSize()
        } else {
            Toast.makeText(this, getString(R.string.min_font_size_reached), Toast.LENGTH_SHORT).show()
        }
    }

    private fun applyFontSize() {
        adapter.setFontSize(currentFontSize)
        prefs.setChatFontSize(currentFontSize)

        Toast.makeText(this,
            getString(R.string.font_size_set_to, currentFontSize.toInt()),
            Toast.LENGTH_SHORT
        ).show()
    }

    private fun showKeyboardWarning() {
        val keyboardManager = KeyboardSafetyManager(this)
        val currentKeyboardId = keyboardManager.getCurrentKeyboardId()
        val keyboardName = keyboardManager.getKeyboardDisplayName()
        val displayKeyboardId = currentKeyboardId ?: getString(R.string.keyboard_unknown)

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.keyboard_warning_title))
            .setMessage(getString(R.string.keyboard_warning_message, keyboardName, displayKeyboardId))
            .setPositiveButton(getString(R.string.keyboard_warning_open_settings)) { dialog, _ ->
                openKeyboardSettings()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.keyboard_warning_ignore)) { dialog, _ ->
                Toast.makeText(this,
                    getString(R.string.keyboard_warning_ignored),
                    Toast.LENGTH_SHORT
                ).show()
                dialog.dismiss()
            }
            .setNeutralButton(getString(R.string.keyboard_warning_manage)) { dialog, _ ->
                openKeyboardManagement()
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }

    private fun showPasteOptions() {
        val popup = PopupMenu(this, messageInput)
        val clipboard = getSystemService(ClipboardManager::class.java)

        if (clipboard.hasPrimaryClip()) {
            popup.menu.add(getString(R.string.paste)).setOnMenuItemClickListener {
                messageInput.onTextContextMenuItem(android.R.id.paste)
                true
            }
        }

        if (ChatSafeCopyManager.hasValidSafeCopy(this)) {
            val preview = ChatSafeCopyManager.getPreviewText(this)
            val menuText = if (preview != null) {
                "${getString(R.string.paste_safe)}: \"$preview\""
            } else {
                getString(R.string.paste_safe)
            }

            popup.menu.add(menuText).setOnMenuItemClickListener {
                pasteSafeText()
                true
            }
        }

        if (popup.menu.size() > 0) {
            popup.show()
        }
    }

    private fun pasteSafeText(decodedText: String? = null) {
        try {
            val pastedText = ChatSafeCopyManager.pasteTextSafely(this)
            if (pastedText != null) {
                val start = messageInput.selectionStart
                val end = messageInput.selectionEnd
                val editable = messageInput.text
                editable.replace(start, end, pastedText)
                messageInput.setSelection(start + pastedText.length)
            }
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.safe_copy_error), Toast.LENGTH_SHORT).show()
            LogUtils.e(this, "ChatActivity", "Error safe paste", e)
            prefs.clearSafeCopyText()
        }
    }

    private fun openKeyboardSettings() {
        try {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.cannot_open_settings), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openKeyboardManagement() {
        val intent = Intent(this, KeyboardManagementActivity::class.java)
        startActivity(intent)
    }

    @SuppressLint("WrongViewCast")
    private fun setChatTitle() {
        val titleText = conversation?.contactName ?: PhoneUtils.normalizePhoneNumber(phoneNumber)

        chatTitle.text = titleText
        val toolbar = findViewById<LinearLayout>(R.id.chat_toolbar)
        chatTitle.setTextColor(ContextCompat.getColor(this, android.R.color.white))

        val gradient = GradientDrawable().apply {
            setColor(ContextCompat.getColor(this@ChatActivity, R.color.middle_green))
        }
        toolbar?.background = gradient

    }

    private fun chatActSendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isEmpty()) {
            Toast.makeText(this, getString(R.string.write_message_before_sending), Toast.LENGTH_SHORT).show()
            return
        }

        LogUtils.d(this, "ChatActivity", "✈️ Sending message: '$text', YChat: $isYChat")

        chatActAddOutgoingMessageToChat(text)
        messageInput.text.clear()
        hideKeyboard()
        checkIfPlaintextAndSend(text)

    }

    private fun chatActAddOutgoingMessageToChat(text: String) {
        LogUtils.d(this, "ChatActivity", "✈️ Creation of outgoing message: '$text'")

        val encryptionScheme = chatManager.getEncryptionSchemeForChat(phoneNumber)
        val schemeToUse = encryptionScheme.ifEmpty { prefs.decodingScheme }
        val schemeAbbr = EncryptionMapper.getPrefixForScheme(schemeToUse)
        val displayText = if (schemeAbbr?.isNotEmpty() == true && schemeToUse != EncryptionMapper.ENCRYPTION_SCHEME_TEXT) {
            "[$schemeAbbr] $text"
        } else {
            text
        }

        val messageId = chatManager.generateMessageId()
        val message = ChatMessage(
            id = messageId,
            text = displayText,
            sender = phoneNumber,
            senderName = conversation?.contactName,
            timestamp = System.currentTimeMillis(),
            isDecoded = true,
            isOutgoing = true,
            isSent = false,
            isYMessage = false
        )

        runOnUiThread {
            adapter.addMessages(listOf(message))
            recyclerView.postDelayed({
                scrollToAppropriatePosition()
            }, 100)
        }

        LogUtils.d(this, "ChatActivity", "⚠️ Message shown in UI, DB will save it after sending SMS")
    }

    private fun checkIfPlaintextAndSend(text: String) {
        val chatManager = ChatManager(this)
        val encryptionScheme = chatManager.getEncryptionSchemeForChat(phoneNumber)

        // val conversation = ChatManager.getConversation(phoneNumber)
        // Carica la conversazione dal db
        val conversation = runBlocking {
            databaseActor.getChatConversation(phoneNumber)
        }

        val encodingScheme = conversation?.encoding
        val schemeToUse = if (encryptionScheme.isNotEmpty()) encryptionScheme else prefs.decodingScheme

        if (schemeToUse == EncryptionMapper.ENCRYPTION_SCHEME_TEXT
            && encodingScheme == EncryptionMapper.ENCRYPTION_SCHEME_TEXT) {
            showPlaintextWarningDialog(text,conversation)
        } else {
            Thread {
                if (conversation!=null)
                    chatManager.sendMessage(this, conversation, text)
            }.start()
        }
    }

    private fun showPlaintextWarningDialog(text: String, conversation: ChatConversation) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.plaintext_warning_title))
            .setMessage(getString(R.string.plaintext_warning_message))
            .setPositiveButton(getString(R.string.send_anyway)) { dialog, _ ->
                sendSmsMessage(text,conversation)
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                //showEncryptionOptionsDialog(text)
                dialog.dismiss()
            }
            .setNeutralButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }



    private fun sendSmsMessage(text: String, conversation: ChatConversation) {
        val chatManager = ChatManager(this)
        val result = chatManager.sendMessage(this, conversation, text)

        if (result.isSent) {
            MainActivity.showToast(getString(R.string.message_sent), false)
        } else {
            MainActivity.showToast(getString(R.string.sms_send_failed), true)
        }
    }

    private fun hideKeyboard() {
        val imm = getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(messageInput.windowToken, 0)
    }

    private fun showMessageOptions(message: ChatMessage) {
        val popup = PopupMenu(this, findViewById(R.id.chat_title))

        popup.menu.add(getString(R.string.zoom_in)).setOnMenuItemClickListener {
            increaseFontSize()
            true
        }

        popup.menu.add(getString(R.string.zoom_out)).setOnMenuItemClickListener {
            decreaseFontSize()
            true
        }

        popup.menu.add(getString(R.string.separator)).isEnabled = false

        popup.menu.add(getString(R.string.copy_text)).setOnMenuItemClickListener {
            copyToClipboard(message.text)
            true
        }

        popup.menu.add(getString(R.string.menu_copy_safe)).setOnMenuItemClickListener {
            copyMessageSafely(message.text)
            true
        }

        popup.menu.add(getString(R.string.delete_message)).setOnMenuItemClickListener {
            deleteMessage(message)
            true
        }

        popup.show()
    }

    private fun copyMessageSafely(text: String) {
        try {
            val success = ChatSafeCopyManager.copyTextSafely(this, text)
            if (success) {
                MainActivity.showToast(getString(R.string.safe_copy_success), false)
            } else {
                MainActivity.showToast(getString(R.string.safe_copy_error), false)
            }
        } catch (e: Exception) {
            MainActivity.showToast(getString(R.string.safe_copy_error), false)
            LogUtils.e(this, "ChatActivity", "Error safe copy", e)
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText(getString(R.string.message), text)
        clipboard.setPrimaryClip(clip)
        MainActivity.showToast(getString(R.string.text_copied))
    }

    private fun deleteMessage(message: ChatMessage) {
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.delete_message_question))
            .setMessage(getString(R.string.do_you_really_want_to_delete_this_message))
            .setPositiveButton(getString(R.string.yes)) { dialog, _ ->
                val success = chatManager.deleteMessage(message.id.toLong())
                if (success) {
                    loadMessages()
                    MainActivity.showToast("Message deleted")
                } else {
                    MainActivity.showToast( "Error deleting message", true)
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.no), null)
            .show()
    }

    private fun showChatInfo() {
        val info = """
            📱 ${getString(R.string.chat)}: ${if (isYChat) "Y-Chat" else "SMS"}
            ${getString(R.string.number_id)}: $phoneNumber
            ${getString(R.string.messages)}: ${conversation?.messages?.size ?: 0}
            ${getString(R.string.unread)}: ${conversation?.unreadCount ?: 0}
            ${getString(R.string.sort_order_button)}: ${if (currentMsgSeq == 1) getString(R.string.sort_order_on) else getString(R.string.sort_order_off)}
        """.trimIndent()

        AlertDialog.Builder(this)
            .setTitle(getString(R.string.chat_info))
            .setMessage(info)
            .setPositiveButton(getString(R.string.ok), null)
            .show()
    }

    private fun showScanOptionsDialogForThisChat() {
        if (!::phoneNumber.isInitialized) {
            Toast.makeText(this, "Errore: chat invalid", Toast.LENGTH_SHORT).show()
            return
        }

        val contactName = conversation?.contactName ?: phoneNumber
        val options = arrayOf(
            this.getString(R.string.lasthours_scan_hours, 6),
            this.getString(R.string.lasthours_scan_hours, 12),
            this.getString(R.string.lasthours_scan_hours, 24),
            this.getString(R.string.lasthours_scan_hours, 48),
            this.getString(R.string.lasthours_scan_days, 3),
            this.getString(R.string.lasthours_scan_days, 7),
            this.getString(R.string.smsscanduration_personalized )
        )

        // Crea una view semplice programmaticamente
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val padding = dpToPx(16)
            setPadding(padding, padding, padding, padding)
        }

        val explanation = TextView(this).apply {
            text = context.getString(R.string.import_explanation)
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.gray_3))
            setPadding(0, 0, 0, dpToPx(16))
        }

        layout.addView(explanation)

        AlertDialog.Builder(this)
            .setTitle("Scansione SMS da $contactName")
            .setView(layout)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> startSmsScanForThisChat(6)
                    1 -> startSmsScanForThisChat(12)
                    2 -> startSmsScanForThisChat(24)
                    3 -> startSmsScanForThisChat(48)
                    4 -> startSmsScanForThisChat(168)
                    5 -> showCustomScanDialogForThisChat()
                }
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // Helper function
    private fun dpToPx(dp: Int): Int {
        return (dp * resources.displayMetrics.density).toInt()
    }

    private fun showCustomScanDialogForThisChat() {
        if (!::phoneNumber.isInitialized) return

        val contactName = conversation?.contactName ?: phoneNumber
        val input = EditText(this)
        input.inputType = InputType.TYPE_CLASS_NUMBER
        input.hint = this.getString(R.string.hourstoscansms_72)+""

        AlertDialog.Builder(this)
            .setTitle(this.getString(R.string.personalized_sms_scanning, contactName))
            .setMessage(this.getString(R.string.howmanyhoursbackcheck))
            .setView(input)
            .setPositiveButton(this.getString(R.string.start)) { _, _ ->
                val hoursText = input.text.toString()
                if (hoursText.isNotEmpty()) {
                    val hours = hoursText.toIntOrNull()
                    if (hours != null && hours > 0) {
                        startSmsScanForThisChat(hours)
                    } else {
                        MainActivity.showToast(this.getString(R.string.insert_a_valid_number))
                    }
                }
            }
            .setNegativeButton(this.getString(R.string.cancel), null)
            .show()
    }

    private fun startSmsScanForThisChat(hoursBack: Int, dummymessageid: Long = -1L, multipartMsgTimestamp: Long = 0, multipartcount: Int = 1) {
        if (!::phoneNumber.isInitialized) {
            LogUtils.e(this, "ChatActivity", "❌ phoneNumber not initialized")
            MainActivity.showToast(this.getString(R.string.error_chat_invalid))
            return
        }

        val contactName = conversation?.contactName ?: phoneNumber
        LogUtils.d(this, "ChatActivity",
            "🎯 Start scanning for: $contactName ($phoneNumber) - last $hoursBack hours")

        val progressDialog = AlertDialog.Builder(this)
            .setTitle(this.getString(R.string.scanning))
            .setMessage(this.getString(R.string.scanning_sms_from_of_last_hours, contactName, hoursBack))
            .setCancelable(false)
            .create()

        progressDialog.show()

        Thread {
            try {
                LogUtils.d(this, "ChatActivity", "🧵 Scanning Thread started")
                val scanner = solutions.semweb.nook.sms.SmsScanner(this@ChatActivity)
                val result = scanner.scanMissingSmsForContact(
                    phoneNumber,
                    hoursBack
                )

                LogUtils.d(this, "ChatActivity",
                    "📊 Results for $phoneNumber: ${result.processed} new SMS's")

                runOnUiThread {
                    progressDialog.dismiss()
                    showResultsForThisChat(result, hoursBack, contactName)
                }
            } catch (e: Exception) {
                LogUtils.e(this, "ChatActivity", "❌ Error scanning", e)
                runOnUiThread {
                    progressDialog.dismiss()
                    showErrorForThisChat(e, contactName)
                }
            }
        }.start()
    }

    private fun showResultsForThisChat(
        result: solutions.semweb.nook.sms.SmsScanner.ScanResult,
        hoursBack: Int,
        contactName: String
    ) {
        val builder = AlertDialog.Builder(this)

        when {
            result.error != null -> {
                builder.setTitle(this.getString(R.string.error))
                    .setMessage(this.getString(R.string.errorscaningfor, contactName, result.error))
                    .setPositiveButton(this.getString(R.string.ok), null)
            }
            result.processed > 0 -> {
                builder.setTitle(this.getString(R.string.scanning_completed))
                    .setMessage(
                                this.getString(R.string.scanning_results_for, contactName)+"\n\n" +
                                this.getString(R.string.scanning_new_sms_added, result.processed) + "\n" +
                                this.getString(R.string.scanning_sms_decrypted, result.decrypted) + "\n" +
                                this.getString(R.string.scanning_sms_plaintext, result.plaintext) + "\n" +
                                this.getString(R.string.scanning_sms_present, result.alreadyExist) + "\n" +
                                this.getString(R.string.scanning_sms_errors, result.errors)
                    )
                    .setPositiveButton(this.getString(R.string.ok)) { dialog, _ ->
                        dialog.dismiss()
                    }
            }
            else -> {
                builder.setTitle(this.getString(R.string.scanning_sms_no_sms_found))
                    .setMessage(
                        this.getString(R.string.scanning_sms_no_missing_sms, contactName, hoursBack)
                    )
                    .setPositiveButton(this.getString(R.string.ok), null )
            }
        }

        builder.show()
    }

    private fun showErrorForThisChat(e: Exception, contactName: String) {
        AlertDialog.Builder(this)
            .setTitle(this.getString(R.string.scanning_error))
            .setMessage(this.getString(R.string.scanning_error_for,contactName,e.message))
            .setPositiveButton(this.getString(R.string.ok), null)
            .show()
    }



    override fun onResume() {
        super.onResume()
        AppStateTracker.onActivityResumed(this)

        isChatForeground = true
        markAsRead()
    }

    override fun onPause() {
        super.onPause()
        AppStateTracker.onActivityPaused(this)

        isChatForeground = false
    }

    override fun onStop() {
        super.onStop()
        AppStateTracker.onActivityStopped(this)
        AppStateTracker.clearCurrentActivity(this)
    }

    override fun onDestroy() {
        markAsRead()
        super.onDestroy()
    }

    private fun markAsRead() {
        LogUtils.d(this, "ChatActivity", "📱 markAsRead for chat: $phoneNumber")
        chatManager.resetUnreadCount(phoneNumber)
    }

    private fun syncMsgSeqFromPrefs() {
        currentMsgSeq = prefs.msgSeq
        LogUtils.d(this, "ChatActivity", "🔄 MSG_SEQ synchronized: $currentMsgSeq")
    }


    private fun configureSwipeRefreshLayout() {
        LogUtils.d(this, "ChatActivity", "🔄 Configuration SwipeRefreshLayout for MSG_SEQ: $currentMsgSeq")

        debugSwipeConfiguration()

        // ⭐⭐ FORZA L'ABILITAZIONE INIZIALE ⭐⭐
        swipeRefreshLayout.isEnabled = true

        // Configura l'offset del progresso in base a MSG_SEQ
        when (currentMsgSeq) {
            1 -> {
                // MSG_SEQ=1: Ordinamento normale (ultimo in fondo)
                // Swipe verso il BASSO per caricare messaggi più vecchi
                swipeRefreshLayout.setProgressViewOffset(false, 0, 100)
                LogUtils.d(this, "ChatActivity", "⬇️ Swipe enabled (MSG_SEQ=1)")
            }
            0 -> {
                // MSG_SEQ=0:  No swipe!!! It does not function towards top
                swipeRefreshLayout.setProgressViewOffset(true, 0, 100)
                // Alternativa: prova con setDistanceToTriggerSync
                swipeRefreshLayout.setDistanceToTriggerSync(100)
            }
        }

        swipeRefreshLayout.setColorSchemeResources(
            android.R.color.holo_blue_bright,
            android.R.color.holo_green_light,
            android.R.color.holo_orange_light,
            android.R.color.holo_red_light
        )

        swipeRefreshLayout.setOnRefreshListener {
            LogUtils.d(this, "ChatActivity", "👆 OnRefreshListener called for MSG_SEQ: $currentMsgSeq")
            handleSwipeToLoadMore()
        }

        // ⭐⭐ FORZA IL CALCOLO DELLE DIMENSIONI ⭐⭐
        swipeRefreshLayout.post {
            LogUtils.d(this, "ChatActivity", "📏 Initial dimensions SwipeRefreshLayout: ${swipeRefreshLayout.width}x${swipeRefreshLayout.height}")

            if (swipeRefreshLayout.height == 0) {
                LogUtils.w(this, "ChatActivity", "⚠️ Hight 0, enforce measuring!")
                swipeRefreshLayout.layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT
                swipeRefreshLayout.requestLayout()
            }
        }

        updateSwipeRefreshVisibility()

        LogUtils.d(this, "ChatActivity", "✅ SwipeRefreshLayout configured for MSG_SEQ: $currentMsgSeq")
    }

    private fun setupTouchDebugging() {
        // Aggiungi un touch listener per debug
        swipeRefreshLayout.setOnTouchListener { view, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    LogUtils.d(this, "ChatActivity", "🖐️ ACTION_DOWN - Y: ${event.y}")
                    LogUtils.d(this, "ChatActivity", "📏 View height: ${view.height}, Enabled: ${view.isEnabled}")
                }
                MotionEvent.ACTION_MOVE -> {
                    LogUtils.d(this, "ChatActivity", "🔄 ACTION_MOVE - Y: ${event.y}, DeltaY: ${event.y - view.height/2}")
                }
                MotionEvent.ACTION_UP -> {
                    LogUtils.d(this, "ChatActivity", "👆 ACTION_UP - Y: ${event.y}")
                }
            }
            false // Non consumiamo l'evento
        }

        // Aggiungi anche al RecyclerView per debug
        recyclerView.setOnTouchListener { view, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                LogUtils.d(this, "ChatActivity", "🖐️ RecyclerView TOUCH - Y: ${event.y}")
            }
            false
        }
    }


    private fun updateSwipeRefreshVisibility() {
        runOnUiThread {
            LogUtils.d(this, "ChatActivity", "🔄 updateSwipeRefreshVisibility chiamato")

            val shouldEnable = hasOlderMessages && !isLoadingMore

            LogUtils.d(this, "ChatActivity",
                "📊 State for enabling swipe: hasOlderMessages=$hasOlderMessages, " +
                        "isLoadingMore=$isLoadingMore, shouldEnable=$shouldEnable")

            swipeRefreshLayout.isEnabled = shouldEnable

            if (!shouldEnable) {
                swipeRefreshLayout.isRefreshing = false
                LogUtils.d(this, "ChatActivity", "⏹️ SwipeRefreshLayout disabled")
            } else {
                LogUtils.d(this, "ChatActivity", "✅ SwipeRefreshLayout enabled")
            }

            // ⭐⭐ ENFORCE LAYOUT REDESIGN ⭐⭐
            swipeRefreshLayout.post {
                // Verifica le dimensioni dopo il layout
                if (swipeRefreshLayout.height == 0) {
                    LogUtils.w(this, "ChatActivity", "⚠️ SwipeRefreshLayout has still height 0!")
                    // Forza il calcolo delle dimensioni
                    swipeRefreshLayout.measure(
                        View.MeasureSpec.makeMeasureSpec(swipeRefreshLayout.width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
                    )
                }
                LogUtils.d(this, "ChatActivity",
                    "📏 Dimensions SwipeRefreshLayout: ${swipeRefreshLayout.width}x${swipeRefreshLayout.height}")
            }

            debugSwipeConfiguration()
        }
    }


    private fun handleSwipeToLoadMore() {
        LogUtils.d(this, "ChatActivity", "👆 handleSwipeToLoadMore called, MSG_SEQ: $currentMsgSeq")

        if (isLoadingMore) {
            LogUtils.d(this, "ChatActivity", "⚠️ Already loading, ignore swipe")
            swipeRefreshLayout.isRefreshing = false
            return
        }

        if (!hasOlderMessages) {
            LogUtils.d(this, "ChatActivity", "📭 No older message to load")
            swipeRefreshLayout.isRefreshing = false

            runOnUiThread {
                Toast.makeText(
                    this,
                    getString(R.string.no_more_messages_to_load),
                    Toast.LENGTH_SHORT
                ).show()
            }
            return
        }

        LogUtils.d(this, "ChatActivity", "🎯 Swipe gesture triggers loadMoreMessages")
        loadMoreMessages()
    }

}

