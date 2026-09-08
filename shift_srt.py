import re, os, sys

def add_ms(ts, ms):
    h, m, s, ms2 = re.match(r'(\d+):(\d+):(\d+),(\d+)', ts).groups()
    total = int(h)*3600000 + int(m)*60000 + int(s)*1000 + int(ms2) + ms
    total = max(total, 0)
    h2 = total // 3600000; total %= 3600000
    m2 = total // 60000; total %= 60000
    s2 = total // 1000; ms2 = total % 1000
    return f"{h2:02d}:{m2:02d}:{s2:02d},{ms2:03d}"

subs_dir = sys.argv[1]
for fn in sorted(os.listdir(subs_dir)):
    if not fn.endswith('.srt'):
        continue
    path = os.path.join(subs_dir, fn)
    with open(path, 'r', encoding='utf-8') as f:
        text = f.read()
    def shift(m):
        start, end = m.group(1), m.group(2)
        return f"{start} --> {add_ms(end, 250)}"
    text = re.sub(r'(\d{2}:\d{2}:\d{2},\d{3}) --> (\d{2}:\d{2}:\d{2},\d{3})', shift, text)
    with open(path, 'w', encoding='utf-8') as f:
        f.write(text)
    print(fn)
