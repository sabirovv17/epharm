import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/config/api_config.dart';
import '../../../core/network/api_client.dart';
import '../../../core/network/api_exception.dart';
import '../../auth/application/auth_controller.dart';
import '../data/me_repository.dart';

/// В mock-режиме репозитория нет (баланс статичен) → null. В API-режиме — реальный.
final meRepositoryProvider = Provider<MeRepository?>((ref) {
  if (!ApiConfig.useApi) return null;
  return ApiMeRepository(ref.read(apiClientProvider));
});

class ProfileActions {
  ProfileActions(this._ref);
  final Ref _ref;

  /// Подтянуть свежий профиль (баланс мог измениться после одобрения чека).
  /// В mock-режиме — no-op. Ошибки глотаем: баланс не критичен для отрисовки экрана.
  Future<void> refreshMe() async {
    final repo = _ref.read(meRepositoryProvider);
    if (repo == null) return;
    try {
      final me = await repo.fetchMe();
      _ref.read(currentUserProvider.notifier).setUser(me);
    } on ApiException {
      // Молча: показываем последний известный баланс.
    }
  }
}

final profileActionsProvider = Provider<ProfileActions>((ref) => ProfileActions(ref));
