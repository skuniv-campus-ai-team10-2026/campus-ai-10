import {afterEach,beforeEach,describe,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,within} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {MemoryRouter,Route,Routes} from 'react-router-dom';
import {api,patch} from './api';
import {SessionContext} from './context';
import {IncidentPage} from './repeated';
import {ReportDetailPage,ReportsPage} from './reports';
import type {Incident,Report,Rule,User} from './types';

vi.mock('./api',async(importOriginal)=>({...await importOriginal<typeof import('./api')>(),api:vi.fn(),patch:vi.fn(),post:vi.fn()}));
const meta={aiMode:'DEMO',embeddingMode:'RULE_ONLY',databaseMode:'H2_DEMO'};
const admin:User={id:1,role:'ADMIN',displayName:'시연 관리자'};
const rule:Rule={id:1,category:'IT',subcategory:'WIFI',workName:'유·무선 인터넷 문의',departmentName:'정보전산원',assignmentBasis:'VERIFIED',verified:true};
const base:Report={id:10,originalText:'북악관 313호 와이파이가 끊겨요',category:'IT',subcategory:'WIFI',symptom:'와이파이 끊김',locationText:'북악관 313호',building:'북악관',floor:'3',requestKind:'REPORT',status:'RECEIVED',routeStatus:'MATCHED',assignedDepartment:{id:1,name:'정보전산원',assignmentBasis:'VERIFIED',verified:true},createdAt:'2026-10-25T15:59:00+09:00',updatedAt:'2026-10-25T15:59:00+09:00',events:[{eventType:'CREATED',oldValue:null,newValue:'RECEIVED',note:'시연용 자체 접수',changedAt:'2026-10-25T15:59:00+09:00',actorName:'시연 학생 1'}]};
const question:Report={...base,id:11,originalText:'와이파이 문제 어디로 문의하면 되나요?',symptom:null,locationText:null,building:null,floor:null,requestKind:'QUESTION'};
function mockReport(value:Report){
 vi.mocked(api).mockImplementation(async path=>path==='/report-types'?{items:[rule]}:path.endsWith('/similar')?{items:[],reportId:value.id,aiMode:'RULE_ONLY'}:value);
}
function mountDetail(id:number){
 return render(<SessionContext.Provider value={{user:admin,meta,logout:async()=>{}}}><MemoryRouter initialEntries={[`/admin/reports/${id}`]}><Routes><Route path="/admin/reports/:id" element={<ReportDetailPage admin/>}/></Routes></MemoryRouter></SessionContext.Provider>);
}
beforeEach(()=>{vi.clearAllMocks();sessionStorage.clear();});
afterEach(cleanup);

describe('admin question report wording',()=>{
 it('hides empty location and time fields and uses inquiry wording for a received question',async()=>{
  mockReport(question);mountDetail(11);
  expect(await screen.findByText('문의가 접수되었습니다. 담당자의 확인을 기다리고 있습니다.')).toBeInTheDocument();
  expect(screen.getByRole('heading',{name:'답변'})).toBeInTheDocument();
  expect(screen.getByText('문의 접수')).toBeInTheDocument();
  expect(screen.getByText('분류')).toBeInTheDocument();
  for(const label of ['발생 장소','건물 / 층','발생 시점 설명','확인된 발생일'])expect(screen.queryByText(label)).not.toBeInTheDocument();
 });

 it('labels the answer form and completion notice for a question',async()=>{
  mockReport({...question,status:'IN_PROGRESS'});mountDetail(11);
  expect(await screen.findByText('담당자가 확인하고 있습니다. 답변이 등록되면 여기에 표시됩니다.')).toBeInTheDocument();
  expect(screen.getByLabelText('답변 (필수)')).toBeInTheDocument();
  expect(screen.getByRole('button',{name:'답변 저장하고 처리 완료'})).toBeDisabled();
  cleanup();
  mockReport({...question,status:'RESOLVED',resolutionText:'정보전산원으로 문의해 주세요.'});mountDetail(11);
  expect(await screen.findByText('답변이 완료된 문의입니다.')).toBeInTheDocument();
 });

 it('keeps report wording and unknown fields for a regular report',async()=>{
  mockReport({...base,locationText:null,building:null,floor:null});mountDetail(10);
  expect(await screen.findByText('신고가 접수되었습니다. 담당자의 확인을 기다리고 있습니다.')).toBeInTheDocument();
  expect(screen.getByRole('heading',{name:'처리 결과'})).toBeInTheDocument();
  expect(screen.getByText('신고 접수')).toBeInTheDocument();
  expect(screen.getByText('발생 장소')).toBeInTheDocument();
  expect(screen.getByText('확인된 발생일')).toBeInTheDocument();
 });

 it('shows Korean labels for incident and similarity events in the admin timeline',async()=>{
  mockReport({...base,events:[...base.events!,{eventType:'INCIDENT_LINK',oldValue:null,newValue:'1',note:'관리자 확인으로 사건 연결',changedAt:'2026-10-25T16:01:00+09:00',actorName:'시연 관리자'},{eventType:'SIMILARITY_REVIEW',oldValue:'CANDIDATE',newValue:'SAME_INCIDENT',note:'유사 후보 #1 / 상대 신고 #1',changedAt:'2026-10-25T16:01:00+09:00',actorName:'시연 관리자'}]});
  mountDetail(10);
  expect(await screen.findByText('사건 연결',{selector:'.timeline-item strong'})).toBeInTheDocument();
  expect(screen.getByText('유사 후보 검토',{selector:'.timeline-item strong'})).toBeInTheDocument();
  expect(screen.queryByText('INCIDENT_LINK')).not.toBeInTheDocument();
  expect(screen.queryByText('SIMILARITY_REVIEW')).not.toBeInTheDocument();
 });
});

