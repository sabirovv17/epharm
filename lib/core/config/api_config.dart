/// Конфигурация подключения к backend. Значения задаются через --dart-define при сборке.
///
/// ПРОД (по умолчанию): `USE_API=true`, `API_BASE=https://api.epharm.kz`.
/// Релизная сборка без флагов идёт на прод (fail-safe) — на localhost она бы 100%
/// не работала на реальном устройстве.
///
/// ЛОКАЛЬНАЯ РАЗРАБОТКА — переопределяй явно, напр.:
///   flutter run --dart-define=API_BASE=http://10.0.2.2:8080   (Android-эмулятор)
///   flutter run --dart-define=API_BASE=http://localhost:8080  (iOS-симулятор)
///   flutter run --dart-define=USE_API=false                   (офлайн на mock-репозиториях)
class ApiConfig {
  ApiConfig._();

  /// Переключатель mock ↔ реальный HTTP backend. По умолчанию **true** — реальные данные.
  /// Для офлайн-демо/разработки без бэкенда: `--dart-define=USE_API=false`.
  static const bool useApi = bool.fromEnvironment('USE_API', defaultValue: true);

  /// База API без trailing slash. Пути добавляются как `/api/mobile/...`.
  /// Дефолт — прод; локально переопределяется `--dart-define=API_BASE=...`.
  static const String baseUrl =
      String.fromEnvironment('API_BASE', defaultValue: 'https://api.epharm.kz');
}
