// DroidServer istemcisi: "SDK deneme" sunucusu için üretildi (konfigürasyon v21).
// Konfigürasyon değişince yeniden indirin: GET <API>/_sdk/{ts|kotlin|dart|swift} (yönetici) veya uygulama/panel.
// Gerekenler: yalnızca Dart 3 SDK'sı (`dart:io`). Dart VM ve Flutter Android/iOS/masaüstünde çalışır; Flutter web'de çalışmaz.
//
// Kullanım:
//   final ds = DroidServerClient('https://telefon.example.com/api', apiKey: '…');   // veya token / auth.login
//   final page = await ds.todoItems.list(const ListOptions(filter: {'done': 'false'}, sort: '-id'));
//   final sub = ds.subscribe(['todo_items']).listen((e) => print('${e.action} ${e.record}'));
// ignore_for_file: non_constant_identifier_names

import 'dart:async';
import 'dart:convert';
import 'dart:io';
import 'dart:typed_data';

/// `todo_items` kaydı. Gizli alanlar yetkiye göre gelmeyebilir; bu yüzden alanlar boş olabilir.
class TodoItems {
  final int id;
  final String? title;
  final bool? done;
  final int? qty;
  final double? price;
  final String? status;
  final List<String>? tags;
  final Object? meta;
  final String? in_;
  final String? due;
  final GeoPoint? place;
  final String? photo;
  final double? total;
  final int? owner;
  final String? olusturulma;
  final String? updated_at;
  final Map<String, dynamic>? expand;

  const TodoItems({required this.id, this.title, this.done, this.qty, this.price, this.status, this.tags, this.meta, this.in_, this.due, this.place, this.photo, this.total, this.owner, this.olusturulma, this.updated_at, this.expand});

  factory TodoItems.fromJson(Map<String, dynamic> j) => TodoItems(
        id: (j['id'] as num).toInt(),
        title: j['title'] as String?,
        done: j['done'] as bool?,
        qty: (j['qty'] as num?)?.toInt(),
        price: (j['price'] as num?)?.toDouble(),
        status: j['status'] as String?,
        tags: (j['tags'] as List?)?.cast<String>(),
        meta: j['meta'],
        in_: j['in'] as String?,
        due: j['due'] as String?,
        place: j['place'] == null ? null : GeoPoint.fromJson(j['place'] as Map<String, dynamic>),
        photo: j['photo'] as String?,
        total: (j['total'] as num?)?.toDouble(),
        owner: (j['owner'] as num?)?.toInt(),
        olusturulma: j['olusturulma'] as String?,
        updated_at: j['updated_at'] as String?,
        expand: j['expand'] as Map<String, dynamic>?,
      );
}

/// `todo_items` oluşturma/güncelleme gövdesi: verilmeyen (null) alanlar gönderilmez. Boşaltmak için `RecordCollection.updateRaw`.
class TodoItemsInput {
  final String? title;
  final bool? done;
  final int? qty;
  final double? price;
  final String? status;
  final List<String>? tags;
  final Object? meta;
  final String? in_;
  final String? due;
  final GeoPoint? place;
  final int? owner;

  const TodoItemsInput({this.title, this.done, this.qty, this.price, this.status, this.tags, this.meta, this.in_, this.due, this.place, this.owner});

  Map<String, dynamic> toJson() => {
        if (title != null) 'title': title,
        if (done != null) 'done': done,
        if (qty != null) 'qty': qty,
        if (price != null) 'price': price,
        if (status != null) 'status': status,
        if (tags != null) 'tags': tags,
        if (meta != null) 'meta': meta,
        if (in_ != null) 'in': in_,
        if (due != null) 'due': due,
        if (place != null) 'place': place?.toJson(),
        if (owner != null) 'owner': owner,
      };
}

/// SDK deneme istemcisi. `baseUrl`: API adresi, örn. `https://telefon.example.com/api`; `fallbackUrls`: canlı
/// kopyaların adresleri (ana sunucuya ulaşılamazsa veya 503 dönerse sıradaki denenir). `readUrls`: okumaların gideceği
/// kopyalar; bir yazmadan sonra `readYourWritesMs` boyunca okumalar ana sunucudan.
class DroidServerClient extends DroidServerBase {
  DroidServerClient(super.baseUrl, {super.apiKey, super.token, super.refreshToken, super.fallbackUrls, super.readUrls, super.readYourWritesMs})
      : super(realtimePath: '/realtime', refreshPath: '/auth/refresh', filesPath: '/files/{collection}/{id}/{name}');

  late final todoItems = RecordCollection<TodoItems, TodoItemsInput>(
      this, 'todo_items', const CollectionPaths(list: '/collections/todo_items/records', view: '/collections/todo_items/records/{id}', create: '/collections/todo_items/records', update: '/collections/todo_items/records/{id}', delete: '/collections/todo_items/records/{id}', sync: '/sync/todo_items', idField: 'id'), TodoItems.fromJson, (i) => i.toJson());

  late final auth = Auth(this,
      signup: '/auth/signup', login: '/auth/login', logout: '/auth/logout', me: '/auth/me',
      verify2fa: null, requestLoginCode: null, loginCode: null,
      requestSmsCode: null, smsCode: null, usage: '/auth/me/usage');

