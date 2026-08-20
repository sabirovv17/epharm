import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';
import 'package:pinput/pinput.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_gradients.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_shadows.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_theme.dart';
import '../../../core/config/api_config.dart';
import '../../../core/network/api_exception.dart';
import '../../../core/widgets/brand_icons.dart';
import '../../../core/widgets/pharma_logo.dart';
import '../application/auth_controller.dart';
import '../data/auth_repository.dart';

class OtpScreen extends ConsumerStatefulWidget {
  const OtpScreen({super.key});

  @override
  ConsumerState<OtpScreen> createState() => _OtpScreenState();
}

class _OtpScreenState extends ConsumerState<OtpScreen> {
  final _controller = TextEditingController();
  final _focus = FocusNode();
  String _code = '';
  bool _busy = false;
  bool _resending = false;
  String? _error;
  Timer? _ticker;
  // 60с — в такт серверному кулдауну повторной отправки (app.otp.resend-cooldown-seconds).
  int _seconds = 60;
  Timer? _autoFillTimer;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _focus.requestFocus());
    _ticker = Timer.periodic(const Duration(seconds: 1), (_) {
      if (_seconds <= 0) return;
      setState(() => _seconds -= 1);
    });
    // Demo: auto-fill дефолтным кодом через 600ms — ТОЛЬКО в mock-режиме.
    // В боевом режиме (USE_API=true) реальный код приходит по SMS, автозаполнять нельзя.
    if (!ApiConfig.useApi) {
      _autoFillTimer = Timer(const Duration(milliseconds: 600), () {
        if (!mounted) return;
        _controller.text = AuthRepository.defaultOtpCode;
        setState(() => _code = AuthRepository.defaultOtpCode);
      });
    }
  }

  @override
  void dispose() {
    _ticker?.cancel();
    _autoFillTimer?.cancel();
    _controller.dispose();
    _focus.dispose();
    super.dispose();
  }

  bool get _canSubmit => _code.length == AuthRepository.otpCodeLength && !_busy;

  /// Повторная отправка кода (когда таймер дошёл до 0). Реальная SMS может потеряться /
  /// задержаться — без этой кнопки пользователь застревал на экране. Бэкенд держит свой
  /// кулдаун (429 OTP_RESEND_TOO_SOON) — его сообщение показываем как есть.
  Future<void> _resend() async {
    if (_resending) return;
    final phone = ref.read(authDraftProvider).phone;
    if (phone == null || phone.isEmpty) return;
    setState(() {
      _resending = true;
      _error = null;
    });
    try {
      await ref.read(authActionsProvider).sendOtp(phone);
      if (!mounted) return;
      _controller.clear();
      setState(() {
        _code = '';
        _seconds = 60; // новый цикл ожидания
      });
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } catch (_) {
      if (mounted) setState(() => _error = 'Ошибка сети. Попробуйте ещё раз.');
    } finally {
      if (mounted) setState(() => _resending = false);
    }
  }

  Future<void> _submit() async {
    if (!_canSubmit) return;
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      final outcome = await ref.read(authActionsProvider).verifyOtp(_code);
      if (!mounted) return;
      // Существующий фармацевт → сразу домой; новый номер → экран ФИО+ИИН.
      if (outcome == OtpOutcome.loggedIn) {
        context.go('/home');
      } else {
        context.go('/auth/profile');
      }
    } on ApiException catch (e) {
      if (mounted) setState(() => _error = e.message);
    } catch (_) {
      if (mounted) setState(() => _error = 'Ошибка сети. Попробуйте ещё раз.');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final phone = ref.watch(authDraftProvider).phone ?? '';
    final mm = (_seconds ~/ 60).toString().padLeft(2, '0');
    final ss = (_seconds % 60).toString().padLeft(2, '0');

    final defaultPinTheme = PinTheme(
      width: 48,
      height: 60,
      textStyle: const TextStyle(
        fontFamily: 'Manrope',
        fontSize: 22,
        fontWeight: FontWeight.w800,
        color: AppColors.ink900,
      ),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: AppRadii.brLg,
      ),
    );
    final focusedPinTheme = defaultPinTheme.copyWith(
      decoration: defaultPinTheme.decoration!.copyWith(
        border: Border.all(color: AppColors.brandBlue400, width: 2),
      ),
    );

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
                Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Padding(
                      padding: const EdgeInsets.fromLTRB(16, 8, 24, 0),
                      child: Row(
                        children: [
                          IconButton(
                            onPressed: () => context.go('/auth/phone'),
                            icon: const Icon(Icons.arrow_back,
                                color: Colors.white),
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
                        'Введите код из смс 💬',
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
                      child: Text(
                        'Код отправлен на номер $phone',
                        style: const TextStyle(
                          fontFamily: 'Manrope',
                          fontSize: 14,
                          fontWeight: FontWeight.w700,
                          color: Colors.white,
                        ),
                      ),
                    ),
                    const SizedBox(height: AppSpacing.s16),
                    Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: AppSpacing.s24,
                      ),
                      child: Pinput(
                        controller: _controller,
                        focusNode: _focus,
                        length: AuthRepository.otpCodeLength,
                        enabled: !_busy, // блокируем ввод, пока проверяется код
                        defaultPinTheme: defaultPinTheme,
                        focusedPinTheme: focusedPinTheme,
                        separatorBuilder: (_) => const SizedBox(width: 8),
                        keyboardType: TextInputType.number,
                        inputFormatters: [
                          FilteringTextInputFormatter.digitsOnly
                        ],
                        onChanged: (v) {
                          setState(() {
                            _code = v;
                            _error = null;
                          });
                        },
                        onCompleted: (_) => _submit(),
                      ),
                    ),
                    const SizedBox(height: 20),
                    Padding(
                      padding: const EdgeInsets.symmetric(
                        horizontal: AppSpacing.s24,
                      ),
                      child: _MintCta(
                        label: _busy ? 'Проверяем…' : 'Войти',
                        onTap: _canSubmit ? _submit : null,
                      ),
                    ),
                    const SizedBox(height: AppSpacing.s24),
                    if (_error != null)
                      Padding(
                        padding: const EdgeInsets.symmetric(
                          horizontal: AppSpacing.s24,
                        ),
                        child: Text(
                          _error!,
                          style: const TextStyle(
                            fontFamily: 'Manrope',
                            fontSize: 14,
                            color: Colors.white,
                          ),
                        ),
                      ),
                    Center(
                      // Пока тикает таймер — показываем обратный отсчёт; на нуле — активную
                      // кнопку повторной отправки (SMS может потеряться, без неё тупик).
                      child: _seconds > 0
                          ? Text(
                              'Повторная отправка через: $mm:$ss',
                              style: const TextStyle(
                                fontFamily: 'Manrope',
                                fontSize: 13,
                                fontWeight: FontWeight.w800,
                                color: Colors.white,
                              ),
                            )
                          : TextButton(
                              onPressed: _resending ? null : _resend,
                              child: Text(
                                _resending
                                    ? 'Отправляем…'
                                    : 'Отправить код ещё раз',
                                style: const TextStyle(
                                  fontFamily: 'Manrope',
                                  fontSize: 14,
                                  fontWeight: FontWeight.w800,
                                  color: Colors.white,
                                  decoration: TextDecoration.underline,
                                  decorationColor: Colors.white,
                                ),
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
