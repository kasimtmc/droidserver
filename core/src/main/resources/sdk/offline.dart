
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
    // Abonelik kurulunca (ve her yeniden bağlanmada) bir kez daha: ilk eşitlemeyle bağlantı arasındaki değişiklik kaçmasın.
    return _client.subscribe([name], onReady: () => unawaited(go())).listen((_) => unawaited(go()), onError: (Object e) => onError?.call(e));
  }
}
