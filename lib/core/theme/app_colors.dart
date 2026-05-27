import 'package:flutter/material.dart';

/// Цветовые токены Epharm.
/// Источник: `_reference/design-tokens.md` §1.
///
/// Использовать только через эти константы — не хардкодить хексы в виджетах.
abstract final class AppColors {
  // Brand — Green (primary)
  static const Color brandGreen700 = Color(0xFF0F8F55);
  static const Color brandGreen600 = Color(0xFF16C97A);
  static const Color brandGreen500 = Color(0xFF21D17A);
  static const Color brandGreen400 = Color(0xFF3DCDA2);
  static const Color brandGreen100 = Color(0xFFE5F8EE);

  // Brand — Blue (secondary / accents)
  static const Color brandBlue700 = Color(0xFF1F1FCC);
  static const Color brandBlue600 = Color(0xFF2A2BE2);
  static const Color brandBlue500 = Color(0xFF3F47F0);
  static const Color brandBlue400 = Color(0xFF5560FB);
  static const Color brandBlue300 = Color(0xFF8189FF);
  static const Color brandBlue200 = Color(0xFFC9CCFF);
  static const Color brandBlue100 = Color(0xFFE8EAFE);

  // Neutrals — ink
  static const Color ink900 = Color(0xFF0F1424);
  static const Color ink700 = Color(0xFF2A2F40);
  static const Color ink500 = Color(0xFF5A6173);
  static const Color ink400 = Color(0xFF9098A6);
  static const Color ink300 = Color(0xFFC2C7D2);

  // Surfaces
  static const Color paperCanvas = Color(0xFFF4F6FA);
  static const Color paperCard = Color(0xFFFFFFFF);
  static const Color paperInput = Color(0xFFF2F4F8);

  // Text on brand fills
  static const Color textOnBrand = Color(0xFFFFFFFF);

  // Borders / overlays
  static const Color borderHairline = Color(0x0F0F1424); // rgba(15,20,36,0.06)
  static const Color overlayScrim = Color(0x730F1424); // rgba(15,20,36,0.45)

  // Status / accents
  static const Color accentTrophy = Color(0xFFF4B73A);
  static const Color accentWarning = Color(0xFFF1B416);
  static const Color accentCoinDeep = Color(0xFFB97F11);
}
