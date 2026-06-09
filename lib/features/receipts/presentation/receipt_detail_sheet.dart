import 'dart:io';

import 'package:flutter/material.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_radii.dart';
import '../../../core/theme/app_shadows.dart';
import '../data/receipt_repository.dart';

/// Bottom-sheet с содержимым отправленного чека. Открывается тапом по строке в
/// истории чеков. Показывает то, что фармацевт отправил и что с чеком стало:
/// фото, акция/товар, ВЫБРАННАЯ АПТЕКА, сумма, бонус, статус, причина отклонения.
Future<void> showReceiptDetailSheet(BuildContext context, Receipt receipt) {
  return showModalBottomSheet(
    context: context,
    isScrollControlled: true,
    backgroundColor: Colors.transparent,
    builder: (_) => _ReceiptDetailSheet(receipt: receipt),
  );
}

class _StatusStyle {
  const _StatusStyle(this.bg, this.fg);
  final Color bg;
  final Color fg;
}

_StatusStyle _styleFor(ReceiptStatus s) => switch (s) {
      ReceiptStatus.confirmed =>
        const _StatusStyle(Color(0xFFE5F8EE), Color(0xFF0F8F55)),
      ReceiptStatus.inReview =>
        const _StatusStyle(Color(0xFFFFF1D6), Color(0xFFB8740E)),
      ReceiptStatus.awaitingReceipt =>
        const _StatusStyle(Color(0xFFEFF1F5), Color(0xFF5A6173)),
      ReceiptStatus.rejected =>
        const _StatusStyle(Color(0xFFFFE6E5), Color(0xFFC0392B)),
    };

String _formatKzt(int kzt) {
  final s = kzt.toString();
  final buf = StringBuffer();
  for (var i = 0; i < s.length; i++) {
    if (i > 0 && (s.length - i) % 3 == 0) buf.write(' ');
    buf.write(s[i]);
  }
  return '$buf ₸';
}

class _ReceiptDetailSheet extends StatelessWidget {
  const _ReceiptDetailSheet({required this.receipt});
  final Receipt receipt;

  @override
  Widget build(BuildContext context) {
    final st = _styleFor(receipt.status);
    return ClipRRect(
      borderRadius: const BorderRadius.vertical(top: Radius.circular(28)),
      child: Container(
        constraints: BoxConstraints(
          maxHeight: MediaQuery.of(context).size.height * 0.88,
        ),
        color: Colors.white,
        child: SafeArea(
          top: false,
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(20, 12, 20, 24),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Center(
                  child: Container(
                    width: 40,
                    height: 4,
                    decoration: BoxDecoration(
                      color: AppColors.ink300,
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                ),
                const SizedBox(height: 16),
                // Заголовок: товар/акция + статус-бейдж.
                Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Text(
                        receipt.title,
                        style: const TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 22,
                          fontWeight: FontWeight.w800,
                          color: AppColors.ink900,
                          height: 1.15,
                        ),
                      ),
                    ),
                    const SizedBox(width: 12),
                    Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 10, vertical: 6),
                      decoration: BoxDecoration(
                        color: st.bg,
                        borderRadius: BorderRadius.circular(99),
                      ),
                      child: Text(
                        receipt.status.label,
                        style: TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: const ['Roboto', 'sans-serif'],
                          fontSize: 11,
                          fontWeight: FontWeight.w800,
                          color: st.fg,
                          letterSpacing: 0.4,
                        ),
                      ),
                    ),
                  ],
                ),
                const SizedBox(height: 16),
                _ReceiptPhoto(receipt: receipt),
                const SizedBox(height: 16),
                // Аптека — главное, чего не хватало (баг-фикс).
                _DetailRow(
                  icon: Icons.storefront_outlined,
                  label: 'Аптека',
                  value: receipt.pharmacy?.isNotEmpty == true
                      ? receipt.pharmacy!
                      : 'Не указана',
                ),
                _DetailRow(
                  icon: Icons.receipt_long_outlined,
                  label: 'Сумма по чеку',
                  value: receipt.amountKzt > 0
                      ? _formatKzt(receipt.amountKzt)
                      : 'Уточняется при сверке',
                ),
                if (receipt.bonusCredited > 0)
                  _DetailRow(
                    icon: Icons.savings_outlined,
                    label: 'Бонус зачислен',
                    value: _formatKzt(receipt.bonusCredited),
                    accent: true,
                  )
                else if (receipt.bonus != null && receipt.bonus! > 0)
                  _DetailRow(
                    icon: Icons.savings_outlined,
                    label: 'Бонус к зачислению',
                    value: _formatKzt(receipt.bonus!),
                  ),
                _DetailRow(
                  icon: Icons.schedule_outlined,
                  label: 'Загружен',
                  value: receipt.dateLabel,
                ),
                if (receipt.status == ReceiptStatus.rejected &&
                    receipt.rejectedReason != null)
                  _DetailRow(
                    icon: Icons.error_outline_rounded,
                    label: 'Причина отклонения',
                    value: receipt.rejectedReason!,
                    danger: true,
                  ),
                const SizedBox(height: 8),
                _StatusHint(status: receipt.status),
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// Фото чека: API-чек → photoUrl (S3), свежий локальный → photoPath, иначе заглушка.
class _ReceiptPhoto extends StatelessWidget {
  const _ReceiptPhoto({required this.receipt});
  final Receipt receipt;

