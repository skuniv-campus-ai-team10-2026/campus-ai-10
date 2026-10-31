import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,render,screen} from '@testing-library/react';
import {MemoryRouter,Route,Routes} from 'react-router-dom';
import {api,ApiError} from './api';
import {ErrorBox} from './ui';
import {ReportDetailPage} from './reports';
import {AnalyzePage} from './student';
vi.mock('./api',async original=>({...await original<typeof import('./api')>(),api:vi.fn()}));
afterEach(()=>{cleanup();vi.clearAllMocks();sessionStorage.clear();});

it('shows a neutral detail title and excludes internal classification and event notes',async()=>{
 vi.mocked(api).mockImplementation(async path=>path.endsWith('/attachments')?{items:[]}:{id:10,category:'INTERNAL_CATEGORY',subcategory:'NEW_TYPE',originalText:'확인 요청 내용',symptom:'시설 확인',status:'RECEIVED',routeStatus:'NEEDS_REVIEW',assignedDepartment:null,createdAt:'2026-10-30',updatedAt:'2026-10-30',events:[{eventType:'SIMILARITY_REVIEW',note:'internal review note',changedAt:'2026-10-30',actorName:'관리자'},{eventType:'STATUS_CHANGED',newValue:'IN_PROGRESS',note:'담당자가 확인 중입니다.',changedAt:'2026-10-30',actorName:'관리자'}]});
 render(<MemoryRouter initialEntries={['/reports/10']}><Routes><Route path="/reports/:id" element={<ReportDetailPage/>}/></Routes></MemoryRouter>);
 expect(await screen.findByRole('heading',{name:'불편 신고 상세'})).toBeInTheDocument();
 expect(screen.queryByText(/INTERNAL_CATEGORY|NEW_TYPE|SIMILARITY_REVIEW|internal review note/)).not.toBeInTheDocument();
 expect(screen.queryByText('담당자가 확인 중입니다.')).not.toBeInTheDocument();
 expect(screen.getByRole('link',{name:'접수내역으로'})).toHaveAttribute('href','/reports');
});

it('uses the receipt-list label when loading a report fails',async()=>{
 vi.mocked(api).mockRejectedValue(new ApiError(404,'NOT_FOUND','접수 건을 찾지 못했어요.'));
 render(<MemoryRouter initialEntries={['/reports/10']}><Routes><Route path="/reports/:id" element={<ReportDetailPage/>}/></Routes></MemoryRouter>);
 expect(await screen.findByRole('link',{name:'접수내역으로'})).toHaveAttribute('href','/reports');
 expect(screen.queryByText('NOT_FOUND')).not.toBeInTheDocument();
});

it('shows field messages without exposing error codes and English field keys',()=>{
 render(<ErrorBox error={new ApiError(403,'FORBIDDEN','요청 권한을 확인해 주세요.',{username:'아이디를 확인해 주세요.'})}/>);
 expect(screen.getByRole('alert')).toHaveTextContent('아이디를 확인해 주세요.');
 expect(screen.getByRole('alert')).not.toHaveTextContent('FORBIDDEN');
 expect(screen.getByRole('alert')).not.toHaveTextContent('username');
});

it('adds newly supplied server types to manual intake without showing their internal codes',async()=>{
 sessionStorage.setItem('skope-draft',JSON.stringify({originalText:'새로운 유형 접수 내용',preferredIntent:'REPORT',clientRequestId:'new-type',manual:true}));
 vi.mocked(api).mockResolvedValue({items:[{id:9,category:'NEW_CATEGORY',subcategory:'NEW_TYPE',workName:'새 담당 업무',departmentName:'담당 부서',verified:true}]});
 render(<MemoryRouter><AnalyzePage/></MemoryRouter>);
 expect(await screen.findByRole('option',{name:'새 담당 업무'})).toHaveValue('NEW_CATEGORY/NEW_TYPE');
 expect(screen.queryByText('NEW_TYPE')).not.toBeInTheDocument();
});
