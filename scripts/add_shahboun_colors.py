from pathlib import Path
p = Path('/tmp/ShahbounMultiEngine/app/src/main/res/values/shahboun_modern_colors.xml')
p.write_text('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="on_background">#171717</color>
    <color name="on_surface_variant">#6B7280</color>
</resources>
''', encoding='utf-8')
