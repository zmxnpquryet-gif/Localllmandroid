# -*- coding: utf-8 -*-
"""汉化编辑库：代码替换（精确断言）+ 三语言 strings.xml 注入"""
import io, re, os

REPO = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
BASE = os.path.join(REPO, 'app/src/main/java/com/localllm/android')
RES = {
    'en': os.path.join(REPO, 'app/src/main/res/values/strings.xml'),
    'ko': os.path.join(REPO, 'app/src/main/res/values-ko/strings.xml'),
    'zh': os.path.join(REPO, 'app/src/main/res/values-zh/strings.xml'),
}

def _read(p): return io.open(p, encoding='utf-8').read()
def _write(p, s): io.open(p, 'w', encoding='utf-8').write(s)

def edit(rel_path, pairs, counts=None):
    """pairs: [(old, new)]；counts: {old: expected_occurrence} 默认全为 1"""
    p = os.path.join(BASE, rel_path)
    s = _read(p)
    for old, new in pairs:
        expect = (counts or {}).get(old, 1)
        got = s.count(old)
        assert got == expect, "%s 期望×%d 实际×%d: %r" % (rel_path, expect, got, old[:80])
        s = s.replace(old, new)
    _write(p, s)
    print("  ✓", rel_path)

def xml_esc(t):
    t = t.replace('&', '&amp;').replace('<', '&lt;').replace('>', '&gt;')
    return t

def existing_keys(path):
    s = _read(path)
    return set(re.findall(r'<string name="([^"]+)"', s))

def add_res(entries):
    """entries: [(key, en, ko, zh)] — 先全量校验，再追加到三份资源末尾"""
    ks = {lang: existing_keys(p) for lang, p in RES.items()}
    dup = [e[0] for e in entries if e[0] in ks['en']]
    assert not dup, "键已存在: %s" % dup
    for key, en, ko, zh in entries:
        assert key.isidentifier(), "非法键名: " + key
        assert en.strip() and ko.strip() and zh.strip(), "空翻译: " + key
        specs = {tuple(re.findall(r'%\d+\$[a-z]', v)) for v in (en, ko, zh)}
        assert len(specs) == 1, "占位符不一致 %s: en=%s ko=%s zh=%s" % (key, en, ko, zh)
    lines = {lang: '' for lang in RES}
    for key, en, ko, zh in entries:
        for lang, val in (('en', en), ('ko', ko), ('zh', zh)):
            lines[lang] += '    <string name="%s">%s</string>\n' % (key, xml_esc(val))
    for lang, p in RES.items():
        s = _read(p)
        assert s.rstrip().endswith('</resources>'), p
        idx = s.rindex('</resources>')
        _write(p, s[:idx] + lines[lang] + s[idx:])
    print("  ✓ 资源 +%d 键 ×3" % len(entries))
