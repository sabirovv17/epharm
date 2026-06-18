import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:go_router/go_router.dart';

import '../../../core/theme/app_colors.dart';
import '../../../core/theme/app_theme.dart';

/// Холст тёплого кремового цвета `#EFEAE2` для всех Profile-pages
/// (Claude orange/white — бренд = коралл).
/// См. `_reference/profile-pages/README.md` (общий паттерн) и
/// `_reference/design_handoff_pharmapay/design-tokens.md` §6.9.
const profilePageCanvas = Color(0xFFEFEAE2);

/// Общий скаффолд для дочерних экранов Профиля:
/// • back-стрелка слева вверху
/// • центрированный жирный заголовок (26/800)
/// • прокручиваемое тело
class ProfilePageScaffold extends StatelessWidget {
  const ProfilePageScaffold({
    super.key,
    required this.title,
    required this.child,
    this.titleSize = 26,
  });

  final String title;
  final Widget child;

  /// Размер заголовка. 26 (FAQ / Cooperation / Long-doc) или 24 для длинных названий.
  final double titleSize;

  @override
  Widget build(BuildContext context) {
    return AnnotatedRegion<SystemUiOverlayStyle>(
      value: systemUiOverlayStyleLight,
      child: Scaffold(
        backgroundColor: profilePageCanvas,
        body: SafeArea(
          bottom: false,
          child: Column(
            children: [
              // Header: back arrow.
              Padding(
                padding: const EdgeInsets.fromLTRB(12, 4, 12, 0),
                child: Align(
                  alignment: Alignment.centerLeft,
                  child: IconButton(
                    onPressed: () {
                      if (context.canPop()) {
                        context.pop();
                      } else {
                        context.go('/home');
                      }
                    },
                    icon: const Icon(
                      Icons.arrow_back_ios_new_rounded,
                      size: 24,
                      color: AppColors.ink900,
                    ),
                  ),
                ),
              ),
              // Centered bold title.
              Padding(
                padding: const EdgeInsets.fromLTRB(20, 4, 20, 20),
                child: Text(
                  title,
                  textAlign: TextAlign.center,
                  style: TextStyle(
                    fontFamily: 'Manrope',
                    fontFamilyFallback: const ['Roboto', 'sans-serif'],
                    fontSize: titleSize,
                    fontWeight: FontWeight.w800,
                    color: AppColors.ink900,
                    height: 1.15,
                  ),
                ),
              ),
              Expanded(child: child),
            ],
          ),
        ),
      ),
    );
  }
}
