import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_shadows.dart';
import '../../../core/theme/app_spacing.dart';
import '../application/receipts_controller.dart';

/// Bonus-card capture sheet. Превью карты + 3×4 цифровая клавиатура.
/// Активирует «Привязать карту» при 16 цифрах.
///
/// Источник: `_reference/recipe/review.jsx` → `CardSheet`.
Future<void> showCardSheet(BuildContext context) {
  return showModalBottomSheet<void>(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    barrierColor: const Color(0x730F1424),
    builder: (_) => const _CardSheet(),
  );
}

class _CardSheet extends ConsumerStatefulWidget {
  const _CardSheet();

  @override
  ConsumerState<_CardSheet> createState() => _CardSheetState();
}

class _CardSheetState extends ConsumerState<_CardSheet> {
  late String _digits;

  @override
  void initState() {
    super.initState();
    final existing = ref.read(receiptDraftProvider).card;
    _digits = (existing ?? '').replaceAll(' ', '');
  }

  void _press(String d) {
    if (_digits.length >= 16) return;
    setState(() => _digits = _digits + d);
  }

  void _backspace() {
    if (_digits.isEmpty) return;
    setState(() => _digits = _digits.substring(0, _digits.length - 1));
  }

  String get _formatted {
    // groups of 4, space-separated
    final buf = StringBuffer();
    for (var i = 0; i < _digits.length; i++) {
      if (i > 0 && i % 4 == 0) buf.write(' ');
      buf.write(_digits[i]);
    }
    return buf.toString();
  }

  bool get _ready => _digits.length == 16;

