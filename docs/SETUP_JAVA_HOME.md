# Настройка JAVA_HOME для Gradle

## Проблема

Ошибка: `ERROR: JAVA_HOME is not set and no 'java' command could be found in your PATH`

## Решение

### Быстрый способ (для текущей сессии PowerShell)

1. **Используйте готовый скрипт:**
   ```powershell
   .\setup-java-home.ps1
   ```

2. **Или установите вручную:**
   ```powershell
   $env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
   $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
   ```

### Постоянная установка JAVA_HOME (рекомендуется)

#### Способ 1: Через графический интерфейс Windows

1. Нажмите `Win + R`, введите `sysdm.cpl` и нажмите Enter
2. Перейдите на вкладку **Дополнительно**
3. Нажмите **Переменные среды**
4. В разделе **Системные переменные** нажмите **Создать**
5. Укажите:
   - **Имя переменной:** `JAVA_HOME`
   - **Значение переменной:** `C:\Program Files\Android\Android Studio\jbr`
6. Нажмите **OK**
7. Найдите переменную **Path** в системных переменных, выделите её и нажмите **Изменить**
8. Нажмите **Создать** и добавьте: `%JAVA_HOME%\bin`
9. Нажмите **OK** во всех окнах
10. **Перезапустите** терминал/PowerShell

#### Способ 2: Через PowerShell (от администратора)

```powershell
# Установить JAVA_HOME системно (требуются права администратора)
[System.Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Android\Android Studio\jbr", [System.EnvironmentVariableTarget]::Machine)

# Добавить Java в PATH
$currentPath = [System.Environment]::GetEnvironmentVariable("Path", [System.EnvironmentVariableTarget]::Machine)
if ($currentPath -notlike "*%JAVA_HOME%\bin*") {
    [System.Environment]::SetEnvironmentVariable("Path", "$currentPath;%JAVA_HOME%\bin", [System.EnvironmentVariableTarget]::Machine)
}
```

**Важно:** После выполнения команд перезапустите PowerShell.

#### Способ 3: Для пользователя (без прав администратора)

```powershell
# Установить JAVA_HOME для текущего пользователя
[System.Environment]::SetEnvironmentVariable("JAVA_HOME", "C:\Program Files\Android\Android Studio\jbr", [System.EnvironmentVariableTarget]::User)

# Добавить Java в PATH пользователя
$currentPath = [System.Environment]::GetEnvironmentVariable("Path", [System.EnvironmentVariableTarget]::User)
if ($currentPath -notlike "*%JAVA_HOME%\bin*") {
    [System.Environment]::SetEnvironmentVariable("Path", "$currentPath;%JAVA_HOME%\bin", [System.EnvironmentVariableTarget]::User)
}
```

### Проверка установки

После установки откройте **новый** терминал PowerShell и выполните:

```powershell
# Проверка JAVA_HOME
echo $env:JAVA_HOME

# Проверка версии Java
java -version

# Проверка Gradle
.\gradlew.bat --version
```

Если всё работает, вы увидите:
- Путь к JDK для JAVA_HOME
- Версию Java (например, 21.0.8)
- Версию Gradle (8.5)

## Альтернативные пути к JDK

Если Android Studio установлена в другом месте, найдите JDK:

1. **Через Android Studio:**
   - File → Settings → Build, Execution, Deployment → Build Tools → Gradle
   - Проверьте путь к JDK в настройках проекта

2. **Типичные пути:**
   - `C:\Program Files\Android\Android Studio\jbr`
   - `C:\Program Files\JetBrains\Android Studio\jbr`
   - `%LOCALAPPDATA%\Programs\Android\Android Studio\jbr`

3. **Проверить наличие JDK:**
   ```powershell
   Get-ChildItem "C:\Program Files" -Recurse -Filter "java.exe" -ErrorAction SilentlyContinue | Select-Object FullName
   ```

## Использование Gradle после настройки

После настройки JAVA_HOME вы можете использовать Gradle команды:

```powershell
# Сборка проекта
.\gradlew.bat build

# Очистка проекта
.\gradlew.bat clean

# Сборка Debug APK
.\gradlew.bat assembleDebug

# Сборка Release APK
.\gradlew.bat assembleRelease

# Установка на подключенное устройство
.\gradlew.bat installDebug
```

## Примечания

- **Android Studio использует свой собственный JDK** (JetBrains Runtime), который обычно находится в `jbr` папке установки Android Studio
- Для Android-разработки рекомендуется использовать JDK, который поставляется с Android Studio
- Версия Java должна быть 17 или выше для Android Gradle Plugin 8.2.2

