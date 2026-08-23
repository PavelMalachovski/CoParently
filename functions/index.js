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
 * Body of the `acceptPairingInvitation` callable. Takes `db` as a parameter for the same
 * reason `unpairCoParentImpl` does: it is the only way to exercise the transaction and the
 * returned slot without a live Firestore.
 *
 * @param {FirebaseFirestore.Firestore} db Firestore instance.
 * @param {string} acceptingUserId The signed-in caller's UID.
 * @param {string} acceptingEmail The signed-in caller's email, or ''.
 * @param {{code: ?string, invitationId: ?string}} ref Exactly one identifier.
 * @return {Promise<{partnerId: string, role: string}>} The UID the caller is now paired
 *   with, and the parent slot (`assignSlots`) this device was just assigned — the client
 *   compares it to its own last-known slot to decide whether records created before pairing
 *   need re-stamping (see `ParentSlotMigrator` on the Android side).
 */
async function acceptPairingInvitationImpl(db, acceptingUserId, acceptingEmail, ref) {
  const inviteRef = await findInvitation(db, ref);
  const invite = (await inviteRef.get()).data();

  // The dangerous direction of the two-callable split. A guest invitation redeemed here
  // would run `assignSlots` and write `partnerId` on both users, turning a grandmother into
  // a co-parent with a parent colour and a full view of the family. Refused outright rather
  // than downgraded: the client already knows which kind of code it holds in the ordinary
  // case, and the reason below tells it when it does not.
  if (invite.kind === GUEST_INVITATION) {
    throw new functions.https.HttpsError(
        'failed-precondition', 'This is a guest invitation, not a co-parent invitation',
        {reason: 'guest-invitation'});
  }
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

  // Hoisted out of the transaction closure so it is still in scope for the return
  // statement below — the client needs the accepter's new slot to know whether its own
  // records need re-stamping.
  let slots;

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
    slots = assignSlots(inviterSnap.data().role);
    tx.update(inviterRef, {
      partnerId: acceptingUserId, pairedAt, role: slots.inviterRole,
    });
    tx.update(accepterRef, {
      partnerId: invite.fromUserId, pairedAt, role: slots.accepterRole,
    });
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

  return {partnerId: invite.fromUserId, role: slots.accepterRole};
}

exports.acceptPairingInvitationImpl = acceptPairingInvitationImpl;

/**
 * Accepts a pairing invitation identified either by its short code or by its
 * document id, and links the two parents.
 *
 * Runs server-side because linking writes BOTH user documents, and no Firestore
 * rule can grant a client write access to another user's profile without
 * granting it for every user.
 *
 * @param {{code?: string, invitationId?: string}} data Exactly one identifier.
 * @return {Promise<{partnerId: string, role: string}>} See [acceptPairingInvitationImpl].
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

  return acceptPairingInvitationImpl(
      admin.firestore(), context.auth.uid, context.auth.token.email || '', {code, invitationId});
});

/**
 * The `kind` marking an invitation as a guest invitation rather than a co-parent one.
 *
 * Absent means co-parent: every invitation written before guests existed carries no `kind`
 * at all, and those must keep pairing. So the guest path tests for this value explicitly and
 * the pairing path refuses only this value — neither treats "unrecognised" as its own.
 */
const GUEST_INVITATION = 'guest';
exports.GUEST_INVITATION = GUEST_INVITATION;

/**
 * Body of the `acceptGuestInvitation` callable — lets somebody read one child's record
 * without becoming a parent.
 *
 * **A deliberate second function rather than a branch in `acceptPairingInvitationImpl`.**
 * That one assigns parent slots and writes `partnerId` on two user documents; a `kind`
 * branch inside it is a mistake waiting for a tired evening, and the mistake's outcome is a
 * grandmother holding a parent slot and reading every event, expense and message in the
 * family. Two functions cannot be confused by accident, and `pairing-guard` above makes the
 * refusal explicit in the other direction too.
 *
 * What this writes, and nothing else: one entry in the child record's `guests` map, and the
 * guest's uid appended to that record's `sharedWith`. No user document is touched at all.
 *
 * The grant's end comes from the invitation (`guestExpiresAt`, epoch millis, chosen by the
 * parent when they made it) and must still be in the future — an invitation redeemed after
 * its window elapsed grants nothing. There is deliberately no fallback duration here: the
 * one default this feature must never have is "forever", and a server that quietly supplies
 * thirty days for a malformed invitation is a server that would also supply them for a
 * `guestExpiresAt` some future change forgets to write.
 *
 * @param {FirebaseFirestore.Firestore} db Firestore instance.
 * @param {string} acceptingUserId The signed-in caller's UID.
 * @param {string} acceptingEmail The signed-in caller's email, or ''.
 * @param {{code: ?string, invitationId: ?string}} ref Exactly one identifier.
 * @return {Promise<{childInfoId: string, expiresAtMillis: number}>} The record the caller may
 *   now read, and the instant their access ends.
 */
