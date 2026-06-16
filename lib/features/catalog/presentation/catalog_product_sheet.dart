import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_spacing.dart';
import '../application/catalog_controller.dart';
import '../data/catalog_models.dart';

/// Детальная карточка товара каталога. Данные грузятся по medusa-id через
/// [catalogDetailProvider]. Цена/фото/поля могут отсутствовать — секции рисуются
/// только при наличии данных.
Future<void> showCatalogProductSheet(BuildContext context, String id) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    barrierColor: const Color(0x730F1424),
    builder: (_) => _CatalogProductSheet(id: id),
  );
}

class _CatalogProductSheet extends ConsumerWidget {
  const _CatalogProductSheet({required this.id});
  final String id;

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final async = ref.watch(catalogDetailProvider(id));
    return DraggableScrollableSheet(
      initialChildSize: 0.7,
      maxChildSize: 0.95,
      minChildSize: 0.5,
      expand: false,
      builder: (ctx, scrollCtrl) => Container(
        decoration: const BoxDecoration(
          color: Colors.white,
          borderRadius: BorderRadius.vertical(top: Radius.circular(AppRadii.xxl)),
        ),
        child: async.when(
          loading: () => ListView(
            controller: scrollCtrl,
            children: const [
              SizedBox(height: 120),
              Center(child: CircularProgressIndicator()),
            ],
          ),
          error: (_, __) => ListView(
            controller: scrollCtrl,
            padding: const EdgeInsets.all(AppSpacing.screenEdge),
            children: [
              const SizedBox(height: 60),
              const Icon(Icons.wifi_off_rounded,
                  size: 40, color: AppColors.ink300),
              const SizedBox(height: 12),
              const Text(
                'Не удалось загрузить товар',
                textAlign: TextAlign.center,
                style: TextStyle(
                  fontFamily: 'Manrope',
                  fontFamilyFallback: ['Roboto', 'sans-serif'],
                  fontSize: 16,
                  fontWeight: FontWeight.w800,
                  color: AppColors.ink900,
                ),
              ),
              const SizedBox(height: 16),
              Center(
                child: TextButton(
                  onPressed: () => ref.invalidate(catalogDetailProvider(id)),
                  child: const Text(
                    'Повторить',
                    style: TextStyle(
                      fontFamily: 'Manrope',
                      fontFamilyFallback: ['Roboto', 'sans-serif'],
                      fontSize: 15,
                      fontWeight: FontWeight.w800,
                      color: AppColors.brandGreen700,
                    ),
                  ),
                ),
              ),
            ],
          ),
          data: (d) => _content(d, scrollCtrl),
        ),
      ),
    );
  }

  Widget _content(CatalogProductDetail d, ScrollController scrollCtrl) {
    return ListView(
      controller: scrollCtrl,
      padding: const EdgeInsets.fromLTRB(
        AppSpacing.screenEdge,
        8,
        AppSpacing.screenEdge,
        AppSpacing.s24,
      ),
      children: [
        Center(
          child: Container(
            width: 36,
            height: 4,
            decoration: BoxDecoration(
              color: AppColors.ink300,
              borderRadius: BorderRadius.circular(2),
            ),
          ),
        ),
        const SizedBox(height: 12),
        ClipRRect(
          borderRadius: AppRadii.brXl,
          child: AspectRatio(
            aspectRatio: 1.6,
            child: _DetailImage(d: d),
          ),
        ),
        const SizedBox(height: 14),
        Text(
          d.name,
          style: const TextStyle(
            fontFamily: 'Manrope',
            fontFamilyFallback: ['Roboto', 'sans-serif'],
            fontSize: 20,
            fontWeight: FontWeight.w800,
            color: AppColors.ink900,
            height: 1.2,
          ),
        ),
        if (d.brand != null) ...[
          const SizedBox(height: 4),
          Text(
            d.brand!,
            style: const TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 14,
              fontWeight: FontWeight.w700,
              color: AppColors.ink500,
            ),
          ),
        ],
        const SizedBox(height: 10),
        Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            _Chip(
              text: d.isRx ? 'По рецепту' : 'Без рецепта',
              color: d.isRx ? const Color(0xFFE5484D) : AppColors.brandGreen700,
              bg: d.isRx ? const Color(0xFFFDE7E9) : AppColors.brandGreen100,
            ),
            if (d.category != null)
              _Chip(
                text: d.category!,
                color: AppColors.ink700,
                bg: AppColors.paperInput,
              ),
          ],
        ),
        const SizedBox(height: 12),
        Text(
          catalogPriceLabel(d.price, currency: d.currency),
          style: TextStyle(
            fontFamily: 'Manrope',
            fontFamilyFallback: const ['Roboto', 'sans-serif'],
            fontSize: d.price == null ? 15 : 22,
            fontWeight: FontWeight.w800,
            color: d.price == null ? AppColors.ink500 : AppColors.brandGreen700,
          ),
        ),
        const SizedBox(height: 14),
        _InfoRow(label: 'Действующее вещество', value: d.mnn),
        _InfoRow(label: 'ATC', value: d.atc),
        _InfoRow(label: 'Производитель', value: d.manufacturer),
        _InfoRow(label: 'Страна', value: d.country),
        _InfoRow(label: 'Штрихкод', value: d.barcode),
        if (d.description != null && d.description!.isNotEmpty) ...[
          const SizedBox(height: 8),
          const _SectionTitle('Описание'),
          const SizedBox(height: 6),
          Text(
            d.description!,
            style: const TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 14,
              fontWeight: FontWeight.w600,
              color: AppColors.ink700,
              height: 1.4,
            ),
          ),
        ],
        if (d.keyFacts.isNotEmpty) ...[
          const SizedBox(height: 14),
          const _SectionTitle('Кратко'),
          const SizedBox(height: 6),
          ...d.keyFacts.map(_bullet),
        ],
        if (d.qa.isNotEmpty) ...[
          const SizedBox(height: 14),
          const _SectionTitle('Вопросы и ответы'),
          const SizedBox(height: 6),
          ...d.qa.map(_qaItem),
        ],
        if (d.marketplaceLinks.isNotEmpty) ...[
          const SizedBox(height: 14),
          const _SectionTitle('Цены на маркетплейсах'),
          const SizedBox(height: 8),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: d.marketplaceLinks
                .map((m) => _Chip(
                      text: m.price != null
                          ? '${_platformLabel(m.platform)} · ${catalogPriceLabel(m.price)}'
                          : _platformLabel(m.platform),
                      color: AppColors.ink700,
                      bg: AppColors.paperInput,
                    ))
                .toList(),
          ),
        ],
      ],
    );
  }

  Widget _bullet(String text) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 6),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Padding(
            padding: EdgeInsets.only(top: 6, right: 8),
            child: Icon(Icons.circle, size: 6, color: AppColors.brandGreen600),
          ),
          Expanded(
            child: Text(
              text,
              style: const TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 14,
                fontWeight: FontWeight.w600,
                color: AppColors.ink700,
                height: 1.35,
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _qaItem(CatalogQaItem item) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            item.q,
            style: const TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 14,
              fontWeight: FontWeight.w800,
              color: AppColors.ink900,
              height: 1.3,
            ),
          ),
          const SizedBox(height: 2),
          Text(
            item.a,
            style: const TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 14,
              fontWeight: FontWeight.w600,
              color: AppColors.ink700,
              height: 1.35,
            ),
          ),
        ],
      ),
    );
  }

  String _platformLabel(String p) {
    switch (p.toLowerCase()) {
      case 'kaspi':
        return 'Kaspi';
      case 'wb':
        return 'Wildberries';
      case 'ozon':
        return 'Ozon';
      default:
        return p;
    }
  }
}