  void _submit() {
    if (!_ready) return;
    ref.read(receiptDraftProvider.notifier).setCard(_formatted);
    Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final bottomInset = MediaQuery.of(context).viewInsets.bottom;
    return Padding(
      padding: EdgeInsets.only(bottom: bottomInset),
      child: Container(
        decoration: const BoxDecoration(
          color: Colors.white,
          borderRadius:
              BorderRadius.vertical(top: Radius.circular(AppRadii.xxl)),
        ),
        child: SafeArea(
          top: false,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const SizedBox(height: 8),
                Container(
                  width: 36,
                  height: 4,
                  decoration: BoxDecoration(
                    color: AppColors.ink300,
                    borderRadius: BorderRadius.circular(2),
                  ),
                ),
                const SizedBox(height: 12),
                const Padding(
                  padding:
                      EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: Text(
                      'Добавить карту',
                      style: TextStyle(
                        fontFamily: 'Manrope',
                        fontFamilyFallback: ['Roboto', 'sans-serif'],
                        fontSize: 22,
                        fontWeight: FontWeight.w800,
                        color: AppColors.ink900,
                        height: 1.15,
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 4),
                const Padding(
                  padding:
                      EdgeInsets.symmetric(horizontal: AppSpacing.screenEdge),
                  child: Align(
                    alignment: Alignment.centerLeft,
                    child: Text(
                      'Бонусы будут зачисляться на эту карту',
                      style: TextStyle(
                        fontFamily: 'Manrope',
                        fontSize: 13,
                        fontWeight: FontWeight.w700,
                        color: AppColors.ink500,
                      ),
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                Padding(
                  padding: const EdgeInsets.symmetric(
                      horizontal: AppSpacing.screenEdge),
                  child: _CardPreview(digits: _digits),
                ),
                const SizedBox(height: 16),
                Padding(
                  padding: const EdgeInsets.symmetric(
                      horizontal: AppSpacing.screenEdge),
                  child: _NumberField(digits: _digits, formatted: _formatted),
                ),
                const SizedBox(height: 16),
                Padding(
                  padding: const EdgeInsets.symmetric(
                      horizontal: AppSpacing.screenEdge),
                  child: _Keypad(
                    onPress: _press,
                    onBackspace: _backspace,
                  ),
                ),
                const SizedBox(height: 16),
                Padding(
                  padding: const EdgeInsets.symmetric(
                      horizontal: AppSpacing.screenEdge),
                  child: _BindCta(ready: _ready, onTap: _submit),
                ),
                const SizedBox(height: 24),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _CardPreview extends StatelessWidget {
  const _CardPreview({required this.digits});
  final String digits;

  @override
  Widget build(BuildContext context) {
    // 16 placeholders «•», заменяем введёнными цифрами
    final padded = (digits + '•' * 16).substring(0, 16);
    final g1 = padded.substring(0, 4);
    final g2 = padded.substring(4, 8);
    final g3 = padded.substring(8, 12);
    final g4 = padded.substring(12, 16);

    return Container(
      height: 150,
      padding: const EdgeInsets.all(16),
      clipBehavior: Clip.antiAlias,
      decoration: BoxDecoration(
        gradient: const LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: [
            Color(0xFF16A65C),
            Color(0xFF21D17A),
            Color(0xFF3DCDA2),
          ],
          stops: [0.0, 0.6, 1.0],
        ),
        borderRadius: AppRadii.brXxl,
        boxShadow: const [
          BoxShadow(
            color: Color(0x590F8F55), // rgba(15,143,85,0.35)
            offset: Offset(0, 10),
            blurRadius: 28,
          ),
        ],
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      'PHARMAPAY',
                      style: TextStyle(
                        fontFamily: 'Manrope',
                        fontFamilyFallback: const ['Roboto', 'sans-serif'],
                        fontSize: 10,
                        fontWeight: FontWeight.w800,
                        letterSpacing: 1.4,
                        color: Colors.white.withValues(alpha: 0.85),
                      ),
                    ),
                    const SizedBox(height: 2),
                    const Text(
                      'Бонусная карта',
                      style: TextStyle(
                        fontFamily: 'Manrope',
                        fontFamilyFallback: ['Roboto', 'sans-serif'],
                        fontSize: 18,
                        fontWeight: FontWeight.w800,
                        color: Colors.white,
                        height: 1.15,
                      ),
                    ),
                  ],
                ),
              ),
              // Золотой чип
              Container(
                width: 36,
                height: 24,
                decoration: BoxDecoration(
                  gradient: const LinearGradient(
                    begin: Alignment.topLeft,
                    end: Alignment.bottomRight,
                    colors: [Color(0xFFF4B73A), Color(0xFFB97F11)],
                  ),
                  borderRadius: BorderRadius.circular(6),
                  border: Border.all(
                    color: Colors.white.withValues(alpha: 0.4),
                    width: 1,
                  ),
                ),
              ),
            ],
          ),
          const Spacer(),
          Row(
            children: [
              _CardDigitGroup(text: g1),
              const SizedBox(width: 10),
              _CardDigitGroup(text: g2),
              const SizedBox(width: 10),
              _CardDigitGroup(text: g3),
              const SizedBox(width: 10),
              _CardDigitGroup(text: g4),
            ],
          ),
          const SizedBox(height: 6),
          Row(
            mainAxisAlignment: MainAxisAlignment.spaceBetween,
            children: [
              Text(
                'ДЕРЖАТЕЛЬ',
                style: TextStyle(
                  fontFamily: 'Manrope',
                  fontFamilyFallback: const ['Roboto', 'sans-serif'],
                  fontSize: 9,
                  fontWeight: FontWeight.w800,
                  letterSpacing: 1.0,
                  color: Colors.white.withValues(alpha: 0.85),
                ),
              ),
              Text(
                'VISA / KASPIGOLD / HALYK',
                style: TextStyle(
                  fontFamily: 'Manrope',
                  fontFamilyFallback: const ['Roboto', 'sans-serif'],
                  fontSize: 9,
                  fontWeight: FontWeight.w800,
                  letterSpacing: 1.0,
                  color: Colors.white.withValues(alpha: 0.85),
                ),
              ),
            ],
          ),
        ],
      ),
    );
  }
}

class _CardDigitGroup extends StatelessWidget {
  const _CardDigitGroup({required this.text});
  final String text;

  @override
  Widget build(BuildContext context) {
    final hasDot = text.contains('•');
    return Text(
      text,
      style: TextStyle(
        fontFamily: 'Manrope',
        fontFamilyFallback: const ['Roboto', 'sans-serif'],
        fontSize: 15,
        fontWeight: FontWeight.w800,
        letterSpacing: 1.5,
        color: Colors.white.withValues(alpha: hasDot ? 0.6 : 1.0),
        fontFeatures: const [FontFeature.tabularFigures()],
      ),
    );
  }
}

class _NumberField extends StatelessWidget {
  const _NumberField({required this.digits, required this.formatted});
  final String digits;
  final String formatted;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: AppColors.paperInput,
        borderRadius: AppRadii.brXl,
      ),
      child: Row(
        children: [
          const Icon(Icons.credit_card_outlined,
              size: 22, color: AppColors.brandGreen700),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                const Text(
                  'НОМЕР КАРТЫ',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 11,
                    fontWeight: FontWeight.w800,
                    letterSpacing: 0.8,
                    color: AppColors.ink500,
                  ),
                ),
                const SizedBox(height: 2),
                SizedBox(
                  height: 20,
                  child: digits.isEmpty
                      ? const Text(
                          'Введите номер карты',
                          style: TextStyle(
                            fontFamily: 'Manrope',
                            fontFamilyFallback: ['Roboto', 'sans-serif'],
                            fontSize: 16,
                            fontWeight: FontWeight.w700,
                            color: AppColors.ink300,
                          ),
                        )
                      : Text(
                          formatted,
                          maxLines: 1,
                          overflow: TextOverflow.ellipsis,
                          style: const TextStyle(
                            fontFamily: 'Manrope',
                            fontFamilyFallback: ['Roboto', 'sans-serif'],
                            fontSize: 16,
                            fontWeight: FontWeight.w800,
                            color: AppColors.ink900,
                            letterSpacing: 1.3,
                            fontFeatures: [FontFeature.tabularFigures()],
                          ),
                        ),
                ),
              ],
            ),
          ),
          Text(
            '${digits.length}/16',
            style: const TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 12,
              fontWeight: FontWeight.w800,
              color: AppColors.ink400,
              fontFeatures: [FontFeature.tabularFigures()],
            ),
          ),
        ],
      ),
    );
  }
}

