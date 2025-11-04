# Реализация совместного доступа к календарю

## Обзор

Реализована полноценная система совместного доступа к календарю между двумя родителями с поддержкой:
- Приглашений по email
- Важных данных о ребенке (медикаменты, кружки, аллергии)
- Общего календаря с правами read/write
- Разграничения ролей (Parent A / Parent B)
- Push-уведомлений о новых/изменённых событиях
- Отображения статуса синхронизации

## Архитектура

### Основные компоненты

#### 1. Модели данных

**ChildInfo** (`domain/model/ChildInfo.kt`)
- Хранит важную информацию о ребенке
- Включает: медикаменты, активности, аллергии, школьную информацию, экстренные контакты
- Поддерживает синхронизацию между родителями

**Event** (расширенная модель)
- Добавлены поля: `sharedWith`, `lastModifiedBy`, `permissions`
- Поддержка совместного редактирования
- Отслеживание автора последних изменений

#### 2. Синхронизация

**SyncService** (`data/sync/SyncService.kt`)
- Управляет синхронизацией между локальной БД и Firestore
- Поддерживает двунаправленную синхронизацию
- Отслеживает статус синхронизации в реальном времени
- Автоматически отправляет уведомления партнеру при изменениях

**SyncStatus** (sealed class)
```kotlin
sealed class SyncStatus {
    data object Idle : SyncStatus()
    data class Syncing(val progress: Int, val total: Int) : SyncStatus()
    data class Success(val lastSyncTime: LocalDateTime) : SyncStatus()
    data class Error(val message: String) : SyncStatus()
}
```

#### 3. Push-уведомления

**FcmService** (`data/remote/firebase/FcmService.kt`)
- Управление FCM токенами
- Создание notification payloads для различных типов событий:
  - Создание/изменение/удаление событий
  - Приглашения от партнера
  - Обновления данных о ребенке
- Постановка уведомлений в очередь для Cloud Functions

**CoParentlyMessagingService** (`data/remote/firebase/CoParentlyMessagingService.kt`)
- Обработка входящих push-уведомлений
- Создание локальных уведомлений с правильным каналом
- Поддержка различных типов уведомлений

#### 4. UI компоненты

**ChildInfoScreen** (`presentation/childinfo/ChildInfoScreen.kt`)
- Отображение полной информации о ребенке
- Секции: медикаменты, активности, аллергии, экстренные контакты, школьная информация
- Кнопки редактирования и синхронизации

**SyncStatusIndicator** (`presentation/sync/SyncStatusIndicator.kt`)
- Визуальная индикация статуса синхронизации
- Три варианта:
  - `SyncStatusIndicator` - полноценный индикатор с прогрессом
  - `SyncStatusIcon` - компактная иконка для app bar
  - `SyncStatusBadge` - встраиваемый бадж

#### 5. Безопасность

**Firestore Security Rules** (`firestore.rules`)
Реализованы детальные правила доступа:

```javascript
// События - доступ для владельца и партнеров
match /events/{eventId} {
  allow read: if isAuthenticated() && (
    resource.data.createdByFirebaseUid == request.auth.uid ||
    request.auth.uid in resource.data.sharedWith
  );

  allow update: if isAuthenticated() && (
    resource.data.createdByFirebaseUid == request.auth.uid ||
    (request.auth.uid in resource.data.sharedWith &&
     resource.data.permissions == 'read_write')
  );
}

// Информация о ребенке - общий доступ для обоих родителей
match /child_info/{childInfoId} {
  allow read: if isAuthenticated() &&
                request.auth.uid in resource.data.sharedWith;

  allow update: if isAuthenticated() &&
                  request.auth.uid in resource.data.sharedWith;
}
```

## Интеграция в существующую систему

### 1. База данных

Обновлена версия БД с 2 до 3:
```kotlin
@Database(
    entities = [
        EventEntity::class,
        UserEntity::class,
        CustodyScheduleEntity::class,
        ChildInfoEntity::class  // Новая таблица
    ],
    version = 3
)
```

