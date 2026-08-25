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
 * FCM requires every `data` value to be a string and rejects the whole message otherwise, so
 * every value is coerced here and an absent key becomes '' rather than the string 'undefined'.
 *
 * **The payload no longer carries the notification's text** (SEC-3). It carries a `type` and the
 * few names that type needs; the receiving device writes the sentence from its own string
 * resources, in the reader's language, and drops a `type` it has no wording for. The `title` and
 * `body` defaults this used to inject are gone with that — they encoded a contract in which the
 * sender wrote what the other parent's lock screen would say. `type` keeps its 'general'
 * fallback, which is now a value no client composes and every client therefore discards: the
 * right outcome for a payload that arrived malformed.
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

      // Chunk the deletes: Firestore rejects a batch of more than 500 operations, so a single
      // batch over every stale notification threw INVALID_ARGUMENT once the backlog crossed
      // 500 — after which the daily job failed permanently and the queue only grew. The other
      // batch loops in this file (guest sweep, unpair revocation) already cap at 400; match
      // them.
      const CLEANUP_BATCH_LIMIT = 400;
      let count = 0;
      let batch = admin.firestore().batch();
      let pending = 0;

      for (const doc of oldNotificationsQuery.docs) {
        batch.delete(doc.ref);
        pending++;
        count++;
        if (pending === CLEANUP_BATCH_LIMIT) {
          await batch.commit();
          batch = admin.firestore().batch();
          pending = 0;
        }
      }

      if (pending > 0) {
        await batch.commit();
      }

      console.log(count > 0 ?
        `Deleted ${count} old notifications` :
        'No old notifications to delete');

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

      // Google Calendar imports are bulk-synced, not deliberately authored, so they must not
      // each fire a "created a new event" push. A parent connecting a calendar with hundreds
      // of events would otherwise flood their co-parent with hundreds of notifications at once.
      if (eventData.eventType === 'google') {
        console.log('Skipping notification for a Google Calendar import');
        return null;
      }

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
              // The child document's name field is `childName`, not `name` — reading `name`
              // rendered every push as "... updated information about undefined".
              body: `${creatorData.email || 'Your partner'} updated information about ` +
                `${newData.childName || 'your child'}`,
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
/**
 * Delivery outcomes recorded on an invitation. Deliberately **not** the invitation's `status`.
 *
 * The previous implementation wrote `status: 'failed'` when delivery threw — and `status` is
 * what every redemption path gates on (`invite.status !== 'pending'` refuses in all three
 * callables, and the rules only allow a `pending` invitation to be cancelled or rejected). So
 * a bounced email permanently destroyed a perfectly good invite code, which the inviter could
 * still read off their own screen and hand over in person. Delivery is a separate fact from
 * redeemability, and it now lives in its own field.
 *
 * @enum {string}
 */
const EmailDelivery = {
  SENT: 'sent',
  FAILED: 'failed',
  NOT_CONFIGURED: 'not_configured',
  /** No address to deliver to: a code, QR or share-link invitation. */
  NOT_APPLICABLE: 'not_applicable',
};

exports.EmailDelivery = EmailDelivery;

/**
 * The configured mail provider, or null when none is set up.
 *
 * Read fresh on every call rather than cached at module load, so tests can set it per-case —
 * the same reason `backfillAdminUids` does. Populated at deploy time from `functions/.env`.
 *
 * @return {?{apiKey: string, from: string, fromName: string}} The provider, or null.
 */
function emailProviderConfig() {
  const apiKey = process.env.SENDGRID_API_KEY || '';
  const from = process.env.INVITE_FROM_EMAIL || '';
  if (!apiKey || !from) {
    return null;
  }
  return {apiKey, from, fromName: process.env.INVITE_FROM_NAME || 'CoPlanly'};
}

exports.emailProviderConfig = emailProviderConfig;

/**
 * Posts one message to SendGrid's v3 API.
 *
 * Written against `https` directly rather than pulling in `@sendgrid/mail`: this is a single
 * JSON POST, and a dependency added for it would be one more thing to keep patched in a
 * function that runs on every invitation. Swapping providers means replacing this one
 * function — everything above it is provider-agnostic.
 *
 * @param {{apiKey: string, from: string, fromName: string}} config The provider.
 * @param {{to: string, subject: string, html: string, text: string}} message The email.
 * @return {Promise<void>} Resolves when the provider accepts the message.
 */
function sendViaSendGrid(config, message) {
  const https = require('https');
  const payload = JSON.stringify({
    personalizations: [{to: [{email: message.to}]}],
    from: {email: config.from, name: config.fromName},
    subject: message.subject,
    content: [
      {type: 'text/plain', value: message.text},
      {type: 'text/html', value: message.html},
    ],
  });

  return new Promise((resolve, reject) => {
    const request = https.request({
      hostname: 'api.sendgrid.com',
      path: '/v3/mail/send',
      method: 'POST',
      headers: {
        'Authorization': `Bearer ${config.apiKey}`,
        'Content-Type': 'application/json',
        'Content-Length': Buffer.byteLength(payload),
      },
    }, (response) => {
      // SendGrid answers 202 with an empty body on success. Anything else is drained and
      // reported, so the failure recorded on the invitation says what the provider said.
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve();
        } else {
          reject(new Error(
              `SendGrid responded ${response.statusCode}: ${Buffer.concat(chunks)}`));
        }
      });
    });
    request.on('error', reject);
    request.write(payload);
    request.end();
  });
}