class _Keypad extends StatelessWidget {
  const _Keypad({required this.onPress, required this.onBackspace});
  final ValueChanged<String> onPress;
  final VoidCallback onBackspace;

  @override
  Widget build(BuildContext context) {
    // 3×4 grid: 1 2 3 / 4 5 6 / 7 8 9 / [empty] 0 ←
    return GridView.count(
      crossAxisCount: 3,
      shrinkWrap: true,
      physics: const NeverScrollableScrollPhysics(),
      crossAxisSpacing: 10,
      mainAxisSpacing: 10,
      childAspectRatio: 2.4, // ~h56
      children: [
        for (final n in [1, 2, 3, 4, 5, 6, 7, 8, 9])
          _KeyDigit(label: '$n', onTap: () => onPress('$n')),
        const SizedBox.shrink(),
        _KeyDigit(label: '0', onTap: () => onPress('0')),
        _KeyBackspace(onTap: onBackspace),
      ],
    );
  }
}

class _KeyDigit extends StatelessWidget {
  const _KeyDigit({required this.label, required this.onTap});
  final String label;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: AppColors.paperInput,
      borderRadius: AppRadii.brXl,
      child: InkWell(
        onTap: onTap,
        borderRadius: AppRadii.brXl,
        child: Center(
          child: Text(
            label,
            style: const TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 24,
              fontWeight: FontWeight.w800,
              color: AppColors.ink900,
            ),
          ),
        ),
      ),
    );
  }
}

class _KeyBackspace extends StatelessWidget {
  const _KeyBackspace({required this.onTap});
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: AppColors.brandGreen600,
        borderRadius: AppRadii.brXl,
        boxShadow: AppShadows.fab,
      ),
      clipBehavior: Clip.antiAlias,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          child: const Center(
            child: Icon(Icons.backspace_outlined,
                size: 24, color: Colors.white),
          ),
        ),
      ),
    );
  }
}

class _BindCta extends StatelessWidget {
  const _BindCta({required this.ready, required this.onTap});
  final bool ready;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return Opacity(
      opacity: ready ? 1.0 : 0.5,
      child: Material(
        color: AppColors.brandGreen600,
        borderRadius: AppRadii.brFull,
        child: InkWell(
          onTap: ready ? onTap : null,
          borderRadius: AppRadii.brFull,
          child: Container(
            height: 60,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              borderRadius: AppRadii.brFull,
              boxShadow: ready ? AppShadows.fab : null,
            ),
            child: const Text(
              'Привязать карту',
              style: TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 18,
                fontWeight: FontWeight.w800,
                color: Colors.white,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
