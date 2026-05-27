import 'package:flutter/material.dart';

import '../../../../core/theme/app_colors.dart';
import '../../../../core/theme/app_radii.dart';
import '../../../../core/theme/app_shadows.dart';
import '../../../../core/theme/app_spacing.dart';
import '../../../../core/theme/app_typography.dart';

/// Welcome-gate card «Войдите в аккаунт».
/// См. `_reference/design-tokens.md` §6.4b.
class LoginInviteCard extends StatelessWidget {
  const LoginInviteCard({super.key, required this.onLogin});

  final VoidCallback onLogin;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.s20),
      decoration: BoxDecoration(
        color: AppColors.brandGreen700.withValues(alpha: 0.4),
        borderRadius: AppRadii.brXl,
        border: Border.all(
          color: Colors.white.withValues(alpha: 0.15),
        ),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            'Войдите в аккаунт',
            style: AppTypography.h2(color: Colors.white),
          ),
          const SizedBox(height: 6),
          Text(
            'Чтобы видеть баланс, историю чеков и участвовать в конкурсах',
            style: AppTypography.caption(
              color: Colors.white.withValues(alpha: 0.85),
            ),
          ),
          const SizedBox(height: AppSpacing.s16),
          Material(
            color: Colors.transparent,
            child: InkWell(
              onTap: onLogin,
              borderRadius: AppRadii.brLg,
              child: Container(
                height: 56,
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
                    fontSize: 18,
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
