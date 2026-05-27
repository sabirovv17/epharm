import 'package:flutter/material.dart';

import '../../../../core/theme/app_colors.dart';
import '../../../../core/theme/app_shadows.dart';
import '../../../../core/widgets/brand_icons.dart';
import '../../../receipts/presentation/upload_prompt_sheet.dart';
import '../../data/home_repository.dart';
import 'package_mock.dart';
import 'product_card.dart' show TierLadder;

/// Bottom-sheet с подробностями товара. Открывается тапом по карточке (Big/Small).
///
/// Содержит:
/// • Drag-handle вверху + paperCanvas-фон
/// • Hero-блок: цвет фона из `product.pkg.background`, период-pill, NEW/Trophy
/// • PackageMock + название / бренд / SKU
/// • TierLadder (от 1 шт / от 10 / от 20)
/// • Список бонусов
/// • Restrictions блок (если есть)
/// • Sticky CTA «Загрузить чек» внизу (заглушка пока, в этапе Upload подключим)
Future<void> showProductDetailSheet(BuildContext context, Product product) {
  return showModalBottomSheet(
    context: context,
    backgroundColor: AppColors.paperCanvas,
    isScrollControlled: true,
    useSafeArea: true,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
    ),
    builder: (_) => _ProductDetailSheet(product: product),
  );
}

class _ProductDetailSheet extends StatelessWidget {
  const _ProductDetailSheet({required this.product});
  final Product product;

  @override
  Widget build(BuildContext context) {
    return DraggableScrollableSheet(
      initialChildSize: 0.92,
      minChildSize: 0.5,
      maxChildSize: 0.95,
      expand: false,
      builder: (_, scroll) {
        return Column(
          children: [
            const _DragHandle(),
            Expanded(
              child: ListView(
                controller: scroll,
                padding: EdgeInsets.zero,
                children: [
                  _HeroBlock(product: product),
                  Padding(
                    padding: const EdgeInsets.fromLTRB(20, 20, 20, 0),
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        _BrandRow(brand: product.brand),
                        const SizedBox(height: 6),
                        Text(
                          product.name,
                          style: const TextStyle(
                            fontFamily: 'Manrope',
                            fontFamilyFallback: ['Roboto', 'sans-serif'],
                            fontSize: 22,
                            fontWeight: FontWeight.w800,
                            color: AppColors.ink900,
                            height: 1.2,
                          ),
                        ),
                        if (product.pkg.sub != null) ...[
                          const SizedBox(height: 4),
                          Text(
                            product.pkg.sub!,
                            style: const TextStyle(
                              fontFamily: 'Manrope',
                              fontFamilyFallback: ['Roboto', 'sans-serif'],
                              fontSize: 14,
                              fontWeight: FontWeight.w700,
                              color: AppColors.ink500,
                              height: 1.3,
                            ),
                          ),
                        ],

                        if (product.tiers.isNotEmpty) ...[
                          const SizedBox(height: 20),
                          const _SectionTitle('Бонусы по объёму'),
                          const SizedBox(height: 12),
                          TierLadder(tiers: product.tiers),
                        ],

                        if (product.bonuses.isNotEmpty) ...[
                          const SizedBox(height: 20),
                          const _SectionTitle('Дополнительные условия'),
                          const SizedBox(height: 8),
                          for (final b in product.bonuses)
                            Padding(
                              padding: const EdgeInsets.only(bottom: 6),
                              child: _BulletLine(text: b),
                            ),
                        ],

                        if (product.contest) ...[
                          const SizedBox(height: 16),
                          _ContestBanner(),
                        ],

                        if (product.restrictions != null) ...[
                          const SizedBox(height: 20),
                          const _SectionTitle('Ограничения'),
                          const SizedBox(height: 8),
                          Container(
                            padding: const EdgeInsets.all(12),
                            decoration: BoxDecoration(
                              color: AppColors.brandBlue100,
                              borderRadius: BorderRadius.circular(12),
                            ),
                            child: Row(
                              crossAxisAlignment: CrossAxisAlignment.start,
                              children: [
                                const Icon(
                                  Icons.info_outline_rounded,
                                  size: 18,
                                  color: AppColors.brandBlue600,
                                ),
                                const SizedBox(width: 10),
                                Expanded(
                                  child: Text(
                                    product.restrictions!,
                                    style: const TextStyle(
                                      fontFamily: 'Manrope',
                                      fontFamilyFallback: [
                                        'Roboto',
                                        'sans-serif'
                                      ],
                                      fontSize: 13,
                                      fontWeight: FontWeight.w800,
                                      color: AppColors.brandBlue600,
                                      height: 1.35,
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],

                        const SizedBox(height: 24),
                        _PeriodLine(period: product.period),
                        const SizedBox(height: 100), // под нижнюю CTA
                      ],
                    ),
                  ),
                ],
              ),
            ),
            _BottomCta(
              onTap: () {
                Navigator.of(context).pop();
                showUploadPromptSheet(context);
              },
            ),
          ],
        );
      },
    );
  }
}

class _DragHandle extends StatelessWidget {
  const _DragHandle();

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.only(top: 8, bottom: 4),
      child: Center(
        child: Container(
          width: 40,
          height: 4,
          decoration: BoxDecoration(
            color: AppColors.ink300,
            borderRadius: BorderRadius.circular(2),
          ),
        ),
      ),
    );
  }
}

class _HeroBlock extends StatelessWidget {
  const _HeroBlock({required this.product});
  final Product product;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
      decoration: BoxDecoration(gradient: product.pkg.background),
      child: Column(
        children: [
          // Бейджи слева-сверху и справа-сверху.
          Row(
            children: [
              if (product.isNew) const _Badge(label: 'НОВОЕ', isBlue: true),
              if (product.isNew && product.contest) const SizedBox(width: 6),
              if (product.contest)
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(99),
                  ),
                  child: const Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      TrophyEmojiGlyph(size: 16),
                      SizedBox(width: 4),
                      Text(
                        'КОНКУРС',
                        style: TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 10,
                          fontWeight: FontWeight.w800,
                          color: AppColors.ink900,
                          letterSpacing: 0.4,
                          height: 1.1,
                        ),
                      ),
                    ],
                  ),
                ),
              const Spacer(),
            ],
          ),
          const SizedBox(height: 12),
          Center(child: PackageMock(pkg: product.pkg)),
        ],
      ),
    );
  }
}

