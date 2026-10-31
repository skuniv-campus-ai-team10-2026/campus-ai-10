import {beforeEach,describe,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {MemoryRouter,Route,Routes} from 'react-router-dom';
import {api,post,patch} from './api';
import {clearPendingPhotos} from './attachments';
import {AnalyzePage,HomePage} from './student';
import {ReportDetailPage} from './reports';
import {SessionContext} from './context';
import type {Draft,FollowUpState,Report} from './types';
vi.mock('./api',async(importOriginal)=>({...await importOriginal<typeof import('./api')>(),api:vi.fn(),post:vi.fn(),patch:vi.fn()}));
const demoReport:Report={id:10,originalText:'북악관 502호에서 와이파이가 끊겨요',category:'IT',subcategory:'WIFI',symptom:'와이파이 연결 장애',locationText:'북악관 502호',building:'북악관',floor:'5',status:'IN_PROGRESS',routeStatus:'MATCHED',assignedDepartment:{id:1,name:'정보전산원',verified:true},createdAt:'2026-10-10T10:00:00+09:00',updatedAt:'2026-10-10T10:00:00+09:00'};
const baseDraft:Draft={originalText:'학교 인터넷이 안 돼요',preferredIntent:'REPORT',clientRequestId:'test-request',analysis:{intent:'REPORT',aiMode:'DEMO',report:{category:'IT',subcategory:'WIFI',locationText:null,symptom:'인터넷 장애',missingFields:['LOCATION'],followUpQuestion:'어느 건물에서 발생했나요?'},questionAnswer:null,routingPreview:null}};
function mountAnalysis(draft:Draft){sessionStorage.setItem('skope-draft',JSON.stringify(draft));return render(<MemoryRouter initialEntries={['/report/analyze']}><Routes><Route path="/report/analyze" element={<AnalyzePage/>}/><Route path="/reports/success" element={<div>접수 성공 화면</div>}/></Routes></MemoryRouter>);}
describe('student and admin workflows',()=>{
 beforeEach(()=>{cleanup();sessionStorage.clear();vi.clearAllMocks();vi.mocked(api).mockResolvedValue({items:[{id:1,category:'IT',subcategory:'WIFI',workName:'무선 인터넷',departmentName:'정보전산원',verified:true}]});});
 it.each(['reanalyze','manual','remove'] as const)('preserves photo selection through %s and reload',async(action)=>{
  clearPendingPhotos();URL.createObjectURL=vi.fn(()=>'blob:preview');URL.revokeObjectURL=vi.fn();
  const user=userEvent.setup();
  const initial=action==='manual'?{...baseDraft,analysis:{intent:'QUESTION' as const,aiMode:'DEMO' as const,report:null,questionAnswer:null,routingPreview:null}}:baseDraft;
  mountAnalysis({...initial,hadPhotos:false});
  await user.upload(screen.getByLabelText('첨부 사진 선택'),new File(['photo'],'test.png',{type:'image/png'}));
  if(action==='remove')await user.click(screen.getByRole('button',{name:'test.png 삭제'}));
  if(action==='manual')await user.click(screen.getByRole('button',{name:'분석 없이 직접 신고 내용 입력'}));
  else{
   vi.mocked(post).mockResolvedValue({...baseDraft.analysis,report:{...baseDraft.analysis!.report,followUpQuestion:null}});
   await user.type(screen.getByLabelText('추가 질문 답변'),'북악관 502호');await user.click(screen.getByRole('button',{name:'재분석'}));
   await waitFor(()=>expect(screen.queryByLabelText('추가 질문 답변')).not.toBeInTheDocument());
  }
  const saved=JSON.parse(sessionStorage.getItem('skope-draft')!) as Draft;expect(saved.hadPhotos).toBe(action!=='remove');
  if(action==='manual')expect(saved.manual).toBe(true);
  cleanup();clearPendingPhotos();mountAnalysis(saved);
  expect(screen.queryByText(/새로고침으로 이전에 선택한 사진이 사라졌습니다/)!==null).toBe(action!=='remove');
 });
 it('renders an unsupported question without creating a report',async()=>{
  mountAnalysis({...baseDraft,analysis:{intent:'QUESTION',aiMode:'DEMO',report:null,routingPreview:null,questionAnswer:{text:'확인된 안내 근거가 없어 답변할 수 없습니다.',verified:false}}});
  expect(await screen.findByText('확인된 안내 근거가 없어 답변할 수 없습니다.')).toBeInTheDocument();
  expect(screen.getByText('현재 공식 출처 미검증')).toBeInTheDocument();
  expect(screen.getByText('질문만으로는 신고가 접수되지 않았습니다.')).toBeInTheDocument();
  expect(post).not.toHaveBeenCalled();
 });
 it('submits follow-up with schema-safe previousReport while retaining the confirmed date',async()=>{
  const user=userEvent.setup();mountAnalysis(baseDraft);
  vi.mocked(post).mockResolvedValue({...baseDraft.analysis,report:{...baseDraft.analysis!.report,locationText:'북악관 502호',building:'북악관',floor:'5',missingFields:[],followUpQuestion:null}});
  await user.type(screen.getByLabelText('확인된 발생일 (선택)'),'2026-10-09');
  await user.type(screen.getByLabelText('추가 질문 답변'),'북악관 502호');await user.click(screen.getByRole('button',{name:'재분석'}));
  await waitFor(()=>expect(post).toHaveBeenCalledOnce());
  const request=vi.mocked(post).mock.calls[0][1] as {followUp:{field:string;answer:string;previousReport:Record<string,unknown>}};
  expect(request.followUp).toMatchObject({field:'LOCATION',answer:'북악관 502호'});expect(request.followUp.previousReport).not.toHaveProperty('occurredOn');
  await waitFor(()=>expect(screen.getByLabelText('확인된 발생일 (선택)')).toHaveValue('2026-10-09'));
  expect(JSON.parse(sessionStorage.getItem('skope-draft')!).analysis.report.occurredOn).toBe('2026-10-09');
  cleanup();
  mountAnalysis(JSON.parse(sessionStorage.getItem('skope-draft')!) as Draft);
  expect(screen.getByLabelText('확인된 발생일 (선택)')).toHaveValue('2026-10-09');
 });
 it('continues follow-ups with server state and the current manually edited report, then stops when complete',async()=>{
  const user=userEvent.setup();
  const initial:FollowUpState={turns:0,noProgress:0,askedFields:[],status:'CONTINUE',nextField:'LOCATION'};
  const continued:FollowUpState={turns:1,noProgress:0,askedFields:['LOCATION'],status:'CONTINUE',nextField:'SYMPTOM'};
  mountAnalysis({...baseDraft,analysis:{...baseDraft.analysis!,followUpState:initial}});
  vi.mocked(post).mockResolvedValueOnce({...baseDraft.analysis,followUpState:continued,report:{...baseDraft.analysis!.report,locationText:'북악관 502호',building:'북악관',floor:'5',missingFields:['SYMPTOM'],followUpQuestion:'어떤 문제가 발생했나요?'}}).mockResolvedValueOnce({...baseDraft.analysis,followUpState:{turns:2,noProgress:0,askedFields:['LOCATION','SYMPTOM'],status:'COMPLETE',nextField:null},report:{...baseDraft.analysis!.report,locationText:'북악관 502호',building:'북악관',floor:'5',symptom:'수업 중 10분마다 연결이 끊김',occurredAtText:'어제부터',missingFields:[],followUpQuestion:null}});
  await user.type(screen.getByLabelText('추가 질문 답변'),'북악관 502호에서 어제부터 그랬어요');
  await user.click(screen.getByRole('button',{name:'재분석'}));
  expect(await screen.findByText('어떤 문제가 발생했나요?')).toBeInTheDocument();
  await user.type(screen.getByLabelText('발생 시점 (설명)'),'어제부터');
  await user.type(screen.getByLabelText('추가 질문 답변'),'수업 중 10분마다 연결이 끊겨요');
  await user.click(screen.getByRole('button',{name:'재분석'}));
  await waitFor(()=>expect(screen.queryByLabelText('추가 질문 답변')).not.toBeInTheDocument());
  expect(vi.mocked(post).mock.calls[0][1]).toMatchObject({followUp:{field:'LOCATION',state:initial}});
  expect(vi.mocked(post).mock.calls[1][1]).toMatchObject({followUp:{field:'SYMPTOM',state:continued,previousReport:{locationText:'북악관 502호',occurredAtText:'어제부터'}}});
  expect(screen.getByLabelText('문제 상황')).toHaveValue('수업 중 10분마다 연결이 끊김');
  expect(screen.getByRole('button',{name:'확인하고 신고 접수'})).toBeEnabled();
 });
 it.each(['LIMIT','NO_PROGRESS','MANUAL_REVIEW'] as const)('keeps manual completion and report submission available after %s',async(status)=>{
  const user=userEvent.setup();
  mountAnalysis({...baseDraft,analysis:{...baseDraft.analysis!,followUpState:{turns:3,noProgress:2,askedFields:['LOCATION'],status,nextField:null},report:{...baseDraft.analysis!.report!,followUpQuestion:null}}});
  expect(screen.queryByLabelText('추가 질문 답변')).not.toBeInTheDocument();
  expect(screen.getByText('추가 질문을 마쳤습니다. 아래 내용을 확인·수정한 뒤 접수할 수 있습니다. 자동 배정 여부는 담당 부서 안내를 확인해 주세요.')).toBeInTheDocument();
  vi.mocked(post).mockResolvedValue({id:13,status:'RECEIVED',routeStatus:'NEEDS_REVIEW',assignedDepartment:null});
  await screen.findByText('무선 인터넷');
  await user.type(screen.getByLabelText('발생 장소'),'북악관 502호');
  await user.click(screen.getByRole('button',{name:'확인하고 신고 접수'}));
  expect(await screen.findByText('접수 성공 화면')).toBeInTheDocument();
  const [path,payload]=vi.mocked(post).mock.calls[0];
  expect(path).toBe('/reports');
  expect(payload).toMatchObject({locationText:'북악관 502호',symptom:'인터넷 장애',clientRequestId:'test-request'});
  expect(payload).not.toHaveProperty('followUpState');
 });
 it.each(['LOCATION','CATEGORY'] as const)('clears only manually resolved %s review before a symptom follow-up without resetting limits',async(group)=>{
  const user=userEvent.setup();
  const state:FollowUpState={turns:2,noProgress:1,askedFields:['LOCATION','SYMPTOM'],reviewFields:[group,'OCCURRED_AT'],status:'CONTINUE',nextField:'SYMPTOM'};
  mountAnalysis({...baseDraft,analysis:{...baseDraft.analysis!,followUpState:state,report:{...baseDraft.analysis!.report!,followUpQuestion:'어떤 현상이 발생했나요?'}}});
  const corrected=group==='LOCATION'?{locationText:'북악관 502호',building:'북악관',floor:'5'}:{category:'OTHER',subcategory:'UNKNOWN'};
  vi.mocked(post).mockResolvedValue({...baseDraft.analysis,followUpState:{...state,turns:3,reviewFields:['OCCURRED_AT'],status:'MANUAL_REVIEW',nextField:null},report:{...baseDraft.analysis!.report,...corrected,symptom:'수업 중 10분마다 연결이 끊김',followUpQuestion:null}});
  if(group==='LOCATION'){
   fireEvent.change(screen.getByLabelText('발생 장소'),{target:{value:'북악관 502호'}});
   fireEvent.change(screen.getByLabelText('건물'),{target:{value:'북악관'}});
   fireEvent.change(screen.getByLabelText('층'),{target:{value:'5'}});
  }else await user.selectOptions(screen.getByLabelText('문제 유형'),'OTHER/UNKNOWN');
  await user.type(screen.getByLabelText('추가 질문 답변'),'수업 중 10분마다 연결이 끊겨요');
  await user.click(screen.getByRole('button',{name:'재분석'}));
  await waitFor(()=>expect(screen.queryByLabelText('추가 질문 답변')).not.toBeInTheDocument());
  expect(vi.mocked(post).mock.calls[0][1]).toMatchObject({followUp:{field:'SYMPTOM',previousReport:corrected,state:{turns:2,noProgress:1,askedFields:state.askedFields,reviewFields:['OCCURRED_AT']}}});
  expect(screen.getByRole('button',{name:'확인하고 신고 접수'})).toBeEnabled();
 });
 it.each([
  ['명진관 1층 전등 하나가 안 켜집니다. 불꽃이나 타는 냄새는 전혀 없습니다.',false],
  ['불꽃이나 연기, 타는 냄새는 전혀 없습니다',false],
  ['불꽃, 연기, 타는 냄새가 납니다',true],
  ['불꽃이 보여요, 연기와 타는 냄새는 없습니다',true],
  ['연기가 나고, 불꽃은 없습니다',true],
  ['불꽃이나 연기, 타는 냄새가 없는지는 모르겠어요',true],
  ['불꽃, 그리고 연기는 없습니다',false],
  ['연기 수업 문의',false],
  ['연기학과 강의실 문의',false],
  ['가스 냄새가 나지 않아요',false],
  ['연기가 나지 않습니다',false],
  ['콘센트에서 불꽃이 튀어요',true],
  ['연기 발생',true],
  ['불꽃은 없지만 타는 냄새가 납니다',true],
  ['타는 냄새는 없고 콘센트에서 불꽃이 나요',true],
  ['불꽃이 없다는 건 아닙니다',true],
  ['불꽃이 없었던 것은 아닙니다',true],
  ['가스 냄새가 없는 것 같아요',true],
  ['불꽃은 없어요\n타는 냄새가 나요',true],
 ])('shows a conservative pre-analysis warning for "%s": %s',(text,expected)=>{
  render(<SessionContext.Provider value={{user:{id:1,role:'STUDENT',displayName:'학생'},meta:{aiMode:'LIVE',embeddingMode:'LIVE',databaseMode:'MYSQL'},logout:async()=>{}}}><MemoryRouter><HomePage/></MemoryRouter></SessionContext.Provider>);
  fireEvent.change(screen.getByLabelText('캠퍼스 문제 또는 질문'),{target:{value:text}});
  expect(!!screen.queryByText('위험이 느껴진다면 안전을 먼저 확보해 주세요.')).toBe(expected);
  expect(post).not.toHaveBeenCalled();
 });
 it.each([false,true])('preserves the server emergency judgment when local cues are negated: %s',(emergencyHint)=>{
  mountAnalysis({...baseDraft,originalText:'불꽃이나 타는 냄새는 전혀 없습니다.',analysis:{...baseDraft.analysis!,report:{...baseDraft.analysis!.report!,symptom:'전등이 안 켜짐',emergencyHint}}});
  expect(!!screen.queryByText('위험이 느껴진다면 안전을 먼저 확보해 주세요.')).toBe(emergencyHint);
 });
 it.each(['신고 원문','문제 상황'])('warns for danger manually added to %s after a non-emergency analysis',(label)=>{
  mountAnalysis({...baseDraft,analysis:{...baseDraft.analysis!,report:{...baseDraft.analysis!.report!,emergencyHint:false}}});
  expect(screen.queryByText('위험이 느껴진다면 안전을 먼저 확보해 주세요.')).not.toBeInTheDocument();
  fireEvent.change(screen.getByLabelText(label),{target:{value:'콘센트에서 불꽃이 튀어요'}});
  expect(screen.getByText('위험이 느껴진다면 안전을 먼저 확보해 주세요.')).toBeInTheDocument();
 });
 it('keeps a local emergency warning available for manual reports',()=>{
  mountAnalysis({...baseDraft,originalText:'북악관 콘센트에서 불꽃이 튀어요',manual:true,analysis:undefined});
  expect(screen.getByText('위험이 느껴진다면 안전을 먼저 확보해 주세요.')).toBeInTheDocument();
 });
 it('keeps manual submission available without an AI response and does not assign a department client-side',async()=>{
  const user=userEvent.setup();mountAnalysis({...baseDraft,manual:true,analysis:undefined});
  vi.mocked(post).mockResolvedValue({id:11,status:'RECEIVED',routeStatus:'NEEDS_REVIEW',assignedDepartment:null});
  await screen.findByText('무선 인터넷');
  await user.click(screen.getByRole('button',{name:'확인하고 신고 접수'}));
  expect(await screen.findByText('접수 성공 화면')).toBeInTheDocument();
  const [path,payload]=vi.mocked(post).mock.calls[0];expect(path).toBe('/reports');expect(payload).toMatchObject({category:'OTHER',subcategory:'UNKNOWN',clientRequestId:'test-request',originalText:baseDraft.originalText});expect(payload).not.toHaveProperty('departmentId');expect(payload).not.toHaveProperty('studentId');
 });
 it('does not allow an empty resolution or whitespace-only resolution',async()=>{
  vi.mocked(api).mockImplementation(async(path)=>path.includes('/similar')?{items:[],reportId:10}:path==='/report-types'?{items:[]}:demoReport);
  render(<SessionContext.Provider value={{user:{id:3,role:'ADMIN',displayName:'관리자'},meta:{aiMode:'DEMO',embeddingMode:'RULE_ONLY',databaseMode:'H2_DEMO'},logout:async()=>{}}}><MemoryRouter initialEntries={['/admin/reports/10']}><Routes><Route path="/admin/reports/:id" element={<ReportDetailPage admin/>}/></Routes></MemoryRouter></SessionContext.Provider>);
  const complete=await screen.findByRole('button',{name:'결과 저장하고 처리 완료'});expect(complete).toBeDisabled();
  const user=userEvent.setup();await user.type(screen.getByLabelText('처리 결과 (필수)'),'   ');expect(complete).toBeDisabled();expect(patch).not.toHaveBeenCalled();
  await user.type(screen.getByLabelText('처리 결과 (필수)'),'점검 완료');expect(complete).toBeEnabled();
 });
 it('allows manual leak classification when no verified leak routing rule exists',async()=>{
  const user=userEvent.setup();mountAnalysis({...baseDraft,manual:true,analysis:undefined});
  vi.mocked(post).mockResolvedValue({id:12,status:'RECEIVED',routeStatus:'NEEDS_REVIEW',assignedDepartment:null});
  await screen.findByRole('option',{name:'누수 · 관리자 확인'});
  await user.selectOptions(screen.getByLabelText('문제 유형'),'FACILITY/LEAK');
  expect(screen.getByText('관리자가 확인할 예정이에요')).toBeInTheDocument();
  await user.click(screen.getByRole('button',{name:'확인하고 신고 접수'}));
  expect(await screen.findByText('접수 성공 화면')).toBeInTheDocument();
  expect(vi.mocked(post).mock.calls[0][1]).toMatchObject({category:'FACILITY',subcategory:'LEAK'});
  expect(vi.mocked(post).mock.calls[0][1]).not.toHaveProperty('departmentId');
 });
});