  late final teams = Teams(this,
      list: '/teams', create: '/teams', get: '/teams/{id}', update: '/teams/{id}', delete: '/teams/{id}',
      addMember: '/teams/{id}/members', updateMember: '/teams/{id}/members/{user}', removeMember: '/teams/{id}/members/{user}');

  /// QR / bağlantı metninden (`droidserver://connect?url=…&token=…` veya düz adres): JWT token, değilse API anahtarı olur.
  static DroidServerClient fromLink(String link) {
    final l = ConnectionLink.parse(link);
    final jwt = l.token != null && '.'.allMatches(l.token!).length == 2;
    return DroidServerClient(l.url, apiKey: jwt ? null : l.token, token: jwt ? l.token : null);
  }
}

// ---- Çalışma zamanı (her sunucu için aynı) ----

/// Sayfalı liste.
class Page<T> {
  final List<T> items;
  final int page;
  final int perPage;
  final int totalItems;
  final int totalPages;

  const Page(this.items, this.page, this.perPage, this.totalItems, this.totalPages);
}

/// Konum alanı; filtre: `{'konum[near]': '41.01,28.97,5'}` (km, yakından uzağa) veya `{'konum[box]': 'güney,batı,kuzey,doğu'}`.
class GeoPoint {
  final double lat;
  final double lng;
  final double? accuracy;

  const GeoPoint(this.lat, this.lng, {this.accuracy});

  factory GeoPoint.fromJson(Map<String, dynamic> j) =>
      GeoPoint((j['lat'] as num).toDouble(), (j['lng'] as num).toDouble(), accuracy: (j['accuracy'] as num?)?.toDouble());

  Map<String, dynamic> toJson() => {'lat': lat, 'lng': lng, if (accuracy != null) 'accuracy': accuracy};

  @override
  String toString() => '$lat,$lng';
}

/// Sunucunun hata yanıtı (`status`, `message`, alan başına `details`).
class DroidServerException implements Exception {
  final int status;
  final String message;
  final Map<String, String> details;

  const DroidServerException(this.status, this.message, [this.details = const {}]);

  @override
  String toString() => 'DroidServerException($status): $message${details.isEmpty ? '' : ' $details'}';
}

/// Giriş sonucu: [tokens] ya da iki adımlı doğrulama açıksa [challenge] (`Auth.verify2fa` ile tamamlayın).
class LoginResult {
  final Map<String, dynamic>? tokens;
  final String? challenge;

  const LoginResult(this.tokens, this.challenge);
}

class RealtimeEvent {
  final String action;
  final String collection;
  final Map<String, dynamic> record;

  const RealtimeEvent(this.action, this.collection, this.record);
}

/// Koleksiyonun uç yolları; [sync] eşitleme kapalıysa null, [idField] kimlik alanının API adı.
class CollectionPaths {
  final String? list;
  final String? view;
  final String? create;
  final String? update;
  final String? delete;
  final String? sync;
  final String idField;

  const CollectionPaths({this.list, this.view, this.create, this.update, this.delete, this.sync, this.idField = 'id'});
}

/// Liste seçenekleri. [filter]: `{'done': 'false', 'qty[gt]': '0', 'tags': 'yeni'}` (sorgu parametresi biçiminde).
class ListOptions {
  final int? page;
  final int? perPage;
  final String? sort;
  final String? q;
  final List<String>? fields;
  final List<String>? expand;
  final Map<String, String> filter;

  const ListOptions({this.page, this.perPage, this.sort, this.q, this.fields, this.expand, this.filter = const {}});

  Map<String, String> query() => {
        if (page != null) 'page': '$page',
        if (perPage != null) 'perPage': '$perPage',
        if (sort != null) 'sort': sort!,
        if (q != null) 'q': q!,
        if (fields != null) 'fields': fields!.join(','),
        if (expand != null) 'expand': expand!.join(','),
        ...filter,
      };
}

/// Çok parçalı gövde: alanlar ve dosyalar.
class Multipart {
  final Map<String, String> fields;
  final Map<String, List<File>> files;
  final String boundary = 'droidserver${DateTime.now().microsecondsSinceEpoch}';

  Multipart(this.fields, this.files);

  Future<List<int>> bytes() async {
    final out = BytesBuilder();
    void line(String s) => out.add(utf8.encode('$s\r\n'));
    fields.forEach((k, v) {
      line('--$boundary');
      line('Content-Disposition: form-data; name="$k"');
      line('');
      line(v);
    });
    for (final e in files.entries) {
      for (final f in e.value) {
        line('--$boundary');
        line('Content-Disposition: form-data; name="${e.key}"; filename="${f.uri.pathSegments.last.replaceAll('"', '')}"');
        line('Content-Type: application/octet-stream');
        line('');
        out.add(await f.readAsBytes());
        line('');
      }
    }
    line('--$boundary--');
    return out.takeBytes();
  }
}

/// HTTP katmanı: yetki başlığı, JSON/çok parçalı gövde, hata, 401'de bir kez token yenileme. `dart:io` kullanır
/// (Dart VM, Flutter Android/iOS/masaüstü; Flutter web desteklenmez). İş bitince [close].
abstract class DroidServerBase {
  DroidServerBase(
    String baseUrl, {
    this.apiKey,
    this.token,
    this.refreshToken,
    required this.realtimePath,
    required this.refreshPath,
    required this.filesPath,
    List<String> fallbackUrls = const [],
    List<String> readUrls = const [],
    this.readYourWritesMs = 5000,
  })  : _urls = [baseUrl, ...fallbackUrls].map(_trim).toList(),
        _reads = readUrls.map(_trim).toList() {
    _http.connectionTimeout = const Duration(seconds: 10);
  }

