import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:flutter/widgets.dart';
import 'package:second_screen_viewer/main.dart';

void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  const channel = MethodChannel('second_screen_viewer/display');

  setUp(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, (call) async {
          switch (call.method) {
            case 'getDisplays':
              return [
                {
                  'id': 0,
                  'name': 'Built-in Screen',
                  'width': 1920,
                  'height': 1080,
                  'densityDpi': 320,
                  'isDefault': true,
                  'isPresentation': false,
                },
                {
                  'id': 1,
                  'name': 'HDMI Screen',
                  'width': 1920,
                  'height': 1080,
                  'densityDpi': 160,
                  'isDefault': false,
                  'isPresentation': true,
                },
              ];
            case 'getSavedConfig':
              return {
                'imageUri': null,
                'imageName': null,
                'mediaUri': null,
                'mediaName': null,
                'mediaType': 'image',
                'displayId': 1,
                'scaleMode': 'centerCrop',
                'rotationDegrees': 0,
              };
          }

          return null;
        });
  });

  tearDown(() {
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(channel, null);
  });

  testWidgets('shows the display control screen', (tester) async {
    await tester.pumpWidget(const SecondScreenApp());
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 100));

    expect(find.text('副屏媒体显示'), findsOneWidget);
    expect(find.text('选择图片或视频'), findsOneWidget);
    expect(find.textContaining('HDMI Screen'), findsOneWidget);

    await tester.drag(find.byType(ListView), const Offset(0, -500));
    await tester.pump();

    expect(find.text('开始在副屏播放/显示'), findsOneWidget);
  });
}
