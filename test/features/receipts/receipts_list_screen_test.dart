import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:go_router/go_router.dart';
import 'package:pharmacy/core/network/api_exception.dart';
import 'package:pharmacy/features/auth/application/auth_controller.dart';
import 'package:pharmacy/features/auth/domain/user.dart';
import 'package:pharmacy/features/receipts/application/receipts_controller.dart';
import 'package:pharmacy/features/receipts/data/receipt_repository.dart';
import 'package:pharmacy/features/receipts/presentation/receipts_list_screen.dart';

/// П.6: при 401/«сессия истекла» экран «Мои чеки» должен показывать дружелюбное
/// «Сессия истекла» + кнопку входа (а не пугающую «Ошибку сервера»), и тап по
/// кнопке должен чистить сессию и уводить на экран входа.

/// Прокачивает экран чеков, у которого history-провайдер падает с [error].
/// Использует MaterialApp.router с минимальным GoRouter (/home + /welcome),
/// чтобы кнопка входа могла вызвать GoRouter.of(context).go('/welcome').
Future<GoRouter> _pumpWithError(
  WidgetTester tester,
  Object error, {
  User? user,
}) async {
  final router = GoRouter(
    initialLocation: '/home',
    routes: [
      GoRoute(
        path: '/home',
        builder: (_, __) => const ReceiptsListScreen(),
      ),
      GoRoute(
        path: '/welcome',
        builder: (_, __) => const Scaffold(body: Text('WELCOME_SCREEN')),
      ),
    ],
  );

  await tester.pumpWidget(
    ProviderScope(
      overrides: [
        // История чеков падает с переданной ошибкой.
        receiptListProvider.overrideWith((ref) => Stream<List<Receipt>>.error(error)),
        // Залогиненный пользователь (если задан) — чтобы logout было что чистить.
        if (user != null)
          currentUserProvider.overrideWith(() => _SeededUserNotifier(user)),
      ],
      child: MaterialApp.router(routerConfig: router),
    ),
  );
  await tester.pumpAndSettle();
  return router;
}

/// Notifier, который стартует с уже залогиненным пользователем.
class _SeededUserNotifier extends CurrentUserNotifier {
  _SeededUserNotifier(this._seed);
  final User _seed;
  @override
  User? build() => _seed;
}

const _user = User(
  fio: 'Иван Иванов',
  phone: '+7 (700) 123-45-67',
  iin: '900101300123',
);

void main() {
  testWidgets('401 (statusCode) → «Сессия истекла» + кнопка входа, без «Ошибки сервера»',
      (tester) async {
    await _pumpWithError(
      tester,
      const ApiException(message: 'Сессия истекла. Войдите снова.', statusCode: 401),
    );

    expect(find.text('Сессия истекла'), findsOneWidget);
    expect(find.text('Войти снова'), findsOneWidget);
    // Старого пугающего текста быть не должно.
    expect(find.text('Ошибка сервера'), findsNothing);
  });

  testWidgets('UNAUTHORIZED (code, пустой statusCode) → «Сессия истекла»', (tester) async {
    await _pumpWithError(
      tester,
      const ApiException(message: 'Требуется вход', code: 'UNAUTHORIZED'),
    );
    expect(find.text('Сессия истекла'), findsOneWidget);
    expect(find.text('Войти снова'), findsOneWidget);
  });

  testWidgets('тап «Войти снова» чистит сессию и уводит на /welcome', (tester) async {
    await _pumpWithError(
      tester,
      const ApiException(message: 'Сессия истекла', code: 'UNAUTHORIZED', statusCode: 401),
      user: _user,
    );

    await tester.tap(find.text('Войти снова'));
    await tester.pumpAndSettle();

    // Ушли на экран входа.
    expect(find.text('WELCOME_SCREEN'), findsOneWidget);
  });

  testWidgets('403/FORBIDDEN → «Доступ запрещён»', (tester) async {
    await _pumpWithError(
      tester,
      const ApiException(message: 'Нет прав', code: 'FORBIDDEN', statusCode: 403),
    );
    expect(find.text('Доступ запрещён'), findsOneWidget);
    expect(find.text('Войти снова'), findsOneWidget);
  });

  testWidgets('прочая ошибка бэка → его message, есть «Повторить» (старое поведение)',
      (tester) async {
    await _pumpWithError(
      tester,
      const ApiException(message: 'Сервис недоступен', code: 'INTERNAL', statusCode: 500),
    );
    // Показываем сообщение бэка как есть + retry.
    expect(find.text('Сервис недоступен'), findsOneWidget);
    expect(find.text('Повторить'), findsOneWidget);
    // Это НЕ сессия истекла.
    expect(find.text('Сессия истекла'), findsNothing);
  });
}
