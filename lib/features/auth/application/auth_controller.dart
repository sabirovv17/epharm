import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../data/auth_repository.dart';
import '../domain/user.dart';

final authRepositoryProvider = Provider<AuthRepository>((ref) => AuthRepository());

/// Состояние шагов регистрации (нужно, чтобы помнить номер между экранами).
class AuthDraft {
  const AuthDraft({this.phone, this.fio, this.iin});

  final String? phone;
  final String? fio;
  final String? iin;

  AuthDraft copyWith({String? phone, String? fio, String? iin}) => AuthDraft(
        phone: phone ?? this.phone,
        fio: fio ?? this.fio,
        iin: iin ?? this.iin,
      );
}

class AuthDraftNotifier extends Notifier<AuthDraft> {
  @override
  AuthDraft build() => const AuthDraft();

  void setPhone(String phone) => state = state.copyWith(phone: phone);
  void setFio(String fio) => state = state.copyWith(fio: fio);
  void setIin(String iin) => state = state.copyWith(iin: iin);
}

final authDraftProvider =
    NotifierProvider<AuthDraftNotifier, AuthDraft>(AuthDraftNotifier.new);

/// Текущий аутентифицированный пользователь, null если не залогинен.
class CurrentUserNotifier extends Notifier<User?> {
  @override
  User? build() => null;

  void setUser(User user) => state = user;
  void logout() => state = null;
}

final currentUserProvider =
    NotifierProvider<CurrentUserNotifier, User?>(CurrentUserNotifier.new);

/// Action-провайдеры для шагов.
class AuthActions {
  AuthActions(this._ref);
  final Ref _ref;

  AuthRepository get _repo => _ref.read(authRepositoryProvider);

  Future<void> sendOtp(String phone) async {
    _ref.read(authDraftProvider.notifier).setPhone(phone);
    await _repo.requestOtp(phone: phone);
  }

  Future<bool> verifyOtp(String code) async {
    final phone = _ref.read(authDraftProvider).phone ?? '';
    return _repo.verifyOtp(phone: phone, code: code);
  }

  Future<User> completeRegistration({required String fio, required String iin}) async {
    final phone = _ref.read(authDraftProvider).phone ?? '';
    _ref.read(authDraftProvider.notifier).setFio(fio);
    _ref.read(authDraftProvider.notifier).setIin(iin);
    final user = await _repo.completeRegistration(phone: phone, fio: fio, iin: iin);
    _ref.read(currentUserProvider.notifier).setUser(user);
    return user;
  }
}

final authActionsProvider = Provider<AuthActions>((ref) => AuthActions(ref));