async function acceptGuestInvitationImpl(db, acceptingUserId, acceptingEmail, ref) {
  const inviteRef = await findInvitation(db, ref);
  const invite = (await inviteRef.get()).data();

  if (invite.kind !== GUEST_INVITATION) {
    throw new functions.https.HttpsError(
        'failed-precondition', 'This is not a guest invitation',
        {reason: 'not-a-guest-invitation'});
  }
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

  const childInfoId = typeof invite.childInfoId === 'string' ? invite.childInfoId : '';
  if (!childInfoId) {
    throw new functions.https.HttpsError(
        'failed-precondition', 'Invitation names no child record',
        {reason: 'invitation-malformed'});
  }
  const expiresAtMillis = typeof invite.guestExpiresAt === 'number' ? invite.guestExpiresAt : 0;
  if (expiresAtMillis <= Date.now()) {
    throw new functions.https.HttpsError(
        'failed-precondition', 'This guest access has already ended',
        {reason: 'grant-expired'});
  }

  const childRef = db.collection('child_info').doc(childInfoId);
  const accepterRef = db.collection('users').doc(acceptingUserId);
  const grantedAtMillis = Date.now();

  await db.runTransaction(async (tx) => {
    const [childSnap, inviteSnap] = await Promise.all([tx.get(childRef), tx.get(inviteRef)]);
    if (!childSnap.exists) {
      throw new functions.https.HttpsError(
          'not-found', 'Child record not found', {reason: 'not-found'});
    }
    // Re-read inside the transaction: two devices redeeming the same code at once would
    // otherwise both pass the check above and the second grant would overwrite the first,
    // silently moving somebody else's expiry.
    if (inviteSnap.data().status !== 'pending') {
      throw new functions.https.HttpsError(
          'failed-precondition', 'Invitation is no longer pending',
          {reason: 'invitation-not-pending'});
    }

    const child = childSnap.data();
    const sharedWith = Array.isArray(child.sharedWith) ? child.sharedWith : [];
    const guests = child.guests && typeof child.guests === 'object' ? child.guests : {};

    // The inviter must hold the record as a **parent**. Membership of `sharedWith` alone is
    // not enough: a guest is in it too, and a guest who can invite another guest is how a
    // thirty-day grant becomes permanent — each hand-off restarts the clock and no parent
    // ever sees who is really reading. Nothing stops a client writing an invitation that
    // names somebody else's child id either; the rules shape that document, and this is what
    // stops it meaning anything.
    if (sharedWith.indexOf(invite.fromUserId) < 0 ||
        Object.prototype.hasOwnProperty.call(guests, invite.fromUserId)) {
      throw new functions.https.HttpsError(
          'permission-denied', 'The inviter cannot grant access to this record',
          {reason: 'inviter-not-entitled'});
    }

    tx.update(childRef, {
      // Written whole rather than through a `guests.<uid>` field path so the read and the
      // write are the same transaction's view of the map — a dotted update would be a blind
      // write over whatever a concurrent revoke had just done.
      guests: Object.assign({}, guests, {
        [acceptingUserId]: {
          name: await guestName(accepterRef, acceptingEmail),
          grantedBy: invite.fromUserId,
          grantedAtMillis,
          expiresAtMillis,
        },
      }),
      sharedWith: sharedWith.indexOf(acceptingUserId) < 0 ?
        sharedWith.concat([acceptingUserId]) : sharedWith,
    });
    tx.update(inviteRef, {
      status: 'accepted',
      acceptedBy: acceptingUserId,
      acceptedAt: grantedAtMillis,
    });
  });

  await db.collection('notification_queue').add({
    targetUserId: invite.fromUserId,
    data: {
      type: 'guest_accepted',
      title: 'Guest access accepted',
      body: `${await guestName(accepterRef, acceptingEmail)} can now see this child's record`,
    },
    status: 'pending',
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
  });

  return {childInfoId, expiresAtMillis};
}

