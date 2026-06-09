import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_gradients.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_shadows.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/validation/iin.dart';
import '../../../core/widgets/brand_icons.dart';
import '../../../core/widgets/pharma_logo.dart';
import '../application/auth_controller.dart';

class ProfileFormScreen extends ConsumerStatefulWidget {
  const ProfileFormScreen({super.key});

  @override
  ConsumerState<ProfileFormScreen> createState() => _ProfileFormScreenState();
}

class _ProfileFormScreenState extends ConsumerState<ProfileFormScreen> {
  final _fioCtrl = TextEditingController();
  final _iinCtrl = TextEditingController();
  bool _busy = false;

  @override
  void dispose() {
    _fioCtrl.dispose();
    _iinCtrl.dispose();
    super.dispose();
  }

  bool get _fioValid =>
      _fioCtrl.text.trim().split(RegExp(r'\s+')).where((s) => s.isNotEmpty).length >= 2;
  // Полная проверка: формат + дата рождения + контрольная сумма (как на бэке @Iin).
  bool get _iinValid => isValidIin(_iinCtrl.text);
  // Показываем ошибку только когда уже ввели 12 цифр, но контроль не сошёлся.
  bool get _iinError => _iinCtrl.text.length == 12 && !_iinValid;
  bool get _canSubmit => _fioValid && _iinValid && !_busy;

