#!/usr/bin/env python3
"""plyground.org/droidserver sitesini üretir: python3 site/build.py → site/dist/droidserver/

Kaynaklar: tanıtım sayfaları site/src/<dil>/index.html (gövde parçası), rehber ve SSS site/src/<dil>/*.md, gizlilik ve
kullanım koşulları depo kökündeki PRIVACY*.md ve TERMS*.md (tek kaynak; GitHub'daki metinle aynı). Yalnızca Python'un
standart kitaplığı gerekir; görselleri küçültmek için Pillow varsa kullanılır, yoksa olduğu gibi kopyalanır.
"""
import html
import re
import shutil
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "site" / "src"
OUT = ROOT / "site" / "dist" / "droidserver"
GITHUB = "https://github.com/kasimtmc/droidserver"
PLAY = "https://play.google.com/store/apps/details?id=com.plyg.droidserver"

# Her dilde sayfalar: (anahtar, dosya adı, menü adı, kaynak)
PAGES = {
    "tr": [
        ("home", "index.html", "Ana sayfa", SRC / "tr" / "index.html"),
        ("guide", "nasil-kullanilir.html", "Nasıl kullanılır?", SRC / "tr" / "nasil-kullanilir.md"),
        ("faq", "sss.html", "SSS", SRC / "tr" / "sss.md"),
        ("privacy", "gizlilik.html", "Gizlilik", ROOT / "PRIVACY.md"),
        ("terms", "kosullar.html", "Kullanım koşulları", ROOT / "TERMS.md"),
    ],
    "en": [
        ("home", "index.html", "Home", SRC / "en" / "index.html"),
        ("guide", "how-to-use.html", "How to use", SRC / "en" / "how-to-use.md"),
        ("faq", "faq.html", "FAQ", SRC / "en" / "faq.md"),
        ("privacy", "privacy.html", "Privacy", ROOT / "PRIVACY.en.md"),
        ("terms", "terms.html", "Terms of use", ROOT / "TERMS.en.md"),
    ],
}
LANG_NAME = {"tr": "Türkçe", "en": "English"}
DESCRIPTION = {
    "tr": "DroidServer: Android telefonunuzu veritabanı, API, kullanıcı hesapları ve web paneli olan bir sunucuya çevirin.",
    "en": "DroidServer: turn your Android phone into a server with a database, an API, user accounts and a web panel.",
}
# Depodaki Markdown bağlantıları sitedeki karşılıklarına.
LINKS = {"PRIVACY.md": "gizlilik.html", "PRIVACY.en.md": "privacy.html", "TERMS.md": "kosullar.html", "TERMS.en.md": "terms.html"}
IMAGES = ["01-karsilama.png", "02-durum.png", "06-sunucu.png", "07-bilesenler.png", "09-sihirbaz.png"]


def slug(text):
    s = re.sub(r"<[^>]+>", "", text).lower()
    s = re.sub(r"[^\w\s-]", "", s, flags=re.UNICODE)
    return re.sub(r"\s+", "-", s.strip())


def inline(text):
    """Satır içi Markdown: kod, kalın, eğik, görsel, bağlantı ve <adres>."""
    codes = []
    text = re.sub(r"`([^`]+)`", lambda m: codes.append(m.group(1)) or f"\x00{len(codes) - 1}\x00", text)
    text = html.escape(text, quote=False)
    text = re.sub(r"!\[([^\]]*)\]\(([^)]+)\)", lambda m: f'<img src="{m.group(2)}" alt="{m.group(1)}" loading="lazy">', text)
    text = re.sub(r"\[([^\]]+)\]\(([^)]+)\)", lambda m: f'<a href="{LINKS.get(m.group(2), m.group(2))}">{m.group(1)}</a>', text)
    text = re.sub(r"&lt;(https?://[^&\s]+)&gt;", r'<a href="\1">\1</a>', text)
    text = re.sub(r"\*\*([^*]+)\*\*", r"<strong>\1</strong>", text)
    text = re.sub(r"(?<![\w*])\*([^*\s][^*]*)\*(?![\w*])", r"<em>\1</em>", text)
    return re.sub(r"\x00(\d+)\x00", lambda m: f"<code>{html.escape(codes[int(m.group(1))], quote=False)}</code>", text)


