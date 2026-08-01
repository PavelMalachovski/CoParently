/**
 * Firebase Cloud Functions для CoParently
 *
 * Обрабатывает отправку push-уведомлений при создании записей в notification_queue
 */

const functions = require('firebase-functions');
const admin = require('firebase-admin');

// Инициализация Firebase Admin SDK
admin.initializeApp();

/**
 * Cloud Function для отправки push-уведомлений.
 * Триггерится при создании нового документа в коллекции notification_queue.
 *
 * Структура документа notification_queue:
 * {
 *   targetUserId: string,
 *   data: {
 *     title: string,
 *     body: string,
 *     type: string (optional),
 *     eventId: string (optional),
 *     childInfoId: string (optional)
 *   },
 *   status: 'pending' | 'sent' | 'failed',
 *   createdAt: timestamp,
 *   sentAt: timestamp (optional),
 *   error: string (optional)
 * }
 */
/**
 * Builds the FCM message for a queued notification.
 *
 * Data-only: no top-level `notification` block. A message carrying one is auto-displayed
 * by the OS from the system tray whenever the app is backgrounded or killed, and FCM never
 * calls the app's onMessageReceived in that case — so the app's own notification-building
 * code (deep links, icon, per-type notification id) would only ever run while the app
 * happens to be in the foreground. A data-only message with android.priority 'high' is
 * delivered to onMessageReceived uniformly in all three app states, so the client always
 * decides how to render it. title/body therefore live in `data`.
 *
 * FCM requires every `data` value to be a string and rejects the whole message otherwise.
 * `title` and `body` used to be copied through unconverted, which made them the only two
 * values that were never coerced: a queue document with a missing or non-string title made
 * `admin.messaging().send` throw and the push was lost. Every value is coerced here,
 * `title`/`body` included, and absent keys become '' rather than the string 'undefined'.
 *
 * @param {string} token The recipient's FCM registration token.
 * @param {Object} data The queued `data` payload.
 * @return {Object} A message ready for `admin.messaging().send`.
 */
function buildFcmMessage(token, data) {
  const payload = data || {};
  const stringified = Object.keys(payload).reduce((acc, key) => {
    acc[key] = payload[key] === null || payload[key] === undefined ?
      '' :
      String(payload[key]);
    return acc;
  }, {});

  return {
    token: token,
    data: Object.assign(
        {
          title: '',
          body: '',
          type: 'general',
          eventId: '',
          childInfoId: '',
        },
        stringified,
        // A present-but-empty type must still fall back to 'general', matching the
        // previous `notificationData.data.type || 'general'` behaviour.
        stringified.type ? {} : {type: 'general'},
    ),
    android: {
      priority: 'high',
    },
  };
}

exports.buildFcmMessage = buildFcmMessage;

exports.sendNotification = functions.firestore
    .document('notification_queue/{notificationId}')
    .onCreate(async (snap, context) => {
      const notificationId = context.params.notificationId;
      const notificationData = snap.data();

      console.log(`Processing notification ${notificationId} for user ${notificationData.targetUserId}`);

      try {
      // Получаем FCM токен целевого пользователя
        const userDoc = await admin.firestore()
            .collection('users')
            .doc(notificationData.targetUserId)
            .get();

        if (!userDoc.exists) {
          throw new Error(`User ${notificationData.targetUserId} not found`);
        }

        const userData = userDoc.data();
        const fcmToken = userData.fcmToken;

        if (!fcmToken) {
          console.log(`User ${notificationData.targetUserId} has no FCM token. Skipping notification.`);
          await snap.ref.update({
            status: 'skipped',
            error: 'No FCM token',
            sentAt: admin.firestore.FieldValue.serverTimestamp(),
          });
          return null;
        }

        const message = buildFcmMessage(fcmToken, notificationData.data);

        // Отправка уведомления
        const response = await admin.messaging().send(message);
        console.log(`Successfully sent notification ${notificationId}:`, response);

        // Обновление статуса в базе данных
        await snap.ref.update({
          status: 'sent',
          sentAt: admin.firestore.FieldValue.serverTimestamp(),
          messageId: response,
        });

        return response;
      } catch (error) {
        console.error(`Error sending notification ${notificationId}:`, error);

        // Обновление статуса с ошибкой
        await snap.ref.update({
          status: 'failed',
          error: error.message,
          sentAt: admin.firestore.FieldValue.serverTimestamp(),
        });

        // Повторная попытка для определенных ошибок
        if (error.code === 'messaging/registration-token-not-registered') {
          console.log(`FCM token for user ${notificationData.targetUserId} is invalid. Clearing token.`);
          // Очищаем недействительный токен
          await admin.firestore()
              .collection('users')
              .doc(notificationData.targetUserId)
              .update({
                fcmToken: admin.firestore.FieldValue.delete(),
              });
        }

        throw error;
      }
    });

