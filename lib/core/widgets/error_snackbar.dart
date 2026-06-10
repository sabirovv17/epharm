import 'package:flutter/material.dart';

import '../network/api_exception.dart';

/// Человекочитаемое сообщение из любой ошибки. `ApiException` уже несёт
/// готовый текст (сетевой фолбэк или message от бэкенда); для прочего —
/// нейтральный фолбэк, чтобы наружу не утёк технический stack.
String messageFromError(Object error) =>
    error is ApiException ? error.message : 'Что-то пошло не так. Попробуйте ещё раз.';

/// Готовый красный снэкбар с текстом ошибки. Вынесен отдельно, чтобы его можно
/// было показать через заранее захваченный `ScaffoldMessengerState` (когда исходный
/// контекст уже отмонтирован — напр. после закрытия bottom-sheet перед async-операцией).
SnackBar buildErrorSnackBar(Object error) => SnackBar(
      content: Text(
        messageFromError(error),
        style: const TextStyle(
          fontFamily: 'Manrope',
          fontFamilyFallback: ['Roboto', 'sans-serif'],
          fontWeight: FontWeight.w700,
          color: Colors.white,
        ),
      ),
      backgroundColor: const Color(0xFFEF4444),
      behavior: SnackBarBehavior.floating,
    );

/// Показать ошибку пользователю плавающим красным снэкбаром.
///
/// Безопасно вызывать из async-обработчиков: если контекст уже отмонтирован
/// (`ScaffoldMessenger` недоступен) — тихо выходим, не роняя ошибку наружу.
void showErrorSnackBar(BuildContext context, Object error) {
  ScaffoldMessenger.maybeOf(context)
    ?..clearSnackBars()
    ..showSnackBar(buildErrorSnackBar(error));
}
