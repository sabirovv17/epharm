import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_gradients.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/widgets/pharma_logo.dart';
import 'profile_page_scaffold.dart';

/// 5-шаговый туториал «Как пользоваться приложением». Карусель с peeking
/// phone-mock-картинками и нижним gradient pager-card.
///
/// См. `_reference/profile-pages/instruction.jsx`.
class InstructionScreen extends StatefulWidget {
  const InstructionScreen({super.key});

  @override
  State<InstructionScreen> createState() => _InstructionScreenState();
}

class _InstructionScreenState extends State<InstructionScreen> {
  int _idx = 0;

  static const _steps = <_Step>[
    _Step(
      n: 1,
      text:
          'Нажмите на кнопку Загрузить чек вверху экрана или на кнопку фотоаппарата внизу экрана',
      kind: _PreviewKind.home,
    ),
    _Step(
      n: 2,
      text: 'Войдите, введя номер телефона и смс-код',
      kind: _PreviewKind.auth,
    ),
    _Step(
      n: 3,
      text: 'Прикрепите или сфотографируйте чек',
      kind: _PreviewKind.upload,
    ),
    _Step(
      n: 4,
      text:
          'Определим акции из чека. Если нужной нет — добавьте. Введите карту, адрес аптеки и нажмите Отправить',
      kind: _PreviewKind.review,
    ),
    _Step(
      n: 5,
      text: 'Отслеживайте статусы в разделе История',
      kind: _PreviewKind.history,
    ),
  ];