/**
 * Cloud Function для очистки старых уведомлений.
 * Запускается каждый день в 2:00 по UTC.
 * Удаляет уведомления старше 30 дней.
 */
exports.cleanupOldNotifications = functions.pubsub
    .schedule('0 2 * * *')
    .timeZone('UTC')
    .onRun(async (context) => {
      const thirtyDaysAgo = new Date();
      thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30);

      console.log(`Cleaning up notifications older than ${thirtyDaysAgo.toISOString()}`);

      const oldNotificationsQuery = await admin.firestore()
          .collection('notification_queue')
          .where('createdAt', '<', admin.firestore.Timestamp.fromDate(thirtyDaysAgo))
          .get();

      const batch = admin.firestore().batch();
      let count = 0;

      oldNotificationsQuery.forEach((doc) => {
        batch.delete(doc.ref);
        count++;
      });

      if (count > 0) {
        await batch.commit();
        console.log(`Deleted ${count} old notifications`);
      } else {
        console.log('No old notifications to delete');
      }

      return null;
    });

/**
 * Cloud Function для отправки уведомления о новом событии.
 * Триггерится при создании нового события в коллекции events.
 */
exports.onEventCreated = functions.firestore
    .document('events/{eventId}')
    .onCreate(async (snap, context) => {
      const eventData = snap.data();
      const eventId = context.params.eventId;

      console.log(`New event created: ${eventId}`);

      // Находим партнера пользователя
      const creatorDoc = await admin.firestore()
          .collection('users')
          .doc(eventData.createdByFirebaseUid)
          .get();

      if (!creatorDoc.exists) {
        console.log('Creator not found');
        return null;
      }

      const creatorData = creatorDoc.data();
      const partnerId = creatorData.partnerId;

      if (!partnerId) {
        console.log('Creator has no partner');
        return null;
      }

      // Создаем уведомление для партнера
      await admin.firestore()
          .collection('notification_queue')
          .add({
            targetUserId: partnerId,
            data: {
              title: 'New Event Created',
              body: `${creatorData.email || 'Your partner'} created a new event: ${eventData.title}`,
              type: 'event_created',
              eventId: eventId,
            },
            status: 'pending',
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
          });

      console.log(`Notification queued for partner ${partnerId}`);
      return null;
    });

/**
 * Cloud Function для отправки уведомления об обновлении информации о ребенке.
 * Триггерится при обновлении документа в коллекции child_info.
 */
exports.onChildInfoUpdated = functions.firestore
    .document('child_info/{childInfoId}')
    .onUpdate(async (change, context) => {
      const newData = change.after.data();
      const oldData = change.before.data();
      const childInfoId = context.params.childInfoId;

      console.log(`Child info updated: ${childInfoId}`);

      // Проверяем, действительно ли изменились данные
      if (JSON.stringify(newData) === JSON.stringify(oldData)) {
        console.log('No actual changes detected');
        return null;
      }

      // Находим создателя
      const creatorDoc = await admin.firestore()
          .collection('users')
          .doc(newData.createdByFirebaseUid)
          .get();

      if (!creatorDoc.exists) {
        console.log('Creator not found');
        return null;
      }

      const creatorData = creatorDoc.data();
      const partnerId = creatorData.partnerId;

      if (!partnerId) {
        console.log('Creator has no partner');
        return null;
      }

      // Создаем уведомление для партнера
      await admin.firestore()
          .collection('notification_queue')
          .add({
            targetUserId: partnerId,
            data: {
              title: 'Child Info Updated',
              body: `${creatorData.email || 'Your partner'} updated information about ${newData.name}`,
              type: 'child_info_updated',
              childInfoId: childInfoId,
            },
            status: 'pending',
            createdAt: admin.firestore.FieldValue.serverTimestamp(),
          });

      console.log(`Notification queued for partner ${partnerId}`);
      return null;
    });

