import '../domain/user.dart';

/// Контракт аутентификации фармацевта. Реализации: [MockAuthRepository] (офлайн-демо) и
/// ApiAuthRepository (реальный backend `/api/mobile/auth/**`). Выбор — по ApiConfig.useApi.
abstract interface class AuthRepository {
  /// Длина OTP-кода в Daribar и во всех режимах ePharm.
  static const int otpCodeLength = 4;

  /// Дефолтный dev-OTP. Совпадает с `app.otp.dev-fixed` на бэке (профиль dev/test).
  /// Используется для авто-подстановки в demo-режиме.
  static const String defaultOtpCode = '5445';

  /// Запросить SMS-код на номер.
  Future<void> requestOtp({required String phone});

  /// Подтвердить код. Результат говорит, нужна ли регистрация или вход уже выполнен.
  Future<AuthVerifyResult> verifyOtp(
      {required String phone, required String code});

  /// Завершить регистрацию нового фармацевта (ФИО + ИИН) → создаётся pending-аккаунт.
  Future<User> register(
      {required String phone, required String fio, required String iin});
}

/// Итог подтверждения кода.
///  - registered=true  → номер уже привязан к фармацевту: [user] заполнен, сессия началась.
///  - registered=false → новый номер: вести на экран ФИО+ИИН (register).
class AuthVerifyResult {
  const AuthVerifyResult({required this.registered, this.user});

  final bool registered;
  final User? user;
}
