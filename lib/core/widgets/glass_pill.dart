import 'package:flutter/material.dart';

import '../theme/app_colors.dart';
import '../theme/app_radii.dart';

/// Glass pill (§6.1): rgba(255,255,255,0.16) + inner-glass shine, h=52 (default), pill radius.
/// Используется на коралловом header'е («История», «Загрузить чек», «Конкурсы»).
class GlassPill extends StatelessWidget {
  const GlassPill({
    super.key,
    required this.label,
    this.icon,
    this.onTap,
    this.height = 52,
    this.expand = true,
  });

  final String label;
  final IconData? icon;
  final VoidCallback? onTap;
  final double height;
  final bool expand;

  @override
  Widget build(BuildContext context) {
    // Коралловая glass-pill для header'а: brand-500 fill (#E78A5C) даёт ЯВНУЮ
    // коралловую плашку поверх gradient header'а (#F0A074 → #E07E52).
    // Раньше использовали white @ 40% — pill читался как «беловато-светлый»,
    // а не как «более брендовый». Сейчас saturated coral с лёгкой white-кромкой.
    final content = Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: AppRadii.brFull,
        child: Container(
          height: height,
          // padding уменьшен 14 → 10 чтобы длинные label'ы вроде «Загрузить чек»
          // (16/800 ~127 px) помещались в pill (~170 px ширина при 2 pill в row).
          padding: const EdgeInsets.symmetric(horizontal: 10),
          decoration: BoxDecoration(
            color: AppColors.brandGreen500.withValues(alpha: 0.55),
            borderRadius: AppRadii.brFull,
            border: Border.all(
              color: const Color(0x66FFFFFF), // ~40% white — мягкая кромка
              width: 1,
            ),
            boxShadow: const [
              BoxShadow(
                color: Color(0x1AFFFFFF),
                offset: Offset(0, 2),
                blurRadius: 6,
              ),
            ],
          ),
          child: Row(
            mainAxisAlignment: MainAxisAlignment.center,
            mainAxisSize: MainAxisSize.min,
            children: [
              if (icon != null) ...[
                Icon(icon, size: 18, color: Colors.white),
                // gap 6 → 4: добавляет +2 px горизонтального места под label.
                const SizedBox(width: 4),
              ],
              Flexible(
                child: Text(
                  label,
                  overflow: TextOverflow.ellipsis,
                  maxLines: 1,
                  style: const TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    // 15/800 — было 16, ужали на 1 px чтобы длинный label
                    // «Загрузить чек» гарантированно входил без ellipsis.
                    fontSize: 15,
                    fontWeight: FontWeight.w800,
                    color: Colors.white,
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
    return expand ? SizedBox(width: double.infinity, child: content) : content;
  }
}
