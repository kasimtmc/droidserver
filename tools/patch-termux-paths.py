"""Termux derlemelerine gömülü geçici klasör yolunu uygulamanın klasörüne çevirir.

Termux kütüphaneleri (libandroid-shmem, libandroid-posix-semaphore …) ve PostgreSQL araçları geçici dosyalarını
derleme anında sabitlenen /data/data/com.termux/files/usr/tmp altına yazar. Termux kurulu olmayan telefonda bu klasör
yoktur; libandroid-shmem orada bağlantı oluşturamayınca sonsuz döngüye girer (initdb "selecting default
max_connections" adımında takılır). Yol, aynı uzunlukta uygulamanın klasörüyle değiştirilir (dizgelerin uzunluğu
binary'de sabittir; baştaki fazladan eğik çizgiler yolu değiştirmez). Uygulama bu klasörü açılışta oluşturur.

Kullanım: python3 -I tools/patch-termux-paths.py <dosya>...   (değişen dosya ve sayılar yazılır)
"""
import sys

OLD = b"/data/data/com.termux/files/usr/tmp"
NEW = b"/data/data/com.plyg.droidserver/t"
NEW = b"/" * (len(OLD) - len(NEW)) + NEW
assert len(NEW) == len(OLD)

total = 0
for path in sys.argv[1:]:
    with open(path, "rb") as f:
        data = f.read()
    n = data.count(OLD)
    if n == 0:
        continue
    with open(path, "wb") as f:
        f.write(data.replace(OLD, NEW))
    total += n
    print(f"{path}: {n} yol")
print(f"Geçici klasör yolu {total} yerde {NEW.decode()} yapıldı")