/**
 * Cloud Function for sending email invitations.
 * Triggered when a new invitation is created in Firestore.
 */
exports.sendEmailInvitation = functions.firestore
    .document('invitations/{invitationId}')
    .onCreate(async (snap, context) => {
      const invitation = snap.data();
      const invitationId = context.params.invitationId;

      console.log(`Processing email invitation ${invitationId} to ${invitation.toEmail}`);

      try {
        // Get sender's information
        const senderDoc = await admin.firestore()
            .collection('users')
            .doc(invitation.fromUserId)
            .get();

        if (!senderDoc.exists) {
          console.error('Sender not found for invitation:', invitationId);
          return null;
        }

        const senderData = senderDoc.data();

        // Create invitation acceptance URL
        const acceptUrl = `https://coparently.app/pair?invitation=${invitationId}`;

        // Email content (in production, use a proper email service like SendGrid)
        const emailContent = {
          to: invitation.toEmail,
          subject: 'Co-Parent Invitation from CoParently',
          html: `
            <div style="font-family: Arial, sans-serif; max-width: 600px; margin: 0 auto;">
              <h1 style="color: #4CAF50;">Co-Parent Invitation</h1>
              <p>Hello,</p>
              <p><strong>${senderData.name}</strong> has invited you to connect as co-parents on CoParently!</p>
              <p>CoParently helps co-parents coordinate childcare schedules, share information, and stay organized.</p>

              <div style="text-align: center; margin: 30px 0;">
                <a href="${acceptUrl}"
                   style="background-color: #4CAF50; color: white; padding: 12px 24px;
                          text-decoration: none; border-radius: 4px; display: inline-block;">
                  Accept Invitation
                </a>
              </div>

              <p>If the button doesn't work, copy and paste this link into your browser:</p>
              <p style="word-break: break-all; color: #666;">${acceptUrl}</p>

              <p>This invitation will expire in 7 days.</p>
              <p>If you didn't expect this invitation, you can safely ignore this email.</p>

              <hr style="border: none; border-top: 1px solid #eee; margin: 30px 0;">
              <p style="color: #666; font-size: 12px;">
                CoParently - Making co-parenting easier
              </p>
            </div>
          `,
        };

        // In production, integrate with email service (SendGrid, Mailgun, etc.)
        // For now, we'll log the email content
        console.log('Email invitation prepared:', {
          to: emailContent.to,
          subject: emailContent.subject,
          acceptUrl: acceptUrl,
        });

        // TODO: Replace with actual email sending service
        // Example with SendGrid:
        // const sgMail = require('@sendgrid/mail');
        // sgMail.setApiKey(process.env.SENDGRID_API_KEY);
        // await sgMail.send(emailContent);

        console.log(`Email invitation sent to ${invitation.toEmail}`);
        return null;
      } catch (error) {
        console.error('Error sending email invitation:', error);

        // Mark invitation as failed
        await snap.ref.update({
          status: 'failed',
          error: error.message,
          sentAt: admin.firestore.FieldValue.serverTimestamp(),
        });

        throw error;
      }
    });

/**
 * Accepts a pairing invitation identified either by its short code or by its
 * document id, and links the two parents.
 *
 * Runs server-side because linking writes BOTH user documents, and no Firestore
 * rule can grant a client write access to another user's profile without
 * granting it for every user.
 *
 * @param {{code?: string, invitationId?: string}} data Exactly one identifier.
 * @return {Promise<{partnerId: string}>} The UID the caller is now paired with.
 */