  String? apiKey;
  String? token;
  String? refreshToken;
  final String? realtimePath;
  final String? refreshPath;
  final String? filesPath;
  final List<String> _urls;
  int _current = 0;
  final List<String> _reads;
  int _nextRead = 0;
  int _primaryUntil = 0;

  /// Bir yazmadan sonra okumaların ana sunucudan yapıldığı süre ("kendi yazdığını oku").
  final int readYourWritesMs;
  final HttpClient _http = HttpClient();

  /// Token'lar değişince (giriş, yenileme, çıkış): saklamak için.
  void Function(String? token, String? refreshToken)? onTokens;

  /// Şu an kullanılan sunucu adresi (yedek adreslerden biri olabilir).
  String get baseUrl => _urls[_current];

  static String _trim(String u) => u.endsWith('/') ? _trim(u.substring(0, u.length - 1)) : u;

  void setTokens(String? token, String? refreshToken) {
    this.token = token;
    this.refreshToken = refreshToken;
    onTokens?.call(token, refreshToken);
  }

  /// Açık bağlantıları kapatır.
  void close() => _http.close(force: true);

  /// Ham istek; [json] veya [multipart] gövde. Yanıt JSON'u (boş yanıtta null). Yedek adres varsa: bağlantı kurulamazsa
  /// veya 503 dönerse (salt okunur kopya) sıradaki denenir, çalışan adres hatırlanır.
  Future<Object?> request(String method, String path,
      {Map<String, String> query = const {}, Object? json, Multipart? multipart, bool retry = true}) async {
    // Okuma kopyalara (sırayla); yazmadan sonra kısa süre ana sunucudan.
    if (method == 'GET' && _reads.isNotEmpty && DateTime.now().millisecondsSinceEpoch >= _primaryUntil) {
      for (var n = 0; n < _reads.length; n++) {
        final url = _reads[_nextRead++ % _reads.length];
        try {
          return await _requestAt(url, method, path, query, json, multipart, retry);
        } on DroidServerException catch (e) {
          if (e.status != 503) rethrow;
        } on IOException {
          // sıradaki
        }
      }
    }
    if (method != 'GET') _primaryUntil = DateTime.now().millisecondsSinceEpoch + readYourWritesMs;
    DroidServerException? failed;
    Object? error;
    for (var n = 0; n < _urls.length; n++) {
      final i = (_current + n) % _urls.length;
      try {
        final r = await _requestAt(_urls[i], method, path, query, json, multipart, retry);
        _current = i;
        return r;
      } on DroidServerException catch (e) {
        if (e.status != 503) {
          _current = i;
          rethrow;
        }
        failed ??= e;
      } on IOException catch (e) {
        error ??= e;
      }
    }
    throw failed ?? error!;
  }

  Uri _uri(String base, String path, Map<String, String> query) =>
      Uri.parse(base + path).replace(queryParameters: query.isEmpty ? null : query);

  Future<Object?> _requestAt(String base, String method, String path, Map<String, String> query, Object? json,
      Multipart? multipart, bool retry) async {
    final req = await _http.openUrl(method, _uri(base, path, query));
    final auth = token ?? apiKey;
    if (auth != null) req.headers.set(HttpHeaders.authorizationHeader, 'Bearer $auth');
    req.headers.set(HttpHeaders.acceptHeader, 'application/json');
    if (json != null) {
      req.headers.contentType = ContentType('application', 'json', charset: 'utf-8');
      req.add(utf8.encode(jsonEncode(json)));
    } else if (multipart != null) {
      req.headers.set(HttpHeaders.contentTypeHeader, 'multipart/form-data; boundary=${multipart.boundary}');
      req.add(await multipart.bytes());
    }
    final res = await req.close();
    final status = res.statusCode;
    final body = await utf8.decodeStream(res);
    if (status == 401 && retry && token != null && refreshToken != null && refreshPath != null && path != refreshPath && await _tryRefresh()) {
      return _requestAt(base, method, path, query, json, multipart, false);
    }
    Object? data;
    if (body.trim().isNotEmpty) {
      try {
        data = jsonDecode(body);
      } on FormatException {
        data = null;
      }
    }
    if (status >= 400) {
      final o = data is Map<String, dynamic> ? data : const <String, dynamic>{};
      final details = (o['details'] as Map<String, dynamic>?)?.map((k, v) => MapEntry(k, '$v')) ?? const <String, String>{};
      throw DroidServerException(status, (o['message'] as String?) ?? 'HTTP $status', details);
    }
    return data;
  }

