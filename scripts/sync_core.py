#!/usr/bin/env python3
"""Vendor an exact CarrierSIM release; keep transport adaptations in separate modules."""
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys

root=Path(__file__).resolve().parents[1]
repo=Path(sys.argv[1] if len(sys.argv)>1 else '/home/vlw/carrierSIM')
ref=sys.argv[2] if len(sys.argv)>2 else 'v6'
if not re.fullmatch(r'v\d+(?:-beta\d+)?',ref):raise SystemExit('Expected a release tag')
commit=subprocess.check_output(['git','-C',str(repo),'rev-parse',ref+'^{commit}'],text=True).strip()
files={}
for name in ('carrier.py','airtraffic_native.py','airtraffic_apple.py','device_models.py','carriersim_version.py','assets.zip','bundle.yaml','LICENSE','LICENSE-AirLift.txt','LICENSE-AirCard.txt'):
    data=subprocess.check_output(['git','-C',str(repo),'show',commit+':'+name])
    target=root/('app/src/main/python' if name.endswith('.py') else 'app/src/main/assets/carriersim')/name
    target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(data)
    files[name]=hashlib.sha256(data).hexdigest()
(root/'core-provenance.json').write_text(json.dumps({'repository':'https://github.com/ios-bundles/CarrierSIM','tag':ref,'commit':commit,'sha256':files},indent=2)+'\n')
