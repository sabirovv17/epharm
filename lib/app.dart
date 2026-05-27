import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import 'core/router/app_router.dart';
import 'core/theme/app_theme.dart';

/// Системные шрифты-фолбэки. Нужны для глифов, которых нет в Manrope:
/// в частности — символ тенге `₸` (U+20B8). На Android Flutter не делает
/// автоматический fallback на системный шрифт для кастомных fontFamily,
/// поэтому без этого списка ₸ рендерится как тофу `□`.
const _systemFontFallback = <String>[
  'Roboto',           // Android default
  'sans-serif',       // Android generic
  'SF Pro Text',      // iOS default
  '.SF UI Text',      // iOS fallback
];

class PharmacyApp extends ConsumerWidget {
  const PharmacyApp({super.key});

  @override
  Widget build(BuildContext context, WidgetRef ref) {
    final router = ref.watch(appRouterProvider);
    return MaterialApp.router(
      title: 'Epharm',
      debugShowCheckedModeBanner: false,
      theme: AppTheme.light(),
      routerConfig: router,
      builder: (context, child) {
        // Оборачиваем дерево в DefaultTextStyle с fontFamilyFallback,
        // чтобы инлайн-стили `TextStyle(fontFamily: 'Manrope', ...)`
        // унаследовали список fallback'ов для отсутствующих глифов.
        final base = DefaultTextStyle.of(context).style;
        return DefaultTextStyle(
          style: base.copyWith(fontFamilyFallback: _systemFontFallback),
          child: child ?? const SizedBox.shrink(),
        );
      },
    );
  }
}
