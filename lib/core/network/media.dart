import '../config/api_config.dart';

/// URL для загрузки фото товара (витрина Medusa) в приложении.
///
/// Medusa отдаёт фото по голому HTTP (`http://host:9000/static/…`), а релизная
/// сборка ходит ТОЛЬКО по HTTPS (cleartext запрещён сетевой политикой Android/iOS) —
/// значит http-картинки напрямую НЕ грузятся (видны буквы-плейсхолдеры).
///
/// Решение единое с админкой: гоним фото через наш backend-прокси
/// `/api/media/img` (тот же эндпоинт, тот же SSRF-guard). Получаем HTTPS-ссылку
/// на наш домен — фото грузятся в релизе на обеих платформах, единый пайплайн
/// медиа для всей экосистемы.
///
/// http → `${ApiConfig.baseUrl}/api/media/img?u=<encoded>`; https/пусто — как есть.
String? proxyMedia(String? url) {
  final u = url?.trim();
  if (u == null || u.isEmpty) return null;
  if (u.startsWith('http://')) {
    return '${ApiConfig.baseUrl}/api/media/img?u=${Uri.encodeComponent(u)}';
  }
  return u; // https / data: / уже прокси — без изменений
}
