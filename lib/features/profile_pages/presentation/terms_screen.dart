import 'package:flutter/widgets.dart';

import '../data/terms_content.dart';
import 'long_doc_screen.dart';

/// Экран «Пользовательское соглашение». Контент в `kTermsBlocks`.
class TermsScreen extends StatelessWidget {
  const TermsScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return const LongDocScreen(blocks: kTermsBlocks);
  }
}