exports.sendViaSendGrid = sendViaSendGrid;

/**
 * The invitation email, in both parts.
 *
 * Carries the **code** as well as the link. A deep link only opens the app on a device that
 * has it installed, and the recipient of a co-parent invitation frequently does not yet — the
 * code is what they can type in after installing, and it is what the inviter can also read out
 * over the phone. A message that omitted it would strand exactly the recipient it is for.
 *
 * @param {string} senderName The inviting parent's display name.
 * @param {!Object} invite The invitation document.
 * @return {{to: string, subject: string, html: string, text: string}} The email.
 */
function invitationEmail(senderName, invite) {
  const who = senderName || 'A co-parent';
  const code = invite.code || '';
  const subject = `${who} invited you to CoPlanly`;

  const text = [
    `${who} has invited you to share a calendar on CoPlanly.`,
    '',
    `Your invite code: ${code}`,
    '',
    'Install CoPlanly, sign in, and enter the code on the pairing screen.',
    'The code expires in 7 days.',
    '',
    'If you were not expecting this, you can ignore this email.',
  ].join('\n');

  const html = `
    <div style="font-family: -apple-system, Segoe UI, Roboto, sans-serif;
                max-width: 560px; margin: 0 auto; color: #14171F;">
      <h1 style="font-size: 20px; margin: 0 0 16px;">${who} invited you to CoPlanly</h1>
      <p style="margin: 0 0 16px; line-height: 1.6;">
        CoPlanly is a shared calendar for parents raising a child in two homes.
      </p>
      <p style="margin: 0 0 8px; line-height: 1.6;">Your invite code:</p>
      <p style="font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 28px;
                letter-spacing: 4px; margin: 0 0 24px;">${code}</p>
      <p style="margin: 0 0 16px; line-height: 1.6;">
        Install CoPlanly, sign in, and enter the code on the pairing screen.
        The code expires in 7 days.
      </p>
      <hr style="border: none; border-top: 1px solid #E7EAF1; margin: 24px 0;">
      <p style="color: #545B6D; font-size: 13px; line-height: 1.5;">
        If you were not expecting this, you can ignore this email.
      </p>
    </div>
  `;

  return {to: invite.toEmail, subject, html, text};
}

exports.invitationEmail = invitationEmail;

/**
 * Delivers an invitation email and records what happened on the invitation.
 *
 * **This used to send nothing at all.** It built the message, wrote it to `console.log`, and
 * returned — under a `// TODO: Replace with actual email sending service`. The client's
 * "Invite by email" button therefore produced an invitation the recipient was never told
 * about, while the inviter saw the field clear as though it had worked. For a product that is
 * worth nothing until the *other* parent installs it, that was the growth path, dead.
 *
 * Every outcome is recorded in `emailDelivery`, including the one where no provider is
 * configured — a deployment without `SENDGRID_API_KEY` should be visibly unconfigured rather
 * than quietly silent, which is precisely how the old behaviour survived so long.
 *
 * A failure here never throws: an `onCreate` trigger retries an uncaught rejection
 * indefinitely, and a rejected address will be rejected on every retry. The invitation itself
 * is untouched — see [EmailDelivery].
 *
 * @param {FirebaseFirestore.Firestore} db Firestore instance.
 * @param {FirebaseFirestore.DocumentReference} inviteRef The invitation.
 * @param {!Object} invite The invitation document.
 * @param {function(!Object): !Promise<void>} send Delivers one message.
 * @return {Promise<string>} The recorded [EmailDelivery] outcome.
 */
async function deliverInvitationEmailImpl(db, inviteRef, invite, send) {
  const record = async (outcome, extra) => {
    await inviteRef.update(Object.assign({
      emailDelivery: outcome,
      emailDeliveryAt: admin.firestore.FieldValue.serverTimestamp(),
    }, extra || {}));
    return outcome;
  };

  // Code, QR and share-link invitations carry no address. They are the majority, and this
  // trigger fires for all of them.
  if (!invite.toEmail) {
    return record(EmailDelivery.NOT_APPLICABLE);
  }

  const senderSnap = await db.collection('users').doc(invite.fromUserId).get();
  const senderName = senderSnap.exists && senderSnap.data() ? senderSnap.data().name : '';

  try {
    await send(invitationEmail(senderName, invite));
    return record(EmailDelivery.SENT);
  } catch (err) {
    console.error(`Invitation email to ${invite.toEmail} was not delivered`, err);
    return record(EmailDelivery.FAILED, {emailDeliveryError: String(err.message || err)});
  }
}

exports.deliverInvitationEmailImpl = deliverInvitationEmailImpl;

/**
 * Sends the invitation email when an invitation is created.
 *
 * See [deliverInvitationEmailImpl] for what is recorded and why nothing here throws.
 */
