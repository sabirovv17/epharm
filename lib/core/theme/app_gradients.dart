import 'package:flutter/material.dart';

/// Градиенты Epharm. Источник: `_reference/design-tokens.md` §8.
abstract final class AppGradients {
  /// Welcome screen — большой 3-stop сверху вниз.
  static const LinearGradient welcome = LinearGradient(
    begin: Alignment.topCenter,
    end: Alignment.bottomCenter,
    colors: [
      Color(0xFF3DCDA2),
      Color(0xFF21D17A),
      Color(0xFF16A65C),
    ],
    stops: [0.0, 0.5, 1.0],
  );

  /// Header authed/unauthed Home (`grad-blueHead`).
  /// Смягчённый 3-stop: чуть менее «неоновый» верх + плавный спуск к более
  /// мягкому низу — чтобы стык зелёной шапки со светлым канвасом не резал глаз.
  static const LinearGradient header = LinearGradient(
    begin: Alignment.topCenter,
    end: Alignment.bottomCenter,
    colors: [
      Color(0xFF2ECC86),
      Color(0xFF1FBE74),
      Color(0xFF1AAE66),
    ],
    stops: [0.0, 0.55, 1.0],
  );

  /// Плейсхолдер баннера (пока нет реальных): мягкая светлая подложка с лёгким
  /// вертикальным градиентом — спокойный блок, не «тяжёлый серый» (ink300),
  /// гармонично лежит на светлом канвасе.
  static const LinearGradient bannerPlaceholder = LinearGradient(
    begin: Alignment.topCenter,
    end: Alignment.bottomCenter,
    colors: [
      Color(0xFFEDF0F5),
      Color(0xFFDFE5EE),
    ],
  );

  /// Bottom-sheet «Загрузить чек» — 150° диагональ.
  /// Flutter не оперирует углом напрямую — задаём через alignments.
  static const LinearGradient receiptSheet = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [
      Color(0xFF3DCDA2),
      Color(0xFF21D17A),
      Color(0xFF16A65C),
    ],
    stops: [0.0, 0.5, 1.0],
  );

  /// Обложка курса — синяя.
  static const LinearGradient courseCoverBlue = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [
      Color(0xFF2A2BE2),
      Color(0xFF5560FB),
    ],
  );

  /// Обложка курса — зелёная.
  static const LinearGradient courseCoverGreen = LinearGradient(
    begin: Alignment.topLeft,
    end: Alignment.bottomRight,
    colors: [
      Color(0xFF16A65C),
      Color(0xFF21D17A),
    ],
  );

  /// Монета — радиальная.
  static const RadialGradient coin = RadialGradient(
    radius: 0.35,
    colors: [
      Color(0xFFFFE07A),
      Color(0xFFF4B73A),
      Color(0xFFB97F11),
    ],
    stops: [0.0, 0.6, 1.0],
  );
}
