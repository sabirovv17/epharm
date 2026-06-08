import '../../../core/network/api_client.dart';
import '../../../core/network/token_store.dart';
import '../domain/user.dart';
import 'auth_repository.dart';

/// Реальная реализация поверх backend `/api/mobile/auth/**`.
/// На verify/register сохраняет токены в [TokenStore] (далее их подставляет ApiClient).
class ApiAuthRepository implements AuthRepository {
  ApiAuthRepository(this._client, this._tokenStore);

  final ApiClient _client;
  final TokenStore _tokenStore;

  @override
  Future<void> requestOtp({required String phone}) async {
    await _client.postJson('/api/mobile/auth/sms/request', {'phone': phone}, auth: false);
  }

  @override
  Future<AuthVerifyResult> verifyOtp({required String phone, required String code}) async {
    final json = await _client.postJson(
      '/api/mobile/auth/sms/verify',
      {'phone': phone, 'code': code},
      auth: false,
    );
    final registered = json['registered'] as bool? ?? false;
    if (registered) {
      _tokenStore.save(AuthTokens.fromJson(json['tokens'] as Map<String, dynamic>));
      return AuthVerifyResult(
        registered: true,
        user: User.fromMeJson(json['pharmacist'] as Map<String, dynamic>),
      );
    }
    return const AuthVerifyResult(registered: false);
  }

  @override
  Future<User> register({required String phone, required String fio, required String iin}) async {
    final json = await _client.postJson(
      '/api/mobile/auth/register',
      {'phone': phone, 'fio': fio, 'iin': iin},
      auth: false,
    );
    _tokenStore.save(AuthTokens.fromJson(json['tokens'] as Map<String, dynamic>));
    return User.fromMeJson(json['pharmacist'] as Map<String, dynamic>);
  }
}
