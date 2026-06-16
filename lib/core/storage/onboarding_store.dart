import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Флаг «онбординг (Welcome) уже показан». Персист в защищённом хранилище ОС —
/// та же инфраструктура, что [TokenStore]/[CardStore]. Зачем: онбординг нужен
/// НОВОМУ пользователю; вернувшийся (тем более залогиненный) видеть его не должен.
///
/// Паттерн повторяет CardStore: write-through (fire-and-forget), ошибки секьюр-
/// хранилища (нет нативного плагина в unit-тестах) безопасно игнорируются.
class OnboardingStore {
  OnboardingStore({FlutterSecureStorage? storage})
      : _storage = storage ?? const FlutterSecureStorage();

  final FlutterSecureStorage _storage;
  static const _kSeen = 'epharm_seen_onboarding';

  /// Показывали ли онбординг. Ошибка чтения (нет плагина / новое устройство) →
  /// `false` — безопасный дефолт (лучше лишний раз показать, чем спрятать новому).
  Future<bool> seen() async {
    try {
      return (await _storage.read(key: _kSeen)) == '1';
    } catch (_) {
      return false;
    }
  }

  /// Отметить онбординг показанным — после прохождения/выхода из Welcome.
  void markSeen() {
    _storage.write(key: _kSeen, value: '1').catchError((_) {});
  }
}

final onboardingStoreProvider =
    Provider<OnboardingStore>((ref) => OnboardingStore());