exports.sendEmailInvitation = functions.firestore
    .document('invitations/{invitationId}')
    .onCreate(async (snap) => {
      const config = emailProviderConfig();
      if (!config) {
        console.warn(
            'No mail provider configured (SENDGRID_API_KEY / INVITE_FROM_EMAIL); ' +
            'email invitations are not being delivered.');
        await snap.ref.update({
          emailDelivery: snap.data().toEmail ?
            EmailDelivery.NOT_CONFIGURED : EmailDelivery.NOT_APPLICABLE,
          emailDeliveryAt: admin.firestore.FieldValue.serverTimestamp(),
        });
        return null;
      }

      await deliverInvitationEmailImpl(
          admin.firestore(), snap.ref, snap.data(),
          (message) => sendViaSendGrid(config, message));
      return null;
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
  // The same hazard for a friend invitation (item 16), and worse: a friend's grant is
  // read-only and expires, while `assignSlots` here would hand them a permanent parent slot,
  // the other parent's colour, and write access to the whole family. Absent `kind` still
  // means co-parent — only these two named kinds are refused.
  if (invite.kind === FRIEND_INVITATION) {
    throw new functions.https.HttpsError(
        'failed-precondition', 'This is a friend invitation, not a co-parent invitation',
        {reason: 'friend-invitation'});
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

    // The relationship itself, as a document. Its id is `FamilyKey.of` — the same string
    // `custody_models`, `family_settings` and `conversations` are already keyed by — and its
    // `members` array is what `firestore.rules` will read to answer "may this person see this
    // record" once a person can co-parent with more than one other person.
    //
    // Written here, as admin, and by no client ever: the membership *is* the grant, so a
    // create path open to clients would let anyone name themselves a member of any pair. The
    // same reasoning rules out keeping the list on `users/{uid}`, which its owner may write.
    tx.set(db.collection('families').doc(
        custodyModelKey(invite.fromUserId, acceptingUserId)), {
      members: [invite.fromUserId, acceptingUserId].sort(),
      createdAt: pairedAt,
    });

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

  // The name only. The sentence around it is written by the receiving device, from its own
  // string resources, in the reader's language (SEC-3) — an English `title`/`body` written here
  // would reach a Czech parent in English, and would also be the shape that let a *client*
  // write whatever it liked. An empty name is fine: the app substitutes a translated
  // "your co-parent" for it.
  const accepterName = (await accepterRef.get()).data().name || '';
  await db.collection('notification_queue').add({
    targetUserId: invite.fromUserId,
    data: {
      type: 'pairing_accepted',
      actorName: accepterName,
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
      admin.firestore(), context.auth.uid, verifiedEmailOf(context), {code, invitationId});
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

    // Somebody who already reads this record gains nothing from a grant, and a co-parent who
    // took one would gain a trap: they would sit in `guests` while also being a parent in
    // `sharedWith`, and when the grant ran out `sweepExpiredGuests` would take a *parent* out
    // of the audience of their own child's record. The sweep declines to remove the creator
    // for exactly that reason, but it cannot recognise the other parent — this can.
    if (sharedWith.indexOf(acceptingUserId) >= 0) {
      throw new functions.https.HttpsError(
          'failed-precondition', 'You can already see this record',
          {reason: 'already-entitled'});
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
 * The accepter's avatar, as the one-key object to merge into a grant — `{}` when they have none.
 *
 * Returned as an object rather than a string so the caller never writes `photoUrl: undefined`,
 * which Firestore rejects outright. A Google sign-in puts the account's own picture in
 * `users/{uid}.profilePhotoUrl` (see `ProfileIdentity.resolvePhotoUrl` on the client); an
 * email/password account has none, and the reader's initial-letter fallback covers that.
 *
 * Copied into the grant for the same reason the name is: the parents' "who can see this" list
 * would otherwise need a second read of a document that is not theirs to read.
 *
 * @param {FirebaseFirestore.DocumentReference} accepterRef The accepter's user document.
 * @return {Promise<!Object>} `{photoUrl}` or an empty object.
 */
async function accepterPhoto(accepterRef) {
  const snap = await accepterRef.get();
  const stored = snap.exists && snap.data() ? snap.data().profilePhotoUrl : '';
  return typeof stored === 'string' && stored.trim() ? {photoUrl: stored} : {};
}

exports.accepterPhoto = accepterPhoto;

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
      admin.firestore(), context.auth.uid, verifiedEmailOf(context), {code, invitationId});
});

/**
 * The `kind` marking an invitation as a **calendar friend** invitation (item 16).
 *
 * A third kind rather than a flag on the guest one: a guest opens exactly one child record, a
 * friend opens the whole calendar, and the two redemption paths write different documents. As
 * with `guest`, absent still means co-parent.
 */
const FRIEND_INVITATION = 'friend';
exports.FRIEND_INVITATION = FRIEND_INVITATION;

/**
 * Body of the `acceptCalendarFriendInvitation` callable — lets a trusted third person read the
 * family's calendar without occupying a parent slot.
 *
 * A third function beside the pairing and guest ones, for the reason stated on
 * `acceptGuestInvitationImpl`: paths that grant different things must not be one `kind` branch
 * apart. This one writes exactly one document — `calendar_friends/{friendUid}` — and touches no
 * user document, no event and no child record. **No event is ever rewritten to admit a friend**:
 * the events read rule consults this grant instead, so admitting or revoking is one write rather
 * than a fan-out over the family's whole history.
 *
 * The inviter must be a **paired parent**: `partnerId` is what proves they hold a slot, and it
 * also supplies the second uid the grant records, so a friend admitted by one parent can read
 * both parents' events — which is what "see the calendar" means for a family of two.
 *
 * @param {FirebaseFirestore.Firestore} db Firestore instance.
 * @param {string} acceptingUserId The signed-in caller's UID.
 * @param {string} acceptingEmail The signed-in caller's email, or ''.
 * @param {{code: ?string, invitationId: ?string}} ref Exactly one identifier.
 * @return {Promise<{familyParents: !Array<string>, expiresAtMillis: number}>} The pair whose
 *   calendar the caller may now read, and the instant their access ends.
 */
async function acceptCalendarFriendInvitationImpl(db, acceptingUserId, acceptingEmail, ref) {
  const inviteRef = await findInvitation(db, ref);
  const invite = (await inviteRef.get()).data();

  if (invite.kind !== FRIEND_INVITATION) {
    throw new functions.https.HttpsError(
        'failed-precondition', 'This is not a friend invitation',
        {reason: 'not-a-friend-invitation'});
  }
  if (invite.status !== 'pending') {
    throw new functions.https.HttpsError(
        'failed-precondition', 'Invitation is no longer pending',
        {reason: 'invitation-not-pending'});
  }
  if (typeof invite.expiresAt === 'number' && invite.expiresAt < Date.now()) {
    throw new functions.https.HttpsError(
        'failed-precondition', 'Invitation has expired', {reason: 'invitation-expired'});
  }
  if (invite.fromUserId === acceptingUserId) {
    throw new functions.https.HttpsError(
        'invalid-argument', 'You cannot accept your own invitation', {reason: 'self-pairing'});
  }
  if (invite.toEmail && invite.toEmail !== acceptingEmail) {
    throw new functions.https.HttpsError(
        'permission-denied', 'This invitation is addressed to somebody else',
        {reason: 'wrong-recipient'});
  }

  // No fallback duration, for the reason the guest path states: the one default this must never
  // have is "forever".
  const expiresAtMillis = typeof invite.friendExpiresAt === 'number' ? invite.friendExpiresAt : 0;
  if (expiresAtMillis <= Date.now()) {
    throw new functions.https.HttpsError(
        'failed-precondition', 'This access has already ended', {reason: 'grant-expired'});
  }

  const inviterRef = db.collection('users').doc(invite.fromUserId);
  const accepterRef = db.collection('users').doc(acceptingUserId);
  const grantRef = db.collection('calendar_friends').doc(acceptingUserId);
  const grantedAtMillis = Date.now();
  let familyParents = [];

  await db.runTransaction(async (tx) => {
    const [inviterSnap, inviteSnap] = await Promise.all([tx.get(inviterRef), tx.get(inviteRef)]);
    // Re-read inside the transaction: two devices redeeming one code would otherwise both pass
    // the check above and the second grant would overwrite the first's expiry.
    if (inviteSnap.data().status !== 'pending') {
      throw new functions.https.HttpsError(
          'failed-precondition', 'Invitation is no longer pending',
          {reason: 'invitation-not-pending'});
    }
    const inviter = inviterSnap.exists ? inviterSnap.data() : null;
    const partnerId = inviter && typeof inviter.partnerId === 'string' ? inviter.partnerId : '';
    if (!partnerId) {
      throw new functions.https.HttpsError(
          'failed-precondition', 'Only a paired parent can invite a friend',
          {reason: 'inviter-not-paired'});
    }
    // A parent must never end up in their own family's friend grant: they would read the
    // calendar through a door that expires, and a sweep would later "revoke" a parent.
    if (acceptingUserId === partnerId) {
      throw new functions.https.HttpsError(
          'failed-precondition', 'You are already a parent in this family',
          {reason: 'already-entitled'});
    }
    familyParents = [invite.fromUserId, partnerId].sort();

    tx.set(grantRef, Object.assign({
      familyParents,
      name: await guestName(accepterRef, acceptingEmail),
      grantedBy: invite.fromUserId,
      grantedAtMillis,
      expiresAtMillis,
    }, await accepterPhoto(accepterRef)));
    tx.update(inviteRef, {
      status: 'accepted', acceptedBy: acceptingUserId, acceptedAt: grantedAtMillis,
    });
  });

  // Both parents are told: a third person reading the family's calendar is a fact the parent who
  // did not send the invitation has as much right to know as the one who did.
  await Promise.all(familyParents.map((parentUid) =>
    db.collection('notification_queue').add({
      targetUserId: parentUid,
      data: {
        type: 'calendar_friend_accepted',
        title: 'Calendar access accepted',
        body: `${acceptingEmail || 'A friend'} can now see the family calendar`,
      },
      status: 'pending',
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
    })));

  return {familyParents, expiresAtMillis};
}

exports.acceptCalendarFriendInvitationImpl = acceptCalendarFriendInvitationImpl;

/**
 * Redeems a calendar-friend invitation identified either by its short code or by its id.
 *
 * Runs server-side because it must read the inviter's `users` document to prove they are a
 * paired parent — a document the caller cannot read until the grant it is deciding exists.
 *
 * @param {{code?: string, invitationId?: string}} data Exactly one identifier.
 * @return {Promise<{familyParents: !Array<string>, expiresAtMillis: number}>} See
 *   [acceptCalendarFriendInvitationImpl].
 */
exports.acceptCalendarFriendInvitation = functions.https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError('unauthenticated', 'Sign in first');
  }

  const code = data && data.code ? String(data.code).trim().toUpperCase() : null;
  const invitationId = data && data.invitationId ? String(data.invitationId) : null;

  if ((!code && !invitationId) || (code && invitationId)) {
    throw new functions.https.HttpsError(
        'invalid-argument', 'Provide exactly one of code or invitationId');
  }

  return acceptCalendarFriendInvitationImpl(
      admin.firestore(), context.auth.uid, verifiedEmailOf(context), {code, invitationId});
});

