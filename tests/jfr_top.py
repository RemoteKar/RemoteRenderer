# -*- coding: utf-8 -*-
"""Where the render thread spends its time in a JFR recording: inclusive and self sample shares per method.

  python tests/jfr_top.py <file.jfr> [thread name] [top N]
"""
import collections, json, subprocess, sys
from pathlib import Path

JFR = Path(__file__).resolve().parents[2] / 'Java/Java_25/bin/jfr.exe'


def main():
    f = sys.argv[1]
    thread = sys.argv[2] if len(sys.argv) > 2 else 'Render thread'
    top = int(sys.argv[3]) if len(sys.argv) > 3 else 45
    out = subprocess.run([str(JFR), 'print', '--json', '--stack-depth', '96', '--events', 'jdk.ExecutionSample', f],
                         capture_output=True, text=True, encoding='utf-8', errors='replace').stdout
    events = json.loads(out)['recording']['events']
    incl, self_ = collections.Counter(), collections.Counter()
    n = 0
    for e in events:
        v = e['values']
        if (v.get('sampledThread') or {}).get('javaName') != thread:
            continue
        frames = [fr['method']['type']['name'].split('.')[-1] + '.' + fr['method']['name'] for fr in (v.get('stackTrace') or {}).get('frames', [])]
        if not frames:
            continue
        n += 1
        self_[frames[0]] += 1
        for m in set(frames):
            incl[m] += 1
    print('samples on %s: %d' % (thread, n))
    print('--- inclusive')
    for m, c in incl.most_common(top):
        print('%5.1f%%  %s' % (100.0 * c / n, m))
    print('--- self')
    for m, c in self_.most_common(25):
        print('%5.1f%%  %s' % (100.0 * c / n, m))


if __name__ == '__main__':
    main()
