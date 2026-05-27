import 'package:flutter/material.dart';

import '../theme/app_colors.dart';
import '../theme/app_radii.dart';
import '../theme/app_shadows.dart';
import '../theme/app_typography.dart';

/// Search-инпут (§6.2). **Белая** карточка с border и `shadow/card` —
/// раньше использовали `paper/input` (#F2F4F8), который почти не отличался от
/// канвасa `paper/DEFAULT` (#F4F6FA), поэтому строка поиска визуально сливалась
/// с фоном. Сейчас явная белая поверхность с 1 px бордюром `ink/300 @ 30%` и
/// мягкой тенью — читается как полноценный input.
class SearchInput extends StatelessWidget {
  const SearchInput({
    super.key,
    this.hint = 'Поиск',
    this.controller,
    this.onChanged,
  });

  final String hint;
  final TextEditingController? controller;
  final ValueChanged<String>? onChanged;

  @override
  Widget build(BuildContext context) {
    return Container(
      height: 56,
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: AppRadii.brMd,
        border: Border.all(
          color: AppColors.ink300.withValues(alpha: 0.5),
          width: 1,
        ),
        boxShadow: AppShadows.card,
      ),
      child: Row(
        children: [
          const SizedBox(width: 18),
          const Icon(Icons.search, size: 22, color: AppColors.ink500),
          const SizedBox(width: 12),
          Expanded(
            child: TextField(
              controller: controller,
              onChanged: onChanged,
              // Cyrillic-friendly: отключаем autocorrect/suggestions Android IME
              // (Latin-словарь убивает не-латинские символы).
              autocorrect: false,
              enableSuggestions: false,
              smartDashesType: SmartDashesType.disabled,
              smartQuotesType: SmartQuotesType.disabled,
              style: AppTypography.body16(color: AppColors.ink900).copyWith(
                fontFamilyFallback: const ['Roboto', 'sans-serif'],
              ),
              decoration: InputDecoration(
                filled: false,
                border: InputBorder.none,
                enabledBorder: InputBorder.none,
                focusedBorder: InputBorder.none,
                contentPadding: EdgeInsets.zero,
                hintText: hint,
                hintStyle:
                    AppTypography.body16(color: AppColors.ink400).copyWith(
                  fontFamilyFallback: const ['Roboto', 'sans-serif'],
                ),
              ),
            ),
          ),
          const SizedBox(width: 16),
        ],
      ),
    );
  }
}