  Future<bool> _tryRefresh() async {
    try {
      final t = await request('POST', refreshPath!, json: {'refreshToken': refreshToken}, retry: false) as Map<String, dynamic>;
      setTokens(t['token'] as String, t['refreshToken'] as String);
      return true;
    } on Exception {
      setTokens(null, null);
      return false;
    }
  }

  /// Dosyanın adresi (`thumb`: örn. "100x100").
  String? fileUrl(String collection, int id, String name, {String? thumb}) {
    final p = filesPath;
    if (p == null) return null;
    final path = p
        .replaceAll('{collection}', Uri.encodeComponent(collection))
        .replaceAll('{id}', '$id')
        .replaceAll('{name}', Uri.encodeComponent(name));
    return baseUrl + path + (thumb == null ? '' : '?thumb=${Uri.encodeComponent(thumb)}');
  }

  /// Canlı abonelik (Server-Sent Events): bağlantı koparsa kaldığı olaydan devam eder; hatalar akışa hata olarak gelir.
  /// Dinlemeyi iptal etmek ([StreamSubscription.cancel]) aboneliği kapatır.
  Stream<RealtimeEvent> subscribe(List<String> collections) {
    final path = realtimePath;
    if (path == null) throw const DroidServerException(404, 'Canlı abonelik kapalı');
    final http = HttpClient();
    var open = true;
    late final StreamController<RealtimeEvent> out;
    Future<void> run() async {
      String? lastId;
      while (open) {
        try {
          final req = await http.getUrl(_uri(baseUrl, path, {'collections': collections.join(',')}));
          final auth = token ?? apiKey;
          if (auth != null) req.headers.set(HttpHeaders.authorizationHeader, 'Bearer $auth');
          req.headers.set(HttpHeaders.acceptHeader, 'text/event-stream');
          if (lastId != null) req.headers.set('Last-Event-ID', lastId);
          final res = await req.close();
          if (res.statusCode != 200) {
            await res.drain<void>();
            throw DroidServerException(res.statusCode, 'Canlı abonelik açılamadı');
          }
          var event = 'message';
          final data = StringBuffer();
          await for (final line in res.transform(utf8.decoder).transform(const LineSplitter())) {
            if (!open) break;
            if (line.isEmpty) {
              if (event == 'message' && data.isNotEmpty) {
                final o = jsonDecode(data.toString()) as Map<String, dynamic>;
                out.add(RealtimeEvent(o['action'] as String, o['collection'] as String, o['record'] as Map<String, dynamic>));
              }
              event = 'message';
              data.clear();
            } else if (line.startsWith('id: ')) {
              lastId = line.substring(4);
            } else if (line.startsWith('event: ')) {
              event = line.substring(7);
            } else if (line.startsWith('data: ')) {
              data.write(line.substring(6));
            }
          }
        } catch (e, s) {
          if (open) out.addError(e, s);
        }
        if (open) await Future<void>.delayed(const Duration(seconds: 3));
      }
    }

    out = StreamController<RealtimeEvent>(
      onListen: run,
      onCancel: () {
        open = false;
        http.close(force: true);
      },
    );
    return out.stream;
  }
}

class RecordCollection<T, I> {
  final DroidServerBase _client;
  final String name;
  final CollectionPaths _paths;
  final T Function(Map<String, dynamic>) _fromJson;
  final Map<String, dynamic> Function(I) _toJson;

  RecordCollection(this._client, this.name, this._paths, this._fromJson, this._toJson);

  String _at(String? p, String key, [int? id]) {
    if (p == null) throw DroidServerException(404, "'$name' koleksiyonunda bu uç kapalı: $key");
    return id == null ? p : p.replaceAll('{id}', '$id');
  }

  T _record(Object? e) => _fromJson(e as Map<String, dynamic>);

  ({Object? json, Multipart? multipart}) _body(I data, Map<String, List<File>> files) {
    final json = _toJson(data);
    if (files.isEmpty) return (json: json, multipart: null);
    final fields = json.map((k, v) => MapEntry(k, v is String ? v : v == null ? '' : jsonEncode(v)));
    return (json: null, multipart: Multipart(fields, files));
  }

  Future<Page<T>> list([ListOptions options = const ListOptions()]) async {
    final o = await _client.request('GET', _at(_paths.list, 'list'), query: options.query()) as Map<String, dynamic>;
    return Page(
      (o['items'] as List).map((e) => _record(e)).toList(),
      (o['page'] as num).toInt(),
      (o['perPage'] as num).toInt(),
      (o['totalItems'] as num).toInt(),
      (o['totalPages'] as num).toInt(),
    );
  }

  Future<T> get(int id, {List<String>? expand}) async =>
      _record(await _client.request('GET', _at(_paths.view, 'view', id), query: expand == null ? const {} : {'expand': expand.join(',')}));

  /// [files]: dosya alanı → dosyalar.
  Future<T> create(I data, {Map<String, List<File>> files = const {}}) async {
    final b = _body(data, files);
    return _record(await _client.request('POST', _at(_paths.create, 'create'), json: b.json, multipart: b.multipart));
  }

  Future<T> update(int id, I data, {Map<String, List<File>> files = const {}}) async {
    final b = _body(data, files);
    return _record(await _client.request('PATCH', _at(_paths.update, 'update', id), json: b.json, multipart: b.multipart));
  }

