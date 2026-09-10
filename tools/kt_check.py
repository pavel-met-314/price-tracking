'''Проверка Kotlin-файлов без компилятора: баланс скобок, пар комментариев и целость регулярных шаблонов.

Зачем: сборку здесь не собрать (нет Android SDK), а «unclosed comment» и незакрытая тройная
кавычка ломают компиляцию на минуту-две в CI и стоят прогона. Отдельно проверяется правило
проекта: любой шаблон с кириллицей обязан нести inline `(?iu)` — `RegexOption.UNICODE_CASE` в
Kotlin отсутствует, и без флага внутри шаблона `java.util.regex` не складывает русский регистр.

Запуск: python3 tools/kt_check.py ['app/src/**/*.kt']
'''
import glob
import re
import sys

CYRILLIC = re.compile(r'[а-яёА-ЯЁ]')


def strip(src):
    """Оставляет только код: строки и комментарии вырезаны, поэтому счётчик скобок честный."""
    out = []
    i = 0
    n = len(src)
    while i < n:
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            if j < 0:
                i = n
            else:
                # Series of quotes: Kotlin относит к содержимому все, кроме последних трёх
                # (""""x"""" -> "x"), поэтому пропускаем всю серию целиком.
                k = j + 3
                while k < n and src[k] == '"':
                    k += 1
                i = k
            continue
        c = src[i]
        if c == '"':
            i += 1
            while i < n and src[i] != '"':
                i += 2 if src[i] == '\\' else 1
            i += 1
            continue
        if c == "'":
            i += 1
            while i < n and src[i] != "'":
                i += 2 if src[i] == '\\' else 1
            i += 1
            continue
        if src.startswith('//', i):
            j = src.find('\n', i)
            i = n if j < 0 else j
            continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2)
            i = n if j < 0 else j + 2
            continue
        out.append(c)
        i += 1
    return ''.join(out)


def without_strings(src):
    """Тот же обход, что и strip(), но комментарии сохраняются: по ним видно пары /* и */."""
    out = []
    i = 0
    n = len(src)
    while i < n:
        if src.startswith('"""', i):
            j = src.find('"""', i + 3)
            if j < 0:
                i = n
            else:
                # Series of quotes: Kotlin относит к содержимому все, кроме последних трёх
                # (""""x"""" -> "x"), поэтому пропускаем всю серию целиком.
                k = j + 3
                while k < n and src[k] == '"':
                    k += 1
                i = k
            continue
        c = src[i]
        if c == '"':
            i += 1
            while i < n and src[i] != '"':
                i += 2 if src[i] == '\\' else 1
            i += 1
            continue
        if c == "'":
            i += 1
            while i < n and src[i] != "'":
                i += 2 if src[i] == '\\' else 1
            i += 1
            continue
        out.append(c)
        i += 1
    return ''.join(out)


COPY_DUP = re.compile(r"\.copy\(([^()]*(?:\([^()]*\)[^()]*)*)\)", re.S)
NAMED_ARG = re.compile(r"^\s*(\w+) =", re.M)


def check(path):
    src = open(path, encoding='utf-8').read()
    problems = []
    if src.count('"""') % 2 != 0:
        problems.append('лишняя/незакрытая тройная кавычка')

    nosk = without_strings(src)
    opens, closes = nosk.count('/*'), nosk.count('*/')
    if opens != closes:
        problems.append('комментарии: /*=%d */=%d' % (opens, closes))

    code = strip(src)
    for opener, closer in (('{', '}'), ('(', ')'), ('[', ']')):
        a, b = code.count(opener), code.count(closer)
        if a != b:
            problems.append('%s=%d %s=%d' % (opener, a, closer, b))

    # Один именованный аргумент, переданный дважды в .copy(...) — Kotlin считает это ошибкой,
    # а глазами такой дубль в длинном списке полей не виден.
    for match in COPY_DUP.finditer(src):
        names = NAMED_ARG.findall(match.group(1))
        for name in sorted({n for n in names if names.count(n) > 1}):
            line = src[:match.start()].count('\n') + 1
            problems.append('строка %d: .copy(... %s =) повторяется' % (line, name))

    # Регулярки с кириллицей: ищем строки с IGNORE_CASE и с русскими буквами в шаблоне.
    for match in re.finditer(r'Regex\((.{0,400}?)\)\n?', src, re.S):
        body = match.group(1)
        if 'IGNORE_CASE' in body and CYRILLIC.search(body) and '(?iu' not in body and '(?iU' not in body:
            line = src[:match.start()].count('\n') + 1
            problems.append('строка %d: regex с кириллицей без (?iu)' % line)
    return problems


def main():
    patterns = sys.argv[1:] or ['app/src/**/*.kt']
    files = sorted({f for pattern in patterns for f in glob.glob(pattern, recursive=True)})
    bad = 0
    for path in files:
        problems = check(path)
        if problems:
            bad += 1
            print('PROBLEM', path + ':', '; '.join(problems))
    print('checked %d files, %d with issues' % (len(files), bad))
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main())