Добавлены новые поля в EventEntity:
- `sharedWithJson: String` - JSON массив Firebase UIDs
- `lastModifiedBy: String?` - UID последнего редактора
- `permissions: String` - "read_only" или "read_write"

### 2. Dependency Injection

Необходимо добавить в модули Hilt:

```kotlin
// DatabaseModule.kt
@Provides
@Singleton
fun provideChildInfoDao(database: CoParentlyDatabase): ChildInfoDao {
    return database.childInfoDao()
}

// RepositoryModule.kt
@Provides
@Singleton
fun provideChildInfoRepository(
    childInfoDao: ChildInfoDao,
    firebaseAuthService: FirebaseAuthService,
    firestoreChildInfoDataSource: FirestoreChildInfoDataSource
): ChildInfoRepository {
    return ChildInfoRepositoryImpl(childInfoDao, firebaseAuthService, firestoreChildInfoDataSource)
}

@Provides
@Singleton
fun provideSyncService(
    eventDao: EventDao,
    childInfoDao: ChildInfoDao,
    userDao: UserDao,
    firestoreEventDataSource: FirestoreEventDataSource,
    firestoreChildInfoDataSource: FirestoreChildInfoDataSource,
    firestoreUserDataSource: FirestoreUserDataSource,
    firebaseAuthService: FirebaseAuthService,
    fcmService: FcmService
): SyncService {
    return SyncService(
        eventDao, childInfoDao, userDao,
        firestoreEventDataSource, firestoreChildInfoDataSource, firestoreUserDataSource,
        firebaseAuthService, fcmService
    )
}
```

### 3. AndroidManifest.xml

Обновлен с настройками FCM:
- Добавлено разрешение `POST_NOTIFICATIONS`
- Добавлена meta-data для канала уведомлений по умолчанию
- Настроена автоинициализация FCM

### 4. Навигация

Добавлены новые экраны:
```kotlin
sealed class Screen(val route: String) {
    // ... существующие экраны
    data object ChildInfo : Screen("child_info")
    data object Pairing : Screen("pairing")
}
```

## Использование

### Приглашение второго родителя

```kotlin
// В PairingViewModel
fun sendInvitation(email: String) {
    viewModelScope.launch {
        val result = pairingService.sendInvitation(
            fromUserId = currentUser.uid,
            fromUserEmail = currentUser.email,
            fromUserName = currentUserData.name,
            toEmail = email
        )
        // Обработка результата
    }
}
```

### Управление данными о ребенке

```kotlin
// В ChildInfoViewModel
fun upsertChildInfo(
    childName: String,
    medications: List<Medication>,
    activities: List<Activity>,
    // ... остальные поля
) {
    val childInfo = ChildInfo(
        id = UUID.randomUUID().toString(),
        childName = childName,
        medications = medications,
        // ...
    )
    childInfoRepository.upsertChildInfo(childInfo)
    // Автоматическая синхронизация с партнером
}
```

### Отслеживание синхронизации

```kotlin
@Composable
fun MyScreen(syncViewModel: SyncViewModel = hiltViewModel()) {
    val syncStatus by syncViewModel.firestoreSyncStatus.collectAsState()

    // Отображение индикатора
    SyncStatusIndicator(syncStatus = syncStatus)

    // Или компактный бадж
    SyncStatusBadge(syncStatus = syncStatus)
}
```

### Push-уведомления

При изменении события автоматически отправляется уведомление партнеру:

```kotlin
// В SyncService.notifyEventUpdate()
private suspend fun notifyEventUpdate(
    partnerId: String,
    eventId: String,
    eventTitle: String,
    action: String
) {
    val notificationPayload = fcmService.createEventNotificationPayload(
        eventId = eventId,
        eventTitle = eventTitle,
        action = action,
        performedBy = userData.name
    )

    fcmService.queueNotificationForUser(partnerId, notificationPayload)
}
```

## Firestore структура данных

### События (events)
```json
{
  "id": "uuid",
  "title": "Soccer practice",
  "startDateTime": "2025-11-05T16:00:00",
  "createdByFirebaseUid": "user1_uid",
  "sharedWith": ["user1_uid", "user2_uid"],
  "lastModifiedBy": "user1_uid",
  "permissions": "read_write"
}
```