exports.acceptGuestInvitationImpl = acceptGuestInvitationImpl;

/**
 * A display name for the guest, never blank.
 *
 * `ChildInfoGuests.decode` on the Android side **drops a grant that has no name**, so a
 * blank here would be a guest the rules keep serving and the parent's screen cannot show —
 * access nobody can see to revoke. The email is the honest second choice; the constant is
 * the third only because a Firebase account with neither is possible (phone sign-in).
 *
 * @param {FirebaseFirestore.DocumentReference} accepterRef The guest's user document.
 * @param {string} acceptingEmail The signed-in caller's email, or ''.
 * @return {Promise<string>} A non-empty name.
 */
async function guestName(accepterRef, acceptingEmail) {
  const snap = await accepterRef.get();
  const stored = snap.exists && snap.data() ? snap.data().name : '';
  return (typeof stored === 'string' && stored.trim()) || acceptingEmail || 'Guest';
}

exports.guestName = guestName;

/**
 * Redeems a guest invitation identified either by its short code or by its document id.
 *
 * Runs server-side for the same reason the pairing callable does: it writes a child record
 * the caller cannot yet read, let alone write. The `child_info` update rule requires the
 * writer to already be in `sharedWith`, which the guest is not until this has run.
 *
 * @param {{code?: string, invitationId?: string}} data Exactly one identifier.
 * @return {Promise<{childInfoId: string, expiresAtMillis: number}>} See
 *   [acceptGuestInvitationImpl].
 */
