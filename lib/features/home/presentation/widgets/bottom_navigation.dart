import 'package:flutter/material.dart';

import '../../../../core/theme/app_colors.dart';
import '../../../../core/theme/app_shadows.dart';
import '../../../../core/theme/app_typography.dart';

/// Primary mobile navigation shared by the pharmacist workspace.
class PharmaBottomNav extends StatelessWidget {
  const PharmaBottomNav({
    super.key,
    required this.currentIndex,
    required this.onTap,
  });

  final int currentIndex;
  final ValueChanged<int> onTap;

  static const _items =
      <({IconData icon, IconData selectedIcon, String label})>[
    (
      icon: Icons.home_outlined,
      selectedIcon: Icons.home_rounded,
      label: 'Главная'
    ),
    (
      icon: Icons.medication_outlined,
      selectedIcon: Icons.medication_rounded,
      label: 'Каталог'
    ),
    (
      icon: Icons.receipt_long_outlined,
      selectedIcon: Icons.receipt_long_rounded,
      label: 'Чеки'
    ),
    (
      icon: Icons.school_outlined,
      selectedIcon: Icons.school_rounded,
      label: 'Обучение'
    ),
    (
      icon: Icons.person_outline_rounded,
      selectedIcon: Icons.person_rounded,
      label: 'Профиль'
    ),
  ];

  @override
  Widget build(BuildContext context) => Container(
        decoration: const BoxDecoration(
          color: Colors.white,
          boxShadow: AppShadows.navTop,
          border: Border(top: BorderSide(color: AppColors.borderHairline)),
        ),
        child: SafeArea(
          top: false,
          child: SizedBox(
            height: 66,
            child: Row(
              children: List.generate(
                _items.length,
                (index) => Expanded(
                  child: _NavTabButton(
                    item: _items[index],
                    active: currentIndex == index,
                    onTap: () => onTap(index),
                  ),
                ),
              ),
            ),
          ),
        ),
      );
}

class _NavTabButton extends StatelessWidget {
  const _NavTabButton({
    required this.item,
    required this.active,
    required this.onTap,
  });

  final ({IconData icon, IconData selectedIcon, String label}) item;
  final bool active;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = active ? AppColors.brandGreen700 : AppColors.ink400;
    return Semantics(
      label: item.label,
      button: true,
      selected: active,
      child: InkWell(
        onTap: onTap,
        child: Padding(
          padding: const EdgeInsets.fromLTRB(2, 6, 2, 5),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: [
              AnimatedContainer(
                duration: const Duration(milliseconds: 180),
                width: 48,
                height: 30,
                decoration: BoxDecoration(
                  color: active ? AppColors.brandGreen100 : Colors.transparent,
                  borderRadius: BorderRadius.circular(12),
                ),
                alignment: Alignment.center,
                child: Icon(
                  active ? item.selectedIcon : item.icon,
                  size: 22,
                  color: color,
                ),
              ),
              const SizedBox(height: 2),
              Text(
                item.label,
                maxLines: 1,
                overflow: TextOverflow.clip,
                style: AppTypography.micro(color: color).copyWith(fontSize: 10),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