/** Firestore caps a batched write at 500 operations; stay clear of the edge. */
const GUEST_SWEEP_BATCH_LIMIT = 400;

/**
 * Whether a stored guest grant has run out, at [nowMillis].
 *
 * The third implementation of the question `GuestGrantPolicy` states and the `guests` block
 * in `firestore.rules` asks — read that file before changing this. All three use the same
 * strict comparison, so a grant expiring at noon is inactive at noon for every one of them;
 * if this one rounded the other way a grant would be live for the rules and swept here.
 *
 * Fail closed: anything that is not a positive number is expired, never absent. A grant that
 * reached the record without an end — an older client, a partial write — is removed rather
 * than kept forever, which is the one outcome this feature must never produce.
 *
 * @param {*} grant The stored grant, whatever shape it turned out to be.
 * @param {number} nowMillis The instant to judge it at.
 * @return {boolean} True when the grant may no longer be used.
 */
function guestGrantExpired(grant, nowMillis) {
  const expiresAtMillis = grant && typeof grant.expiresAtMillis === 'number' ?
    grant.expiresAtMillis : 0;
  return expiresAtMillis <= 0 || expiresAtMillis <= nowMillis;
}

exports.guestGrantExpired = guestGrantExpired;

/**
 * Body of the `sweepExpiredGuests` schedule — removes guest grants that have run out.
 *
 * The read rule refusing an expired guest is only half of the expiry. It stops the read, but
 * the uid stays in `sharedWith`, so the record keeps coming back from every audience query
 * the guest issues and the app keeps listing them as somebody with access. This is the half
 * that actually ends it, and it writes both places: the grant leaves `guests` and the uid
 * leaves `sharedWith`.
 *
 * **Scans the whole collection**, because there is no query for it: Firestore cannot filter
 * on a field inside a map's values, so "any record with an expired guest" is not expressible.
 * A denormalised "earliest expiry" column would make it expressible, and is deliberately not
 * here — it would be a derived field that every one of the several places building a child
 * document has to remember to recompute, which is exactly the class of bug this codebase
 * keeps finding. `child_info` holds one document per child per family; revisit this if that
 * ever stops being small.
 *
 * A uid is never removed from `sharedWith` of a document it created — the same rule
 * `revokeSharedAudience` follows, and for the same reason: `sharedWith` is what the parent's
 * own audience query reads, so dropping the creator would hide the child from the parent who
 * entered them. A parent should never be in `guests` at all (`acceptGuestInvitation` refuses
 * an accepter who already reads the record), and the stale grant is still cleaned off the
 * map — this only declines to touch the audience.
 *
 * @param {FirebaseFirestore.Firestore} db Firestore instance.
 * @param {number} nowMillis The instant to sweep at.
 * @return {Promise<number>} How many grants were removed.
 */