def markdown(src):
    """Belgelerde kullanılan Markdown alt kümesi: başlık, paragraf, liste, tablo, kod bloğu, görsel."""
    out, lines, i = [], src.splitlines(), 0
    while i < len(lines):
        line = lines[i]
        if not line.strip():
            i += 1
        elif line.startswith("```"):
            j = i + 1
            while j < len(lines) and not lines[j].startswith("```"):
                j += 1
            out.append("<pre><code>" + html.escape("\n".join(lines[i + 1:j]), quote=False) + "</code></pre>")
            i = j + 1
        elif m := re.match(r"(#{1,4}) (.*)", line):
            level, text = len(m.group(1)), inline(m.group(2))
            out.append(f'<h{level} id="{slug(m.group(2))}">{text}</h{level}>')
            i += 1
        elif line.startswith("|"):
            rows = []
            while i < len(lines) and lines[i].startswith("|"):
                cells = [c.strip() for c in lines[i].strip().strip("|").split("|")]
                if not all(re.fullmatch(r":?-+:?", c) for c in cells):
                    rows.append(cells)
                i += 1
            head, body = rows[0], rows[1:]
            out.append('<div class="table"><table><thead><tr>' + "".join(f"<th>{inline(c)}</th>" for c in head) + "</tr></thead><tbody>"
                       + "".join("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in r) + "</tr>" for r in body) + "</tbody></table></div>")
        elif re.match(r"(- |\d+\. )", line):
            tag = "ol" if line[0].isdigit() else "ul"
            items = []
            while i < len(lines) and (re.match(r"(- |\d+\. )", lines[i]) or (lines[i].startswith("  ") and items)):
                if re.match(r"(- |\d+\. )", lines[i]):
                    items.append(re.sub(r"^(- |\d+\. )", "", lines[i]))
                else:
                    items[-1] += " " + lines[i].strip()
                i += 1
            out.append(f"<{tag}>" + "".join(f"<li>{inline(t)}</li>" for t in items) + f"</{tag}>")
        else:
            para = []
            while i < len(lines) and lines[i].strip() and not re.match(r"(#{1,4} |```|\||- |\d+\. )", lines[i]):
                para.append(lines[i].strip())
                i += 1
            text = " ".join(para)
            # GitHub'daki dil seçimi satırı ("**Türkçe** | [English](…)"): sitede üst çubukta var.
            if re.fullmatch(r"\*\*\w+\*\* \| \[\w+\]\([^)]+\)|\[\w+\]\([^)]+\) \| \*\*\w+\*\*", text):
                continue
            out.append(f"<p>{inline(text)}</p>")
    return "\n".join(out)


def layout(lang, key, title, body):
    other = "en" if lang == "tr" else "tr"
    other_file = next(f for k, f, _, _ in PAGES[other] if k == key)
    nav = "".join(
        f'<a href="{f}"{" aria-current=\"page\"" if k == key else ""}>{name}</a>' for k, f, name, _ in PAGES[lang] if k != "home"
    )
    files = {k: f for k, f, _, _ in PAGES[lang]}
    privacy, terms = (("Gizlilik", "Kullanım koşulları") if lang == "tr" else ("Privacy", "Terms of use"))
    page_title = "DroidServer" if key == "home" else f"{title} · DroidServer"
    return f"""<!doctype html>
<html lang="{lang}">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light dark">
<title>{html.escape(page_title)}</title>
<meta name="description" content="{html.escape(DESCRIPTION[lang])}">
<link rel="icon" href="../img/icon.png">
<link rel="alternate" hreflang="{other}" href="../{other}/{other_file}">
<link rel="stylesheet" href="../style.css">
</head>
<body class="page-{key}">
<header class="top">
  <a class="brand" href="index.html"><img src="../img/icon.png" alt="" width="32" height="32">DroidServer</a>
  <nav>{nav}<a class="lang" href="../{other}/{other_file}" hreflang="{other}" lang="{other}">{LANG_NAME[other]}</a></nav>
</header>
<main>
{body}
</main>
<footer>
  <span>© 2026 <a href="../../">plyGround</a></span>
  <a href="{files['privacy']}">{privacy}</a>
  <a href="{files['terms']}">{terms}</a>
  <a href="{GITHUB}">GitHub</a>
</footer>
</body>
</html>
"""


def images():
    dst = OUT / "img"
    dst.mkdir(parents=True, exist_ok=True)
    try:
        from PIL import Image
    except ImportError:
        Image = None
    sources = [(ROOT / "fastlane/metadata/android/tr-TR/images/icon.png", "icon.png", 192)] + [
        (ROOT / "screenshots" / n, n, 720) for n in IMAGES
    ]
    # İngilizce sayfalar: gerçek telefondan İngilizce arayüz görüntüleri (durum çubuğu kırpılmış).
    (dst / "en").mkdir(exist_ok=True)
    sources += [(f, f"en/{f.name}", 720) for f in sorted((ROOT / "screenshots" / "en").glob("*.jpg"))]
    for src, name, width in sources:
        if Image is None:
            shutil.copy(src, dst / name)
            continue
        im = Image.open(src)
        if im.width > width:
            im = im.resize((width, round(im.height * width / im.width)), Image.LANCZOS)
        im.save(dst / name, optimize=True, **({"quality": 85} if name.endswith(".jpg") else {}))


def main():
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    shutil.copy(SRC / "style.css", OUT / "style.css")
    images()
    for lang, pages in PAGES.items():
        (OUT / lang).mkdir()
        for key, name, menu, src in pages:
            text = src.read_text(encoding="utf-8")
            body = text.replace("{PLAY}", PLAY).replace("{GITHUB}", GITHUB) if src.suffix == ".html" else markdown(text)
            title = menu if key != "home" else "DroidServer"
            wrapped = body if key == "home" else f'<article class="doc">\n{body}\n</article>'
            (OUT / lang / name).write_text(layout(lang, key, title, wrapped), encoding="utf-8")
    # Kök: tarayıcının diline göre; betik çalışmazsa bağlantılar.
    (OUT / "index.html").write_text("""<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>DroidServer</title>
<script>
var l = (navigator.languages && navigator.languages[0]) || navigator.language || 'en';
location.replace((/^tr\\b/i.test(l) ? 'tr' : 'en') + '/index.html');
</script></head>
<body><p><a href="tr/index.html">Türkçe</a> · <a href="en/index.html">English</a></p></body></html>
""", encoding="utf-8")
    print(f"site: {OUT}")


if __name__ == "__main__":
    main()
