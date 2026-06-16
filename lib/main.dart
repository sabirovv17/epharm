import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'app.dart';
import 'core/network/card_store.dart';

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
  // Дефолтная карта (ДОП.7) — локальна, грузим всегда (независимо от useApi),
  // заранее, чтобы экран чека префилил её синхронно из памяти.
  unawaited(container.read(cardStoreProvider).load());
  // Восстановление сессии и решение «куда стартовать» (Home/Welcome) — внутри
  // SplashScreen через appStartProvider: он читает персистнутые токены ДО показа
  // онбординга, поэтому залогиненный его не увидит (фикс гонки восстановления сессии).

  runApp(UncontrolledProviderScope(container: container, child: const PharmacyApp()));
}
