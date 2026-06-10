import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Пара токенов фармацевта (access + refresh), как отдаёт backend в поле `tokens`.
class AuthTokens {
  const AuthTokens({required this.accessToken, required this.refreshToken});

  final String accessToken;
  final String refreshToken;

  factory AuthTokens.fromJson(Map<String, dynamic> json) => AuthTokens(
        accessToken: json['accessToken'] as String,
        refreshToken: json['refreshToken'] as String,
      );
}

/// Хранилище токенов сессии: in-memory кэш (синхронный доступ для [ApiClient]) +
/// **персист** в защищённом хранилище ОС (Keychain/EncryptedSharedPreferences),
/// чтобы пользователь оставался залогинен между запусками приложения.
///
/// Запись/удаление — write-through (fire-and-forget): in-memory обновляется сразу,
/// диск догоняет асинхронно. [load] вызывается на старте (см. main.dart). Ошибки
/// секьюр-хранилища (например, отсутствие нативного плагина в unit-тестах) безопасно
/// игнорируются — приложение работает без персиста.
class TokenStore {
  TokenStore({FlutterSecureStorage? storage})
      : _storage = storage ?? const FlutterSecureStorage();

  final FlutterSecureStorage _storage;
  AuthTokens? _tokens;

  static const _kAccess = 'epharm_access_token';
  static const _kRefresh = 'epharm_refresh_token';

  AuthTokens? get tokens => _tokens;
  bool get hasTokens => _tokens != null;

  /// Подгружает персистнутые токены в память. Вызывать однократно на старте.
  Future<void> load() async {
    try {
      final access = await _storage.read(key: _kAccess);
      final refresh = await _storage.read(key: _kRefresh);
      if (access != null && access.isNotEmpty && refresh != null && refresh.isNotEmpty) {
        _tokens = AuthTokens(accessToken: access, refreshToken: refresh);
      }
    } catch (_) {
      // Секьюр-хранилище недоступно (unit-тест без плагина) — без персиста.
    }
  }

  void save(AuthTokens tokens) {
    _tokens = tokens;
    _storage.write(key: _kAccess, value: tokens.accessToken).catchError((_) {});
    _storage.write(key: _kRefresh, value: tokens.refreshToken).catchError((_) {});
  }

  void clear() {
    _tokens = null;
    _storage.delete(key: _kAccess).catchError((_) {});
    _storage.delete(key: _kRefresh).catchError((_) {});
  }
}

final tokenStoreProvider = Provider<TokenStore>((ref) => TokenStore());
