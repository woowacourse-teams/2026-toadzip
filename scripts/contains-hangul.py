import sys


text = sys.stdin.read()
has_hangul = any("\uac00" <= character <= "\ud7a3" for character in text)
raise SystemExit(0 if has_hangul else 1)
