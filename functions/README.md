# CoPlanly Cloud Functions

Firebase Cloud Functions для обработки push-уведомлений и автоматизации в приложении CoPlanly.

## Функции

### 1. sendNotification
Отправляет push-уведомления пользователям при создании записи в коллекции `notification_queue`.

**Триггер:** onCreate в `notification_queue/{notificationId}`

**Поля документа notification_queue:**
```javascript
{
  targetUserId: string,        // Firebase UID пользователя
  data: {
    title: string,             // Заголовок уведомления
    body: string,              // Текст уведомления
    type: string,              // Тип уведомления (optional)
    eventId: string,           // ID события (optional)
    childInfoId: string        // ID информации о ребенке (optional)
  },
  status: 'pending',           // Статус: pending/sent/failed/skipped
  createdAt: timestamp,
  sentAt: timestamp,           // Время отправки (optional)
  error: string                // Сообщение об ошибке (optional)
}
```

### 2. cleanupOldNotifications
Автоматически удаляет старые уведомления (старше 30 дней).

**Триггер:** Каждый день в 2:00 UTC

### 3. onEventCreated
Автоматически создает уведомление для партнера при создании нового события.

**Триггер:** onCreate в `events/{eventId}`

### 4. onChildInfoUpdated
Автоматически создает уведомление для партнера при обновлении информации о ребенке.

**Триггер:** onUpdate в `child_info/{childInfoId}`

## Установка

### 1. Установить Firebase CLI
```bash
npm install -g firebase-tools
```

### 2. Войти в Firebase
```bash
firebase login
```

### 3. Инициализировать проект (если еще не сделано)
```bash
firebase init functions
```
Выберите:
- JavaScript
- Use ESLint: Yes
- Install dependencies: Yes

### 4. Установить зависимости
```bash
cd functions
npm install
```

## Разработка

### Локальное тестирование
```bash
# Запустить эмуляторы Firebase
firebase emulators:start

# Или только Functions
npm run serve
```

### Проверка кода
```bash
npm run lint
```

## Деплой

### Деплой всех функций
```bash
firebase deploy --only functions
```

### Деплой конкретной функции
```bash
firebase deploy --only functions:sendNotification
```

## Требования к Android приложению

### 1. FCM Token
Приложение должно сохранять FCM токен в документе пользователя:

```kotlin
// В FcmService.kt
fun updateUserToken(token: String) {
    val userId = FirebaseAuth.getInstance().currentUser?.uid ?: return
    FirebaseFirestore.getInstance()
        .collection("users")
        .document(userId)
        .update("fcmToken", token)
}
```

### 2. Notification Channel
Создать канал уведомлений в Android приложении:

```kotlin
// В MainActivity или Application
private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val channel = NotificationChannel(
            "coparently_notifications",
            "CoPlanly Notifications",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications about co-parenting events"
            enableLights(true)
            lightColor = Color.GREEN
            enableVibration(true)
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.createNotificationChannel(channel)
    }
}
```

## Структура базы данных

### Коллекция users
```javascript
{
  uid: string,
  email: string,
  partnerId: string,
  fcmToken: string,  // FCM token для push-уведомлений
  createdAt: timestamp
}
```

### Коллекция notification_queue
```javascript
{
  targetUserId: string,
  data: {
    title: string,
    body: string,
    type: string,
    eventId: string,
    childInfoId: string
  },
  status: 'pending' | 'sent' | 'failed' | 'skipped',
  createdAt: timestamp,
  sentAt: timestamp,
  messageId: string,
  error: string
}
```

## Мониторинг

### Просмотр логов
```bash
# Все логи
firebase functions:log

# Логи конкретной функции
firebase functions:log --only sendNotification

# Последние N записей
firebase functions:log --limit 50
```

### Метрики в Firebase Console
1. Перейти в Firebase Console > Functions
2. Выбрать функцию для просмотра:
   - Количество вызовов
   - Время выполнения
   - Ошибки
   - Использование памяти

## Troubleshooting

### Ошибка: "registration-token-not-registered"
FCM токен недействителен. Функция автоматически удаляет токен из документа пользователя.
Приложение должно обновить токен при следующем запуске.

