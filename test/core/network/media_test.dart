import 'package:flutter_test/flutter_test.dart';
import 'package:pharmacy/core/config/api_config.dart';
import 'package:pharmacy/core/network/media.dart';

void main() {
  group('proxyMedia', () {
    test('http-URL Medusa → наш HTTPS-прокси /api/media/img', () {
      const raw = 'http://78.140.246.238:9000/static/photo 1.jpg';
      final out = proxyMedia(raw)!;
      expect(out, startsWith('${ApiConfig.baseUrl}/api/media/img?u='));
      // исходный URL должен быть percent-encoded в параметре u
      expect(out, contains(Uri.encodeComponent(raw)));
      expect(out, isNot(contains(' '))); // пробелы закодированы
    });

    test('https-URL остаётся без изменений', () {
      const url = 'https://epharm.example/s3/banner.png';
      expect(proxyMedia(url), url);
    });

    test('пусто/null → null', () {
      expect(proxyMedia(null), isNull);
      expect(proxyMedia(''), isNull);
      expect(proxyMedia('   '), isNull);
    });
  });
}
