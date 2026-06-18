import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pharmacy/features/catalog/data/catalog_models.dart';
import 'package:pharmacy/features/home/application/home_controller.dart';

void main() {
  group('homeRecoPoolProvider — пилюли Альтернативы/Дополнения', () {
    test('по умолчанию none; toggle включает; повторный toggle выключает', () {
      final c = ProviderContainer();
      addTearDown(c.dispose);
      expect(c.read(homeRecoPoolProvider), RecoPool.none);

      c.read(homeRecoPoolProvider.notifier).toggle(RecoPool.alternatives);
      expect(c.read(homeRecoPoolProvider), RecoPool.alternatives);

      // Повторный тап по активной пилюле — выключает (назад к ленте акций).
      c.read(homeRecoPoolProvider.notifier).toggle(RecoPool.alternatives);
      expect(c.read(homeRecoPoolProvider), RecoPool.none);
    });

    test('переключение между пилюлями взаимоисключающее', () {
      final c = ProviderContainer();
      addTearDown(c.dispose);
      c.read(homeRecoPoolProvider.notifier).toggle(RecoPool.alternatives);
      c.read(homeRecoPoolProvider.notifier).toggle(RecoPool.crosssells);
      expect(c.read(homeRecoPoolProvider), RecoPool.crosssells);
    });
  });

  group('CatalogRecommendationPools.fromJson', () {
    test('парсит alternatives/crosssells', () {
      final pools = CatalogRecommendationPools.fromJson(const {
        'alternatives': [
          {'id': 'A', 'name': 'Товар A'},
        ],
        'crosssells': [
          {'id': 'B', 'name': 'Товар B'},
        ],
      });
      expect(pools.alternatives.map((p) => p.id).toList(), ['A']);
      expect(pools.crosssells.map((p) => p.id).toList(), ['B']);
      expect(pools.isEmpty, isFalse);
    });

    test('пустой/битый JSON → пустые пулы', () {
      expect(CatalogRecommendationPools.fromJson(const {}).isEmpty, isTrue);
    });
  });
}
