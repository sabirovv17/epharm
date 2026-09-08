import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:sentry_flutter/sentry_flutter.dart';

import 'app.dart';
import 'core/network/card_store.dart';
import 'core/network/image_cache_config.dart';

Future<void> main() async {
  await SentryFlutter.init(
    (options) {
      options
        ..dsn = const String.fromEnvironment('SENTRY_DSN')
        ..environment = const String.fromEnvironment(
          'APP_ENVIRONMENT',
          defaultValue: 'development',
        )
        ..release =
            'epharm-mobile@${const String.fromEnvironment('RELEASE_ID', defaultValue: 'dev')}'
        ..tracesSampleRate = _sentryTracesSampleRate()
        ..sendDefaultPii = false
        ..enableAutoSessionTracking = true
        // Receipt photos can contain personal data; never attach UI screenshots.
        ..attachScreenshot = false;
    },
    appRunner: _bootstrap,
  );
}

double _sentryTracesSampleRate() {
  const raw = String.fromEnvironment(
    'SENTRY_TRACES_SAMPLE_RATE',
    defaultValue: '0.05',
  );
  final value = double.tryParse(raw);
  return value != null && value >= 0 && value <= 1 ? value : 0.05;
}

void _bootstrap() {
  WidgetsFlutterBinding.ensureInitialized();
  SystemChrome.setPreferredOrientations([DeviceOrientation.portraitUp]);
  // Лёгкий тюнинг in-memory imageCache (диск-кэш фото витрины — основной фикс
  // лагов скролла — в MediaImage/MediaCache).
  tuneImageCache();

  // Keep the handler installed by Sentry and preserve Flutter's console diagnostics.
  final sentryFlutterErrorHandler = FlutterError.onError;
  FlutterError.onError = (FlutterErrorDetails details) {
    if (sentryFlutterErrorHandler != null) {
      sentryFlutterErrorHandler(details);
    } else {
      FlutterError.presentError(details);
    }
  };

  final container = ProviderContainer();
  // Дефолтная карта (ДОП.7) — локальна, грузим всегда (независимо от useApi),
  // заранее, чтобы экран чека префилил её синхронно из памяти.
  unawaited(container.read(cardStoreProvider).load());
  // Восстановление сессии и решение «куда стартовать» (Home/Welcome) — внутри
  // SplashScreen через appStartProvider: он читает персистнутые токены ДО показа
  // онбординга, поэтому залогиненный его не увидит (фикс гонки восстановления сессии).

  runApp(UncontrolledProviderScope(
      container: container, child: const PharmacyApp()));
}
