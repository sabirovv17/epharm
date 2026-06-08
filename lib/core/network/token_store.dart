import 'package:flutter_riverpod/flutter_riverpod.dart';

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

/// Хранилище токенов сессии. На MVP — в памяти (живёт пока запущено приложение).
/// Когда понадобится «оставаться залогиненным между запусками» — сюда добавим
/// persistence (flutter_secure_storage), интерфейс не изменится.
class TokenStore {
  AuthTokens? _tokens;

  AuthTokens? get tokens => _tokens;
  bool get hasTokens => _tokens != null;

  void save(AuthTokens tokens) => _tokens = tokens;
  void clear() => _tokens = null;
}

final tokenStoreProvider = Provider<TokenStore>((ref) => TokenStore());