class _Badge extends StatelessWidget {
  const _Badge({required this.label, this.isBlue = false});
  final String label;
  final bool isBlue;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: isBlue ? AppColors.brandBlue600 : Colors.white,
        borderRadius: BorderRadius.circular(99),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontFamily: 'Manrope',
          fontFamilyFallback: const ['Roboto', 'sans-serif'],
          fontSize: 10,
          fontWeight: FontWeight.w800,
          color: isBlue ? Colors.white : AppColors.ink900,
          letterSpacing: 0.4,
          height: 1.1,
        ),
      ),
    );
  }
}

class _BrandRow extends StatelessWidget {
  const _BrandRow({required this.brand});
  final String brand;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
      decoration: BoxDecoration(
        color: AppColors.brandBlue100,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        brand,
        style: const TextStyle(
          fontFamily: 'Manrope',
          fontFamilyFallback: ['Roboto', 'sans-serif'],
          fontSize: 11,
          fontWeight: FontWeight.w800,
          color: AppColors.brandBlue600,
          letterSpacing: 0.4,
          height: 1.1,
        ),
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
        fontSize: 18,
        fontWeight: FontWeight.w800,
        color: AppColors.ink900,
        height: 1.2,
      ),
    );
  }
}

class _BulletLine extends StatelessWidget {
  const _BulletLine({required this.text});
  final String text;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        const SizedBox(width: 4),
        Container(
          margin: const EdgeInsets.only(top: 8),
          width: 5,
          height: 5,
          decoration: const BoxDecoration(
            color: AppColors.ink900,
            shape: BoxShape.circle,
          ),
        ),
        const SizedBox(width: 10),
        Expanded(
          child: Text(
            text,
            style: const TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 14,
              fontWeight: FontWeight.w700,
              color: AppColors.ink900,
              height: 1.4,
            ),
          ),
        ),
      ],
    );
  }
}

class _ContestBanner extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(14),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [Color(0xFFF4B73A), Color(0xFFD69010)],
        ),
        borderRadius: BorderRadius.circular(14),
        boxShadow: AppShadows.card,
      ),
      child: const Row(
        children: [
          TrophyEmojiGlyph(size: 32),
          SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Конкурсный товар',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 16,
                    fontWeight: FontWeight.w800,
                    color: Colors.white,
                    height: 1.2,
                  ),
                ),
                SizedBox(height: 2),
                Text(
                  'Чеки с этим SKU участвуют в розыгрыше призов и ежемесячном лидерборде.',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 12,
                    fontWeight: FontWeight.w800,
                    color: Colors.white,
                    height: 1.35,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _PeriodLine extends StatelessWidget {
  const _PeriodLine({required this.period});
  final String period;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        const Icon(
          Icons.calendar_today_rounded,
          size: 16,
          color: AppColors.ink500,
        ),
        const SizedBox(width: 8),
        Text(
          'Период акции: $period',
          style: const TextStyle(
            fontFamily: 'Manrope',
            fontFamilyFallback: ['Roboto', 'sans-serif'],
            fontSize: 13,
            fontWeight: FontWeight.w800,
            color: AppColors.ink500,
            height: 1.3,
          ),
        ),
      ],
    );
  }
}

class _BottomCta extends StatelessWidget {
  const _BottomCta({required this.onTap});
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
      decoration: const BoxDecoration(
        color: AppColors.paperCanvas,
        boxShadow: [
          BoxShadow(
            color: Color(0x140F1424),
            offset: Offset(0, -4),
            blurRadius: 16,
          ),
        ],
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(99),
          child: Container(
            height: 60,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: AppColors.brandGreen600,
              borderRadius: BorderRadius.circular(99),
              boxShadow: AppShadows.fab,
            ),
            child: const Row(
              mainAxisAlignment: MainAxisAlignment.center,
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.photo_camera_outlined,
                    color: Colors.white, size: 22),
                SizedBox(width: 10),
                Text(
                  'Загрузить чек',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 17,
                    fontWeight: FontWeight.w800,
                    color: Colors.white,
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