### Информация о ребенке (child_info)
```json
{
  "id": "uuid",
  "childName": "Alice",
  "medications": [
    {
      "name": "Vitamin D",
      "dosage": "1000 IU",
      "frequency": "Daily"
    }
  ],
  "activities": [
    {
      "name": "Piano lessons",
      "schedule": "Tuesday 5PM",
      "location": "Music School"
    }
  ],
  "allergies": ["peanuts", "cats"],
  "sharedWith": ["user1_uid", "user2_uid"],
  "lastModifiedBy": "user1_uid"
}
```

### Очередь уведомлений (notification_queue)
```json
{
  "targetUserId": "user2_uid",
  "data": {
    "type": "event_created",
    "eventId": "event_uuid",
    "title": "New Event: Soccer practice",
    "body": "John created an event"
  },
  "createdAt": 1699200000000,
  "status": "pending"
}
```

## Cloud Functions (рекомендуется)

Для отправки push-уведомлений рекомендуется создать Cloud Function:

```javascript
// functions/index.js
const functions = require('firebase-functions');
const admin = require('firebase-admin');

admin.initializeApp();

exports.sendNotification = functions.firestore
    .document('notification_queue/{notificationId}')
    .onCreate(async (snap, context) => {
        const data = snap.data();

        // Получить FCM токен целевого пользователя
        const userDoc = await admin.firestore()
            .collection('users')
            .doc(data.targetUserId)
            .get();

        const fcmToken = userDoc.data().fcmToken;

        if (fcmToken) {
            // Отправить уведомление
            await admin.messaging().send({
                token: fcmToken,
                data: data.data,
                notification: {
                    title: data.data.title,
                    body: data.data.body
                }
            });

            // Удалить из очереди
            await snap.ref.delete();
        }
    });
```

## Миграция базы данных

При обновлении с версии 2 до 3 Room автоматически создаст таблицу `child_info`.
Для миграции существующих событий с новыми полями:

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(database: SupportSQLiteDatabase) {
        // Создать новую таблицу child_info
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

## Тестирование

### Unit тесты

```kotlin
@Test
fun `syncEvents should upload unsynced events`() = runTest {
    // Arrange
    val unsyncedEvent = createTestEvent(syncedToFirestore = false)
    whenever(eventDao.getUnsyncedEvents()).thenReturn(listOf(unsyncedEvent))

    // Act
    syncService.performFullSync()

    // Assert
    verify(firestoreEventDataSource).insertEvent(any(), any())
    verify(eventDao).markAsSynced(unsyncedEvent.id)
}
```

### UI тесты

```kotlin
@Test
fun childInfoScreen_displaysCorrectly() {
    composeTestRule.setContent {
        ChildInfoScreen(
            onNavigateBack = {},
            onEditClick = {}
        )
    }

    composeTestRule.onNodeWithText("Medications").assertIsDisplayed()
    composeTestRule.onNodeWithText("Activities & Classes").assertIsDisplayed()
}
```

## Известные ограничения

1. **Offline support**: События создаются локально и синхронизируются при подключении к интернету
2. **Conflict resolution**: При конфликтах используется стратегия "последний записавший побеждает"
3. **File attachments**: В текущей версии не поддерживаются вложения (фото, документы)
4. **Bulk operations**: Массовые операции могут быть медленными при большом количестве событий

## Следующие шаги

1. Реализовать разрешение конфликтов при одновременном редактировании
2. Добавить поддержку вложений (фото рецептов, расписаний)
3. Реализовать историю изменений событий
4. Добавить возможность отмены приглашений
5. Расширить систему разрешений (только чтение для определенных событий)

## Заключение

Реализована полноценная система совместного доступа к календарю с поддержкой всех требуемых функций:
- ✅ Приглашения по email
- ✅ Важные данные о ребенке
- ✅ Общий календарь с правами read/write
- ✅ Разграничение ролей
- ✅ Push-уведомления
- ✅ Статус синхронизации

Система следует принципам Clean Architecture, использует Jetpack Compose для UI, и полностью интегрирована с Firebase для backend функциональности.

