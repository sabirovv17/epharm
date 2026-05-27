import 'package:flutter/material.dart';

import '../../../core/theme/app_colors.dart';
import '../data/faq_items.dart';
import 'profile_page_scaffold.dart';
import 'widgets/inline_marker_text.dart';
import 'widgets/video_instruction_card.dart';

/// Экран «Вопросы и ответы». Аккордеон: tap по строке раскрывает ответ,
/// `+` поворачивается в `×` (анимация 280мс).
///
/// См. `_reference/profile-pages/faq.jsx`.
class FaqScreen extends StatefulWidget {
  const FaqScreen({super.key});

  @override
  State<FaqScreen> createState() => _FaqScreenState();
}

class _FaqScreenState extends State<FaqScreen> {
  String? _openId;

  @override
  Widget build(BuildContext context) {
    return ProfilePageScaffold(
      title: 'Вопросы и ответы',
      child: ListView(
        padding: const EdgeInsets.fromLTRB(16, 0, 16, 40),
        children: [
          // Аккордеон вопросов.
          for (var i = 0; i < kFaqItems.length; i++) ...[
            _FaqRow(
              item: kFaqItems[i],
              open: _openId == kFaqItems[i].id,
              onTap: () => setState(() {
                _openId =
                    _openId == kFaqItems[i].id ? null : kFaqItems[i].id;
              }),
            ),
            if (i < kFaqItems.length - 1) const SizedBox(height: 8),
          ],
          // Видео-инструкция в самом низу.
          const VideoInstructionCard(
            youtubeUrl: 'https://youtu.be/2ax0jHFhM-A?si=dxRdebSNOYDiuFpa',
          ),
        ],
      ),
    );
  }
}

class _FaqRow extends StatelessWidget {
  const _FaqRow({
    required this.item,
    required this.open,
    required this.onTap,
  });

  final FaqItem item;
  final bool open;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return AnimatedContainer(
      duration: const Duration(milliseconds: 280),
      curve: Curves.easeOut,
      margin: EdgeInsets.symmetric(vertical: open ? 8 : 0),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(open ? 16 : 8),
        boxShadow: [
          BoxShadow(
            color: const Color(0x0F0F1424),
            blurRadius: open ? 12 : 0,
            offset: const Offset(0, 2),
          ),
          BoxShadow(
            color: const Color(0x0A0F1424),
            blurRadius: 0,
            spreadRadius: 1,
          ),
        ],
      ),
      child: Material(
        color: Colors.transparent,
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(open ? 16 : 8),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 18, 20, 18),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Expanded(
                      child: Text(
                        item.question,
                        style: const TextStyle(
                          fontFamily: 'Manrope',
                          fontFamilyFallback: ['Roboto', 'sans-serif'],
                          fontSize: 17,
                          fontWeight: FontWeight.w800,
                          color: AppColors.ink900,
                          height: 1.3,
                        ),
                      ),
                    ),
                    const SizedBox(width: 12),
                    _PlusToX(open: open),
                  ],
                ),
              ),
              // Answer panel (animated).
              AnimatedCrossFade(
                duration: const Duration(milliseconds: 280),
                crossFadeState:
                    open ? CrossFadeState.showSecond : CrossFadeState.showFirst,
                firstChild: const SizedBox(width: double.infinity, height: 0),
                secondChild: Padding(
                  padding: const EdgeInsets.fromLTRB(20, 0, 20, 24),
                  child: _FaqAnswer(item: item),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _PlusToX extends StatelessWidget {
  const _PlusToX({required this.open});
  final bool open;

  @override
  Widget build(BuildContext context) {
    return TweenAnimationBuilder<double>(
      tween: Tween(begin: 0, end: open ? -0.125 : 0), // -45deg = -1/8 turn
      duration: const Duration(milliseconds: 280),
      curve: Curves.easeOutCubic,
      builder: (_, t, __) => Transform.rotate(
        angle: t * 6.28318530718, // turn → radians
        child: const Icon(
          Icons.add_rounded,
          size: 26,
          color: AppColors.ink900,
        ),
      ),
    );
  }
}

class _FaqAnswer extends StatelessWidget {
  const _FaqAnswer({required this.item});
  final FaqItem item;

  static const _baseStyle = TextStyle(
    fontFamily: 'Manrope',
    fontFamilyFallback: ['Roboto', 'sans-serif'],
    fontSize: 15,
    fontWeight: FontWeight.w600,
    color: AppColors.ink900,
    height: 1.4,
  );

  @override
  Widget build(BuildContext context) {
    final blocks = <Widget>[];

    if (item.intro != null) {
      blocks.add(InlineMarkerText(text: item.intro!, baseStyle: _baseStyle));
    }
    if (item.bullets != null) {
      if (blocks.isNotEmpty) blocks.add(const SizedBox(height: 12));
      for (final b in item.bullets!) {
        blocks.add(_Bullet(text: b));
        if (b != item.bullets!.last) blocks.add(const SizedBox(height: 12));
      }
    }
    if (item.text != null) {
      if (blocks.isNotEmpty) blocks.add(const SizedBox(height: 12));
      blocks.add(InlineMarkerText(text: item.text!, baseStyle: _baseStyle));
    }
    if (item.outro != null) {
      if (blocks.isNotEmpty) blocks.add(const SizedBox(height: 12));
      blocks.add(InlineMarkerText(text: item.outro!, baseStyle: _baseStyle));
    }

    return Column(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: blocks,
    );
  }
}

class _Bullet extends StatelessWidget {
  const _Bullet({required this.text});
  final String text;

  @override
  Widget build(BuildContext context) {
    return Row(
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
        Expanded(
          child: InlineMarkerText(
            text: text,
            baseStyle: _FaqAnswer._baseStyle,
          ),
        ),
      ],
    );
  }
}
