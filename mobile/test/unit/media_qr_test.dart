import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:image/image.dart' as img;

import 'package:essor_mobile/core/media/image_compress.dart';
import 'package:essor_mobile/core/scan/qr_location.dart';

Uint8List png(int w, int h) => img.encodePng(img.Image(width: w, height: h));

void main() {
  group('compression des images', () {
    test('réduit le côté le plus long à 1600 px, en JPEG', () {
      final out = compressImage(png(4000, 3000))!;
      final decoded = img.decodeJpg(out)!;
      expect(decoded.width, 1600);
      expect(decoded.height, 1200);
    });

    test('portrait : la hauteur est limitée', () {
      final decoded = img.decodeJpg(compressImage(png(1200, 3200))!)!;
      expect(decoded.height, 1600);
      expect(decoded.width, 600);
    });

    test('petite image : taille conservée, réencodée en JPEG', () {
      final out = compressImage(png(800, 600))!;
      expect(out.sublist(0, 2), [0xFF, 0xD8]);
      expect(img.decodeJpg(out)!.width, 800);
    });

    test('qualité 80 % : plus léger qu\'en qualité 100', () {
      final src = img.Image(width: 1600, height: 1200);
      for (final p in src) {
        p
          ..r = (p.x * 7) % 256
          ..g = (p.y * 3) % 256
          ..b = (p.x ^ p.y) % 256;
      }
      final q80 = compressImage(img.encodePng(src))!;
      final q100 = img.encodeJpg(src, quality: 100);
      expect(q80.length, lessThan(q100.length));
      expect(jpegQuality, 80);
    });

    test('octets illisibles : null', () {
      expect(compressImage(Uint8List.fromList([1, 2, 3])), isNull);
    });
  });

  group('analyse du QR', () {
    test('site et zone', () {
      expect(parseQrLocation('essor://site/KEN'), isA<SiteQr>());
      final zone = parseQrLocation('essor://zone/L2-USI');
      expect(zone, isA<ZoneQr>());
      expect(zone!.code, 'L2-USI');
      expect(parseQrLocation('  ESSOR://Zone/a.b  ')?.code, 'a.b');
    });

    test('refuse tout le reste', () {
      for (final raw in [
        null,
        '',
        'https://site/KEN',
        'essor://instrument/PAC-0142',
        'essor://site/',
        'essor://site/a/b',
        'essor://zone/%3Cscript%3E',
        'n importe quoi',
      ]) {
        expect(parseQrLocation(raw), isNull, reason: raw);
      }
    });
  });
}
