import 'package:flutter/material.dart';

import '../theme/app_colors.dart';

/// Декоративные брендовые иконки Epharm (см. `_reference/.../icons.jsx`).
///
/// Все рисуются через CustomPainter в 40×40 (или 64×64 для Pharm) viewBox.

// ─── Coin ───────────────────────────────────────────────────────────────────
/// Золотая монета с символом ₸ — на balance card.
class CoinGlyph extends StatelessWidget {
  const CoinGlyph({super.key, this.size = 40});
  final double size;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: size,
      height: size,
      child: Stack(
        alignment: Alignment.center,
        children: [
          CustomPaint(
            size: Size.square(size),
            painter: _CoinPainter(),
          ),
          Text(
            '₸',
            style: TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: const ['Roboto', 'sans-serif'],
              fontSize: size * 0.4,
              fontWeight: FontWeight.w800,
              color: const Color(0xFFC95E33),
              height: 1.0,
            ),
          ),
        ],
      ),
    );
  }
}

class _CoinPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final r = size.width / 2;
    final center = Offset(r, r);
    final gradient = const RadialGradient(
      center: Alignment(-0.3, -0.3),
      radius: 0.75,
      colors: [
        Color(0xFFF0A074),
        Color(0xFFE78A5C),
        Color(0xFFD86C3A),
      ],
      stops: [0.0, 0.6, 1.0],
    );
    final paint = Paint()
      ..shader = gradient.createShader(Rect.fromCircle(center: center, radius: r * 0.85));
    canvas.drawCircle(center, r * 0.85, paint);

    // Inner ring
    final ringPaint = Paint()
      ..color = const Color(0xFFF0A074).withValues(alpha: 0.6)
      ..style = PaintingStyle.stroke
      ..strokeWidth = size.width * 0.03;
    canvas.drawCircle(center, r * 0.65, ringPaint);
  }

  @override
  bool shouldRepaint(_) => false;
}

// ─── Gift Emoji ──────────────────────────────────────────────────────────────
/// Синяя подарочная коробка — между пилюлями tier-ladder.
class GiftEmojiGlyph extends StatelessWidget {
  const GiftEmojiGlyph({super.key, this.size = 32});
  final double size;

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      size: Size.square(size),
      painter: _GiftPainter(),
    );
  }
}

class _GiftPainter extends CustomPainter {
  static const _bodyTop = Color(0xFF7AA9FF);
  static const _bodyBottom = Color(0xFF3F7BFF);
  static const _lidColor = Color(0xFF5F8DFF);

  @override
  void paint(Canvas canvas, Size size) {
    final s = size.width / 40.0;

    // Body 6,15,28,20 with linear gradient
    final bodyRect = Rect.fromLTWH(6 * s, 15 * s, 28 * s, 20 * s);
    final bodyPaint = Paint()
      ..shader = const LinearGradient(
        begin: Alignment.topCenter,
        end: Alignment.bottomCenter,
        colors: [_bodyTop, _bodyBottom],
      ).createShader(bodyRect);
    canvas.drawRRect(
      RRect.fromRectAndRadius(bodyRect, Radius.circular(2 * s)),
      bodyPaint,
    );

    // Lid 6,13,28,6
    final lidPaint = Paint()..color = _lidColor;
    final lidRect = Rect.fromLTWH(6 * s, 13 * s, 28 * s, 6 * s);
    canvas.drawRRect(
      RRect.fromRectAndRadius(lidRect, Radius.circular(2 * s)),
      lidPaint,
    );

    // Vertical ribbon
    final ribbonPaint = Paint()..color = Colors.white.withValues(alpha: 0.95);
    canvas.drawRect(
      Rect.fromLTWH(18 * s, 13 * s, 4 * s, 22 * s),
      ribbonPaint,
    );

    // Bow loops
    final bowPaint = Paint()
      ..color = Colors.white
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2 * s
      ..strokeCap = StrokeCap.round;
    final left = Path()
      ..moveTo(18 * s, 12 * s)
      ..cubicTo(15 * s, 9 * s, 11 * s, 11 * s, 11 * s, 14 * s)
      ..cubicTo(11 * s, 16 * s, 14 * s, 17 * s, 18 * s, 16 * s);
    canvas.drawPath(left, bowPaint);
    final right = Path()
      ..moveTo(22 * s, 12 * s)
      ..cubicTo(25 * s, 9 * s, 29 * s, 11 * s, 29 * s, 14 * s)
      ..cubicTo(29 * s, 16 * s, 26 * s, 17 * s, 22 * s, 16 * s);
    canvas.drawPath(right, bowPaint);
  }

  @override
  bool shouldRepaint(_) => false;
}