exports.acceptPairingInvitation = functions.https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError('unauthenticated', 'Sign in first');
  }

  const code = data && data.code ? String(data.code).trim().toUpperCase() : null;
  const invitationId = data && data.invitationId ? String(data.invitationId) : null;

  if ((!code && !invitationId) || (code && invitationId)) {
    throw new functions.https.HttpsError(
        'invalid-argument',
        'Provide exactly one of code or invitationId',
    );
  }

  const db = admin.firestore();
  const acceptingUserId = context.auth.uid;
  const acceptingEmail = context.auth.token.email || '';

  const inviteRef = await findInvitation(db, {code, invitationId});
  const invite = (await inviteRef.get()).data();

  if (invite.status !== 'pending') {
    throw new functions.https.HttpsError(
        'failed-precondition', 'Invitation is no longer pending',
        {reason: 'invitation-not-pending'});
  }
  if (typeof invite.expiresAt === 'number' && invite.expiresAt < Date.now()) {
    throw new functions.https.HttpsError(
        'failed-precondition', 'Invitation has expired',
        {reason: 'invitation-expired'});
  }
  if (invite.fromUserId === acceptingUserId) {
    throw new functions.https.HttpsError(
        'invalid-argument', 'You cannot accept your own invitation',
        {reason: 'self-pairing'});
  }
  if (invite.toEmail && invite.toEmail !== acceptingEmail) {
    throw new functions.https.HttpsError(
        'permission-denied', 'This invitation is addressed to somebody else',
        {reason: 'wrong-recipient'});
  }

  const inviterRef = db.collection('users').doc(invite.fromUserId);
  const accepterRef = db.collection('users').doc(acceptingUserId);
  const pairedAt = Date.now();

  await db.runTransaction(async (tx) => {
    const [inviterSnap, accepterSnap] = await Promise.all([
      tx.get(inviterRef), tx.get(accepterRef),
    ]);
    if (!inviterSnap.exists || !accepterSnap.exists) {
      throw new functions.https.HttpsError(
          'not-found', 'User profile missing', {reason: 'not-found'});
    }
    if (hasPartner(inviterSnap) || hasPartner(accepterSnap)) {
      throw new functions.https.HttpsError(
          'failed-precondition', 'One of the accounts is already paired',
          {reason: 'already-paired'});
    }
    tx.update(inviterRef, {partnerId: acceptingUserId, pairedAt});
    tx.update(accepterRef, {partnerId: invite.fromUserId, pairedAt});
    tx.update(inviteRef, {
      status: 'accepted',
      acceptedBy: acceptingUserId,
      acceptedAt: pairedAt,
    });
  });

  const accepterName = (await accepterRef.get()).data().name || 'Your co-parent';
  await db.collection('notification_queue').add({
    targetUserId: invite.fromUserId,
    data: {
      type: 'pairing_accepted',
      title: 'Invitation accepted',
      body: `${accepterName} is now your co-parent in CoPlanly`,
    },
    status: 'pending',
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
  });

  return {partnerId: invite.fromUserId};
});

/**
 * Collections whose visibility is a per-document `sharedWith` audience.
 *
 * These are the only two: `expenses` and `budgets` are gated on the *live* `isPartnerOf`
 * relationship rather than a stored list, so clearing `partnerId` already revokes them.
 * `conversations` membership is deliberately immutable — whether an ended co-parent link
 * should also erase the chat history is a product decision, not a leak to close here.
 */
const SHARED_AUDIENCE_COLLECTIONS = ['events', 'child_info'];

/** Firestore caps a batched write at 500 operations; stay clear of the edge. */
const REVOCATION_BATCH_LIMIT = 400;

