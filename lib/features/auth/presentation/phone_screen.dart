import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mask_text_input_formatter/mask_text_input_formatter.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_gradients.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_shadows.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/widgets/brand_icons.dart';
import '../../../core/widgets/error_snackbar.dart';
import '../../../core/widgets/pharma_logo.dart';
import '../application/auth_controller.dart';

class PhoneScreen extends ConsumerStatefulWidget {
  const PhoneScreen({super.key});

  @override
  ConsumerState<PhoneScreen> createState() => _PhoneScreenState();
}

class _PhoneScreenState extends ConsumerState<PhoneScreen> {
  final _maskFormatter = MaskTextInputFormatter(
    mask: '(###) ###-##-##',
    filter: {'#': RegExp(r'[0-9]')},
  );
  final _focus = FocusNode();
  String _digits = '';
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _focus.requestFocus());
  }

  @override
  void dispose() {
    _focus.dispose();
    super.dispose();
  }

  bool get _canSubmit => _digits.length == 10 && !_busy;

  Future<void> _submit() async {
    if (!_canSubmit) return;
    setState(() => _busy = true);
    final full = '+7 (${_digits.substring(0, 3)}) '
        '${_digits.substring(3, 6)}-${_digits.substring(6, 8)}-${_digits.substring(8, 10)}';
    try {
      await ref.read(authActionsProvider).sendOtp(full);
      if (mounted) context.go('/auth/otp');
    } catch (e) {
      // Сеть/бэкенд недоступны или номер отклонён — показываем понятную
      // ошибку, а не «глотаем» её (иначе кнопка «Войти» молча ничего не делает).
      if (mounted) showErrorSnackBar(context, e);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: systemUiOverlayStyleDark,
      child: Scaffold(
        body: Container(
          decoration: const BoxDecoration(gradient: AppGradients.welcome),
          child: SafeArea(
            child: Stack(
              children: [
                // Декорация Pharm внизу
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
                // Контент
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 8, 24, 0),
                      child: Row(
                        children: [
                          IconButton(
                            onPressed: () => context.go('/home'),
                            icon: const Icon(Icons.arrow_back, color: Colors.white),
                          ),
                          const SizedBox(width: 4),
                          const PharmaLogo(size: 48),
                        ],
                      ),
                    ),
                    const SizedBox(height: AppSpacing.s24),
                    const Padding(
                      padding: EdgeInsets.symmetric(horizontal: AppSpacing.s24),
                      child: Text(
                        'Введите номер телефона 📱',
                        style: TextStyle(
                          fontFamily: 'Manrope',
                          fontSize: 26,
                          fontWeight: FontWeight.w800,
                          color: Colors.white,
                          height: 1.1,
                        ),
                      ),
                    ),
                    const SizedBox(height: 8),
                    Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: AppSpacing.s24,
                      ),
                      child: Text(
                        'На него мы отправим код подтверждения',
                        style: TextStyle(
                          fontFamily: 'Manrope',
                          fontSize: 14,
                          fontWeight: FontWeight.w700,
                          color: Colors.white.withValues(alpha: 0.85),
                        ),
                      ),
                    ),
                    const SizedBox(height: 40),
                    Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: AppSpacing.s24,
                      ),
                      child: _PhoneField(
                        focusNode: _focus,
                        maskFormatter: _maskFormatter,
                        onChanged: (d) => setState(() => _digits = d),
                      ),
                    ),
                    const SizedBox(height: 20),
                    Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: AppSpacing.s24,
                      ),
                      child: _MintCta(
                        label: _busy ? 'Отправляем…' : 'Войти',
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

class _PhoneField extends StatelessWidget {
  const _PhoneField({
    required this.focusNode,
    required this.maskFormatter,
    required this.onChanged,
  });

  final FocusNode focusNode;
  final MaskTextInputFormatter maskFormatter;
  final ValueChanged<String> onChanged;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 64,
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: AppRadii.brLg,
      ),
      child: Row(
        children: [
          const SizedBox(width: 16),
          const Text(
            '+7',
            style: TextStyle(
              fontFamily: 'Manrope',
              fontSize: 22,
              fontWeight: FontWeight.w800,
              color: AppColors.ink900,
              fontFeatures: [FontFeature.tabularFigures()],
            ),
          ),
          const SizedBox(width: 8),
          Expanded(
            child: TextField(
              focusNode: focusNode,
              keyboardType: TextInputType.phone,
              inputFormatters: [maskFormatter],
              autofillHints: const [AutofillHints.telephoneNumberNational],
              style: const TextStyle(
                fontFamily: 'Manrope',
                fontSize: 22,
                fontWeight: FontWeight.w800,
                color: AppColors.ink900,
              ),
              decoration: InputDecoration(
                filled: false,
                border: InputBorder.none,
                enabledBorder: InputBorder.none,
                focusedBorder: InputBorder.none,
                contentPadding: const EdgeInsets.symmetric(vertical: 18),
                hintText: '(___) ___-__-__',
                hintStyle: TextStyle(
                  fontFamily: 'Manrope',
                  fontSize: 22,
                  fontWeight: FontWeight.w800,
                  color: AppColors.ink300,
                ),
              ),
              onChanged: (_) => onChanged(maskFormatter.getUnmaskedText()),
            ),
          ),
          const SizedBox(width: 12),
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
