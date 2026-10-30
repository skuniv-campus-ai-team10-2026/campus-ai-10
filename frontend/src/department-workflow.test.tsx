import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {MemoryRouter,Route,Routes} from 'react-router-dom';
import App from './App';
import {api,ApiError,patch,post} from './api';
import {SessionContext} from './context';
import {ReportDetailPage,ReportsPage} from './reports';
import {AnalyzePage,SuccessPage} from './student';
import type {Report,Role,Rule,User} from './types';

vi.mock('./api',async(importOriginal)=>({...await importOriginal<typeof import('./api')>(),api:vi.fn(),patch:vi.fn(),post:vi.fn()}));
const meta={aiMode:'LIVE',embeddingMode:'LIVE',databaseMode:'MYSQL'};
const staff:User={id:4,role:'DEPT_ADMIN',displayName:'시설 시연 담당자',departmentId:3,departmentName:'총무처 건설관리과'};
const rule:Rule={id:3,category:'FACILITY',subcategory:'LEAK',workName:'누수 시연 배정',departmentName:'총무처 건설관리과',assignmentBasis:'DEMO',verified:false};
const report:Report={id:10,originalText:'북악관 5층 복도 천장에서 물이 새요',category:'FACILITY',subcategory:'LEAK',symptom:'천장 누수',locationText:'북악관 5층',building:'북악관',floor:'5',requestKind:'REPORT',status:'RECEIVED',routeStatus:'MATCHED',assignedDepartment:{id:3,name:rule.departmentName,assignmentBasis:'DEMO',verified:false},createdAt:'2026-10-17T10:00:00+09:00',updatedAt:'2026-10-17T10:00:00+09:00'};
function mockReport(value:Report=report){
 vi.mocked(api).mockImplementation(async path=>path==='/report-types'?{items:[rule]}:path.endsWith('/similar')?{items:[],reportId:value.id,aiMode:'LIVE'}:value);
}
function mountDetail(role:Role='DEPT_ADMIN'){
 return render(<SessionContext.Provider value={{user:{...staff,role},meta,logout:async()=>{}}}><MemoryRouter initialEntries={['/admin/reports/10']}><Routes><Route path="/admin/reports/:id" element={<ReportDetailPage admin/>}/></Routes></MemoryRouter></SessionContext.Provider>);
}
beforeEach(()=>{vi.clearAllMocks();sessionStorage.clear();mockReport();});
afterEach(cleanup);

