"""Explicit integration exercise. Creates labelled local records and retains audit history."""
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime
from ops.local_api import LocalApi
from ops.local_config import ConfigError


def verify(root,config):
    clerk=LocalApi(root,config).login('test','TEST_PASSWORD')
    reviewer=LocalApi(root,config).login('test1','TEST1_PASSWORD')
    admin=LocalApi(root,config).login('admin','ADMIN_PASSWORD')
    prefix='[验证]'+datetime.now().strftime('%m%d-%H%M%S')
    checks=[]; created=[]
    def ok(label,condition):
        if not condition: raise ConfigError('验证未通过：'+label)
        checks.append(label); print('通过：'+label)
    def detail(pid,actor=clerk): return actor.request('/project/projects/'+str(pid))['data']
    def change(pid,action,data=None,actor=clerk):
        return actor.request('/project/projects/'+str(pid)+'/'+action,'POST',data or {})
    def denied(actor,path,method='POST',body=None,label='禁止越权'):
        r=actor.request(path,method,body,check=False)
        # An unrelated server exception must never count as a successful authorization test.
        intended=('项目审批请通过项目业务接口办理','仅备案草稿','当前阶段不能提交',
                  '无权查看该项目','只能办理本人项目')
        ok(label,r.get('code')==403 or (r.get('code')!=200 and any(s in r.get('msg','') for s in intended)))
    def create(name,route='DIRECT',actor=clerk):
        p=actor.request('/project/projects','POST',{'name':prefix+name,'customerName':'本地验证客户','location':'演示地址','acquisitionMode':route,'estimatedAmount':100000})['data']
        created.append(str(p['id'])); return p['id']
    def application(pid):
        return next(a for a in detail(pid)['applications'] if a['status']=='SUBMITTED')
    def decide(pid,decision='APPROVE'):
        a=application(pid)
        reviewer.request('/project/approvals/'+str(a['id'])+'/decision','POST',{'decision':decision,'comment':'资料确认通过' if decision=='APPROVE' else '请补充投标内容后重新提交'})
        return a
    def register(pid):
        change(pid,'registration'); decide(pid); ok('备案审批后可以继续办理 '+str(pid),detail(pid)['stage']=='REGISTERED')
    def bid(pid):
        change(pid,'bid',{'bidName':'演示工程投标','bidType':'公开招标','bidDeadline':'2026-11-10'}); decide(pid)
    init={'managerName':'演示项目负责人','projectAmount':98000,'plannedStart':'2026-11-15','plannedEnd':'2027-03-01'}
    for actor,name in [(clerk,'业务员'),(reviewer,'审批负责人')]:
        denied(actor,'/system/user/list','GET',label=name+'不能进入人员管理')
    denied(clerk,'/project/approvals','GET',label='业务员不能查看审批负责人收件箱')
    d=create('直接委托'); change(d,'registration'); app=application(d)
    denied(clerk,'/project/approvals/'+str(app['id'])+'/decision',body={'decision':'APPROVE'},label='申请人不能自行审批')
    if app.get('taskId'):
        denied(clerk,'/workflow/task/completeTask',body={'taskId':app['taskId'],'variables':{'ignore':True}},label='通用流程入口不能绕过项目审批授权')
    denied(clerk,'/project/projects/'+str(d),'PUT',{'name':'在途修改','customerName':'客户','location':'地址','acquisitionMode':'DIRECT'},'审批中不能修改申请依据')
    decide(d); change(d,'follow-ups',{'message':'客户确认采用直接委托承接。'})
    change(d,'initiation',init); decide(d); ok('直接委托完成立项审批',detail(d)['stage']=='ESTABLISHED')
    denied(clerk,'/project/projects/'+str(d)+'/initiation',body=init,label='已立项不能重复立项')
    t=create('投标中标','TENDER'); register(t)
    change(t,'bid',{'bidName':'第一次投标申请','bidType':'公开招标','bidDeadline':'2026-11-10'}); returned=decide(t,'RETURN')
    current=detail(t); ok('退回后恢复可修改阶段且保留旧申请',current['stage']=='REGISTERED' and any(a['id']==returned['id'] and a['status']=='RETURNED' for a in current['applications']))
    bid(t); ok('重新提交通过后进入投标准备',detail(t)['stage']=='BID_PREPARATION')
    change(t,'tasks',{'title':'准备投标文件','kind':'BID_DOCUMENT','assigneeName':'演示业务员','dueDate':'2026-11-09','note':'准备事项记录'})
    task=detail(t)['tasks'][0]; clerk.request('/project/projects/'+str(t)+'/tasks/'+str(task['id']),'PUT',{'status':'DONE','note':'文件已整理'})
    change(t,'opening',{'bidResult':'WON','openingDate':'2026-11-10','bidAmount':98000,'bidRemark':'本地中标验证'})
    change(t,'initiation',init); decide(t); ok('投标、中标、立项完整完成',detail(t)['stage']=='ESTABLISHED')
    lost=create('未中标','TENDER'); register(lost); bid(lost)
    change(lost,'opening',{'bidResult':'LOST','openingDate':'2026-11-10','bidRemark':'未中标验证'})
    denied(clerk,'/project/projects/'+str(lost)+'/initiation',body=init,label='未中标项目禁止立项')
    terminated=create('终止备案'); change(terminated,'terminate',{'reason':'已确认无合作可能'})
    denied(clerk,'/project/projects/'+str(terminated)+'/registration',body={},label='终止后禁止继续提交')
    private=create('其他人的记录',actor=admin)
    denied(clerk,'/project/projects/'+str(private),'GET',label='业务员不能通过记录ID读取他人项目')
    denied(clerk,'/project/projects/'+str(private),'PUT',{'name':'越权修改','customerName':'客户','location':'地址','acquisitionMode':'DIRECT'},'业务员不能修改他人项目')
    parallel=create('重复提交')
    def submit(_): return clerk.request('/project/projects/'+str(parallel)+'/registration','POST',{},check=False)
    with ThreadPoolExecutor(max_workers=2) as pool: results=list(pool.map(submit,range(2)))
    ok('并发重复提交仅产生一条申请',sum(r.get('code')==200 for r in results)==1
       and any('当前阶段不能提交' in r.get('msg','') for r in results)
       and len(detail(parallel)['applications'])==1)
    decide(parallel,'RETURN')
    print('项目验证完成：%s项；保留样本%s条，名称前缀%s。' % (len(checks),len(created),prefix))
    return {'checks':checks,'projectIds':created,'prefix':prefix}


def main():
    from dev import ROOT, operation_lock
    from ops.local_config import prepare
    from ops.runtime import check_compose, docker_ready

    with operation_lock():
        config = prepare(ROOT)
        check_compose(ROOT, config)
        docker_ready()
        verify(ROOT, config)


if __name__ == "__main__":
    import subprocess
    try:
        main()
    except (ConfigError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from None
