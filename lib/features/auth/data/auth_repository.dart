import '../domain/user.dart';

/// Mock-репозиторий аутентификации.
/// Эмулирует сетевые задержки. Принимает дефолтный 6-значный OTP `544544`.
class AuthRepository {
  /// Дефолтный mock-код подтверждения для авторизации по телефону.
  static const String defaultOtpCode = '544544';

  /// «Отправить SMS» — задержка 800 мс.
  Future<void> requestOtp({required String phone}) async {
    await Future<void>.delayed(const Duration(milliseconds: 800));
  }

  /// «Проверить OTP» — 500 мс. Принимаем только `defaultOtpCode`.
  Future<bool> verifyOtp({required String phone, required String code}) async {
    await Future<void>.delayed(const Duration(milliseconds: 500));
    return code == defaultOtpCode;
  }

  /// «Завершить регистрацию» — создать пользователя.
  Future<User> completeRegistration({
    required String phone,
    required String fio,
    required String iin,
  }) async {
    await Future<void>.delayed(const Duration(milliseconds: 600));
    return User(fio: fio, phone: phone, iin: iin);
  }
}
