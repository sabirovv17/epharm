import 'catalog_models.dart';
import 'catalog_repository.dart';

/// Офлайн-каталог для разработки/демо без бэкенда. Реалистичные фарм-позиции,
/// часть — БЕЗ цены (проверяем «Цена в аптеке») и без бренда/МНН (пустые поля).
class MockCatalogRepository implements CatalogRepository {
  static const List<CatalogProduct> _all = [
    CatalogProduct(
        id: 'm_aspirin',
        name: 'Аспирин Кардио 100 мг №28',
        brand: 'Bayer',
        mnn: 'Ацетилсалициловая кислота',
        rxOtc: 'OTC',
        price: 1290,
        category: 'Сердце и сосуды'),
    CatalogProduct(
        id: 'm_nurofen',
        name: 'Нурофен Экспресс 200 мг капс №20',
        brand: 'Reckitt',
        mnn: 'Ибупрофен',
        rxOtc: 'OTC',
        price: 2450,
        category: 'Обезболивающие'),
    CatalogProduct(
        id: 'm_aquamaris',
        name: 'Аквамарис спрей назальный 30 мл',
        brand: 'Jadran',
        mnn: 'Морская вода',
        rxOtc: 'OTC',
        price: 1890,
        category: 'ЛОР'),
    CatalogProduct(
        id: 'm_essentiale',
        name: 'Эссенциале Форте Н №30',
        brand: 'Sanofi',
        mnn: 'Фосфолипиды',
        rxOtc: 'OTC',
        price: 5680,
        category: 'ЖКТ'),
    CatalogProduct(
        id: 'm_amoxiclav',
        name: 'Амоксиклав 875/125 мг №14',
        brand: 'Sandoz',
        mnn: 'Амоксициллин + клавулановая кислота',
        rxOtc: 'Rx',
        price: 2980,
        category: 'Антибиотики'),
    CatalogProduct(
        id: 'm_vitd',
        name: 'Витамин D3 2000 МЕ капли 10 мл',
        brand: 'Natrol',
        mnn: 'Колекальциферол',
        rxOtc: 'OTC',
        price: 3200,
        category: 'Витамины'),
    CatalogProduct(
        id: 'm_omez',
        name: 'Омез 20 мг капс №30',
        brand: 'Dr. Reddys',
        mnn: 'Омепразол',
        rxOtc: 'OTC',
        price: 2150,
        category: 'ЖКТ'),
    CatalogProduct(
        id: 'm_panthenol',
        name: 'Пантенол спрей 130 г',
        brand: 'Hemofarm',
        mnn: 'Декспантенол',
        rxOtc: 'OTC',
        price: 2890,
        category: 'Дерматология'),
    CatalogProduct(
        id: 'm_no_price',
        name: 'Везилют капс. №30',
        brand: 'ТД Пептид Био',
        mnn: null,
        rxOtc: 'OTC',
        price: null, // цена в каталоге пока не задана — «Цена в аптеке»
        category: 'БАДы'),
    CatalogProduct(
        id: 'm_panangin',
        name: 'Панангин №50',
        brand: 'Gedeon Richter',
        mnn: 'Калия/магния аспарагинат',
        rxOtc: 'OTC',
        price: 2640,
        category: 'Сердце и сосуды'),
    CatalogProduct(
        id: 'm_ingavirin',
        name: 'Ингавирин 90 мг №7',
        brand: null,
        mnn: null,
        rxOtc: 'OTC',
        price: null,
        category: 'Простуда'),
    CatalogProduct(
        id: 'm_loratadin',
        name: 'Лоратадин 10 мг №10',
        brand: 'Santo',
        mnn: 'Лоратадин',
        rxOtc: 'OTC',
        price: 640,
        category: 'Аллергия'),
  ];

  @override
  Future<CatalogPage> search({String? q, int limit = 24, int offset = 0}) async {
    await Future<void>.delayed(const Duration(milliseconds: 220));
    final needle = (q ?? '').trim().toLowerCase();
    final filtered = needle.isEmpty
        ? _all
        : _all
            .where((p) => ('${p.name} ${p.brand ?? ''} ${p.mnn ?? ''}')
                .toLowerCase()
                .contains(needle))
            .toList();
    final slice = filtered.skip(offset).take(limit).toList();
    return CatalogPage(
        items: slice, total: filtered.length, limit: limit, offset: offset);
  }

  @override
  Future<CatalogProductDetail> detail(String id) async {
    await Future<void>.delayed(const Duration(milliseconds: 180));
    final p = _all.firstWhere((e) => e.id == id, orElse: () => _all.first);
    return CatalogProductDetail(
      id: p.id,
      name: p.name,
      brand: p.brand,
      mnn: p.mnn,
      rxOtc: p.rxOtc,
      price: p.price,
      currency: p.currency,
      imageUrl: p.imageUrl,
      barcode: p.barcode,
      category: p.category,
      description:
          'Демо-описание (офлайн-режим). Реальное описание придёт из каталога.',
      keyFacts: [
        'Форма выпуска и дозировка указаны в названии',
        p.rxOtc == 'Rx' ? 'Отпускается по рецепту' : 'Отпускается без рецепта',
      ],
    );
  }

  @override
  Future<List<MobileCategory>> categories() async => const [];
}
