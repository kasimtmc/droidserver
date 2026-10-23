// SdkClientTest'in Dart denemesi: üretilen istemci gerçek sunucuya karşı. Argümanlar: API adresi, yönetici anahtarı,
// ulaşılamayan bir adres. Başarıda "TAMAM" yazar.
import 'dart:async';
import 'dart:io';

import 'droidserver.dart';

void check(bool ok, String what) {
  if (!ok) throw StateError(what);
}

Future<int> status(Future<Object?> f) async {
  try {
    await f;
  } on DroidServerException catch (e) {
    return e.status;
  }
  return 0;
}

Future<void> main(List<String> args) async {
  final base = args[0], key = args[1], dead = args[2];

  final l = ConnectionLink.parse('droidserver://connect?url=${Uri.encodeComponent('http://x/api')}&name=Evim&token=a.b.c&exp=1800000000');
  check(l.url == 'http://x/api' && l.name == 'Evim' && l.token == 'a.b.c' && l.expiresAtEpochSec == 1800000000, 'bağlantı');
  final linked = DroidServerClient.fromLink('droidserver://connect?url=${Uri.encodeComponent(base)}&token=ds_xyz');
  check(linked.apiKey == 'ds_xyz' && linked.token == null && linked.baseUrl == base, 'fromLink');
  check(DroidServerClient.fromLink('droidserver://connect?url=http%3A%2F%2Fx&token=a.b.c').token == 'a.b.c', 'fromLink jwt');
  linked.close();

  final admin = DroidServerClient(base, apiKey: key);
  check((await admin.todoItems.list()).totalItems == 0, 'boş liste');

  final ds = DroidServerClient(base);
  final saved = <String?>[];
  ds.onTokens = (t, _) => saved.add(t);
  final signup = await ds.auth.signup({'email': 'dart@example.com', 'password': 'parola-1234'});
  check(signup.tokens != null && ds.token != null, 'kayıt');
  final myId = ((await ds.auth.me())['id'] as num).toInt();

  // Canlı abonelik: oluşturma olayı gelir.
  final got = Completer<RealtimeEvent>();
  final sub = ds.subscribe(['todo_items']).listen((e) {
    if (!got.isCompleted) got.complete(e);
  });
  await Future<void>.delayed(const Duration(milliseconds: 300));

  final t = await ds.todoItems.create(const TodoItemsInput(
      title: 'Süt', qty: 2, price: 1.5, status: 'açık', tags: ['ev'], meta: {'a': 1}, in_: 'x', place: GeoPoint(41.0054, 28.9768)));
  check(t.total == 3.0, 'hesaplanan alan');
  check(t.place?.lat == 41.0054 && t.place?.lng == 28.9768, 'konum');
  final near = await ds.todoItems.list(const ListOptions(filter: {'place[near]': '41.0,29.0,5'}));
  check(near.items.map((x) => x.title).join() == 'Süt', 'yakın');
  check(t.owner == myId, 'sahip alanı');
  check(t.olusturulma != null, 'uzman modu adı');
  check(t.in_ == 'x' && t.tags!.single == 'ev' && (t.meta as Map)['a'] == 1, 'alanlar');
  final e = await got.future.timeout(const Duration(seconds: 5));
  check(e.action == 'create' && e.collection == 'todo_items' && e.record['title'] == 'Süt', 'canlı olay');
  await sub.cancel();

  // Liste, filtre, PATCH, alan boşaltma.
  await ds.todoItems.create(const TodoItemsInput(title: 'Ekmek', tags: ['iş']));
  final page = await ds.todoItems.list(const ListOptions(filter: {'tags': 'ev'}, sort: '-id'));
  check(page.items.map((i) => i.title).join(',') == 'Süt', 'filtre');
  final updated = await ds.todoItems.update(t.id, const TodoItemsInput(done: true, qty: 4));
  check(updated.done == true && updated.total == 6.0, 'güncelleme');
  check((await ds.todoItems.updateRaw(t.id, {'in': null})).in_ == null, 'boşaltma');

  // Dosya yükleme ve adres.
  final dir = await Directory.systemTemp.createTemp('ds-dart');
  final png = File('${dir.path}/foto.png')..writeAsBytesSync([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, ...List.filled(64, 0)]);
  final withPhoto = await ds.todoItems.create(const TodoItemsInput(title: 'Fotoğraf'), files: {'photo': [png]});
  final name = withPhoto.photo!;
  check(ds.fileUrl('todo_items', withPhoto.id, name)!.endsWith('/api/files/todo_items/${withPhoto.id}/$name'), 'dosya adresi');
  await dir.delete(recursive: true);

  // Doğrulama hatası alan başına; silinen kayıt 404.
  try {
    await ds.todoItems.create(const TodoItemsInput(status: 'yok'));
    check(false, 'doğrulama hatası bekleniyordu');
  } on DroidServerException catch (x) {
    check(x.status == 400 && x.details.containsKey('title') && x.details.containsKey('status'), 'doğrulama ${x.details}');
  }
  await ds.todoItems.delete(t.id);
  check(await status(ds.todoItems.get(t.id)) == 404, 'silindi');

  // Erişim token'ı geçersizse yenileme token'ıyla bir kez yenilenip tekrarlanır.
  ds.token = 'bozuk';
  check((await ds.todoItems.list()).totalItems == 2, 'yenileme');
  check(saved.length >= 2 && saved.last != 'bozuk', 'token saklandı');

  // Çevrimdışı kopya: yerel yazma, eşitleme, başka istemcinin değişikliği, çakışma, silme, depodan yeniden açma.
  final storeDir = await Directory.systemTemp.createTemp('ds-offline');
  final store = FileOfflineStore(storeDir);
  final off = ds.todoItems.offline(store);
  final conflicts = <String>[];
  off.onConflict = (c) => conflicts.add(c.status);
  await off.sync();
  final local = await off.create(const TodoItemsInput(title: 'Çevrimdışı', qty: 1));
  check(local.id < 0 && await off.pendingCount() == 1, 'geçici kimlik');
  await off.update(local.id, const TodoItemsInput(qty: 2));
  await off.sync();
  final synced = (await off.all()).firstWhere((r) => r.title == 'Çevrimdışı');
  check(synced.id > 0 && synced.qty == 2 && await off.pendingCount() == 0, 'eşitleme');
  await ds.todoItems.update(synced.id, const TodoItemsInput(title: 'sunucuda'));
  await off.sync();
  check((await off.get(synced.id))?.title == 'sunucuda', 'çekme');
  await off.update(synced.id, const TodoItemsInput(title: 'yerel', price: 9));
  await Future<void>.delayed(const Duration(milliseconds: 20));
  await ds.todoItems.update(synced.id, const TodoItemsInput(title: 'sunucu'));
  await off.sync();
  final merged = await off.get(synced.id);
  check(merged?.title == 'sunucu' && merged?.price == 9 && conflicts.join() == 'merged', 'çakışma ${merged?.title} $conflicts');
  await off.delete(synced.id);
  await off.sync();
  check(await ds.todoItems.offline(FileOfflineStore(storeDir)).get(synced.id) == null, 'silme ve yeniden açma');
  await storeDir.delete(recursive: true);

  // Takımlar.
  final team = await ds.teams.create('Mutfak');
  check(team['role'] == 'owner', 'takım rolü');
  final id = (team['id'] as num).toInt();
  check(((await ds.teams.get(id))['members'] as List).length == 1, 'takım üyeleri');
  check((await ds.teams.rename(id, 'Ev'))['name'] == 'Ev', 'takım adı');
  check(((await ds.teams.list())['items'] as List).length == 1, 'takım listesi');
  await ds.teams.delete(id);
  await ds.auth.logout();
  check(ds.token == null, 'çıkış');
  check(await status(ds.todoItems.list()) == 401, 'çıkıştan sonra 401');

  // Yedek adres: ana sunucuya ulaşılamıyor, sıradaki denenir ve hatırlanır.
  final fb = DroidServerClient(dead, apiKey: key, fallbackUrls: [base]);
  await fb.todoItems.list();
  check(fb.baseUrl == base, 'yedek adres ${fb.baseUrl}');

  // Okuma kopyaya; yazmadan hemen sonra ana sunucuya.
  final rd = DroidServerClient(base, apiKey: key, readUrls: [args[3]], readYourWritesMs: 300);
  Future<String> health() async => ((await rd.request('GET', '/health')) as Map<String, dynamic>)['name'] as String;
  check(await health() == 'Okuma', 'okuma kopyaya');
  await rd.todoItems.create(const TodoItemsInput(title: 'okuma denemesi'));
  check(await health() == 'SDK deneme', 'kendi yazdığını oku');
  await Future<void>.delayed(const Duration(milliseconds: 400));
  check(await health() == 'Okuma', 'süre bitince yine kopya');

  for (final c in [admin, ds, fb, rd]) {
    c.close();
  }
  print('TAMAM');
}
