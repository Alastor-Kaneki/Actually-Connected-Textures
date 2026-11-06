"""Generate Python outputs for mandatory Kotlin pixel/JSON parity checks in CI."""
import json
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from test_fusion import FIX, ROOT, SUPPORTED, ctm, base_pack, outputs

out=ROOT/'build/fusion-golden';out.mkdir(parents=True,exist_ok=True)
for case in SUPPORTED:
    for direct in (False,True):
        name=case+('-direct' if direct else '')
        result=ctm.convert(FIX/case,out/(name+'.mcpack'),'Fixture','fixture',direct)
        (out/(name+'.json')).write_text(json.dumps(result,indent=2))
print('Generated 12 Python reference packs')
