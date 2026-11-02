#!/usr/bin/env python3
"""Copy the newest CHANGELOG.adoc section into the <change-notes> of plugin.xml.

The newest section is the first "== <version>" or "== <version> — <title>" heading. Each "Term::" description
list item becomes "<li><b>Term.</b> body</li>". Inline `code`, _italic_ and *bold*
become <code>, <i> and <b>.

If <change-notes> already has an <h3> for that version, the script replaces it;
otherwise it adds the version above the previous one.

Usage, from anywhere: python3 tools/changelog-to-plugin-xml.py
"""
import html
import re
import textwrap
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CHANGELOG = ROOT / 'CHANGELOG.adoc'
PLUGIN_XML = ROOT / 'src/main/resources/META-INF/plugin.xml'


def inline(text):
    codes = []

    def keep(m):
        codes.append('<code>' + html.escape(m.group(1), quote=False) + '</code>')
        return '\0%d\0' % (len(codes) - 1)

    text = re.sub(r'`([^`]+)`', keep, text)
    text = html.escape(text, quote=False)
    text = re.sub(r'(?<!\w)_([^_]+?)_(?!\w)', r'<i>\1</i>', text)
    text = re.sub(r'(?<!\w)\*([^*]+?)\*(?!\w)', r'<b>\1</b>', text)
    return re.sub(r'\0(\d+)\0', lambda m: codes[int(m.group(1))], text)


def notes_block(adoc):
    heading = re.search(r'^== (\d\S*)(?:\s+[—-]\s*(.*?))?\s*$', adoc, re.M)
    if not heading:
        raise SystemExit('No "== <version>" section in ' + str(CHANGELOG))
    section = adoc[heading.end():]
    following = re.search(r'^== ', section, re.M)
    if following:
        section = section[:following.start()]

    version, title = heading.group(1), heading.group(2)
    h3 = version + (' — ' + inline(title) if title else '')
    out = ['        <h3>%s</h3>' % h3, '        <ul>']
    for m in re.finditer(r'^(.+?)::\n((?:.+\n?)+)', section, re.M):
        body = ' '.join(m.group(2).split())
        item = '<li><b>%s.</b> %s</li>' % (inline(m.group(1)), inline(body))
        lines = textwrap.wrap(item, 84, break_on_hyphens=False, break_long_words=False)
        out.append('            ' + lines[0])
        out += ['                ' + line for line in lines[1:]]
    out.append('        </ul>')
    return version, '\n'.join(out) + '\n'


def main():
    version, block = notes_block(CHANGELOG.read_text(encoding='utf-8'))
    xml = PLUGIN_XML.read_text(encoding='utf-8')
    opening = '<change-notes><![CDATA[\n'
    start = xml.index(opening) + len(opening)
    existing = re.compile(r'^        <h3>%s[ <]' % re.escape(version), re.M).search(xml, start)
    if existing:
        start = existing.start()
        end = xml.index('        <h3>', start + 1)
    else:
        end = start
    PLUGIN_XML.write_text(xml[:start] + block + xml[end:], encoding='utf-8')
    print('Wrote change notes for %s into %s' % (version, PLUGIN_XML.relative_to(ROOT)))


if __name__ == '__main__':
    main()
