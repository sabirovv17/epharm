import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:mask_text_input_formatter/mask_text_input_formatter.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_gradients.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/widgets/error_snackbar.dart';
import '../../../core/widgets/pharma_logo.dart';
import '../application/auth_controller.dart';

class ActivationPhoneScreen extends ConsumerStatefulWidget {
  const ActivationPhoneScreen({super.key, this.nextPath});

  final String? nextPath;

  @override
  ConsumerState<ActivationPhoneScreen> createState() =>
      _ActivationPhoneScreenState();
}

class _ActivationPhoneScreenState extends ConsumerState<ActivationPhoneScreen> {
  final _formatter = MaskTextInputFormatter(
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

  Future<void> _submit() async {
    if (_digits.length != 10 || _busy) return;
    setState(() => _busy = true);
    try {
      await ref.read(authActionsProvider).sendActivationOtp('+7$_digits');
      if (!mounted) return;
      context.go(
        Uri(
          path: '/auth/otp',
          queryParameters:
              widget.nextPath == null ? null : {'next': widget.nextPath!},
        ).toString(),
      );
    } on ApiException catch (error) {
      if (error.code == 'CONFLICT' && mounted) {
        context.go(
          Uri(
            path: '/auth/password',
            queryParameters:
                widget.nextPath == null ? null : {'next': widget.nextPath!},
          ).toString(),
        );
        return;
      }
      if (mounted) showErrorSnackBar(context, error);
    } catch (error) {
      if (mounted) showErrorSnackBar(context, error);
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final draft = ref.watch(authDraftProvider);
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: systemUiOverlayStyleDark,
      child: Scaffold(
        body: Container(
          decoration: const BoxDecoration(gradient: AppGradients.welcome),
          child: SafeArea(
            child: Center(
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
                              onPressed: () => context.go(
                                Uri(
                                  path: '/auth/phone',
                                  queryParameters: widget.nextPath == null
                                      ? null
                                      : {'next': widget.nextPath!},
                                ).toString(),
                              ),
                              icon: const Icon(
                                Icons.arrow_back,
                                color: Colors.white,
                              ),
                            ),
                            const PharmaLogo(size: 48),
                          ],
                        ),
                      ),
                      const SizedBox(height: AppSpacing.s24),
                      const Text(
                        'Подтвердите телефон',
                        style: TextStyle(
                          fontFamily: 'Manrope',
                          fontSize: 26,
                          fontWeight: FontWeight.w800,
                          color: Colors.white,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        (draft.phoneMasked?.isNotEmpty ?? false)
                            ? 'Введите номер, закреплённый за ИИН ${draft.iin ?? ''}. Подсказка: ${draft.phoneMasked}'
                            : 'Введите номер телефона. После подтверждения он будет закреплён за ИИН ${draft.iin ?? ''}.',
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
                        child: Row(
                          children: [
                            const Text(
                              '+7',
                              style: TextStyle(
                                fontFamily: 'Manrope',
                                fontSize: 21,
                                fontWeight: FontWeight.w800,
                                color: AppColors.ink900,
                              ),
                            ),
                            const SizedBox(width: 8),
                            Expanded(
                              child: TextField(
                                key: const Key('activation-phone-field'),
                                focusNode: _focus,
                                keyboardType: TextInputType.phone,
                                inputFormatters: [_formatter],
                                autofillHints: const [
                                  AutofillHints.telephoneNumberNational,
                                ],
                                style: const TextStyle(
                                  fontFamily: 'Manrope',
                                  fontSize: 21,
                                  fontWeight: FontWeight.w800,
                                  color: AppColors.ink900,
                                ),
                                decoration: const InputDecoration(
                                  filled: false,
                                  border: InputBorder.none,
                                  enabledBorder: InputBorder.none,
                                  focusedBorder: InputBorder.none,
                                  hintText: '(___) ___-__-__',
                                ),
                                onChanged: (_) => setState(
                                  () => _digits = _formatter.getUnmaskedText(),
                                ),
                                onSubmitted: (_) => _submit(),
                              ),
                            ),
                          ],
                        ),
                      ),
                      const SizedBox(height: 20),
                      FilledButton(
                        onPressed:
                            _digits.length == 10 && !_busy ? _submit : null,
                        style: FilledButton.styleFrom(
                          minimumSize: const Size.fromHeight(60),
                          backgroundColor: AppColors.brandBlue400,
                          shape: const StadiumBorder(),
                        ),
                        child: Text(
                          _busy ? 'Отправляем…' : 'Получить код',
                          style: const TextStyle(
                            fontFamily: 'Manrope',
                            fontSize: 18,
                            fontWeight: FontWeight.w800,
                          ),
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
