import 'dart:async';

import 'package:camera/camera.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../../../core/theme/app_colors.dart';

/// Реальный экран камеры. Открывает превью первой задней камеры устройства,
/// показывает dashed viewfinder под формат чека и большую shutter-кнопку.
///
/// При нажатии shutter — `takePicture()`, возвращает путь к файлу через
/// `Navigator.pop(String path)`.
class CameraScreen extends StatefulWidget {
  const CameraScreen({super.key});

  @override
  State<CameraScreen> createState() => _CameraScreenState();
}

class _CameraScreenState extends State<CameraScreen>
    with WidgetsBindingObserver {
  CameraController? _controller;
  List<CameraDescription>? _cameras;
  int _cameraIndex = 0;
  bool _initializing = true;
  String? _error;
  bool _capturing = false;
  FlashMode _flash = FlashMode.off;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _initCamera();
  }

  Future<void> _initCamera() async {
    try {
      _cameras = await availableCameras();
      if (_cameras == null || _cameras!.isEmpty) {
        setState(() {
          _initializing = false;
          _error = 'Камера не найдена на устройстве';
        });
        return;
      }
      // Берём первую заднюю; если нет — первую любую.
      final back = _cameras!.firstWhere(
        (c) => c.lensDirection == CameraLensDirection.back,
        orElse: () => _cameras!.first,
      );
      _cameraIndex = _cameras!.indexOf(back);
      await _bind(_cameras![_cameraIndex]);
    } on CameraException catch (e) {
      setState(() {
        _initializing = false;
        _error = 'Не удалось открыть камеру: ${e.description ?? e.code}';
      });
    } catch (e) {
      setState(() {
        _initializing = false;
        _error = 'Ошибка камеры: $e';
      });
    }
  }

  Future<void> _bind(CameraDescription cam) async {
    await _controller?.dispose();
    final ctrl = CameraController(
      cam,
      ResolutionPreset.high,
      enableAudio: false,
      imageFormatGroup: ImageFormatGroup.jpeg,
    );
    _controller = ctrl;
    await ctrl.initialize();
    if (!mounted) return;
    setState(() => _initializing = false);
  }

  Future<void> _switchCamera() async {
    if (_cameras == null || _cameras!.length < 2) return;
    setState(() => _initializing = true);
    _cameraIndex = (_cameraIndex + 1) % _cameras!.length;
    await _bind(_cameras![_cameraIndex]);
  }

  Future<void> _toggleFlash() async {
    if (_controller == null) return;
    final next = switch (_flash) {
      FlashMode.off => FlashMode.auto,
      FlashMode.auto => FlashMode.always,
      FlashMode.always => FlashMode.off,
      FlashMode.torch => FlashMode.off,
    };
    try {
      await _controller!.setFlashMode(next);
      setState(() => _flash = next);
    } catch (_) {/* не все симуляторы поддерживают */}
  }

  Future<void> _capture() async {
    final ctrl = _controller;
    if (ctrl == null || !ctrl.value.isInitialized || _capturing) return;
    setState(() => _capturing = true);
    try {
      final file = await ctrl.takePicture();
      HapticFeedback.lightImpact();
      if (!mounted) return;
      Navigator.of(context).pop(file.path);
    } catch (e) {
      setState(() => _capturing = false);
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(content: Text('Не удалось снять фото: $e')),
      );
    }
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState s) {
    final ctrl = _controller;
    if (ctrl == null || !ctrl.value.isInitialized) return;
    if (s == AppLifecycleState.inactive) {
      ctrl.dispose();
    } else if (s == AppLifecycleState.resumed) {
      _bind(_cameras![_cameraIndex]);
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _controller?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: SystemUiOverlayStyle.light,
      child: Scaffold(
        backgroundColor: Colors.black,
        body: SafeArea(
          child: Stack(
            children: [
              _buildPreview(),
              Positioned(
                top: 0,
                left: 0,
                right: 0,
                child: _TopBar(
                  onClose: () => Navigator.of(context).pop(),
                  flash: _flash,
                  onFlash: _toggleFlash,
                ),
              ),
              Positioned(
                bottom: 0,
                left: 0,
                right: 0,
                child: _BottomBar(
                  busy: _capturing,
                  onShutter: _capture,
                  onSwitch: _switchCamera,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildPreview() {
    if (_initializing) {
      return const Center(
        child: CircularProgressIndicator(color: Colors.white),
      );
    }
    if (_error != null) {
      return Padding(
        padding: const EdgeInsets.all(24),
        child: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              const Icon(Icons.no_photography_outlined,
                  size: 56, color: Colors.white70),
              const SizedBox(height: 12),
              Text(
                _error!,
                textAlign: TextAlign.center,
                style: const TextStyle(
                  fontFamily: 'Manrope',
                  fontFamilyFallback: ['Roboto', 'sans-serif'],
                  fontSize: 15,
                  fontWeight: FontWeight.w800,
                  color: Colors.white,
                ),
              ),
            ],
          ),
        ),
      );
    }
    final ctrl = _controller;
    if (ctrl == null || !ctrl.value.isInitialized) {
      return const SizedBox.shrink();
    }
    return Stack(
      fit: StackFit.expand,
      children: [
        // Camera preview — обёрнут в FittedBox чтобы пропорционально
        // заполнить экран без растяжения.
        FittedBox(
          fit: BoxFit.cover,
          child: SizedBox(
            width: ctrl.value.previewSize?.height ?? 1,
            height: ctrl.value.previewSize?.width ?? 1,
            child: CameraPreview(ctrl),
          ),
        ),
        // Затемнение по краям + dashed viewfinder.
        CustomPaint(
          painter: _ViewfinderPainter(),
        ),
        const Center(
          child: Padding(
            padding: EdgeInsets.symmetric(horizontal: 32),
            child: Padding(
              padding: EdgeInsets.only(top: 120),
              child: Align(
                alignment: Alignment.topCenter,
                child: Text(
                  'Поместите чек в рамку',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 14,
                    fontWeight: FontWeight.w800,
                    color: Colors.white,
                    height: 1.3,
                  ),
                ),
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _TopBar extends StatelessWidget {
  const _TopBar({
    required this.onClose,
    required this.flash,
    required this.onFlash,
  });
  final VoidCallback onClose;
  final FlashMode flash;
  final VoidCallback onFlash;

  IconData get _flashIcon => switch (flash) {
        FlashMode.off => Icons.flash_off_rounded,
        FlashMode.auto => Icons.flash_auto_rounded,
        FlashMode.always => Icons.flash_on_rounded,
        FlashMode.torch => Icons.flashlight_on_rounded,
      };

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(12, 8, 12, 0),
      child: Row(
        children: [
          _Circle(icon: Icons.close_rounded, onTap: onClose),
          const Spacer(),
          const Text(
            'Фото чека',
            style: TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 17,
              fontWeight: FontWeight.w800,
              color: Colors.white,
            ),
          ),
          const Spacer(),
          _Circle(icon: _flashIcon, onTap: onFlash),
        ],
      ),
    );
  }
}

class _BottomBar extends StatelessWidget {
  const _BottomBar({
    required this.busy,
    required this.onShutter,
    required this.onSwitch,
  });
  final bool busy;
  final VoidCallback onShutter;
  final VoidCallback onSwitch;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.fromLTRB(20, 16, 20, 24),
      decoration: const BoxDecoration(
        gradient: LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [Colors.transparent, Colors.black87],
        ),
      ),
      child: Row(
        children: [
          const SizedBox(width: 48),
          const Spacer(),
          _ShutterButton(busy: busy, onTap: onShutter),
          const Spacer(),
          _Circle(
            icon: Icons.cameraswitch_outlined,
            onTap: onSwitch,
            size: 48,
          ),
        ],
      ),
    );
  }
}

class _ShutterButton extends StatelessWidget {
  const _ShutterButton({required this.busy, required this.onTap});
  final bool busy;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return GestureDetector(
      onTap: busy ? null : onTap,
      child: AnimatedScale(
        scale: busy ? 0.9 : 1,
        duration: const Duration(milliseconds: 100),
        child: Container(
          width: 76,
          height: 76,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: Colors.white.withValues(alpha: 0.2),
            border: Border.all(color: Colors.white, width: 4),
          ),
          child: Container(
            decoration: BoxDecoration(
              shape: BoxShape.circle,
              color: busy ? AppColors.brandGreen600 : Colors.white,
            ),
          ),
        ),
      ),
    );
  }
}

class _Circle extends StatelessWidget {
  const _Circle({required this.icon, required this.onTap, this.size = 44});
  final IconData icon;
  final VoidCallback onTap;
  final double size;

  @override
  Widget build(BuildContext context) {
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(99),
        child: Container(
          width: size,
          height: size,
          alignment: Alignment.center,
          decoration: BoxDecoration(
            color: Colors.black.withValues(alpha: 0.45),
            shape: BoxShape.circle,
          ),
          child: Icon(icon, color: Colors.white, size: size * 0.5),
        ),
      ),
    );
  }
}

/// Рисует затемнение по краям + dashed-рамку посередине под чек (вертикальный).
class _ViewfinderPainter extends CustomPainter {
  @override
  void paint(Canvas canvas, Size size) {
    final reticleWidth = size.width - 64;
    final reticleHeight = reticleWidth * 1.4;
    final left = (size.width - reticleWidth) / 2;
    final top = (size.height - reticleHeight) / 2;
    final reticle = RRect.fromRectAndRadius(
      Rect.fromLTWH(left, top, reticleWidth, reticleHeight),
      const Radius.circular(20),
    );

    // Затемнение с вырезом.
    final scrim = Path()..addRect(Offset.zero & size);
    final hole = Path()..addRRect(reticle);
    final combined =
        Path.combine(PathOperation.difference, scrim, hole);
    canvas.drawPath(combined, Paint()..color = Colors.black.withValues(alpha: 0.4));

    // Dashed белая рамка.
    final stroke = Paint()
      ..color = Colors.white
      ..strokeWidth = 2
      ..style = PaintingStyle.stroke;
    final path = Path()..addRRect(reticle);
    const dash = 8.0;
    const gap = 6.0;
    for (final m in path.computeMetrics()) {
      double dist = 0;
      while (dist < m.length) {
        canvas.drawPath(m.extractPath(dist, dist + dash), stroke);
        dist += dash + gap;
      }
    }
  }

  @override
  bool shouldRepaint(_ViewfinderPainter old) => false;
}
