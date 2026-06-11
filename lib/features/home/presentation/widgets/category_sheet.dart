import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/app_colors.dart';
import '../../application/home_controller.dart';

/// Bottom-sheet выбора категорий каталога. Открывается из chip «Категории».
///
/// Список — РЕАЛЬНЫЕ категории загруженного каталога ([homeCategoriesProvider]),
/// без структурной «Сайт». Множественный выбор + поиск + «Применить»/«Сбросить».
/// Каталог наполняется постепенно — сейчас категорий мало, список растёт по мере
/// проставления категорий в PIM.
Future<void> showCategorySheet(BuildContext context) {
  return showModalBottomSheet(
    context: context,
    backgroundColor: Colors.white,
    isScrollControlled: true,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(24)),
    ),
    builder: (_) => const _CategorySheet(),
  );
}

class _CategorySheet extends ConsumerStatefulWidget {
  const _CategorySheet();

  @override
  ConsumerState<_CategorySheet> createState() => _CategorySheetState();
}

class _CategorySheetState extends ConsumerState<_CategorySheet> {
  late Set<String> _localSelection;
  String _query = '';

  @override
  void initState() {
    super.initState();
    _localSelection = {...ref.read(selectedCategoriesProvider)};
  }

  @override
  Widget build(BuildContext context) {
    final all = ref.watch(homeCategoriesProvider);
    final visible = all
        .where((c) => c.toLowerCase().contains(_query.trim().toLowerCase()))
        .toList();

    return SafeArea(
      top: false,
      child: FractionallySizedBox(
        heightFactor: 0.85,
        child: Column(
          children: [
            const SizedBox(height: 8),
            Container(
              width: 36,
              height: 4,
              decoration: BoxDecoration(
                color: AppColors.ink300,
                borderRadius: BorderRadius.circular(2),
              ),
            ),
            const SizedBox(height: 12),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: Row(
                children: [
                  const Expanded(
                    child: Text(
                      'Категории',
                      style: TextStyle(
                        fontFamily: 'Manrope',
                        fontFamilyFallback: ['Roboto', 'sans-serif'],
                        fontSize: 22,
                        fontWeight: FontWeight.w800,
                        color: AppColors.ink900,
                        height: 1.15,
                      ),
                    ),
                  ),
                  if (_localSelection.isNotEmpty)
                    TextButton(
                      onPressed: () => setState(_localSelection.clear),
                      child: const Text(
                        'Сбросить',
                        style: TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 14,
                          fontWeight: FontWeight.w800,
                          color: AppColors.brandBlue600,
                        ),
                      ),
                    ),
                ],
              ),
            ),
            const SizedBox(height: 12),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 20),
              child: Container(
                height: 44,
                padding: const EdgeInsets.symmetric(horizontal: 14),
                decoration: BoxDecoration(
                  color: AppColors.paperInput,
                  borderRadius: BorderRadius.circular(12),
                ),
                child: Row(
                  children: [
                    const Icon(Icons.search_rounded,
                        size: 20, color: AppColors.ink400),
                    const SizedBox(width: 8),
                    Expanded(
                      child: TextField(
                        onChanged: (v) => setState(() => _query = v),
                        autocorrect: false,
                        enableSuggestions: false,
                        smartDashesType: SmartDashesType.disabled,
                        smartQuotesType: SmartQuotesType.disabled,
                        decoration: const InputDecoration(
                          filled: false,
                          border: InputBorder.none,
                          enabledBorder: InputBorder.none,
                          focusedBorder: InputBorder.none,
                          contentPadding: EdgeInsets.zero,
                          hintText: 'Поиск категории',
                          hintStyle: TextStyle(
                            fontFamily: 'Manrope',
                            fontFamilyFallback: ['Roboto', 'sans-serif'],
                            fontSize: 15,
                            fontWeight: FontWeight.w700,
                            color: AppColors.ink400,
                          ),
                        ),
                        style: const TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 15,
                          fontWeight: FontWeight.w800,
                          color: AppColors.ink900,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 8),
            Expanded(
              child: visible.isEmpty
                  ? Center(
                      child: Text(
                        all.isEmpty
                            ? 'Категории появятся по мере наполнения каталога'
                            : 'Ничего не найдено',
                        textAlign: TextAlign.center,
                        style: const TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 14,
                          fontWeight: FontWeight.w700,
                          color: AppColors.ink500,
                        ),
                      ),
                    )
                  : ListView.builder(
                      padding: const EdgeInsets.only(top: 4, bottom: 8),
                      itemCount: visible.length,
                      itemBuilder: (_, i) {
                        final c = visible[i];
                        final checked = _localSelection.contains(c);
                        return _CategoryRow(
                          name: c,
                          checked: checked,
                          onTap: () => setState(() {
                            if (checked) {
                              _localSelection.remove(c);
                            } else {
                              _localSelection.add(c);
                            }
                          }),
                        );
                      },
                    ),
            ),
            Padding(
              padding: const EdgeInsets.fromLTRB(20, 12, 20, 16),
              child: Material(
                color: Colors.transparent,
                child: InkWell(
                  borderRadius: BorderRadius.circular(99),
                  onTap: () {
                    ref
                        .read(selectedCategoriesProvider.notifier)
                        .replace(_localSelection);
                    Navigator.of(context).pop();
                  },
                  child: Container(
                    height: 56,
                    alignment: Alignment.center,
                    decoration: BoxDecoration(
                      color: AppColors.brandGreen600,
                      borderRadius: BorderRadius.circular(99),
                    ),
                    child: Text(
                      _localSelection.isEmpty
                          ? 'Показать все'
                          : 'Применить (${_localSelection.length})',
                      style: const TextStyle(
                        fontFamily: 'Manrope',
                        fontFamilyFallback: ['Roboto', 'sans-serif'],
                        fontSize: 17,
                        fontWeight: FontWeight.w800,
                        color: Colors.white,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

class _CategoryRow extends StatelessWidget {
  const _CategoryRow({
    required this.name,
    required this.checked,
    required this.onTap,
  });

  final String name;
  final bool checked;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return InkWell(
      onTap: onTap,
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 20, vertical: 12),
        child: Row(
          children: [
            Expanded(
              child: Text(
                name,
                style: TextStyle(
                  fontFamily: 'Manrope',
                  fontFamilyFallback: const ['Roboto', 'sans-serif'],
                  fontSize: 16,
                  fontWeight: checked ? FontWeight.w800 : FontWeight.w700,
                  color: AppColors.ink900,
                ),
              ),
            ),
            Container(
              width: 24,
              height: 24,
              alignment: Alignment.center,
              decoration: BoxDecoration(
                color: checked ? AppColors.brandGreen600 : Colors.white,
                borderRadius: BorderRadius.circular(6),
                border: Border.all(
                  color: checked ? AppColors.brandGreen600 : AppColors.ink300,
                  width: 2,
                ),
              ),
              child: checked
                  ? const Icon(Icons.check, size: 16, color: Colors.white)
                  : null,
            ),
          ],
        ),
      ),
    );
  }
}
