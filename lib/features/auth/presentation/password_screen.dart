import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_gradients.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/widgets/error_snackbar.dart';
import '../../../core/widgets/pharma_logo.dart';
import '../application/auth_controller.dart';

class PasswordScreen extends ConsumerStatefulWidget {
  const PasswordScreen({super.key, required this.create, this.nextPath});

  final bool create;
  final String? nextPath;

  @override
  ConsumerState<PasswordScreen> createState() => _PasswordScreenState();
}

class _PasswordScreenState extends ConsumerState<PasswordScreen> {
  final _password = TextEditingController();
  final _confirmation = TextEditingController();
  bool _obscure = true;
  bool _busy = false;

  bool get _valid =>
      _password.text.length >= 8 &&
      (!widget.create || _password.text == _confirmation.text);

  @override
  void dispose() {
    _password.dispose();
    _confirmation.dispose();
    super.dispose();
  }

  Future<void> _submit() async {
    if (!_valid || _busy) return;
    setState(() => _busy = true);
    try {
      if (widget.create) {
        await ref
            .read(authActionsProvider)
            .createInitialPassword(_password.text);
      } else {
        await ref.read(authActionsProvider).loginWithPassword(_password.text);
      }
      if (mounted) context.go(widget.nextPath ?? '/home');
    } on ApiException catch (error) {
      if (widget.create && error.code == 'CONFLICT' && mounted) {
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
    final iin = ref.watch(authDraftProvider).iin ?? '';
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
                      Text(
                        widget.create ? 'Создайте пароль' : 'Введите пароль',
                        style: const TextStyle(
                          fontFamily: 'Manrope',
                          fontSize: 26,
                          fontWeight: FontWeight.w800,
                          color: Colors.white,
                        ),
                      ),
                      const SizedBox(height: 8),
                      Text(
                        widget.create
                            ? 'Пароль будет закреплён за ИИН $iin. Дальше входите по ИИН и паролю.'
                            : 'Вход по ИИН $iin',
                        style: TextStyle(
                          fontFamily: 'Manrope',
                          fontSize: 14,
                          fontWeight: FontWeight.w700,
                          color: Colors.white.withValues(alpha: 0.85),
                          height: 1.4,
                        ),
                      ),
                      const SizedBox(height: 28),
                      _PasswordField(
                        key: const Key('password-field'),
                        controller: _password,
                        label: 'Пароль — минимум 8 символов',
                        obscure: _obscure,
                        onToggle: () => setState(() => _obscure = !_obscure),
                        onChanged: (_) => setState(() {}),
                        onSubmitted: (_) => _submit(),
                      ),
                      if (widget.create) ...[
                        const SizedBox(height: 14),
                        _PasswordField(
                          key: const Key('password-confirmation-field'),
                          controller: _confirmation,
                          label: 'Повторите пароль',
                          obscure: _obscure,
                          onToggle: () => setState(() => _obscure = !_obscure),
                          onChanged: (_) => setState(() {}),
                          onSubmitted: (_) => _submit(),
                        ),
                        if (_confirmation.text.isNotEmpty &&
                            _password.text != _confirmation.text) ...[
                          const SizedBox(height: 8),
                          const Text(
                            'Пароли не совпадают.',
                            style: TextStyle(
                              fontFamily: 'Manrope',
                              fontSize: 12,
                              fontWeight: FontWeight.w800,
                              color: Color(0xFFFFD2D2),
                            ),
                          ),
                        ],
                      ],
                      const SizedBox(height: 20),
                      FilledButton(
                        onPressed: _valid && !_busy ? _submit : null,
                        style: FilledButton.styleFrom(
                          minimumSize: const Size.fromHeight(60),
                          backgroundColor: AppColors.brandBlue400,
                          shape: const StadiumBorder(),
                        ),
                        child: Text(
                          _busy
                              ? 'Сохраняем…'
                              : widget.create
                                  ? 'Создать пароль'
                                  : 'Войти',
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

class _PasswordField extends StatelessWidget {
  const _PasswordField({
    super.key,
    required this.controller,
    required this.label,
    required this.obscure,
    required this.onToggle,
    required this.onChanged,
    required this.onSubmitted,
  });

  final TextEditingController controller;
  final String label;
  final bool obscure;
  final VoidCallback onToggle;
  final ValueChanged<String> onChanged;
  final ValueChanged<String> onSubmitted;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 64,
      padding: const EdgeInsets.only(left: 16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: AppRadii.brLg,
      ),
      child: TextField(
        controller: controller,
        obscureText: obscure,
        enableSuggestions: false,
        autocorrect: false,
        textInputAction: TextInputAction.done,
        autofillHints: const [AutofillHints.password],
        style: const TextStyle(
          fontFamily: 'Manrope',
          fontSize: 18,
          fontWeight: FontWeight.w800,
          color: AppColors.ink900,
        ),
        decoration: InputDecoration(
          filled: false,
          border: InputBorder.none,
          enabledBorder: InputBorder.none,
          focusedBorder: InputBorder.none,
          hintText: label,
          suffixIcon: IconButton(
            tooltip: obscure ? 'Показать пароль' : 'Скрыть пароль',
            onPressed: onToggle,
            icon: Icon(obscure ? Icons.visibility : Icons.visibility_off),
          ),
        ),
        onChanged: onChanged,
        onSubmitted: onSubmitted,
      ),
    );
  }
}