  /// Ham güncelleme (örn. alanı boşaltmak: `{'note': null}`).
  Future<T> updateRaw(int id, Map<String, dynamic> body) async =>
      _record(await _client.request('PATCH', _at(_paths.update, 'update', id), json: body));

  Future<void> delete(int id) async {
    await _client.request('DELETE', _at(_paths.delete, 'delete', id));
  }

  /// Çevrimdışı kopya (koleksiyonda eşitleme açık olmalı). [key]: depodaki anahtar; sunucu başına farklı verin.
  OfflineCollection<T, I> offline(OfflineStore store, {String? key}) =>
      OfflineCollection(_client, name, _at(_paths.sync, 'sync'), _paths.idField, _fromJson, _toJson, store, key ?? 'droidserver-$name');
}

/// Abonelikler: planlar, ödeme sayfası (rol ödeme bildirimi gelince verilir) ve kullanıcının abonelikleri. Yanıtlar JSON.
class Payments {
  final DroidServerBase _client;
  final String? _plans, _subscribe, _subscriptions, _cancel;

  Payments(this._client, {String? plans, String? subscribe, String? subscriptions, String? cancel})
      : _plans = plans,
        _subscribe = subscribe,
        _subscriptions = subscriptions,
        _cancel = cancel;

  String _at(String? p, String key) {
    if (p == null) throw DroidServerException(404, 'Bu ödeme ucu kapalı: $key');
    return p;
  }

  /// `{items: [{name, title, provider, role}]}`
  Future<Map<String, dynamic>> plans() async => await _client.request('GET', _at(_plans, 'plans')) as Map<String, dynamic>;

  /// `{plan, url}`: kullanıcıyı ödeme sayfasına (`url`) yönlendirin.
  Future<Map<String, dynamic>> subscribe(String plan) async =>
      await _client.request('POST', _at(_subscribe, 'subscribe'), json: {'plan': plan}) as Map<String, dynamic>;

  /// `{items: [{id, plan, provider, status, period_end, canceling}]}`
  Future<Map<String, dynamic>> subscriptions() async => await _client.request('GET', _at(_subscriptions, 'subscriptions')) as Map<String, dynamic>;

  /// Aboneliği dönem sonunda bitirir; rol dönem bitene kadar kalır.
  Future<Map<String, dynamic>> cancel(int id) async =>
      await _client.request('POST', _at(_cancel, 'cancel').replaceAll('{id}', '$id')) as Map<String, dynamic>;
}

/// Takımlar (`team` kuralı): kullanıcının takımları; üye ekleme, rol ve silme takım sahibinindir. Yanıtlar JSON.
class Teams {
  final DroidServerBase _client;
  final String? _list, _create, _get, _update, _delete, _addMember, _updateMember, _removeMember;

  Teams(this._client,
      {String? list,
      String? create,
      String? get,
      String? update,
      String? delete,
      String? addMember,
      String? updateMember,
      String? removeMember})
      : _list = list,
        _create = create,
        _get = get,
        _update = update,
        _delete = delete,
        _addMember = addMember,
        _updateMember = updateMember,
        _removeMember = removeMember;

  String _at(String? p, String key, [int? id, int? user]) {
    if (p == null) throw DroidServerException(404, 'Bu takım ucu kapalı: $key');
    return p.replaceAll('{id}', '$id').replaceAll('{user}', '$user');
  }

  Future<Map<String, dynamic>> _call(String method, String path, [Object? json]) async =>
      await _client.request(method, path, json: json) as Map<String, dynamic>;

  /// `{items: [{id, name, role, memberCount}]}`
  Future<Map<String, dynamic>> list() => _call('GET', _at(_list, 'list'));

  Future<Map<String, dynamic>> create(String name) => _call('POST', _at(_create, 'create'), {'name': name});

  /// Takım ve üyeleri: `{id, name, role, memberCount, members: [{user, email, role}]}`
  Future<Map<String, dynamic>> get(int id) => _call('GET', _at(_get, 'get', id));

  Future<Map<String, dynamic>> rename(int id, String name) => _call('PATCH', _at(_update, 'update', id), {'name': name});

  Future<void> delete(int id) async {
    await _client.request('DELETE', _at(_delete, 'delete', id));
  }

  /// [email] veya [user] (kimlik); [role] `owner` veya `member`.
  Future<Map<String, dynamic>> addMember(int id, {String? email, int? user, String role = 'member'}) =>
      _call('POST', _at(_addMember, 'addMember', id), {if (email != null) 'email': email, if (user != null) 'user': user, 'role': role});

  Future<Map<String, dynamic>> setRole(int id, int user, String role) =>
      _call('PATCH', _at(_updateMember, 'updateMember', id, user), {'role': role});

  /// Sahip üyeyi çıkarır; kullanıcı kendi kimliğiyle çağırınca takımdan ayrılır.
  Future<void> removeMember(int id, int user) async {
    await _client.request('DELETE', _at(_removeMember, 'removeMember', id, user));
  }
}

class Auth {
  final DroidServerBase _client;
  final String? _signup, _login, _logout, _me, _verify2fa, _requestLoginCode, _loginCode, _requestSmsCode, _smsCode, _usage;

