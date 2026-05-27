import 'package:flutter/material.dart';

import '../../../../core/theme/app_colors.dart';
import '../../../../core/theme/app_radii.dart';
import '../../../../core/theme/app_shadows.dart';
import '../../../../core/theme/app_spacing.dart';
import '../../data/home_repository.dart';

/// Горизонтальная карусель промо. Высота 260, ширина карточки 200.
class PromoCarousel extends StatelessWidget {
  const PromoCarousel({super.key, required this.items});

  final List<Promo> items;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 260,
      child: ListView.separated(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
        itemCount: items.length,
        separatorBuilder: (_, __) => const SizedBox(width: 12),
        itemBuilder: (_, i) {
          final p = items[i];
          return switch (p) {
            InfoPromo() => _InfoPromoCard(promo: p),
            HuggiesPromo() => _HuggiesPromoCard(promo: p),
            KotexPromo() => _KotexPromoCard(promo: p),
          };
        },
      ),
    );
  }
}

/// Базовый shell: 200×260, radius xl, shadow card + 2px inset blue-500 outline.
class _PromoShell extends StatelessWidget {
  const _PromoShell({required this.background, required this.child});

  final LinearGradient background;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 200,
      height: 260,
      decoration: BoxDecoration(
        gradient: background,
        borderRadius: AppRadii.brXl,
        boxShadow: AppShadows.card,
        border: Border.all(color: AppColors.brandBlue500, width: 2),
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(AppRadii.xl - 2),
        child: child,
      ),
    );
  }
}

/// Info — cream bg, dashed yellow border, warning dot, title, body (with 2025 highlight), bottom CTA pill.
class _InfoPromoCard extends StatelessWidget {
  const _InfoPromoCard({required this.promo});
  final InfoPromo promo;

