import subprocess,struct,json,time,os,sys,tempfile
from pathlib import Path
jar=Path(sys.argv[1]).resolve()
java=os.environ.get('JAVA_HOME', '') + '/bin/java' if os.environ.get('JAVA_HOME') else 'java'
with tempfile.TemporaryDirectory(prefix='local-inference-clean-') as home:
 env={'PATH':os.environ.get('PATH',''), 'HOME':home,'DJL_OFFLINE':'true'}
 started=time.monotonic()
 p=subprocess.Popen(([ '/usr/bin/sandbox-exec','-p','(version 1)(allow default)(deny network*)'] if sys.platform=='darwin' else []) + [java,'-Duser.home='+home,'-jar',str(jar),'--worker'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.PIPE,env=env,cwd=home)
 def read():
  header=p.stdout.read(4)
  if len(header)!=4: raise RuntimeError(p.stderr.read().decode())
  n=struct.unpack('>i',header)[0];assert 0<n<=1048576
  return json.loads(p.stdout.read(n))
 def call(value):
  data=json.dumps(value).encode();p.stdin.write(struct.pack('>i',len(data))+data);p.stdin.flush();return read()
 assert read()=={'ready':True}
 print('ready_seconds',time.monotonic()-started,flush=True)
 cases=[{'raw_logits':[6.3687362671,-4.0468711853,-2.4287686348,0.5433447361]}, {'raw_logits':[-3.3791496754,6.1880517006,-4.9604926109,-0.4779653847]}]
 states=['I lost my wallet and need to block my debit card.','I cannot remember my login password. Please reset it.']
 for i,state in enumerate(states):
  start=time.monotonic();r=call({'context':state,'question':'What does this person need?','choices':['Reporting a lost bank card','Resetting a password','Requesting a loan']})
  assert r['selected']==i,r
  assert max(abs(a-b) for a,b in zip(r['logits'],cases[i]['raw_logits']))<1e-5,r
  print('case',i,'ms',(time.monotonic()-start)*1000,r,flush=True)
 for context in ['bad <<LABEL>> input','word '*600]:
  r=call({'context':context,'question':'What?', 'choices':['One','Two']}); assert 'error' in r,r;print('rejected',r,flush=True)
 p.stdin.close();assert p.wait(timeout=15)==0;print('eof_shutdown_ok',flush=True)
 print(p.stderr.read().decode(),file=sys.stderr)
