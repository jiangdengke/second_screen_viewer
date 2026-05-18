import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const appVersionLabel = '版本 1.1.2 (8)';

void main() {
  runApp(const SecondScreenApp());
}

class SecondScreenApp extends StatelessWidget {
  const SecondScreenApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      debugShowCheckedModeBanner: false,
      title: '副屏媒体显示',
      theme: ThemeData(
        colorScheme: ColorScheme.fromSeed(
          seedColor: const Color(0xFF1F6FEB),
          brightness: Brightness.light,
        ),
        scaffoldBackgroundColor: const Color(0xFFF6F8FA),
        useMaterial3: true,
      ),
      home: const DisplayControlPage(),
    );
  }
}

class DisplayControlPage extends StatefulWidget {
  const DisplayControlPage({super.key});

  @override
  State<DisplayControlPage> createState() => _DisplayControlPageState();
}

class _DisplayControlPageState extends State<DisplayControlPage> {
  static const _channel = MethodChannel('second_screen_viewer/display');

  final List<DeviceDisplay> _displays = [];
  String? _mediaUri;
  String? _mediaName;
  String _mediaType = 'image';
  int? _selectedDisplayId;
  ScaleMode _scaleMode = ScaleMode.centerCrop;
  RotationMode _rotationMode = RotationMode.degrees0;
  bool _isLoading = true;
  bool _isShowing = false;
  String? _status;
  final List<String> _logs = [];

  List<DeviceDisplay> get _secondaryDisplays =>
      _displays.where((display) => !display.isDefault).toList(growable: false);

  @override
  void initState() {
    super.initState();
    _channel.setMethodCallHandler(_handleNativeMethodCall);
    _loadInitialState();
  }

  Future<dynamic> _handleNativeMethodCall(MethodCall call) async {
    _addLog('原生事件：${call.method} ${call.arguments ?? ''}');
    if (call.method == 'presentationDismissed' && mounted) {
      setState(() {
        _isShowing = false;
        _status = '副屏显示已关闭';
      });
    }
  }

