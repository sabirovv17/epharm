import 'package:flutter/material.dart';

import '../../../../core/theme/app_colors.dart';
import '../../../../core/theme/app_radii.dart';
import '../../../../core/theme/app_shadows.dart';
import '../../../../core/theme/app_spacing.dart';

/// Welcome banner на главном экране для неавторизованного пользователя.
/// `bg-brand-green-700/40 backdrop-blur` (radius 28, p=20), title 26/800,
/// white CTA «Войти» h=60 radius 16 brand-green-600 текст + shadow/fab.
class HomeWelcomeGate extends StatelessWidget {
  const HomeWelcomeGate({super.key, required this.onLogin});
  final VoidCallback onLogin;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.s20),
      decoration: BoxDecoration(
        color: AppColors.brandGreen700.withValues(alpha: 0.4),
        borderRadius: AppRadii.brXxl,
        border: Border.all(
          color: Colors.white.withValues(alpha: 0.15),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text(
            'Добро пожаловать!',
            style: TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              // 22/700 — теперь это secondary под PharmaWordmark (32/800),
              // чтобы вордмарк-бренд был визуально доминирующим. Раньше было
              // 26/800 — конкурировало с лого «как заголовок».
              fontSize: 22,
              fontWeight: FontWeight.w700,
              color: Colors.white,
              height: 1.1,
            ),
          ),
          const SizedBox(height: AppSpacing.s16),
          Material(
            color: Colors.transparent,
            child: InkWell(
              onTap: onLogin,
              borderRadius: AppRadii.brLg,
              child: Container(
                height: 60,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: Colors.white,
                  borderRadius: AppRadii.brLg,
                  boxShadow: AppShadows.fab,
                ),
                child: const Text(
                  'Войти',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontSize: 20,
                    fontWeight: FontWeight.w800,
                    color: AppColors.brandGreen600,
                  ),
                ),
              ),
            ),
          ),
        ],
      ),
    );
  }
}
