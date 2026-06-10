import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app.dart';
import 'core/config/api_config.dart';
import 'core/network/token_store.dart';
import 'features/profile/application/profile_controller.dart';

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);

  // Один контейнер на всё приложение — чтобы восстановить сессию ДО первого кадра
  // (роутер синхронно читает currentUserProvider для редиректа).
  final container = ProviderContainer();
  await _restoreSession(container);

  runApp(UncontrolledProviderScope(container: container, child: const PharmacyApp()));
}

/// Восстановление сессии при запуске: если в защищённом хранилище есть токены —
/// тянем профиль (`/api/mobile/me`) и логиним пользователя (он попадёт сразу на Home).
/// Протухшие токены (refresh не прошёл) → fetchMe бросит ошибку → пользователь
/// останется null (экран приветствия), токены очищены в ApiClient.
Future<void> _restoreSession(ProviderContainer container) async {
  if (!ApiConfig.useApi) return;
  final tokenStore = container.read(tokenStoreProvider);
  await tokenStore.load();
  if (!tokenStore.hasTokens) return;
  await container.read(profileActionsProvider).refreshMe();
}