async function sweepExpiredGuestsImpl(db, nowMillis) {
  const snap = await db.collection('child_info').get();

  let batch = db.batch();
  let pending = 0;
  let removed = 0;

  for (const doc of snap.docs) {
    const data = doc.data();
    const guests = data.guests && typeof data.guests === 'object' &&
      !Array.isArray(data.guests) ? data.guests : {};
    const expired = Object.keys(guests)
        .filter((uid) => guestGrantExpired(guests[uid], nowMillis));
    if (expired.length === 0) {
      continue;
    }

    const kept = {};
    Object.keys(guests)
        .filter((uid) => expired.indexOf(uid) < 0)
        .forEach((uid) => {
          kept[uid] = guests[uid];
        });

    const update = {guests: kept};
    const fromAudience = expired.filter((uid) => uid !== data.createdByFirebaseUid);
    if (fromAudience.length > 0) {
      update.sharedWith = admin.firestore.FieldValue.arrayRemove(...fromAudience);
    }

    batch.update(doc.ref, update);
    pending++;
    removed += expired.length;

    if (pending === GUEST_SWEEP_BATCH_LIMIT) {
      await batch.commit();
      batch = db.batch();
      pending = 0;
    }
  }

  if (pending > 0) {
    await batch.commit();
  }

  return removed;
}

exports.sweepExpiredGuestsImpl = sweepExpiredGuestsImpl;

/**
 * Daily sweep of guest grants that have run out.
 *
 * An hour after `cleanupOldNotifications` so the two never contend, and daily rather than
 * hourly because the read rule already refuses an expired guest from the moment their grant
 * ends — this is cleanup, not enforcement. The gap between the two is the only window in
 * which a swept guest still appears in a parent's list, and it is bounded by a day.
 */
exports.sweepExpiredGuests = functions.pubsub
    .schedule('0 3 * * *')
    .timeZone('UTC')
    .onRun(async () => {
      const removed = await sweepExpiredGuestsImpl(admin.firestore(), Date.now());
      console.log(`Swept ${removed} expired guest grants`);
      return null;
    });

/**
 * Collections whose documents are deleted by being tombstoned rather than removed (CQ-3).
 *
 * Both are read by the co-parent's phone through a filtered collection query, which is the
 * channel a deletion travels down: the client marks the document `deletedAtMillis` instead of
 * removing it, the other device sees the field on its next sync and drops its local row. That
 * only works while the document is still there, which is what this sweep is bounding.
 */
const TOMBSTONED_COLLECTIONS = ['events', 'expenses'];

exports.TOMBSTONED_COLLECTIONS = TOMBSTONED_COLLECTIONS;