### Ошибка: "No FCM token"
Пользователь еще не зарегистрировал FCM токен.
Проверьте, что приложение корректно сохраняет токен в Firestore.

### Уведомления не приходят
1. Проверьте, что FCM токен сохранен в документе пользователя
2. Проверьте логи функции: `firebase functions:log`
3. Убедитесь, что notification channel создан в приложении
4. Проверьте, что приложение имеет разрешения на уведомления

## Стоимость

Cloud Functions для Firebase использует модель оплаты pay-as-you-go.
Бесплатный план (Spark) включает:
- 2M вызовов/месяц
- 400,000 ГБ-секунд
- 200,000 ЦП-секунд
- 5 ГБ исходящего трафика

Для production рекомендуется план Blaze.

## Безопасность

1. **Firestore Rules:** Убедитесь, что правила Firestore разрешают создание документов в notification_queue только авторизованным пользователям
2. **Валидация данных:** Функция проверяет существование пользователя перед отправкой уведомления
3. **Обработка ошибок:** Все ошибки логируются и сохраняются в документе для отладки

## Дополнительно

### Добавление новых типов уведомлений
1. Создайте новый триггер в `index.js`
2. Используйте структуру notification_queue для создания уведомления
3. Задеплойте функцию

### Настройка расписания
Измените cron-выражение в функции `cleanupOldNotifications`:
```javascript
.schedule('0 2 * * *')  // Каждый день в 2:00 UTC
```

Формат cron: `минута час день_месяца месяц день_недели`

## Admin operations

### backfillParentSlots

`backfillParentSlots` is an operator-only `onCall` callable (in `index.js`) that re-slots
co-parent pairs created before pairing started assigning distinct `"mom"`/`"dad"` slots. It
is not a user-facing feature — every existing pair before this change has both parents in the
same slot, and this migration is the one-time fix for that, invoked manually.

**It refuses every caller by default.** The gate reads a comma-separated allow-list of
Firebase Auth UIDs from the `BACKFILL_ADMIN_UIDS` environment variable
(`backfillAdminUids()`/`isBackfillOperator()` in `index.js`); with nothing configured, the
list is empty and the callable rejects every caller with `permission-denied`.

**To open it for an operator:**

1. Copy `functions/.env.example` to `functions/.env` (the latter is gitignored — never
   commit it) and set `BACKFILL_ADMIN_UIDS` to the operator's Firebase Auth UID(s), comma
   separated:
   ```
   BACKFILL_ADMIN_UIDS=uid-of-operator-1,uid-of-operator-2
   ```
2. Deploy the function so the CLI picks up the new value:
   ```bash
   firebase deploy --only functions:backfillParentSlots
   ```
   The Firebase CLI loads `functions/.env` into `process.env` for every deployed function —
   1st gen and 2nd gen alike — with no extra code needed to bind it. This project
   deliberately does **not** use a Secret Manager secret (`firebase functions:secrets:set`)
   for this value: a secret additionally requires the function to declare
   `functions.runWith({secrets: [...]})`, and without that binding the secret's value never
   reaches `process.env` at runtime even though `firebase functions:secrets:set` reports
   success — silently leaving the gate impossible to open by the route that looks like it
   should open it. A plain `.env` variable has no such trap.
3. Sign in to the app as one of the configured operator UIDs and invoke the callable with no
   arguments (e.g. from `firebase functions:shell`, or any authenticated client SDK call to
   `backfillParentSlots`). It returns a summary — `scanned`/`updated`/`skipped`/`failed`
   counts, with `skipped` broken down by reason — rather than a bare success flag, so the
   result can be checked afterwards.

**Do not invoke it before the Task 12b client (the one that reacts to a slot changing outside
the accept flow) has reached users.** Re-slotting a pair on the server before that client
ships leaves the affected parent's app stamping new records with their *old* slot while the
co-parent's app already sees the new one — the exact "history reads as my co-parent's"
failure the accept-path re-stamp exists to prevent, delivered by this migration instead.

## Лицензия

© 2025 CoPlanly. All rights reserved.