describe('department assignment and staff controls',()=>{
 it('routes a newly authenticated department staff member to their department workspace',async()=>{
  vi.mocked(api).mockImplementation(async path=>{if(path==='/auth/me')throw new ApiError(401,'UNAUTHENTICATED','로그인 필요');return path==='/meta'?meta:{items:[],totalElements:0,page:0,size:12};});
  vi.mocked(post).mockResolvedValue(staff);
  render(<MemoryRouter><App/></MemoryRouter>);
  const user=userEvent.setup();await user.type(await screen.findByLabelText('아이디'),'dept3');await user.type(screen.getByLabelText('비밀번호'),'synthetic-password');
  await user.click(screen.getByRole('button',{name:'로그인'}));
  expect(await screen.findByRole('heading',{name:'총무처 건설관리과 문의·신고'})).toBeInTheDocument();
  expect(screen.getByText('부서 담당자')).toBeInTheDocument();
  expect(screen.getByRole('link',{name:'신고 관리'})).toHaveAttribute('href','/admin/reports');
  expect(screen.queryByLabelText('이관 요청만 보기')).not.toBeInTheDocument();
  expect(api).toHaveBeenCalledWith('/admin/reports?page=0&size=12');
 });

 it('allows department staff to request transfer with a reason but never shows direct rerouting',async()=>{
  mountDetail();
  const transfer=await screen.findByRole('button',{name:'총괄 관리자에게 이관 요청'});
  expect(screen.queryByLabelText('담당 부서 재배정')).not.toBeInTheDocument();
  expect(transfer).toBeDisabled();
  fireEvent.change(screen.getByLabelText('이관 요청 사유 (필수)'),{target:{value:'   '}});expect(transfer).toBeDisabled();
  fireEvent.change(screen.getByLabelText('이관 요청 사유 (필수)'),{target:{value:'  배관이 아닌 실험 장비 문제로 보입니다.  '}});
  vi.mocked(patch).mockResolvedValue({...report,transferRequested:true});
  mockReport({...report,transferRequested:true,transferReason:'배관이 아닌 실험 장비 문제로 보입니다.'});
  await userEvent.setup().click(transfer);
  expect(patch).toHaveBeenCalledExactlyOnceWith('/admin/reports/10/transfer-request',{reason:'배관이 아닌 실험 장비 문제로 보입니다.'});
  expect(await screen.findByText('총괄 관리자에게 이관 검토를 요청했습니다.')).toBeInTheDocument();
  expect(screen.queryByRole('button',{name:'총괄 관리자에게 이관 요청'})).not.toBeInTheDocument();
  expect(screen.getByRole('button',{name:'처리 시작하기'})).toBeEnabled();
 });

 it('shows a failed transfer without reporting it as pending',async()=>{
  vi.mocked(patch).mockRejectedValue(new ApiError(403,'FORBIDDEN','현재 담당 부서의 신고가 아닙니다.'));
  mountDetail();await screen.findByLabelText('이관 요청 사유 (필수)');
  fireEvent.change(screen.getByLabelText('이관 요청 사유 (필수)'),{target:{value:'담당 재확인 요청'}});
  await userEvent.setup().click(screen.getByRole('button',{name:'총괄 관리자에게 이관 요청'}));
  expect(await screen.findByRole('alert')).toHaveTextContent('현재 담당 부서의 신고가 아닙니다.');
  expect(screen.queryByText('총괄 관리자에게 이관 검토를 요청했습니다.')).not.toBeInTheDocument();
 });

 it('lets the supervisor see the transfer reason and reroute using a clearly labeled demo rule',async()=>{
  mockReport({...report,transferRequested:true,transferReason:'담당 업무 재확인 필요'});mountDetail('ADMIN');
  const select=await screen.findByLabelText('담당 부서 재배정');
  expect(screen.getByText('요청 사유: 담당 업무 재확인 필요')).toBeInTheDocument();
  expect(screen.queryByLabelText('이관 요청 사유 (필수)')).not.toBeInTheDocument();
  expect(await screen.findByRole('option',{name:'누수 시연 배정 · 총무처 건설관리과 (시연용 가정 · 실제 담당 업무 미확인)'})).toBeInTheDocument();
  const user=userEvent.setup();await user.selectOptions(select,'3');
  await user.type(screen.getByLabelText('재배정·이관 검토 사유'),'시설 담당자가 확인하도록 배정합니다.');
  await user.click(screen.getByRole('button',{name:'선택한 규칙으로 재배정'}));
  expect(patch).toHaveBeenCalledWith('/admin/reports/10/routing',{routingRuleId:3,reason:'시설 담당자가 확인하도록 배정합니다.'});
 });

 it('requests the supervisor pending-transfer filter from the server',async()=>{
  vi.mocked(api).mockResolvedValue({items:[],totalElements:0,page:0,size:12});
  render(<SessionContext.Provider value={{user:{...staff,role:'ADMIN'},meta,logout:async()=>{}}}><MemoryRouter><ReportsPage admin/></MemoryRouter></SessionContext.Provider>);
  await screen.findByText('아직 신고가 없습니다');
  await userEvent.setup().click(screen.getByLabelText('이관 요청만 보기'));
  await waitFor(()=>expect(api).toHaveBeenLastCalledWith('/admin/reports?page=0&size=12&transferRequested=true'));
 });

 it('never presents demo routing as official even if conflicting source metadata is returned',async()=>{
  mockReport({...report,assignedDepartment:{...report.assignedDepartment!,verified:true,sourceUrl:'https://example.org/unverified',contactPhone:'02-000-0000'}});mountDetail();
  expect(await screen.findByText('시연용 배정 · 실제 담당 업무 미검증')).toBeInTheDocument();
  expect(screen.queryByText('검증 규칙 배정')).not.toBeInTheDocument();
  expect(screen.queryByText('공식 출처 확인 자료')).not.toBeInTheDocument();
  expect(screen.queryByText('02-000-0000')).not.toBeInTheDocument();
  expect(screen.queryByRole('link',{name:/출처 보기/})).not.toBeInTheDocument();
  expect(screen.getByText('LIVE · 실제 AI')).toBeInTheDocument();
 });

 it('lets the supervisor find unassigned reports without showing this control to department staff',async()=>{
  vi.mocked(api).mockResolvedValue({items:[],totalElements:0,page:0,size:12});
  render(<SessionContext.Provider value={{user:{...staff,role:'ADMIN'},meta,logout:async()=>{}}}><MemoryRouter><ReportsPage admin/></MemoryRouter></SessionContext.Provider>);
  await screen.findByText('아직 신고가 없습니다');
  await userEvent.setup().selectOptions(screen.getByLabelText('배정 상태'),'NEEDS_REVIEW');
  await waitFor(()=>expect(api).toHaveBeenLastCalledWith('/admin/reports?page=0&size=12&routeStatus=NEEDS_REVIEW'));
  cleanup();
  render(<SessionContext.Provider value={{user:staff,meta,logout:async()=>{}}}><MemoryRouter><ReportsPage admin/></MemoryRouter></SessionContext.Provider>);
  expect(screen.queryByLabelText('배정 상태')).not.toBeInTheDocument();
 });

 it('retains verified routing evidence for verified assignments',async()=>{
  mockReport({...report,assignedDepartment:{id:1,name:'정보전산원',assignmentBasis:'VERIFIED',verified:true,sourceUrl:'https://example.org/official',contactPhone:'02-111-1111'}});mountDetail();
  expect(await screen.findByText('공식 출처 확인 자료')).toBeInTheDocument();
  expect(screen.getByText('담당 부서 배정')).toBeInTheDocument();
  expect(screen.getByRole('link',{name:/출처 보기/})).toHaveAttribute('href','https://example.org/official');
  expect(screen.getByText('02-111-1111')).toBeInTheDocument();
 });

 it('labels the student manual preview and receipt as assumed demo assignments',async()=>{
  sessionStorage.setItem('skope-draft',JSON.stringify({originalText:report.originalText,preferredIntent:'REPORT',clientRequestId:'demo-assignment-test',manual:true,analysis:{intent:'REPORT',aiMode:'LIVE',report,questionAnswer:null,routingPreview:null}}));
  render(<MemoryRouter><AnalyzePage/></MemoryRouter>);
  expect(await screen.findByText('시연용 배정 · 실제 담당 업무 미검증')).toBeInTheDocument();
  expect(screen.getByRole('heading',{name:'총무처 건설관리과'})).toBeInTheDocument();
  expect(screen.getByRole('option',{name:'누수 · 담당 업무 미검증'})).toBeInTheDocument();
  cleanup();
  render(<MemoryRouter initialEntries={[{pathname:'/reports/success',state:{report}}]}><SuccessPage/></MemoryRouter>);
  expect(screen.getByText('시연용 배정 · 실제 담당 업무 미검증')).toBeInTheDocument();
 });
});
