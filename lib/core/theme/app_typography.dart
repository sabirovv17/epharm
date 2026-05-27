import 'package:flutter/material.dart';

import 'app_colors.dart';

/// Типографика Epharm. Источник: `_reference/design-tokens.md` §2.
///
/// Семейство: Manrope (variable font бандлится в `assets/fonts/Manrope/`).
/// Веса: 500 / 600 / 700 / 800.
///
/// **Note (2026)**: после раунда правок «сделай шрифт +10% жирнее» все роли
/// поднялись на одну ступень. Текущая карта весов:
///   body14/body16/caption: 600 → **700**
///   captionSmall:          500 → **600**
///   bodyStrong / title20:  700 → **800**
///   micro / button:        700 → **800**
///   display/h1/h2/h3/otp:  остались на 800 (max в нашем бандле)
abstract final class AppTypography {
  static const String _family = 'Manrope';

  // --- Веса ---
  static const FontWeight w500 = FontWeight.w500;
  static const FontWeight w600 = FontWeight.w600;
  static const FontWeight w700 = FontWeight.w700;
  static const FontWeight w800 = FontWeight.w800;

  static TextStyle _base({
    required double size,
    required double height,
    required FontWeight weight,
    Color color = AppColors.ink900,
    double? letterSpacing,
  }) {
    return TextStyle(
      fontFamily: _family,
      fontSize: size,
      height: height / size,
      fontWeight: weight,
      color: color,
      letterSpacing: letterSpacing,
    );
  }

  /// Display 32 / 38 / 800 — логотип, «Добро пожаловать!».
  static TextStyle display({Color color = AppColors.ink900}) =>
      _base(size: 32, height: 38, weight: w800, color: color);

  /// H1 26 / 32 / 800 — заголовки экранов авторизации.
  static TextStyle h1({Color color = AppColors.ink900}) =>
      _base(size: 26, height: 32, weight: w800, color: color);

  /// H2 22 / 28 / 800 — титулы экранов, имя в профиле.
  static TextStyle h2({Color color = AppColors.ink900}) =>
      _base(size: 22, height: 28, weight: w800, color: color);

  /// H3 / Title 18 / 24 / 800.
  static TextStyle h3({Color color = AppColors.ink900}) =>
      _base(size: 18, height: 24, weight: w800, color: color);

  static TextStyle title20({Color color = AppColors.ink900}) =>
      _base(size: 20, height: 24, weight: w800, color: color);

  /// Body-strong 16 / 22 / **800** (раньше 700 — bump после ревью).
  static TextStyle bodyStrong({Color color = AppColors.ink900}) =>
      _base(size: 16, height: 22, weight: w800, color: color);

  /// Body 16 / 22 / **700** (раньше 600).
  static TextStyle body16({Color color = AppColors.ink700}) =>
      _base(size: 16, height: 22, weight: w700, color: color);

  /// Body 14 / 20 / **700** (раньше 600).
  static TextStyle body14({Color color = AppColors.ink700}) =>
      _base(size: 14, height: 20, weight: w700, color: color);

  /// Caption 13 / 18 / **700** (раньше 600).
  static TextStyle caption({Color color = AppColors.ink500}) =>
      _base(size: 13, height: 18, weight: w700, color: color);

  /// Caption 12 / 16 / **600** (раньше 500).
  static TextStyle captionSmall({Color color = AppColors.ink500}) =>
      _base(size: 12, height: 16, weight: w600, color: color);

  /// Micro 11 / 16 / **800** (раньше 700).
  static TextStyle micro({Color color = AppColors.ink400}) =>
      _base(size: 11, height: 16, weight: w800, color: color);

  /// Цифра OTP 28 / 32 / 800.
  static TextStyle otpDigit({Color color = AppColors.ink900}) =>
      _base(size: 28, height: 32, weight: w800, color: color);

  /// CTA-кнопка 18 / 22 / **800** (раньше 700 — теперь max).
  static TextStyle button({
    Color color = AppColors.textOnBrand,
    FontWeight weight = w800,
  }) =>
      _base(size: 18, height: 22, weight: weight, color: color);
}
