# Быстрая справка: OAuth 2.0 для Android

## Получение SHA-1 fingerprint

### Для Debug версии (автоматический keystore)

```bash
# Windows PowerShell
cd C:\Git\CoParently
.\gradlew signingReport
```

Найдите в выводе:
```
Variant: debug
SHA1: XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX:XX
```

### Для Release версии (ваш keystore)

```bash
# Windows PowerShell
keytool -list -v -keystore "путь_к_вашему_keystore.jks" -alias ваш_alias
```

## Ключевые параметры для CoParently

- **Package name**: `com.coparently.app`
- **Project ID**: `coparently-a39c9`
- **Project Number**: `492948924829`
- **API**: Google Calendar API
- **Scope**: `https://www.googleapis.com/auth/calendar`

## Чек-лист настройки

- [ ] Google Calendar API включена в Google Cloud Console
- [ ] OAuth consent screen настроен
- [ ] OAuth Client ID (Android) создан в Google Cloud Console
- [ ] SHA-1 добавлен в OAuth Client ID
- [ ] SHA-1 добавлен в Firebase → Project Settings → Your apps → Android app
- [ ] OAuth Client ID добавлен в Firebase (или синхронизирован автоматически)
- [ ] `google-services.json` обновлен и содержит `oauth_client` записи
- [ ] Приложение пересобрано после обновления `google-services.json`

## Проверка google-services.json

Убедитесь, что файл содержит:

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
            "certificate_hash": "XX:XX:XX:XX:..."
          }
        }
      ]
    }
  ]
}
```

**Важно:** `oauth_client` не должен быть пустым массивом `[]`

## Ссылки

- [Google Cloud Console - Credentials](https://console.cloud.google.com/apis/credentials)
- [Firebase Console - Project Settings](https://console.firebase.google.com/project/coparently-a39c9/settings/general)
- [Полная инструкция](./google-oauth-setup.md)

