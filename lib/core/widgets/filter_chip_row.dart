import 'package:flutter/material.dart';

import '../theme/app_colors.dart';
import '../theme/app_radii.dart';
import '../theme/app_shadows.dart';

/// Filter chip — h=44, pill, active brand-green-600+shadow/fab, inactive white+shadow/card (§6.1).
class PharmaFilterChip extends StatelessWidget {
  const PharmaFilterChip({
    super.key,
    required this.label,
    this.active = false,
    this.leading,
    this.trailing,
    this.countBadge,
    this.onTap,
  });

  final String label;
  final bool active;
  final Widget? leading;
  final Widget? trailing;
  final int? countBadge;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final fg = active ? Colors.white : AppColors.ink900;
    final bg = active ? AppColors.brandGreen600 : Colors.white;

    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: AppRadii.brFull,
        child: Container(
          height: 44,
          padding: const EdgeInsets.symmetric(horizontal: 18),
          decoration: BoxDecoration(
            color: bg,
            borderRadius: AppRadii.brFull,
            boxShadow: active ? AppShadows.fab : AppShadows.card,
          ),
          child: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              if (leading != null) ...[
                leading!,
                const SizedBox(width: 6),
              ],
              Text(
                label,
                style: TextStyle(
                  fontFamily: 'Manrope',
                  fontSize: 16,
                  // Body-strong (filter chip) per Fonts.md §6 → 700.
                  fontWeight: FontWeight.w800,
                  color: fg,
                ),
              ),
              if (trailing != null) ...[
                const SizedBox(width: 6),
                trailing!,
              ],
              if (countBadge != null && countBadge! > 0) ...[
                const SizedBox(width: 6),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 1),
                  decoration: BoxDecoration(
                    color: active
                        ? Colors.white.withValues(alpha: 0.3)
                        : AppColors.brandGreen600,
                    borderRadius: AppRadii.brFull,
                  ),
                  child: Text(
                    countBadge.toString(),
                    style: const TextStyle(
                      fontFamily: 'Manrope',
                      fontSize: 11,
                      fontWeight: FontWeight.w800,
                      color: Colors.white,
                    ),
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

/// Sort кнопка — 44×44 circle.
class SortChipButton extends StatelessWidget {
  const SortChipButton({super.key, this.onTap, this.active = false});
  final VoidCallback? onTap;

  /// Подсвечивает кнопку (зелёный фон), если применена не-дефолтная сортировка.
  final bool active;

  @override
  Widget build(BuildContext context) {
    final bg = active ? AppColors.brandGreen600 : Colors.white;
    final fg = active ? Colors.white : AppColors.ink900;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: AppRadii.brFull,
        child: Container(
          width: 44,
          height: 44,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: bg,
            borderRadius: AppRadii.brFull,
            boxShadow: active ? AppShadows.fab : AppShadows.card,
          ),
          child: Icon(Icons.swap_vert, size: 22, color: fg),
        ),
      ),
    );
  }
}
