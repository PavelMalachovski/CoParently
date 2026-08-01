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

        // Подготовка сообщения для отправки
        const message = {
          token: fcmToken,
          notification: {
            title: notificationData.data.title,
            body: notificationData.data.body,
          },
          data: {
          // Преобразуем все значения в строки (требование FCM)
            type: notificationData.data.type || 'general',
            eventId: notificationData.data.eventId || '',
            childInfoId: notificationData.data.childInfoId || '',
            // Добавляем любые другие данные
            ...Object.keys(notificationData.data)
                .filter((key) => !['title', 'body'].includes(key))
                .reduce((acc, key) => {
                  acc[key] = String(notificationData.data[key]);
                  return acc;
                }, {}),
          },
          android: {
            priority: 'high',
            notification: {
              sound: 'default',
              color: '#4CAF50',
              channelId: 'coparently_notifications',
            },
          },
        };

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
 * Removes the link between the caller and their co-parent.
 *
 * One-sided by product decision: no confirmation from the other parent is
 * required. Shared data (events, chat, expenses) is left untouched.
 *
 * The decision of who to unlink is made and re-verified entirely inside the
 * transaction (both docs are read via `tx.get`, never via a plain `get()`
 * beforehand). Without that, a concurrent unpair/re-pair on the other side
 * between this call's start and its commit could make this transaction blindly
 * clear a partnerId the caller is no longer actually linked to.
 *
 * @return {Promise<{unpairedFrom: string|null}>} The former partner's UID.
 */
exports.unpairCoParent = functions.https.onCall(async (data, context) => {
  if (!context.auth) {
    throw new functions.https.HttpsError('unauthenticated', 'Sign in first');
  }

  const db = admin.firestore();
  const callerRef = db.collection('users').doc(context.auth.uid);

  const result = await db.runTransaction(async (tx) => {
    const callerSnap = await tx.get(callerRef);
    const partnerId = callerSnap.exists ? callerSnap.data().partnerId : null;

    if (!partnerId) {
      return {unpairedFrom: null};
    }

    const partnerRef = db.collection('users').doc(partnerId);
    const partnerSnap = await tx.get(partnerRef);

    // Re-verify the link is still mutually intact before clearing it. If the
    // partner has already unpaired or re-paired with someone else, the link
    // this call was asked to remove is already gone.
    if (!partnerSnap.exists || partnerSnap.data().partnerId !== context.auth.uid) {
      return {unpairedFrom: null};
    }

    tx.update(callerRef, {partnerId: '', pairedAt: null});
    tx.update(partnerRef, {partnerId: '', pairedAt: null});

    return {unpairedFrom: partnerId, callerName: callerSnap.data().name || 'Your co-parent'};
  });

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

  return {unpairedFrom: result.unpairedFrom};
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
