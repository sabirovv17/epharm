import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

import 'package:pharmacy/app.dart';

void main() {
  testWidgets('App builds without crashing', (tester) async {
    await tester.pumpWidget(const ProviderScope(child: PharmacyApp()));
    await tester.pump();
    expect(find.byType(MaterialApp), findsOneWidget);
  });
}
