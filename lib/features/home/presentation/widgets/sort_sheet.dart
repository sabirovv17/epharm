import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/app_colors.dart';
import '../../application/home_controller.dart';

/// Bottom-sheet выбора сортировки. Открывается из круглой кнопки Sort (↕).
///
/// Дизайн (см. референс-скриншот):
/// • Заголовок «Сортировка» 22/800 ink-900, выровнен слева.
/// • Список из 4 опций (см. `SortOption`).
/// • Каждая строка: текст слева, radio-кружок справа (24×24 ring 2px).
/// • Между строками тонкий divider `paperInput`.
/// • Активная строка: текст w800, radio-кружок зелёный ring + зелёная заливка
///   ø10 внутри.
Future<void> showSortSheet(BuildContext context) {
  return showModalBottomSheet(
    context: context,
    backgroundColor: Colors.white,
    isScrollControlled: false,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
    ),
    builder: (_) => const _SortSheet(),
  );
}

class _SortSheet extends ConsumerWidget {
  const _SortSheet();

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final selected = ref.watch(homeSortProvider);
    final options = CatalogSort.values;

    return SafeArea(
      top: false,
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          const SizedBox(height: 8),
          // Handle bar.
          Container(
            width: 36,
            height: 4,
            decoration: BoxDecoration(
              color: AppColors.ink300,
              borderRadius: BorderRadius.circular(2),
            ),
          ),
          const SizedBox(height: 20),

          // Title — выравнивание по левому краю, как на скрине.
          const Padding(
            padding: EdgeInsets.symmetric(horizontal: 20),
            child: Align(
              alignment: Alignment.centerLeft,
              child: Text(
                'Сортировка',
                style: TextStyle(
                  fontFamily: 'Manrope',
                  fontFamilyFallback: ['Roboto', 'sans-serif'],
                  fontSize: 24,
                  fontWeight: FontWeight.w800,
                  color: AppColors.ink900,
                  height: 1.15,
                ),
              ),
            ),
          ),
          const SizedBox(height: 8),

          // Список опций с разделителями.
          for (var i = 0; i < options.length; i++) ...[
            _SortRow(
              option: options[i],
              selected: options[i] == selected,
              onTap: () {
                ref.read(homeSortProvider.notifier).set(options[i]);
                Navigator.of(context).pop();
              },
            ),
            if (i < options.length - 1)
              const Padding(
                padding: EdgeInsets.symmetric(horizontal: 20),
                child: Divider(
                  height: 1,
                  thickness: 1,
                  color: AppColors.paperInput,
                ),
              ),
          ],
          const SizedBox(height: 12),
        ],
      ),
    );
  }
}

class _SortRow extends StatelessWidget {
  const _SortRow({
    required this.option,
    required this.selected,
    required this.onTap,
  });

  final CatalogSort option;
  final bool selected;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 18),
        child: Row(
          children: [
            Expanded(
              child: Text(
                option.label,
                style: TextStyle(
                  fontFamily: 'Manrope',
                  fontFamilyFallback: const ['Roboto', 'sans-serif'],
                  fontSize: 16,
                  fontWeight: selected ? FontWeight.w800 : FontWeight.w800,
                  color: AppColors.ink900,
                  height: 1.25,
                ),
              ),
            ),
            // Radio: 24×24 ring 2px. Активный — зелёный ring + 10×10 fill внутри.
            Container(
              width: 24,
              height: 24,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                border: Border.all(
                  color: selected ? AppColors.brandGreen600 : AppColors.ink300,
                  width: 2,
                ),
              ),
              child: selected
                  ? Container(
                      width: 10,
                      height: 10,
                      decoration: const BoxDecoration(
                        color: AppColors.brandGreen600,
                        shape: BoxShape.circle,
                      ),
                    )
                  : null,
            ),
          ],
        ),
      ),
    );
  }
}
