import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:mobile_scanner/mobile_scanner.dart';

import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_spacing.dart';
import '../../../core/theme/app_typography.dart';
import '../../../core/widgets/error_snackbar.dart';
import '../application/training_controller.dart';
import '../data/training_models.dart';

class TrainingQrScreen extends ConsumerStatefulWidget {
  const TrainingQrScreen({super.key});

  @override
  ConsumerState<TrainingQrScreen> createState() => _TrainingQrScreenState();
}

class _TrainingQrScreenState extends ConsumerState<TrainingQrScreen> {
  final MobileScannerController _controller = MobileScannerController(
    formats: const [BarcodeFormat.qrCode],
    detectionSpeed: DetectionSpeed.noDuplicates,
  );
  bool _submitting = false;

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        title: const Text('Регистрация на мероприятии'),
        foregroundColor: Colors.white,
        backgroundColor: Colors.black,
        actions: [
          TextButton(
            onPressed: _submitting ? null : _showCodeEntry,
            child: const Text(
              'Ввести код',
              style: TextStyle(color: Colors.white, fontWeight: FontWeight.w700),
            ),
          ),
          IconButton(
            tooltip: 'Фонарик',
            onPressed: _controller.toggleTorch,
            icon: const Icon(Icons.flashlight_on_outlined),
          ),
        ],
      ),
      body: Stack(
        fit: StackFit.expand,
        children: [
          MobileScanner(
            controller: _controller,
            onDetect: _onDetect,
            errorBuilder: (context, error, child) => _ScannerError(
              message: error.errorDetails?.message ??
                  'Камера недоступна. Проверьте разрешение в настройках.',
            ),
          ),
          IgnorePointer(
            child: Center(
              child: Container(
                width: 260,
                height: 260,
                decoration: BoxDecoration(
                  border: Border.all(color: Colors.white, width: 3),
                  borderRadius: AppRadii.brLg,
                ),
              ),
            ),
          ),
          if (_submitting)
            const ColoredBox(
              color: Color(0x66000000),
              child: Center(
                child: CircularProgressIndicator(color: Colors.white),
              ),
            ),
        ],
      ),
    );
  }

  Future<void> _onDetect(BarcodeCapture capture) async {
    if (_submitting) return;
    final rawValue = capture.barcodes
        .map((barcode) => barcode.rawValue)
        .whereType<String>()
        .firstOrNull;
    final token = rawValue == null ? null : _extractToken(rawValue);
    if (token == null) return;
    await _submitCredential(token);
  }

  Future<void> _submitCredential(String credential) async {
    if (_submitting) return;
    setState(() => _submitting = true);
    await _controller.stop();
    try {
      final assignment =
          await ref.read(trainingActionsProvider).checkIn(credential);
      if (!mounted) return;
      Navigator.of(context).pop<TrainingAssignment>(assignment);
    } catch (error) {
      if (!mounted) return;
      showErrorSnackBar(context, error);
      setState(() => _submitting = false);
      await _controller.start();
    }
  }

  Future<void> _showCodeEntry() async {
    if (_submitting) return;
    // На web остановка camera stream может ждать браузерный media lifecycle и
    // блокировать открытие формы. Форма кода не зависит от результата stop().
    unawaited(_controller.stop());
    if (!mounted) return;
    final codeController = TextEditingController();
    final code = await showModalBottomSheet<String>(
      context: context,
      isScrollControlled: true,
      showDragHandle: true,
      builder: (sheetContext) => SafeArea(
        child: Padding(
          padding: EdgeInsets.fromLTRB(
            AppSpacing.s24,
            AppSpacing.s8,
            AppSpacing.s24,
            MediaQuery.viewInsetsOf(sheetContext).bottom + AppSpacing.s24,
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Text('Код мероприятия', style: AppTypography.title20()),
              const SizedBox(height: AppSpacing.s8),
              Text(
                'Введите 6 цифр, которые показывает организатор.',
                style: AppTypography.body14(),
              ),
              const SizedBox(height: AppSpacing.s16),
              TextField(
                controller: codeController,
                autofocus: true,
                keyboardType: TextInputType.number,
                textAlign: TextAlign.center,
                maxLength: 6,
                inputFormatters: [FilteringTextInputFormatter.digitsOnly],
                style: const TextStyle(
                  fontSize: 28,
                  fontWeight: FontWeight.w800,
                  letterSpacing: 8,
                ),
                decoration: const InputDecoration(
                  hintText: '000000',
                  counterText: '',
                ),
                onSubmitted: (value) {
                  if (value.length == 6) Navigator.of(sheetContext).pop(value);
                },
              ),
              const SizedBox(height: AppSpacing.s16),
              FilledButton(
                onPressed: () {
                  final value = codeController.text;
                  if (value.length == 6) Navigator.of(sheetContext).pop(value);
                },
                child: const Text('Подтвердить посещение'),
              ),
            ],
          ),
        ),
      ),
    );
    codeController.dispose();
    if (!mounted) return;
    if (code == null) {
      unawaited(_controller.start());
      return;
    }
    await _submitCredential(code);
  }

  String? _extractToken(String rawValue) {
    final candidate =
        Uri.tryParse(rawValue)?.pathSegments.lastOrNull ?? rawValue.trim();
    final uuid = RegExp(
      r'^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$',
    );
    return uuid.hasMatch(candidate) ? candidate : null;
  }
}

class _ScannerError extends StatelessWidget {
  const _ScannerError({required this.message});

  final String message;

  @override
  Widget build(BuildContext context) => ColoredBox(
        color: Colors.black,
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(AppSpacing.s24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                const Icon(
                  Icons.no_photography_outlined,
                  size: 44,
                  color: Colors.white,
                ),
                const SizedBox(height: AppSpacing.s12),
                Text(
                  message,
                  style: AppTypography.body14(color: Colors.white),
                  textAlign: TextAlign.center,
                ),
              ],
            ),
          ),
        ),
      );
}