  @override
  Widget build(BuildContext context) {
    Widget? img;
    if (receipt.photoUrl != null && receipt.photoUrl!.isNotEmpty) {
      img = Image.network(
        receipt.photoUrl!,
        fit: BoxFit.cover,
        // Пока грузится — спиннер по центру (а не пустая плашка).
        loadingBuilder: (context, child, progress) =>
            progress == null ? child : const Center(child: CircularProgressIndicator(strokeWidth: 2)),
        // Битый/недоступный URL (403/404) не должен показывать красный error-box.
        errorBuilder: (_, __, ___) => const _PhotoFallback(),
      );
    } else if (receipt.photoPath != null && receipt.photoPath!.isNotEmpty) {
      final f = File(receipt.photoPath!);
      if (f.existsSync()) img = Image.file(f, fit: BoxFit.cover);
    }
    return Container(
      width: double.infinity,
      height: 180,
      decoration: BoxDecoration(
        color: AppColors.paperInput,
        borderRadius: AppRadii.brXl,
        boxShadow: AppShadows.card,
      ),
      clipBehavior: Clip.antiAlias,
      child: img ?? const _PhotoFallback(),
    );
  }
}

/// Заглушка вместо фото: нет URL/локального файла или картинка не загрузилась.
class _PhotoFallback extends StatelessWidget {
  const _PhotoFallback();
  @override
  Widget build(BuildContext context) {
    return const Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(Icons.receipt_long_outlined, size: 40, color: AppColors.ink400),
          SizedBox(height: 8),
          Text(
            'Фото чека недоступно',
            style: TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 13,
              fontWeight: FontWeight.w700,
              color: AppColors.ink400,
            ),
          ),
        ],
      ),
    );
  }
}

class _DetailRow extends StatelessWidget {
  const _DetailRow({
    required this.icon,
    required this.label,
    required this.value,
    this.accent = false,
    this.danger = false,
  });

  final IconData icon;
  final String label;
  final String value;
  final bool accent;
  final bool danger;

  @override
  Widget build(BuildContext context) {
    final valueColor = danger
        ? const Color(0xFFC0392B)
        : accent
            ? AppColors.brandGreen700
            : AppColors.ink900;
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 10),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 20, color: AppColors.ink400),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  label,
                  style: const TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: ['Roboto', 'sans-serif'],
                    fontSize: 12,
                    fontWeight: FontWeight.w800,
                    color: AppColors.ink500,
                  ),
                ),
                const SizedBox(height: 2),
                Text(
                  value,
                  maxLines: 3,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: const ['Roboto', 'sans-serif'],
                    fontSize: 16,
                    fontWeight: FontWeight.w800,
                    color: valueColor,
                    height: 1.25,
                  ),
                ),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

/// Подсказка по статусу — что происходит с чеком (сверка по логам + Excel).
class _StatusHint extends StatelessWidget {
  const _StatusHint({required this.status});
  final ReceiptStatus status;

  String get _text => switch (status) {
        ReceiptStatus.awaitingReceipt =>
          'Ждём фото чека для подтверждения покупки.',
        ReceiptStatus.inReview =>
          'Чек сверяется с логами кассы и Excel-выгрузкой. Если данные совпадут — бонус зачислится автоматически, иначе чек проверит менеджер.',
        ReceiptStatus.confirmed =>
          'Чек подтверждён. Бонус зачислен и будет выплачен в ближайшую выплату.',
        ReceiptStatus.rejected =>
          'Чек отклонён. По вопросам обратитесь в службу поддержки.',
      };

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 12),
      decoration: BoxDecoration(
        color: AppColors.paperInput.withValues(alpha: 0.7),
        borderRadius: AppRadii.brXl,
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          const Padding(
            padding: EdgeInsets.only(top: 1),
            child: Icon(Icons.info_outline, size: 18, color: AppColors.ink400),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Text(
              _text,
              style: const TextStyle(
                fontFamily: 'Manrope',
                fontFamilyFallback: ['Roboto', 'sans-serif'],
                fontSize: 13,
                fontWeight: FontWeight.w700,
                color: AppColors.ink500,
                height: 1.4,
              ),
            ),
          ),
        ],
      ),
    );
  }
}
