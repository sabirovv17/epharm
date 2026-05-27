import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import '../../../../core/theme/app_colors.dart';

/// Карточка «Посмотрите видео-инструкцию». Открывает YouTube по тапу.
///
/// Дизайн повторяет блок из `_reference/profile-pages/faq.jsx` (футер FAQ-экрана):
/// 16:9 синий gradient с белым Epharm-бейджем сверху, плашкой «Бонус до 50%»,
/// красной play-кнопкой по центру и + декоративной иконкой в правом нижнем углу.
class VideoInstructionCard extends StatelessWidget {
  const VideoInstructionCard({
    super.key,
    required this.youtubeUrl,
    this.title = 'Epharm инструкция',
    this.subtitle = 'Pharma Pay',
    this.bonusBadge = 'Бонус до',
    this.bonusValue = '50%',
  });

  final String youtubeUrl;
  final String title;
  final String subtitle;
  final String bonusBadge;
  final String bonusValue;

  Future<void> _open() async {
    final uri = Uri.parse(youtubeUrl);
    // externalApplication → пытается открыть YouTube-app, потом браузер.
    await launchUrl(uri, mode: LaunchMode.externalApplication);
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        const Padding(
          padding: EdgeInsets.only(top: 24, bottom: 12),
          child: Text(
            'Посмотрите видео-инструкцию',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 16,
              fontWeight: FontWeight.w800,
              color: AppColors.ink900,
              height: 1.3,
            ),
          ),
        ),
        Material(
          color: Colors.transparent,
          borderRadius: BorderRadius.circular(20),
          child: InkWell(
            onTap: _open,
            borderRadius: BorderRadius.circular(20),
            child: ClipRRect(
              borderRadius: BorderRadius.circular(20),
              child: AspectRatio(
                aspectRatio: 16 / 9,
                child: Stack(
                  fit: StackFit.expand,
                  children: [
                    // Основной синий градиент (135deg).
                    const DecoratedBox(
                      decoration: BoxDecoration(
                        gradient: LinearGradient(
                          begin: Alignment.topLeft,
                          end: Alignment.bottomRight,
                          colors: [
                            Color(0xFF1147A8),
                            Color(0xFF1E70D8),
                            Color(0xFF29A5E0),
                          ],
                          stops: [0.0, 0.45, 1.0],
                        ),
                      ),
                    ),
                    // Декоративный второй слой с радиальным светом + штриховкой.
                    CustomPaint(painter: _NoisePainter()),
                    // Верхний левый бейдж: круглый «pharma pay».
                    const Positioned(
                      top: 12,
                      left: 12,
                      child: _BrandBadge(),
                    ),
                    // Тексты рядом с бейджем.
                    Positioned(
                      top: 12,
                      left: 56,
                      right: 12,
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        mainAxisSize: MainAxisSize.min,
                        children: [
                          Text(
                            title,
                            maxLines: 1,
                            overflow: TextOverflow.ellipsis,
                            style: const TextStyle(
                              fontFamily: 'Manrope',
                              fontFamilyFallback: ['Roboto', 'sans-serif'],
                              fontSize: 15,
                              fontWeight: FontWeight.w800,
                              color: Colors.white,
                              height: 1.15,
                            ),
                          ),
                          Text(
                            subtitle,
                            style: TextStyle(
                              fontFamily: 'Manrope',
                              fontFamilyFallback: const ['Roboto', 'sans-serif'],
                              fontSize: 10,
                              fontWeight: FontWeight.w800,
                              color: Colors.white.withValues(alpha: 0.9),
                              height: 1.2,
                            ),
                          ),
                        ],
                      ),
                    ),
                    // Плашка «Бонус до 50%» — две пилюли в одну строку.
                    Positioned(
                      left: 12,
                      top: 0,
                      bottom: 0,
                      child: Align(
                        alignment: Alignment.centerLeft,
                        child: Padding(
                          padding: const EdgeInsets.only(top: 4),
                          child: Row(
                            mainAxisSize: MainAxisSize.min,
                            crossAxisAlignment: CrossAxisAlignment.center,
                            children: [
                              _BonusPill(text: bonusBadge, size: 14),
                              const SizedBox(width: 4),
                              _BonusPill(text: bonusValue, size: 18),
                            ],
                          ),
                        ),
                      ),
                    ),
                    // Декоративный «+» bottom-right.
                    Positioned(
                      right: 16,
                      bottom: 12,
                      child: Container(
                        width: 64,
                        height: 64,
                        alignment: Alignment.center,
                        decoration: BoxDecoration(
                          gradient: const RadialGradient(
                            center: Alignment(-0.3, -0.3),
                            radius: 0.9,
                            colors: [
                              Color(0xFF5FCEFF),
                              Color(0xFF1581D4),
                              Color(0xFF0C4A8A),
                            ],
                            stops: [0.0, 0.6, 1.0],
                          ),
                          borderRadius: BorderRadius.circular(14),
                          boxShadow: const [
                            BoxShadow(
                              color: Color(0x33FFFFFF),
                              offset: Offset(0, 0),
                              blurRadius: 0,
                              spreadRadius: 0,
                            ),
                          ],
                        ),
                        child: const Icon(
                          Icons.add_rounded,
                          size: 36,
                          color: Colors.white,
                        ),
                      ),
                    ),
                    // Красная YouTube-style play-кнопка по центру.
                    Center(
                      child: Container(
                        width: 64,
                        height: 44,
                        alignment: Alignment.center,
                        decoration: BoxDecoration(
                          color: const Color(0xFFFF0033),
                          borderRadius: BorderRadius.circular(10),
                          boxShadow: const [
                            BoxShadow(
                              color: Color(0x590F1424),
                              offset: Offset(0, 4),
                              blurRadius: 14,
                            ),
                          ],
                        ),
                        child: const Icon(
                          Icons.play_arrow_rounded,
                          size: 32,
                          color: Colors.white,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

/// Маленький круглый бейдж «pharma pay» в левом верхнем углу карточки.
class _BrandBadge extends StatelessWidget {
  const _BrandBadge();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 36,
      height: 36,
      alignment: Alignment.center,
      decoration: const BoxDecoration(
        color: Colors.white,
        shape: BoxShape.circle,
      ),
      child: const Text.rich(
        TextSpan(
          style: TextStyle(
            fontFamily: 'Manrope',
            fontFamilyFallback: ['Roboto', 'sans-serif'],
            fontSize: 7,
            fontWeight: FontWeight.w800,
            color: Color(0xFF1E1FCC),
            height: 1.1,
          ),
          children: [
            TextSpan(text: 'pharma\n'),
            TextSpan(text: 'pay'),
          ],
        ),
        textAlign: TextAlign.center,
      ),
    );
  }
}

/// Полупрозрачная sky-blue пилюля с текстом — для «Бонус до» / «50%».
class _BonusPill extends StatelessWidget {
  const _BonusPill({required this.text, required this.size});
  final String text;
  final double size;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 5, vertical: 2),
      decoration: BoxDecoration(
        color: const Color(0xCC7BCDEF), // sky-blue with alpha
        borderRadius: BorderRadius.circular(4),
      ),
      child: Text(
        text,
        style: TextStyle(
          fontFamily: 'Manrope',
          fontFamilyFallback: const ['Roboto', 'sans-serif'],
          fontSize: size,
          fontWeight: FontWeight.w800,
          color: Colors.white,
          height: 1.1,
        ),
      ),
    );
  }
}

/// Шумовая подложка: радиальный свет + диагональная штриховка прозрачного
/// белого. Имитирует фактуру из референса.
class _NoisePainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    // Радиальный свет в правой нижней области.
    final radial = Paint()
      ..shader = RadialGradient(
        center: const Alignment(0.6, 0.2),
        radius: 0.7,
        colors: [
          Colors.white.withValues(alpha: 0.25),
          Colors.transparent,
        ],
      ).createShader(Rect.fromLTWH(0, 0, size.width, size.height));
    canvas.drawRect(Offset.zero & size, radial);

    // Диагональная штриховка.
    final hatchPaint = Paint()
      ..color = Colors.white.withValues(alpha: 0.06)
      ..strokeWidth = 1;
    const step = 14.0;
    const angle = 60 * 3.14159265 / 180;
    final dx = step / 1.0;
    final dy = step / 1.0;
    for (double offset = -size.height;
        offset < size.width + size.height;
        offset += step) {
      canvas.drawLine(
        Offset(offset, 0),
        Offset(offset + size.height / 1.732, size.height),
        hatchPaint,
      );
    }
    // suppress unused
    [angle, dx, dy].toString();
  }

  @override
  bool shouldRepaint(covariant _) => false;
}