describe('admin report list status filter',()=>{
 it('filters by processing status from the status select',async()=>{
  vi.mocked(api).mockResolvedValue({items:[],totalElements:0,page:0,size:12});
  render(<SessionContext.Provider value={{user:admin,meta,logout:async()=>{}}}><MemoryRouter><ReportsPage admin/></MemoryRouter></SessionContext.Provider>);
  fireEvent.change(await screen.findByLabelText('처리 상태'),{target:{value:'IN_PROGRESS'}});
  expect(api).toHaveBeenLastCalledWith('/admin/reports?page=0&size=12&status=IN_PROGRESS');
 });
});

describe('incident recurrence review',()=>{
 const note='과거 사건 #1. 같은 유형·장소의 과거 종료 사건과 비교한 관계이며 최종 판단은 관리자 검토 결과를 따릅니다. 미래 고장 예측이 아닙니다. 발생일 미확인: 접수 시각만으로 비교했으며 실제 재발 시점은 불확실합니다.';
 const incident:Incident={id:2,category:'IT',subcategory:'WIFI',building:'북악관',floor:'3',status:'OPEN',previousIncidentId:1,recurrenceDecision:'CONFIRMED',createdAt:'2026-10-25T16:15:00+09:00',closedAt:null,reportIds:[3],reports:[{...base,id:3,incidentId:2}],events:[{eventType:'RECURRENCE_REVIEW',oldValue:'CANDIDATE',newValue:'CONFIRMED',note,changedAt:'2026-10-25T16:16:00+09:00',actorName:'시연 관리자'}]};
 function mountIncident(value:Incident){
  vi.mocked(api).mockResolvedValue(value);
  return render(<SessionContext.Provider value={{user:admin,meta,logout:async()=>{}}}><MemoryRouter initialEntries={['/admin/incidents/2']}><Routes><Route path="/admin/incidents/:id" element={<IncidentPage/>}/></Routes></MemoryRouter></SessionContext.Provider>);
 }

 it('collapses the recurrence note behind a link to the compared incident',async()=>{
  mountIncident(incident);
  const link=await screen.findByRole('link',{name:/과거 사건 #1과 비교/});
  expect(link).toHaveAttribute('href','/admin/incidents/1');
  const details=screen.getByText('자세히').closest('details')!;
  expect(details).not.toHaveAttribute('open');
  expect(within(details).getByText(/같은 유형·장소의 과거 종료 사건과 비교한 관계/)).toBeInTheDocument();
 });

 it('does not allow saving the same recurrence decision twice for the same incident',async()=>{
  mountIncident(incident);
  const confirm=await screen.findByRole('button',{name:'재발 확인'});
  expect(confirm).toBeDisabled();
  expect(screen.getByRole('button',{name:'재발 후보 반려'})).toBeEnabled();
  fireEvent.change(screen.getByLabelText('이전 종료 사건 번호'),{target:{value:'5'}});
  expect(confirm).toBeEnabled();
 });

 it('disables only the reject button after a rejection is saved',async()=>{
  mountIncident({...incident,recurrenceDecision:'REJECTED'});
  expect(await screen.findByRole('button',{name:'재발 후보 반려'})).toBeDisabled();
  const confirm=screen.getByRole('button',{name:'재발 확인'});
  expect(confirm).toBeEnabled();
  vi.mocked(patch).mockResolvedValue({});
  await userEvent.setup().click(confirm);
  expect(patch).toHaveBeenCalledExactlyOnceWith('/admin/incidents/2/recurrence',{previousIncidentId:1,decision:'CONFIRMED'});
 });
});
