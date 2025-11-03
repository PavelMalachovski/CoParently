# Настройка OAuth 2.0 Client ID для Google Calendar API

Эта инструкция поможет настроить OAuth 2.0 Client ID для работы Google Calendar API синхронизации в приложении CoParently.

## Предварительные требования

- Доступ к [Google Cloud Console](https://console.cloud.google.com/)
- Доступ к [Firebase Console](https://console.firebase.google.com/)
- Проект уже создан в Firebase (project_id: `coparently-a39c9`)

## Шаг 1: Включение Google Calendar API в Google Cloud Console

1. Откройте [Google Cloud Console](https://console.cloud.google.com/)
2. Выберите проект `coparently-a39c9` (или создайте новый, если нужно)
3. Перейдите в **APIs & Services** → **Library**
4. В поиске введите "Google Calendar API"
5. Нажмите на **Google Calendar API**
6. Нажмите кнопку **Enable** (Включить)
7. Дождитесь активации API (обычно несколько секунд)

## Шаг 2: Создание OAuth 2.0 Client ID для Android

### 2.1. Переход в OAuth настроек

1. В Google Cloud Console перейдите в **APIs & Services** → **Credentials**
2. Вверху страницы нажмите **+ CREATE CREDENTIALS** → **OAuth client ID**

### 2.2. Настройка OAuth consent screen (если еще не настроен)

Если вы видите предупреждение о необходимости настроить OAuth consent screen:

1. Нажмите **CONFIGURE CONSENT SCREEN**
2. Выберите **External** (внешний) и нажмите **CREATE**
3. Заполните обязательные поля:
   - **App name**: `CoParently`
   - **User support email**: ваш email
   - **Developer contact information**: ваш email
4. Нажмите **SAVE AND CONTINUE**
5. На шаге **Scopes** добавьте:
   - `https://www.googleapis.com/auth/calendar` (Google Calendar API)
6. Нажмите **SAVE AND CONTINUE**
7. На шаге **Test users** добавьте тестовые email адреса:
   - **Важно**: Добавьте свой email и email всех, кто будет тестировать приложение
   - Только эти пользователи смогут войти через Google Sign-In
   - Можно добавить до 100 тестовых пользователей
   - Пример: `your-email@gmail.com`, `tester@gmail.com`
8. Нажмите **SAVE AND CONTINUE**
9. Нажмите **BACK TO DASHBOARD**

**Примечание:** После настройки приложение будет в режиме **Testing**. Это означает, что только добавленные тестовые пользователи смогут войти. Для доступа всех пользователей нужно опубликовать приложение (см. раздел "Публикация приложения" ниже).

### 2.3. Создание OAuth Client ID для Android

1. Вернитесь в **APIs & Services** → **Credentials**
2. Нажмите **+ CREATE CREDENTIALS** → **OAuth client ID**
3. Выберите тип приложения: **Android**
4. Введите **Name** (имя): `CoParently Android Client`
5. В поле **Package name** введите: `com.coparently.app`
6. Для получения **SHA-1 certificate fingerprint**:

   **Вариант A: Через Gradle (рекомендуется для debug)**
   ```bash
   # Windows PowerShell
   cd C:\Git\CoParently
   .\gradlew signingReport
   ```

   Найдите строку с `Variant: debug` и скопируйте значение `SHA1`:
   ```
   Variant: debug
   Config: debug
   Store: C:\Users\...\.android\debug.keystore
   Alias: AndroidDebugKey
   SHA1: XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX
   ```

   **Вариант B: Через keytool (для release keystore)**
   ```bash
   keytool -list -v -keystore "путь_к_вашему_keystore.jks" -alias ваш_alias
   ```

7. Вставьте SHA-1 в поле **SHA-1 certificate fingerprint**
8. Нажмите **CREATE**

### 2.4. Сохранение Client ID

После создания вы увидите диалог с **Client ID** (например: `123456789-abcdefghijklmnopqrstuvwxyz.apps.googleusercontent.com`)

**Важно:** Сохраните этот Client ID - он понадобится для Firebase.

## Шаг 3: Добавление OAuth Client ID в Firebase

### 3.1. Открытие настроек проекта Firebase

1. Откройте [Firebase Console](https://console.firebase.google.com/)
2. Выберите проект **coparently-a39c9**
3. Перейдите в **Project Settings** (⚙️ → Project settings)
4. Прокрутите вниз до раздела **Your apps**
5. Найдите ваше Android приложение с package name `com.coparently.app`
6. Если приложения нет, нажмите **Add app** → **Android** и добавьте:
   - **Android package name**: `com.coparently.app`
   - **App nickname** (опционально): `CoParently`
   - **Debug signing certificate SHA-1**: вставьте тот же SHA-1, что использовали выше

### 3.2. Добавление OAuth Client ID

1. В разделе **Your apps** найдите ваше Android приложение
2. Прокрутите до раздела **SHA certificate fingerprints**
3. Убедитесь, что SHA-1 добавлен (если нет - добавьте)
4. Перейдите в **Google Cloud Console** → **APIs & Services** → **Credentials**
5. Найдите только что созданный OAuth Client ID для Android
6. Скопируйте **Client ID** (например: `123456789-...apps.googleusercontent.com`)
7. Вернитесь в **Firebase Console** → **Project Settings**
8. В разделе **Your apps** → Android приложение найдите секцию **OAuth 2.0 Client IDs**
9. Нажмите **Add OAuth 2.0 Client ID**
10. Вставьте скопированный Client ID
11. Нажмите **Save**

**Альтернативный способ (если секции нет):**
- Firebase автоматически синхронизирует OAuth clients из Google Cloud Console
- Просто подождите несколько минут и обновите страницу

## Шаг 4: Обновление google-services.json

### 4.1. Скачивание обновленного файла

1. В **Firebase Console** → **Project Settings**
2. В разделе **Your apps** найдите ваше Android приложение
3. Нажмите на иконку **google-services.json** (или кнопку **Download google-services.json`)
4. Скачайте файл

### 4.2. Замена файла в проекте

1. Откройте скачанный `google-services.json`
2. Скопируйте его содержимое
3. Замените содержимое файла `app/google-services.json` в проекте
4. Убедитесь, что в массиве `oauth_client` появились записи:

```json
"oauth_client": [
  {
    "client_id": "123456789-...apps.googleusercontent.com",
    "client_type": 1,
    "android_info": {
      "package_name": "com.coparently.app",
      "certificate_hash": "XX:XX:XX:XX:..."
    }
  }
]
```

**Важно:**
- `client_type: 1` означает Android клиент
- `certificate_hash` должен соответствовать вашему SHA-1

### 4.3. Проверка правильности настройки

Убедитесь, что в файле:
- ✅ `package_name` совпадает с вашим `applicationId` (`com.coparently.app`)
- ✅ `oauth_client` не пустой массив
- ✅ В `oauth_client` есть запись с `client_type: 1`

## Шаг 5: Проверка работы

1. Соберите проект:
   ```bash
   ./gradlew clean assembleDebug
   ```

2. Запустите приложение на устройстве/эмуляторе

3. Перейдите в **Settings** → **Google Calendar Sync**

4. Нажмите **Sign in with Google**

5. Выберите Google аккаунт

6. Предоставьте разрешения на доступ к Google Calendar

7. Если все настроено правильно, вы увидите:
   - ✅ "Signed in to Google"
   - ✅ Кнопка "Sync from Google Calendar" станет активной

## Важно: Режим тестирования OAuth

После настройки OAuth consent screen приложение будет в режиме **Testing**. Это означает:

- ✅ OAuth consent screen настроен правильно
- ⚠️ Только пользователи, добавленные в **Test users**, могут войти
- ⚠️ Обычные пользователи увидят сообщение: "App is currently being tested"

### Быстрое решение: Добавление тестовых пользователей

Если вы видите ошибку о том, что приложение в режиме тестирования:

1. Перейдите в **OAuth consent screen** → **Test users**
2. Нажмите **+ ADD USERS**
3. Добавьте email адреса пользователей, которым нужен доступ
4. Сохраните

**Подробная инструкция:** См. [quick-fix-test-users.md](./quick-fix-test-users.md)

### Публикация приложения для всех пользователей

Для доступа всех пользователей (production):

1. В **OAuth consent screen** прокрутите вниз
2. Нажмите **PUBLISH APP**
3. Подтвердите публикацию
4. Дождитесь верификации Google (1-7 дней)

**Подробная инструкция:** См. [google-oauth-testing-mode.md](./google-oauth-testing-mode.md)

## Решение проблем

### Проблема: "Sign-in failed" или "App is currently being tested"

**Если видите сообщение "App is currently being tested":**

1. ✅ Это нормально - приложение в режиме Testing
2. ✅ Добавьте свой email в **Test users** в OAuth consent screen
3. ✅ Убедитесь, что используете тот же Google аккаунт, который добавлен
4. ✅ Подождите несколько минут после добавления

**Проверьте:**
1. ✅ SHA-1 в Google Cloud Console совпадает с SHA-1 приложения
2. ✅ Package name точно совпадает (`com.coparently.app`)
3. ✅ Google Calendar API включена
4. ✅ OAuth consent screen настроен
5. ✅ Ваш email добавлен в **Test users**
6. ✅ В `google-services.json` есть `oauth_client` записи

### Проблема: "Failed to get access token"

**Проверьте:**
1. ✅ Scope `https://www.googleapis.com/auth/calendar` добавлен в OAuth consent screen
2. ✅ Пользователь предоставил разрешение на доступ к календарю
3. ✅ Проверьте логи в Logcat с тегом `GoogleSignIn`

### Проблема: OAuth client не появляется в Firebase

**Решение:**
1. Убедитесь, что проект в Google Cloud Console совпадает с Firebase проектом
2. Подождите 5-10 минут (синхронизация может занять время)
3. Перезагрузите страницу Firebase Console
4. Проверьте, что SHA-1 добавлен в Firebase

## Дополнительные ресурсы

- [Google Cloud Console](https://console.cloud.google.com/)
- [Firebase Console](https://console.firebase.google.com/)
- [Google Calendar API Documentation](https://developers.google.com/calendar/api)
- [OAuth 2.0 для Android](https://developers.google.com/identity/protocols/oauth2/native-app)

## Примечания

- Для **debug** версии используется автоматический keystore Android
- Для **release** версии нужно создать свой keystore и добавить его SHA-1
- Можно добавить несколько SHA-1 (для debug и release)
- OAuth Client ID можно использовать для нескольких приложений с одинаковым package name

