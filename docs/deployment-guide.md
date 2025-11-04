# Руководство по развертыванию системы совместного доступа к календарю

## Предварительные требования

- Android Studio Arctic Fox или новее
- JDK 17
- Firebase проект
- Google Cloud Console доступ (для FCM)

## Шаг 1: Настройка Firebase

### 1.1 Создание Firebase проекта

1. Перейдите в [Firebase Console](https://console.firebase.google.com/)
2. Создайте новый проект или используйте существующий
3. Добавьте Android приложение:
   - Package name: `com.coparently.app`
   - Скачайте `google-services.json`
   - Поместите файл в `app/` директорию

### 1.2 Включение сервисов

В Firebase Console включите:
- **Authentication**
  - Email/Password provider
  - Google Sign-In provider
- **Cloud Firestore**
  - Создайте базу данных в режиме production
- **Cloud Messaging**
  - Автоматически включен

### 1.3 Развертывание Security Rules

1. Откройте Firestore в Firebase Console
2. Перейдите в раздел "Rules"
3. Скопируйте содержимое `firestore.rules` из проекта
4. Нажмите "Publish"

Или используйте Firebase CLI:
```bash
firebase deploy --only firestore:rules
```

## Шаг 2: Настройка Cloud Functions (опционально, но рекомендуется)

### 2.1 Инициализация Functions

```bash
cd /путь/к/проекту
firebase init functions
```

Выберите:
- Язык: JavaScript или TypeScript
- ESLint: Yes
- Install dependencies: Yes

### 2.2 Создание функции для отправки уведомлений

Создайте файл `functions/index.js`:

```javascript
const functions = require('firebase-functions');
const admin = require('firebase-admin');

admin.initializeApp();

// Функция для отправки push-уведомлений
exports.sendNotification = functions.firestore
    .document('notification_queue/{notificationId}')
    .onCreate(async (snap, context) => {
        const data = snap.data();

        try {
            // Получить FCM токен целевого пользователя
            const userDoc = await admin.firestore()
                .collection('users')
                .doc(data.targetUserId)
                .get();

            if (!userDoc.exists) {
                console.log('User not found:', data.targetUserId);
                await snap.ref.delete();
                return;
            }

            const fcmToken = userDoc.data().fcmToken;

            if (!fcmToken) {
                console.log('No FCM token for user:', data.targetUserId);
                await snap.ref.delete();
                return;
            }

            // Отправить уведомление
            const message = {
                token: fcmToken,
                data: data.data,
                notification: {
                    title: data.data.title,
                    body: data.data.body
                },
                android: {
                    priority: 'high',
                    notification: {
                        channelId: 'coparently_notifications',
                        sound: 'default'
                    }
                }
            };

            await admin.messaging().send(message);
            console.log('Notification sent successfully');

            // Удалить из очереди
            await snap.ref.delete();
        } catch (error) {
            console.error('Error sending notification:', error);
            // Обновить статус на error
            await snap.ref.update({
                status: 'error',
                error: error.message
            });
        }
    });

// Функция для автоматического обновления статуса приглашений
exports.updateInvitationExpiry = functions.pubsub
    .schedule('every 24 hours')
    .onRun(async (context) => {
        const now = Date.now();
        const expiryTime = 7 * 24 * 60 * 60 * 1000; // 7 дней

        const snapshot = await admin.firestore()
            .collection('invitations')
            .where('status', '==', 'pending')
            .where('createdAt', '<', now - expiryTime)
            .get();

        const batch = admin.firestore().batch();

        snapshot.forEach(doc => {
            batch.update(doc.ref, { status: 'expired' });
        });

        await batch.commit();
        console.log(`Expired ${snapshot.size} invitations`);
    });
```

### 2.3 Развертывание Functions

```bash
firebase deploy --only functions
```

## Шаг 3: Настройка Android проекта

### 3.1 Проверка зависимостей

Убедитесь, что в `app/build.gradle.kts` присутствуют все необходимые зависимости:

```kotlin
dependencies {
    // Firebase
    val firebaseBom = platform("com.google.firebase:firebase-bom:32.7.0")
    implementation(firebaseBom)
    implementation("com.google.firebase:firebase-auth-ktx")
    implementation("com.google.firebase:firebase-firestore-ktx")
    implementation("com.google.firebase:firebase-messaging-ktx")
    implementation("com.google.firebase:firebase-analytics-ktx")

    // Gson для JSON
    implementation("com.google.code.gson:gson:2.10.1")
}
```

### 3.2 Миграция базы данных

При первом запуске обновленной версии приложения Room автоматически выполнит миграцию.
Для проверки миграции можно использовать:

```kotlin
// В DatabaseModule.kt
@Provides
@Singleton
fun provideCoParentlyDatabase(
    @ApplicationContext context: Context
): CoParentlyDatabase {
    return Room.databaseBuilder(
        context,
        CoParentlyDatabase::class.java,
        "coparently_database"
    )
    .addMigrations(MIGRATION_2_3)
    .fallbackToDestructiveMigration() // Только для разработки!
    .build()
}

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Создать таблицу child_info
        database.execSQL("""
            CREATE TABLE IF NOT EXISTS child_info (
                id TEXT PRIMARY KEY NOT NULL,
                childName TEXT NOT NULL,
                dateOfBirth TEXT,
                medicationsJson TEXT NOT NULL,
                activitiesJson TEXT NOT NULL,
                allergiesJson TEXT NOT NULL,
                medicalNotes TEXT,
                emergencyContactsJson TEXT NOT NULL,
                schoolInfoJson TEXT,
                createdAt TEXT NOT NULL,
                updatedAt TEXT NOT NULL,
                createdByFirebaseUid TEXT,
                lastModifiedBy TEXT,
                syncedToFirestore INTEGER NOT NULL
            )
        """)

        // Добавить новые колонки в events
        database.execSQL("ALTER TABLE events ADD COLUMN sharedWithJson TEXT NOT NULL DEFAULT '[]'")
        database.execSQL("ALTER TABLE events ADD COLUMN lastModifiedBy TEXT")
        database.execSQL("ALTER TABLE events ADD COLUMN permissions TEXT NOT NULL DEFAULT 'read_write'")
    }
}
```

### 3.3 Проверка AndroidManifest.xml

Убедитесь, что AndroidManifest.xml содержит:

```xml
<uses-permission android:name="android.permission.INTERNET" />
<uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />

<application>
    <!-- ... -->

    <!-- FCM Service -->
    <service
        android:name=".data.remote.firebase.CoParentlyMessagingService"
        android:exported="false">
        <intent-filter>
            <action android:name="com.google.firebase.MESSAGING_EVENT" />
        </intent-filter>
    </service>

    <!-- FCM metadata -->
    <meta-data
        android:name="com.google.firebase.messaging.default_notification_channel_id"
        android:value="coparently_notifications" />
</application>
```

## Шаг 4: Сборка и тестирование

### 4.1 Сборка проекта

```bash
./gradlew clean assembleDebug
```

### 4.2 Тестирование функций

#### Тест приглашения
1. Зарегистрируйте первого пользователя
2. Откройте экран Settings
3. Нажмите "Pairing"
4. Введите email второго родителя
5. Второй родитель должен получить приглашение

#### Тест синхронизации событий
1. Создайте событие от первого пользователя
2. Второй пользователь должен увидеть событие автоматически
3. Проверьте статус синхронизации в UI

#### Тест push-уведомлений
1. Убедитесь, что оба пользователя дали разрешение на уведомления
2. Создайте событие от первого пользователя
3. Второй пользователь должен получить push-уведомление

#### Тест данных о ребенке
1. Откройте Settings → Child Info
2. Добавьте информацию о ребенке
3. Проверьте, что партнер видит эту информацию

## Шаг 5: Мониторинг и отладка

### 5.1 Firebase Console

Отслеживайте в реальном времени:
- **Firestore**: Проверяйте создание/обновление документов
- **Cloud Messaging**: Статистика отправки уведомлений
- **Authentication**: Активные пользователи

### 5.2 Android Logcat

Фильтры для отладки:
```
CoParentlyMessagingService  # FCM уведомления
SyncService                 # Синхронизация
FirestoreEventDataSource   # Firestore операции
```

### 5.3 Debugging Firestore Rules

Используйте Firebase Console → Firestore → Rules Playground для тестирования правил доступа:

```javascript
// Тест создания события
{
  "path": "/events/test_event_id",
  "operation": "create",
  "data": {
    "createdByFirebaseUid": "user1_uid",
    "sharedWith": ["user1_uid", "user2_uid"]
  }
}
```

## Шаг 6: Production Release

### 6.1 Обновление security.rules

Убедитесь, что Firestore работает в production режиме:

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    // Запретить доступ по умолчанию
    match /{document=**} {
      allow read, write: if false;
    }

    // Разрешить только через специфичные правила
    match /events/{eventId} {
      // ... детальные правила
    }
  }
}
```

### 6.2 ProGuard/R8

Добавьте в `proguard-rules.pro`:

```proguard
# Firebase
-keep class com.google.firebase.** { *; }
-keep class com.google.android.gms.** { *; }

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-keep class com.coparently.app.domain.model.** { *; }
-keep class com.coparently.app.data.local.entity.** { *; }

