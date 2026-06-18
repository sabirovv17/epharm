import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pharmacy/features/receipts/application/receipts_controller.dart';

void main() {
  group('ReceiptDraft.promoIds', () {
    test('по умолчанию promoIds пустой', () {
      const draft = ReceiptDraft();
      expect(draft.promoIds, isEmpty);
    });

    test('copyWith сохраняет promoIds, если не передан', () {
      const draft = ReceiptDraft(promoIds: ['pr_1']);
      final next = draft.copyWith(card: '4444 4444 4444 4448');
      expect(next.promoIds, ['pr_1']);
    });
  });

  group('ReceiptDraftNotifier.setClaimedPromo', () {
    test('устанавливает promoIds = [id]', () {
      final container = ProviderContainer();
      addTearDown(container.dispose);

      final notifier = container.read(receiptDraftProvider.notifier);
      expect(container.read(receiptDraftProvider).promoIds, isEmpty);

      notifier.setClaimedPromo('pr_42');
      expect(container.read(receiptDraftProvider).promoIds, ['pr_42']);
    });

    test('повторный вызов ЗАМЕНЯЕТ заявленную акцию (одна карточка — одна акция)', () {
      final container = ProviderContainer();
      addTearDown(container.dispose);

      final notifier = container.read(receiptDraftProvider.notifier);
      notifier.setClaimedPromo('pr_1');
      notifier.setClaimedPromo('pr_2');
      expect(container.read(receiptDraftProvider).promoIds, ['pr_2']);
    });

    test('reset() обнуляет promoIds (после submit акция больше не заявлена)', () {
      final container = ProviderContainer();
      addTearDown(container.dispose);

      final notifier = container.read(receiptDraftProvider.notifier);
      notifier.setClaimedPromo('pr_1');
      notifier.reset();
      expect(container.read(receiptDraftProvider).promoIds, isEmpty);
    });

    // Ревью-баг #1: акция «прилипала» к долгоживущему драфту при отмене загрузки и
    // уходила со следующим, не связанным с ней чеком. Теперь не-бонусный вход
    // (showUploadPromptSheet без promoId → setClaimedPromo(null)) очищает её.
    test('setClaimedPromo(null) ОЧИЩАЕТ ранее заявленную акцию', () {
      final container = ProviderContainer();
      addTearDown(container.dispose);

      final notifier = container.read(receiptDraftProvider.notifier);
      notifier.setClaimedPromo('pr_1');
      notifier.setClaimedPromo(null);
      expect(container.read(receiptDraftProvider).promoIds, isEmpty);
    });

    test('setClaimedPromo(пустая строка) тоже очищает', () {
      final container = ProviderContainer();
      addTearDown(container.dispose);

      final notifier = container.read(receiptDraftProvider.notifier);
      notifier.setClaimedPromo('pr_1');
      notifier.setClaimedPromo('');
      expect(container.read(receiptDraftProvider).promoIds, isEmpty);
    });
  });

  group('ReceiptDraft.copyWith(clearPromo)', () {
    test('clearPromo:true сбрасывает promoIds в пустой список', () {
      const draft = ReceiptDraft(promoIds: ['pr_1']);
      final next = draft.copyWith(clearPromo: true);
      expect(next.promoIds, isEmpty);
    });
  });
}
