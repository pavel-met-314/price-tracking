'''Проверка Kotlin-файлов без компилятора: скобки, пары комментариев и `(?iu)` в регулярках.

Зачем: сборку здесь не собрать (нет Android SDK), а «unclosed comment» или лишняя тройная кавычка
стоят прогона CI и минуты-двух на цикл «push — падение». Отдельно проверяется правило проекта:
любой шаблон с кириллицей обязан нести inline `(?iu)` — `RegexOption.UNICODE_CASE` в Kotlin нет, и
без флага внутри шаблона `java.util.regex` не складывает русский регистр (из-за этого поиск когда-то
и потерял цены «22 235 РУБ.»).

Запуск: python3 tools/kt_check.py ['app/src/**/*.kt']

Токенайзер общий для обеих проверок и потому порядок один: тройные кавычки, строка, символ, а
комментарии разбираются отдельно. Ошибка первой версии была ровно в обратном: апостроф в русском
тексте комментария («url'ы») открывал фиксированный char-literal и съедал ближайший `*/`.
'''
import glob
import re
import sys

CYRILLIC = re.compile(r'[а-яёА-ЯЁ]')
COPY_DUP = re.compile(r"\.copy\(([^()]*(?:\([^()]*\)[^()]*)*)\)", re.S)
NAMED_ARG = re.compile(r"^\s*(\w+) =", re.M)


def _quote_run_end(src, start):
    """Конец сырого литерала: серия кавычек длины >=3 закрывает его, всё до неё — содержимое."""
    i = start
    n = len(src)
    while i < n:
        if src[i] == '"':
            j = i
            while j < n and src[j] == '"':
                j += 1
            if j - i >= 3:
                return j
            i = j
            continue
        i += 1
    return n


def _simple_literal_end(src, start, quote):
    i = start
    n = len(src)
    while i < n:
        if src[i] == '\\':
            i += 2
            continue
        if src[i] == quote:
            return i + 1
        i += 1
    return n


def scan(src):
    """Возвращает (код, комментарии): строки вырезаны, комментарии собраны отдельно."""
    code = []
    comments = []
    i = 0
    n = len(src)
    while i < n:
        if src.startswith('"""', i):
            i = _quote_run_end(src, i + 3)
            continue
        if src.startswith('//', i):
            j = src.find('\n', i)
            j = n if j < 0 else j
            comments.append(src[i:j])
            i = j
            continue
        if src.startswith('/*', i):
            j = src.find('*/', i + 2)
            if j < 0:
                comments.append(src[i:])
                return ''.join(code), ''.join(comments)
            comments.append(src[i:j + 2])
            i = j + 2
            continue
        c = src[i]
        if c == '"':
            i = _simple_literal_end(src, i + 1, '"')
            continue
        if c == "'":
            i = _simple_literal_end(src, i + 1, "'")
            continue
        code.append(c)
        i += 1
    return ''.join(code), ''.join(comments)


def find_regex_issues(src):
    """Regex с кириллицей без inline `(?iu)` — то, из-за чего парсер молча глохнет."""
    problems = []
    for match in re.finditer(r'Regex\((.{0,400}?)\)\n?', src, re.S):
        body = match.group(1)
        if 'IGNORE_CASE' in body and CYRILLIC.search(body) and '(?iu' not in body and '(?iU' not in body:
            line = src[:match.start()].count('\n') + 1
            problems.append('строка %d: regex с кириллицей без (?iu)' % line)
    return problems


def check(path):
    src = open(path, encoding='utf-8').read()
    problems = []

    if src.count('"""') % 2 != 0:
        problems.append('лишняя/незакрытая тройная кавычка')

    code, comments = scan(src)

    opens, closes = comments.count('/*'), comments.count('*/')
    if opens != closes:
        problems.append('комментарии: /*=%d */=%d' % (opens, closes))

    for opener, closer in (('{', '}'), ('(', ')'), ('[', ']')):
        a, b = code.count(opener), code.count(closer)
        if a != b:
            problems.append('%s=%d %s=%d' % (opener, a, closer, b))

    for match in COPY_DUP.finditer(src):
        names = NAMED_ARG.findall(match.group(1))
        for name in sorted({n for n in names if names.count(n) > 1}):
            line = src[:match.start()].count('\n') + 1
            problems.append('строка %d: .copy(... %s =) повторяется' % (line, name))

    problems.extend(find_regex_issues(src))
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
