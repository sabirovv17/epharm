import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../../../core/theme/app_colors.dart';
import '../../../catalog/application/catalog_controller.dart';
import '../../../catalog/data/catalog_models.dart';
import '../../../promotions/application/promotions_controller.dart';
import '../../application/home_controller.dart';

/// Узел дерева категорий (по имени — выбор фильтра именованный, см. selectedCategories).
class CategoryNode {
  CategoryNode(this.name, this.children);
  final String name;
  final List<CategoryNode> children;
}

/// Строит дерево из плоских [MobileCategory] (parentId), оставляя только ветки,
/// в которых есть актуальная категория (имя ∈ [relevant]) — её предки сохраняются.
/// Так дерево не разрастается до всех ~640 категорий Medusa, а показывает релевантные.
List<CategoryNode> buildPrunedTree(
  List<MobileCategory> all,
  Set<String> relevant,
) {
  final childrenOf = <String?, List<MobileCategory>>{};
  for (final c in all) {
    childrenOf.putIfAbsent(c.parentId, () => []).add(c);
  }
  // id есть у всех; корни — те, чей parentId null или отсутствует среди id.
  final ids = all.map((c) => c.id).toSet();

  CategoryNode? build(MobileCategory c) {
    final kids = (childrenOf[c.id] ?? const <MobileCategory>[])
        .map(build)
        .whereType<CategoryNode>()
        .toList()
      ..sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
    final isRelevant = relevant.contains(c.name.trim());
    if (!isRelevant && kids.isEmpty) return null; // ветка без релевантных — отбрасываем
    return CategoryNode(c.name.trim(), kids);
  }

  final roots = all.where((c) => c.parentId == null || !ids.contains(c.parentId));
  return roots
      .map(build)
      .whereType<CategoryNode>()
      .toList()
    ..sort((a, b) => a.name.toLowerCase().compareTo(b.name.toLowerCase()));
}

/// Bottom-sheet выбора категорий каталога. Открывается из chip «Категории».
///
/// Список — категории из пула промо-акций ([promoCategoriesProvider]),
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
    final all = ref.watch(promoCategoriesProvider);
    final visible = all
        .where((c) => c.toLowerCase().contains(_query.trim().toLowerCase()))
        .toList();

    // Дерево категорий (ДОП.5): иерархия из каталога, обрезанная до релевантных
    // (промо-пул) веток. При поиске или пока дерево не загружено — плоский список.
    final tree = ref.watch(catalogCategoriesProvider).maybeWhen(
          data: (cats) => buildPrunedTree(cats, all.toSet()),
          orElse: () => const <CategoryNode>[],
        );
    final showTree = _query.trim().isEmpty && tree.isNotEmpty;

    void toggle(String name) => setState(() {
          if (_localSelection.contains(name)) {
            _localSelection.remove(name);
          } else {
            _localSelection.add(name);
          }
        });

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
                      // Сброс сразу применяем к провайдеру: иначе при закрытии
                      // листа свайпом (без «Применить») фильтр оставался — баг
                      // «иногда не сбрасывается».
                      onPressed: () {
                        setState(_localSelection.clear);
                        ref.read(selectedCategoriesProvider.notifier).clear();
                      },
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
              child: showTree
                  ? ListView(
                      padding: const EdgeInsets.only(top: 4, bottom: 8),
                      children: [
                        for (final n in tree)
                          _TreeNodeTile(
                            node: n,
                            depth: 0,
                            selected: _localSelection,
                            onToggle: toggle,
                          ),
                      ],
                    )
                  : visible.isEmpty
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
                            return _CategoryRow(
                              name: c,
                              checked: _localSelection.contains(c),
                              onTap: () => toggle(c),
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
            _CheckBox(checked: checked),
          ],
        ),
      ),
    );
  }
}

/// Квадратный чекбокс 24×24 (зелёный при выборе). Общий для списка и дерева.
class _CheckBox extends StatelessWidget {
  const _CheckBox({required this.checked});
  final bool checked;

  @override
  Widget build(BuildContext context) {
    return Container(
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
    );
  }
}

/// Узел дерева категорий: строка (отступ по глубине) с чекбоксом выбора (по имени)
/// и шевроном раскрытия, если есть подкатегории. Тап по строке — выбор; по шеврону —
/// раскрытие/сворачивание.
class _TreeNodeTile extends StatefulWidget {
  const _TreeNodeTile({
    required this.node,
    required this.depth,
    required this.selected,
    required this.onToggle,
  });

  final CategoryNode node;
  final int depth;
  final Set<String> selected;
  final void Function(String name) onToggle;

  @override
  State<_TreeNodeTile> createState() => _TreeNodeTileState();
}

class _TreeNodeTileState extends State<_TreeNodeTile> {
  bool _expanded = false;

  @override
  Widget build(BuildContext context) {
    final n = widget.node;
    final hasKids = n.children.isNotEmpty;
    final checked = widget.selected.contains(n.name);

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        InkWell(
          onTap: () => widget.onToggle(n.name),
          child: Padding(
            padding: EdgeInsets.only(
              left: 20.0 + widget.depth * 16,
              right: 20,
              top: 12,
              bottom: 12,
            ),
            child: Row(
              children: [
                if (hasKids)
                  GestureDetector(
                    behavior: HitTestBehavior.opaque,
                    onTap: () => setState(() => _expanded = !_expanded),
                    child: Padding(
                      padding: const EdgeInsets.only(right: 6),
                      child: Icon(
                        _expanded
                            ? Icons.keyboard_arrow_down_rounded
                            : Icons.keyboard_arrow_right_rounded,
                        size: 22,
                        color: AppColors.ink500,
                      ),
                    ),
                  )
                else
                  const SizedBox(width: 28),
                Expanded(
                  child: Text(
                    n.name,
                    style: TextStyle(
                      fontFamily: 'Manrope',
                      fontFamilyFallback: const ['Roboto', 'sans-serif'],
                      fontSize: 16,
                      fontWeight: checked ? FontWeight.w800 : FontWeight.w700,
                      color: AppColors.ink900,
                    ),
                  ),
                ),
                _CheckBox(checked: checked),
              ],
            ),
          ),
        ),
        if (hasKids && _expanded)
          for (final c in n.children)
            _TreeNodeTile(
              node: c,
              depth: widget.depth + 1,
              selected: widget.selected,
              onToggle: widget.onToggle,
            ),
      ],
    );
  }
}
