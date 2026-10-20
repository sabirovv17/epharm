import 'package:flutter/material.dart';

/// Цветовые токены Epharm.
/// Источник: `_reference/design-tokens.md` §1.
///
/// Использовать только через эти константы — не хардкодить хексы в виджетах.
abstract final class AppColors {
  // Pharmacy brand — selected mobile direction: deep clinical teal + mint.
  static const Color brandGreen700 = Color(0xFF073D3A);
  static const Color brandGreen600 = Color(0xFF0F766E);
  static const Color brandGreen500 = Color(0xFF149B8F);
  static const Color brandGreen400 = Color(0xFF63CFC2);
  static const Color brandGreen100 = Color(0xFFDDF4EF);

  // Warm accent — deadlines, overdue states and rare emphasis only.
  static const Color brandBlue700 = Color(0xFFB84F35);
  static const Color brandBlue600 = Color(0xFFD46749);
  static const Color brandBlue500 = Color(0xFFE47A59);
  static const Color brandBlue400 = Color(0xFFEA9B82);
  static const Color brandBlue300 = Color(0xFFF0B9A7);
  static const Color brandBlue200 = Color(0xFFF5D2C7);
  static const Color brandBlue100 = Color(0xFFFBEAE4);

  // Cool neutral ink for a precise clinical feel.
  static const Color ink900 = Color(0xFF071B26);
  static const Color ink700 = Color(0xFF334A55);
  static const Color ink500 = Color(0xFF6B7F88);
  static const Color ink400 = Color(0xFF91A0A7);
  static const Color ink300 = Color(0xFFCBD5D9);

  // Surfaces.
  static const Color paperCanvas = Color(0xFFF7FAF9);
  static const Color paperCard = Color(0xFFFFFFFF);
  static const Color paperInput = Color(0xFFEFF5F3);

  // Text on brand fills
  static const Color textOnBrand = Color(0xFFFFFFFF);

  // Borders / overlays.
  static const Color borderHairline = Color(0x1A0F766E);
  static const Color overlayScrim = Color(0x73071B26);

  // Status / accents
  static const Color accentTrophy = Color(0xFFD46749);
  static const Color accentWarning = Color(0xFFD46749);
  static const Color accentCoinDeep = Color(0xFF0F766E);
}