/**
 * How long a tombstone is kept before the document is removed for good.
 *
 * This is the deadline for a co-parent's phone to come back and collect the deletion. Long,
 * because the cost of the two outcomes is not symmetric: sweeping early leaves a cancelled
 * event on a returning parent's calendar with nothing left to correct it — the exact defect
 * CQ-3 exists to fix, reintroduced by the cleanup for it — whereas sweeping late costs a few
 * bytes per deleted row. Ninety days is well past any period a phone that opens this app at
 * all goes without syncing.
 *
 * A device offline for longer than this still keeps that one event. Bounded and rare, and it
 * is the reason this number is not smaller.
 */
const TOMBSTONE_RETENTION_DAYS = 90;

exports.TOMBSTONE_RETENTION_DAYS = TOMBSTONE_RETENTION_DAYS;

const TOMBSTONE_SWEEP_BATCH_LIMIT = 400;

/**
 * Body of the `sweepDeletedDocuments` schedule — removes tombstones nobody is still waiting for.
 *
 * Unlike `sweepExpiredGuestsImpl` this does **not** scan the collection: `deletedAtMillis` is a
 * top-level number, so "deleted before the cutoff" is an ordinary range query on a field
 * Firestore indexes by itself. A live document has no such field at all, and a document missing
 * the field is not returned by a range query on it — so the query cannot match anything that is
 * not already a tombstone, which is the property that makes a scheduled delete safe to run
 * unattended.
 *
 * @param {FirebaseFirestore.Firestore} db Firestore instance.
 * @param {number} nowMillis The instant to sweep at.
 * @param {number=} retentionDays Override the retention window; defaults to
 *     [TOMBSTONE_RETENTION_DAYS].
 * @return {Promise<number>} How many documents were removed.
 */
async function sweepDeletedDocumentsImpl(db, nowMillis, retentionDays) {
  const days = typeof retentionDays === 'number' ? retentionDays :
    TOMBSTONE_RETENTION_DAYS;
  const cutoff = nowMillis - days * 24 * 60 * 60 * 1000;

  let removed = 0;

  for (const collection of TOMBSTONED_COLLECTIONS) {
    const snap = await db.collection(collection)
        .where('deletedAtMillis', '<=', cutoff)
        .get();

    let batch = db.batch();
    let pending = 0;

    for (const doc of snap.docs) {
      batch.delete(doc.ref);
      pending++;
      removed++;

      if (pending === TOMBSTONE_SWEEP_BATCH_LIMIT) {
        await batch.commit();
        batch = db.batch();
        pending = 0;
      }
    }

    if (pending > 0) {
      await batch.commit();
    }
  }

  return removed;
}

exports.sweepDeletedDocumentsImpl = sweepDeletedDocumentsImpl;

/**
 * Daily removal of tombstones past their retention window.
 *
 * An hour after `sweepExpiredGuests` so the scheduled jobs never contend, and daily rather
 * than more often for the same reason that one is: nothing depends on this running promptly.
 * A tombstone that outlives its window by a day is a document; a tombstone swept a day early
 * is a deletion that was never delivered.
 */
exports.sweepDeletedDocuments = functions.pubsub
    .schedule('0 4 * * *')
    .timeZone('UTC')
    .onRun(async () => {
      const removed = await sweepDeletedDocumentsImpl(admin.firestore(), Date.now());
      console.log(`Swept ${removed} tombstoned documents`);
      return null;
    });

/**
 * Collections whose visibility is a per-document `sharedWith` audience.
 *
 * These three (`events`, `child_info`, `pets`) each keep a per-document `sharedWith` list
 * that only ever widens on the client, so unpair has to narrow it here. `expenses` and
 * `budgets` are gated on the *live* `isPartnerOf` relationship rather than a stored list, so
 * clearing `partnerId` already revokes them. `conversations` membership is deliberately
 * immutable — whether an ended co-parent link should also erase the chat history is a
 * product decision, not a leak to close here.
 */
const SHARED_AUDIENCE_COLLECTIONS = ['events', 'child_info', 'pets'];

exports.SHARED_AUDIENCE_COLLECTIONS = SHARED_AUDIENCE_COLLECTIONS;

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

    // The pair's money agreement goes with it, at the same derived key and for the same
    // reason: left behind, it would silently reattach if these two ever re-paired, and a
    // split neither of them remembers agreeing would start pricing their expenses again.
    tx.delete(db.collection('family_settings').doc(custodyModelKey(callerUid, partnerId)));

    // And the relationship itself. This one is not tidiness: `families/{id}.members` is what
    // grants access to everything the pair shares, so leaving it behind leaves the ex-partner
    // reading this household after the unpair — the sweep below narrows documents, but a live
    // membership would let them all back in.
    tx.delete(db.collection('families').doc(custodyModelKey(callerUid, partnerId)));

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
          actorName: result.callerName || '',
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
 * The caller's email address, but **only when Firebase has verified it**.
 *
 * Every `accept*InvitationImpl` refuses an invitation addressed to somebody else by comparing
 * `invite.toEmail` against the address the caller presents. That comparison is only worth
 * anything if the address is proven, and `context.auth.token.email` does not prove one: Firebase
 * fills it in for an email/password account the moment it is registered, with no verification
 * step in between. So anybody who knew the address an invitation was sent to could register it,
 * be handed a token claiming it, and redeem the invitation — and an invitation is a bearer
 * credential for a co-parent link, a child's record, or a family's calendar.
 *
 * Returning '' for an unverified caller is what makes them fail that comparison: an invitation
 * with a `toEmail` no longer matches. A **code-based** invitation carries `toEmail: ''` and is
 * unaffected, which is correct — there the six-character code is the credential, and it was
 * delivered out of band to whoever holds it.
 *
 * Google sign-in always carries a verified address, so the ordinary path does not notice this.
 * An email/password user who has not confirmed their address can still pair by code.
 *
 * @param {?{auth: ?{token: ?Object}}} context The `onCall` context.
 * @return {string} The verified address, or ''.
 */
