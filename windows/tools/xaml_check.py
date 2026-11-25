#!/usr/bin/env python3
"""Static checks for the WPF UI that can be run on any OS (the app can't be run on a Mac).

A missing StaticResource key is a runtime XamlParseException the moment a window opens, and the compiler does not
catch it. This script finds, for every .xaml and its code-behind:
  - {StaticResource X} / {DynamicResource X} whose key is not defined in Theme.xaml, App.xaml or the same file
  - FindResource("X") / Res("X") / St("X") string literals with no such key
  - Click="Handler" (and other event attributes) with no method of that name in the code-behind
Exit code 0 = clean.
"""
import re, sys, pathlib

root = pathlib.Path(__file__).resolve().parent.parent / "AirBridge"
keys_re = re.compile(r'x:Key="([^"]+)"')
theme = set(keys_re.findall((root / "Theme.xaml").read_text(encoding="utf-8")))
app = root / "App.xaml"
if app.exists():
    theme |= set(keys_re.findall(app.read_text(encoding="utf-8")))

problems = 0
for xaml in sorted(root.rglob("*.xaml")):
    if "/obj/" in str(xaml) or "/bin/" in str(xaml) or xaml.name in ("Theme.xaml",):
        continue
    text = xaml.read_text(encoding="utf-8")
    cs = xaml.with_name(xaml.name + ".cs")
    code = cs.read_text(encoding="utf-8") if cs.exists() else ""
    local = set(keys_re.findall(text))
    known = theme | local
    for m in re.finditer(r'\{(?:StaticResource|DynamicResource)\s+([^\s}]+)\}', text):
        if m.group(1) not in known:
            print(f"{xaml.relative_to(root)}: missing resource {m.group(1)}"); problems += 1
    for m in re.finditer(r'\b(?:FindResource|Res|St|TryFindResource)\("([^"]+)"\)', code):
        if m.group(1) not in known:
            print(f"{cs.relative_to(root)}: missing resource {m.group(1)}"); problems += 1
    for m in re.finditer(r'\b(Click|Drop|DragOver|MouseLeftButtonDown|MouseLeftButtonUp|MouseDown|MouseUp|TextChanged|KeyDown|SelectionChanged|Checked|Unchecked|Loaded|Closed|Closing|Deactivated|Activated|MouseEnter|MouseLeave|PreviewMouseDown)="([A-Za-z_][A-Za-z0-9_]*)"', text):
        if not re.search(r'\b' + re.escape(m.group(2)) + r'\s*\(', code):
            print(f"{xaml.relative_to(root)}: handler {m.group(2)} ({m.group(1)}) not found in code-behind"); problems += 1
# code-behind of controls built without XAML resources: the keys they look up must exist in the theme
for cs in sorted(root.rglob("*.cs")):
    if "/obj/" in str(cs) or "/bin/" in str(cs) or cs.with_suffix("").with_suffix(".xaml").exists() and cs.name.endswith(".xaml.cs"):
        continue
    code = cs.read_text(encoding="utf-8")
    for m in re.finditer(r'\b(?:FindResource|Res|St|TryFindResource)\("(AB\.[^"]+)"\)', code):
        if m.group(1) not in theme:
            print(f"{cs.relative_to(root)}: missing theme resource {m.group(1)}"); problems += 1
print("xaml_check: clean" if problems == 0 else f"xaml_check: {problems} problem(s)")
sys.exit(1 if problems else 0)