/**
 * Removes each of two former co-parents from the other's per-document `sharedWith` lists.
 *
 * `EventRepositoryImpl` and `SyncService` only ever *widen* `sharedWith`, so without this
 * an ex-partner stayed in the audience of every event ever shared with them. Because
 * `Event.permissions` defaults to `read_write`, the `events` update rule kept admitting
 * them indefinitely — including on edits made long after the link ended. In an app for
 * separated parents, unpair has to actually revoke something.
 *
 * Revocation is symmetric: it runs in both directions, so neither parent keeps access to
 * documents the other created. Anything else would be a trap, since the person pressing
 * unpair is usually the one who needs the boundary and has no way to ask the other side
 * to press it too.
 *
 * A uid is never removed from a document it created. `sharedWith` is what
 * `FirestoreEventDataSource.observeEventsSharedWith` and
 * `FirestoreChildInfoDataSource.getChildInfoForParent` query on, so dropping the creator
 * would hide the document from the parent it belongs to.
 *
 * Runs with Admin credentials, which is why this belongs on the server: a client sweep
 * would only ever run on the device that pressed unpair, would be blocked by the `events`
 * update rule on any document shared `read_only`, and would silently do nothing at all if
 * that device were offline or the app uninstalled.
 *
 * @param {FirebaseFirestore.Firestore} db Firestore instance.
 * @param {string} uidA One former co-parent.
 * @param {string} uidB The other former co-parent.
 * @return {Promise<number>} How many documents were narrowed.
 */
async function revokeSharedAudience(db, uidA, uidB) {
  let revoked = 0;

  for (const collection of SHARED_AUDIENCE_COLLECTIONS) {
    for (const [reader, removed] of [[uidA, uidB], [uidB, uidA]]) {
      const snap = await db.collection(collection)
          .where('sharedWith', 'array-contains', reader)
          .get();

      let batch = db.batch();
      let pending = 0;

      for (const doc of snap.docs) {
        const docData = doc.data();
        if (docData.createdByFirebaseUid === removed) {
          continue;
        }
        if (!(docData.sharedWith || []).includes(removed)) {
          continue;
        }

        batch.update(doc.ref, {
          sharedWith: admin.firestore.FieldValue.arrayRemove(removed),
        });
        pending++;
        revoked++;

        if (pending === REVOCATION_BATCH_LIMIT) {
          await batch.commit();
          batch = db.batch();
          pending = 0;
        }
      }

      if (pending > 0) {
        await batch.commit();
      }
    }
  }

  return revoked;
}

exports.revokeSharedAudience = revokeSharedAudience;

/**
 * Removes the link between the caller and their co-parent, and revokes the access that
 * link handed out.
 *
 * One-sided by product decision: no confirmation from the other parent is
 * required. Chat history and expenses are left as they are — see
 * [SHARED_AUDIENCE_COLLECTIONS] for why.
 *
 * The decision of who to unlink is made and re-verified entirely inside the
 * transaction (both docs are read via `tx.get`, never via a plain `get()`
 * beforehand). Without that, a concurrent unpair/re-pair on the other side
 * between this call's start and its commit could make this transaction blindly
 * clear a partnerId the caller is no longer actually linked to.
 *
 * The audience sweep runs *after* the transaction, because it can touch an unbounded
 * number of documents and a Firestore transaction cannot. That leaves a window where the
 * link is gone but some documents still list the ex-partner, so the transaction records
 * `pendingRevocationOf` on the caller and the sweep clears it only once it finishes. A
 * partial failure therefore leaves: the link broken on both sides (the safety-critical
 * half, and it is what the `expenses`/`budgets`/`notification_queue` rules gate on), some
 * prefix of the documents narrowed, the rest still listing the ex-partner, and a marker
 * that makes the next call resume the sweep. The call itself fails rather than reporting
 * a success it did not achieve, so the user is told to retry instead of being left
 * believing the boundary is in place.
 *
 * @return {Promise<{unpairedFrom: string|null, revokedDocuments: number}>} The former
 *   partner's UID and how many documents the sweep narrowed.
 */
