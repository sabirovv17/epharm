import 'package:flutter/material.dart';

/// Цветовые токены Epharm.
/// Источник: `_reference/design-tokens.md` §1.
///
/// Использовать только через эти константы — не хардкодить хексы в виджетах.
abstract final class AppColors {
  // Brand — Coral (primary). Бренд = Claude coral.
  // Имена brandGreen* сохранены для совместимости с виджетами; значения — коралл.
  static const Color brandGreen700 = Color(0xFFBE5A38);
  static const Color brandGreen600 = Color(0xFFD97757); // PRIMARY
  static const Color brandGreen500 = Color(0xFFDB7F57);
  static const Color brandGreen400 = Color(0xFFE0916B);
  static const Color brandGreen100 = Color(0xFFF8E7DD);

  // Brand — Coral accent (бывш. синий; моно-бренд, акцент чуть глубже).
  static const Color brandBlue700 = Color(0xFFA8472A);
  static const Color brandBlue600 = Color(0xFFBE5A38);
  static const Color brandBlue500 = Color(0xFFD97757);
  static const Color brandBlue400 = Color(0xFFE0916B);
  static const Color brandBlue300 = Color(0xFFE4A485);
  static const Color brandBlue200 = Color(0xFFF0C8B4);
  static const Color brandBlue100 = Color(0xFFF8E7DD);

  // Neutrals — ink (тёплый)
  static const Color ink900 = Color(0xFF221C16);
  static const Color ink700 = Color(0xFF423B32);
  static const Color ink500 = Color(0xFF6F665B);
  static const Color ink400 = Color(0xFF9D9388);
  static const Color ink300 = Color(0xFFD4CCC0);

  // Surfaces (кремовый)
  static const Color paperCanvas = Color(0xFFFAF7F2);
  static const Color paperCard = Color(0xFFFFFFFF);
  static const Color paperInput = Color(0xFFF3EEE7);

  // Text on brand fills
  static const Color textOnBrand = Color(0xFFFFFFFF);

  // Borders / overlays (тёплый ink-тон)
  static const Color borderHairline = Color(0x0F221C16); // rgba(34,28,22,0.06)
  static const Color overlayScrim = Color(0x73221C16); // rgba(34,28,22,0.45)

  // Status / accents
  static const Color accentTrophy = Color(0xFFF4B73A);
  static const Color accentWarning = Color(0xFFF1B416);
  static const Color accentCoinDeep = Color(0xFFB97F11);
}