  @override
  Widget build(BuildContext context) {
    final step = _steps[_idx];
    final isLast = _idx == _steps.length - 1;

    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: systemUiOverlayStyleLight,
      child: Scaffold(
        backgroundColor: profilePageCanvas,
        body: SafeArea(
          bottom: false,
          child: Column(
            children: [
              // Header — small back chevron + centered medium title.
              SizedBox(
                height: 48,
                child: Stack(
                  alignment: Alignment.center,
                  children: [
                    Align(
                      alignment: Alignment.centerLeft,
                      child: IconButton(
                        onPressed: () {
                          if (context.canPop()) {
                            context.pop();
                          } else {
                            context.go('/home');
                          }
                        },
                        icon: const Icon(
                          Icons.arrow_back_ios_new_rounded,
                          size: 22,
                          color: AppColors.ink900,
                        ),
                      ),
                    ),
                    const Text(
                      'Подробная инструкция',
                      style: TextStyle(
                        fontFamily: 'Manrope',
                        fontFamilyFallback: ['Roboto', 'sans-serif'],
                        fontSize: 17,
                        fontWeight: FontWeight.w800,
                        color: AppColors.ink900,
                      ),
                    ),
                  ],
                ),
              ),
              // Carousel area.
              Expanded(
                child: LayoutBuilder(
                  builder: (context, c) {
                    return Stack(
                      alignment: Alignment.center,
                      clipBehavior: Clip.none,
                      children: [
                        if (_idx > 0)
                          Positioned(
                            left: -80,
                            child: Opacity(
                              opacity: 0.4,
                              child: _PhonePreview(kind: _steps[_idx - 1].kind),
                            ),
                          ),
                        if (!isLast)
                          Positioned(
                            right: -80,
                            child: Opacity(
                              opacity: 0.4,
                              child: _PhonePreview(kind: _steps[_idx + 1].kind),
                            ),
                          ),
                        _PhonePreview(kind: step.kind),
                      ],
                    );
                  },
                ),
              ),
              // Bottom pager card.
              Padding(
                padding: const EdgeInsets.fromLTRB(16, 0, 16, 24),
                child: Container(
                  padding: const EdgeInsets.all(24),
                  decoration: BoxDecoration(
                    gradient: const LinearGradient(
                      begin: Alignment.topLeft,
                      end: Alignment.bottomRight,
                      colors: [
                        AppColors.brandGreen400, // E0916B
                        AppColors.brandGreen600, // D97757 — PRIMARY
                        AppColors.brandGreen700, // BE5A38
                      ],
                      stops: [0.0, 0.55, 1.0],
                    ),
                    borderRadius: BorderRadius.circular(24),
                    boxShadow: const [
                      BoxShadow(
                        color: Color(0x47D97757), // rgba(217,119,87,0.28) — коралловый glow
                        offset: Offset(0, 12),
                        blurRadius: 28,
                      ),
                    ],
                  ),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(
                        'Шаг ${step.n}.',
                        style: const TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 22,
                          fontWeight: FontWeight.w800,
                          color: Colors.white,
                          height: 1.15,
                        ),
                      ),
                      const SizedBox(height: 12),
                      SizedBox(
                        height: 64,
                        child: Text(
                          step.text,
                          style: TextStyle(
                            fontFamily: 'Manrope',
                            fontFamilyFallback: const ['Roboto', 'sans-serif'],
                            fontSize: 14,
                            fontWeight: FontWeight.w700,
                            color: Colors.white.withValues(alpha: 0.95),
                            height: 1.4,
                          ),
                        ),
                      ),
                      const SizedBox(height: 20),
                      Row(
                        children: [
                          Expanded(
                            child: _PagerButton(
                              label: 'Назад',
                              enabled: _idx > 0,
                              filled: false,
                              onTap: () => setState(() => _idx -= 1),
                            ),
                          ),
                          const SizedBox(width: 12),
                          Expanded(
                            child: _PagerButton(
                              label: isLast ? 'Готово' : 'Далее',
                              enabled: true,
                              filled: true,
                              onTap: () {
                                if (isLast) {
                                  if (context.canPop()) {
                                    context.pop();
                                  } else {
                                    context.go('/home');
                                  }
                                } else {
                                  setState(() => _idx += 1);
                                }
                              },
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 16),
                      // Step dots.
                      Row(
                        mainAxisAlignment: MainAxisAlignment.center,
                        children: [
                          for (var i = 0; i < _steps.length; i++) ...[
                            AnimatedContainer(
                              duration: const Duration(milliseconds: 220),
                              width: i == _idx ? 18 : 6,
                              height: 6,
                              decoration: BoxDecoration(
                                color: i == _idx
                                    ? Colors.white
                                    : Colors.white.withValues(alpha: 0.45),
                                borderRadius: BorderRadius.circular(3),
                              ),
                            ),
                            if (i < _steps.length - 1)
                              const SizedBox(width: 6),
                          ],
                        ],
                      ),
                    ],
                  ),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Step {
  const _Step({required this.n, required this.text, required this.kind});
  final int n;
  final String text;
  final _PreviewKind kind;
}

enum _PreviewKind { home, auth, upload, review, history }

class _PagerButton extends StatelessWidget {
  const _PagerButton({
    required this.label,
    required this.enabled,
    required this.filled,
    required this.onTap,
  });

  final String label;
  final bool enabled;
  final bool filled;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final bg = filled
        ? Colors.white
        : (enabled ? Colors.white.withValues(alpha: 0.1) : Colors.transparent);
    final fg = filled ? AppColors.brandBlue600 : Colors.white;
    return Opacity(
      opacity: enabled ? 1 : 0.4,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: enabled ? onTap : null,
          borderRadius: BorderRadius.circular(16),
          child: Container(
            height: 48,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: bg,
              borderRadius: BorderRadius.circular(16),
              border: filled
                  ? null
                  : Border.all(color: Colors.white.withValues(alpha: 0.3)),
            ),
            child: Text(
              label,
              style: TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: const ['Roboto', 'sans-serif'],
                fontSize: 15,
                fontWeight: filled ? FontWeight.w800 : FontWeight.w800,
                color: fg,
              ),
            ),
          ),
        ),
      ),
    );
  }
}

/// Стилизованный мини-телефон 200×400 с примитивным мокапом экрана внутри.
class _PhonePreview extends StatelessWidget {
  const _PhonePreview({required this.kind});
  final _PreviewKind kind;

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 200,
      height: 400,
      padding: const EdgeInsets.all(5),
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Color(0xFFF6F3EE), Color(0xFFE2DCD2)],
        ),
        borderRadius: BorderRadius.circular(28),
        boxShadow: const [
          BoxShadow(
            color: Color(0x2E221C16),
            offset: Offset(0, 10),
            blurRadius: 30,
          ),
        ],
      ),
      child: ClipRRect(
        borderRadius: BorderRadius.circular(24),
        child: Stack(
          children: [
            Container(color: Colors.white),
            // Notch.
            Positioned(
              top: 6,
              left: 0,
              right: 0,
              child: Center(
                child: Container(
                  width: 48,
                  height: 12,
                  decoration: BoxDecoration(
                    color: Colors.black,
                    borderRadius: BorderRadius.circular(99),
                  ),
                ),
              ),
            ),
            // Screen content per kind.
            _MiniContent(kind: kind),
          ],
        ),
      ),
    );
  }
}