  Auth(this._client,
      {String? signup,
      String? login,
      String? logout,
      String? me,
      String? verify2fa,
      String? requestLoginCode,
      String? loginCode,
      String? requestSmsCode,
      String? smsCode,
      String? usage})
      : _signup = signup,
        _login = login,
        _logout = logout,
        _me = me,
        _verify2fa = verify2fa,
        _requestLoginCode = requestLoginCode,
        _loginCode = loginCode,
        _requestSmsCode = requestSmsCode,
        _smsCode = smsCode,
        _usage = usage;

  String _at(String? p, String key) => p ?? (throw DroidServerException(404, 'Bu hesap ucu kapalı: $key'));

  Future<LoginResult> _keep(Future<Object?> call) async {
    final o = await call as Map<String, dynamic>;
    final token = o['token'] as String?;
    final refresh = o['refreshToken'] as String?;
    if (token != null && refresh != null) _client.setTokens(token, refresh);
    return LoginResult(token != null ? o : null, o['challenge'] as String?);
  }

  /// Gövde: email, password ve ek alanlar.
  Future<LoginResult> signup(Map<String, dynamic> body) => _keep(_client.request('POST', _at(_signup, 'signup'), json: body));

  Future<LoginResult> login(String email, String password) =>
      _keep(_client.request('POST', _at(_login, 'login'), json: {'email': email, 'password': password}));

  Future<LoginResult> verify2fa(String challenge, String code) =>
      _keep(_client.request('POST', _at(_verify2fa, 'verify2fa'), json: {'challenge': challenge, 'code': code}));

  Future<void> requestLoginCode(String email) async {
    await _client.request('POST', _at(_requestLoginCode, 'requestLoginCode'), json: {'email': email});
  }

  Future<LoginResult> loginWithCode(String email, String code) =>
      _keep(_client.request('POST', _at(_loginCode, 'loginCode'), json: {'email': email, 'code': code}));

  /// Numara uluslararası biçimde (+905551234567); kod sunucu telefonundan SMS ile gelir.
  Future<void> requestSmsCode(String phone) async {
    await _client.request('POST', _at(_requestSmsCode, 'requestSmsCode'), json: {'phone': phone});
  }

  Future<LoginResult> loginWithSms(String phone, String code) =>
      _keep(_client.request('POST', _at(_smsCode, 'smsCode'), json: {'phone': phone, 'code': code}));

  Future<void> logout() async {
    final r = _client.refreshToken;
    if (r != null) {
      try {
        await _client.request('POST', _at(_logout, 'logout'), json: {'refreshToken': r});
      } on Exception {
        // Sunucuya ulaşılamasa da yerel oturum kapanır.
      }
    }
    _client.setTokens(null, null);
  }

  Future<Map<String, dynamic>> me() async => await _client.request('GET', _at(_me, 'me')) as Map<String, dynamic>;

  Future<Map<String, dynamic>> usage() async => await _client.request('GET', _at(_usage, 'usage')) as Map<String, dynamic>;
}

/// Telefondaki "Bağlan (QR)" ekranının metni: `droidserver://connect?url=…&name=…&token=…&exp=…` veya düz adres.
class ConnectionLink {
  final String url;
  final String? name;
  final String? token;
  final int? expiresAtEpochSec;

  const ConnectionLink(this.url, {this.name, this.token, this.expiresAtEpochSec});

  static ConnectionLink parse(String link) {
    final t = link.trim();
    if (t.startsWith('http://') || t.startsWith('https://')) return ConnectionLink(DroidServerBase._trim(t));
    if (!t.startsWith('droidserver://connect?')) throw const FormatException('DroidServer bağlantısı değil');
    final q = Uri.splitQueryString(t.substring(t.indexOf('?') + 1));
    final url = q['url'];
    if (url == null || !(url.startsWith('http://') || url.startsWith('https://'))) throw const FormatException('Bağlantıda adres yok');
    return ConnectionLink(url, name: q['name'], token: q['token'], expiresAtEpochSec: int.tryParse(q['exp'] ?? ''));
  }
}

// ---- Çevrimdışı eşitleme (koleksiyonda `sync` açıkken) ----

/// Çevrimdışı deponun saklama yeri: anahtar → JSON metni. SQLite gibi başka bir depo bu arayüzle takılabilir.
abstract class OfflineStore {
  Future<String?> read(String key);
  Future<void> write(String key, String value);
}

/// Bellekte (uygulama kapanınca silinir; denemeler için).
class MemoryOfflineStore implements OfflineStore {
  final Map<String, String> _values = {};

  @override
  Future<String?> read(String key) async => _values[key];

  @override
  Future<void> write(String key, String value) async => _values[key] = value;
}

/// Klasörde anahtar başına bir dosya (Flutter'da `getApplicationSupportDirectory()`). Yazma önce geçici dosyaya.
class FileOfflineStore implements OfflineStore {
  final Directory dir;

  FileOfflineStore(this.dir);

  File _file(String key) => File('${dir.path}/${key.replaceAll(RegExp(r'[^A-Za-z0-9._-]'), '_')}.json');

  @override
  Future<String?> read(String key) async {
    final f = _file(key);
    return await f.exists() ? f.readAsString() : null;
  }

