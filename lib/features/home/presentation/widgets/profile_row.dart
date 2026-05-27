import 'package:flutter/material.dart';

import '../../../../core/theme/app_colors.dart';
import '../../../../core/theme/app_radii.dart';
import '../../../../core/theme/app_shadows.dart';

/// Строка списка на экране Профиль: белая карточка с иконкой-плиткой,
/// текстом и шевроном справа.
///
/// Используется в секциях «Помощь» и «О приложении».
class ProfileRow extends StatelessWidget {
  const ProfileRow({
    super.key,
    required this.icon,
    required this.label,
    this.onTap,
  });

  final IconData icon;
  final String label;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    // Чистая белая карточка с лёгкой тенью — как на дизайн-эталоне.
    // Раньше Container не задавал color, поэтому белый рендерил Material
    // _под_ Container'ом, и shadow в Container'е визуально «затемнял»
    // surface — карточка казалась серой. Сейчас Container сам белый + тень,
    // Material снаружи прозрачный (только для InkWell ripple).
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
            padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
            child: Row(
              children: [
                SizedBox(
                  width: 32,
                  height: 32,
                  child: Icon(
                    icon,
                    // Иконка 28 px (немного крупнее старых 26), цвет
                    // brand-green-600 — зелёный — оставлен primary brand
                    // accent, не уходим в синий.
                    size: 28,
                    color: AppColors.brandGreen600,
                  ),
                ),
                const SizedBox(width: 14),
                Expanded(
                  child: Text(
                    label,
                    style: const TextStyle(
                      fontFamily: 'Manrope',
                      fontFamilyFallback: ['Roboto', 'sans-serif'],
                      // List-row title (H3 role) — 18/800. Раньше было 17/800;
                      // подняли на 1px для совпадения с дизайн-эталоном, где
                      // label выглядит чуть крупнее body-strong.
                      fontSize: 18,
                      fontWeight: FontWeight.w800,
                      color: AppColors.ink900,
                      height: 1.25,
                    ),
                  ),
                ),
                const SizedBox(width: 8),
                Icon(
                  Icons.chevron_right_rounded,
                  size: 22,
                  color: AppColors.ink400,
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
