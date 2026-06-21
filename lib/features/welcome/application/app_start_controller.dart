import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/config/api_config.dart';
import '../../../core/network/token_store.dart';
import '../../../core/storage/onboarding_store.dart';
import '../../profile/application/profile_controller.dart';

/// Куда отправить пользователя на старте: онбординг (Welcome) или сразу Home.
enum StartDestination { welcome, home }

/// Чистое решение «куда стартовать», тестируемое без UI/сети.
///
/// Правило (фикс бага «онбординг у залогиненного»): если на устройстве есть
/// сохранённая сессия (токены) — пользователь ЗАЛОГИНЕН → всегда Home, онбординг
/// не показываем. Если токенов нет, но онбординг уже видели — тоже Home (повторно
/// не навязываем). Иначе (новое устройство) — Welcome.
///
/// Решение принимается ДО показа Welcome и опирается на ПЕРСИСТНУТЫЙ токен, а не на
/// гонку с фоновым восстановлением сессии — поэтому залогиненный Welcome не увидит.
///
/// [restoreSession] — побочный эффект восстановления профиля (refreshMe) при наличии
/// токенов; ждём его (с таймаутом внутри), чтобы Home сразу был с балансом.
Future<StartDestination> resolveStartDestination({
  required bool useApi,
  required TokenStore tokenStore,
  required OnboardingStore onboardingStore,
  required Future<void> Function() restoreSession,
}) async {
  if (useApi) {
    try {
      await tokenStore.load().timeout(const Duration(seconds: 3));
    } catch (_) {
      // Секьюр-хранилище недоступно/зависло — считаем, что токенов нет.
    }
    if (tokenStore.hasTokens) {
      try {
        await restoreSession();
      } catch (_) {
        // Профиль не подтянулся (сеть) — Home покажет гостевой режим, ретрай на resume.
      }
      return StartDestination.home;
    }
  }
  // Токенов нет (или mock-режим): онбординг только если ещё не видели.
  // Таймаут на чтение флага: на ПЕРВОМ cold-start iOS Keychain может отвечать
  // медленно/зависнуть — без таймаута сплеш висел бы бесконечно (симптом
  // «приложение открывается со второго раза»). Истёк → считаем «не видели».
  final seen = await onboardingStore
      .seen()
      .timeout(const Duration(seconds: 2), onTimeout: () => false);
  return seen ? StartDestination.home : StartDestination.welcome;
}

/// Стартовый провайдер: выполняет [resolveStartDestination] один раз. SplashScreen
/// слушает его и навигирует на Home/Welcome. Восстановление сессии (refreshMe) —
/// внутри, с таймаутом, чтобы не зависнуть на сплеше при недоступной сети.
final appStartProvider = FutureProvider<StartDestination>((ref) async {
  // Провайдер ОБЯЗАН резолвиться, иначе сплеш зависнет навсегда (нет навигации →
  // «второй тап»). Все ветки resolveStartDestination уже защищены try/catch, но
  // внешний guard страхует от любой неожиданной ошибки: тогда уходим на Home
  // (гостевой режим доступен всем; не показываем онбординг по ошибке).
  try {
    return await resolveStartDestination(
      useApi: ApiConfig.useApi,
      tokenStore: ref.read(tokenStoreProvider),
      onboardingStore: ref.read(onboardingStoreProvider),
      restoreSession: () => ref
          .read(profileActionsProvider)
          .refreshMe()
          .timeout(const Duration(seconds: 6)),
    );
  } catch (_) {
    return StartDestination.home;
  }
});
