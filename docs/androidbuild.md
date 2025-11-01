# Инструкция по запуску и сборке CoParently в Android Studio

Полное руководство по настройке, запуску и сборке Android-приложения CoParently.

## Содержание

1. [Требования](#требования)
2. [Установка Android Studio](#установка-android-studio)
3. [Настройка проекта](#настройка-проекта)
4. [Настройка эмулятора](#настройка-эмулятора)
5. [Запуск приложения](#запуск-приложения)
6. [Сборка APK для физического устройства](#сборка-apk-для-физического-устройства)
7. [Установка APK на физическое устройство](#установка-apk-на-физическое-устройство)
8. [Решение проблем](#решение-проблем)

---

## Требования

### Системные требования

**Минимальные:**
- **ОС:** Windows 10/11, macOS 10.14+, или Linux (Ubuntu 18.04+)
- **RAM:** 8 ГБ (рекомендуется 16 ГБ)
- **Дисковое пространство:** 20 ГБ свободного места
- **JDK:** Java Development Kit 17 (JDK 17)

**Для приложения:**
- **Минимальный Android SDK:** 26 (Android 8.0 Oreo)
- **Целевой Android SDK:** 34 (Android 14)
- **Сборка SDK:** 34

### Необходимое программное обеспечение

- **Android Studio:** Hedgehog (2023.1.1) или новее
- **Android SDK Platform:** API Level 34
- **Android SDK Build-Tools:** версия, совместимая с Gradle 8.2.2
- **Android Emulator:** для тестирования на виртуальных устройствах

---

## Установка Android Studio

### Шаг 1: Скачивание Android Studio

1. Перейдите на [официальный сайт Android Studio](https://developer.android.com/studio)
2. Скачайте последнюю версию для вашей операционной системы
3. Запустите установщик и следуйте инструкциям

### Шаг 2: Первоначальная настройка Android Studio

1. Запустите Android Studio
2. Пройдите мастер первоначальной настройки (Setup Wizard)
3. Выберите **Standard** режим установки (рекомендуется)
4. Дождитесь автоматической загрузки необходимых компонентов:
   - Android SDK
   - Android SDK Platform-Tools
   - Android Emulator
   - Intel x86 Emulator Accelerator (HAXM) для Windows/Mac

### Шаг 3: Установка дополнительных компонентов через SDK Manager

1. В Android Studio откройте: **File** → **Settings** (или **Preferences** на macOS)
2. Перейдите: **Appearance & Behavior** → **System Settings** → **Android SDK**
3. Во вкладке **SDK Platforms** убедитесь, что установлены:
   - ✅ **Android 14.0 (API 34)** — обязательный
   - ✅ **Android 8.0 (API 26)** — для минимальной версии
4. Во вкладке **SDK Tools** проверьте наличие:
   - ✅ Android SDK Build-Tools
   - ✅ Android Emulator
   - ✅ Android SDK Platform-Tools
   - ✅ Intel x86 Emulator Accelerator (HAXM installer) — для Windows/Mac
5. Нажмите **Apply** и дождитесь установки

---

## Настройка проекта

### Шаг 1: Открытие проекта в Android Studio

1. Запустите Android Studio
2. Выберите **File** → **Open**
3. Перейдите в папку проекта `CoParently` (где находится файл `settings.gradle.kts`)
4. Нажмите **OK**

### Шаг 2: Синхронизация Gradle

1. Android Studio автоматически обнаружит проект на базе Gradle
2. Если появится уведомление "Gradle files have changed since last project sync", нажмите **Sync Now**
3. Или вручную: **File** → **Sync Project with Gradle Files**
4. Дождитесь окончания синхронизации (первый раз может занять несколько минут)

### Шаг 3: Настройка Google Services (для Firebase)

⚠️ **Важно:** Приложение использует Firebase и Google Services. Для полной работы необходима настройка Firebase.

1. Создайте проект в [Firebase Console](https://console.firebase.google.com/)
2. Добавьте Android-приложение с package name: `com.coparently.app`
3. Скачайте файл `google-services.json`
4. Поместите его в `app/` директорию проекта:
   ```
   app/
   └── google-services.json
   ```
5. Пересинхронизируйте проект: **File** → **Sync Project with Gradle Files**

### Шаг 4: Проверка конфигурации

Убедитесь, что следующие файлы настроены правильно:

**`app/build.gradle.kts`:**
- `minSdk = 26` ✓
- `targetSdk = 34` ✓
- `compileSdk = 34` ✓

**`build.gradle.kts` (корневой):**
- Android Gradle Plugin: `8.2.2` ✓
- Kotlin: `1.9.22` ✓

---

## Настройка эмулятора

### Создание виртуального устройства (AVD)

#### Метод 1: Через Android Studio AVD Manager

1. Откройте **Tools** → **Device Manager** (или **AVD Manager** в старых версиях)
2. Нажмите **Create Device**
3. Выберите устройство:
   - Рекомендуется: **Pixel 6** или **Pixel 7**
   - Или выберите любое другое устройство по вашему выбору
4. Нажмите **Next**
5. Выберите **System Image**:
   - Рекомендуется: **Android 14 (API 34)** — **Tiramisu** или **UpsideDownCake**
   - Минимум: **Android 8.0 (API 26)** — **Oreo**
   - Если система не скачана, нажмите **Download** рядом с нужной версией
6. Нажмите **Next**
7. Проверьте конфигурацию AVD:
   - **AVD Name:** любое имя (например, "Pixel 6 API 34")
   - **Startup orientation:** Portrait (вертикальная)
   - **Graphics:** Automatic (или Hardware - GLES 2.0 для лучшей производительности)
   - **RAM:** минимум 2 ГБ (рекомендуется 4 ГБ)
   - **VM heap:** 512 МБ (можно увеличить до 1024 МБ)
8. Нажмите **Finish**

#### Метод 2: Через командную строку

```bash
# Список доступных системных образов
sdkmanager --list | grep "system-images"

# Создание AVD через avdmanager
avdmanager create avd -n Pixel6_API34 -k "system-images;android-34;google_apis;x86_64" -d "pixel_6"
```

### Запуск эмулятора

1. В **Device Manager** найдите созданное устройство
2. Нажмите кнопку **▶️ Play** рядом с устройством
3. Дождитесь полной загрузки эмулятора (первый запуск может занять несколько минут)

### Рекомендуемые настройки эмулятора

- **API Level:** 34 (Android 14) или выше
- **RAM:** минимум 2 ГБ, рекомендуется 4 ГБ
- **Graphics:** Hardware acceleration (для лучшей производительности)
- **Storage:** минимум 2 ГБ свободного места

---

## Запуск приложения

### Запуск на эмуляторе

1. Убедитесь, что эмулятор запущен и загружен полностью
2. В Android Studio выберите эмулятор в выпадающем списке устройств (вверху панели инструментов)
3. Нажмите кнопку **▶️ Run** (или **Shift+F10**)
4. Или: **Run** → **Run 'app'**
5. Дождитесь сборки и установки приложения на эмулятор
6. Приложение автоматически откроется на эмуляторе

### Запуск на физическом устройстве

1. Подключите Android-устройство к компьютеру через USB
2. На устройстве включите **Режим разработчика**:
   - Перейдите: **Настройки** → **О телефоне**
   - Нажмите 7 раз на **Номер сборки** (Build number)
3. Включите **Отладка по USB**:
   - Перейдите: **Настройки** → **Для разработчиков**
   - Включите **Отладка по USB**
4. На устройстве при подключении появится запрос на разрешение отладки — выберите **Разрешить**
5. В Android Studio в выпадающем списке устройств должно появиться ваше устройство
6. Выберите устройство и нажмите **▶️ Run**

### Проверка работы приложения

После запуска приложение должно:
- ✅ Открыться на экране авторизации
- ✅ Позволить войти через Google Sign-In
- ✅ Отобразить календарь после авторизации

---

## Сборка APK для физического устройства

### Debug APK (для тестирования)

#### Метод 1: Через Android Studio

1. Выберите: **Build** → **Build Bundle(s) / APK(s)** → **Build APK(s)**
2. Дождитесь окончания сборки
3. Появится уведомление "APK(s) generated successfully"
4. Нажмите **locate** в уведомлении или перейдите вручную:
   ```
   app/build/outputs/apk/debug/app-debug.apk
   ```

#### Метод 2: Через командную строку (Terminal/Git Bash)

В корневой директории проекта выполните:

**Windows (PowerShell или CMD):**
```bash
.\gradlew.bat assembleDebug
```

**macOS/Linux:**
```bash
./gradlew assembleDebug
```

**Windows (Git Bash):**
```bash
./gradlew assembleDebug
```

APK будет создан в: `app/build/outputs/apk/debug/app-debug.apk`

### Release APK (для распространения)

⚠️ **Важно:** Release APK требует настройки подписывания (signing).

#### Шаг 1: Создание ключа для подписи

1. Откройте терминал в корне проекта
2. Выполните команду:

**Windows:**
```bash
keytool -genkey -v -keystore coparently-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias coparently
```

**macOS/Linux:**
```bash
keytool -genkey -v -keystore coparently-release-key.jks -keyalg RSA -keysize 2048 -validity 10000 -alias coparently
```

3. Заполните данные:
   - **Пароль:** придумайте надежный пароль (сохраните его!)
   - **Имя:** ваше имя
   - **Организация:** название организации
   - И т.д.

4. Файл `coparently-release-key.jks` будет создан в корне проекта

#### Шаг 2: Настройка signing в build.gradle.kts

1. Создайте файл `keystore.properties` в корне проекта (не коммитьте в git!):
   ```
   storePassword=ваш_пароль_от_ключа
   keyPassword=ваш_пароль_от_ключа
   keyAlias=coparently
   storeFile=../coparently-release-key.jks
   ```

2. Добавьте в `app/build.gradle.kts` перед блоком `android {`:
   ```kotlin
   // Загрузка keystore.properties
   val keystorePropertiesFile = rootProject.file("keystore.properties")
   val keystoreProperties = Properties()
   if (keystorePropertiesFile.exists()) {
       keystoreProperties.load(keystorePropertiesFile.inputStream())
   }
   ```

3. Добавьте в блок `android {` после `buildTypes {`:
   ```kotlin
   signingConfigs {
       create("release") {
           keyAlias = keystoreProperties["keyAlias"] as String
           keyPassword = keystoreProperties["keyPassword"] as String
           storeFile = file(keystoreProperties["storeFile"] as String)
           storePassword = keystoreProperties["storePassword"] as String
       }
   }

   buildTypes {
       release {
           signingConfig = signingConfigs.getByName("release")
           isMinifyEnabled = false
           proguardFiles(
               getDefaultProguardFile("proguard-android-optimize.txt"),
               "proguard-rules.pro"
           )
       }
   }
   ```

#### Шаг 3: Сборка Release APK

**Через Android Studio:**
1. Выберите: **Build** → **Generate Signed Bundle / APK**
2. Выберите **APK** → **Next**
3. Выберите ваш `coparently-release-key.jks` файл
4. Введите пароли
5. Выберите **release** build variant
6. Нажмите **Finish**

**Через командную строку:**
```bash
# Windows
.\gradlew.bat assembleRelease

# macOS/Linux
./gradlew assembleRelease
```

APK будет создан в: `app/build/outputs/apk/release/app-release.apk`

### Добавление keystore в .gitignore

⚠️ **КРИТИЧЕСКИ ВАЖНО:** Никогда не коммитьте файлы ключей в git!

Убедитесь, что в `.gitignore` есть:
```
*.jks
*.keystore
keystore.properties
```

---

## Установка APK на физическое устройство

### Метод 1: Через USB (ADB)

1. Подключите устройство к компьютеру через USB
2. Включите **Отладку по USB** (см. раздел "Запуск на физическом устройстве")
3. Откройте терминал в директории, где находится APK файл
4. Выполните команду:

**Windows:**
```bash
adb install app-debug.apk
```

**macOS/Linux:**
```bash
./adb install app-debug.apk
```

Или укажите полный путь:
```bash
adb install "C:\Git\CoParently\app\build\outputs\apk\debug\app-debug.apk"
```

### Метод 2: Через файловый менеджер устройства

1. Скопируйте APK файл на устройство (через USB, email, облачное хранилище и т.д.)
2. На устройстве откройте файловый менеджер
3. Найдите APK файл
4. Нажмите на него для установки
5. Если появится предупреждение "Установка из неизвестных источников":
   - **Android 8.0+:** Перейдите: **Настройки** → **Безопасность** → **Установка неизвестных приложений**
   - Разрешите установку для используемого приложения (файловый менеджер)
6. Нажмите **Установить**

### Метод 3: Через Android Studio (автоматическая установка)

1. Соберите APK через Android Studio
2. Подключите устройство
3. Выберите устройство в списке устройств
4. Нажмите **▶️ Run**
5. Android Studio автоматически установит APK на устройство

### Проверка установки

1. На устройстве найдите приложение **CoParently** в списке приложений
2. Откройте приложение
3. Проверьте работу основных функций

---

## Решение проблем

### Проблема: Gradle синхронизация не завершается

**Решение:**
1. Проверьте интернет-соединение
2. Закройте Android Studio
3. Удалите папку `.gradle` в домашней директории (или в проекте)
4. Откройте проект заново
5. Попробуйте: **File** → **Invalidate Caches / Restart**

### Проблема: Ошибка "SDK location not found"

**Решение:**
1. Создайте файл `local.properties` в корне проекта (если его нет)
2. Добавьте путь к SDK:
   ```
   sdk.dir=C\:\\Users\\ВашеИмя\\AppData\\Local\\Android\\Sdk
   ```
   (На Windows путь может отличаться)

### Проблема: Эмулятор не запускается или работает медленно

**Решение:**
1. Убедитесь, что включена виртуализация в BIOS/UEFI (Intel VT-x или AMD-V)
2. Для Windows: установите Intel HAXM через SDK Manager
3. Используйте **Hardware acceleration** в настройках эмулятора
4. Увеличьте RAM для эмулятора (минимум 2 ГБ)
5. Используйте x86_64 системный образ (быстрее, чем ARM)

### Проблема: Ошибка компиляции Kotlin

**Решение:**
1. Проверьте версию Kotlin в `build.gradle.kts` (должна быть `1.9.22`)
2. Обновите Kotlin plugin в Android Studio
3. Выполните: **File** → **Sync Project with Gradle Files**

### Проблема: Firebase ошибки

**Решение:**
1. Убедитесь, что файл `google-services.json` находится в `app/` директории
2. Проверьте, что package name в Firebase совпадает с `com.coparently.app`
3. Пересинхронизируйте проект

### Проблема: ADB не видит устройство

**Решение:**
1. Убедитесь, что включена **Отладка по USB**
2. Переподключите USB кабель
3. Выполните `adb kill-server` затем `adb start-server`
4. Проверьте драйверы USB на компьютере
5. Попробуйте другой USB порт или кабель

### Проблема: Ошибка при установке APK "INSTALL_FAILED_UPDATE_INCOMPATIBLE"

**Решение:**
1. Удалите предыдущую версию приложения с устройства
2. Установите APK заново

### Проблема: Ошибка "Unable to resolve dependency"

**Решение:**
1. Проверьте интернет-соединение
2. Проверьте настройки репозиториев в `settings.gradle.kts`
3. Очистите кэш: **File** → **Invalidate Caches / Restart**

---

## Дополнительные ресурсы

- [Официальная документация Android](https://developer.android.com/)
- [Документация Jetpack Compose](https://developer.android.com/jetpack/compose)
- [Документация Hilt](https://dagger.dev/hilt/)
- [Документация Firebase](https://firebase.google.com/docs)

---

## Полезные команды Gradle

```bash
# Очистка проекта
./gradlew clean

# Сборка Debug APK
./gradlew assembleDebug

# Сборка Release APK
./gradlew assembleRelease

# Установка на подключенное устройство
./gradlew installDebug

# Запуск unit-тестов
./gradlew test

# Запуск instrumented тестов
./gradlew connectedAndroidTest

# Просмотр всех задач
./gradlew tasks
```

---

**Версия документа:** 1.0
**Последнее обновление:** 2024

