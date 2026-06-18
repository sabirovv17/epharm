import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'app_colors.dart';
import 'app_radii.dart';
import 'app_typography.dart';

/// Системные шрифты-фолбэки для глифов отсутствующих в Manrope (напр. ₸).
/// На iOS macOS делает auto-fallback, на Android — нет, поэтому список нужен.
const _fontFamilyFallback = <String>[
  'Roboto', // Android default
  'sans-serif', // Android generic
  'SF Pro Text', // iOS default
  '.SF UI Text', // iOS fallback
];

/// Собранный `ThemeData` для MaterialApp.
abstract final class AppTheme {
  static ThemeData light() {
    final base = ThemeData(
      useMaterial3: true,
      brightness: Brightness.light,
      scaffoldBackgroundColor: AppColors.paperCanvas,
      colorScheme: const ColorScheme.light(
        primary: AppColors.brandGreen600,
        onPrimary: AppColors.textOnBrand,
        secondary: AppColors.brandBlue600,
        onSecondary: AppColors.textOnBrand,
        surface: AppColors.paperCard,
        onSurface: AppColors.ink900,
        error: AppColors.accentWarning,
      ),
      fontFamily: 'Manrope',
      fontFamilyFallback: _fontFamilyFallback,
      textTheme: const TextTheme().apply(
        fontFamily: 'Manrope',
        fontFamilyFallback: _fontFamilyFallback,
        bodyColor: AppColors.ink900,
        displayColor: AppColors.ink900,
      ),
      appBarTheme: const AppBarTheme(
        backgroundColor: Colors.transparent,
        elevation: 0,
        scrolledUnderElevation: 0,
        systemOverlayStyle: systemUiOverlayStyleLight,
      ),
      splashFactory: InkRipple.splashFactory,
    );

    return base.copyWith(
      inputDecorationTheme: InputDecorationTheme(
        filled: true,
        fillColor: AppColors.paperInput,
        contentPadding: const EdgeInsets.symmetric(horizontal: 16, vertical: 16),
        hintStyle: AppTypography.body16(color: AppColors.ink400),
        border: const OutlineInputBorder(
          borderRadius: AppRadii.brMd,
          borderSide: BorderSide.none,
        ),
        enabledBorder: const OutlineInputBorder(
          borderRadius: AppRadii.brMd,
          borderSide: BorderSide.none,
        ),
        focusedBorder: const OutlineInputBorder(
          borderRadius: AppRadii.brMd,
          borderSide: BorderSide(color: AppColors.brandBlue300, width: 2),
        ),
      ),
    );
  }
}

/// Тёмные иконки status bar на светлом фоне.
const systemUiOverlayStyleLight = SystemUiOverlayStyle(
  statusBarColor: Color(0x00000000),
  statusBarIconBrightness: Brightness.dark,
  statusBarBrightness: Brightness.light,
);

/// Светлые иконки status bar на тёмном/брендовом фоне (коралловый header).
const systemUiOverlayStyleDark = SystemUiOverlayStyle(
  statusBarColor: Color(0x00000000),
  statusBarIconBrightness: Brightness.light,
  statusBarBrightness: Brightness.dark,
);
