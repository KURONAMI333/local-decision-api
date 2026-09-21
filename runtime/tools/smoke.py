import subprocess,struct,json,time,os,sys,tempfile,shutil
from pathlib import Path
jar=Path(sys.argv[1]).resolve()
java=os.environ.get('JAVA_HOME', '') + '/bin/java' if os.environ.get('JAVA_HOME') else 'java'
with tempfile.TemporaryDirectory(prefix='local-inference-clean-') as home:
 clean_jar=Path(home)/'runtime.jar';shutil.copyfile(jar,clean_jar)
 env={'PATH':os.environ.get('PATH',''), 'HOME':home,'DJL_OFFLINE':'true'}
 started=time.monotonic()
 p=subprocess.Popen(([ '/usr/bin/sandbox-exec','-p','(version 1)(allow default)(deny network*)'] if sys.platform=='darwin' else []) + (['/usr/bin/time','-l'] if sys.platform=='darwin' else []) + [java,'-Duser.home='+home,'-jar',str(clean_jar),'--worker'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env,cwd=home)
 def read():
  header=p.stdout.read(4)
  if len(header)!=4: raise RuntimeError(p.stderr.read().decode())
  n=struct.unpack('>i',header)[0];assert 0<n<=1048576
  return json.loads(p.stdout.read(n))
 def call(value):
  data=json.dumps(value).encode();p.stdin.write(struct.pack('>i',len(data))+data);p.stdin.flush();return read()
 assert read()=={'ready':True}
 print('ready_seconds',time.monotonic()-started,flush=True)
 fixtures=json.loads(Path(__file__).with_name('diagnostic-cases.json').read_text())
 correct=0
 for case in fixtures['cases']:
  start=time.monotonic();r=call({key:case[key] for key in ('context','question','choices')})
  expected_selection=case['selected'] if case['selected']<len(case['choices']) else None
  assert r['selected']==expected_selection,(case['name'],case['reverse'],r)
  assert len(r['logits'])==len(case['logits'])
  assert max(abs(a-b) for a,b in zip(r['logits'],case['logits']))<1e-4,(case['name'],r)
  correct+=r['selected']==case['expected']
  print('case',case['name'],'reverse',case['reverse'],'ms',(time.monotonic()-start)*1000,r,flush=True)
 print('diagnostic_correct',correct,'of',len(fixtures['cases']),'all_reference_selections_match',flush=True)
 for context in ['bad <<LABEL>> input','word '*600]:
  r=call({'context':context,'question':'What?', 'choices':['One','Two']}); assert 'error' in r,r;print('rejected',r,flush=True)
 p.stdin.close();assert p.wait(timeout=15)==0;print('eof_shutdown_ok',flush=True)
 print(p.stderr.read().decode(),file=sys.stderr)
