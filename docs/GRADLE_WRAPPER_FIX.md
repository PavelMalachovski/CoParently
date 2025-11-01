# Исправление ошибки Gradle Configuration

## Проблема

Ошибка: `'org.gradle.api.file.FileCollection org.gradle.api.artifacts.Configuration.fileCollection(org.gradle.api.specs.Spec)'`

Эта ошибка возникает из-за отсутствия или неправильной версии Gradle wrapper.

## Решение

### Шаг 1: Генерация Gradle Wrapper через Android Studio

1. Откройте проект в Android Studio
2. Перейдите: **File** → **Settings** (или **Preferences** на macOS)
3. Перейдите: **Build, Execution, Deployment** → **Build Tools** → **Gradle**
4. Убедитесь, что выбрано: **Gradle wrapper (recommended)**
5. Нажмите **OK**
6. В терминале Android Studio (или в корне проекта) выполните:

**Windows (PowerShell):**
```bash
cd C:\Git\CoParently
.\gradlew.bat wrapper --gradle-version 8.5
```

**macOS/Linux:**
```bash
cd /path/to/CoParently
./gradlew wrapper --gradle-version 8.5
```

Если команда не работает (так как wrapper ещё не создан), используйте установленный Gradle:

**Если у вас установлен Gradle глобально:**
```bash
gradle wrapper --gradle-version 8.5
```

### Шаг 2: Альтернативный способ - через Android Studio напрямую

1. В Android Studio откройте терминал (View → Tool Windows → Terminal)
2. В корне проекта выполните одну из команд:

**Если Gradle установлен:**
```bash
gradle wrapper --gradle-version 8.5
```

**Если Gradle не установлен, Android Studio должен создать wrapper автоматически при первой синхронизации**

### Шаг 3: Синхронизация проекта

1. После создания wrapper, перейдите: **File** → **Sync Project with Gradle Files**
2. Дождитесь окончания синхронизации

### Шаг 4: Очистка кеша (если проблема сохраняется)

1. Закройте Android Studio
2. Удалите папки `.gradle` в корне проекта (если есть)
3. Откройте Android Studio заново
4. Выполните: **File** → **Invalidate Caches / Restart** → **Invalidate and Restart**

## Что было исправлено

1. ✅ Создан файл `gradle/wrapper/gradle-wrapper.properties` с версией Gradle 8.5
2. ✅ Созданы скрипты `gradlew.bat` (Windows) и `gradlew` (Unix)
3. ✅ Обновлена версия плагина `google-services` с 4.4.0 до 4.4.2
4. ✅ Обновлена версия Compose Compiler с 1.5.8 до 1.5.14

## Проверка

После выполнения шагов выше, попробуйте собрать проект:

**Windows:**
```bash
.\gradlew.bat build
```

**macOS/Linux:**
```bash
./gradlew build
```

Если сборка проходит успешно, проблема решена.

