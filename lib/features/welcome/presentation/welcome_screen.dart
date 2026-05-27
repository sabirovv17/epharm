import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_gradients.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_shadows.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/widgets/pharma_logo.dart';

class WelcomeScreen extends StatefulWidget {
  const WelcomeScreen({super.key});

  @override
  State<WelcomeScreen> createState() => _WelcomeScreenState();
}

class _WelcomeScreenState extends State<WelcomeScreen> {
  int _idx = 0;

  static const _slides = <_Slide>[
    _Slide(
      title: 'Зарабатывайте на каждой покупке',
      body: 'Возвращайте часть стоимости за лекарства из акционного списка',
      cta: 'Далее',
      green: false,
    ),
    _Slide(
      title: 'Простой способ дополнительного дохода',
      body: 'Купите лекарство из акции, отправьте чек и получите бонус на карту',
      cta: 'Далее',
      green: false,
    ),
    _Slide(
      title: 'От 500 до 1500 тенге за упаковку',
      body:
          'Фиксированные бонусы, процент от суммы покупки, повышенные ставки и накопительные акции',
      cta: 'Начать',
      green: true,
    ),
  ];

  void _next() {
    if (_idx < _slides.length - 1) {
      setState(() => _idx += 1);
    } else {
      context.go('/home');
    }
  }

