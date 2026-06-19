import 'package:flutter/material.dart';

import '../../../../core/theme/app_colors.dart';
import '../../../../core/theme/app_radii.dart';
import '../../../../core/theme/app_spacing.dart';
import '../../../../core/widgets/glass_pill.dart';
import '../../../../core/widgets/pharma_logo.dart';

/// Balance card — на зелёном header'е после авторизации.
/// Coin + «Epharm / Баланс» + сумма (24/800 white tabular) + 2 glass-pill (История, Загрузить чек).
class BalanceCard extends StatelessWidget {
  const BalanceCard({
    super.key,
    required this.balanceKzt,
    required this.onHistory,
    required this.onUpload,
  });

  final int balanceKzt;
  final VoidCallback onHistory;
  final VoidCallback onUpload;

  String _format(int v) {
    final s = v.toString();
    final b = StringBuffer();
    for (int i = 0; i < s.length; i++) {
      if (i > 0 && (s.length - i) % 3 == 0) b.write(' ');
      b.write(s[i]);
    }
    return '$b ₸';
  }

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(AppSpacing.s16),
      decoration: BoxDecoration(
        color: AppColors.brandGreen700.withValues(alpha: 0.4),
        borderRadius: AppRadii.brXxl,
        border: Border.all(
          color: Colors.white.withValues(alpha: 0.1),
        ),
      ),
      child: Column(
        children: [
          Row(
            children: [
              const PharmaLogo(size: 40),
              const SizedBox(width: 12),
              const Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      'Epharm',
                      style: TextStyle(
                        fontFamily: 'Manrope',
                        fontSize: 14,
                        fontWeight: FontWeight.w700,
                        color: Colors.white,
                        height: 1.15,
                      ),
                    ),
                    Text(
                      'Баланс',
                      style: TextStyle(
                        fontFamily: 'Manrope',
                        fontSize: 14,
                        fontWeight: FontWeight.w700,
                        color: Colors.white,
                        height: 1.15,
                      ),
                    ),
                  ],
                ),
              ),
              Text(
                _format(balanceKzt),
                style: const TextStyle(
                  fontFamily: 'Manrope',
                  fontFamilyFallback: ['Roboto', 'sans-serif'],
                  fontSize: 24,
                  fontWeight: FontWeight.w800,
                  color: Colors.white,
                  fontFeatures: [FontFeature.tabularFigures()],
                ),
              ),
            ],
          ),
          const SizedBox(height: AppSpacing.s16),
          Row(
            children: [
              Expanded(
                child: GlassPill(
                  label: 'История',
                  icon: Icons.access_time,
                  onTap: onHistory,
                ),
              ),
              const SizedBox(width: 10),
              Expanded(
                child: GlassPill(
                  label: 'Загрузить чек',
                  icon: Icons.upload_outlined,
                  onTap: onUpload,
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}