class _MiniContent extends StatelessWidget {
  const _MiniContent({required this.kind});
  final _PreviewKind kind;

  @override
  Widget build(BuildContext context) {
    // Упрощённые мокапы для каждого экрана. Стилистически следуем за
    // _reference/profile-pages/instruction.jsx (PreviewHome / Auth / Upload / ...).
    switch (kind) {
      case _PreviewKind.home:
        return Column(
          children: [
            // Коралловый header c карточкой баланса — 1:1 с реальным экраном:
            // знак-чек + «Epharm / Баланс» + сумма + пилюли История/Загрузить чек.
            Container(
              padding: const EdgeInsets.fromLTRB(10, 26, 10, 12),
              decoration: const BoxDecoration(
                gradient: AppGradients.header,
                borderRadius: BorderRadius.only(
                  bottomLeft: Radius.circular(16),
                  bottomRight: Radius.circular(16),
                ),
              ),
              child: Container(
                padding: const EdgeInsets.all(7),
                decoration: BoxDecoration(
                  color: Colors.white.withValues(alpha: 0.14),
                  borderRadius: BorderRadius.circular(12),
                  border: Border.all(
                    color: Colors.white.withValues(alpha: 0.18),
                  ),
                ),
                child: Column(
                  children: [
                    Row(
                      children: [
                        const PharmaLogo(size: 18),
                        const SizedBox(width: 5),
                        const Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              Text('Epharm', style: _miniWhite8),
                              Text('Баланс', style: _miniWhite8),
                            ],
                          ),
                        ),
                        const Text('3 500 ₸', style: _miniBalance),
                      ],
                    ),
                    const SizedBox(height: 6),
                    Row(
                      children: [
                        Expanded(child: _miniPill('История')),
                        const SizedBox(width: 5),
                        Expanded(child: _miniPill('Загрузить чек')),
                      ],
                    ),
                  ],
                ),
              ),
            ),
            // Канвас: поиск + чипы-фильтры + карточка товара с бонусом.
            Expanded(
              child: Container(
                color: AppColors.paperCanvas,
                padding: const EdgeInsets.fromLTRB(8, 8, 8, 0),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Container(
                      height: 18,
                      alignment: Alignment.centerLeft,
                      padding: const EdgeInsets.symmetric(horizontal: 6),
                      decoration: BoxDecoration(
                        color: Colors.white,
                        borderRadius: BorderRadius.circular(99),
                      ),
                      child: const Text('Поиск', style: _miniHint),
                    ),
                    const SizedBox(height: 6),
                    Row(
                      children: [
                        _miniChip('Бренд'),
                        const SizedBox(width: 4),
                        _miniChip('Категории'),
                      ],
                    ),
                    const SizedBox(height: 6),
                    Expanded(
                      child: Container(
                        decoration: BoxDecoration(
                          color: Colors.white,
                          borderRadius: BorderRadius.circular(8),
                        ),
                        child: Column(
                          crossAxisAlignment: CrossAxisAlignment.stretch,
                          children: [
                            Expanded(
                              child: Container(
                                margin: const EdgeInsets.all(6),
                                decoration: BoxDecoration(
                                  color: AppColors.paperInput,
                                  borderRadius: BorderRadius.circular(6),
                                ),
                              ),
                            ),
                            const Padding(
                              padding: EdgeInsets.symmetric(horizontal: 6),
                              child: Text('Ivatherm Cicaderm',
                                  style: _miniName),
                            ),
                            const SizedBox(height: 5),
                            Container(
                              margin: const EdgeInsets.all(6),
                              height: 16,
                              alignment: Alignment.center,
                              decoration: BoxDecoration(
                                color: AppColors.brandGreen600,
                                borderRadius: BorderRadius.circular(6),
                              ),
                              child: const Text('Бонус 2 200 ₸',
                                  style: _miniWhite8),
                            ),
                          ],
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            // Нижняя навигация с центральной кнопкой-камерой.
            Container(
              height: 26,
              color: Colors.white,
              child: const Row(
                mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                children: [
                  Icon(Icons.home_rounded,
                      size: 13, color: AppColors.brandGreen600),
                  _MiniCameraButton(),
                  Icon(Icons.person_outline_rounded,
                      size: 13, color: AppColors.ink400),
                ],
              ),
            ),
          ],
        );
      case _PreviewKind.auth:
        return Container(
          decoration: const BoxDecoration(gradient: AppGradients.welcome),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(14, 30, 14, 16),
            child: Column(
              children: [
                const SizedBox(height: 6),
                Container(
                  padding: const EdgeInsets.all(8),
                  decoration: BoxDecoration(
                    color: Colors.white.withValues(alpha: 0.16),
                    shape: BoxShape.circle,
                  ),
                  child: const PharmaLogo(size: 30),
                ),
                const SizedBox(height: 10),
                const Text('Добро пожаловать!', style: _miniWhite11),
                const SizedBox(height: 3),
                const Text('Войдите по номеру телефона', style: _miniWhite7),
                const Spacer(),
                Container(
                  height: 22,
                  alignment: Alignment.centerLeft,
                  padding: const EdgeInsets.symmetric(horizontal: 8),
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: const Text('+7 ___ ___ __ __', style: _miniHint),
                ),
                const SizedBox(height: 6),
                Container(
                  height: 22,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: Colors.white,
                    borderRadius: BorderRadius.circular(8),
                  ),
                  child: const Text('Войти', style: _miniCoral8),
                ),
                const SizedBox(height: 10),
              ],
            ),
          ),
        );
      case _PreviewKind.upload:
        return Container(
          color: AppColors.paperCanvas,
          padding: const EdgeInsets.fromLTRB(8, 28, 8, 12),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Center(child: Text('Загрузить чек', style: _miniTitle9)),
              const SizedBox(height: 8),
              Expanded(
                child: Container(
                  decoration: BoxDecoration(
                    color: AppColors.brandGreen100,
                    borderRadius: BorderRadius.circular(10),
                    border: Border.all(
                      color: AppColors.brandGreen400,
                      width: 1.2,
                    ),
                  ),
                  child: const Center(
                    child: Icon(Icons.receipt_long_rounded,
                        size: 34, color: AppColors.brandGreen600),
                  ),
                ),
              ),
              const SizedBox(height: 8),
              Row(
                children: [
                  Expanded(child: _miniSolidBtn('Камера', filled: true)),
                  const SizedBox(width: 6),
                  Expanded(child: _miniSolidBtn('Галерея', filled: false)),
                ],
              ),
            ],
          ),
        );
      case _PreviewKind.review:
        return Padding(
          padding: const EdgeInsets.fromLTRB(8, 28, 8, 8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Container(
                padding: const EdgeInsets.all(6),
                decoration: BoxDecoration(
                  color: AppColors.brandGreen100,
                  borderRadius: BorderRadius.circular(6),
                ),
                child: const Row(
                  children: [
                    Icon(Icons.check_circle,
                        size: 10, color: AppColors.brandGreen600),
                    SizedBox(width: 4),
                    Text(
                      'Информация принята',
                      style: TextStyle(
                        fontFamily: 'Manrope',
                        fontFamilyFallback: ['Roboto', 'sans-serif'],
                        fontSize: 8,
                        fontWeight: FontWeight.w800,
                        color: AppColors.ink900,
                      ),
                    ),
                  ],
                ),
              ),
              const SizedBox(height: 6),
              for (final t in const [
                '+ Добавить акцию',
                '✎ Адрес аптеки',
                '+ Номер карты',
              ])
                Padding(
                  padding: const EdgeInsets.only(bottom: 4),
                  child: Container(
                    padding:
                        const EdgeInsets.symmetric(horizontal: 6, vertical: 4),
                    decoration: BoxDecoration(
                      color: Colors.white,
                      border:
                          Border.all(color: AppColors.brandBlue100, width: 1),
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: Text(
                      t,
                      style: const TextStyle(
                        fontFamily: 'Manrope',
                        fontFamilyFallback: ['Roboto', 'sans-serif'],
                        fontSize: 8,
                        fontWeight: FontWeight.w800,
                        color: AppColors.brandBlue600,
                      ),
                    ),
                  ),
                ),
              const Spacer(),
              Container(
                height: 22,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: AppColors.brandBlue600,
                  borderRadius: BorderRadius.circular(99),
                ),
                child: const Text(
                  'Отправить',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 9,
                    fontWeight: FontWeight.w800,
                    color: Colors.white,
                  ),
                ),
              ),
            ],
          ),
        );
      case _PreviewKind.history:
        return Padding(
          padding: const EdgeInsets.fromLTRB(8, 28, 8, 8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const Center(
                child: Text(
                  'История',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 10,
                    fontWeight: FontWeight.w800,
                    color: AppColors.ink900,
                  ),
                ),
              ),
              const SizedBox(height: 8),
              for (final row in const [
                ('Принято', '160 ₸'),
                ('Принято', '180 ₸'),
                ('В обработке', '230 ₸'),
                ('Принято', '90 ₸'),
              ])
                Padding(
                  padding: const EdgeInsets.only(bottom: 4),
                  child: Container(
                    padding: const EdgeInsets.all(6),
                    decoration: BoxDecoration(
                      color: Colors.white,
                      borderRadius: BorderRadius.circular(6),
                    ),
                    child: Row(
                      children: [
                        const Expanded(
                          child: Text(
                            'Чек №12345',
                            style: TextStyle(
                              fontFamily: 'Manrope',
                              fontFamilyFallback: ['Roboto', 'sans-serif'],
                              fontSize: 7,
                              fontWeight: FontWeight.w800,
                              color: AppColors.ink900,
                            ),
                          ),
                        ),
                        Container(
                          padding: const EdgeInsets.symmetric(
                              horizontal: 4, vertical: 1),
                          decoration: BoxDecoration(
                            color: AppColors.brandGreen100,
                            borderRadius: BorderRadius.circular(99),
                          ),
                          child: Text(
                            row.$1,
                            style: const TextStyle(
                              fontFamily: 'Manrope',
                              fontFamilyFallback: ['Roboto', 'sans-serif'],
                              fontSize: 6,
                              fontWeight: FontWeight.w800,
                              color: AppColors.brandGreen600,
                            ),
                          ),
                        ),
                        const SizedBox(width: 4),
                        Text(
                          row.$2,
                          style: const TextStyle(
                            fontFamily: 'Manrope',
                            fontFamilyFallback: ['Roboto', 'sans-serif'],
                            fontSize: 7,
                            fontWeight: FontWeight.w800,
                            color: AppColors.ink900,
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
}

// ── Хелперы мини-превью (token-based, под палитру приложения) ────────────────

/// Центральная кнопка-камера в нижней навигации превью «Главная».
class _MiniCameraButton extends StatelessWidget {
  const _MiniCameraButton();

  @override
  Widget build(BuildContext context) {
    return Container(
      width: 18,
      height: 18,
      decoration: const BoxDecoration(
        color: AppColors.brandGreen600,
        shape: BoxShape.circle,
      ),
      child: const Icon(Icons.photo_camera_rounded,
          size: 10, color: Colors.white),
    );
  }
}

const _miniWhite11 = TextStyle(
  fontFamily: 'Manrope',
  fontFamilyFallback: ['Roboto', 'sans-serif'],
  fontSize: 11,
  fontWeight: FontWeight.w800,
  color: Colors.white,
);
const _miniWhite8 = TextStyle(
  fontFamily: 'Manrope',
  fontFamilyFallback: ['Roboto', 'sans-serif'],
  fontSize: 8,
  fontWeight: FontWeight.w800,
  color: Colors.white,
  height: 1.15,
);
const _miniWhite7 = TextStyle(
  fontFamily: 'Manrope',
  fontFamilyFallback: ['Roboto', 'sans-serif'],
  fontSize: 7,
  fontWeight: FontWeight.w700,
  color: Colors.white70,
);
const _miniBalance = TextStyle(
  fontFamily: 'Manrope',
  fontFamilyFallback: ['Roboto', 'sans-serif'],
  fontSize: 12,
  fontWeight: FontWeight.w800,
  color: Colors.white,
  fontFeatures: [FontFeature.tabularFigures()],
);
const _miniHint = TextStyle(
  fontFamily: 'Manrope',
  fontFamilyFallback: ['Roboto', 'sans-serif'],
  fontSize: 8,
  fontWeight: FontWeight.w700,
  color: AppColors.ink400,
);
const _miniName = TextStyle(
  fontFamily: 'Manrope',
  fontFamilyFallback: ['Roboto', 'sans-serif'],
  fontSize: 8,
  fontWeight: FontWeight.w800,
  color: AppColors.ink900,
);
const _miniCoral8 = TextStyle(
  fontFamily: 'Manrope',
  fontFamilyFallback: ['Roboto', 'sans-serif'],
  fontSize: 8,
  fontWeight: FontWeight.w800,
  color: AppColors.brandGreen600,
);
const _miniTitle9 = TextStyle(
  fontFamily: 'Manrope',
  fontFamilyFallback: ['Roboto', 'sans-serif'],
  fontSize: 9,
  fontWeight: FontWeight.w800,
  color: AppColors.ink900,
);

/// Полупрозрачная пилюля на коралловом header (История / Загрузить чек).
Widget _miniPill(String label) => Container(
      height: 16,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: Colors.white.withValues(alpha: 0.16),
        borderRadius: BorderRadius.circular(99),
        border: Border.all(color: Colors.white.withValues(alpha: 0.22)),
      ),
      child: Text(
        label,
        maxLines: 1,
        overflow: TextOverflow.ellipsis,
        style: const TextStyle(
          fontFamily: 'Manrope',
          fontFamilyFallback: ['Roboto', 'sans-serif'],
          fontSize: 7,
          fontWeight: FontWeight.w800,
          color: Colors.white,
        ),
      ),
    );

/// Белый чип-фильтр на канвасе (Бренд ⌄ / Категории ⌄).
Widget _miniChip(String label) => Container(
      padding: const EdgeInsets.fromLTRB(6, 3, 4, 3),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(99),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Text(
            label,
            style: const TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 7,
              fontWeight: FontWeight.w800,
              color: AppColors.ink700,
            ),
          ),
          const Icon(Icons.keyboard_arrow_down_rounded,
              size: 9, color: AppColors.ink400),
        ],
      ),
    );

/// Кнопка в превью загрузки (filled — коралл-заливка, иначе — обводка).
Widget _miniSolidBtn(String label, {required bool filled}) => Container(
      height: 18,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: filled ? AppColors.brandGreen600 : Colors.white,
        borderRadius: BorderRadius.circular(99),
        border:
            filled ? null : Border.all(color: AppColors.brandGreen400, width: 1),
      ),
      child: Text(
        label,
        style: TextStyle(
          fontFamily: 'Manrope',
          fontFamilyFallback: const ['Roboto', 'sans-serif'],
          fontSize: 8,
          fontWeight: FontWeight.w800,
          color: filled ? Colors.white : AppColors.brandGreen600,
        ),
      ),
    );
