import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

/// Хранилище бонусной карты фармацевта по умолчанию: in-memory кэш (синхронный
/// доступ) + **персист** в защищённом хранилище ОС (Keychain / EncryptedSharedPreferences).
///
/// Зачем: чтобы не привязывать карту при КАЖДОЙ загрузке чека — один раз ввёл,
/// дальше она подставляется как дефолтная (ДОП.7). Карта — не секрет уровня токена,
/// но лежит в том же защищённом хранилище и не попадает в обычные prefs/логи.
///
/// Паттерн повторяет [TokenStore]: write-through (fire-and-forget), ошибки
/// секьюр-хранилища (нет нативного плагина в unit-тестах) безопасно игнорируются.
class CardStore {
  CardStore({FlutterSecureStorage? storage})
      : _storage = storage ?? const FlutterSecureStorage();

  final FlutterSecureStorage _storage;
  String? _card;

  static const _kCard = 'epharm_default_card';

  /// Дефолтная карта в памяти (или null, если ещё не вводили/не загрузили).
  String? get card => _card;

  /// Подгружает сохранённую карту в память. Идемпотентно (повторный вызов — no-op).
  Future<void> load() async {
    if (_card != null) return;
    try {
      final v = await _storage.read(key: _kCard);
      if (v != null && v.isNotEmpty) _card = v;
    } catch (_) {
      // Секьюр-хранилище недоступно (unit-тест без плагина) — без персиста.
    }
  }

  void save(String card) {
    _card = card;
    _storage.write(key: _kCard, value: card).catchError((_) {});
  }

  void clear() {
    _card = null;
    _storage.delete(key: _kCard).catchError((_) {});
  }
}

final cardStoreProvider = Provider<CardStore>((ref) => CardStore());