  Future<void> _submit() async {
    if (!_canSubmit) return;
    setState(() => _busy = true);
    try {
      await ref.read(authActionsProvider).completeRegistration(
            fio: _fioCtrl.text.trim(),
            iin: _iinCtrl.text,
          );
      if (mounted) context.go('/home');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: systemUiOverlayStyleDark,
      child: Scaffold(
        resizeToAvoidBottomInset: true,
        body: Container(
          decoration: const BoxDecoration(gradient: AppGradients.welcome),
          child: SafeArea(
            child: Stack(
              children: [
                const Positioned(
                  left: 24,
                  bottom: 80,
                  child: Opacity(opacity: 0.95, child: PharmGlyph(size: 170)),
                ),
                const Positioned(
                  right: 24,
                  bottom: -8,
                  child: PharmGlyph(size: 120),
                ),
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 8, 24, 0),
                      child: Row(
                        children: [
                          IconButton(
                            onPressed: () => context.go('/auth/otp'),
                            icon: const Icon(Icons.arrow_back, color: Colors.white),
                          ),
                          const SizedBox(width: 4),
                          const PharmaWordmark(size: PharmaWordmarkSize.lg),
                        ],
                      ),
                    ),
                    const SizedBox(height: AppSpacing.s24),
                    const Padding(
                      padding: EdgeInsets.symmetric(horizontal: AppSpacing.s24),
                      child: Text(
                        'Завершите регистрацию',
                        style: TextStyle(
                          fontFamily: 'Manrope',
                          fontSize: 26,
                          fontWeight: FontWeight.w800,
                          color: Colors.white,
                          height: 1.1,
                        ),
                      ),
                    ),
                    const SizedBox(height: AppSpacing.s24),
                    Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: AppSpacing.s24,
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          _Label('Введите ваше ФИО'),
                          const SizedBox(height: 6),
                          _WhiteField(
                            controller: _fioCtrl,
                            hint: 'Иванов Иван Иванович',
                            keyboardType: TextInputType.text,
                            textCapitalization: TextCapitalization.words,
                            onChanged: (_) => setState(() {}),
                          ),
                          const SizedBox(height: 16),
                          _Label('Введите ИИН'),
                          const SizedBox(height: 6),
                          _WhiteField(
                            controller: _iinCtrl,
                            hint: '000000 000000',
                            keyboardType: TextInputType.number,
                            inputFormatters: [
                              FilteringTextInputFormatter.digitsOnly,
                            ],
                            maxLength: 12,
                            letterSpacing: 0.18 * 20,
                            trailing: Text(
                              '${_iinCtrl.text.length}/12',
                              style: const TextStyle(
                                fontFamily: 'Manrope',
                                fontSize: 12,
                                fontWeight: FontWeight.w800,
                                color: AppColors.ink400,
                              ),
                            ),
                            onChanged: (_) => setState(() {}),
                          ),
                          if (_iinError) ...[
                            const SizedBox(height: 8),
                            const Text(
                              'Проверьте ИИН: 12 цифр, корректная дата рождения и контрольная цифра',
                              style: TextStyle(
                                fontFamily: 'Manrope',
                                fontSize: 12,
                                fontWeight: FontWeight.w800,
                                color: Color(0xFFFFD2D2),
                                height: 1.3,
                              ),
                            ),
                          ],
                          const SizedBox(height: 12),
                          Row(
                            children: [
                              Icon(
                                Icons.lock_outline,
                                size: 16,
                                color: Colors.white.withValues(alpha: 0.85),
                              ),
                              const SizedBox(width: 8),
                              Expanded(
                                child: Text(
                                  'Данные защищены и используются только для зачисления выплат',
                                  style: TextStyle(
                                    fontFamily: 'Manrope',
                                    fontSize: 12,
                                    fontWeight: FontWeight.w800,
                                    color: Colors.white.withValues(alpha: 0.85),
                                    height: 1.3,
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                    const SizedBox(height: 24),
                    Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: AppSpacing.s24,
                      ),
                      child: _MintCta(
                        label: _busy ? 'Сохраняем…' : 'Продолжить',
                        onTap: _canSubmit ? _submit : null,
                      ),
                    ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _Label extends StatelessWidget {
  const _Label(this.text);
  final String text;

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 4),
      child: Text(
        text,
        style: const TextStyle(
          fontFamily: 'Manrope',
          fontSize: 14,
          fontWeight: FontWeight.w700,
          color: Colors.white,
        ),
      ),
    );
  }
}

class _WhiteField extends StatelessWidget {
  const _WhiteField({
    required this.controller,
    required this.hint,
    this.keyboardType,
    this.inputFormatters,
    this.textCapitalization = TextCapitalization.none,
    this.maxLength,
    this.letterSpacing,
    this.trailing,
    required this.onChanged,
  });

  final TextEditingController controller;
  final String hint;
  final TextInputType? keyboardType;
  final List<TextInputFormatter>? inputFormatters;
  final TextCapitalization textCapitalization;
  final int? maxLength;
  final double? letterSpacing;
  final Widget? trailing;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 60,
      padding: const EdgeInsets.symmetric(horizontal: 16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: AppRadii.brLg,
      ),
      child: Row(
        children: [
          Expanded(
            child: TextField(
              controller: controller,
              keyboardType: keyboardType,
              inputFormatters: inputFormatters,
              textCapitalization: textCapitalization,
              maxLength: maxLength,
              // Кириллический ввод на Android. Android IME при включённом
              // autocorrect/suggestions нередко форсит Latin-словарь и теряет
              // или искажает не-латинские символы. Отключаем оба.
              autocorrect: false,
              enableSuggestions: false,
              smartDashesType: SmartDashesType.disabled,
              smartQuotesType: SmartQuotesType.disabled,
              style: TextStyle(
                fontFamily: 'Manrope',
                // Глифы кириллицы у Manrope-Variable есть, но fallback ставим
                // явно — на случай если subset font пропустит редкий символ.
                fontFamilyFallback: const ['Roboto', 'sans-serif'],
                fontSize: 18,
                fontWeight: FontWeight.w800,
                color: AppColors.ink900,
                letterSpacing: letterSpacing,
              ),
              decoration: InputDecoration(
                filled: false,
                border: InputBorder.none,
                enabledBorder: InputBorder.none,
                focusedBorder: InputBorder.none,
                contentPadding: EdgeInsets.zero,
                hintText: hint,
                hintStyle: const TextStyle(
                  fontFamily: 'Manrope',
                  fontFamilyFallback: ['Roboto', 'sans-serif'],
                  fontSize: 18,
                  fontWeight: FontWeight.w800,
                  color: AppColors.ink300,
                ),
                counterText: '',
              ),
              onChanged: onChanged,
            ),
          ),
          if (trailing != null) trailing!,
        ],
      ),
    );
  }
}

class _MintCta extends StatelessWidget {
  const _MintCta({required this.label, this.onTap});
  final String label;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    final enabled = onTap != null;
    return Opacity(
      opacity: enabled ? 1 : 0.5,
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: AppRadii.brFull,
          child: Container(
            width: double.infinity,
            height: 60,
            alignment: Alignment.center,
            decoration: BoxDecoration(
              color: AppColors.brandBlue400,
              borderRadius: AppRadii.brFull,
              boxShadow: enabled ? AppShadows.fab : null,
            ),
            child: Text(
              label,
              style: const TextStyle(
                fontFamily: 'Manrope',
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
