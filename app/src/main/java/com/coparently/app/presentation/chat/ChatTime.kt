package com.coparently.app.presentation.chat

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * A message's send time as text, in the reading device's own timezone.
 *
 * `Message.sentAtMillis` is an instant and deliberately carries no zone, so that two parents in
 * different timezones agree on when a message was sent. The zone belongs here instead, at
 * render time: each parent should read a message in their own local time, whatever the clock on
 * the phone that sent it said.
 *
 * @param sentAtMillis The message's send time, epoch millis.
 * @param formatter How to render it, e.g. `HH:mm`.
 */
internal fun formatSentAt(sentAtMillis: Long, formatter: DateTimeFormatter): String =
    formatter.format(Instant.ofEpochMilli(sentAtMillis).atZone(ZoneId.systemDefault()))
