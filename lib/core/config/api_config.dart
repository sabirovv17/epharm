/// Конфигурация подключения к backend. Значения задаются через --dart-define при сборке:
///   flutter run --dart-define=USE_API=true --dart-define=API_BASE=http://10.0.2.2:8080
///
/// По умолчанию USE_API=false → приложение работает на mock-репозиториях (офлайн-разработка,
/// демо без поднятого бэка). 10.0.2.2 — алиас хоста для Android-эмулятора; на iOS-симуляторе
/// используется localhost.
class ApiConfig {
  ApiConfig._();

  /// Переключатель mock ↔ реальный HTTP backend.
  static const bool useApi = bool.fromEnvironment('USE_API', defaultValue: false);

  /// База API без trailing slash. Пути добавляются как `/api/mobile/...`.
  static const String baseUrl =
      String.fromEnvironment('API_BASE', defaultValue: 'http://localhost:8080');
}
