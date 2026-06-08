/// Ошибка обращения к backend. Несёт машинный код (зеркалит ErrorCode бэкенда —
/// OTP_INVALID, OTP_EXPIRED, CONFLICT, PHARMACIST_BLOCKED, …) для UX-ветвления и
/// человекочитаемое message для показа пользователю.
class ApiException implements Exception {
  const ApiException({
    required this.message,
    this.code,
    this.statusCode,
  });

  /// Сообщение для пользователя (бэкенд кладёт его в поле `message`).
  final String message;

  /// Машинный код (`code` из ApiErrorResponse), null если сеть/парсинг.
  final String? code;

  /// HTTP-статус ответа, null если ошибка до получения ответа.
  final int? statusCode;

  /// Фолбэк-ошибка сети (нет ответа / таймаут / не распарсили).
  const ApiException.network()
      : message = 'Ошибка сети. Проверьте подключение и попробуйте ещё раз.',
        code = null,
        statusCode = null;

  @override
  String toString() => 'ApiException($statusCode, $code): $message';
}
