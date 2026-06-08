import '../../../core/network/api_exception.dart';
import '../domain/user.dart';
import 'auth_repository.dart';

/// Mock-реализация: эмулирует сетевые задержки, принимает только [AuthRepository.defaultOtpCode].
/// Всегда ведёт на регистрацию (registered=false) — у мока нет базы существующих фармацевтов.
class MockAuthRepository implements AuthRepository {
  @override
  Future<void> requestOtp({required String phone}) async {
    await Future<void>.delayed(const Duration(milliseconds: 800));
  }

  @override
  Future<AuthVerifyResult> verifyOtp({required String phone, required String code}) async {
    await Future<void>.delayed(const Duration(milliseconds: 500));
    if (code != AuthRepository.defaultOtpCode) {
      throw const ApiException(message: 'Неверный код', code: 'OTP_INVALID', statusCode: 400);
    }
    return const AuthVerifyResult(registered: false);
  }

  @override
  Future<User> register({required String phone, required String fio, required String iin}) async {
    await Future<void>.delayed(const Duration(milliseconds: 600));
    return User(fio: fio, phone: phone, iin: iin);
  }
}
