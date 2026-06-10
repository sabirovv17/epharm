import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app.dart';
import 'core/config/api_config.dart';
import 'core/network/token_store.dart';
import 'features/profile/application/profile_controller.dart';

void main() {
  // Глобальный перехват необработанных ошибок (async/zone). Логируем —
  // НЕ подменяя UI: отдельные экраны показывают свои ошибки сами (snackbar/inline).
  // TODO(P1): отправлять в Sentry.
  runZonedGuarded(_bootstrap, (error, stack) {
    debugPrint('Необработанная ошибка: $error\n$stack');
  });
}

void _bootstrap() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);

  // Ошибки фреймворка (build/layout/paint) — в консоль (в release не валят приложение).
  FlutterError.onError = (FlutterErrorDetails details) {
    FlutterError.presentError(details);
    debugPrint('FlutterError: ${details.exceptionAsString()}');
  };

  final container = ProviderContainer();
  unawaited(_restoreSession(container));

  runApp(UncontrolledProviderScope(container: container, child: const PharmacyApp()));
}

/// Восстановление сессии в фоне: если в защищённом хранилище есть токены — тянем
/// профиль (`/api/mobile/me`) и логиним пользователя. Ошибки/зависания не блокируют UI.
Future<void> _restoreSession(ProviderContainer container) async {
  if (!ApiConfig.useApi) return;
  try {
    final tokenStore = container.read(tokenStoreProvider);
    await tokenStore.load().timeout(const Duration(seconds: 3));
    if (!tokenStore.hasTokens) return;
    await container
        .read(profileActionsProvider)
        .refreshMe()
        .timeout(const Duration(seconds: 6));
  } catch (_) {
    // Хранилище/сеть недоступны — остаёмся на экране приветствия.
  }
}
