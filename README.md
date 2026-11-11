# FanFilm4K — WebView-обёртка

Приложение открывает `https://v16.fanfilm4k.media/` в WebView на весь экран.
Адрес и домен, который остаётся внутри приложения, меняются в двух константах
в `app/src/main/java/app/fanfilm4k/MainActivity.java`.

## Сборка APK в облаке (GitHub Actions)

1. Создай пустой репозиторий на GitHub.
2. Загрузи в него всё содержимое этой папки, включая `.github/workflows/build.yml`.
3. Вкладка **Actions** → **build-apk** → **Run workflow**.
4. Когда сборка закончится, в самом запуске внизу появится артефакт `fanfilm4k-debug-apk`. Внутри `app-debug.apk`.
5. Поставь APK на телефон (нужно разрешить установку из неизвестных источников).

## Сборка в Android Studio

File → Open → эта папка → Build → Build APK(s).
Файл появится в `app/build/outputs/apk/debug/app-debug.apk`.
