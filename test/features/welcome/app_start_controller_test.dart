import 'package:flutter_test/flutter_test.dart';
import 'package:pharmacy/core/network/token_store.dart';
import 'package:pharmacy/core/storage/onboarding_store.dart';
import 'package:pharmacy/features/welcome/application/app_start_controller.dart';

/// Фейк хранилища токенов: hasTokens задаём в конструкторе, секьюр-стораджа не трогаем.
class _FakeTokenStore extends TokenStore {
  _FakeTokenStore(this._has);
  final bool _has;
  @override
  Future<void> load() async {}
  @override
  bool get hasTokens => _has;
}

/// Фейк флага онбординга: seen() возвращает заданное значение.
class _FakeOnboardingStore extends OnboardingStore {
  _FakeOnboardingStore(this._seen);
  final bool _seen;
  @override
  Future<bool> seen() async => _seen;
  @override
  void markSeen() {}
}

void main() {
  group('resolveStartDestination — куда стартовать', () {
    test('есть токены → Home, сессия восстанавливается, онбординг не показываем', () async {
      var restored = false;
      final dest = await resolveStartDestination(
        useApi: true,
        tokenStore: _FakeTokenStore(true),
        onboardingStore: _FakeOnboardingStore(false),
        restoreSession: () async => restored = true,
      );
      expect(dest, StartDestination.home);
      expect(restored, isTrue, reason: 'при наличии токенов тянем профиль');
    });

    test('нет токенов, онбординг уже видели → Home (повторно не навязываем)', () async {
      final dest = await resolveStartDestination(
        useApi: true,
        tokenStore: _FakeTokenStore(false),
        onboardingStore: _FakeOnboardingStore(true),
        restoreSession: () async {},
      );
      expect(dest, StartDestination.home);
    });

    test('нет токенов, онбординг не видели (новое устройство) → Welcome', () async {
      final dest = await resolveStartDestination(
        useApi: true,
        tokenStore: _FakeTokenStore(false),
        onboardingStore: _FakeOnboardingStore(false),
        restoreSession: () async {},
      );
      expect(dest, StartDestination.welcome);
    });

    test('mock-режим (useApi=false): токены игнорируем, решает только флаг онбординга', () async {
      var restored = false;
      final seen = await resolveStartDestination(
        useApi: false,
        tokenStore: _FakeTokenStore(true), // даже если есть — в mock не смотрим
        onboardingStore: _FakeOnboardingStore(true),
        restoreSession: () async => restored = true,
      );
      expect(seen, StartDestination.home);
      expect(restored, isFalse, reason: 'в mock-режиме сессию не восстанавливаем');

      final fresh = await resolveStartDestination(
        useApi: false,
        tokenStore: _FakeTokenStore(false),
        onboardingStore: _FakeOnboardingStore(false),
        restoreSession: () async {},
      );
      expect(fresh, StartDestination.welcome);
    });

    test('restoreSession упал (нет сети) → всё равно Home (гостевой режим, ретрай на resume)', () async {
      final dest = await resolveStartDestination(
        useApi: true,
        tokenStore: _FakeTokenStore(true),
        onboardingStore: _FakeOnboardingStore(false),
        restoreSession: () async => throw Exception('network down'),
      );
      expect(dest, StartDestination.home);
    });
  });
}
