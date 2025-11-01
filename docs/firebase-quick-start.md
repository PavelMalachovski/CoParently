# Firebase Setup - Быстрый старт

## Краткая инструкция

### 1. Откройте Firebase Console
👉 [console.firebase.google.com](https://console.firebase.google.com/)

### 2. Добавьте Android-приложение
1. Нажмите **⚙️ Project settings** (или **Add app**)
2. Нажмите **➕ Add another app** → выберите **🟢 Android**
3. Заполните:
   - **Android package name:** `com.coparently.app`
   - **App nickname:** `CoParently` (опционально)
   - **SHA-1:** (см. ниже - опционально, но рекомендуется)

### 3. Получите SHA-1 (для Google Sign-In)

Откройте PowerShell в проекте и выполните:

```powershell
cd C:\Git\CoParently
keytool -list -v -keystore "$env:USERPROFILE\.android\debug.keystore" -alias androiddebugkey -storepass android -keypass android
```

Найдите строку **SHA1:** и скопируйте значение (например: `A1:B2:C3:D4:E5:F6:...`)

Вставьте в Firebase Console в поле **Debug signing certificate SHA-1**

### 4. Скачайте google-services.json
1. После регистрации нажмите **Download google-services.json**
2. Или: **⚙️ Project settings** → **General** → **Your apps** → ваше Android-приложение → **Download google-services.json**

### 5. Поместите файл в проект

Переместите `google-services.json` в:
```
C:\Git\CoParently\app\google-services.json
```

**Важно:** Файл должен быть в папке `app/`, на том же уровне, что и `build.gradle.kts`

### 6. Синхронизируйте проект
В Android Studio: **File** → **Sync Project with Gradle Files**

### 7. Готово! ✅

---

📖 **Подробная инструкция:** [firebase-setup.md](firebase-setup.md)