  @override
  Widget build(BuildContext context) {
    return _PromoShell(
      background: promo.background,
      child: Stack(
        children: [
          // Dashed inner border
          Positioned.fill(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 8),
              child: CustomPaint(
                painter: _DashedBorderPainter(
                  color: const Color(0xFFFFE7A8),
                  strokeWidth: 1.5,
                  radius: 16,
                  dash: 5,
                  gap: 4,
                ),
              ),
            ),
          ),
          Padding(
            padding: const EdgeInsets.all(12),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                const SizedBox(height: 4),
                Container(
                  width: 32,
                  height: 32,
                  alignment: Alignment.center,
                  decoration: const BoxDecoration(
                    color: AppColors.accentWarning,
                    shape: BoxShape.circle,
                  ),
                  child: const Text(
                    '!',
                    style: TextStyle(
                      fontFamily: 'Manrope',
                     fontFamilyFallback: ['Roboto', 'sans-serif'],
                      fontSize: 18,
                      fontWeight: FontWeight.w800,
                      color: Colors.white,
                      height: 1.0,
                    ),
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  promo.title,
                  textAlign: TextAlign.center,
                  style: const TextStyle(
                    fontFamily: 'Manrope',
                   fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 15,
                    fontWeight: FontWeight.w700,
                    color: AppColors.ink900,
                    height: 1.15,
                  ),
                ),
                const SizedBox(height: 8),
                _BodyWithHighlight(text: promo.subtitle, highlight: '2025'),
                const Spacer(),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 6),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: AppRadii.brSm,
                    boxShadow: AppShadows.card,
                  ),
                  child: Row(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Container(
                        width: 16,
                        height: 16,
                        decoration: const BoxDecoration(
                          color: AppColors.brandBlue500,
                          shape: BoxShape.circle,
                        ),
                      ),
                      const SizedBox(width: 8),
                      Flexible(
                        child: Text(
                          promo.note,
                          style: const TextStyle(
                            fontFamily: 'Manrope',
                           fontFamilyFallback: ['Roboto', 'sans-serif'],
                            fontSize: 10,
                            fontWeight: FontWeight.w800,
                            color: AppColors.ink700,
                            height: 1.2,
                          ),
                          maxLines: 2,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 4),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _BodyWithHighlight extends StatelessWidget {
  const _BodyWithHighlight({required this.text, required this.highlight});
  final String text;
  final String highlight;

  @override
  Widget build(BuildContext context) {
    final idx = text.indexOf(highlight);
    const base = TextStyle(
      fontFamily: 'Manrope',
     fontFamilyFallback: ['Roboto', 'sans-serif'],
      fontSize: 11,
      fontWeight: FontWeight.w800,
      color: AppColors.ink700,
      height: 1.3,
    );
    if (idx < 0) {
      return Text(text, textAlign: TextAlign.center, style: base);
    }
    return RichText(
      textAlign: TextAlign.center,
      text: TextSpan(
        style: base,
        children: [
          TextSpan(text: text.substring(0, idx)),
          TextSpan(
            text: highlight,
            style: base.copyWith(
              color: AppColors.brandGreen600,
              fontWeight: FontWeight.w800,
            ),
          ),
          TextSpan(text: text.substring(idx + highlight.length)),
        ],
      ),
    );
  }
}

/// Huggies promo — beige bg, red period, title, tilted color blocks, red price pill.
class _HuggiesPromoCard extends StatelessWidget {
  const _HuggiesPromoCard({required this.promo});
  final HuggiesPromo promo;

  @override
  Widget build(BuildContext context) {
    return _PromoShell(
      background: promo.background,
      child: Padding(
        padding: const EdgeInsets.all(8),
        child: Column(
          children: [
            Text(
              promo.period,
              style: const TextStyle(
                fontFamily: 'Manrope',
               fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 10,
                fontWeight: FontWeight.w800,
                color: Color(0xFFB22424),
              ),
            ),
            const SizedBox(height: 4),
            Text(
              '${promo.title}\n${promo.subtitle}',
              textAlign: TextAlign.center,
              style: const TextStyle(
                fontFamily: 'Manrope',
               fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 11,
                fontWeight: FontWeight.w800,
                color: AppColors.ink900,
                height: 1.2,
              ),
            ),
            const SizedBox(height: 8),
            _TiltedBlocks(
              colors: const [
                Color(0xFFFF9A4B),
                Color(0xFF3A7CD9),
                Color(0xFFD94B4B),
                Color(0xFF6FB54B),
              ],
            ),
            const Spacer(),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 4),
              decoration: BoxDecoration(
                gradient: const LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [Color(0xFFDC2828), Color(0xFF7E1112)],
                ),
                borderRadius: BorderRadius.circular(6),
              ),
              child: Text(
                promo.amount,
                style: const TextStyle(
                  fontFamily: 'Manrope',
                 fontFamilyFallback: ['Roboto', 'sans-serif'],
                  fontSize: 22,
                  fontWeight: FontWeight.w800,
                  color: Colors.white,
                  letterSpacing: 1.0,
                  height: 1.1,
                ),
              ),
            ),
            const SizedBox(height: 4),
            Text(
              promo.footer,
              maxLines: 2,
              textAlign: TextAlign.center,
              style: const TextStyle(
                fontFamily: 'Manrope',
               fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 9.5,
                fontWeight: FontWeight.w800,
                color: AppColors.ink700,
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Kotex — red bg, white text, tilted blocks, pink price pill.
class _KotexPromoCard extends StatelessWidget {
  const _KotexPromoCard({required this.promo});
  final KotexPromo promo;

  @override
  Widget build(BuildContext context) {
    return _PromoShell(
      background: promo.background,
      child: Padding(
        padding: const EdgeInsets.all(8),
        child: Column(
          children: [
            Text(
              promo.period,
              style: TextStyle(
                fontFamily: 'Manrope',
               fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 10,
                fontWeight: FontWeight.w800,
                color: Colors.white.withValues(alpha: 0.9),
              ),
            ),
            const SizedBox(height: 4),
            Text(
              promo.title,
              textAlign: TextAlign.center,
              style: const TextStyle(
                fontFamily: 'Manrope',
               fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 14,
                fontWeight: FontWeight.w700,
                color: Colors.white,
                height: 1.1,
              ),
            ),
            const SizedBox(height: 2),
            Text(
              promo.subtitle,
              textAlign: TextAlign.center,
              style: const TextStyle(
                fontFamily: 'Manrope',
               fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 18,
                fontWeight: FontWeight.w800,
                color: Colors.white,
                height: 1.1,
              ),
            ),
            const SizedBox(height: 8),
            _TiltedBlocks(
              colors: const [
                Color(0xFFE94A4A),
                Color(0xFF26A57F),
                Color(0xFFFFB84B),
                Color(0xFF6FB54B),
                Color(0xFFE94A4A),
              ],
              width: 20,
            ),
            const Spacer(),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
              decoration: BoxDecoration(
                gradient: const LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [Color(0xFFFBCDD8), Color(0xFFFADADD)],
                ),
                borderRadius: BorderRadius.circular(6),
              ),
              child: Text(
                promo.amount,
                style: const TextStyle(
                  fontFamily: 'Manrope',
                 fontFamilyFallback: ['Roboto', 'sans-serif'],
                  fontSize: 22,
                  fontWeight: FontWeight.w800,
                  color: Color(0xFFB22424),
                  height: 1.1,
                ),
              ),
            ),
            const SizedBox(height: 4),
            Text(
              promo.footer,
              maxLines: 2,
              textAlign: TextAlign.center,
              style: TextStyle(
                fontFamily: 'Manrope',
               fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 10,
                fontWeight: FontWeight.w800,
                color: Colors.white.withValues(alpha: 0.9),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// Ряд наклонённых цветных блоков (используется в huggies/kotex).
class _TiltedBlocks extends StatelessWidget {
  const _TiltedBlocks({required this.colors, this.width = 28});
  final List<Color> colors;
  final double width;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 50,
      child: Row(
        mainAxisAlignment: MainAxisAlignment.center,
        crossAxisAlignment: CrossAxisAlignment.end,
        children: [
          for (int i = 0; i < colors.length; i++) ...[
            Transform.rotate(
              angle: ((i % 2 == 0 ? 1 : -1) * (i * 2)) * 0.01745,
              child: Container(
                width: width,
                height: 46,
                decoration: BoxDecoration(
                  color: colors[i],
                  borderRadius: BorderRadius.circular(4),
                  boxShadow: const [
                    BoxShadow(
                      color: Color(0x33000000),
                      offset: Offset(0, 2),
                      blurRadius: 4,
                    ),
                  ],
                ),
              ),
            ),
            if (i < colors.length - 1) const SizedBox(width: 3),
          ],
        ],
      ),
    );
  }
}

class _DashedBorderPainter extends CustomPainter {
  _DashedBorderPainter({
    required this.color,
    required this.strokeWidth,
    required this.radius,
    required this.dash,
    required this.gap,
  });

  final Color color;
  final double strokeWidth;
  final double radius;
  final double dash;
  final double gap;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..strokeWidth = strokeWidth
      ..style = PaintingStyle.stroke;
    final rrect = RRect.fromRectAndRadius(
      Offset.zero & size,
      Radius.circular(radius),
    );
    final path = Path()..addRRect(rrect);
    final metrics = path.computeMetrics();
    for (final m in metrics) {
      double dist = 0;
      while (dist < m.length) {
        final segment = m.extractPath(dist, dist + dash);
        canvas.drawPath(segment, paint);
        dist += dash + gap;
      }
    }
  }

  @override
  bool shouldRepaint(_) => false;
}
