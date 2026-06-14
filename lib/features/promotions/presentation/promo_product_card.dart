import 'package:flutter/material.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_radii.dart';
import '../data/promotion_models.dart';

/// Богатая карточка промо-акции на всю ширину (одна колонка): фото товара,
/// название/бренд, ценовые пороги «от N шт → цена» и бонусы за достижение порога,
/// диапазон дат. Тап → детальная карточка товара (по medusa product id).
class PromoProductCard extends StatelessWidget {
  const PromoProductCard({super.key, required this.promo, required this.onTap});

  final Promotion promo;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final bonusTiers = <int>[]; // индексы порогов с бонусом (для строк ниже)
    for (var i = 0; i < promo.tiers.length; i++) {
      if (promo.tiers[i].bonus > 0) bonusTiers.add(i);
    }

    return Material(
      color: Colors.white,
      borderRadius: AppRadii.brXl,
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.all(14),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              if (promo.dateLabel != null) ...[
                _DateBadge(label: promo.dateLabel!),
                const SizedBox(height: 10),
              ],
              // Шапка: фото + название/бренд/Rx.
              Row(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  _Thumb(promo: promo),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        Text(
                          promo.name,
                          maxLines: 3,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontFamily: 'Manrope',
                            fontFamilyFallback: ['Roboto', 'sans-serif'],
                            fontSize: 15,
                            fontWeight: FontWeight.w800,
                            color: AppColors.ink900,
                            height: 1.2,
                          ),
                        ),
                        if (promo.brand != null) ...[
                          const SizedBox(height: 3),
                          Text(
                            promo.brand!,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              fontFamily: 'Manrope',
                              fontFamilyFallback: ['Roboto', 'sans-serif'],
                              fontSize: 12,
                              fontWeight: FontWeight.w700,
                              color: AppColors.ink500,
                            ),
                          ),
                        ],
                        if (promo.isRx) ...[
                          const SizedBox(height: 6),
                          const _RxBadge(),
                        ],
                      ],
                    ),
                  ),
                ],
              ),
              if (promo.tiers.isNotEmpty) ...[
                const SizedBox(height: 14),
                // До 3 порогов — в ряд; при 4+ переносятся на новую строку (Wrap),
                // чтобы цена не ужималась в нечитаемое «4…» на узком экране.
                LayoutBuilder(
                  builder: (ctx, c) {
                    const gap = 8.0;
                    final perRow =
                        promo.tiers.length <= 3 ? promo.tiers.length : 3;
                    final w = (c.maxWidth - gap * (perRow - 1)) / perRow;
                    return Wrap(
                      spacing: gap,
                      runSpacing: 10,
                      children: [
                        for (final t in promo.tiers)
                          SizedBox(width: w, child: _TierPill(tier: t)),
                      ],
                    );
                  },
                ),
              ],
              if (bonusTiers.isNotEmpty) ...[
                const SizedBox(height: 12),
                for (final i in bonusTiers)
                  Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: _BonusLine(
                        tierNumber: i + 1, bonus: promo.tiers[i].bonus),
                  ),
              ],
            ],
          ),
        ),
      ),
    );
  }
}

class _Thumb extends StatelessWidget {
  const _Thumb({required this.promo});
  final Promotion promo;

  @override
  Widget build(BuildContext context) {
    const size = 88.0;
    Widget placeholder() => Container(
          width: size,
          height: size,
          alignment: Alignment.center,
          color: AppColors.brandGreen100,
          child: Text(
            promo.name.isNotEmpty
                ? promo.name.characters.first.toUpperCase()
                : '?',
            style: const TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 30,
              fontWeight: FontWeight.w800,
              color: AppColors.brandGreen700,
            ),
          ),
        );

    final url = promo.imageUrl;
    return ClipRRect(
      borderRadius: BorderRadius.circular(14),
      child: (url == null || url.isEmpty)
          ? placeholder()
          : Image.network(
              url,
              width: size,
              height: size,
              fit: BoxFit.cover,
              loadingBuilder: (_, child, p) =>
                  p == null ? child : placeholder(),
              errorBuilder: (_, __, ___) => placeholder(),
            ),
    );
  }
}

class _DateBadge extends StatelessWidget {
  const _DateBadge({required this.label});
  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 4),
      decoration: BoxDecoration(
        color: AppColors.brandGreen100,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Text(
        label,
        style: const TextStyle(
          fontFamily: 'Manrope',
          fontFamilyFallback: ['Roboto', 'sans-serif'],
          fontSize: 11,
          fontWeight: FontWeight.w800,
          color: AppColors.brandGreen700,
        ),
      ),
    );
  }
}

/// Ценовой порог: пилюля «цена ₸» + подпись «от N шт».
class _TierPill extends StatelessWidget {
  const _TierPill({required this.tier});
  final PromoTier tier;

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Container(
          width: double.infinity,
          padding: const EdgeInsets.symmetric(vertical: 9),
          decoration: BoxDecoration(
            color: AppColors.brandGreen700,
            borderRadius: BorderRadius.circular(12),
          ),
          alignment: Alignment.center,
          child: Text(
            '${_money(tier.price)} ₸',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: const TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 14,
              fontWeight: FontWeight.w800,
              color: Colors.white,
            ),
          ),
        ),
        const SizedBox(height: 6),
        Text(
          'от ${tier.minQty} шт',
          style: const TextStyle(
            fontFamily: 'Manrope',
            fontFamilyFallback: ['Roboto', 'sans-serif'],
            fontSize: 11,
            fontWeight: FontWeight.w700,
            color: AppColors.ink500,
          ),
        ),
      ],
    );
  }
}

