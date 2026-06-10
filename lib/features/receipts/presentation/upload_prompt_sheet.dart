import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:image_picker/image_picker.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_gradients.dart';
import '../../../core/theme/app_shadows.dart';
import '../../../core/widgets/error_snackbar.dart';
import '../application/receipts_controller.dart';
import 'camera_screen.dart';
import 'receipt_review_screen.dart';

/// Bottom-sheet выбора способа загрузки чека. Открывается из CTA «Загрузить чек»
/// (в ReceiptsListScreen и в Product Detail).
///
/// 2 опции:
///   • Сделать фото → реальная CameraScreen.
///   • Из галереи → системный image_picker.
Future<void> showUploadPromptSheet(BuildContext context) {
  return showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    builder: (_) => const _UploadPromptSheet(),
  );
}

class _UploadPromptSheet extends ConsumerWidget {
  const _UploadPromptSheet();

  Future<void> _takePhoto(BuildContext context, WidgetRef ref) async {
    // Захватываем Navigator/Messenger/notifier ДО попа — после закрытия шита
    // context и ref становятся невалидными (виджет размонтируется).
    final nav = Navigator.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final notifier = ref.read(receiptDraftProvider.notifier);
    nav.pop();
    try {
      final path = await nav.push<String?>(
        MaterialPageRoute(builder: (_) => const CameraScreen()),
      );
      if (path == null) return;
      notifier.setPhoto(path);
      await nav.push(
        MaterialPageRoute(builder: (_) => const ReceiptReviewScreen()),
      );
    } catch (e) {
      // Камера недоступна / отказ в доступе — показываем ошибку, а не молчим.
      messenger
        ..clearSnackBars()
        ..showSnackBar(buildErrorSnackBar(e));
    }
  }

  Future<void> _pickFromGallery(BuildContext context, WidgetRef ref) async {
    // Захватываем Navigator/Messenger/notifier ДО попа — после закрытия шита
    // context и ref становятся невалидными (виджет размонтируется,
    // пока пользователь выбирает фото в системной галерее).
    final nav = Navigator.of(context);
    final messenger = ScaffoldMessenger.of(context);
    final notifier = ref.read(receiptDraftProvider.notifier);
    nav.pop();
    try {
      final picker = ImagePicker();
      final file = await picker.pickImage(
        source: ImageSource.gallery,
        maxWidth: 2000,
        imageQuality: 85,
      );
      if (file == null) return;
      notifier.setPhoto(file.path);
      await nav.push(
        MaterialPageRoute(builder: (_) => const ReceiptReviewScreen()),
      );
    } catch (e) {
      // Галерея недоступна / отказ в доступе — показываем ошибку.
      messenger
        ..clearSnackBars()
        ..showSnackBar(buildErrorSnackBar(e));
    }
  }

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    return ClipRRect(
      borderRadius: const BorderRadius.vertical(top: Radius.circular(28)),
      child: Container(
        decoration: const BoxDecoration(
          gradient: AppGradients.welcome, // grad-receipt зелёный
        ),
        child: SafeArea(
          top: false,
          child: Padding(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                Center(
                  child: Container(
                    width: 40,
                    height: 4,
                    decoration: BoxDecoration(
                      color: Colors.white.withValues(alpha: 0.4),
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                const Text(
                  'Загрузите фото чека',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 26,
                    fontWeight: FontWeight.w800,
                    color: Colors.white,
                    height: 1.15,
                  ),
                ),
                const SizedBox(height: 8),
                Text(
                  'Сфотографируйте или загрузите фискальный чек — '
                  'мы автоматически извлечём данные и зачислим бонус.',
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: const ['Roboto', 'sans-serif'],
                    fontSize: 14,
                    fontWeight: FontWeight.w700,
                    color: Colors.white.withValues(alpha: 0.92),
                    height: 1.4,
                  ),
                ),
                const SizedBox(height: 20),
                _OptionTile(
                  icon: Icons.photo_camera_outlined,
                  title: 'Сделать фото',
                  subtitle: 'Камера телефона',
                  onTap: () => _takePhoto(context, ref),
                  primary: true,
                ),
                const SizedBox(height: 10),
                _OptionTile(
                  icon: Icons.photo_library_outlined,
                  title: 'Выбрать из галереи',
                  subtitle: 'Готовое фото или скриншот',
                  onTap: () => _pickFromGallery(context, ref),
                ),
                const SizedBox(height: 12),
                Center(
                  child: TextButton(
                    onPressed: () => Navigator.of(context).pop(),
                    child: Text(
                      'Отмена',
                      style: TextStyle(
                        fontFamily: 'Manrope',
                        fontFamilyFallback: const ['Roboto', 'sans-serif'],
                        fontSize: 15,
                        fontWeight: FontWeight.w800,
                        color: Colors.white.withValues(alpha: 0.92),
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

class _OptionTile extends StatelessWidget {
  const _OptionTile({
    required this.icon,
    required this.title,
    required this.subtitle,
    required this.onTap,
    this.primary = false,
  });

  final IconData icon;
  final String title;
  final String subtitle;
  final VoidCallback onTap;
  final bool primary;

  @override
  Widget build(BuildContext context) {
    final bg = primary ? Colors.white : Colors.white.withValues(alpha: 0.15);
    final fg = primary ? AppColors.brandGreen600 : Colors.white;
    return Material(
      color: Colors.transparent,
      child: InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(16),
        child: Container(
          padding: const EdgeInsets.symmetric(horizontal: 16, vertical: 14),
          decoration: BoxDecoration(
            color: bg,
            borderRadius: BorderRadius.circular(16),
            boxShadow: primary ? AppShadows.fab : null,
            border: primary
                ? null
                : Border.all(
                    color: Colors.white.withValues(alpha: 0.25),
                    width: 1,
                  ),
          ),
          child: Row(
            children: [
              Container(
                width: 44,
                height: 44,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  color: primary
                      ? AppColors.brandGreen100
                      : Colors.white.withValues(alpha: 0.2),
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Icon(icon, size: 22, color: fg),
              ),
              const SizedBox(width: 12),
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      title,
                      style: TextStyle(
                        fontFamily: 'Manrope',
                        fontFamilyFallback: const ['Roboto', 'sans-serif'],
                        fontSize: 17,
                        fontWeight: FontWeight.w800,
                        color: fg,
                        height: 1.2,
                      ),
                    ),
                    Text(
                      subtitle,
                      style: TextStyle(
                        fontFamily: 'Manrope',
                        fontFamilyFallback: const ['Roboto', 'sans-serif'],
                        fontSize: 12,
                        fontWeight: FontWeight.w700,
                        color: primary
                            ? AppColors.ink500
                            : Colors.white.withValues(alpha: 0.85),
                        height: 1.3,
                      ),
                    ),
                  ],
                ),
              ),
              Icon(
                Icons.chevron_right_rounded,
                size: 22,
                color: primary
                    ? AppColors.ink400
                    : Colors.white.withValues(alpha: 0.6),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