function verifiedEmailOf(context) {
  const token = (context && context.auth && context.auth.token) || {};
  return token.email_verified === true && typeof token.email === 'string' ? token.email : '';
}

exports.verifiedEmailOf = verifiedEmailOf;

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
      // `actorName`/`preview` rather than `title`/`body`, so that no queued payload anywhere
      // carries pre-written notification text and the security rule can refuse those two keys
      // outright (SEC-3). This one still relays rather than composes — a chat notification's
      // title *is* the sender and its body *is* the message — but only this function has seen
      // the message, and the rule refuses `chat_message` from a client, so relaying it here is
      // not the hole that relaying the others was.
      actorName: message.senderName || '',
      preview: String(message.content || '').slice(0, CHAT_MESSAGE_PREVIEW_LENGTH),
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

/** Firestore caps a batched write at 500 operations; stay clear of the edge. */
const ACCOUNT_DELETE_BATCH_LIMIT = 400;

/**
 * Collections holding documents stamped with their author's uid in `createdByFirebaseUid`.
 *
 * Everything here is deleted outright when that author erases their account: it is content
 * they entered, and this app has no notion of transferring ownership of a record to the other
 * parent. See [deleteAccountDataImpl] for what that costs the co-parent and why it is still
 * the right default.
 */
const AUTHORED_COLLECTIONS = ['events', 'child_info', 'pets', 'expenses', 'budgets'];

exports.AUTHORED_COLLECTIONS = AUTHORED_COLLECTIONS;

/**
 * Deletes every document a query returns, in batches below the write cap.
 *
 * @param {FirebaseFirestore.Firestore} db Firestore instance.
 * @param {FirebaseFirestore.Query} query The documents to remove.
 * @return {Promise<number>} How many documents were deleted.
 */
async function deleteQueryInBatches(db, query) {
  const snap = await query.get();
  let batch = db.batch();
  let pending = 0;
  let deleted = 0;

  for (const doc of snap.docs) {
    batch.delete(doc.ref);
    pending++;
    deleted++;
    if (pending === ACCOUNT_DELETE_BATCH_LIMIT) {
      await batch.commit();
      batch = db.batch();
      pending = 0;
    }
  }
  if (pending > 0) {
    await batch.commit();
  }
  return deleted;
}

exports.deleteQueryInBatches = deleteQueryInBatches;

/**
 * Removes [uid] from the `sharedWith` array of documents somebody else created.
 *
 * The counterpart to deleting the user's own records: a document another parent authored is
 * *their* data and stays, but the departing account must not remain in its audience. Documents
 * the user created are skipped here — they are deleted wholesale instead, and issuing both a
 * narrow and a delete for one document would be two writes for one outcome.
 *
 * @param {FirebaseFirestore.Firestore} db Firestore instance.
 * @param {string} uid The departing account.
 * @return {Promise<number>} How many documents were narrowed.
 */
async function scrubFromAudiences(db, uid) {
  let narrowed = 0;

  for (const collection of SHARED_AUDIENCE_COLLECTIONS) {
    const snap = await db.collection(collection)
        .where('sharedWith', 'array-contains', uid)
        .get();

    let batch = db.batch();
    let pending = 0;

    for (const doc of snap.docs) {
      if (doc.data().createdByFirebaseUid === uid) {
        continue;
      }
      batch.update(doc.ref, {
        sharedWith: admin.firestore.FieldValue.arrayRemove(uid),
      });
      pending++;
      narrowed++;
      if (pending === ACCOUNT_DELETE_BATCH_LIMIT) {
        await batch.commit();
        batch = db.batch();
        pending = 0;
      }
    }
    if (pending > 0) {
      await batch.commit();
    }
  }

  return narrowed;
}

exports.scrubFromAudiences = scrubFromAudiences;

/**
 * Erases everything an account holds, and returns a per-collection tally.
 *
 * **Why this exists at all.** `FirebaseAuthService.deleteCurrentUser()` on the client removes
 * the Auth user and nothing else, so every event, every message, and a child's whole medical
 * profile stayed in Firestore under a uid nobody could sign in as — unreachable, unerasable,
 * and still there. Google Play requires an in-app deletion path for any app offering account
 * creation, and GDPR Art. 17 requires the data to actually go.
 *
 * **What is deleted, and the decision behind it.** Documents the user *authored* go
 * ([AUTHORED_COLLECTIONS]); documents somebody else authored stay, with the departing uid
 * scrubbed from their `sharedWith`. That is the honest reading of erasure — but it is worth
 * being plain about the cost, because it is not small: **the co-parent loses the events,
 * expenses and child records this parent created.** The alternative — transferring authorship
 * to the co-parent — keeps a shared calendar intact but means an erasure request leaves the
 * requester's entries in somebody else's account, which is the thing erasure is supposed to
 * prevent. Neither is free. This picks the one the regulation asks for, and the client warns
 * the user before calling it.
 *
 * Chat is deleted whole. A 1:1 thread whose second participant no longer exists has no reader
 * the app can serve, and half a conversation is worse than none: the surviving parent would
 * read their own messages answering nothing.
 *
 * **Order matters.** The pairing is torn down first, through the existing
 * [unpairCoParentImpl], so the co-parent's `partnerId` is cleared and the audience sweep that
 * unpair already performs runs while both accounts still exist. The Auth user is deleted
 * **last**, by the callable rather than here: while it exists, a failed run can simply be
 * retried, and a partial deletion leaves an account the user can still sign into and try
 * again. Deleting the credential first would strand whatever remained.
 *
 * Takes `db` as a parameter for the reason every other `*Impl` in this file does: it is the
 * only way to exercise the batching and the ordering without a live Firestore.
 *
 * @param {FirebaseFirestore.Firestore} db Firestore instance.
 * @param {string} uid The account being erased.
 * @return {Promise<!Object>} Counts per collection, plus `unpairedFrom`.
 */
