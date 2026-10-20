import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/config/api_config.dart';
import '../../../core/network/api_client.dart';
import '../../../core/network/token_store.dart';
import '../data/api_auth_repository.dart';
import '../data/auth_repository.dart';
import '../data/mock_auth_repository.dart';
import '../domain/user.dart';

/// Выбор реализации: реальный backend при USE_API=true, иначе mock (офлайн-демо).
final authRepositoryProvider = Provider<AuthRepository>((ref) {
  if (ApiConfig.useApi) {
    return ApiAuthRepository(
      ref.read(apiClientProvider),
      ref.read(tokenStoreProvider),
    );
  }
  return MockAuthRepository();
});

/// Итог шага OTP для навигации.
enum OtpOutcome { loggedIn, needsRegistration }

/// Состояние шагов регистрации (нужно, чтобы помнить номер между экранами).
class AuthDraft {
  const AuthDraft({this.phone, this.fio, this.iin, this.phoneMasked});

  final String? phone;
  final String? fio;
  final String? iin;
  final String? phoneMasked;

  AuthDraft copyWith({
    String? phone,
    String? fio,
    String? iin,
    String? phoneMasked,
  }) => AuthDraft(
    phone: phone ?? this.phone,
    fio: fio ?? this.fio,
    iin: iin ?? this.iin,
    phoneMasked: phoneMasked ?? this.phoneMasked,
  );
}

class AuthDraftNotifier extends Notifier<AuthDraft> {
  @override
  AuthDraft build() => const AuthDraft();

  void setPhone(String phone) => state = state.copyWith(phone: phone);
  void setFio(String fio) => state = state.copyWith(fio: fio);
  void setIin(String iin) => state = state.copyWith(iin: iin);
  void setPhoneMasked(String phoneMasked) =>
      state = state.copyWith(phoneMasked: phoneMasked);
}

final authDraftProvider = NotifierProvider<AuthDraftNotifier, AuthDraft>(
  AuthDraftNotifier.new,
);

/// Текущий аутентифицированный пользователь, null если не залогинен.
class CurrentUserNotifier extends Notifier<User?> {
  @override
  User? build() => null;

  void setUser(User user) => state = user;

  void logout() {
    // Чистим персистнутые токены — иначе на следующем старте сессия восстановится.
    ref.read(tokenStoreProvider).clear();
    state = null;
  }
}

final currentUserProvider = NotifierProvider<CurrentUserNotifier, User?>(
  CurrentUserNotifier.new,
);

/// Action-провайдеры для шагов.
class AuthActions {
  AuthActions(this._ref);
  final Ref _ref;

  AuthRepository get _repo => _ref.read(authRepositoryProvider);

  Future<void> sendOtp(String phone) async {
    _ref.read(authDraftProvider.notifier).setPhone(phone);
    await _repo.requestOtp(phone: phone);
  }

  /// Подтверждает код. Если номер уже фармацевта — выполняет вход (ставит currentUser) и
  /// возвращает [OtpOutcome.loggedIn]; иначе — [OtpOutcome.needsRegistration] (на экран ФИО+ИИН).
  /// Бросает ApiException на неверном/истёкшем коде — экран показывает сообщение.
  Future<OtpOutcome> verifyOtp(String code) async {
    final phone = _ref.read(authDraftProvider).phone ?? '';
    final result = await _repo.verifyOtp(phone: phone, code: code);
    if (result.registered && result.user != null) {
      _ref.read(currentUserProvider.notifier).setUser(result.user!);
      return OtpOutcome.loggedIn;
    }
    return OtpOutcome.needsRegistration;
  }

  Future<User> completeRegistration({
    required String fio,
    required String iin,
  }) async {
    final phone = _ref.read(authDraftProvider).phone ?? '';
    _ref.read(authDraftProvider.notifier).setFio(fio);
    _ref.read(authDraftProvider.notifier).setIin(iin);
    final user = await _repo.register(phone: phone, fio: fio, iin: iin);
    _ref.read(currentUserProvider.notifier).setUser(user);
    return user;
  }

  Future<bool> checkIin(String iin) async {
    final status = await _repo.activationStatus(iin: iin);
    final draft = _ref.read(authDraftProvider.notifier);
    draft.setIin(iin);
    draft.setPhoneMasked(status.phoneMasked);
    return status.passwordSet;
  }

  Future<void> sendActivationOtp(String phone) async {
    final draft = _ref.read(authDraftProvider);
    final iin = draft.iin ?? '';
    _ref.read(authDraftProvider.notifier).setPhone(phone);
    await _repo.requestActivationOtp(iin: iin, phone: phone);
  }

  Future<void> verifyActivationOtp(String code) async {
    final draft = _ref.read(authDraftProvider);
    await _repo.verifyActivationOtp(
      iin: draft.iin ?? '',
      phone: draft.phone ?? '',
      code: code,
    );
  }

  Future<User> createInitialPassword(String password) async {
    final draft = _ref.read(authDraftProvider);
    final user = await _repo.setInitialPassword(
      iin: draft.iin ?? '',
      phone: draft.phone ?? '',
      password: password,
    );
    _ref.read(currentUserProvider.notifier).setUser(user);
    return user;
  }

  Future<User> loginWithPassword(String password) async {
    final iin = _ref.read(authDraftProvider).iin ?? '';
    final user = await _repo.loginWithPassword(iin: iin, password: password);
    _ref.read(currentUserProvider.notifier).setUser(user);
    return user;
  }
}

final authActionsProvider = Provider<AuthActions>((ref) => AuthActions(ref));
