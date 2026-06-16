import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:pharmacy/app.dart';
import 'package:pharmacy/features/welcome/application/app_start_controller.dart';

void main() {
  testWidgets('App builds without crashing', (tester) async {
    await tester.pumpWidget(
      ProviderScope(
        overrides: [
          // Детерминируем старт: сразу Welcome. Иначе SplashScreen крутит
          // бесконечный CircularProgressIndicator (тикер «висит» к концу теста)
          // и лезет в секьюр-сторадж/сеть, которых в unit-тесте нет.
          appStartProvider.overrideWith((ref) async => StartDestination.welcome),
        ],
        child: const PharmacyApp(),
      ),
    );
    await tester.pumpAndSettle();
    expect(find.byType(MaterialApp), findsOneWidget);
  });
}
