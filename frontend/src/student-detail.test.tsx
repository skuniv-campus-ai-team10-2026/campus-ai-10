import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,render,screen,within} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {MemoryRouter,Route,Routes} from 'react-router-dom';
import {api} from './api';
import {ReportDetailPage} from './reports';
import {SessionContext} from './context';
import {clearPendingPhotos} from './attachments';
import type {Report} from './types';
vi.mock('./api',async original=>({...await original<typeof import('./api')>(),api:vi.fn()}));
const base:Report={id:91,originalText:'수강 신청 방법을 알려 주세요',requestKind:'QUESTION',category:'INTERNAL_CATEGORY',subcategory:'INTERNAL_TYPE',symptom:'기타 / 직접 확인',status:'RECEIVED',routeStatus:'MATCHED',assignedDepartment:null,createdAt:'2026-10-31T10:00:00+09:00',updatedAt:'2026-10-31T10:00:00+09:00',reviewNote:'관리자 전용 메모 / 마지막 추가 답변: 학생의 AI 답변',incidentId:987,events:[{eventType:'STATUS_CHANGED',oldValue:null,newValue:'INTERNAL_STATUS',note:'내부 이벤트 메모',actorName:'내부 담당자',changedAt:'2026-10-31'}]};
function mount(report=base,admin=false){
 vi.mocked(api).mockImplementation(async path=>path.endsWith('/attachments')||path.endsWith('/similar')||path==='/report-types'?{items:[]}:report);
 return render(<SessionContext.Provider value={{user:{id:1,role:admin?'ADMIN':'STUDENT',displayName:'테스트'},meta:null,logout:async()=>{}}}><MemoryRouter initialEntries={['/reports/91']}><Routes><Route path="/reports/:id" element={<ReportDetailPage admin={admin}/>}/></Routes></MemoryRouter></SessionContext.Provider>);
}
afterEach(()=>{cleanup();clearPendingPhotos();vi.clearAllMocks();});
it('orders student information, collapses original content and hides empty and internal fields',async()=>{
 mount();await screen.findByRole('heading',{name:'담당자 답변'});
 expect(screen.getAllByRole('heading').slice(0,4).map(h=>h.textContent)).toEqual(['문의 상세','접수 상태','담당자 답변','담당 부서']);
 expect(screen.getByText('배정 대기')).toBeVisible();
 for(const text of ['관리자 전용 메모','학생의 AI 답변','INTERNAL_CATEGORY','INTERNAL_TYPE','INTERNAL_STATUS','내부 이벤트 메모','기타 / 직접 확인','사건 #987','미확인','접수일은 발생일이 아닙니다'])expect(screen.queryByText(text,{exact:false})).not.toBeInTheDocument();
 for(const label of ['발생 장소','건물','층','발생 시점','발생일'])expect(screen.queryByText(label,{exact:true})).not.toBeInTheDocument();
 const original=screen.getByText(base.originalText);expect(original).not.toBeVisible();
 await userEvent.click(screen.getByText('내가 보낸 내용'));expect(original).toBeVisible();
 expect(await screen.findByRole('button',{name:/사진 추가하기/})).toBeVisible();
 expect(screen.getByText('SK-000091')).toBeVisible();
});
it.each(['RECEIVED','IN_PROGRESS','RESOLVED'] as const)('shows appropriate waiting text for %s without deriving an answer from reviewNote',async status=>{
 mount({...base,status});
 const section=(await screen.findByRole('heading',{name:'담당자 답변'})).closest('section')!;
 expect(section).toHaveTextContent(status==='RECEIVED'?'확인과 답변을 기다리고':status==='IN_PROGRESS'?'문의를 확인하고':'등록된 담당자 답변이 없습니다');
 expect(screen.queryByText(/학생의 AI 답변/)).not.toBeInTheDocument();
});
it.each(['QUESTION','REPORT'] as const)('uses resolutionText for %s and keeps submitted fields, photos and emergency guidance',async requestKind=>{
 mount({...base,requestKind,status:'RESOLVED',resolutionText:'담당자가 등록한 최종 답변',locationText:'북악관 3층',building:'북악관',floor:'3',occurredAtText:'어제 오후',occurredOn:'2026-10-30',emergencyHint:true,assignedDepartment:{id:4,name:'교무처'}});
 const answer=(await screen.findByRole('heading',{name:requestKind==='QUESTION'?'담당자 답변':'처리 결과'})).closest('section')!;
 expect(within(answer).getByText('담당자가 등록한 최종 답변')).toBeVisible();
 expect(screen.getByText('교무처')).toBeVisible();expect(screen.queryByText('배정 대기')).not.toBeInTheDocument();
 expect(screen.getByText('위험이 느껴진다면 안전을 먼저 확보해 주세요.')).toBeVisible();
 await userEvent.click(screen.getByText('내가 보낸 내용'));
 for(const value of ['북악관 3층','북악관','3층','어제 오후','2026-10-30'])expect(screen.getByText(value,{exact:true})).toBeVisible();
 await screen.findByText('등록된 사진이 없습니다.');expect(screen.queryByRole('button',{name:/사진 추가하기/})).not.toBeInTheDocument();
});
it('retains internal notes, classification, incident links and actions for administrators',async()=>{
 mount({...base,requestKind:'REPORT'},true);
 expect(await screen.findByText(/관리자 확인 사항: 관리자 전용 메모/)).toBeInTheDocument();
 expect(screen.getByText('INTERNAL_CATEGORY / INTERNAL_TYPE')).toBeInTheDocument();
 expect(screen.getByRole('link',{name:/사건 #987/})).toHaveAttribute('href','/admin/incidents/987');
 expect(screen.getByRole('button',{name:/처리 시작하기/})).toBeEnabled();
 expect(screen.getByText('내부 이벤트 메모')).toBeInTheDocument();
});
