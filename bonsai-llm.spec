# A single offline EXE with native runtimes, local UI, and the smallest new model.
import json
from pathlib import Path
ROOT = Path(SPECPATH)
catalog = json.loads((ROOT / 'config/catalog.json').read_text())
a = Analysis(
    [str(ROOT / 'app.py')], pathex=[str(ROOT)], binaries=[],
    datas=[(str(ROOT / 'static'), 'static'), (str(ROOT / 'config'), 'config'),
           (str(ROOT / 'bin'), 'bin'),
           (str(ROOT / 'models' / catalog['models']['2B']['model']['file']), 'bundled')],
    hiddenimports=['webview.platforms.edgechromium', 'clr_loader', 'pythonnet'],
    excludes=['tkinter','matplotlib','numpy','scipy','pandas','pytest'],
    hookspath=[], hooksconfig={}, runtime_hooks=[], noarchive=False, optimize=1)
pyz = PYZ(a.pure)
exe = EXE(pyz, a.scripts, a.binaries, a.datas, [], name='Bonsai-2-Windows',
          debug=False, bootloader_ignore_signals=False, strip=False, upx=False,
          console=False, disable_windowed_traceback=False, icon=str(ROOT / 'bonsai.ico'))
