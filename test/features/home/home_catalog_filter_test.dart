import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pharmacy/features/catalog/application/catalog_controller.dart';
import 'package:pharmacy/features/catalog/data/catalog_models.dart';
import 'package:pharmacy/features/catalog/data/catalog_repository.dart';
import 'package:pharmacy/features/home/application/home_controller.dart';

/// Лента Home = реальный каталог, фильтруемый на клиенте. Проверяем чистую
/// функцию [applyCatalogFilters] + деривацию брендов/категорий из загруженного
/// каталога (с очисткой мусора «-»/null и структурной «Сайт»).
CatalogProduct _p(
  String id,
  String name, {
  String? brand,
  List<String> cats = const [],
  String? mnn,
}) =>
    CatalogProduct(id: id, name: name, brand: brand, mnn: mnn, categories: cats);

void main() {
  final sample = <CatalogProduct>[
    _p('1', 'Аспирин', brand: 'Bayer', cats: ['Сайт', 'БАДы'], mnn: 'Ацетилсалициловая'),
    _p('2', 'Везилют'), // без бренда/категорий — каталог в наполнении
    _p('3', 'Аквадетрим', brand: 'NOW Foods', cats: ['Сайт', 'Косметика']),
    _p('4', 'Бетадин', brand: '-', cats: ['БАДы']), // мусорный бренд «-»
  ];

  group('applyCatalogFilters', () {
    List<String> ids(Iterable<CatalogProduct> ps) => ps.map((p) => p.id).toList();

    test('без фильтров — все товары, сортировка А-Я по умолчанию', () {
      final r = applyCatalogFilters(
        products: sample,
        brands: const {},
        categories: const {},
        query: '',
        sort: CatalogSort.nameAsc,
      );
      expect(r.map((p) => p.name).toList(),
          ['Аквадетрим', 'Аспирин', 'Бетадин', 'Везилют']);
    });

    test('сортировка Я-А', () {
      final r = applyCatalogFilters(
        products: sample,
        brands: const {},
        categories: const {},
        query: '',
        sort: CatalogSort.nameDesc,
      );
      expect(r.first.name, 'Везилют');
      expect(r.last.name, 'Аквадетрим');
    });

    test('фильтр по бренду', () {
      final r = applyCatalogFilters(
        products: sample,
        brands: const {'Bayer'},
        categories: const {},
        query: '',
        sort: CatalogSort.nameAsc,
      );
      expect(ids(r), ['1']);
    });

    test('фильтр по категории — товар проходит если хотя бы одна совпадает', () {
      final r = applyCatalogFilters(
        products: sample,
        brands: const {},
        categories: const {'БАДы'},
        query: '',
        sort: CatalogSort.nameAsc,
      );
      expect(ids(r).toSet(), {'1', '4'});
    });

    test('поиск по названию + бренду + МНН (case-insensitive)', () {
      expect(
          ids(applyCatalogFilters(
              products: sample,
              brands: const {},
              categories: const {},
              query: 'аспир',
              sort: CatalogSort.nameAsc)),
          ['1']);
      expect(
          ids(applyCatalogFilters(
              products: sample,
              brands: const {},
              categories: const {},
              query: 'BAYER',
              sort: CatalogSort.nameAsc)),
          ['1']);
      expect(
          ids(applyCatalogFilters(
              products: sample,
              brands: const {},
              categories: const {},
              query: 'ацетил',
              sort: CatalogSort.nameAsc)),
          ['1']);
    });

    test('комбинация бренд + поиск', () {
      final r = applyCatalogFilters(
        products: sample,
        brands: const {'NOW Foods'},
        categories: const {},
        query: 'аква',
        sort: CatalogSort.nameAsc,
      );
      expect(ids(r), ['3']);
    });
  });

  group('деривация брендов/категорий из каталога', () {
    ProviderContainer makeContainer() {
      final c = ProviderContainer(overrides: [
        catalogRepositoryProvider.overrideWithValue(_FakeRepo(sample)),
      ]);
      addTearDown(c.dispose);
      return c;
    }

    test('homeBrandsProvider — только значимые бренды, отсортированы (без «-»/null)',
        () async {
      final c = makeContainer();
      await c.read(homeCatalogProvider.future);
      expect(c.read(homeBrandsProvider), ['Bayer', 'NOW Foods']);
    });

    test('homeCategoriesProvider — без структурной «Сайт», отсортированы', () async {
      final c = makeContainer();
      await c.read(homeCatalogProvider.future);
      expect(c.read(homeCategoriesProvider), ['БАДы', 'Косметика']);
    });

    test('homeCatalogProvider грузит весь каталог (постранично)', () async {
      final c = makeContainer();
      final all = await c.read(homeCatalogProvider.future);
      expect(all.length, sample.length);
    });
  });
}

/// Фейковый репозиторий каталога: режет sample по offset/limit (как реальный API).
class _FakeRepo implements CatalogRepository {
  _FakeRepo(this.all);
  final List<CatalogProduct> all;

  @override
  Future<CatalogPage> search({String? q, int limit = 24, int offset = 0}) async {
    final slice = all.skip(offset).take(limit).toList();
    return CatalogPage(items: slice, total: all.length, limit: limit, offset: offset);
  }

  @override
  Future<CatalogProductDetail> detail(String id) async =>
      CatalogProductDetail(id: id, name: 'x');
}
