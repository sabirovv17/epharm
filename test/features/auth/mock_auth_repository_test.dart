import 'package:flutter_test/flutter_test.dart';
import 'package:pharmacy/core/network/api_exception.dart';
import 'package:pharmacy/features/auth/data/auth_repository.dart';
import 'package:pharmacy/features/auth/data/mock_auth_repository.dart';

void main() {
  final repo = MockAuthRepository();

  test('verifyOtp с дефолтным кодом → registered=false (на регистрацию)', () async {
    final r = await repo.verifyOtp(phone: '+77001112233', code: AuthRepository.defaultOtpCode);
    expect(r.registered, false);
    expect(r.user, isNull);
  });

  test('verifyOtp с неверным кодом → ApiException OTP_INVALID', () async {
    expect(
      () => repo.verifyOtp(phone: '+77001112233', code: '000000'),
      throwsA(isA<ApiException>().having((e) => e.code, 'code', 'OTP_INVALID')),
    );
  });

  test('register создаёт пользователя с нулевым балансом', () async {
    final u = await repo.register(phone: '+77001112233', fio: 'Иван Иванов', iin: '990101000111');
    expect(u.fio, 'Иван Иванов');
    expect(u.phone, '+77001112233');
    expect(u.balanceKzt, 0);
  });
}
