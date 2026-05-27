import 'package:flutter/material.dart';

import '../../../../core/theme/app_colors.dart';
import '../../../../core/theme/app_shadows.dart';

/// iOS-style bottom-nav: **2 таба** (Главная / Профиль) + центральный
/// круглый FAB-камера (open upload prompt sheet — то же что и pill
/// «Загрузить чек» в BalanceCard).
///
/// История изменений:
/// • Этап 1-3: 4 таба (Главная / Обучение / Чек / Профиль).
/// • Этап 4 (recipe-интеграция): убрали «Чек» → 3 таба, загрузка через pill
///   в BalanceCard.
/// • Этап 5 (текущий): убрали «Обучение» (перенесён в Profile menu), сделали
///   камеру центральным FAB как primary-action — самая частая операция
///   фармацевта это «сфоткать чек», поэтому она вынесена под большой палец.
///
/// Active tab = brand-green-600 (раньше был blue), inactive = ink/400.
class PharmaBottomNav extends StatelessWidget {
  const PharmaBottomNav({
    super.key,
    required this.currentIndex,
    required this.onTap,
    required this.onScanTap,
  });

  /// 0 = Home, 1 = Profile. FAB-камера НЕ участвует в индексации.
  final int currentIndex;
  final ValueChanged<int> onTap;

  /// Открыть upload prompt sheet — то же что и «Загрузить чек» в BalanceCard.
  final VoidCallback onScanTap;

  @override
  Widget build(BuildContext context) {
    return Container(
      decoration: BoxDecoration(
        color: Colors.white,
        boxShadow: AppShadows.navTop,
      ),
      // Раньше было top: 10, bottom: 28 — но 28px нижнего паддинга
      // плюс SafeArea-bottom (на iOS home-indicator ≈ 34px, на Android до 16px)
      // давали ощутимо пустую белую полосу под иконками. Срезали top 10→6,
      // bottom 28→8: SafeArea сам добавит индикатор-safe-area где нужно.
      padding: const EdgeInsets.only(top: 6, bottom: 8),
      child: SafeArea(
        top: false,
        child: SizedBox(
          height: 48,
          child: Stack(
            clipBehavior: Clip.none,
            alignment: Alignment.center,
            children: [
              // Ряд из 3 ячеек: [Home] [пустое место под FAB] [Profile].
              // FAB рисуется поверх центральной ячейки и приподнят на -24 px.
              Row(
                children: [
                  Expanded(
                    child: _NavTabButton(
                      activeIcon: Icons.home_rounded,
                      inactiveIcon: Icons.home_outlined,
                      label: 'Главная',
                      active: currentIndex == 0,
                      onTap: () => onTap(0),
                    ),
                  ),
                  // Пустая центральная ячейка под FAB.
                  const Expanded(child: SizedBox.shrink()),
                  Expanded(
                    child: _NavTabButton(
                      activeIcon: Icons.person_rounded,
                      inactiveIcon: Icons.person_outline,
                      label: 'Профиль',
                      active: currentIndex == 1,
                      onTap: () => onTap(1),
                    ),
                  ),
                ],
              ),
              // Центральный зелёный FAB-камера.
              Positioned(
                top: -24,
                child: _CenterScanFab(onTap: onScanTap),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// Tab-кнопка — только иконка (label убран 2026-05-26). Icon-only nav: лейблы
/// «Главная» / «Профиль» избыточны при 2-tab nav, иконки сами по себе
/// однозначны (домик / человечек). За счёт убранных лейблов поднимаем размер
/// иконок 28 → 34 px для большего визуального веса.
///
/// Активная иконка — `*_rounded` (filled, более «объёмная»). Inactive —
/// `*_rounded` тоже (раньше был outlined-вариант, но тонкие линии «пропадали»
/// рядом с большим зелёным FAB по центру; filled в обоих состояниях с
/// различием только по цвету = visually more grounded).
class _NavTabButton extends StatelessWidget {
  const _NavTabButton({
    required this.activeIcon,
    required this.inactiveIcon,
    required this.label,
    required this.active,
    required this.onTap,
  });

  /// Иконка для active-состояния (filled rounded).
  final IconData activeIcon;

  /// Иконка для inactive-состояния (outlined rounded — чуть тоньше для
  /// иерархического контраста с активной).
  final IconData inactiveIcon;

  /// Semantic label (используется только screen-reader'ом, не рендерится).
  final String label;
  final bool active;
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    final color = active ? AppColors.brandGreen600 : AppColors.ink400;
    return Semantics(
      label: label,
      button: true,
      selected: active,
      child: InkWell(
        onTap: onTap,
        child: Center(
          child: Icon(
            active ? activeIcon : inactiveIcon,
            size: 34,
            color: color,
          ),
        ),
      ),
    );
  }
}

/// Центральный зелёный FAB — открывает upload sheet (камера / галерея / QR).
/// Тот же зелёный градиент что и у CTA «Продолжить», тот же shadow/fab glow.
class _CenterScanFab extends StatelessWidget {
  const _CenterScanFab({required this.onTap});
  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      width: 64,
      height: 64,
      child: Material(
        color: Colors.transparent,
        shape: const CircleBorder(),
        clipBehavior: Clip.antiAlias,
        child: Ink(
          decoration: BoxDecoration(
            shape: BoxShape.circle,
            color: AppColors.brandGreen600,
            boxShadow: AppShadows.fab,
            // Тонкий белый ring, чтобы FAB визуально отрывался от шапки nav.
            border: Border.all(color: Colors.white, width: 4),
          ),
          child: InkWell(
            onTap: onTap,
            customBorder: const CircleBorder(),
            child: const Center(
              child: Icon(
                Icons.photo_camera_rounded,
                size: 28,
                color: Colors.white,
              ),
            ),
          ),
        ),
      ),
    );
  }
}
