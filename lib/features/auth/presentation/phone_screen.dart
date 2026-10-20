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
import '../../../core/widgets/error_snackbar.dart';
import '../../../core/widgets/pharma_logo.dart';
import '../application/auth_controller.dart';

/// Первый шаг входа фармацевта. Имя класса сохранено ради действующих deep-link.
class PhoneScreen extends ConsumerStatefulWidget {
  const PhoneScreen({super.key, this.nextPath});

  final String? nextPath;

  @override
  ConsumerState<PhoneScreen> createState() => _PhoneScreenState();
}

class _PhoneScreenState extends ConsumerState<PhoneScreen> {
  final _controller = TextEditingController();
  final _focus = FocusNode();
  bool _busy = false;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _focus.requestFocus());
  }

  @override
  void dispose() {
    _controller.dispose();
    _focus.dispose();
    super.dispose();
  }

  bool get _valid => isValidIin(_controller.text);
  bool get _showError => _controller.text.length == 12 && !_valid;

  Future<void> _submit() async {
    if (!_valid || _busy) return;
    setState(() => _busy = true);
    try {
      final passwordSet = await ref
          .read(authActionsProvider)
          .checkIin(_controller.text);
      if (!mounted) return;
      context.go(
        Uri(
          path: passwordSet ? '/auth/password' : '/auth/activation-phone',
          queryParameters: widget.nextPath == null
              ? null
              : {'next': widget.nextPath!},
        ).toString(),
      );
    } catch (error) {
      if (mounted) showErrorSnackBar(context, error);
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
                Align(
                  alignment: Alignment.topCenter,
                  child: ConstrainedBox(
                    constraints: const BoxConstraints(maxWidth: 520),
                    child: Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: AppSpacing.s24,
                      ),
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Padding(
                            padding: const EdgeInsets.only(top: 8),
                            child: Row(
                              children: [
                                IconButton(
                                  tooltip: 'Назад',
                                  onPressed: () =>
                                      context.go(widget.nextPath ?? '/home'),
                                  icon: const Icon(
                                    Icons.arrow_back,
                                    color: Colors.white,
                                  ),
                                ),
                                const SizedBox(width: 4),
                                const PharmaLogo(size: 48),
                              ],
                            ),
                          ),
                          const SizedBox(height: AppSpacing.s24),
                          const Text(
                            'Вход для фармацевта',
                            style: TextStyle(
                              fontFamily: 'Manrope',
                              fontSize: 26,
                              fontWeight: FontWeight.w800,
                              color: Colors.white,
                            ),
                          ),
                          const SizedBox(height: 8),
                          Text(
                            'Введите ИИН. При первом входе подтвердите привязанный номер и создайте пароль.',
                            style: TextStyle(
                              fontFamily: 'Manrope',
                              fontSize: 14,
                              fontWeight: FontWeight.w700,
                              color: Colors.white.withValues(alpha: 0.85),
                              height: 1.4,
                            ),
                          ),
                          const SizedBox(height: 32),
                          Container(
                            height: 64,
                            padding: const EdgeInsets.symmetric(horizontal: 16),
                            decoration: BoxDecoration(
                              color: Colors.white,
                              borderRadius: AppRadii.brLg,
                            ),
                            child: TextField(
                              key: const Key('iin-field'),
                              controller: _controller,
                              focusNode: _focus,
                              keyboardType: TextInputType.number,
                              textInputAction: TextInputAction.done,
                              inputFormatters: [
                                FilteringTextInputFormatter.digitsOnly,
                                LengthLimitingTextInputFormatter(12),
                              ],
                              autofillHints: const [AutofillHints.username],
                              style: const TextStyle(
                                fontFamily: 'Manrope',
                                fontSize: 21,
                                fontWeight: FontWeight.w800,
                                letterSpacing: 2,
                                color: AppColors.ink900,
                              ),
                              decoration: const InputDecoration(
                                filled: false,
                                border: InputBorder.none,
                                enabledBorder: InputBorder.none,
                                focusedBorder: InputBorder.none,
                                hintText: 'ИИН — 12 цифр',
                                counterText: '',
                              ),
                              onChanged: (_) => setState(() {}),
                              onSubmitted: (_) => _submit(),
                            ),
                          ),
                          if (_showError) ...[
                            const SizedBox(height: 8),
                            const Text(
                              'Проверьте ИИН: дата рождения или контрольная цифра неверны.',
                              style: TextStyle(
                                fontFamily: 'Manrope',
                                fontSize: 12,
                                fontWeight: FontWeight.w800,
                                color: Color(0xFFFFD2D2),
                              ),
                            ),
                          ],
                          const SizedBox(height: 20),
                          _PrimaryButton(
                            label: _busy ? 'Проверяем…' : 'Продолжить',
                            onTap: _valid && !_busy ? _submit : null,
                          ),
                          const SizedBox(height: 16),
                          Row(
                            children: [
                              const Icon(
                                Icons.lock_outline,
                                color: Colors.white,
                                size: 17,
                              ),
                              const SizedBox(width: 8),
                              Expanded(
                                child: Text(
                                  'Пароль сохраняется только для указанного ИИН.',
                                  style: TextStyle(
                                    fontFamily: 'Manrope',
                                    fontSize: 12,
                                    fontWeight: FontWeight.w700,
                                    color: Colors.white.withValues(alpha: 0.85),
                                  ),
                                ),
                              ),
                            ],
                          ),
                        ],
                      ),
                    ),
                  ),
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

class _PrimaryButton extends StatelessWidget {
  const _PrimaryButton({required this.label, this.onTap});

  final String label;
  final VoidCallback? onTap;

  @override
  Widget build(BuildContext context) {
    return Opacity(
      opacity: onTap == null ? 0.5 : 1,
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
              boxShadow: onTap == null ? null : AppShadows.fab,
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
