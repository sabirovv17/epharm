import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/config/api_config.dart';
import '../../../core/network/api_client.dart';
import '../../../core/network/card_store.dart';
import '../../../core/validation/card.dart';
import '../data/api_receipt_repository.dart';
import '../data/mock_receipt_repository.dart';
import '../data/receipt_repository.dart';

/// Выбор реализации: реальный backend при USE_API=true, иначе mock (офлайн-демо).
/// Singleton per-container — у обеих реализаций stateful changes-стрим.
final receiptRepositoryProvider = Provider<ReceiptRepository>((ref) {
  if (ApiConfig.useApi) {
    return ApiReceiptRepository(ref.read(apiClientProvider));
  }
  return MockReceiptRepository();
});

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

/// In-progress черновик чека. ДОП.8: акции и аптека больше НЕ выбираются вручную —
/// аптека берётся из профиля, акции система матчит сама (POSM/OCR). Остаётся:
///   • фото чека (snapshot из CameraScreen)
///   • [card] — отформатированный номер бонусной карты «1234 5678 9012 3456»
///     (через CardSheet); сохраняется между чеками в рамках сессии (ДОП.7)
///
/// Источник: `_reference/recipe/README.md` → state-machine.
class ReceiptDraft {
  const ReceiptDraft({
    this.photoPath,
    this.card,
  });

  final String? photoPath;

  /// Отформатированная маска «1234 5678 9012 3456» (с пробелами). Без пробелов
  /// 16 цифр == валидно.
  final String? card;

  /// Карта привязана И проходит алгоритм Луна (та же проверка, что в CardSheet).
  bool get hasCard => isValidCardNumber(card);

  /// Готово к отправке: есть фото чека (обязательно — это его доказательство) И карта.
  /// Без фото CTA неактивна, чтобы не уйти на сервер с null-файлом.
  bool get isComplete => hasCard && (photoPath != null && photoPath!.isNotEmpty);

  ReceiptDraft copyWith({
    String? photoPath,
    String? card,
    bool clearPhoto = false,
  }) =>
      ReceiptDraft(
        photoPath: clearPhoto ? null : (photoPath ?? this.photoPath),
        card: card ?? this.card,
      );
}

class ReceiptDraftNotifier extends Notifier<ReceiptDraft> {
  @override
  ReceiptDraft build() {
    // Префилл дефолтной картой (ДОП.7). CardStore.load() выполняется на старте
    // (main.dart), здесь читаем уже из памяти — синхронно, без async-гонок в build().
    return ReceiptDraft(card: ref.read(cardStoreProvider).card);
  }

  void setPhoto(String path) => state = state.copyWith(photoPath: path);

  /// Принимает форматированный номер «1234 5678 9012 3456». Card persists
  /// между submission'ами в рамках сессии — её НЕ обнуляет `reset()` по
  /// умолчанию (см. [reset(keepCard: true)]).
  void setCard(String formatted) {
    state = state.copyWith(card: formatted);
    // Запоминаем валидную карту как дефолтную — чтобы не вводить её снова (ДОП.7).
    if (isValidCardNumber(formatted)) ref.read(cardStoreProvider).save(formatted);
  }

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