  @override
  Future<void> write(String key, String value) async {
    await dir.create(recursive: true);
    final target = _file(key);
    final tmp = File('${target.path}.tmp');
    await tmp.writeAsString(value, flush: true);
    await tmp.rename(target.path);
  }
}

/// Eşitleme çakışması. `merged`: son yazan kazanır, bazı alanlarda sunucunun değeri kaldı; `conflict`: elle çözme kipinde
/// değişiklik uygulanmadı ya da silinen kayıt sunucuda daha yeni. [local] gönderilen alanlar, [server] kaydın sunucudaki
/// (artık yerelde de olan) hali. Elle çözmek için kaydı yeniden `update` edin.
class SyncConflictInfo {
  final int id;
  final String status;
  final Map<String, dynamic> local;
  final Map<String, dynamic>? server;

  const SyncConflictInfo(this.id, this.status, this.local, this.server);
}

/// Bir koleksiyonun çevrimdışı kopyası. Okuma ve yazma yereldir; yazmalar kayıt başına birleştirilerek bekler ve [sync]
/// ile gönderilir, sonra sunucudaki değişiklikler çekilir. Çevrimdışı oluşturulan kaydın kimliği eşitlenene kadar
/// negatiftir. Dosya alanları yalnızca adıyla gelir (dosya indirilmez, çevrimdışıyken yüklenmez). Çevrimdışı oluşturulan
/// kayda başka koleksiyondan ilişki, kayıt eşitlendikten sonra kurulmalı. İşlemler sırayla çalışır.
class OfflineCollection<T, I> {
  final DroidServerBase _client;
  final String name;
  final String _path;
  final String _idField;
  final T Function(Map<String, dynamic>) _fromJson;
  final Map<String, dynamic> Function(I) _toJson;
  final OfflineStore _store;
  final String _key;

  /// Çakışma bildirimi (bkz. [SyncConflictInfo]).
  void Function(SyncConflictInfo)? onConflict;

  /// Sunucunun kabul etmediği (doğrulama, kural) ve kuyruktan çıkarılan değişiklik.
  void Function(int id, DroidServerException error)? onRejected;

  Map<String, dynamic>? _state;
  Future<void> _chain = Future.value();

  OfflineCollection(this._client, this.name, this._path, this._idField, this._fromJson, this._toJson, this._store, this._key);

  Future<R> _run<R>(Future<R> Function(Map<String, dynamic> s) f) {
    final result = _chain.then((_) async => f(await _load()));
    _chain = result.then((_) {}, onError: (_) {});
    return result;
  }

  Future<Map<String, dynamic>> _load() async {
    if (_state == null) {
      final text = await _store.read(_key);
      _state = text == null
          ? {'cursor': 0, 'nextTemp': -1, 'records': <String, dynamic>{}, 'pending': <dynamic>[]}
          : jsonDecode(text) as Map<String, dynamic>;
    }
    return _state!;
  }

  Future<void> _save(Map<String, dynamic> s) => _store.write(_key, jsonEncode(s));

  Map<String, dynamic> _records(Map<String, dynamic> s) => s['records'] as Map<String, dynamic>;
  List<dynamic> _pending(Map<String, dynamic> s) => s['pending'] as List<dynamic>;
  Map<String, dynamic>? _op(Map<String, dynamic> s, int id) =>
      _pending(s).cast<Map<String, dynamic>>().where((p) => p['id'] == id).firstOrNull;

  /// Yereldeki kayıtlar (bekleyen değişiklikler dahil).
  Future<List<T>> all() => _run((s) async => _records(s).values.map((x) => _fromJson((x as Map<String, dynamic>)['r'] as Map<String, dynamic>)).toList());

  Future<T?> get(int id) => _run((s) async {
        final x = _records(s)['$id'] as Map<String, dynamic>?;
        return x == null ? null : _fromJson(x['r'] as Map<String, dynamic>);
      });

  /// Gönderilmeyi bekleyen değişiklik sayısı.
  Future<int> pendingCount() => _run((s) async => _pending(s).length);

  Future<T> create(I data) => _run((s) async {
        final fields = _toJson(data);
        final id = s['nextTemp'] as int;
        s['nextTemp'] = id - 1;
        final now = DateTime.now().millisecondsSinceEpoch;
        final record = {...fields, _idField: id};
        _records(s)['$id'] = {'v': 0, 'r': record};
        _pending(s).add({'op': 'create', 'id': id, 'base': 0, 'fields': fields, 'times': {for (final k in fields.keys) k: now}, 'time': now});
        await _save(s);
        return _fromJson(record);
      });

  Future<T> update(int id, I data) => _run((s) async {
        final cur = _records(s)['$id'] as Map<String, dynamic>?;
        if (cur == null) throw DroidServerException(404, 'Kayıt yerelde yok: $id');
        final fields = _toJson(data);
        final now = DateTime.now().millisecondsSinceEpoch;
        cur['r'] = {...(cur['r'] as Map<String, dynamic>), ...fields};
        final op = _op(s, id);
        if (op == null) {
          _pending(s).add({'op': 'update', 'id': id, 'base': cur['v'], 'fields': fields, 'times': {for (final k in fields.keys) k: now}, 'time': now});
        } else {
          (op['fields'] as Map<String, dynamic>).addAll(fields);
          for (final k in fields.keys) {
            (op['times'] as Map<String, dynamic>)[k] = now;
          }
          op['time'] = now;
        }
        await _save(s);
        return _fromJson(cur['r'] as Map<String, dynamic>);
      });

