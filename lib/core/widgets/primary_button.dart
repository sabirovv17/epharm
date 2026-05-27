import 'package:flutter/material.dart';

import '../theme/app_colors.dart';
import '../theme/app_radii.dart';
import '../theme/app_shadows.dart';
import '../theme/app_typography.dart';

/// Цвет/высота CTA-кнопки.
enum PrimaryButtonVariant {
  /// `brand/green/600`, высота 60 — главный CTA на экранах.
  green,

  /// `brand/blue/600`, высота 60 — «Загрузить чек» внутри листа.
  blue,

  /// `brand/green/400`, высота 56 — submit на phone/OTP экранах.
  mint,
}

/// Главная pill-кнопка Epharm.
/// См. `_reference/design-tokens.md` §6.1.
class PrimaryButton extends StatelessWidget {
  const PrimaryButton({
    super.key,
    required this.label,
    required this.onPressed,
    this.variant = PrimaryButtonVariant.green,
    this.expand = true,
  });

  final String label;
  final VoidCallback? onPressed;
  final PrimaryButtonVariant variant;
  final bool expand;

  bool get _enabled => onPressed != null;

  Color get _surface => switch (variant) {
        PrimaryButtonVariant.green => AppColors.brandGreen600,
        PrimaryButtonVariant.blue => AppColors.brandBlue600,
        PrimaryButtonVariant.mint => AppColors.brandGreen400,
      };

  double get _height => switch (variant) {
        PrimaryButtonVariant.green => 60,
        PrimaryButtonVariant.blue => 60,
        PrimaryButtonVariant.mint => 56,
      };

  @override
  Widget build(BuildContext context) {
    final child = AnimatedOpacity(
      duration: const Duration(milliseconds: 120),
      opacity: _enabled ? 1 : 0.5,
      child: Container(
        height: _height,
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: _surface,
          borderRadius: AppRadii.brFull,
          boxShadow: _enabled ? AppShadows.fab : null,
        ),
        child: Text(
          label,
          style: AppTypography.button(weight: AppTypography.w800),
        ),
      ),
    );

    final button = Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onPressed,
        borderRadius: AppRadii.brFull,
        child: child,
      ),
    );

    return expand ? SizedBox(width: double.infinity, child: button) : button;
  }
}
