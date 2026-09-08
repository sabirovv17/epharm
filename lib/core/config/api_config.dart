/// Конфигурация подключения к backend. Значения задаются через --dart-define при сборке.
///
/// ПРОД (по умолчанию): `USE_API=true`, `API_BASE=https://epharm.inkar.kz`.
/// Релизная сборка без флагов идёт на прод (fail-safe) — на localhost она бы 100%
/// не работала на реальном устройстве.
/// Production-сборка не имеет cleartext fallback. Резервный origin можно передать
/// через `API_FALLBACK_BASE_URL`, но для релиза он также должен быть HTTPS.
///
/// Для боевой сборки можно явно переопределить адрес через `API_BASE`.
///
/// ЛОКАЛЬНАЯ РАЗРАБОТКА — переопределяй явно, напр.:
///   flutter run --dart-define=API_BASE=http://10.0.2.2:8080   (Android-эмулятор)
///   flutter run --dart-define=API_BASE=http://localhost:8080  (iOS-симулятор)
///   flutter run --dart-define=USE_API=false                   (офлайн на mock-репозиториях)
class ApiConfig {
  ApiConfig._();

  /// Переключатель mock ↔ реальный HTTP backend. По умолчанию **true** — реальные данные.
  /// Для офлайн-демо/разработки без бэкенда: `--dart-define=USE_API=false`.
  static const bool useApi =
      bool.fromEnvironment('USE_API', defaultValue: true);

  /// База API без trailing slash. Пути добавляются как `/api/mobile/...`.
  /// Дефолт — прод; локально переопределяется `--dart-define=API_BASE=...`.
  static const String baseUrl = String.fromEnvironment('API_BASE',
      defaultValue: 'https://epharm.inkar.kz');

  /// Запасной origin выключен по умолчанию. CI/release-процесс не задаёт его,
  /// пока не появится второй проверенный HTTPS ingress.
  static const String fallbackBaseUrl = String.fromEnvironment(
    'API_FALLBACK_BASE_URL',
    defaultValue: '',
  );

  static List<String> get fallbackBaseUrls {
    final fallback = fallbackBaseUrl.trim().replaceFirst(RegExp(r'/+$'), '');
    if (fallback.isEmpty || fallback == baseUrl) return const [];
    return [fallback];
  }
}