async function deleteAccountDataImpl(db, uid) {
  const removed = {};

  // Tear the co-parent link down first, while both accounts still exist. This also runs the
  // shared-audience revocation unpair already owns, so the ex-partner is out of this user's
  // documents before those documents are removed.
  let unpairedFrom = null;
  try {
    const unpair = await unpairCoParentImpl(db, uid);
    unpairedFrom = unpair.unpairedFrom;
  } catch (err) {
    // An account with no partner, or a sweep that could not finish, must not stop an erasure
    // request. The deletions below remove the same documents the sweep would have narrowed.
    console.error(`Unpair during account deletion failed for ${uid}`, err);
  }

  for (const collection of AUTHORED_COLLECTIONS) {
    removed[collection] = await deleteQueryInBatches(
        db, db.collection(collection).where('createdByFirebaseUid', '==', uid));
  }

  removed.sharedWithScrubbed = await scrubFromAudiences(db, uid);

  // Change requests name their two parties directly rather than through an audience array.
  removed.change_requests =
    await deleteQueryInBatches(db, db.collection('change_requests').where('requestedBy', '==', uid)) +
    await deleteQueryInBatches(db, db.collection('change_requests').where('requestedTo', '==', uid));

  // Conversations and their messages, whole — see the block comment above.
  const conversations = await db.collection('conversations')
      .where('participants', 'array-contains', uid)
      .get();
  removed.messages = 0;
  for (const conversation of conversations.docs) {
    removed.messages += await deleteQueryInBatches(
        db, db.collection('messages').where('conversationId', '==', conversation.id));
  }
  removed.conversations = await deleteQueryInBatches(
      db, db.collection('conversations').where('participants', 'array-contains', uid));

  removed.custody_models = await deleteQueryInBatches(
      db, db.collection('custody_models').where('participants', 'array-contains', uid));

  // Holds both parents' uids, so it is personal data of a deleted account either way.
  removed.family_settings = await deleteQueryInBatches(
      db, db.collection('family_settings').where('participants', 'array-contains', uid));

  // Both directions of the calendar-friend relationship: the grant this user holds over
  // somebody's family, and the grants their own family handed out.
  removed.calendar_friends = await deleteQueryInBatches(
      db, db.collection('calendar_friends').where('familyParents', 'array-contains', uid));
  await db.collection('calendar_friends').doc(uid).delete();
  await db.collection('friend_profiles').doc(uid).delete();

  removed.invitations = await deleteQueryInBatches(
      db, db.collection('invitations').where('fromUserId', '==', uid));

  // Queued pushes addressed to an account that is going away would otherwise be delivered to
  // whatever device still holds its FCM token.
  removed.notification_queue = await deleteQueryInBatches(
      db, db.collection('notification_queue').where('targetUserId', '==', uid));

  // The profile last: while it exists, `isPartnerOf` and the rules keyed on it still resolve,
  // which keeps the deletions above evaluable if any of them are ever moved behind rules.
  await db.collection('users').doc(uid).delete();

  return Object.assign({unpairedFrom}, removed);
}

exports.deleteAccountDataImpl = deleteAccountDataImpl;

/**
 * Erases the caller's account and everything it holds.
 *
 * Deliberately takes no arguments: an account may only ever delete itself. The client is
 * responsible for confirming the decision — see the warning it must show, in
 * [deleteAccountDataImpl]'s note on what the co-parent loses.
 *
 * The Auth user goes last and only if the data deletion returned cleanly, so a failure leaves
 * an account the user can sign into and retry rather than an orphaned pile of documents.
 *
 * 540 seconds, matching `backfillParentSlots`: the work is bounded by one family's history,
 * but that history has no cap and the default 60 seconds is not obviously enough for an
 * account of several years.
 *
 * @return {Promise<!Object>} What was removed, for the client to log or show.
 */
exports.deleteAccount = functions.runWith({timeoutSeconds: 540}).https.onCall(
    async (data, context) => {
      if (!context.auth) {
        throw new functions.https.HttpsError('unauthenticated', 'Sign in first');
      }
      const uid = context.auth.uid;

      const removed = await deleteAccountDataImpl(admin.firestore(), uid);

      try {
        await admin.auth().deleteUser(uid);
      } catch (err) {
        console.error(`Auth user ${uid} could not be deleted after its data was`, err);
        throw new functions.https.HttpsError(
            'internal',
            'Your data was deleted, but the account itself could not be removed. Please try again.',
            {reason: 'auth-delete-failed'});
      }

      return removed;
    });
