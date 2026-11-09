"""Dump public bytecode signatures from this host run's exact published AARs."""
import os
from pathlib import Path
import subprocess
import time
import zipfile
from verification import ROOT, API_MODULES, artifact_path, current_run, file_record, publication, sha, write_json

def dump_api():
    run=current_run(); publication(require_run=True)
    output=ROOT/'api'; output.mkdir(exist_ok=True)
    javap=Path(os.environ['JAVA_HOME'])/'bin'/('javap.exe' if os.name=='nt' else 'javap')
    modules={}
    for module in API_MODULES:
        aar=artifact_path(module,'aar'); extracted=ROOT/'build/api'/module; extracted.mkdir(parents=True,exist_ok=True)
        with zipfile.ZipFile(aar) as archive: (extracted/'classes.jar').write_bytes(archive.read('classes.jar'))
        with zipfile.ZipFile(extracted/'classes.jar') as archive:
            classes=sorted(n[:-6].replace('/','.') for n in archive.namelist() if n.endswith('.class') and not n.rsplit('/',1)[-1].startswith('R$') and n.rsplit('/',1)[-1]!='R.class')
        if not classes: raise ValueError(f'{module}: no bytecode classes to inspect')
        result=subprocess.run([str(javap),'-public','-classpath',str(extracted/'classes.jar'),*classes],capture_output=True,text=True,check=True)
        if not result.stdout.strip(): raise ValueError('javap returned empty API dump')
        # javap output is decoded with universal newlines, so write an explicit CRLF ending to keep
        # the checked-in API baseline byte-identical on Windows, macOS and Linux (no whole-file churn).
        # Written as bytes: Path.write_text(newline=...) only exists on Python 3.10+, and the system
        # python3 on macOS is 3.9, where it raised TypeError and failed the whole host gate.
        target=output/f'{module}.txt'; target.write_bytes(result.stdout.replace('\n','\r\n').encode('utf-8'))
        modules[module]=dict(aar_sha256=sha(aar),class_count=len(classes),dump=file_record(target))
        print(f'{module}: {len(classes)} bytecode classes inspected')
    current_run()
    write_json(ROOT/'build/verification/api.json',dict(run,started_unix=time.time(),modules=modules))

if __name__=='__main__': dump_api()
