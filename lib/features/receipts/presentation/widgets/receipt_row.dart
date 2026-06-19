import 'package:flutter/material.dart';

import '../../../../core/theme/app_colors.dart';
import '../../../../core/theme/app_radii.dart';
import '../../../../core/theme/app_shadows.dart';
import '../../data/receipt_repository.dart';

/// Цветовая схема status-бейджа.
class _BadgeColors {
  const _BadgeColors({
    required this.bg,
    required this.fg,
    required this.border,
    this.dashedBorder = false,
  });
  final Color bg;
  final Color fg;
  final Color border;
  final bool dashedBorder;
}

_BadgeColors _colorsFor(ReceiptStatus s) => switch (s) {
      // Подтверждён — приглушённый teal/green, как на скрине.
      ReceiptStatus.confirmed => const _BadgeColors(
          bg: Color(0xFFE5F8EE),
          fg: Color(0xFF0F8F55),
          border: Color(0xFFA8E2C5),
        ),
      // Проверка — янтарный/orange.
      ReceiptStatus.inReview => const _BadgeColors(
          bg: Color(0xFFFBEADF),
          fg: Color(0xFFC95E33),
          border: Color(0xFFF0AE8C),
        ),
      // Ожидает чека — нейтральный серый (POSM записал, ждём фото от фармацевта).
      ReceiptStatus.awaitingReceipt => const _BadgeColors(
          bg: Color(0xFFEFF1F5),
          fg: Color(0xFF5A6173),
          border: Color(0xFFC2C7D2),
        ),
      // Отклонён — красный, dashed border, как на скриншоте.
      ReceiptStatus.rejected => const _BadgeColors(
          bg: Color(0xFFFFE6E5),
          fg: Color(0xFFC0392B),
          border: Color(0xFFE89B96),
          dashedBorder: true,
        ),
    };

/// Иконка-маркер слева. Цвет повторяет статус-схему чека — лёгкий цветной
/// акцент в начале строки, как у ProfileRow «зелёный квадрат + glyph».
IconData _iconFor(ReceiptStatus s) => switch (s) {
      ReceiptStatus.confirmed => Icons.check_circle_rounded,
      ReceiptStatus.inReview => Icons.hourglass_top_rounded,
      ReceiptStatus.awaitingReceipt => Icons.receipt_long_outlined,
      ReceiptStatus.rejected => Icons.error_outline_rounded,
    };

/// Карточка одного чека в истории.
///
/// **Дизайн в стиле Profile (2026-05-26)**: применён тот же layering-pattern
/// что у `ProfileRow` — `Container(white + shadow/card)` снаружи задаёт
/// surface, `Material(transparent)` внутри отвечает только за ink ripple.
/// Раньше Material был белым, поверх него Container с тенью, и shadow
/// визуально «затемнял» белую surface через прозрачный Container — карточки
/// казались серыми/transparent на фоне.
///
/// Layout (см. design-tokens §6.4 ProfileRow):
/// • Leading: 32 × 32 icon в цвете status'а (зелёная / янтарная / серая /
///   красная — повторяет цвет status-pill).
/// • Center: date · time (12/800 ink/500) + title (17/800 ink/900) +
///   amount (14/800 ink/700).
/// • Trailing: status-pill (тот же `_StatusBadge`, что был раньше).
class ReceiptRow extends StatelessWidget {
  const ReceiptRow({
    super.key,
    required this.receipt,
    this.onTap,
  });

