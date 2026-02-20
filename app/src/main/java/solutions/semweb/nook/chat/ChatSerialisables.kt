package solutions.semweb.nook.chat

import android.annotation.SuppressLint
import android.os.Parcel
import android.os.Parcelable
import com.google.gson.annotations.SerializedName

/**
 * JSON serialization
 * one-char names to save space and money ;)
 */

@SuppressLint("ParcelCreator")
data class ChatSerializableMessage(
    // i = id (mandatory, unique)
    @SerializedName("i")
    val id: Long = 0,

    // t = text (message text)
    @SerializedName("t")
    val text: String? = null,

    // s = timestamp (date/hour)
    @SerializedName("s")
    val timestamp: Long = 0L,

    // o = isOutgoing (true=sent, false=received)
    // 1 = true, 0 = false
    @SerializedName("o")
    val isOutgoing: Int = 0,

    // d = isDecoded (true=decoded)
    // 1 = true, 0 = false
    @SerializedName("d")
    val isDecoded: Int = 0,

    // y = isYMessage (not used here)
    // 1 = true, 0 = false
    @SerializedName("y")
    val isYMessage: Int = 0

) : Parcelable {
    override fun describeContents(): Int {
        TODO("Not yet implemented")
    }

    override fun writeToParcel(p0: Parcel, p1: Int) {
        TODO("Not yet implemented")
    }
}

/**
 * Ultra compact version of ChatConversation for JSON
 */

@SuppressLint("ParcelCreator")
data class SerializableConversation(
    // p = phoneNumber
    @SerializedName("p")
    val phoneNumber: String = "",

    // n = contactName
    @SerializedName("n")
    val contactName: String? = null,

    // l = lastMessage
    @SerializedName("l")
    val lastMessage: String? = null,

    // m = lastMessageTimestamp
    @SerializedName("m")
    val lastMessageTimestamp: Long = 0L,

    // u = unreadCount
    @SerializedName("u")
    val unreadCount: Int = 0,

    // e = encryptionScheme
    @SerializedName("e")
    val encryptionScheme: String? = null,

    // a = messages
    @SerializedName("a")
    val messages: List<ChatSerializableMessage> = emptyList()
) : Parcelable {
    override fun describeContents(): Int {
        TODO("Not yet implemented")
    }

    override fun writeToParcel(p0: Parcel, p1: Int) {
        TODO("Not yet implemented")
    }
}