  Future<void> _loadInitialState() async {
    _addLog('启动：读取保存配置并检测屏幕');
    setState(() {
      _isLoading = true;
      _status = null;
    });

    try {
      await _refreshDisplays(updateLoading: false);
      final config = await _channel.invokeMapMethod<String, dynamic>(
        'getSavedConfig',
      );

      final savedDisplayId = config?['displayId'] as int?;
      final savedScaleMode = ScaleMode.fromWireName(
        config?['scaleMode'] as String?,
      );
      final savedRotationMode = RotationMode.fromDegrees(
        config?['rotationDegrees'] as int?,
      );

      setState(() {
        _mediaUri =
            config?['mediaUri'] as String? ?? config?['imageUri'] as String?;
        _mediaName =
            config?['mediaName'] as String? ?? config?['imageName'] as String?;
        _mediaType = config?['mediaType'] as String? ?? 'image';
        _selectedDisplayId = _resolveDisplayId(savedDisplayId);
        _scaleMode = savedScaleMode;
        _rotationMode = savedRotationMode;
      });
      _addLog(
        '配置：display=$_selectedDisplayId media=${_mediaName ?? _mediaUri ?? '未选择'} '
        'type=$_mediaType '
        'scale=${_scaleMode.wireName} rotation=${_rotationMode.degrees}',
      );
    } on PlatformException catch (error) {
      _handlePlatformError('读取设备状态失败', error);
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  Future<void> _refreshDisplays({bool updateLoading = true}) async {
    _addLog('刷新屏幕列表');
    if (updateLoading) {
      setState(() {
        _isLoading = true;
        _status = null;
      });
    }

    try {
      final result = await _channel.invokeListMethod<dynamic>('getDisplays');
      final displays = (result ?? [])
          .map(
            (value) => DeviceDisplay.fromMap(Map<dynamic, dynamic>.from(value)),
          )
          .toList(growable: false);

      setState(() {
        _displays
          ..clear()
          ..addAll(displays);
        _selectedDisplayId = _resolveDisplayId(_selectedDisplayId);
        if (_selectedDisplayId == null && _secondaryDisplays.isNotEmpty) {
          _selectedDisplayId = _secondaryDisplays.first.id;
        }
        _status = _secondaryDisplays.isEmpty ? '没有检测到副屏' : null;
      });
      _addLog(
        '屏幕：${displays.map((display) => display.debugLabel).join(' | ')}',
      );
    } on PlatformException catch (error) {
      _handlePlatformError('刷新屏幕失败', error);
    } finally {
      if (mounted && updateLoading) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  int? _resolveDisplayId(int? preferredId) {
    if (_secondaryDisplays.isEmpty) {
      return null;
    }

    if (preferredId != null &&
        _secondaryDisplays.any((display) => display.id == preferredId)) {
      return preferredId;
    }

    return _secondaryDisplays.first.id;
  }

  Future<void> _pickMedia() async {
    _addLog('打开媒体选择器');
    setState(() {
      _isLoading = true;
      _status = null;
    });

    try {
      final result = await _channel.invokeMapMethod<String, dynamic>(
        'pickImage',
      );

      if (result == null) {
        _setStatus('已取消选择媒体');
        return;
      }

      setState(() {
        _mediaUri = result['uri'] as String?;
        _mediaName = result['name'] as String?;
        _mediaType = result['mediaType'] as String? ?? 'image';
        _status = _mediaName == null ? '媒体已选择' : '已选择：$_mediaName';
      });
      _addLog('媒体：type=$_mediaType ${_mediaName ?? _mediaUri ?? '未知'}');
    } on PlatformException catch (error) {
      _handlePlatformError('选择媒体失败', error);
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  Future<void> _showImage() async {
    final displayId = _selectedDisplayId;
    final mediaUri = _mediaUri;

    if (displayId == null) {
      _addLog('显示失败：未选择副屏');
      _setStatus('请先连接并选择副屏');
      return;
    }

    if (mediaUri == null || mediaUri.isEmpty) {
      _addLog('显示失败：未选择媒体');
      _setStatus('请先选择图片或视频');
      return;
    }

    _addLog(
      '开始显示：display=$displayId scale=${_scaleMode.wireName} '
      'rotation=${_rotationMode.degrees} type=$_mediaType media=$mediaUri',
    );
    setState(() {
      _isLoading = true;
      _status = null;
    });

    try {
      await _channel.invokeMapMethod<String, dynamic>('showImage', {
        'displayId': displayId,
        'imageUri': mediaUri,
        'mediaUri': mediaUri,
        'mediaType': _mediaType,
        'scaleMode': _scaleMode.wireName,
        'rotationDegrees': _rotationMode.degrees,
      });

      setState(() {
        _isShowing = true;
        _status = _mediaType == 'video' ? '正在副屏播放视频' : '正在副屏显示图片';
      });
      _addLog('显示成功：display=$displayId');
    } on PlatformException catch (error) {
      _handlePlatformError('副屏显示失败', error);
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  Future<void> _hideImage() async {
    _addLog('停止副屏显示');
    setState(() {
      _isLoading = true;
      _status = null;
    });

    try {
      await _channel.invokeMethod<bool>('hideImage');
      setState(() {
        _isShowing = false;
        _status = '已停止副屏显示';
      });
      _addLog('停止成功');
    } on PlatformException catch (error) {
      _handlePlatformError('停止显示失败', error);
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  void _setStatus(String message) {
    if (!mounted) {
      return;
    }

    setState(() {
      _status = message;
    });
  }

  void _handlePlatformError(String fallbackMessage, PlatformException error) {
    final message = error.message ?? fallbackMessage;
    final details = error.details == null ? '' : '\n${error.details}';
    _setStatus(message);
    _addLog('错误：${error.code} $message$details');
  }

  void _addLog(String message) {
    if (!mounted) {
      return;
    }

    final now = DateTime.now();
    final timestamp =
        '${now.hour.toString().padLeft(2, '0')}:'
        '${now.minute.toString().padLeft(2, '0')}:'
        '${now.second.toString().padLeft(2, '0')}';

    setState(() {
      _logs.insert(0, '[$timestamp] $message');
      if (_logs.length > 80) {
        _logs.removeRange(80, _logs.length);
      }
    });
  }

  void _clearLogs() {
    setState(() {
      _logs.clear();
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('副屏媒体显示'),
        actions: [
          IconButton(
            tooltip: '刷新屏幕',
            onPressed: _isLoading ? null : _refreshDisplays,
            icon: const Icon(Icons.refresh),
          ),
        ],
      ),
      body: SafeArea(
        child: Stack(
          children: [
            ListView(
              padding: const EdgeInsets.fromLTRB(16, 12, 16, 24),
              children: [
                _StatusPanel(
                  isShowing: _isShowing,
                  status: _status,
                  secondaryDisplayCount: _secondaryDisplays.length,
                ),
                const SizedBox(height: 12),
                _Section(
                  title: '媒体',
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      _InfoRow(label: '当前媒体', value: _mediaName ?? '未选择'),
                      const SizedBox(height: 8),
                      _InfoRow(
                        label: '媒体类型',
                        value: _mediaUri == null
                            ? '未选择'
                            : (_mediaType == 'video' ? '视频' : '图片'),
                      ),
                      const SizedBox(height: 12),
                      FilledButton.icon(
                        onPressed: _isLoading ? null : _pickMedia,
                        icon: const Icon(Icons.perm_media_outlined),
                        label: const Text('选择图片或视频'),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 12),
                _Section(
                  title: '副屏',
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      if (_secondaryDisplays.isEmpty)
                        const Text('未检测到扩展屏。请确认系统不是镜像模式，并检查 HDMI/屏幕连接。')
                      else
                        DropdownButtonFormField<int>(
                          initialValue: _selectedDisplayId,
                          decoration: const InputDecoration(
                            border: OutlineInputBorder(),
                            labelText: '目标屏幕',
                          ),
                          items: _secondaryDisplays
                              .map(
                                (display) => DropdownMenuItem<int>(
                                  value: display.id,
                                  child: Text(display.title),
                                ),
                              )
                              .toList(growable: false),
                          onChanged: _isLoading
                              ? null
                              : (value) {
                                  setState(() {
                                    _selectedDisplayId = value;
                                  });
                                },
                        ),
                      const SizedBox(height: 12),
                      OutlinedButton.icon(
                        onPressed: _isLoading ? null : _refreshDisplays,
                        icon: const Icon(Icons.screenshot_monitor_outlined),
                        label: const Text('重新检测屏幕'),
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 12),
                _Section(
                  title: '显示模式',
                  child: SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: SegmentedButton<ScaleMode>(
                      segments: ScaleMode.values
                          .map(
                            (mode) => ButtonSegment<ScaleMode>(
                              value: mode,
                              icon: Icon(mode.icon),
                              label: Text(mode.label),
                            ),
                          )
                          .toList(growable: false),
                      selected: {_scaleMode},
                      onSelectionChanged: _isLoading
                          ? null
                          : (values) {
                              setState(() {
                                _scaleMode = values.first;
                              });
                              if (_isShowing) {
                                _showImage();
                              }
                            },
                    ),
                  ),
                ),
                const SizedBox(height: 12),
                _Section(
                  title: '旋转角度',
                  child: SingleChildScrollView(
                    scrollDirection: Axis.horizontal,
                    child: SegmentedButton<RotationMode>(
                      segments: RotationMode.values
                          .map(
                            (mode) => ButtonSegment<RotationMode>(
                              value: mode,
                              icon: Icon(mode.icon),
                              label: Text(mode.label),
                            ),
                          )
                          .toList(growable: false),
                      selected: {_rotationMode},
                      onSelectionChanged: _isLoading
                          ? null
                          : (values) {
                              setState(() {
                                _rotationMode = values.first;
                              });
                              if (_isShowing) {
                                _showImage();
                              }
                            },
                    ),
                  ),
                ),
                const SizedBox(height: 20),
                FilledButton.icon(
                  onPressed: _isLoading ? null : _showImage,
                  icon: const Icon(Icons.play_arrow),
                  label: const Text('开始在副屏播放/显示'),
                  style: FilledButton.styleFrom(
                    minimumSize: const Size.fromHeight(48),
                  ),
                ),
                const SizedBox(height: 10),
                OutlinedButton.icon(
                  onPressed: _isLoading || !_isShowing ? null : _hideImage,
                  icon: const Icon(Icons.stop),
                  label: const Text('停止显示'),
                  style: OutlinedButton.styleFrom(
                    minimumSize: const Size.fromHeight(48),
                  ),
                ),
                const SizedBox(height: 12),
                _LogPanel(logs: _logs, onClear: _clearLogs),
                const SizedBox(height: 16),
                const Center(
                  child: Text(
                    appVersionLabel,
                    style: TextStyle(color: Color(0xFF57606A), fontSize: 12),
                  ),
                ),
              ],
            ),
            if (_isLoading)
              const Positioned.fill(
                child: IgnorePointer(
                  child: ColoredBox(
                    color: Color(0x33FFFFFF),
                    child: Center(child: CircularProgressIndicator()),
                  ),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class DeviceDisplay {
  const DeviceDisplay({
    required this.id,
    required this.name,
    required this.width,
    required this.height,
    required this.densityDpi,
    required this.isDefault,
    required this.isPresentation,
  });

  factory DeviceDisplay.fromMap(Map<dynamic, dynamic> map) {
    return DeviceDisplay(
      id: (map['id'] as num).toInt(),
      name: map['name'] as String? ?? 'Display',
      width: (map['width'] as num?)?.toInt() ?? 0,
      height: (map['height'] as num?)?.toInt() ?? 0,
      densityDpi: (map['densityDpi'] as num?)?.toInt() ?? 0,
      isDefault: map['isDefault'] == true,
      isPresentation: map['isPresentation'] == true,
    );
  }

  final int id;
  final String name;
  final int width;
  final int height;
  final int densityDpi;
  final bool isDefault;
  final bool isPresentation;

  String get title {
    final role = isPresentation ? '副屏' : '扩展屏';
    final size = width > 0 && height > 0 ? ' $width x $height' : '';
    return '$role #$id - $name$size';
  }

  String get debugLabel {
    final role = isDefault ? '主屏' : (isPresentation ? '副屏' : '扩展屏');
    return '$role#$id "$name" ${width}x$height dpi=$densityDpi';
  }
}

enum ScaleMode {
  centerCrop('centerCrop', '铺满', Icons.crop_free),
  fitCenter('fitCenter', '适应', Icons.fit_screen),
  fitXY('fitXY', '拉伸', Icons.open_in_full),
  centerInside('centerInside', '居中', Icons.center_focus_strong);

  const ScaleMode(this.wireName, this.label, this.icon);

  final String wireName;
  final String label;
  final IconData icon;

  static ScaleMode fromWireName(String? value) {
    return ScaleMode.values.firstWhere(
      (mode) => mode.wireName == value,
      orElse: () => ScaleMode.centerCrop,
    );
  }
}

enum RotationMode {
  degrees0(0, '0°', Icons.screen_rotation_alt_outlined),
  degrees90(90, '90°', Icons.rotate_90_degrees_cw),
  degrees180(180, '180°', Icons.rotate_right),
  degrees270(270, '270°', Icons.rotate_90_degrees_ccw);

  const RotationMode(this.degrees, this.label, this.icon);

  final int degrees;
  final String label;
  final IconData icon;

  static RotationMode fromDegrees(int? value) {
    final normalized = ((value ?? 0) % 360 + 360) % 360;
    return RotationMode.values.firstWhere(
      (mode) => mode.degrees == normalized,
      orElse: () => RotationMode.degrees0,
    );
  }
}

class _StatusPanel extends StatelessWidget {
  const _StatusPanel({
    required this.isShowing,
    required this.status,
    required this.secondaryDisplayCount,
  });

  final bool isShowing;
  final String? status;
  final int secondaryDisplayCount;

  @override
  Widget build(BuildContext context) {
    final colorScheme = Theme.of(context).colorScheme;
    final statusText =
        status ?? (secondaryDisplayCount > 0 ? '已检测到副屏' : '等待连接副屏');

    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: isShowing ? colorScheme.primaryContainer : Colors.white,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(
          color: isShowing ? colorScheme.primary : const Color(0xFFD0D7DE),
        ),
      ),
      child: Row(
        children: [
          Icon(
            isShowing ? Icons.cast_connected : Icons.connected_tv_outlined,
            color: isShowing ? colorScheme.primary : const Color(0xFF57606A),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text(
                  isShowing ? '副屏显示中' : '副屏待机',
                  style: Theme.of(context).textTheme.titleMedium,
                ),
                const SizedBox(height: 2),
                Text(statusText, style: Theme.of(context).textTheme.bodyMedium),
              ],
            ),
          ),
        ],
      ),
    );
  }
}

class _Section extends StatelessWidget {
  const _Section({required this.title, required this.child});

  final String title;
  final Widget child;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: Colors.white,
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: const Color(0xFFD0D7DE)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: Theme.of(context).textTheme.titleMedium),
          const SizedBox(height: 12),
          child,
        ],
      ),
    );
  }
}

class _LogPanel extends StatelessWidget {
  const _LogPanel({required this.logs, required this.onClear});

  final List<String> logs;
  final VoidCallback onClear;

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.all(12),
      decoration: BoxDecoration(
        color: const Color(0xFF0D1117),
        borderRadius: BorderRadius.circular(8),
        border: Border.all(color: const Color(0xFF30363D)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          Row(
            children: [
              const Icon(Icons.terminal, color: Color(0xFFC9D1D9), size: 18),
              const SizedBox(width: 8),
              Text(
                '日志',
                style: Theme.of(context).textTheme.titleSmall?.copyWith(
                  color: const Color(0xFFC9D1D9),
                ),
              ),
              const Spacer(),
              TextButton.icon(
                onPressed: logs.isEmpty ? null : onClear,
                icon: const Icon(Icons.delete_outline, size: 16),
                label: const Text('清空'),
                style: TextButton.styleFrom(
                  foregroundColor: const Color(0xFFC9D1D9),
                  visualDensity: VisualDensity.compact,
                ),
              ),
            ],
          ),
          const SizedBox(height: 8),
          SizedBox(
            height: 180,
            child: logs.isEmpty
                ? const Center(
                    child: Text(
                      '暂无日志',
                      style: TextStyle(color: Color(0xFF8B949E)),
                    ),
                  )
                : Scrollbar(
                    child: ListView.separated(
                      reverse: false,
                      itemCount: logs.length,
                      separatorBuilder: (context, index) =>
                          const SizedBox(height: 6),
                      itemBuilder: (context, index) {
                        return SelectableText(
                          logs[index],
                          style: const TextStyle(
                            color: Color(0xFFC9D1D9),
                            fontSize: 12,
                            fontFamily: 'monospace',
                          ),
                        );
                      },
                    ),
                  ),
          ),
        ],
      ),
    );
  }
}

class _InfoRow extends StatelessWidget {
  const _InfoRow({required this.label, required this.value});

  final String label;
  final String value;

  @override
  Widget build(BuildContext context) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        SizedBox(
          width: 76,
          child: Text(
            label,
            style: Theme.of(
              context,
            ).textTheme.bodyMedium?.copyWith(color: const Color(0xFF57606A)),
          ),
        ),
        Expanded(
          child: Text(
            value,
            maxLines: 2,
            overflow: TextOverflow.ellipsis,
            style: Theme.of(context).textTheme.bodyMedium,
          ),
        ),
      ],
    );
  }
}
