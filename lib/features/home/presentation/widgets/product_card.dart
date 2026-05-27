import 'package:flutter/material.dart';

import '../../../../core/theme/app_colors.dart';
import '../../../../core/theme/app_radii.dart';
import '../../../../core/theme/app_shadows.dart';
import '../../../../core/theme/app_spacing.dart';
import '../../../../core/theme/app_typography.dart';
import '../../../../core/widgets/brand_icons.dart';
import '../../data/home_repository.dart';
import 'package_mock.dart';

/// Большая featured карточка продукта (`featured: true`).
class BigProductCard extends StatelessWidget {
  const BigProductCard({super.key, required this.product, this.onTap});
  final Product product;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: AppRadii.brXxl,
        child: Container(
          padding: const EdgeInsets.all(AppSpacing.s16),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: AppRadii.brXxl,
            boxShadow: AppShadows.card,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Hero strip
              Container(
                height: 200,
                clipBehavior: Clip.antiAlias,
                decoration: BoxDecoration(
                  gradient: product.pkg.background,
                  borderRadius: AppRadii.brLg,
                ),
                child: Stack(
                  children: [
                    Center(child: PackageMock(pkg: product.pkg)),
                    // Floating period badge
                    Positioned(
                      top: 12,
                      left: 12,
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 12,
                          vertical: 6,
                        ),
                        decoration: BoxDecoration(
                          color: Colors.white.withValues(alpha: 0.95),
                          borderRadius: AppRadii.brSm,
                          boxShadow: AppShadows.card,
                        ),
                        child: Text(
                          product.period,
                          style: const TextStyle(
                            fontFamily: 'Manrope',
                           fontFamilyFallback: ['Roboto', 'sans-serif'],
                            fontSize: 12,
                            fontWeight: FontWeight.w800,
                            color: AppColors.ink900,
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 12),
              // Title row
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Expanded(
                    child: Text(
                      product.name,
                      style: AppTypography.bodyStrong().copyWith(
                        fontSize: 16,
                        fontWeight: FontWeight.w800,
                        height: 1.15,
                      ),
                    ),
                  ),
                  if (product.bought != null) ...[
                    const SizedBox(width: 12),
                    Text(
                      'Куплено: ${product.bought} уп.',
                      style: AppTypography.caption(color: AppColors.ink500),
                    ),
                  ],
                ],
              ),
              if (product.tiers.isNotEmpty) ...[
                const SizedBox(height: 16),
                TierLadder(tiers: product.tiers),
              ],
              if (product.bonuses.isNotEmpty) ...[
                const SizedBox(height: 12),
                for (final b in product.bonuses)
                  Padding(
                    padding: const EdgeInsets.only(top: 6),
                    child: Row(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const GiftEmojiGlyph(size: 18),
                        const SizedBox(width: 8),
                        Expanded(
                          child: Text(
                            b,
                            style: AppTypography.caption(color: AppColors.ink700)
                                .copyWith(
                              fontWeight: FontWeight.w800,
                              height: 1.3,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// Маленькая карточка (2-column grid).
class SmallProductCard extends StatelessWidget {
  const SmallProductCard({super.key, required this.product, this.onTap});
  final Product product;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: AppRadii.brLg,
        child: Container(
          padding: const EdgeInsets.all(12),
          decoration: BoxDecoration(
            color: Colors.white,
            borderRadius: AppRadii.brLg,
            boxShadow: AppShadows.card,
          ),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              // Image area 140
              Container(
                height: 140,
                clipBehavior: Clip.antiAlias,
                decoration: BoxDecoration(
                  gradient: product.pkg.background,
                  borderRadius: AppRadii.brMd,
                ),
                child: Stack(
                  children: [
                    Center(
                      child: Padding(
                        padding: const EdgeInsets.symmetric(horizontal: 8),
                        child: Text(
                          product.pkg.label,
                          textAlign: TextAlign.center,
                          style: TextStyle(
                            fontFamily: 'Manrope',
                           fontFamilyFallback: ['Roboto', 'sans-serif'],
                            fontSize: 13,
                            fontWeight: FontWeight.w800,
                            color: product.pkg.accent ?? Colors.white,
                            height: 1.15,
                          ),
                        ),
                      ),
                    ),
                    // Period pill bottom-left
                    Positioned(
                      bottom: 8,
                      left: 8,
                      child: Container(
                        padding: const EdgeInsets.symmetric(
                          horizontal: 8,
                          vertical: 2,
                        ),
                        decoration: BoxDecoration(
                          color: AppColors.brandGreen600,
                          borderRadius: BorderRadius.circular(6),
                        ),
                        child: Text(
                          product.period,
                          style: const TextStyle(
                            fontFamily: 'Manrope',
                           fontFamilyFallback: ['Roboto', 'sans-serif'],
                            fontSize: 12,
                            fontWeight: FontWeight.w800,
                            color: Colors.white,
                          ),
                        ),
                      ),
                    ),
                    // NEW badge top-right
                    if (product.isNew)
                      Positioned(
                        top: 8,
                        right: 8,
                        child: Container(
                          padding: const EdgeInsets.symmetric(
                            horizontal: 6,
                            vertical: 2,
                          ),
                          decoration: BoxDecoration(
                            color: AppColors.brandBlue500,
                            borderRadius: BorderRadius.circular(6),
                          ),
                          child: const Text(
                            'NEW',
                            style: TextStyle(
                              fontFamily: 'Manrope',
                             fontFamilyFallback: ['Roboto', 'sans-serif'],
                              fontSize: 10,
                              fontWeight: FontWeight.w800,
                              color: Colors.white,
                            ),
                          ),
                        ),
                      ),
                    // Trophy top-left for contest
                    if (product.contest)
                      const Positioned(
                        top: 8,
                        left: 8,
                        child: TrophyEmojiGlyph(size: 26),
                      ),
                  ],
                ),
              ),
              const SizedBox(height: 8),
              Text(
                product.name,
                maxLines: 2,
                overflow: TextOverflow.ellipsis,
                style: const TextStyle(
                  fontFamily: 'Manrope',
                 fontFamilyFallback: ['Roboto', 'sans-serif'],
                  fontSize: 14,
                  fontWeight: FontWeight.w700,
                  color: AppColors.ink900,
                  height: 1.15,
                ),
              ),
              if (product.restrictions != null) ...[
                const SizedBox(height: 4),
                Text(
                  product.restrictions!,
                  // 1 строка вместо 2 — caption-текст на small-card в идеале
                  // короткий («Только Алматы», «Рц. отпуск» и т.п.); если
                  // вылезает — ellipsis. Раньше 2 строки давали +14px высоты
                  // на каждую карточку, что приводило к overflow при ужатом
                  // mainAxisExtent сетки.
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: const TextStyle(
                    fontFamily: 'Manrope',
                   fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 12,
                    fontWeight: FontWeight.w800,
                    color: AppColors.brandBlue500,
                    height: 1.2,
                  ),
                ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

/// Tier ladder (§6.8): пилюли сверху, divider+gift emojis между парами, labels снизу.
class TierLadder extends StatelessWidget {
  const TierLadder({super.key, required this.tiers});
  final List<Tier> tiers;

  @override
  Widget build(BuildContext context) {
    if (tiers.isEmpty) return const SizedBox.shrink();

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        // Pills row — pills прижаты к ЛЕВОМУ краю своей ячейки (как в дизайне).
        Row(
          children: [
            for (int i = 0; i < tiers.length; i++)
              Expanded(
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: _Pill(label: tiers[i].price),
                ),
              ),
          ],
        ),
        const SizedBox(height: 14),
        // Track 8px со светлой заливкой + circle-обёртанные gifts ровно по
        // началу 2-й и 3-й колонок (под пилюлями, не в gap-центрах).
        SizedBox(
          height: 32,
          child: Stack(
            alignment: Alignment.center,
            children: [
              Container(
                height: 8,
                decoration: BoxDecoration(
                  color: AppColors.paperInput,
                  borderRadius: BorderRadius.circular(4),
                ),
              ),
              LayoutBuilder(
                builder: (context, c) {
                  final w = c.maxWidth;
                  final colW = w / tiers.length;
                  return Stack(
                    children: [
                      for (int i = 1; i < tiers.length; i++)
                        Positioned(
                          // Центрируем gift-маркер чуть позже start-of-col,
                          // чтобы он смотрелся «над пилюлей» этой ячейки.
                          left: colW * i + (colW * 0.06),
                          top: 0,
                          bottom: 0,
                          child: const Center(child: _GiftMarker()),
                        ),
                    ],
                  );
                },
              ),
            ],
          ),
        ),
        const SizedBox(height: 6),
        // Labels — выровнены по левому краю каждой ячейки (под левый край пилюли),
        // а не по центру. Каждая лейбл-ячейка занимает ту же ширину что и pill
        // выше, поэтому `Align.centerLeft` ставит «от N шт.» ровно под левый край.
        Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            for (int i = 0; i < tiers.length; i++)
              Expanded(
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: Text(
                    tiers[i].label,
                    textAlign: TextAlign.start,
                    style: const TextStyle(
                      fontFamily: 'Manrope',
                      fontFamilyFallback: ['Roboto', 'sans-serif'],
                      fontSize: 13,
                      fontWeight: FontWeight.w800,
                      color: AppColors.ink700,
                    ),
                  ),
                ),
              ),
          ],
        ),
      ],
    );
  }
}

/// Скруглённый квадрат tier-pill в pastel-стиле референса (см. design-tokens
/// §6.8). Светлая заливка `brandGreen100` (#D6FBE9) с green-700 текстом —
/// читаемо на белой карточке, не «давит» как раньше тёмный gradient
/// `#21D17A→#16C97A`. Бордюр 1px brand-green-500 + лёгкая тень для объёма.
class _Pill extends StatelessWidget {
  const _Pill({required this.label});
  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 72,
      height: 40,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: AppColors.brandGreen100,
        borderRadius: BorderRadius.circular(16),
        border: Border.all(
          color: AppColors.brandGreen500.withValues(alpha: 0.35),
          width: 1,
        ),
        boxShadow: const [
          BoxShadow(
            color: Color(0x1416C97A), // ~8% брендового зелёного
            offset: Offset(0, 4),
            blurRadius: 10,
          ),
        ],
      ),
      child: Text(
        label,
        style: const TextStyle(
          fontFamily: 'Manrope',
          fontFamilyFallback: ['Roboto', 'sans-serif'],
          fontSize: 15,
          fontWeight: FontWeight.w800,
          color: AppColors.brandGreen700,
        ),
      ),
    );
  }
}

/// Маркер на gray-track: круглая обёртка (36×36) с GiftEmojiGlyph внутри.
/// Контур цветом `brand-green-500` толщиной 2px на белом фоне — единый
/// brand-accent с tier-pills и иконками профиля. Раньше был brand-blue-300
/// (из первичного синего дизайн-эталона), сменили на зелёный по запросу
/// «обводка в цвет интерфейса».
class _GiftMarker extends StatelessWidget {
  const _GiftMarker();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 36,
      height: 36,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: Colors.white,
        shape: BoxShape.circle,
        border: Border.all(color: AppColors.brandGreen500, width: 2),
        boxShadow: const [
          BoxShadow(
            color: Color(0x1A0F1424),
            offset: Offset(0, 2),
            blurRadius: 6,
          ),
        ],
      ),
      child: const GiftEmojiGlyph(size: 22),
    );
  }
}
