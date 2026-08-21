import 'package:flutter/material.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:pinput/pinput.dart';

import 'package:pharmacy/features/auth/data/auth_repository.dart';
import 'package:pharmacy/features/auth/presentation/otp_screen.dart';

void main() {
  testWidgets('OTP screen accepts the four-digit Daribar code', (tester) async {
    await tester.pumpWidget(
      const ProviderScope(
        child: MaterialApp(home: OtpScreen()),
      ),
    );
    await tester.pump();

    final input = tester.widget<Pinput>(find.byType(Pinput));
    expect(input.length, AuthRepository.otpCodeLength);
    expect(AuthRepository.otpCodeLength, 4);
    expect(AuthRepository.defaultOtpCode, hasLength(4));

    final center = tester.widget<Center>(
      find.byKey(const Key('otp-pin-center')),
    );
    expect(center.child, isA<SizedBox>());
    expect((center.child! as SizedBox).width, 216);

    await tester.pumpWidget(const SizedBox.shrink());
  });
}
