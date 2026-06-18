import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pharmacy/features/catalog/application/catalog_controller.dart';
import 'package:pharmacy/features/catalog/data/catalog_models.dart';
import 'package:pharmacy/features/catalog/data/catalog_repository.dart';
import 'package:pharmacy/features/catalog/presentation/catalog_product_sheet.dart';

/// Фейк-каталог: детальная карточка + рекомендации со ВСЕМИ тремя группами.
/// Товары без imageUrl → placeholder-буква (нет сетевых картинок в widget-тесте).
class _FakeCatalogRepository implements CatalogRepository {
  _FakeCatalogRepository(this._detail, this._recs);

  final CatalogProductDetail _detail;
  final CatalogRecommendations _recs;

  @override
  Future<CatalogProductDetail> detail(String id) async => _detail;

  @override
  Future<CatalogRecommendations> recommendations(String id) async => _recs;

  @override
  Future<CatalogPage> search({String? q, int limit = 24, int offset = 0}) async =>
      const CatalogPage(items: [], total: 0, limit: 24, offset: 0);

  @override
  Future<List<MobileCategory>> categories() async => const [];

  @override
  Future<CatalogRecommendationPools> recommendationPools() async =>
      CatalogRecommendationPools.empty;
}

CatalogRecommendation _reco(String id, String name, String group,
        {int? bonus, bool campaign = false}) =>
    CatalogRecommendation(
      product: CatalogProduct(id: id, name: name),
      group: group,
      bonus: bonus,
      hasActiveCampaign: campaign,
    );

void main() {
  Future<void> openSheet(
    WidgetTester tester, {
    required CatalogProductDetail detail,
    required CatalogRecommendations recs,
  }) async {
    // Карточка — DraggableScrollableSheet с ленивым ListView: контент ниже сгиба
    // (бонус-блок после большого фото, секции рекомендаций) не строится в дефолтном
    // 800×600 вьюпорте. Высокий surface строит весь контент, чтобы find.text видел
    // элементы без ручной прокрутки.
    await tester.binding.setSurfaceSize(const Size(1080, 3200));
    addTearDown(() => tester.binding.setSurfaceSize(null));
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          catalogRepositoryProvider
              .overrideWithValue(_FakeCatalogRepository(detail, recs)),
        ],
        child: MaterialApp(
          home: Scaffold(
            body: Builder(
              builder: (context) => ElevatedButton(
                onPressed: () => showCatalogProductSheet(context, detail.id),
                child: const Text('open'),
              ),
            ),
          ),
        ),
      ),
    );
    await tester.tap(find.text('open'));
    await tester.pumpAndSettle();
  }

  testWidgets('три секции в правильном порядке; пустые скрыты', (tester) async {
    const detail = CatalogProductDetail(id: 'p1', name: 'Основной товар');
    final recs = CatalogRecommendations(
      alternatives: [_reco('a1', 'Альтернатива A', 'alternative')],
      crosssells: [
        _reco('c1', 'Промо-доп', 'crosssell_with_campaign',
            bonus: 400, campaign: true),
        _reco('c2', 'Сопутствующий', 'crosssell_no_campaign'),
      ],
    );
    await openSheet(tester, detail: detail, recs: recs);

    expect(find.text('Можно допродать'), findsOneWidget);
    expect(find.text('Сопутствующие'), findsOneWidget);
    expect(find.text('Альтернативы'), findsOneWidget);

    // Бонус-бейдж только в секции «Можно допродать».
    expect(find.text('+400 ₸'), findsOneWidget);
  });

  testWidgets('секция «Можно допродать» кликабельна (есть InkWell с onTap)',
      (tester) async {
    const detail = CatalogProductDetail(id: 'p1', name: 'Основной товар');
    final recs = CatalogRecommendations(
      crosssells: [
        _reco('c1', 'Промо-доп', 'crosssell_with_campaign',
            bonus: 400, campaign: true),
      ],
    );
    await openSheet(tester, detail: detail, recs: recs);

    // Внутри карточки промо-допа есть кликабельный InkWell (onTap != null).
    final card = find.ancestor(
      of: find.text('Промо-доп'),
      matching: find.byType(InkWell),
    );
    final inkwells = tester.widgetList<InkWell>(card);
    expect(inkwells.any((w) => w.onTap != null), isTrue);
  });

  testWidgets('секции «Сопутствующие» и «Альтернативы» НЕ кликабельны',
      (tester) async {
    const detail = CatalogProductDetail(id: 'p1', name: 'Основной товар');
    final recs = CatalogRecommendations(
      alternatives: [_reco('a1', 'Альтернатива A', 'alternative')],
      crosssells: [
        _reco('c2', 'Сопутствующий', 'crosssell_no_campaign'),
      ],
    );
    await openSheet(tester, detail: detail, recs: recs);

    // У инфо-карточек НЕТ оборачивающего InkWell (только фото+название).
    final companionInk = find.ancestor(
      of: find.text('Сопутствующий'),
      matching: find.byType(InkWell),
    );
    expect(companionInk, findsNothing);

    final altInk = find.ancestor(
      of: find.text('Альтернатива A'),
      matching: find.byType(InkWell),
    );
    expect(altInk, findsNothing);
  });

  testWidgets('bonus==null → блок «Получить бонус» не рисуем', (tester) async {
    const detail = CatalogProductDetail(id: 'p1', name: 'Товар без кампании');
    await openSheet(
      tester,
      detail: detail,
      recs: const CatalogRecommendations(),
    );

    expect(find.text('Получить бонус'), findsNothing);
  });

  testWidgets('bonus!=null → бейдж «Бонус N ₸» + кнопка «Получить бонус»',
      (tester) async {
    const detail = CatalogProductDetail(
      id: 'p1',
      name: 'Промо-товар',
      hasActiveCampaign: true,
      bonus: 520,
      promoId: 'pr_42',
      campaignTitle: 'Акция',
    );
    await openSheet(
      tester,
      detail: detail,
      recs: const CatalogRecommendations(),
    );

    expect(find.text('Бонус 520 ₸'), findsOneWidget);
    expect(find.text('Получить бонус'), findsOneWidget);
  });
}