class _BonusLine extends StatelessWidget {
  const _BonusLine({required this.tierNumber, required this.bonus});
  final int tierNumber;
  final int bonus;

  @override
  Widget build(BuildContext context) {
    return Row(
      children: [
        const Text('🎁', style: TextStyle(fontSize: 14)),
        const SizedBox(width: 6),
        Expanded(
          child: Text(
            'Бонус при достижении $tierNumber порога: ${_money(bonus)} ₸',
            style: const TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 12.5,
              fontWeight: FontWeight.w700,
              color: AppColors.ink700,
            ),
          ),
        ),
      ],
    );
  }
}

class _RxBadge extends StatelessWidget {
  const _RxBadge();
  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: const Color(0xFFFDE7E9),
        borderRadius: BorderRadius.circular(6),
      ),
      child: const Text(
        'Rx',
        style: TextStyle(
          fontFamily: 'Manrope',
          fontFamilyFallback: ['Roboto', 'sans-serif'],
          fontSize: 10,
          fontWeight: FontWeight.w800,
          color: Color(0xFFE5484D),
        ),
      ),
    );
  }
}

/// Компактная карточка товара для сетки 2 колонки (лента главной): фото,
/// название, бренд и бонус фармацевту. Тап → детальная карточка (там — полные
/// пороги/даты/описание). Зелёный — ОДИН основной тон (brandGreen600), как шапка
/// и нижняя навигация: без тёмных оттенков.
class PromoGridCard extends StatelessWidget {
  const PromoGridCard({super.key, required this.promo, required this.onTap});

  final Promotion promo;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final maxBonus =
        promo.tiers.fold<int>(0, (m, t) => t.bonus > m ? t.bonus : m);
    final minPrice = promo.tiers.isEmpty
        ? null
        : promo.tiers.map((t) => t.price).reduce((a, b) => a < b ? a : b);

    return Material(
      color: Colors.white,
      borderRadius: AppRadii.brXl,
      clipBehavior: Clip.antiAlias,
      child: InkWell(
        onTap: onTap,
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            _GridThumb(promo: promo),
            Expanded(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(10, 8, 10, 10),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(
                      promo.name,
                      maxLines: 2,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(
                        fontFamily: 'Manrope',
                        fontFamilyFallback: ['Roboto', 'sans-serif'],
                        fontSize: 13.5,
                        fontWeight: FontWeight.w800,
                        color: AppColors.ink900,
                        height: 1.2,
                      ),
                    ),
                    if (promo.brand != null) ...[
                      const SizedBox(height: 3),
                      Text(
                        promo.brand!,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 11,
                          fontWeight: FontWeight.w700,
                          color: AppColors.ink500,
                        ),
                      ),
                    ],
                    const Spacer(),
                    if (maxBonus > 0)
                      _GridBonusPill(bonus: maxBonus)
                    else if (minPrice != null)
                      Text(
                        'от ${_money(minPrice)} ₸',
                        style: const TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 13,
                          fontWeight: FontWeight.w800,
                          color: AppColors.brandGreen600,
                        ),
                      ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _GridThumb extends StatelessWidget {
  const _GridThumb({required this.promo});
  final Promotion promo;

  @override
  Widget build(BuildContext context) {
    const h = 116.0;
    Widget placeholder() => Container(
          height: h,
          width: double.infinity,
          alignment: Alignment.center,
          color: AppColors.brandGreen100,
          child: Text(
            promo.name.isNotEmpty
                ? promo.name.characters.first.toUpperCase()
                : '?',
            style: const TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 34,
              fontWeight: FontWeight.w800,
              color: AppColors.brandGreen600,
            ),
          ),
        );
    final url = promo.imageUrl;
    return (url == null || url.isEmpty)
        ? placeholder()
        : Image.network(
            url,
            height: h,
            width: double.infinity,
            fit: BoxFit.cover,
            loadingBuilder: (_, child, p) => p == null ? child : placeholder(),
            errorBuilder: (_, __, ___) => placeholder(),
          );
  }
}

/// Бонус фармацевту — заливка ОСНОВНЫМ зелёным (brandGreen600), как шапка/навбар.
class _GridBonusPill extends StatelessWidget {
  const _GridBonusPill({required this.bonus});
  final int bonus;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: double.infinity,
      padding: const EdgeInsets.symmetric(vertical: 7, horizontal: 10),
      decoration: BoxDecoration(
        color: AppColors.brandGreen600,
        borderRadius: BorderRadius.circular(10),
      ),
      alignment: Alignment.center,
      child: Text(
        'Бонус ${_money(bonus)} ₸',
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(
          fontFamily: 'Manrope',
          fontFamilyFallback: ['Roboto', 'sans-serif'],
          fontSize: 13,
          fontWeight: FontWeight.w800,
          color: Colors.white,
        ),
      ),
    );
  }
}

/// «4 500» — разрядка пробелами.
String _money(int v) {
  final s = v.abs().toString();
  final b = StringBuffer();
  for (var i = 0; i < s.length; i++) {
    if (i != 0 && (s.length - i) % 3 == 0) b.write(' ');
    b.write(s[i]);
  }
  return b.toString();
}