# Room
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**
```

### 6.3 Версионирование

Обновите версию в `app/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        versionCode = 2
        versionName = "1.1.0"
    }
}
```

### 6.4 Подпись APK

```bash
./gradlew assembleRelease
```

## Шаг 7: Мониторинг в продакшене

### 7.1 Firebase Crashlytics (рекомендуется)

Добавьте в зависимости:
```kotlin
implementation("com.google.firebase:firebase-crashlytics-ktx")
```

### 7.2 Firebase Performance Monitoring

```kotlin
implementation("com.google.firebase:firebase-perf-ktx")
```

### 7.3 Analytics

Отслеживайте ключевые события:
```kotlin
firebaseAnalytics.logEvent("invitation_sent", Bundle().apply {
    putString("to_email", email)
})

firebaseAnalytics.logEvent("event_synced", Bundle().apply {
    putString("event_type", eventType)
})
```

## Troubleshooting

### Проблема: Уведомления не приходят

**Решение:**
1. Проверьте разрешения в настройках Android
2. Убедитесь, что FCM токен сохранен в Firestore
3. Проверьте Cloud Functions logs
4. Проверьте notification channel создан корректно

### Проблема: События не синхронизируются

**Решение:**
1. Проверьте интернет соединение
2. Проверьте Firestore rules
3. Проверьте логи SyncService
4. Убедитесь, что пользователи связаны (partnerId установлен)

### Проблема: Database migration fails

**Решение:**
1. Очистите данные приложения
2. Проверьте правильность миграции
3. Используйте `.fallbackToDestructiveMigration()` для разработки (потеря данных!)

## Полезные команды

```bash
# Проверка Firebase конфигурации
firebase projects:list

# Логи Cloud Functions
firebase functions:log

# Развертывание всего
firebase deploy

# Только Firestore rules
firebase deploy --only firestore:rules

# Только Functions
firebase deploy --only functions

# Очистка и пересборка
./gradlew clean build

# Запуск тестов
./gradlew test

# Создание release APK
./gradlew assembleRelease
```

## Заключение

После выполнения всех шагов у вас будет полностью функциональная система совместного доступа к календарю с:
- Реал-тайм синхронизацией между пользователями
- Push-уведомлениями о важных событиях
- Безопасным хранением данных в Firestore
- Офлайн поддержкой через Room Database

Для дополнительной информации смотрите:
- [shared-calendar-implementation.md](./shared-calendar-implementation.md) - детальная документация API
- [Firebase Documentation](https://firebase.google.com/docs)
- [Android Architecture Components](https://developer.android.com/topic/architecture)

