import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

const appVersionLabel = '版本 1.2.3 (12)';
const httpControlPort = 9999;

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
  final Map<String, String> _remoteMediaCache = {};
  HttpServer? _httpServer;
  Timer? _imageSlideshowTimer;
  bool _isSlideshowTicking = false;
  String? _controlAddress;
  String? _httpServerStatus;
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
    _startHttpServer();
  }

  @override
  void dispose() {
    _imageSlideshowTimer?.cancel();
    _httpServer?.close(force: true);
    _clearRemoteMediaCache();
    super.dispose();
  }

  Future<dynamic> _handleNativeMethodCall(MethodCall call) async {
    switch (call.method) {
      case 'presentationDismissed':
        _addLog('原生事件：副屏窗口已关闭');
        if (mounted) {
          setState(() {
            _isShowing = false;
            _status = '副屏显示已关闭';
          });
        }
        return;
      case 'presentationEvent':
        final arguments = call.arguments;
        if (arguments is! Map) {
          _addLog('原生事件：presentationEvent 参数异常 ${call.arguments ?? ''}');
          return;
        }
        final event = Map<dynamic, dynamic>.from(arguments);
        _handlePresentationEvent(event);
        return;
      default:
        _addLog('原生事件：${call.method} ${call.arguments ?? ''}');
    }
  }

  void _handlePresentationEvent(Map<dynamic, dynamic> event) {
    final type = event['type']?.toString() ?? 'unknown';
    final mediaType = event['mediaType']?.toString();
    final displayId = event['displayId'];
    final message = event['message']?.toString();
    final width = event['width'];
    final height = event['height'];

    final parts = <String>[
      if (mediaType != null) mediaType == 'video' ? '视频' : '图片',
      if (displayId != null) 'display=$displayId',
      if (width != null && height != null) '${width}x$height',
      if (message != null && message.isNotEmpty) message,
    ];

    final text = parts.isEmpty ? type : '$type：${parts.join(' ')}';
    _addLog('副屏播放：$text');

    if (type == 'videoStarted' && mounted) {
      setState(() {
        _isShowing = true;
        _status = '副屏视频已开始播放';
      });
    }

    if (type == 'videoError' && mounted) {
      setState(() {
        _status = message == null || message.isEmpty ? '副屏视频播放失败' : message;
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

      final savedSourceUri = config?['sourceUri'] as String? ??
          config?['mediaUri'] as String? ??
          config?['imageUri'] as String?;
      final savedDisplayId = config?['displayId'] as int?;
      final savedScaleMode = ScaleMode.fromWireName(
        config?['scaleMode'] as String?,
      );
      final savedRotationMode = RotationMode.fromDegrees(
        config?['rotationDegrees'] as int?,
      );
      final shouldRestore = config?['isShowing'] == true && savedSourceUri != null;

      setState(() {
        _mediaUri = savedSourceUri;
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

      if (shouldRestore && mounted) {
        await _restoreLastShownMedia(
          mediaUri: savedSourceUri,
          mediaName: _mediaName,
          mediaType: _mediaType,
          displayId: _selectedDisplayId,
          scaleMode: _scaleMode,
          rotationMode: _rotationMode,
        );
      }
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

  Future<void> _startHttpServer() async {
    try {
      final server = await HttpServer.bind(
        InternetAddress.anyIPv4,
        httpControlPort,
        shared: true,
      );
      _httpServer = server;
      final address = await _resolveControlAddress(httpControlPort);
      if (!mounted) {
        await server.close(force: true);
        return;
      }
      setState(() {
        _controlAddress = address;
        _httpServerStatus = '运行中';
      });
      _addLog('HTTP服务启动成功：$address 监听端口=$httpControlPort');
      server.listen(
        _handleHttpRequest,
        onError: (Object error) {
          _addLog('HTTP服务错误：$error');
        },
      );
    } catch (error) {
      if (!mounted) {
        return;
      }
      setState(() {
        _httpServerStatus = '启动失败：$error';
      });
      _addLog('HTTP服务启动失败：$error');
    }
  }

  Future<String> _resolveControlAddress(int port) async {
    try {
      final interfaces = await NetworkInterface.list(
        type: InternetAddressType.IPv4,
      );
      for (final networkInterface in interfaces) {
        for (final address in networkInterface.addresses) {
          if (!address.isLoopback) {
            return 'http://${address.address}:$port';
          }
        }
      }
    } catch (_) {
      // Fall back to all interfaces below.
    }

    return 'http://0.0.0.0:$port';
  }

  Future<void> _handleHttpRequest(HttpRequest request) async {
    _applyCorsHeaders(request.response);

    if (request.method == 'OPTIONS') {
      request.response.statusCode = HttpStatus.noContent;
      await request.response.close();
      return;
    }

    try {
      final path = request.uri.path.toLowerCase();
      final remoteAddress =
          request.connectionInfo?.remoteAddress.address ?? 'unknown';
      _addLog(
        'HTTP调用：${request.method} ${request.uri.path} from=$remoteAddress',
      );
      if (request.method == 'GET' && path == '/api/status') {
        await _sendJson(request.response, _successResponse(_statusPayload()));
        return;
      }

      if (request.method == 'GET' && path == '/api/displays') {
        await _refreshDisplays(updateLoading: false);
        await _sendJson(
          request.response,
          _successResponse({
            'displays': _displays.map((display) => display.toJson()).toList(),
          }),
        );
        return;
      }

      if (request.method == 'POST' &&
          (path == '/api/hide' || path == '/robot_task/screen_stop')) {
        _addLog('HTTP停止调用：${request.uri.path}');
        _stopImageSlideshow(clearCache: true);
        await _hideImage();
        await _sendJson(request.response, _successResponse(_statusPayload()));
        return;
      }

      if (request.method != 'POST') {
        await _sendJson(
          request.response,
          _errorResponse('METHOD_NOT_ALLOWED', '只支持 POST 请求'),
          statusCode: HttpStatus.methodNotAllowed,
        );
        return;
      }

      final payload = await _readJsonBody(request);
      if (path == '/robot_task/screen_control') {
        await _handleVideoControlRequest(request, payload);
        return;
      }

      if (path == '/robot_task/screen_control_img_display') {
        await _handleImageControlRequest(request, payload);
        return;
      }

      await _sendJson(
        request.response,
        _errorResponse('NOT_FOUND', '未知接口：${request.uri.path}'),
        statusCode: HttpStatus.notFound,
      );
    } catch (error) {
      _addLog('HTTP请求失败：${request.method} ${request.uri.path} $error');
      await _sendJson(
        request.response,
        _errorResponse('REQUEST_FAILED', error.toString()),
        statusCode: HttpStatus.badRequest,
      );
    }
  }

  Future<void> _handleVideoControlRequest(
    HttpRequest request,
    Map<String, dynamic> payload,
  ) async {
    final url = _stringValue(payload['url']);
    if (url == null || url.isEmpty) {
      throw ArgumentError('缺少 url');
    }

    _stopImageSlideshow(clearCache: true);
    final mediaType =
        _stringValue(payload['mediaType']) ?? _inferMediaType(url);
    final displayId = _intValue(payload['displayId'] ?? payload['display_id']);
    final scaleMode = _scaleModeFromPayload(payload);
    final rotationMode = _rotationModeFromPayload(payload);
    _addLog(
      'HTTP视频调用：url=$url display=${displayId ?? '默认副屏'} '
      'scale=${scaleMode.wireName} rotation=${rotationMode.degrees}',
    );
    final resolvedUri = await _resolveRemoteMediaUriForDisplay(
      url,
      mediaType: mediaType,
    );
    final shownDisplayId = await _showMediaOnDisplay(
      mediaUri: resolvedUri,
      mediaName: _fileNameFromUrl(url),
      mediaType: mediaType,
      displayId: displayId,
      scaleMode: scaleMode,
      rotationMode: rotationMode,
      sourceUri: url,
      source: 'HTTP视频接口',
    );

    await _sendJson(
      request.response,
      _successResponse({
        ..._statusPayload(),
        'displayId': shownDisplayId,
        'url': url,
      }),
    );
  }

  Future<void> _handleImageControlRequest(
    HttpRequest request,
    Map<String, dynamic> payload,
  ) async {
    final urls = _urlListValue(payload['url']);
    if (urls.isEmpty) {
      throw ArgumentError('缺少 url 图片列表');
    }

    final intervalMs = _intValue(payload['time_sleep']) ?? 2000;
    final displayId = _intValue(payload['displayId'] ?? payload['display_id']);
    final scaleMode = _scaleModeFromPayload(payload);
    final rotationMode = _rotationModeFromPayload(payload);
    _addLog(
      'HTTP图片轮播调用：count=${urls.length} interval=${intervalMs}ms '
      'display=${displayId ?? '默认副屏'} scale=${scaleMode.wireName} '
      'rotation=${rotationMode.degrees}',
    );
    final shownDisplayId = await _startImageSlideshow(
      urls: urls,
      intervalMs: intervalMs,
      displayId: displayId,
      scaleMode: scaleMode,
      rotationMode: rotationMode,
    );

    await _sendJson(
      request.response,
      _successResponse({
        ..._statusPayload(),
        'displayId': shownDisplayId,
        'time_sleep': intervalMs,
        'url': urls,
      }),
    );
  }

  Future<Map<String, dynamic>> _readJsonBody(HttpRequest request) async {
    final rawBody = await utf8.decoder.bind(request).join();
    if (rawBody.trim().isEmpty) {
      return {};
    }

    final decoded = jsonDecode(rawBody);
    if (decoded is! Map) {
      throw const FormatException('请求体必须是 JSON 对象');
    }

    return decoded.map((key, value) => MapEntry(key.toString(), value));
  }

  Future<int> _startImageSlideshow({
    required List<String> urls,
    required int intervalMs,
    required int? displayId,
    required ScaleMode scaleMode,
    required RotationMode rotationMode,
  }) async {
    _stopImageSlideshow(clearCache: true);
    final normalizedIntervalMs = intervalMs < 500 ? 500 : intervalMs;
    var index = 0;

    Future<int> showNextImage() async {
      final currentIndex = index % urls.length;
      final sourceUrl = urls[currentIndex];
      index += 1;
      final displayUri = await _resolveRemoteMediaUriForDisplay(
        sourceUrl,
        mediaType: 'image',
      );
      return _showMediaOnDisplay(
        mediaUri: displayUri,
        mediaName:
            '${_fileNameFromUrl(sourceUrl)} (${currentIndex + 1}/${urls.length})',
        mediaType: 'image',
        displayId: displayId,
        scaleMode: scaleMode,
        rotationMode: rotationMode,
        sourceUri: sourceUrl,
        source: 'HTTP图片轮播',
      );
    }

    final shownDisplayId = await showNextImage();
    if (urls.length > 1) {
      _imageSlideshowTimer = Timer.periodic(
        Duration(milliseconds: normalizedIntervalMs),
        (_) async {
          if (_isSlideshowTicking) {
            return;
          }
          _isSlideshowTicking = true;
          try {
            await showNextImage();
          } catch (error) {
            _addLog('图片轮播失败：$error');
          } finally {
            _isSlideshowTicking = false;
          }
        },
      );
      _addLog('图片轮播：${urls.length}张 interval=${normalizedIntervalMs}ms');
    }

    return shownDisplayId;
  }

  Future<String> _resolveRemoteMediaUriForDisplay(
    String sourceUrl, {
    required String mediaType,
  }) async {
    final uri = Uri.tryParse(sourceUrl);
    if (uri == null || (uri.scheme != 'http' && uri.scheme != 'https')) {
      return sourceUrl;
    }

    final cachedUri = _remoteMediaCache[sourceUrl];
    if (cachedUri != null) {
      return cachedUri;
    }

    final cacheDir = Directory(
      '${Directory.systemTemp.path}/second_screen_viewer/media_cache',
    );
    if (!await cacheDir.exists()) {
      await cacheDir.create(recursive: true);
    }

    final fileName =
        '${DateTime.now().millisecondsSinceEpoch}_${_safeMediaFileName(sourceUrl, mediaType)}';
    final file = File('${cacheDir.path}/$fileName');
    final client = HttpClient();
    try {
      _addLog('媒体下载开始：type=$mediaType $sourceUrl');
      final request = await client.getUrl(uri);
      final response = await request.close();
      if (response.statusCode < 200 || response.statusCode >= 300) {
        throw HttpException('下载媒体失败：HTTP ${response.statusCode}', uri: uri);
      }
      await response.pipe(file.openWrite());
      final fileUri = file.uri.toString();
      _remoteMediaCache[sourceUrl] = fileUri;
      _addLog('媒体缓存完成：$sourceUrl -> $fileUri');
      return fileUri;
    } catch (_) {
      try {
        if (await file.exists()) {
          await file.delete();
        }
      } catch (_) {
        // Partial cache cleanup is best effort.
      }
      rethrow;
    } finally {
      client.close(force: true);
    }
  }

  void _stopImageSlideshow({bool clearCache = false}) {
    _imageSlideshowTimer?.cancel();
    _imageSlideshowTimer = null;
    _isSlideshowTicking = false;
    if (clearCache) {
      _clearRemoteMediaCache();
    }
  }

  void _clearRemoteMediaCache() {
    for (final fileUri in _remoteMediaCache.values) {
      final uri = Uri.tryParse(fileUri);
      if (uri?.scheme == 'file') {
        try {
          File.fromUri(uri!).deleteSync();
        } catch (_) {
          // Cache cleanup is best effort.
        }
      }
    }
    _remoteMediaCache.clear();
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
    _stopImageSlideshow(clearCache: false);
    try {
      await _showMediaOnDisplay(
        mediaUri: _mediaUri,
        mediaName: _mediaName,
        mediaType: _mediaType,
        displayId: _selectedDisplayId,
        scaleMode: _scaleMode,
        rotationMode: _rotationMode,
        sourceUri: _mediaUri,
        source: '主界面',
      );
    } catch (_) {
      // Error state has already been shown and logged.
    }
  }

  Future<void> _restoreLastShownMedia({
    required String mediaUri,
    required String? mediaName,
    required String mediaType,
    required int? displayId,
    required ScaleMode scaleMode,
    required RotationMode rotationMode,
  }) async {
    _addLog('启动：自动恢复上次显示内容');
    try {
      await _showMediaOnDisplay(
        mediaUri: mediaUri,
        mediaName: mediaName,
        mediaType: mediaType,
        displayId: displayId,
        scaleMode: scaleMode,
        rotationMode: rotationMode,
        sourceUri: mediaUri,
        source: '开机恢复',
      );
    } catch (_) {
      // Error state has already been shown and logged.
    }
  }

  Future<int> _showMediaOnDisplay({
    required String? mediaUri,
    required String? mediaName,
    required String mediaType,
    required int? displayId,
    required ScaleMode scaleMode,
    required RotationMode rotationMode,
    String? sourceUri,
    required String source,
  }) async {
    final targetDisplayId = _resolveDisplayId(displayId ?? _selectedDisplayId);
    final effectiveSourceUri = sourceUri ?? mediaUri;

    if (targetDisplayId == null) {
      _addLog('显示失败：未选择副屏');
      _setStatus('请先连接并选择副屏');
      throw StateError('请先连接并选择副屏');
    }

    if (mediaUri == null || mediaUri.isEmpty) {
      _addLog('显示失败：未选择媒体');
      _setStatus('请先选择图片或视频');
      throw StateError('请先选择图片或视频');
    }

    final displayMediaUri = await _resolveDisplayMediaUri(mediaUri, mediaType);
    _addLog(
      '开始显示：source=$source display=$targetDisplayId scale=${scaleMode.wireName} '
      'rotation=${rotationMode.degrees} type=$mediaType media=$displayMediaUri',
    );
    setState(() {
      _isLoading = true;
      _status = null;
    });

    try {
      await _channel.invokeMapMethod<String, dynamic>('showImage', {
        'displayId': targetDisplayId,
        'imageUri': displayMediaUri,
        'mediaUri': displayMediaUri,
        'sourceUri': effectiveSourceUri,
        'mediaName': mediaName,
        'mediaType': mediaType,
        'scaleMode': scaleMode.wireName,
        'rotationDegrees': rotationMode.degrees,
      });

      setState(() {
        _mediaUri = effectiveSourceUri ?? displayMediaUri;
        _mediaName = mediaName ?? mediaUri;
        _mediaType = mediaType;
        _selectedDisplayId = targetDisplayId;
        _scaleMode = scaleMode;
        _rotationMode = rotationMode;
        _isShowing = true;
        _status = mediaType == 'video' ? '正在副屏播放视频' : '正在副屏显示图片';
      });
      _addLog('显示成功：display=$targetDisplayId');
      return targetDisplayId;
    } on PlatformException catch (error) {
      _handlePlatformError('副屏显示失败', error);
      rethrow;
    } finally {
      if (mounted) {
        setState(() {
          _isLoading = false;
        });
      }
    }
  }

  Future<String> _resolveDisplayMediaUri(
    String mediaUri,
    String mediaType,
  ) async {
    final uri = Uri.tryParse(mediaUri);
    if (uri == null || (uri.scheme != 'http' && uri.scheme != 'https')) {
      return mediaUri;
    }

    return _resolveRemoteMediaUriForDisplay(
      mediaUri,
      mediaType: mediaType,
    );
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

  Map<String, dynamic> _statusPayload() {
    return {
      'version': appVersionLabel,
      'controlUrl': _controlAddress,
      'httpStatus': _httpServerStatus,
      'isShowing': _isShowing,
      'mediaUri': _mediaUri,
      'mediaName': _mediaName,
      'mediaType': _mediaType,
      'displayId': _selectedDisplayId,
      'scaleMode': _scaleMode.wireName,
      'rotationDegrees': _rotationMode.degrees,
    };
  }

  Map<String, dynamic> _successResponse(Map<String, dynamic> data) {
    return {'code': 200, 'msg': 'success', 'data': data};
  }

  Map<String, dynamic> _errorResponse(String error, String message) {
    return {'code': 400, 'msg': message, 'error': error};
  }

  void _applyCorsHeaders(HttpResponse response) {
    response.headers
      ..set('Access-Control-Allow-Origin', '*')
      ..set('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
      ..set('Access-Control-Allow-Headers', 'Content-Type, Authorization');
  }

  Future<void> _sendJson(
    HttpResponse response,
    Map<String, dynamic> payload, {
    int statusCode = HttpStatus.ok,
  }) async {
    response.statusCode = statusCode;
    response.headers.contentType = ContentType.json;
    response.write(jsonEncode(payload));
    await response.close();
  }

  String? _stringValue(Object? value) {
    if (value == null) {
      return null;
    }
    final text = value.toString().trim();
    return text.isEmpty ? null : text;
  }

  int? _intValue(Object? value) {
    if (value == null) {
      return null;
    }
    if (value is int) {
      return value;
    }
    if (value is num) {
      return value.toInt();
    }
    return int.tryParse(value.toString());
  }

  List<String> _urlListValue(Object? value) {
    if (value is List) {
      return value
          .map(_stringValue)
          .whereType<String>()
          .where((url) => url.isNotEmpty)
          .toList(growable: false);
    }

    final singleUrl = _stringValue(value);
    return singleUrl == null ? const [] : [singleUrl];
  }

  ScaleMode _scaleModeFromPayload(Map<String, dynamic> payload) {
    return ScaleMode.fromWireName(
      _stringValue(payload['scaleMode'] ?? payload['scale_mode']),
    );
  }

  RotationMode _rotationModeFromPayload(Map<String, dynamic> payload) {
    return RotationMode.fromDegrees(
      _intValue(
        payload['rotationDegrees'] ??
            payload['rotation_degrees'] ??
            payload['rotation'],
      ),
    );
  }

  String _inferMediaType(String url) {
    final path = (Uri.tryParse(url)?.path ?? url).toLowerCase();
    if (path.endsWith('.jpg') ||
        path.endsWith('.jpeg') ||
        path.endsWith('.png') ||
        path.endsWith('.webp') ||
        path.endsWith('.bmp') ||
        path.endsWith('.gif')) {
      return 'image';
    }
    return 'video';
  }

  String _fileNameFromUrl(String url) {
    final uri = Uri.tryParse(url);
    if (uri != null && uri.pathSegments.isNotEmpty) {
      final name = uri.pathSegments.last.trim();
      if (name.isNotEmpty) {
        return Uri.decodeComponent(name);
      }
    }
    return 'media';
  }

  String _safeFileName(String value) {
    final sanitized = value.replaceAll(RegExp(r'[^A-Za-z0-9._-]'), '_');
    return sanitized.isEmpty ? 'image' : sanitized;
  }

  String _safeMediaFileName(String sourceUrl, String mediaType) {
    final name = _safeFileName(_fileNameFromUrl(sourceUrl));
    if (name.contains('.')) {
      return name;
    }

    return mediaType == 'video' ? '$name.mp4' : '$name.img';
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
                  title: 'HTTP控制',
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      _InfoRow(
                        label: '服务状态',
                        value: _httpServerStatus ?? '启动中',
                      ),
                      const SizedBox(height: 8),
                      _InfoRow(
                        label: '控制地址',
                        value: _controlAddress ?? '正在获取局域网地址',
                      ),
                      const SizedBox(height: 8),
                      const _InfoRow(
                        label: '视频接口',
                        value: '/robot_task/screen_control',
                      ),
                      const SizedBox(height: 8),
                      const _InfoRow(
                        label: '图片接口',
                        value: '/robot_task/screen_control_Img_display',
                      ),
                    ],
                  ),
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

  Map<String, dynamic> toJson() {
    return {
      'id': id,
      'name': name,
      'width': width,
      'height': height,
      'densityDpi': densityDpi,
      'isDefault': isDefault,
      'isPresentation': isPresentation,
      'title': title,
    };
  }

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
