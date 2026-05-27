import 'package:flutter/widgets.dart';

import '../data/privacy_content.dart';
import 'long_doc_screen.dart';

/// Экран «Политика конфиденциальности». Контент в `kPrivacyBlocks`.
class PrivacyScreen extends StatelessWidget {
  const PrivacyScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const LongDocScreen(blocks: kPrivacyBlocks);
  }
}