class _DetailImage extends StatelessWidget {
  const _DetailImage({required this.d});
  final CatalogProductDetail d;

  @override
  Widget build(BuildContext context) {
    final url = d.imageUrl;
    if (url == null || url.isEmpty) return _placeholder();
    return Image.network(
      url,
      fit: BoxFit.cover,
      loadingBuilder: (ctx, child, progress) =>
          progress == null ? child : _placeholder(),
      errorBuilder: (_, __, ___) => _placeholder(),
    );
  }

  Widget _placeholder() {
    final letter = d.name.isNotEmpty ? d.name.characters.first : '?';
    return Container(
      color: AppColors.brandGreen100,
      alignment: Alignment.center,
      child: Text(
        letter.toUpperCase(),
        style: const TextStyle(
          fontFamily: 'Manrope',
          fontFamilyFallback: ['Roboto', 'sans-serif'],
          fontSize: 44,
          fontWeight: FontWeight.w800,
          color: AppColors.brandGreen700,
        ),
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  const _InfoRow({required this.label, required this.value});
  final String label;
  final String? value;

  @override
  Widget build(BuildContext context) {
    if (value == null || value!.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(bottom: 8),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 150,
            child: Text(
              label,
              style: const TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 13,
                fontWeight: FontWeight.w700,
                color: AppColors.ink500,
              ),
            ),
          ),
          Expanded(
            child: Text(
              value!,
              style: const TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 13,
                fontWeight: FontWeight.w800,
                color: AppColors.ink900,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _SectionTitle extends StatelessWidget {
  const _SectionTitle(this.text);
  final String text;

  @override
  Widget build(BuildContext context) {
    return Text(
      text,
      style: const TextStyle(
        fontFamily: 'Manrope',
        fontFamilyFallback: ['Roboto', 'sans-serif'],
        fontSize: 15,
        fontWeight: FontWeight.w800,
        color: AppColors.ink900,
      ),
    );
  }
}

class _Chip extends StatelessWidget {
  const _Chip({required this.text, required this.color, required this.bg});
  final String text;
  final Color color;
  final Color bg;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: bg,
        borderRadius: BorderRadius.circular(999),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontFamily: 'Manrope',
          fontFamilyFallback: const ['Roboto', 'sans-serif'],
          fontSize: 12,
          fontWeight: FontWeight.w800,
          color: color,
        ),
      ),
    );
  }
}
