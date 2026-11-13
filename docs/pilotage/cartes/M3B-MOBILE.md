# Carte M3B-MOBILE
Branche `m3b/mobile` depuis `integration/m3b`. Périmètre : mobile/.
Partie mobile du contrat M3b : scan GS1-128 / QR, contrôle guidé hors ligne, décision, photos, file M2. Tests : analyse GS1 (AI 00/10/17), verdict selon tolérances, contrôle hors ligne puis synchro.
En plus : fiabiliser `test/widget/m2_flow_test.dart` (« signalement hors ligne avec 2 photos puis synchro »), qui échoue parfois sous charge (attentes fondées sur le temps → attentes sur l'état).
`flutter analyze` et `flutter test` verts (lancer la suite 3 fois).