  final Receipt receipt;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final badge = _colorsFor(receipt.status);
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: AppRadii.brLg,
        boxShadow: AppShadows.card,
      ),
      clipBehavior: Clip.antiAlias,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(16, 14, 16, 14),
            child: Row(
              crossAxisAlignment: CrossAxisAlignment.center,
              children: [
                // Leading status-icon — в цвет status'а, без tile-фона
                // (как у ProfileRow: чистая иконка, не плашка).
                SizedBox(
                  width: 32,
                  height: 32,
                  child: Icon(
                    _iconFor(receipt.status),
                    size: 28,
                    color: badge.fg,
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        receipt.dateLabel,
                        style: const TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 12,
                          fontWeight: FontWeight.w800,
                          color: AppColors.ink500,
                          height: 1.2,
                        ),
                      ),
                      const SizedBox(height: 4),
                      Text(
                        receipt.title,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: const TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 17,
                          fontWeight: FontWeight.w800,
                          color: AppColors.ink900,
                          height: 1.25,
                        ),
                      ),
                      // Сумму показываем ТОЛЬКО у подтверждённого чека — и это бонус
                      // фармацевту («+X ₸» зелёным). Пока чек на проверке/ожидании/
                      // отклонён — суммы нет вообще (бонус ещё не начислен).
                      if (receipt.status == ReceiptStatus.confirmed &&
                          receipt.bonusCredited > 0) ...[
                        const SizedBox(height: 2),
                        Text(
                          '+${_formatAmount(receipt.bonusCredited)}',
                          style: const TextStyle(
                            fontFamily: 'Manrope',
                            fontFamilyFallback: ['Roboto', 'sans-serif'],
                            fontSize: 14,
                            fontWeight: FontWeight.w800,
                            color: AppColors.brandGreen700,
                            height: 1.25,
                          ),
                        ),
                      ],
                      if (receipt.status == ReceiptStatus.rejected &&
                          receipt.rejectedReason != null) ...[
                        const SizedBox(height: 4),
                        Text(
                          receipt.rejectedReason!,
                          style: const TextStyle(
                            fontFamily: 'Manrope',
                            fontFamilyFallback: ['Roboto', 'sans-serif'],
                            fontSize: 12,
                            fontWeight: FontWeight.w700,
                            color: Color(0xFFC0392B),
                            height: 1.25,
                          ),
                        ),
                      ],
                    ],
                  ),
                ),
                const SizedBox(width: 12),
                _StatusBadge(status: receipt.status),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

String _formatAmount(int kzt) {
  // 25000 → «25 000 ₸»
  final s = kzt.toString();
  final buf = StringBuffer();
  for (var i = 0; i < s.length; i++) {
    if (i > 0 && (s.length - i) % 3 == 0) buf.write(' ');
    buf.write(s[i]);
  }
  return '${buf.toString()} ₸';
}

class _StatusBadge extends StatelessWidget {
  const _StatusBadge({required this.status});
  final ReceiptStatus status;

  @override
  Widget build(BuildContext context) {
    final c = _colorsFor(status);
    final core = Container(
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(
        color: c.bg,
        borderRadius: BorderRadius.circular(99),
        border: c.dashedBorder
            ? null // dashed рисуем отдельно через CustomPaint
            : Border.all(color: c.border, width: 1),
      ),
      child: Text(
        status.label,
        style: TextStyle(
          fontFamily: 'Manrope',
          fontFamilyFallback: const ['Roboto', 'sans-serif'],
          fontSize: 11,
          fontWeight: FontWeight.w800,
          color: c.fg,
          letterSpacing: 0.4,
          height: 1.1,
        ),
      ),
    );

    if (!c.dashedBorder) return core;
    return CustomPaint(
      foregroundPainter: _DashedPillBorderPainter(color: c.border),
      child: core,
    );
  }
}

class _DashedPillBorderPainter extends CustomPainter {
  _DashedPillBorderPainter({required this.color});
  final Color color;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..strokeWidth = 1
      ..style = PaintingStyle.stroke;
    final rrect = RRect.fromRectAndRadius(
      Offset.zero & size,
      const Radius.circular(99),
    );
    final path = Path()..addRRect(rrect);
    const dash = 4.0;
    const gap = 3.0;
    for (final m in path.computeMetrics()) {
      double dist = 0;
      while (dist < m.length) {
        final segment = m.extractPath(dist, dist + dash);
        canvas.drawPath(segment, paint);
        dist += dash + gap;
      }
    }
  }

  @override
  bool shouldRepaint(_DashedPillBorderPainter old) => old.color != color;
}
