import '../../../core/network/api_exception.dart';
import '../domain/user.dart';
import 'auth_repository.dart';

/// Mock-реализация: эмулирует сетевые задержки, принимает только [AuthRepository.defaultOtpCode].
/// Всегда ведёт на регистрацию (registered=false) — у мока нет базы существующих фармацевтов.
class MockAuthRepository implements AuthRepository {
  final Map<String, String> _passwordsByIin = <String, String>{};

  @override
  Future<void> requestOtp({required String phone}) async {
    await Future<void>.delayed(const Duration(milliseconds: 800));
  }

  @override
  Future<AuthVerifyResult> verifyOtp({
    required String phone,
    required String code,
  }) async {
    await Future<void>.delayed(const Duration(milliseconds: 500));
    if (code != AuthRepository.defaultOtpCode) {
      throw const ApiException(
        message: 'Неверный код',
        code: 'OTP_INVALID',
        statusCode: 400,
      );
    }
    return const AuthVerifyResult(registered: false);
  }

  @override
  Future<User> register({
    required String phone,
    required String fio,
    required String iin,
  }) async {
    await Future<void>.delayed(const Duration(milliseconds: 600));
    return User(fio: fio, phone: phone, iin: iin);
  }

  @override
  Future<ActivationStatus> activationStatus({required String iin}) async {
    await Future<void>.delayed(const Duration(milliseconds: 350));
    return ActivationStatus(
      passwordSet: _passwordsByIin.containsKey(iin),
      phoneMasked: '+7 (***) ***-**-53',
    );
  }

  @override
  Future<void> requestActivationOtp({
    required String iin,
    required String phone,
  }) async {
    await Future<void>.delayed(const Duration(milliseconds: 500));
  }

  @override
  Future<void> verifyActivationOtp({
    required String iin,
    required String phone,
    required String code,
  }) async {
    await Future<void>.delayed(const Duration(milliseconds: 350));
    if (code != AuthRepository.defaultOtpCode) {
      throw const ApiException(
        message: 'Неверный код',
        code: 'OTP_INVALID',
        statusCode: 400,
      );
    }
  }

  @override
  Future<User> setInitialPassword({
    required String iin,
    required String phone,
    required String password,
  }) async {
    await Future<void>.delayed(const Duration(milliseconds: 450));
    if (_passwordsByIin.containsKey(iin)) {
      throw const ApiException(
        message: 'Пароль для этого ИИН уже создан. Войдите по ИИН и паролю',
        code: 'CONFLICT',
        statusCode: 409,
      );
    }
    _passwordsByIin[iin] = password;
    return User(fio: 'Фармацевт ePharm', phone: phone, iin: iin);
  }

  @override
  Future<User> loginWithPassword({
    required String iin,
    required String password,
  }) async {
    await Future<void>.delayed(const Duration(milliseconds: 400));
    if (_passwordsByIin[iin] != password) {
      throw const ApiException(
        message: 'Неверный ИИН или пароль',
        code: 'INVALID_CREDENTIALS',
        statusCode: 401,
      );
    }
    return User(fio: 'Фармацевт ePharm', phone: '+77470799353', iin: iin);
  }
}
