import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pharmacy/features/home/application/home_controller.dart';
import 'package:pharmacy/features/home/data/banner_model.dart';
import 'package:pharmacy/features/home/presentation/home_screen.dart';
import 'package:pharmacy/features/home/presentation/widgets/promo_carousel.dart';
import 'package:pharmacy/features/promotions/application/promotions_controller.dart';
import 'package:pharmacy/features/promotions/data/promotion_models.dart';

const _promo = Promotion(id: 'pr_a', productId: 'prod_a', name: 'Аквамарис');

void main() {
  group('BannerModel.fromJson', () {
    test('парсит все поля', () {
      final b = BannerModel.fromJson(const {
        'id': 'bn_1',
        'title': 'Розыгрыш',
        'subtitle': 'Главный приз — 1 000 000 ₸',
        'imageUrl': 'https://cdn.epharm.kz/banners/1.jpg',
        'detailMd': 'Условия акции...',
      });
      expect(b.id, 'bn_1');
      expect(b.title, 'Розыгрыш');
      expect(b.subtitle, 'Главный приз — 1 000 000 ₸');
      expect(b.imageUrl, 'https://cdn.epharm.kz/banners/1.jpg');
      expect(b.detailMd, 'Условия акции...');
    });

    test('nullable subtitle/detailMd → null; отсутствующие строки → пусто', () {
      final b = BannerModel.fromJson(const {
        'id': 'bn_2',
        'title': 'Баннер без деталей',
        'imageUrl': 'https://cdn.epharm.kz/banners/2.jpg',
      });
      expect(b.subtitle, isNull);
      expect(b.detailMd, isNull);
      expect(b.title, 'Баннер без деталей');
    });

    test('битый JSON → безопасные дефолты (пустые строки)', () {
      final b = BannerModel.fromJson(const {});
      expect(b.id, '');
      expect(b.title, '');
      expect(b.imageUrl, '');
      expect(b.subtitle, isNull);
    });
  });

  group('Home: карусель скрывается при пустом списке баннеров', () {
    testWidgets('пустой bannerListProvider → PromoCarousel не рендерится', (
      tester,
    ) async {
      final container = ProviderContainer(
        overrides: [
          promotionsProvider.overrideWith((ref) async => const [_promo]),
          bannerListProvider.overrideWith((ref) async => const <BannerModel>[]),
        ],
      );
      addTearDown(container.dispose);

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const MaterialApp(home: HomeScreen()),
        ),
      );
      await tester.pumpAndSettle();

      // Пустой список баннеров → карусели нет (и нет пустой щели-заглушки).
      expect(find.byType(PromoCarousel), findsNothing);
    });

    testWidgets('непустой bannerListProvider → PromoCarousel рендерится', (
      tester,
    ) async {
      final container = ProviderContainer(
        overrides: [
          promotionsProvider.overrideWith((ref) async => const [_promo]),
          bannerListProvider.overrideWith(
            (ref) async => const [
              BannerModel(
                id: 'bn_1',
                title: 'Розыгрыш',
                imageUrl: 'https://cdn.epharm.kz/banners/1.jpg',
              ),
            ],
          ),
        ],
      );
      addTearDown(container.dispose);

      await tester.pumpWidget(
        UncontrolledProviderScope(
          container: container,
          child: const MaterialApp(home: HomeScreen()),
        ),
      );
      await tester.pumpAndSettle();

      expect(find.byType(PromoCarousel), findsOneWidget);
    });
  });
}
