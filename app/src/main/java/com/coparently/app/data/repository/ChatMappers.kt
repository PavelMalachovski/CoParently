package com.coparently.app.data.repository

import com.coparently.app.data.local.entity.ConversationEntity
import com.coparently.app.data.local.entity.MessageEntity
import com.coparently.app.domain.model.Conversation
import com.coparently.app.domain.model.Message
import com.coparently.app.domain.model.MessageSendStatus
import com.coparently.app.domain.activity.ActivityAnnouncement
import com.coparently.app.domain.model.MessageType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Mapping between the chat domain models, their Room entities and their Firestore
 * documents, plus the small arithmetic the read/delivery marks need.
 *
 * Extracted from `MessageRepositoryImpl` so the repository body reads as behaviour rather
 * than as translation, and so the legacy-conversation merge can reuse exactly the same
 * conversions instead of writing a second, subtly different set.
 */

private val gson = Gson()

private val chatDateFormatter: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

private val stringListType = object : TypeToken<List<String>>() {}.type

/** The stored shape of an activity payload — the same sub-map the Firestore document carries. */
private val activityMapType = object : TypeToken<Map<String, Any?>>() {}.type

private val markMapType = object : TypeToken<Map<String, Long>>() {}.type

/** The stored row as a domain conversation, with both mark maps decoded. */
internal fun ConversationEntity.toDomain(): Conversation = Conversation(
    id = id,
    participants = gson.fromJson(participantsJson, stringListType) ?: emptyList(),
    title = title,
    lastReadAt = gson.fromJson(lastReadAtJson, markMapType) ?: emptyMap(),
    lastDeliveredAt = gson.fromJson(lastDeliveredAtJson, markMapType) ?: emptyMap(),
    lastMessageAtMillis = lastMessageAtMillis,
    archived = archived,
    createdAt = createdAt,
    syncedToFirestore = syncedToFirestore
)

/** The domain conversation as a storable row, with both mark maps encoded. */
internal fun Conversation.toEntity(): ConversationEntity = ConversationEntity(
    id = id,
    participantsJson = gson.toJson(participants),
    title = title,
    lastReadAtJson = gson.toJson(lastReadAt),
    lastDeliveredAtJson = gson.toJson(lastDeliveredAt),
    lastMessageAtMillis = lastMessageAtMillis,
    archived = archived,
    createdAt = createdAt,
    syncedToFirestore = syncedToFirestore
)

/**
 * The stored row as a domain message.
 *
 * An unrecognised status falls back to [MessageSendStatus.SENT]: rows written before the
 * column existed carry none, and a row is only ever asked what *this* device's own write
 * achieved.
 */
internal fun MessageEntity.toDomain(): Message = Message(
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    senderName = senderName,
    content = content,
    sentAtMillis = sentAtMillis,
    // Guarded the same way the Firestore reader below is, and for the same reason: a row whose
    // type this build does not know must degrade to a text bubble carrying `content`, not throw
    // on the path that draws the thread. Unguarded, one such row killed the whole list.
    messageType = runCatching { MessageType.valueOf(messageType) }
        .getOrDefault(MessageType.TEXT),
    attachments = gson.fromJson(attachmentsJson, stringListType) ?: emptyList(),
    isRead = isRead,
    replyToMessageId = replyToMessageId,
    syncedToFirestore = syncedToFirestore,
    status = runCatching { MessageSendStatus.valueOf(status ?: MessageSendStatus.SENT.name) }
        .getOrDefault(MessageSendStatus.SENT),
    activity = activityJson
        ?.let { json -> runCatching { gson.fromJson<Map<String, Any?>>(json, activityMapType) }.getOrNull() }
        ?.let(ActivityAnnouncement::fromMap)
)

/** The domain message as a storable row. */
internal fun Message.toEntity(): MessageEntity = MessageEntity(
    id = id,
    conversationId = conversationId,
    senderId = senderId,
    senderName = senderName,
    content = content,
    sentAtMillis = sentAtMillis,
    messageType = messageType.name,
    attachmentsJson = gson.toJson(attachments),
    isRead = isRead,
    replyToMessageId = replyToMessageId,
    syncedToFirestore = syncedToFirestore,
    status = status.name,
    activityJson = activity?.let { gson.toJson(it.toMap()) }
)