  @override
  Widget build(BuildContext context) {
    final s = _slides[_idx];

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: systemUiOverlayStyleDark,
      child: Scaffold(
        body: Container(
          decoration: const BoxDecoration(gradient: AppGradients.welcome),
          child: SafeArea(
            child: Column(
              children: [
                const SizedBox(height: AppSpacing.s8),
                const Padding(
                  padding: EdgeInsets.symmetric(horizontal: AppSpacing.s24),
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: PharmaWordmark(),
                  ),
                ),
                const SizedBox(height: AppSpacing.s16),
                Expanded(
                  child: _PhonePreviewStage(slideIdx: _idx),
                ),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: AppSpacing.s24),
                  child: Column(
                    children: [
                      Text(
                        s.title,
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          fontFamily: 'Manrope',
                         fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 26,
                          fontWeight: FontWeight.w800,
                          color: Colors.white,
                          height: 1.1,
                        ),
                      ),
                      const SizedBox(height: 12),
                      Text(
                        s.body,
                        textAlign: TextAlign.center,
                        style: TextStyle(
                          fontFamily: 'Manrope',
                         fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 15,
                          fontWeight: FontWeight.w700,
                          color: Colors.white.withValues(alpha: 0.95),
                          height: 1.3,
                        ),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: AppSpacing.s16),
                // Page dots
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: List.generate(_slides.length, (i) {
                    final active = i == _idx;
                    return AnimatedContainer(
                      duration: const Duration(milliseconds: 220),
                      margin: const EdgeInsets.symmetric(horizontal: 4),
                      height: 6,
                      width: active ? 24 : 6,
                      decoration: BoxDecoration(
                        color: active ? Colors.white : Colors.white.withValues(alpha: 0.4),
                        borderRadius: BorderRadius.circular(3),
                      ),
                    );
                  }),
                ),
                const SizedBox(height: AppSpacing.s16),
                Padding(
                  padding: const EdgeInsets.symmetric(horizontal: AppSpacing.s20),
                  child: _CtaButton(label: s.cta, green: s.green, onTap: _next),
                ),
                const SizedBox(height: AppSpacing.s32),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Slide {
  const _Slide({
    required this.title,
    required this.body,
    required this.cta,
    required this.green,
  });
  final String title;
  final String body;
  final String cta;
  final bool green;
}

/// 3 peeking phone мокапы: левый и правый чуть наклонены/прозрачные, центральный полный.
class _PhonePreviewStage extends StatelessWidget {
  const _PhonePreviewStage({required this.slideIdx});
  final int slideIdx;

  int _wrap(int i, int n) => (i + n) % n;

  @override
  Widget build(BuildContext context) {
    final left = _wrap(slideIdx - 1, 3);
    final center = slideIdx;
    final right = _wrap(slideIdx + 1, 3);

    return LayoutBuilder(builder: (context, c) {
      const phoneW = 220.0;
      const phoneH = 440.0;
      const sideW = 180.0;
      const sideH = 400.0;
      return Stack(
        alignment: Alignment.center,
        clipBehavior: Clip.none,
        children: [
          Positioned(
            left: -40,
            top: 24,
            child: Opacity(
              opacity: 0.7,
              child: Transform.scale(
                scale: 0.95,
                child: _PhoneMockShell(
                  width: sideW,
                  height: sideH,
                  child: _PreviewByIndex(idx: left),
                ),
              ),
            ),
          ),
          Positioned(
            right: -40,
            top: 24,
            child: Opacity(
              opacity: 0.7,
              child: Transform.scale(
                scale: 0.95,
                child: _PhoneMockShell(
                  width: sideW,
                  height: sideH,
                  child: _PreviewByIndex(idx: right),
                ),
              ),
            ),
          ),
          _PhoneMockShell(
            width: phoneW,
            height: phoneH,
            ring: true,
            child: _PreviewByIndex(idx: center),
          ),
        ],
      );
    });
  }
}

class _PhoneMockShell extends StatelessWidget {
  const _PhoneMockShell({
    required this.width,
    required this.height,
    required this.child,
    this.ring = false,
  });

  final double width;
  final double height;
  final Widget child;
  final bool ring;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: width,
      height: height,
      clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(ring ? 40 : 36),
        boxShadow: const [
          BoxShadow(
            color: Color(0x55000000),
            offset: Offset(0, 14),
            blurRadius: 32,
          ),
        ],
        border: ring
            ? Border.all(color: Colors.white.withValues(alpha: 0.4), width: 4)
            : null,
      ),
      child: child,
    );
  }
}

class _PreviewByIndex extends StatelessWidget {
  const _PreviewByIndex({required this.idx});
  final int idx;

  @override
  Widget build(BuildContext context) {
    return switch (idx) {
      0 => const _PreviewHome(),
      1 => const _PreviewCamera(),
      _ => const _PreviewSuccess(),
    };
  }
}

class _PreviewHome extends StatelessWidget {
  const _PreviewHome();

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(gradient: AppGradients.header),
      padding: const EdgeInsets.all(10),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const PharmaWordmark(size: PharmaWordmarkSize.sm),
          const SizedBox(height: 8),
          Container(
            padding: const EdgeInsets.all(8),
            decoration: BoxDecoration(
              color: AppColors.brandGreen700.withValues(alpha: 0.6),
              borderRadius: BorderRadius.circular(10),
            ),
            child: const Row(
              children: [
                Expanded(
                  child: Text(
                    'Epharm\nБаланс',
                    style: TextStyle(
                      fontFamily: 'Manrope',
                     fontFamilyFallback: ['Roboto', 'sans-serif'],
                      fontSize: 9,
                      color: Colors.white,
                      height: 1.1,
                    ),
                  ),
                ),
                Text(
                  '16 000 ₸',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                   fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 11,
                    fontWeight: FontWeight.w800,
                    color: Colors.white,
                  ),
                ),
              ],
            ),
          ),
          const SizedBox(height: 4),
          Row(
            children: [
              Expanded(child: _MiniPill(label: 'История')),
              const SizedBox(width: 4),
              Expanded(child: _MiniPill(label: 'Загрузить чек')),
            ],
          ),
          const SizedBox(height: 6),
          Expanded(
            child: Container(
              padding: const EdgeInsets.all(4),
              decoration: const BoxDecoration(
                color: Colors.white,
                borderRadius: BorderRadius.vertical(
                  top: Radius.circular(8),
                ),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Row(
                    mainAxisAlignment: MainAxisAlignment.spaceBetween,
                    children: [
                      Text('Акции',
                          style: TextStyle(
                              fontFamily: 'Manrope',
                             fontFamilyFallback: ['Roboto', 'sans-serif'],
                              fontSize: 8,
                              fontWeight: FontWeight.w800,
                              color: AppColors.ink900)),
                      Text('Все',
                          style: TextStyle(
                              fontFamily: 'Manrope',
                             fontFamilyFallback: ['Roboto', 'sans-serif'],
                              fontSize: 8,
                              fontWeight: FontWeight.w800,
                              color: AppColors.brandGreen600)),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Container(
                    height: 18,
                    decoration: BoxDecoration(
                      color: AppColors.paperInput,
                      borderRadius: BorderRadius.circular(4),
                    ),
                  ),
                  const SizedBox(height: 4),
                  Row(
                    children: [
                      _MiniChip(label: '↕'),
                      const SizedBox(width: 3),
                      _MiniChip(label: 'Бренд'),
                      const SizedBox(width: 3),
                      _MiniChip(label: 'Конкурсные', active: true),
                    ],
                  ),
                  const SizedBox(height: 4),
                  Expanded(
                    child: Container(
                      decoration: BoxDecoration(
                        color: AppColors.paperInput,
                        borderRadius: BorderRadius.circular(4),
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _MiniPill extends StatelessWidget {
  const _MiniPill({required this.label});
  final String label;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 18,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: const Color(0x29FFFFFF),
        borderRadius: BorderRadius.circular(9),
      ),
      child: Text(
        label,
        style: const TextStyle(
          fontFamily: 'Manrope',
         fontFamilyFallback: ['Roboto', 'sans-serif'],
          fontSize: 9,
          color: Colors.white,
        ),
      ),
    );
  }
}

class _MiniChip extends StatelessWidget {
  const _MiniChip({required this.label, this.active = false});
  final String label;
  final bool active;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 6, vertical: 2),
      decoration: BoxDecoration(
        color: active ? AppColors.brandGreen600 : AppColors.paperInput,
        borderRadius: BorderRadius.circular(6),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontFamily: 'Manrope',
         fontFamilyFallback: ['Roboto', 'sans-serif'],
          fontSize: 8,
          fontWeight: FontWeight.w800,
          color: active ? Colors.white : AppColors.ink900,
        ),
      ),
    );
  }
}

class _PreviewCamera extends StatelessWidget {
  const _PreviewCamera();

  @override
  Widget build(BuildContext context) {
    return Container(
      color: Colors.white,
      child: Column(
        children: [
          const Padding(
            padding: EdgeInsets.symmetric(vertical: 4),
            child: Text(
              '‹ Медиатека',
              style: TextStyle(
                fontFamily: 'Manrope',
               fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 10,
                color: AppColors.ink700,
              ),
            ),
          ),
          Expanded(
            child: Container(
              decoration: const BoxDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topCenter,
                  end: Alignment.bottomCenter,
                  colors: [Color(0xFFD6D3D1), Color(0xFFA8A29E)],
                ),
              ),
              child: Center(
                child: Container(
                  width: 80,
                  height: 80,
                  color: Colors.black.withValues(alpha: 0.8),
                  padding: const EdgeInsets.all(6),
                  child: Container(color: Colors.white),
                ),
              ),
            ),
          ),
          Container(
            height: 50,
            color: Colors.black.withValues(alpha: 0.8),
            alignment: Alignment.center,
            child: Container(
              width: 32,
              height: 32,
              decoration: const BoxDecoration(
                color: Colors.white,
                shape: BoxShape.circle,
              ),
            ),
          ),
        ],
      ),
    );
  }
}

class _PreviewSuccess extends StatelessWidget {
  const _PreviewSuccess();

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: const BoxDecoration(gradient: AppGradients.header),
      padding: const EdgeInsets.all(10),
      child: Column(
        mainAxisAlignment: MainAxisAlignment.center,
        children: [
          Container(
            width: 60,
            height: 60,
            decoration: BoxDecoration(
              color: AppColors.brandBlue600,
              shape: BoxShape.circle,
              boxShadow: AppShadows.elevated,
            ),
            child: const Icon(Icons.check, color: Colors.white, size: 32),
          ),
          const SizedBox(height: 12),
          Container(
            width: double.infinity,
            padding: const EdgeInsets.all(10),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(10),
            ),
            child: const Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  'Чек успешно отправлен',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                   fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 11,
                    fontWeight: FontWeight.w800,
                    color: AppColors.ink900,
                  ),
                ),
                SizedBox(height: 4),
                Text(
                  'Бонусы легко отследить в разделе «Истории и статусы».',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                   fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 8,
                    color: AppColors.ink500,
                    height: 1.2,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _CtaButton extends StatelessWidget {
  const _CtaButton({
    required this.label,
    required this.green,
    required this.onTap,
  });

  final String label;
  final bool green;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final bg = green ? AppColors.brandBlue600 : Colors.white;
    final fg = green ? Colors.white : AppColors.brandGreen600;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: AppRadii.brFull,
        child: Container(
          width: double.infinity,
          height: 60,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: bg,
            borderRadius: AppRadii.brFull,
            boxShadow: green ? AppShadows.fab : AppShadows.card,
          ),
          child: Text(
            label,
            style: TextStyle(
              fontFamily: 'Manrope',
             fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 18,
              fontWeight: FontWeight.w800,
              color: fg,
            ),
          ),
        ),
      ),
    );
  }
}
