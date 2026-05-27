import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:go_router/go_router.dart';

import '../../features/auth/application/auth_controller.dart';
import '../../features/auth/presentation/otp_screen.dart';
import '../../features/auth/presentation/phone_screen.dart';
import '../../features/auth/presentation/profile_form_screen.dart';
import '../../features/home/presentation/home_screen.dart';
import '../../features/profile_pages/presentation/cooperation_screen.dart';
import '../../features/profile_pages/presentation/faq_screen.dart';
import '../../features/profile_pages/presentation/instruction_screen.dart';
import '../../features/profile_pages/presentation/privacy_screen.dart';
import '../../features/profile_pages/presentation/terms_screen.dart';
import '../../features/welcome/presentation/welcome_screen.dart';

final appRouterProvider = Provider<GoRouter>((ref) {
  return GoRouter(
    initialLocation: '/welcome',
    redirect: (context, state) {
      // /home доступен всем — авторизация добровольная.
      // Если пользователь уже залогинен и попал на welcome/auth — перебрасываем в home.
      final user = ref.read(currentUserProvider);
      final loggedIn = user != null;
      final path = state.matchedLocation;
      final inAuthFlow = path.startsWith('/auth') || path == '/welcome';
      if (loggedIn && inAuthFlow) return '/home';
      return null;
    },
    routes: [
      GoRoute(
        path: '/welcome',
        builder: (_, __) => const WelcomeScreen(),
      ),
      GoRoute(
        path: '/auth/phone',
        builder: (_, __) => const PhoneScreen(),
      ),
      GoRoute(
        path: '/auth/otp',
        builder: (_, __) => const OtpScreen(),
      ),
      GoRoute(
        path: '/auth/profile',
        builder: (_, __) => const ProfileFormScreen(),
      ),
      GoRoute(
        path: '/home',
        builder: (_, __) => const HomeScreen(),
      ),
      // Profile sub-pages (помощь / о приложении).
      GoRoute(
        path: '/profile/faq',
        builder: (_, __) => const FaqScreen(),
      ),
      GoRoute(
        path: '/profile/instruction',
        builder: (_, __) => const InstructionScreen(),
      ),
      GoRoute(
        path: '/profile/cooperation',
        builder: (_, __) => const CooperationScreen(),
      ),
      GoRoute(
        path: '/profile/terms',
        builder: (_, __) => const TermsScreen(),
      ),
      GoRoute(
        path: '/profile/privacy',
        builder: (_, __) => const PrivacyScreen(),
      ),
    ],
  );
});