/**
 * The domain message as a Firestore document.
 *
 * `timestamp` is written as a **number** — epoch millis, the same unit as the conversation's
 * read/delivered marks. It used to be a naive ISO string with no offset, which two devices in
 * different timezones could not agree on. The field keeps its name so a build that predates
 * this change still finds it, and so the `messages` composite index still applies.
 *
 * The send status is deliberately absent: it describes this device's own write, and both
 * of its promoted values (`DELIVERED`, `READ`) are derived at render time from the
 * conversation's marks rather than stored anywhere.
 */
internal fun Message.toFirestoreMap(): Map<String, Any> = mapOf(
    "id" to id,
    "conversationId" to conversationId,
    "senderId" to senderId,
    "senderName" to senderName,
    "content" to content,
    "timestamp" to sentAtMillis,
    "messageType" to messageType.name,
    "attachments" to attachments,
    "isRead" to isRead,
    "replyToMessageId" to (replyToMessageId ?: "")
) + (activity?.let { mapOf("activity" to it.toMap()) } ?: emptyMap())

/**
 * A remote message document as a domain message, or `null` when it lacks a field the
 * domain requires — a malformed document is skipped rather than allowed to fail the
 * whole batch.
 */
internal fun Map<String, Any>.toMessageOrNull(): Message? {
    val id = this["id"] as? String
    val conversationId = this["conversationId"] as? String
    if (id == null || conversationId == null) return null

    val senderId = this["senderId"] as? String
    val sentAtMillis = sentAtMillisOrNull("timestamp")
    if (senderId == null || sentAtMillis == null) return null

    return Message(
        id = id,
        conversationId = conversationId,
        senderId = senderId,
        senderName = (this["senderName"] as? String).orEmpty(),
        content = (this["content"] as? String).orEmpty(),
        sentAtMillis = sentAtMillis,
        messageType = runCatching {
            MessageType.valueOf(this["messageType"] as? String ?: MessageType.TEXT.name)
        }.getOrDefault(MessageType.TEXT),
        attachments = stringList("attachments"),
        isRead = this["isRead"] as? Boolean ?: false,
        replyToMessageId = (this["replyToMessageId"] as? String)?.takeIf { it.isNotEmpty() },
        syncedToFirestore = true,
        // Everything read back from Firestore has, by definition, been sent.
        status = MessageSendStatus.SENT,
        activity = ActivityAnnouncement.fromMap(this["activity"] as? Map<*, *>)
    )
}

/** The document's [key] as a list of strings, empty when absent or of another shape. */
internal fun Map<String, Any>.stringList(key: String): List<String> =
    (this[key] as? List<*>)?.filterIsInstance<String>().orEmpty()

/** The document's [key] as a `{uid: epochMillis}` map, dropping entries of another shape. */
internal fun Map<String, Any>.markMap(key: String): Map<String, Long> =
    (this[key] as? Map<*, *>)
        ?.mapNotNull { (uid, mark) ->
            val id = uid as? String ?: return@mapNotNull null
            val millis = (mark as? Number)?.toLong() ?: return@mapNotNull null
            id to millis
        }
        ?.toMap()
        .orEmpty()

/** The document's [key] as a Long, or `null` when absent or not a number. */
internal fun Map<String, Any>.longOrNull(key: String): Long? = (this[key] as? Number)?.toLong()

/** The document's [key] as an ISO local date-time, or `null` when absent or unparseable. */
internal fun Map<String, Any>.dateTimeOrNull(key: String): LocalDateTime? =
    (this[key] as? String)?.let {
        runCatching { LocalDateTime.parse(it, chatDateFormatter) }.getOrNull()
    }

/**
 * A message document's send time as epoch millis, in either wire format, or `null` when it
 * carries neither.
 *
 * A **number** is epoch millis and needs no interpretation — that is what this app writes now.
 * A **string** is the naive ISO local date-time the previous format used: it carries no offset,
 * so the reading device's own zone is the best available reading of it, and the same one the
 * app applied to these documents until now.
 *
 * Both branches are needed because both exist: documents written before this change, and
 * documents a co-parent's phone is still writing while it runs an older build.
 */
internal fun Map<String, Any>.sentAtMillisOrNull(key: String): Long? = when (val value = this[key]) {
    is Number -> value.toLong()
    is String -> runCatching {
        LocalDateTime.parse(value, chatDateFormatter)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
    }.getOrNull()
    else -> null
}

/** The date-time as an ISO local date-time string, matching what the documents carry. */
internal fun LocalDateTime.toIsoString(): String = format(chatDateFormatter)

/** The larger of two nullable Longs, or whichever one is present. */
internal fun maxOfNullable(a: Long?, b: Long?): Long? = when {
    a == null -> b
    b == null -> a
    else -> maxOf(a, b)
}