  Future<void> delete(int id) => _run((s) async {
        final cur = _records(s).remove('$id') as Map<String, dynamic>?;
        if (cur == null) return;
        final op = _op(s, id);
        if (op != null) _pending(s).remove(op);
        // Henüz gönderilmemiş kayıt: sunucuya hiç gitmez.
        if (op?['op'] != 'create') {
          _pending(s).add({'op': 'delete', 'id': id, 'base': op?['base'] ?? cur['v'], 'fields': {}, 'times': {}, 'time': DateTime.now().millisecondsSinceEpoch});
        }
        await _save(s);
      });

  /// Bekleyen değişiklikleri gönderir, sonra sunucudaki değişiklikleri çeker. Ağ hatasında hata fırlatır (bekleyenler kalır).
  Future<void> sync() => _run((s) async {
        await _push(s);
        await _pull(s);
      });

  Future<void> _push(Map<String, dynamic> s) async {
    final pending = _pending(s);
    final records = _records(s);
    while (pending.isNotEmpty) {
      final batch = pending.take(100).cast<Map<String, dynamic>>().toList();
      final changes = [for (final p in batch) {...p, if (p['op'] == 'create') 'tempId': p['id']}];
      final r = await _client.request('POST', _path, json: {'now': DateTime.now().millisecondsSinceEpoch, 'changes': changes}) as Map<String, dynamic>;
      final results = (r['results'] as List).cast<Map<String, dynamic>>();
      for (var n = 0; n < batch.length; n++) {
        final op = batch[n], o = results[n];
        pending.remove(op);
        final status = o['status'] as String;
        final id = (o['id'] as num?)?.toInt();
        final server = o['record'] as Map<String, dynamic>?;
        if (status == 'ok' || status == 'merged' || status == 'conflict') {
          if (op['op'] == 'create') records.remove('${op['id']}');
          if (server != null && id != null) {
            records['$id'] = {'v': (o['version'] as num?)?.toInt() ?? 0, 'r': server};
          } else if (id != null) {
            records.remove('$id');
          }
          if (status != 'ok') onConflict?.call(SyncConflictInfo(id ?? op['id'] as int, status, op['fields'] as Map<String, dynamic>, server));
        } else if (status == 'gone') {
          records.remove('${op['id']}');
        } else {
          final code = (o['code'] as num?)?.toInt() ?? 0;
          final details = (o['details'] as Map<String, dynamic>?)?.map((k, v) => MapEntry(k, '$v')) ?? const <String, String>{};
          final e = DroidServerException(code, (o['message'] as String?) ?? 'HTTP $code', details);
          if (code >= 500) {
            // Sunucu tarafı geçici hata: sonra yeniden denenir.
            pending.insert(0, op);
            await _save(s);
            throw e;
          }
          onRejected?.call(op['id'] as int, e);
        }
      }
      await _save(s);
    }
  }

  Future<void> _pull(Map<String, dynamic> s) async {
    final records = _records(s);
    final pending = _pending(s).cast<Map<String, dynamic>>();
    for (;;) {
      final query = {'since': '${s['cursor']}', 'limit': '500', if (s['epoch'] != null) 'epoch': s['epoch'] as String};
      final r = await _client.request('GET', _path, query: query) as Map<String, dynamic>;
      if (r['reset'] == true) {
        // Sunucudaki iz yenilendi: yerel kopya baştan kurulur (gönderilmemiş yeni kayıtlar kalır).
        records.removeWhere((k, _) => int.parse(k) > 0);
        s['cursor'] = 0;
      }
      s['epoch'] = r['epoch'];
      for (final c in (r['changes'] as List).cast<Map<String, dynamic>>()) {
        final id = (c['id'] as num).toInt();
        if (pending.any((p) => p['id'] == id)) continue; // yerel değişiklik bekliyor
        if (c['action'] == 'upsert') {
          records['$id'] = {'v': (c['version'] as num).toInt(), 'r': c['record']};
        } else {
          records.remove('$id');
        }
      }
      s['cursor'] = (r['next'] as num).toInt();
      await _save(s);
      if (r['more'] != true) break;
    }
  }

  /// Canlı abonelikle: sunucuda değişiklik olunca [sync] çalışır (art arda olaylar birleşir). Dönen aboneliği iptal etmek
  /// durdurur; hatalar [onError]'a.
  StreamSubscription<RealtimeEvent> live({void Function(Object error)? onError}) {
    var busy = false, again = false;
    Future<void> go() async {
      if (busy) {
        again = true;
        return;
      }
      busy = true;
      try {
        do {
          again = false;
          try {
            await sync();
          } catch (e) {
            onError?.call(e);
          }
        } while (again);
      } finally {
        busy = false;
      }
    }

    unawaited(go());
    return _client.subscribe([name]).listen((_) => unawaited(go()), onError: (Object e) => onError?.call(e));
  }
}
