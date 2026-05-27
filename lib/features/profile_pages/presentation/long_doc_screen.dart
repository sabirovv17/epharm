import 'package:flutter/material.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_shadows.dart';
import '../data/doc_block.dart';
import 'profile_page_scaffold.dart';

/// Скаффолд длинного юр-документа: заголовок из блока `h1`, остальной контент
/// в виде одной белой карточки с h2/p/li/def-блоками.
///
/// См. `_reference/profile-pages/long-doc.jsx`.
class LongDocScreen extends StatelessWidget {
  const LongDocScreen({super.key, required this.blocks});

  final List<DocBlock> blocks;

  @override
  Widget build(BuildContext context) {
    final h1 = blocks.firstWhere(
      (b) => b.type == 'h1',
      orElse: () => const DocBlock('h1', ''),
    );
    final rest = blocks.where((b) => b.type != 'h1').toList();

    return ProfilePageScaffold(
      title: h1.text,
      titleSize: 24,
      child: ListView(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 40),
        children: [
          Container(
            padding: const EdgeInsets.fromLTRB(20, 24, 20, 24),
            decoration: BoxDecoration(
              color: Colors.white,
              borderRadius: BorderRadius.circular(16),
              boxShadow: AppShadows.card,
            ),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: rest.map((b) => _DocBlockView(block: b)).toList(),
            ),
          ),
          const SizedBox(height: 20),
          const Text(
            'ТОО «Epharm» · БИН 230440007098 · Республика Казахстан, '
            'г. Астана, ул. Дінмұхамед Қонаев, 12/1',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontFamily: 'Manrope',
              fontFamilyFallback: ['Roboto', 'sans-serif'],
              fontSize: 12,
              fontWeight: FontWeight.w700,
              color: AppColors.ink400,
              height: 1.4,
            ),
          ),
        ],
      ),
    );
  }
}

class _DocBlockView extends StatelessWidget {
  const _DocBlockView({required this.block});
  final DocBlock block;

  static const _bodyStyle = TextStyle(
    fontFamily: 'Manrope',
    fontFamilyFallback: ['Roboto', 'sans-serif'],
    fontSize: 14,
    fontWeight: FontWeight.w700,
    color: AppColors.ink900,
    height: 1.55,
  );

  @override
  Widget build(BuildContext context) {
    if (block.type == 'h2') {
      return Padding(
        padding: const EdgeInsets.fromLTRB(0, 24, 0, 12),
        child: Text(
          block.text,
          style: const TextStyle(
            fontFamily: 'Manrope',
            fontFamilyFallback: ['Roboto', 'sans-serif'],
            fontSize: 15,
            // Section heading (uppercase): keep w800 per design-tokens §6.11.
            fontWeight: FontWeight.w800,
            color: AppColors.ink900,
            letterSpacing: 0.3,
            height: 1.3,
          ),
        ),
      );
    }
    if (block.type == 'li') {
      return Padding(
        padding: const EdgeInsets.only(bottom: 10),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Container(
              margin: const EdgeInsets.only(top: 8),
              width: 5,
              height: 5,
              decoration: const BoxDecoration(
                color: AppColors.ink900,
                shape: BoxShape.circle,
              ),
            ),
            const SizedBox(width: 12),
            Expanded(child: Text(block.text, style: _bodyStyle)),
          ],
        ),
      );
    }
    if (block.type == 'def') {
      // Split on first " – " or " - ".
      final m = RegExp(r'^(.+?)\s[–-]\s(.+)$').firstMatch(block.text);
      if (m != null) {
        return Padding(
          padding: const EdgeInsets.only(bottom: 12),
          child: Text.rich(
            TextSpan(
              children: [
                TextSpan(
                  text: m.group(1),
                  style: _bodyStyle.copyWith(fontWeight: FontWeight.w800),
                ),
                TextSpan(
                  text: ' — ',
                  style: _bodyStyle.copyWith(color: AppColors.ink700),
                ),
                TextSpan(text: m.group(2), style: _bodyStyle),
              ],
            ),
          ),
        );
      }
      return Padding(
        padding: const EdgeInsets.only(bottom: 12),
        child: Text(block.text, style: _bodyStyle),
      );
    }
    // default: paragraph.
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Text(block.text, style: _bodyStyle),
    );
  }
}
