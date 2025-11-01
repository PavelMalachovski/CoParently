# Структура данных

## Обзор
Приложение использует Room для локального хранения данных. В будущем данные будут синхронизироваться с Firebase/Supabase и Google Calendar.

## Entity (Сущности базы данных)

### EventEntity
Представляет событие в календаре.

**Поля:**
- `id: String` - Уникальный идентификатор события (UUID)
- `title: String` - Название события (обязательное)
- `description: String?` - Описание события (опциональное)
- `startDateTime: LocalDateTime` - Дата и время начала события
- `endDateTime: LocalDateTime?` - Дата и время окончания (опциональное)
- `eventType: String` - Тип события (например, "mom", "dad", "training", "doctor", "school")
- `parentOwner: String` - Владелец события ("mom" или "dad")
- `isRecurring: Boolean` - Является ли событие повторяющимся
- `recurrencePattern: String?` - Паттерн повторения (например, "daily", "weekly", "monthly")
- `createdAt: LocalDateTime` - Время создания записи
- `updatedAt: LocalDateTime` - Время последнего обновления

**Индексы:**
- По `startDateTime` для быстрого поиска по дате
- По `parentOwner` для фильтрации по родителю

### UserEntity
Представляет пользователя (родителя).

**Поля:**
- `id: String` - Уникальный идентификатор пользователя (UUID)
- `email: String` - Email адрес пользователя (уникальный)
- `name: String` - Отображаемое имя пользователя
- `role: String` - Роль пользователя ("mom" или "dad")
- `colorCode: String` - Hex-код цвета для отображения в календаре (например, "#FF4081" для розового, "#2196F3" для синего)
- `profilePhotoUrl: String?` - URL фотографии профиля (опциональное)
- `googleCalendarSyncEnabled: Boolean` - Включена ли синхронизация с Google Calendar
- `googleCalendarId: String?` - ID календаря Google для синхронизации

**Индексы:**
- По `email` для уникальности

### CustodyScheduleEntity
Представляет паттерн расписания опеки (еженедельный график).

**Поля:**
- `id: String` - Уникальный идентификатор расписания (UUID)
- `parentOwner: String` - Родитель, у которого опека ("mom" или "dad")
- `dayOfWeek: Int` - День недели (1 = понедельник, 7 = воскресенье)
- `isActive: Boolean` - Активен ли этот паттерн расписания
- `startDate: String?` - Начальная дата расписания (ISO date string, опциональное)
- `endDate: String?` - Конечная дата расписания (ISO date string, опциональное)

**Использование:**
- Используется для автоматического создания событий опеки на основе регулярного графика
- Например: понедельник-среда у мамы, четверг-воскресенье у папы

## DAO (Data Access Objects)

### EventDao
Методы для работы с событиями:
- `getAllEvents()` - Получить все события (Flow)
- `getEventsByDateRange(start, end)` - Получить события в диапазоне дат
- `getEventsByDate(date)` - Получить события на конкретную дату
- `getEventById(id)` - Получить событие по ID
- `getEventsByParent(parentOwner)` - Получить события конкретного родителя
- `insertEvent(event)` - Вставить новое событие
- `insertEvents(events)` - Вставить несколько событий
- `updateEvent(event)` - Обновить событие
- `deleteEvent(event)` - Удалить событие
- `deleteEventById(id)` - Удалить событие по ID

### UserDao
Методы для работы с пользователями:
- `getAllUsers()` - Получить всех пользователей (Flow)
- `getUserById(id)` - Получить пользователя по ID
- `getUserByEmail(email)` - Получить пользователя по email
- `insertUser(user)` - Вставить нового пользователя
- `updateUser(user)` - Обновить пользователя
- `deleteUserById(id)` - Удалить пользователя

## Будущие расширения

### Для синхронизации с Google Calendar:
- Добавить поле `googleEventId: String?` в `EventEntity` для связи с событием в Google Calendar
- Добавить поле `syncStatus: String` (например, "synced", "pending", "error")

### Для синхронизации с Firebase/Supabase:
- Добавить поле `serverId: String?` для связи с серверной версией
- Добавить поле `lastSyncedAt: LocalDateTime?` для отслеживания последней синхронизации

### Для мульти-пользовательского доступа:
- Добавить поле `createdBy: String` (user ID) в `EventEntity`
- Добавить поле `sharedWith: List<String>` для списка пользователей с доступом

