# Решение проблемы: "Failed to get access token"

Если вы видите ошибку "Failed to get access token", выполните следующие проверки:

## 🔍 Пошаговая диагностика

### Шаг 1: Проверьте Google Calendar API

1. Откройте [Google Cloud Console - APIs](https://console.cloud.google.com/apis/library?project=coparently-a39c9)
2. Найдите "Google Calendar API"
3. Убедитесь, что статус **ENABLED** (Включена)
4. Если не включена:
   - Нажмите на API
   - Нажмите кнопку **ENABLE**
   - Подождите несколько секунд

**Прямая ссылка:** https://console.cloud.google.com/apis/library/calendar-json.googleapis.com?project=coparently-a39c9

### Шаг 2: Проверьте OAuth Client ID для Android

1. Откройте [Google Cloud Console - Credentials](https://console.cloud.google.com/apis/credentials?project=coparently-a39c9)
2. Найдите OAuth 2.0 Client ID с типом **Android**
3. Убедитесь, что:
   - ✅ Package name: `com.coparently.app`
   - ✅ SHA-1 certificate fingerprint добавлен
   - ✅ Client ID существует

**Если OAuth Client ID отсутствует:**
- См. инструкцию: [google-oauth-setup.md](./google-oauth-setup.md)

### Шаг 3: Проверьте OAuth Consent Screen

1. Откройте [OAuth Consent Screen](https://console.cloud.google.com/apis/credentials/consent?project=coparently-a39c9)
2. Убедитесь, что:
   - ✅ App name заполнен
   - ✅ Scopes включают `https://www.googleapis.com/auth/calendar`
   - ✅ Ваш email добавлен в **Test users** (если в режиме Testing)

**Проверка scope:**
- В разделе **Scopes** должна быть запись:
  - `https://www.googleapis.com/auth/calendar` (Google Calendar API)

### Шаг 4: Проверьте google-services.json

1. Откройте файл `app/google-services.json`
2. Проверьте, что:
   - ✅ `package_name` совпадает: `com.coparently.app`
   - ✅ Массив `oauth_client` **не пустой**
   - ✅ В `oauth_client` есть запись с `client_type: 1` (Android)

**Пример правильного содержимого:**
```json
{
  "client": [
    {
      "client_info": {
        "android_client_info": {
          "package_name": "com.coparently.app"
        }
      },
      "oauth_client": [
        {
          "client_id": "123456789-...apps.googleusercontent.com",
          "client_type": 1,
          "android_info": {
            "package_name": "com.coparently.app",
            "certificate_hash": "A4:61:51:71:..."
          }
        }
      ]
    }
  ]
}
```

**Если `oauth_client` пустой `[]`:**
- Скачайте обновленный `google-services.json` из Firebase Console
- Замените файл в проекте
- См. инструкцию: [google-oauth-setup.md](./google-oauth-setup.md), раздел "Шаг 4"

### Шаг 5: Проверьте SHA-1 fingerprint

1. Получите SHA-1 вашего приложения:
   ```bash
   .\gradlew signingReport
   ```

2. Найдите SHA-1 для debug версии (должен быть примерно таким):
   ```
   A4:61:51:71:EC:CD:1F:7C:69:51:17:A3:E8:9D:DE:26:CB:BD:8A:04
   ```

3. Проверьте, что этот SHA-1:
   - ✅ Добавлен в OAuth Client ID в Google Cloud Console
   - ✅ Добавлен в Firebase Console → Project Settings → Your apps → Android app

### Шаг 6: Проверьте тестовых пользователей (если в режиме Testing)

1. Откройте [OAuth Consent Screen](https://console.cloud.google.com/apis/credentials/consent?project=coparently-a39c9)
2. Прокрутите до раздела **Test users**
3. Убедитесь, что:
   - ✅ Ваш email добавлен в список
   - ✅ Вы используете тот же Google аккаунт для входа

**Если email не добавлен:**
- Нажмите **+ ADD USERS**
- Добавьте ваш email
- См. [quick-fix-test-users.md](./quick-fix-test-users.md)

### Шаг 7: Проверьте логи приложения

1. Запустите приложение
2. Откройте Logcat в Android Studio
3. Фильтруйте по тегу: `GoogleSignIn`
4. Попробуйте войти через Google Sign-In
5. Проверьте логи на наличие ошибок

**Что искать в логах:**
- `Calendar scope not granted` - scope не предоставлен
- `GoogleAuthException` - проблема с API или OAuth
- `UserRecoverableAuthException` - требуется разрешение пользователя
- `Token is blank` - токен не получен

## 🛠️ Частые ошибки и решения

### Ошибка: "Calendar scope not granted"

**Причина:** Пользователь не предоставил разрешение на доступ к календарю

**Решение:**
1. Выйдите из Google аккаунта в приложении
2. Войдите снова
3. При запросе разрешений нажмите **Разрешить** для доступа к Google Calendar

### Ошибка: "Google Calendar API is not enabled"

**Причина:** API не включена в Google Cloud Console

**Решение:**
1. Включите Google Calendar API (см. Шаг 1 выше)
2. Подождите несколько секунд
3. Попробуйте снова

### Ошибка: "OAuth 2.0 Client ID is not configured"

**Причина:** OAuth Client ID не создан или не настроен правильно

**Решение:**
1. Создайте OAuth Client ID для Android (см. Шаг 2)
2. Добавьте правильный SHA-1
3. Обновите `google-services.json`
4. См. [google-oauth-setup.md](./google-oauth-setup.md)

### Ошибка: "App is currently being tested"

**Причина:** Приложение в режиме Testing, пользователь не в списке тестеров

**Решение:**
1. Добавьте email пользователя в Test users
2. См. [quick-fix-test-users.md](./quick-fix-test-users.md)

### Ошибка: "Token is empty"

**Причина:** Токен не получен, возможно проблема с конфигурацией

**Решение:**
1. Проверьте все шаги диагностики выше
2. Убедитесь, что Google Calendar API включена
3. Убедитесь, что OAuth Client ID настроен
4. Проверьте логи в Logcat

## ✅ Чек-лист решения проблемы

Пройдитесь по каждому пункту:

- [ ] Google Calendar API включена в Google Cloud Console
- [ ] OAuth Client ID (Android) создан и настроен
- [ ] SHA-1 добавлен в OAuth Client ID
- [ ] SHA-1 добавлен в Firebase
- [ ] OAuth consent screen настроен
- [ ] Scope `https://www.googleapis.com/auth/calendar` добавлен
- [ ] Ваш email добавлен в Test users (если в режиме Testing)
- [ ] `google-services.json` содержит `oauth_client` записи
- [ ] `package_name` в `google-services.json` правильный
- [ ] Приложение пересобрано после изменений

## 🔗 Полезные ссылки

- [Google Cloud Console - APIs](https://console.cloud.google.com/apis/library?project=coparently-a39c9)
- [Google Cloud Console - Credentials](https://console.cloud.google.com/apis/credentials?project=coparently-a39c9)
- [OAuth Consent Screen](https://console.cloud.google.com/apis/credentials/consent?project=coparently-a39c9)
- [Firebase Console](https://console.firebase.google.com/project/coparently-a39c9)
- [Полная инструкция по настройке](./google-oauth-setup.md)

## 📝 Дополнительная информация

Если проблема не решается после выполнения всех шагов:

1. Проверьте логи в Logcat с тегом `GoogleSignIn`
2. Убедитесь, что используете правильный Google аккаунт
3. Попробуйте выйти и войти снова
4. Пересоберите приложение: `./gradlew clean assembleDebug`
5. Убедитесь, что все настройки сохранились (обновите страницы в консоли)

