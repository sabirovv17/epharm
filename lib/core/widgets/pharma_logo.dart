import 'package:flutter/material.dart';

import '../theme/app_colors.dart';

/// Wordmark «Epharm»: "E" в `brand/blue/600` (короткий акцент-монограмма),
/// "pharm" в текущем цвете (по умолчанию white).
///
/// Раньше было «PharmaPay» (Pharma white + Pay blue). После ребрендинга
/// 2026-05-26 (PharmaPay → Epharm) тот же визуальный паттерн — короткая
/// часть = blue-accent, длинная = основной цвет — сохранён, только
/// акцентная часть стала одной буквой "E". Это создаёт monogram-feel
/// (как у Spotify, Telegram, etc.).
///
/// Размеры:
///  - sm = 20px (chrome бар, мелкий контекст)
///  - md = 32px (default — Home / Receipts header, должен быть заметнее
///    «Добро пожаловать!» 22/700 и других sub-headings)
///  - lg = 38px (Welcome splash, auth screens)
class PharmaWordmark extends StatelessWidget {
  const PharmaWordmark({
    super.key,
    this.color = Colors.white,
    this.size = PharmaWordmarkSize.md,
  });

  /// Цвет основной части ("pharm"). По умолчанию белый — для зелёного header.
  final Color color;
  final PharmaWordmarkSize size;

  double get _fontSize => switch (size) {
        PharmaWordmarkSize.sm => 20,
        PharmaWordmarkSize.md => 32,
        PharmaWordmarkSize.lg => 38,
      };

  @override
  Widget build(BuildContext context) {
    final fs = _fontSize;
    return RichText(
      text: TextSpan(
        style: TextStyle(
          fontFamily: 'Manrope',
          fontSize: fs,
          fontWeight: FontWeight.w800,
          height: 1.0,
          letterSpacing: -0.02 * fs,
          color: color,
        ),
        children: const [
          TextSpan(
            text: 'E',
            style: TextStyle(color: AppColors.brandBlue600),
          ),
          TextSpan(text: 'pharm'),
        ],
      ),
    );
  }
}

enum PharmaWordmarkSize { sm, md, lg }