exports.unpairCoParent = functions.https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError('unauthenticated', 'Sign in first');
  }

  const db = admin.firestore();
  const callerRef = db.collection('users').doc(context.auth.uid);

  const result = await db.runTransaction(async (tx) => {
    const callerSnap = await tx.get(callerRef);
    const callerData = callerSnap.exists ? callerSnap.data() : {};
    const partnerId = callerData.partnerId || null;
    // Set by an earlier call whose sweep did not finish. Resuming it is the only way
    // those documents ever get narrowed: once partnerId is cleared, nothing else
    // remembers who the ex-partner was.
    const unfinished = callerData.pendingRevocationOf || null;

    if (!partnerId) {
      return {unpairedFrom: null, revokeFrom: unfinished};
    }

    const partnerRef = db.collection('users').doc(partnerId);
    const partnerSnap = await tx.get(partnerRef);

    // Re-verify the link is still mutually intact before clearing it. If the
    // partner has already unpaired or re-paired with someone else, the link
    // this call was asked to remove is already gone from their side — but the
    // caller is still pointing at them, so clear the caller's own half.
    // Returning without doing so left anyone whose ex deleted their account
    // permanently "paired", with no way out: the unpair button would keep
    // succeeding and keep changing nothing.
    if (!partnerSnap.exists || partnerSnap.data().partnerId !== context.auth.uid) {
      tx.update(callerRef, {
        partnerId: '', pairedAt: null, pendingRevocationOf: partnerId,
      });
      // No notification: there is no intact link, and the other side either does
      // not exist or is already paired with somebody else. The sweep still runs —
      // a half-torn link leaves the shared documents just as exposed.
      return {unpairedFrom: null, revokeFrom: partnerId};
    }

    tx.update(callerRef, {
      partnerId: '', pairedAt: null, pendingRevocationOf: partnerId,
    });
    tx.update(partnerRef, {partnerId: '', pairedAt: null});

    return {
      unpairedFrom: partnerId,
      callerName: callerData.name || 'Your co-parent',
      revokeFrom: partnerId,
    };
  });

  let revokedDocuments = 0;
  if (result.revokeFrom) {
    try {
      revokedDocuments = await revokeSharedAudience(
          db, context.auth.uid, result.revokeFrom);
      await callerRef.update({
        pendingRevocationOf: admin.firestore.FieldValue.delete(),
      });
    } catch (err) {
      console.error(
          `Shared-audience revocation failed for ${context.auth.uid}`, err);
      throw new functions.https.HttpsError(
          'internal',
          'Unpaired, but shared access was not fully revoked. Please try again.',
          {reason: 'revocation-incomplete'});
    }
  }

  if (result.unpairedFrom) {
    await db.collection('notification_queue').add({
      targetUserId: result.unpairedFrom,
      data: {
        type: 'pairing_removed',
        title: 'Co-parent unlinked',
        body: `${result.callerName} ended the co-parent link`,
      },
      status: 'pending',
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    });
  }

  return {unpairedFrom: result.unpairedFrom, revokedDocuments: revokedDocuments};
});

/**
 * Resolves an invitation reference from a code or a document id.
 *
 * @param {FirebaseFirestore.Firestore} db Firestore instance.
 * @param {{code: ?string, invitationId: ?string}} ref Identifier.
 * @return {Promise<FirebaseFirestore.DocumentReference>} The invitation.
 */
async function findInvitation(db, ref) {
  if (ref.invitationId) {
    const doc = await db.collection('invitations').doc(ref.invitationId).get();
    if (!doc.exists) {
      throw new functions.https.HttpsError(
          'not-found', 'Invitation not found', {reason: 'not-found'});
    }
    return doc.ref;
  }

  const query = await db.collection('invitations')
      .where('code', '==', ref.code)
      .where('status', '==', 'pending')
      .limit(2)
      .get();

  if (query.size !== 1) {
    throw new functions.https.HttpsError(
        'not-found', 'Invitation not found', {reason: 'not-found'});
  }
  return query.docs[0].ref;
}

/**
 * Whether a user snapshot already carries a co-parent link.
 *
 * @param {FirebaseFirestore.DocumentSnapshot} snap User document.
 * @return {boolean} True when partnerId is set and non-empty.
 */
function hasPartner(snap) {
  const partnerId = snap.data().partnerId;
  return typeof partnerId === 'string' && partnerId.length > 0;
}
