"""Publish bounded build diagnostics for clients with contents but no Actions tools.
Runs only inside CI, uses its existing scoped token, never records environment.
Reports are [skip ci] commits, ignored by the ROM workflow.
"""
import base64,json,os,pathlib,subprocess,time
repo=os.environ['GITHUB_REPOSITORY'];run=os.environ['GITHUB_RUN_ID'];job=os.environ['REPORT_JOB']
def gh(args,data=None):
 p=subprocess.run(['gh','api',*args],input=None if data is None else json.dumps(data),capture_output=True,text=True)
 if p.returncode:raise RuntimeError(p.stderr[-500:])
 return json.loads(p.stdout) if p.stdout.strip() else {}
report={'sha':os.environ['GITHUB_SHA'],'run':run,'url':f'https://github.com/{repo}/actions/runs/{run}','job':job,'result':os.environ['REPORT_RESULT']}
try:
 jobs=gh([f'repos/{repo}/actions/runs/{run}/jobs?per_page=100'])['jobs']
 report['jobs']=[{'name':j['name'],'status':j['status'],'conclusion':j['conclusion'],'steps':[{'name':s['name'],'conclusion':s['conclusion']} for s in j.get('steps',[]) if s['conclusion']=='failure']} for j in jobs]
except Exception as e:report['inspection_error']=str(e)
if report['result']!='success':
 for name in ['companion-tests.log','music-deps.log','app-update.log']:
  p=pathlib.Path(os.environ['RUNNER_TEMP'])/name
  if p.exists():report.setdefault('logs',{})[name]=p.read_text(errors='replace')[-6500:]
path=f'.github/build-reports/music-3.5.1-{job}.json';route=f'repos/{repo}/contents/{path}'
content=base64.b64encode((json.dumps(report,indent=2)+'\n').encode()).decode()
for attempt in range(5):
 try:
  try:old=gh([route+'?ref=main']).get('sha')
  except Exception:old=None
  payload={'message':f'ci: {job} music build report [skip ci]','branch':'main','content':content}
  if old:payload['sha']=old
  gh(['--method','PUT',route,'--input','-'],payload);print('Published build report:',path);break
 except Exception:
  if attempt==4:raise
  time.sleep(2+attempt)