exports.acceptGuestInvitation = functions.https.onCall(async (data, context) => {
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

  return acceptGuestInvitationImpl(
      admin.firestore(), context.auth.uid, context.auth.token.email || '', {code, invitationId});
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
 * Reads `pendingRevocationOf` as a list, whatever shape it is stored in.
 *
 * The field shipped as a bare UID string and is an array from this version on, so live
 * `users` documents carry both shapes. Anything unrecognised yields an empty list rather
 * than throwing: a malformed marker must not make unpair itself impossible.
 *
 * @param {*} raw The stored field value.
 * @return {!Array<string>} The ex-partner UIDs still awaiting a sweep.
 */
function pendingRevocations(raw) {
  const list = Array.isArray(raw) ? raw : [raw];
  return list.filter((uid) => typeof uid === 'string' && uid.length > 0);
}

exports.pendingRevocations = pendingRevocations;

/**
 * The id of the one custody document a pair shares: the two UIDs sorted and joined with
 * `__`. Matches `firestore-tests/rules/custody-models.test.js` and the Android
 * `FirestoreCustodyDataSource`/`CustodyKey`, which derive the same id from the same rule.
 *
 * @param {string} uidA One participant.
 * @param {string} uidB The other participant.
 * @return {string} The shared document id.
 */
function custodyModelKey(uidA, uidB) {
  return [uidA, uidB].sort().join('__');
}

exports.custodyModelKey = custodyModelKey;

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
 * `pendingRevocationOf` on the caller and the sweep clears each entry only once that
 * entry finishes. A partial failure therefore leaves: the link broken on both sides (the
 * safety-critical half, and it is what the `expenses`/`budgets`/`notification_queue` rules
 * gate on), some prefix of the documents narrowed, the rest still listing the ex-partner,
 * and a marker that makes the next call resume the sweep. The call itself fails rather
 * than reporting a success it did not achieve, so the user is told to retry instead of
 * being left believing the boundary is in place.
 *
 * `pendingRevocationOf` **accumulates** rather than being overwritten. It used to hold a
 * single UID, so a failed sweep followed by a re-pair and a second unpair silently
 * discarded the first ex-partner's marker — and since `partnerId` was long since cleared,
 * nothing anywhere remembered whose access still had to be revoked, leaving those
 * documents exposed permanently.
 *
 * The pair's shared `custody_models/{uidA}__{uidB}` document is deleted inside the same
 * transaction that clears `partnerId`. Both parents keep their own local Room copy of the
 * schedule — only the one Firestore document they shared goes, mirroring the delete
 * `firestore-tests/rules/custody-models.test.js` already lets a participant perform. The
 * delete runs whichever branch below fires, including the half-torn-link one: if the other
 * side has already re-paired with someone new, the document at this pair's old key is stale
 * either way and nothing will ever read it through the rule's live-pairing gate again.
 *
 * The `pairing_removed` notification is queued *before* the sweep, not after. Queued
 * afterwards it was lost on exactly the path that needs it: the sweep threw, and on the
 * retry `unpairedFrom` was already null because the link was gone, so neither attempt ever
 * told the ex-partner the link had ended. Queuing first means it goes out exactly once —
 * on the single attempt that actually tore the link down — and it is truthful at that
 * point, because the transaction has already committed. A failure to enqueue is logged and
 * swallowed: an undelivered notice must not abort the revocation behind it.
 *
 * Takes `db` as a parameter for the same reason [revokeSharedAudience] is exported: it is
 * the only way to exercise the transaction, the marker bookkeeping and the notification
 * ordering without a live Firestore.
 *
 * @param {FirebaseFirestore.Firestore} db Firestore instance.
 * @param {string} callerUid The signed-in caller's UID.
 * @return {Promise<Object>} `{unpairedFrom, revokedDocuments}`: the former partner's UID
 *   (null when no intact link was torn down) and how many documents the sweep narrowed.
 */
async function unpairCoParentImpl(db, callerUid) {
  const callerRef = db.collection('users').doc(callerUid);

  const result = await db.runTransaction(async (tx) => {
    const callerSnap = await tx.get(callerRef);
    const callerData = callerSnap.exists ? callerSnap.data() : {};
    const partnerId = callerData.partnerId || null;
    // Set by earlier calls whose sweeps did not finish. Resuming them is the only way
    // those documents ever get narrowed: once partnerId is cleared, nothing else
    // remembers who the ex-partner was.
    const unfinished = pendingRevocations(callerData.pendingRevocationOf);

    if (!partnerId) {
      return {unpairedFrom: null, revokeFrom: unfinished};
    }

    // Accumulate: never drop an ex-partner an earlier sweep failed to reach.
    const revokeFrom = unfinished.includes(partnerId) ?
      unfinished : unfinished.concat([partnerId]);

    const partnerRef = db.collection('users').doc(partnerId);
    const partnerSnap = await tx.get(partnerRef);

    // The shared custody document belongs to this pair specifically; it goes regardless
    // of which branch below runs.
    tx.delete(db.collection('custody_models').doc(custodyModelKey(callerUid, partnerId)));

    // Re-verify the link is still mutually intact before clearing it. If the
    // partner has already unpaired or re-paired with someone else, the link
    // this call was asked to remove is already gone from their side — but the
    // caller is still pointing at them, so clear the caller's own half.
    // Returning without doing so left anyone whose ex deleted their account
    // permanently "paired", with no way out: the unpair button would keep
    // succeeding and keep changing nothing.
    if (!partnerSnap.exists || partnerSnap.data().partnerId !== callerUid) {
      tx.update(callerRef, {
        partnerId: '', pairedAt: null, pendingRevocationOf: revokeFrom,
      });
      // No notification: there is no intact link, and the other side either does
      // not exist or is already paired with somebody else. The sweep still runs —
      // a half-torn link leaves the shared documents just as exposed.
      return {unpairedFrom: null, revokeFrom: revokeFrom};
    }

    tx.update(callerRef, {
      partnerId: '', pairedAt: null, pendingRevocationOf: revokeFrom,
    });
    tx.update(partnerRef, {partnerId: '', pairedAt: null});

    return {
      unpairedFrom: partnerId,
      callerName: callerData.name || 'Your co-parent',
      revokeFrom: revokeFrom,
    };
  });

  // Queued before the sweep: the transaction has committed, so the link really is gone,
  // and this is the only attempt on which `unpairedFrom` is non-null. See the block
  // comment above for why queuing it after the sweep lost it altogether.
  if (result.unpairedFrom) {
    try {
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
    } catch (err) {
      console.error(
          `Unpair notification could not be queued for ${result.unpairedFrom}`, err);
    }
  }

  let revokedDocuments = 0;
  let remaining = result.revokeFrom.slice();
  for (const exPartnerId of result.revokeFrom) {
    try {
      revokedDocuments += await revokeSharedAudience(db, callerUid, exPartnerId);
      remaining = remaining.filter((uid) => uid !== exPartnerId);
      // Clear this entry as soon as its own sweep is done, so a later failure cannot
      // undo the bookkeeping for the ones that already finished.
      await callerRef.update({
        pendingRevocationOf: remaining.length > 0 ?
          remaining : admin.firestore.FieldValue.delete(),
      });
    } catch (err) {
      console.error(`Shared-audience revocation failed for ${callerUid}`, err);
      throw new functions.https.HttpsError(
          'internal',
          'Unpaired, but shared access was not fully revoked. Please try again.',
          {reason: 'revocation-incomplete'});
    }
  }

  return {unpairedFrom: result.unpairedFrom, revokedDocuments: revokedDocuments};
}

exports.unpairCoParentImpl = unpairCoParentImpl;

exports.unpairCoParent = functions.https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError('unauthenticated', 'Sign in first');
  }
  return unpairCoParentImpl(admin.firestore(), context.auth.uid);
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

/**
 * The two parent slots after pairing.
 *
 * "mom" and "dad" are slot identifiers, not roles: no user picks them and no screen shows
 * them. What matters is only that the two parents end up in different slots, so custody,
 * event ownership and parent colours can tell them apart. The inviter keeps whatever slot
 * they already had — their existing events are stamped with it — and the accepter takes the
 * other one, which is why the accepter's device has re-stamping to do (ParentSlotMigrator).
 *
 * The accepter's own stored slot never factors in: their slot is always the strict
 * inverse of the inviter's, whatever value they currently carry.
 *
 * @param {string|undefined} inviterRole Slot stored on the inviter, if any.
 * @return {{inviterRole: string, accepterRole: string}} The slots to write.
 */
function assignSlots(inviterRole) {
  const inviter = inviterRole === 'dad' ? 'dad' : 'mom';
  return {inviterRole: inviter, accepterRole: inviter === 'mom' ? 'dad' : 'mom'};
}
exports.assignSlots = assignSlots;

/**
 * The UIDs allowed to invoke `backfillParentSlots`.
 *
 * Read fresh from `process.env.BACKFILL_ADMIN_UIDS` on every call rather than cached at
 * module load, so tests can set it per-case. A re-slotting migration is not a user-facing
 * feature — "is authenticated" is not a strong enough gate for a callable that rewrites
 * someone else's `role` field — so the caller's uid must appear on this list.
 *
 * Populated at deploy time from `functions/.env` (see "Admin operations" in
 * `functions/README.md`), which the Firebase CLI loads into `process.env` for every
 * function — 1st and 2nd gen alike — with no extra binding on the function itself. That is
 * deliberately not a Secret Manager secret: a secret additionally requires
 * `functions.runWith({secrets: [...]})` on the callable that reads it, and without that
 * binding the value never reaches `process.env` at runtime even though `firebase
 * functions:secrets:set` reports success — which would leave this gate impossible to open by
 * the very route that looks like it should open it.
 *
 * @return {!Array<string>} The operator UIDs, or an empty list if none are configured.
 */
function backfillAdminUids() {
  const raw = process.env.BACKFILL_ADMIN_UIDS || '';
  return raw.split(',').map((uid) => uid.trim()).filter((uid) => uid.length > 0);
}

exports.backfillAdminUids = backfillAdminUids;

/**
 * Whether an `onCall` context belongs to an allow-listed backfill operator.
 *
 * Split out from `backfillParentSlots` itself so the gate — the allow-list check — can be
 * exercised directly for the uid it is supposed to *admit*, not only for the ones it
 * refuses. Testing only the refusal path leaves a gate that is broken **closed** (the wrong
 * env var name, an inverted condition) invisible: every "refuses ..." case would still pass,
 * since none of them ever supplies a uid the code is supposed to let through.
 *
 * @param {?{auth: ?{uid: string}}} context The `onCall` context.
 * @return {boolean} Whether the caller may invoke the backfill.
 */
function isBackfillOperator(context) {
  return Boolean(context && context.auth && backfillAdminUids().includes(context.auth.uid));
}

exports.isBackfillOperator = isBackfillOperator;

/**
 * A stored parent-slot value, normalized the same way `assignSlots` normalizes one: anything
 * other than `'dad'` is `'mom'`.
 *
 * Exists so "does this pair already hold two distinct slots" can be decided without
 * re-deriving that rule. Comparing two stored `role` values with plain `!==` would call a
 * pair "already separated" the moment one side is a stale or invalid value — `undefined`,
 * `''`, a typo — that happens to differ textually from `'mom'`, even though `assignSlots`
 * would treat both as the same slot.
 *
 * @param {string|undefined} role A stored slot value.
 * @return {string} `'dad'` or `'mom'`.
 */
function normalizedSlot(role) {
  return assignSlots(role).inviterRole;
}

exports.normalizedSlot = normalizedSlot;

/**
 * Body of the `backfillParentSlots` callable — re-slots pairs that were created before
 * pairing started assigning distinct slots.
 *
 * Every pair created before this feature has both parents stamped `"mom"`, because
 * `DEFAULT_ROLE` gave everyone that value and pairing never changed it. Afterwards the two
 * user documents are symmetric — nothing on either one says who actually accepted — so this
 * reads `acceptedBy` off the invitation that created the pair, which is the one record that
 * still remembers.
 *
 * Every invitation returned by the query counts toward `scanned`, even the ones this then
 * skips — a migration whose only report is a bare "ok" cannot be checked afterwards, and
 * that is doubly true for the invitations lacking `acceptedBy`: those name the pairs the spec
 * calls *permanently* unrepairable, so how many of them exist is exactly what an operator
 * needs to see, not a count silently folded into "0 scanned, 0 skipped".
 *
 * Skipped, not guessed at, when:
 * - the invitation carries no `acceptedBy` (never accepted, or accepted before that field
 *   existed) — `skippedReasons.noAccepter`;
 * - either user document is missing (the account was deleted) —
 *   `skippedReasons.missingAccount`;
 * - the two users exist but are not *currently* mutually paired with each other — an
 *   invitation accepted and later unpaired, or re-paired with someone else, must not re-slot
 *   two people who are no longer, or never were, each other's live co-parent —
 *   `skippedReasons.notPaired`;
 * - the inviter and accepter already hold the same normalized slot as each other is false —
 *   i.e. they already hold different slots — `skippedReasons.alreadySeparated`. Running this
 *   twice must look like running it once, and a pair a previous run (or a manual fix) already
 *   separated must not be flipped back.
 *
 * A pair with no surviving invitation at all is never seen by this function in the first
 * place, which is the correct outcome: guessing which parent to move would risk re-stamping
 * the wrong person's events, so those pairs are left indistinct until one of them re-saves.
 *
 * A failure processing one invitation — a malformed document (e.g. a missing
 * `fromUserId`, which makes `db.collection('users').doc(...)` throw synchronously) or a
 * transient Firestore error — is caught per-invitation and counted under `failed` rather than
 * aborting the run: a migration with no undo must not discard the outcome for every pair it
 * already reasoned about, or already wrote, because one later pair was broken.
 *
 * Reuses `assignSlots(inviterRole)` rather than re-deriving the two slots, so this and the
 * accept path can never disagree about what "separated" means.
 *
 * @param {FirebaseFirestore.Firestore} db Firestore instance.
 * @return {Promise<{scanned: number, updated: number, skipped: number, failed: number,
 *   skippedReasons: {noAccepter: number, missingAccount: number, notPaired: number,
 *   alreadySeparated: number}}>} What the migration did: how many accepted invitations were
 *   examined, how many pairs were re-slotted, and how many were skipped or failed, each
 *   broken down by reason.
 */
async function backfillParentSlotsImpl(db) {
  const summary = {
    scanned: 0,
    updated: 0,
    skipped: 0,
    failed: 0,
    skippedReasons: {
      noAccepter: 0,
      missingAccount: 0,
      notPaired: 0,
      alreadySeparated: 0,
    },
  };

  const acceptedInvitations = await db.collection('invitations')
      .where('status', '==', 'accepted')
      .get();

  for (const doc of acceptedInvitations.docs) {
    summary.scanned++;
    const invite = doc.data();

    if (!invite.acceptedBy) {
      summary.skipped++;
      summary.skippedReasons.noAccepter++;
      continue;
    }

    try {
      const inviterId = invite.fromUserId;
      const accepterId = invite.acceptedBy;
      const inviterRef = db.collection('users').doc(inviterId);
      const accepterRef = db.collection('users').doc(accepterId);
      const [inviterSnap, accepterSnap] = await Promise.all([
        inviterRef.get(), accepterRef.get(),
      ]);

      if (!inviterSnap.exists || !accepterSnap.exists) {
        summary.skipped++;
        summary.skippedReasons.missingAccount++;
        continue;
      }

      const inviterData = inviterSnap.data();
      const accepterData = accepterSnap.data();
      const stillPaired = inviterData.partnerId === accepterId &&
        accepterData.partnerId === inviterId;

      if (!stillPaired) {
        summary.skipped++;
        summary.skippedReasons.notPaired++;
        continue;
      }

      if (normalizedSlot(inviterData.role) === normalizedSlot(accepterData.role)) {
        const slots = assignSlots(inviterData.role);
        await inviterRef.update({role: slots.inviterRole});
        await accepterRef.update({role: slots.accepterRole});
        summary.updated++;
      } else {
        summary.skipped++;
        summary.skippedReasons.alreadySeparated++;
      }
    } catch (err) {
      console.error(`backfillParentSlots failed on invitation ${doc.id}`, err);
      summary.failed++;
    }
  }

  return summary;
}

exports.backfillParentSlotsImpl = backfillParentSlotsImpl;

/**
 * Re-slots pairs created before pairing started assigning distinct parent slots.
 *
 * Operator-only: gated on an allow-list (`backfillAdminUids`), not on `context.auth` alone.
 * This rewrites another user's `role` field from the server, which is exactly the kind of
 * write `firestore.rules` deliberately does not let a client make on its own behalf — the
 * same reason slot assignment lives server-side at all — so it must not be reachable by an
 * arbitrary signed-in caller.
 *
 * Runs with a 540-second timeout, well above the 60-second default `onCall` functions get
 * without a `runWith` override (`onCall` itself allows up to 3,600s; 540 is generous headroom
 * for this migration specifically, not the ceiling). The scan is a single unbounded `.get()`
 * over `invitations` with no pagination, and each qualifying pair costs two reads plus up to
 * two writes. Pagination was left out rather than added alongside the timeout bump: this is
 * expected to run once over a historical, bounded set, nothing else in `functions/` scans an
 * unbounded collection in one call, and there is no resume/retry bookkeeping to match it
 * against — the extra timeout budget is the proportionate fix, not a second migration-shaped
 * subsystem.
 *
 * Must not be invoked before the client that watches for a slot changing outside the accept
 * flow (Task 12b) has shipped to users. Flipping a slot here while no device is watching for
 * it leaves that parent's app still stamping their old slot on new records, while the
 * co-parent's app reads the change immediately — exactly the "history reads as the other
 * parent's" damage the accept-path re-stamp exists to prevent, delivered by this migration
 * instead.
 *
 * @return {Promise<{scanned: number, updated: number, skipped: number, failed: number,
 *   skippedReasons: {noAccepter: number, missingAccount: number, notPaired: number,
 *   alreadySeparated: number}}>} See [backfillParentSlotsImpl].
 */
exports.backfillParentSlots = functions.runWith({timeoutSeconds: 540}).https.onCall(
    async (data, context) => {
      if (!isBackfillOperator(context)) {
        throw new functions.https.HttpsError(
            'permission-denied', 'Operator access only', {reason: 'not-operator'});
      }
      return backfillParentSlotsImpl(admin.firestore());
    },
);

/**
 * How many characters of a chat message body are carried into the push notification preview.
 *
 * @const {number}
 */
const CHAT_MESSAGE_PREVIEW_LENGTH = 120;

/**
 * A chat message document's `timestamp` as epoch millis, in either wire format.
 *
 * A **number** is epoch millis already — that is what `Message.toFirestoreMap` writes now, the
 * same unit the read marks use, and the only form two devices in different timezones can agree
 * on. A **string** is the naive `DateTimeFormatter.ISO_LOCAL_DATE_TIME` value the previous
 * format used: it carries no offset, so `Date.parse` reads it in this runtime's own timezone
 * (UTC on Cloud Functions), which is the best available reading of a value that never recorded
 * where it was written. Both are accepted because both exist — documents written before the
 * change, and documents a phone still running an older build keeps writing.
 *
 * Returns `NaN` for anything unreadable, so the caller's `Number.isFinite` fallback still
 * covers it.
 *
 * @param {*} timestamp The document's `timestamp` field, of whatever type it happens to be.
 * @return {number} Epoch millis, or `NaN`.
 */
function sentAtMillisOf(timestamp) {
  if (typeof timestamp === 'number') return timestamp;
  if (typeof timestamp === 'string') return Date.parse(timestamp);
  return NaN;
}

/**
 * Queues a push notification for the other participant when a chat message is created,
 * unless they have already read past it.
 *
 * Exposed separately from the `onChatMessageCreated` trigger (mirroring
 * `unpairCoParentImpl`) purely so it can be exercised against a fake Firestore in tests.
 *
 * Every failure mode below is a quiet no-op rather than a thrown error, because a Firestore
 * `onCreate` trigger retries an uncaught rejection indefinitely, and none of these describes
 * something a retry could fix:
 * - the conversation document does not exist (deleted, or the message somehow predates it),
 * - `participants` holds no second uid distinct from the sender (a malformed or legacy
 *   conversation document),
 * - the sender is not one of the conversation's participants (the same malformed-document
 *   case, from the other side).
 *
 * The suppression rule reads `lastReadAt[recipient]` — an epoch-millis number, written a
 * dotted-path field at a time by `FirestoreMessageDataSource.markRead` — and skips the push
 * once that mark is at or past the message. A conversation that predates the read-mark
 * feature, or one the recipient has simply never opened, carries no `lastReadAt` entry at
 * all: `(conversation.lastReadAt || {})[recipient]` is `undefined` either way, defaulted to
 * `0` here so a never-read conversation always favours notifying rather than silently
 * swallowing the very first message.
 *
 * `message.timestamp` comes in either wire format — see [sentAtMillisOf]. A timestamp that
 * cannot be read at all falls back to "now" rather than to epoch `0`: falling back to `0`
 * would make an unreadable value look "already read" against a never-read conversation's own
 * `0` default and silently swallow the push instead of sending one.
 *
 * Only the first non-sender uid in `participants` is ever notified. `ConversationKey.of`
 * only ever produces a two-uid conversation today, so a third participant is unreachable in
 * practice — but if that ever changes, this silently notifies just one of the other
 * participants rather than all of them, which would need a deliberate decision, not a
 * side effect of `Array.prototype.find`.
 *
 * `messageType` is not read here: only `TEXT` messages are sent today (`MessageType.IMAGE`
 * and `VOICE` exist on the model but nothing produces them yet), so `message.content` is
 * always the right preview source. Once either ships, this will push an empty-body
 * notification for it — not a live bug, but worth fixing at that point rather than being
 * rediscovered as one.
 *
 * @param {FirebaseFirestore.Firestore} db Firestore instance.
 * @param {Object} message The created `messages/{messageId}` document's data.
 * @return {Promise<void>}
 */
async function notifyOfChatMessage(db, message) {
  const conversationId = message && message.conversationId;
  if (!conversationId) return;

  const conversationSnap = await db.collection('conversations').doc(conversationId).get();
  const conversation = conversationSnap.data();
  if (!conversation) return;

  const participants = conversation.participants || [];
  if (!participants.includes(message.senderId)) return;

  const recipient = participants.find((uid) => uid !== message.senderId);
  if (!recipient) return;

  const readMark = (conversation.lastReadAt || {})[recipient] || 0;
  const parsedTimestamp = sentAtMillisOf(message.timestamp);
  const sentAt = Number.isFinite(parsedTimestamp) ? parsedTimestamp : Date.now();
  if (readMark >= sentAt) return;

  await db.collection('notification_queue').add({
    targetUserId: recipient,
    data: {
      type: 'chat_message',
      conversationId,
      title: message.senderName || 'CoPlanly',
      body: String(message.content || '').slice(0, CHAT_MESSAGE_PREVIEW_LENGTH),
    },
    status: 'pending',
    createdAt: admin.firestore.FieldValue.serverTimestamp(),
  });
}

exports.notifyOfChatMessage = notifyOfChatMessage;

/**
 * Notifies the other parent when a message is created.
 *
 * Skipped when the recipient's read mark is already at or past this message — they are
 * looking at the thread as it arrives, and a push would be noise. See
 * [notifyOfChatMessage] for the suppression rule and the no-reader guards.
 */
exports.onChatMessageCreated = functions.firestore
    .document('messages/{messageId}')
    .onCreate(async (snap) => {
      await notifyOfChatMessage(admin.firestore(), snap.data());
      return null;
    });
