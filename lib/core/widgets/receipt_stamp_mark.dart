import 'package:flutter/material.dart';

import '../theme/app_colors.dart';

/// Receipt Stamp — основной знак Epharm (§7).
/// 64×64 viewBox: receipt body 32×43, stamp disc r=11 в (32, 14),
/// крест 14×3 + 3×14, zigzag bottom edge (5 V-notches).
///
/// Используется в header lockup и как иконка.
class ReceiptStampMark extends StatelessWidget {
  const ReceiptStampMark({
    super.key,
    this.size = 56,
    this.onSurface = ReceiptStampSurface.light,
  });

  final double size;
  final ReceiptStampSurface onSurface;

  @override
  Widget build(BuildContext context) {
    return CustomPaint(
      size: Size.square(size),
      painter: _ReceiptStampPainter(onSurface: onSurface),
    );
  }
}

enum ReceiptStampSurface { light, green }

class _ReceiptStampPainter extends CustomPainter {
  _ReceiptStampPainter({required this.onSurface});

  final ReceiptStampSurface onSurface;

  /// Receipt stroke + lines color (зелёный на light, белый на green).
  Color get _receiptStrokeColor => switch (onSurface) {
        ReceiptStampSurface.light => AppColors.brandGreen600,
        ReceiptStampSurface.green => Colors.white,
      };

  Color get _receiptFillColor => switch (onSurface) {
        ReceiptStampSurface.light => Colors.white,
        ReceiptStampSurface.green => Colors.white,
      };

  static const Color _stampColor = AppColors.brandBlue600;
  static const Color _crossColor = Colors.white;

  @override
  void paint(Canvas canvas, Size size) {
    // Mapping 64×64 viewBox → canvas size.
    final s = size.width / 64.0;

    // ---- Receipt body (зигзаг внизу) ----
    // 32×43 body, центрировано: x=[16..48], y=[14..57]
    // Zigzag: 5 V-notches на нижней грани (y=53 верхушки notch peaks, y=57 — впадины)
    final receiptPath = Path();
    final left = 16.0 * s;
    final right = 48.0 * s;
    final top = 14.0 * s;
    final bottomBase = 53.0 * s; // верхняя линия зубцов
    final notchDepth = 4.0 * s;

    receiptPath.moveTo(left, top);
    receiptPath.lineTo(right, top);
    receiptPath.lineTo(right, bottomBase);

    // 5 V-notches справа налево. Ширина бортика 32 / 5 = 6.4
    const notches = 5;
    final notchWidth = (32.0 / notches) * s;
    for (int i = 0; i < notches; i++) {
      final xPeak = right - (i * notchWidth) - (notchWidth / 2);
      final xEnd = right - ((i + 1) * notchWidth);
      receiptPath.lineTo(xPeak, bottomBase + notchDepth);
      receiptPath.lineTo(xEnd, bottomBase);
    }
    receiptPath.lineTo(left, top);
    receiptPath.close();

    // Fill (белый)
    final fillPaint = Paint()
      ..color = _receiptFillColor
      ..style = PaintingStyle.fill;
    canvas.drawPath(receiptPath, fillPaint);

    // Stroke (зелёный/белый)
    final strokePaint = Paint()
      ..color = _receiptStrokeColor
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3.0 * s
      ..strokeJoin = StrokeJoin.round
      ..strokeCap = StrokeCap.round;
    canvas.drawPath(receiptPath, strokePaint);

    // ---- Receipt lines (3 строки) — 45% opacity ----
    final lineColor = _receiptStrokeColor.withValues(alpha: 0.45);
    final linePaint = Paint()
      ..color = lineColor
      ..style = PaintingStyle.fill;
    // Lengths: 20 / 14 / 17, x-start примерно слева внутри receipt (от 22)
    final lineY = [30.0, 37.0, 44.0];
    final lineLens = [20.0, 14.0, 17.0];
    final lineXStart = 22.0;
    for (int i = 0; i < 3; i++) {
      final rect = Rect.fromLTWH(
        lineXStart * s,
        (lineY[i] - 1.2) * s,
        lineLens[i] * s,
        2.4 * s,
      );
      canvas.drawRRect(
        RRect.fromRectAndRadius(rect, Radius.circular(1.2 * s)),
        linePaint,
      );
    }

    // ---- Stamp disc (синий, r=11 в (32, 14)) ----
    final stampPaint = Paint()
      ..color = _stampColor
      ..style = PaintingStyle.fill;
    canvas.drawCircle(Offset(32.0 * s, 14.0 * s), 11.0 * s, stampPaint);

    // ---- Cross (белый, 14×3 + 3×14, центр в (32, 14)) ----
    final crossPaint = Paint()
      ..color = _crossColor
      ..style = PaintingStyle.fill;
    // Вертикальная палка
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromCenter(
          center: Offset(32.0 * s, 14.0 * s),
          width: 3.0 * s,
          height: 14.0 * s,
        ),
        Radius.circular(0.8 * s),
      ),
      crossPaint,
    );
    // Горизонтальная палка
    canvas.drawRRect(
      RRect.fromRectAndRadius(
        Rect.fromCenter(
          center: Offset(32.0 * s, 14.0 * s),
          width: 14.0 * s,
          height: 3.0 * s,
        ),
        Radius.circular(0.8 * s),
      ),
      crossPaint,
    );
  }

  @override
  bool shouldRepaint(_ReceiptStampPainter oldDelegate) =>
      oldDelegate.onSurface != onSurface;
}