// ─── Trophy Emoji ────────────────────────────────────────────────────────────
class TrophyEmojiGlyph extends StatelessWidget {
  const TrophyEmojiGlyph({super.key, this.size = 30});
  final double size;

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      size: Size.square(size),
      painter: _TrophyPainter(),
    );
  }
}

class _TrophyPainter extends CustomPainter {
  static const _gold = Color(0xFFF0A074);
  static const _goldDeep = Color(0xFFE78A5C);
  static const _base = Color(0xFFD86C3A);
  static const _baseDeep = Color(0xFFC95E33);

  @override
  void paint(Canvas canvas, Size size) {
    final s = size.width / 40.0;

    // Cup body 13,6 to 27,14 + curve down — simplified to rounded rect
    final cupRect = Rect.fromLTWH(13 * s, 6 * s, 14 * s, 14 * s);
    final cupPaint = Paint()
      ..shader = const LinearGradient(
        begin: Alignment.topCenter,
        end: Alignment.bottomCenter,
        colors: [_gold, _goldDeep],
      ).createShader(cupRect);
    final cupPath = Path()
      ..moveTo(13 * s, 6 * s)
      ..lineTo(27 * s, 6 * s)
      ..lineTo(27 * s, 14 * s)
      ..quadraticBezierTo(20 * s, 22 * s, 13 * s, 14 * s)
      ..close();
    canvas.drawPath(cupPath, cupPaint);

    // Left/right handles
    final handlePaint = Paint()
      ..color = _goldDeep
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.5 * s
      ..strokeCap = StrokeCap.round;
    final leftHandle = Path()
      ..moveTo(13 * s, 8 * s)
      ..lineTo(9 * s, 8 * s)
      ..lineTo(9 * s, 12 * s)
      ..arcToPoint(
        Offset(11 * s, 14 * s),
        radius: Radius.circular(2 * s),
      );
    canvas.drawPath(leftHandle, handlePaint);
    final rightHandle = Path()
      ..moveTo(27 * s, 8 * s)
      ..lineTo(31 * s, 8 * s)
      ..lineTo(31 * s, 12 * s)
      ..arcToPoint(
        Offset(29 * s, 14 * s),
        radius: Radius.circular(2 * s),
        clockwise: false,
      );
    canvas.drawPath(rightHandle, handlePaint);

    // Stem 14,22,12,6
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(14 * s, 22 * s, 12 * s, 6 * s),
        Radius.circular(1 * s),
      ),
      Paint()..color = _base,
    );

    // Base 11,28,18,4
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromLTWH(11 * s, 28 * s, 18 * s, 4 * s),
        Radius.circular(1 * s),
      ),
      Paint()..color = _baseDeep,
    );

    // Highlight
    canvas.drawCircle(
      Offset(20 * s, 11 * s),
      2 * s,
      Paint()..color = Colors.white.withValues(alpha: 0.4),
    );
  }

  @override
  bool shouldRepaint(_) => false;
}

// ─── Pharm (декорация auth-экранов) ──────────────────────────────────────────
/// Большой pharma-крест на белом круге. Используется как фоновая декорация
/// внизу phone/sms/iin экранов.
class PharmGlyph extends StatelessWidget {
  const PharmGlyph({super.key, this.size = 120});
  final double size;

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      size: Size.square(size),
      painter: _PharmPainter(),
    );
  }
}

class _PharmPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final s = size.width / 64.0;
    final r = 30 * s;
    final center = Offset(32 * s, 32 * s);

    // Background circle — белый с лёгким градиентом
    final bgPaint = Paint()
      ..shader = RadialGradient(
        center: const Alignment(-0.2, -0.3),
        radius: 0.7,
        colors: const [Color(0xFFFFFFFF), Color(0xFFE2E6EE)],
      ).createShader(Rect.fromCircle(center: center, radius: r));
    canvas.drawCircle(center, r, bgPaint);

    // Cross — синий
    final crossPaint = Paint()..color = AppColors.brandBlue600;
    final crossPath = Path()
      // Vertical bar 27,12 → 37,52
      ..addRRect(RRect.fromRectAndRadius(
        Rect.fromLTWH(27 * s, 12 * s, 10 * s, 40 * s),
        Radius.circular(5 * s),
      ))
      // Horizontal bar 12,27 → 52,37
      ..addRRect(RRect.fromRectAndRadius(
        Rect.fromLTWH(12 * s, 27 * s, 40 * s, 10 * s),
        Radius.circular(5 * s),
      ));
    canvas.drawPath(crossPath, crossPaint);
  }

  @override
  bool shouldRepaint(_) => false;
}
