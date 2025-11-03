# Текущие SHA-1 значения для CoParently

## Debug версия (текущий)

**SHA-1**: `A4:61:51:71:EC:CD:1F:7C:69:51:17:A3:E8:9D:DE:26:CB:BD:8A:04`

**Keystore**: `C:\Users\Dell\.android\debug.keystore`

**Использование:**
- Этот SHA-1 нужно добавить в OAuth Client ID в Google Cloud Console
- Этот SHA-1 нужно добавить в Firebase Console → Project Settings → Your apps → Android app

## Release версия

**Status**: Release keystore не настроен

**Примечание:** Для release версии нужно будет:
1. Создать release keystore
2. Получить его SHA-1
3. Добавить SHA-1 в OAuth Client ID и Firebase

## Команда для получения SHA-1

```bash
# Debug версия
.\gradlew signingReport

# Release версия (после создания keystore)
keytool -list -v -keystore "путь_к_keystore.jks" -alias ваш_alias
```

## Следующие шаги

1. Скопируйте SHA-1 выше: `A4:61:51:71:EC:CD:1F:7C:69:51:17:A3:E8:9D:DE:26:CB:BD:8A:04`
2. Перейдите в [Google Cloud Console - Credentials](https://console.cloud.google.com/apis/credentials)
3. Создайте OAuth Client ID для Android
4. Вставьте SHA-1 в поле "SHA-1 certificate fingerprint"
5. Добавьте package name: `com.coparently.app`
6. Сохраните и скопируйте Client ID
7. Добавьте Client ID в Firebase Console

Подробная инструкция: [google-oauth-setup.md](./google-oauth-setup.md)

