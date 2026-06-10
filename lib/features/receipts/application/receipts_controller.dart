import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/config/api_config.dart';
import '../../../core/network/api_client.dart';
import '../../../core/validation/card.dart';
import '../../home/data/home_repository.dart';
import '../data/api_pharmacy_repository.dart';
import '../data/api_receipt_repository.dart';
import '../data/mock_pharmacy_repository.dart';
import '../data/mock_receipt_repository.dart';
import '../data/nearby_pharmacies.dart';
import '../data/pharmacy_repository.dart';
import '../data/receipt_repository.dart';

/// Выбор реализации: реальный backend при USE_API=true, иначе mock (офлайн-демо).
/// Singleton per-container — у обеих реализаций stateful changes-стрим.
final receiptRepositoryProvider = Provider<ReceiptRepository>((ref) {
  if (ApiConfig.useApi) {
    return ApiReceiptRepository(ref.read(apiClientProvider));
  }
  return MockReceiptRepository();
});

/// Источник аптек для AddressSheet: реальный реестр при USE_API=true, иначе mock.
final pharmacyRepositoryProvider = Provider<PharmacyRepository>((ref) {
  if (ApiConfig.useApi) {
    return ApiPharmacyRepository(ref.read(apiClientProvider));
  }
  return MockPharmacyRepository();
});

/// Список аптек (загружается один раз; поиск в AddressSheet — client-side по нему).
final pharmacyListProvider = FutureProvider<List<NearbyPharmacy>>(
  (ref) => ref.read(pharmacyRepositoryProvider).list(),
);

/// Список чеков фармацевта. Использует `StreamProvider` чтобы автоматически
/// перезаливать список при `repository.addReceipt(...)`.
final receiptListProvider = StreamProvider<List<Receipt>>((ref) async* {
  final repo = ref.watch(receiptRepositoryProvider);
  // первая загрузка
  yield await repo.loadReceipts();
  // дальше — на каждый change.
  await for (final _ in repo.changes) {
    yield await repo.loadReceipts();
  }
});

/// In-progress черновик чека. Состоит из:
///   • фото чека (snapshot из CameraScreen)
///   • [promos] — выбранные акции из каталога (через PromoPickerScreen)
///   • [pharmacy] — аптека, где была покупка (через AddressSheet)
///   • [card] — отформатированный номер бонусной карты «1234 5678 9012 3456»
///     (через CardSheet); сохраняется между чеками в рамках сессии
///
/// Источник: `_reference/recipe/README.md` → state-machine.
class ReceiptDraft {
  const ReceiptDraft({
    this.photoPath,
    this.promos = const [],
    this.pharmacy,
    this.card,
  });

  final String? photoPath;
  final List<Product> promos;
  final NearbyPharmacy? pharmacy;

  /// Отформатированная маска «1234 5678 9012 3456» (с пробелами). Без пробелов
  /// 16 цифр == валидно.
  final String? card;

  bool get hasPromos => promos.isNotEmpty;
  bool get hasPharmacy => pharmacy != null;

  /// Карта привязана И проходит алгоритм Луна (та же проверка, что в CardSheet).
  bool get hasCard => isValidCardNumber(card);

  /// Все три пункта чек-листа заполнены — кнопка «Продолжить» активна.
  bool get isComplete => hasPromos && hasPharmacy && hasCard;

  ReceiptDraft copyWith({
    String? photoPath,
    List<Product>? promos,
    NearbyPharmacy? pharmacy,
    String? card,
    bool clearPhoto = false,
    bool clearPharmacy = false,
  }) =>
      ReceiptDraft(
        photoPath: clearPhoto ? null : (photoPath ?? this.photoPath),
        promos: promos ?? this.promos,
        pharmacy: clearPharmacy ? null : (pharmacy ?? this.pharmacy),
        card: card ?? this.card,
      );
}

class ReceiptDraftNotifier extends Notifier<ReceiptDraft> {
  @override
  ReceiptDraft build() => const ReceiptDraft();

  void setPhoto(String path) => state = state.copyWith(photoPath: path);

  void setPromos(List<Product> picked) =>
      state = state.copyWith(promos: List.unmodifiable(picked));

  void setPharmacy(NearbyPharmacy p) => state = state.copyWith(pharmacy: p);

  /// Принимает форматированный номер «1234 5678 9012 3456». Card persists
  /// между submission'ами в рамках сессии — её НЕ обнуляет `reset()` по
  /// умолчанию (см. [reset(keepCard: true)]).
  void setCard(String formatted) => state = state.copyWith(card: formatted);

  /// После Submit: обнуляет всё кроме card (она остаётся «привязанной» в
  /// рамках сессии). Полный reset (включая card) — через
  /// `reset(keepCard: false)`.
  void reset({bool keepCard = true}) {
    state = ReceiptDraft(card: keepCard ? state.card : null);
  }
}

final receiptDraftProvider =
    NotifierProvider<ReceiptDraftNotifier, ReceiptDraft>(
  ReceiptDraftNotifier.new,
);
