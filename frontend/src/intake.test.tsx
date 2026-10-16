import {beforeEach,expect,it,vi} from 'vitest';
import {cleanup,render,screen,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {MemoryRouter,Route,Routes} from 'react-router-dom';
import {post} from './api';
import {IntakeFlow} from './intake';
import {HomePage,SuccessPage} from './student';
import {SessionContext} from './context';
import type {Draft,IntakeResult} from './types';
vi.mock('./api',async original=>({...await original<typeof import('./api')>(),post:vi.fn()}));
const pending:IntakeResult={status:'NEEDS_INPUT',revision:1,aiMode:'LIVE',analysis:{intent:'UNKNOWN',aiMode:'LIVE',report:{category:'OTHER',subcategory:'UNKNOWN',symptom:null,followUpQuestion:'어떤 점이 불편한가요?'},questionAnswer:null,routingPreview:null}};
const draft:Draft={originalText:'강의실이 너무 불편해요',preferredIntent:'AUTO',clientRequestId:'test-request',intake:true,intakeResult:pending};
beforeEach(()=>{cleanup();sessionStorage.clear();vi.clearAllMocks();});
it('explains why the particular clarification is needed',async()=>{
 vi.mocked(post).mockResolvedValueOnce({...pending,analysis:{...pending.analysis!,clarification:{reason:'ISSUE_UNCLEAR',message:'담당자가 어떤 문제를 처리해야 하는지 알아야 해요.',neededReasons:['ISSUE_UNCLEAR'],source:'SERVER_RULES'}}});
 mount();expect(await screen.findByText('담당자가 어떤 문제를 처리해야 하는지 알아야 해요.')).toBeInTheDocument();
});
it('retains the safety warning after urgent automatic intake',()=>{
 render(<MemoryRouter initialEntries={[{pathname:'/reports/success',state:{report:{id:90,status:'RECEIVED',emergencyHint:true}}}]}><SuccessPage/></MemoryRouter>);
 expect(screen.getByText('위험이 느껴진다면 안전을 먼저 확보해 주세요.')).toBeInTheDocument();
});
function mount(){return render(<MemoryRouter><Routes><Route path="/" element={<IntakeFlow draft={draft} onEdit={vi.fn()}/>}/><Route path="/reports/success" element={<p>접수 완료 화면</p>}/></Routes></MemoryRouter>);}
it('shows only the needed question and submits after one answer without a review form',async()=>{
 vi.mocked(post).mockResolvedValueOnce(pending).mockResolvedValueOnce({status:'ACCEPTED',aiMode:'LIVE',report:{id:21}});
 mount();const user=userEvent.setup();await waitFor(()=>expect(screen.getByRole('button',{name:'답변 보내기'})).toBeEnabled());
 expect(screen.queryByLabelText('발생 장소')).not.toBeInTheDocument();expect(screen.queryByLabelText('확인된 발생일 (선택)')).not.toBeInTheDocument();
 await user.type(screen.getByLabelText('추가 답변'),'은주2관 403호에서 물이 떨어져요');await user.click(screen.getByRole('button',{name:'답변 보내기'}));
 expect(await screen.findByText('접수 완료 화면')).toBeInTheDocument();
 expect(post).toHaveBeenNthCalledWith(1,'/assistant/intake',{clientRequestId:'test-request',resume:true});
 expect(post).toHaveBeenNthCalledWith(2,'/assistant/intake',{clientRequestId:'test-request',revision:1,answer:'은주2관 403호에서 물이 떨어져요'});
 expect(sessionStorage.getItem('skope-draft')).toBeNull();
});
it('keeps the answer on network failure and does not retry automatically',async()=>{
 vi.mocked(post).mockResolvedValueOnce(pending).mockRejectedValueOnce(new Error('연결 실패'));
 mount();const user=userEvent.setup();await waitFor(()=>expect(screen.getByRole('button',{name:'답변 보내기'})).toBeEnabled());
 await user.type(screen.getByLabelText('추가 답변'),'에어컨이 안 켜져요');await user.click(screen.getByRole('button',{name:'답변 보내기'}));
 await waitFor(()=>expect(post).toHaveBeenCalledTimes(2));expect(screen.getByLabelText('추가 답변')).toHaveValue('에어컨이 안 켜져요');
});
it('uses explicit administrator review when AI fails without claiming demo analysis',async()=>{
 vi.mocked(post).mockResolvedValueOnce({status:'REVIEW_REQUIRED',revision:1,analysis:null,aiMode:'LIVE',message:'AI 분석을 완료하지 못했습니다.'}).mockResolvedValueOnce({status:'ACCEPTED',aiMode:'LIVE',report:{id:22}});
 mount();const user=userEvent.setup();expect(await screen.findByText('AI 분석을 완료하지 못했습니다.')).toBeInTheDocument();
 expect(screen.queryByLabelText('추가 답변')).not.toBeInTheDocument();await user.click(screen.getByRole('button',{name:'관리자 검토로 접수'}));
 expect(await screen.findByText('접수 완료 화면')).toBeInTheDocument();expect(vi.mocked(post).mock.calls[1][1]).toMatchObject({submitForReview:true,revision:1});
});
it('accepts a sufficient initial input directly from Home',async()=>{
 vi.mocked(post).mockResolvedValue({status:'ACCEPTED',aiMode:'LIVE',report:{id:23}});
 render(<SessionContext.Provider value={{user:{id:1,displayName:'학생',role:'STUDENT'},meta:{aiMode:'LIVE',embeddingMode:'LIVE',databaseMode:'MYSQL'},logout:async()=>{}}}><MemoryRouter><Routes><Route path="/" element={<HomePage/>}/><Route path="/reports/success" element={<p>접수 완료 화면</p>}/></Routes></MemoryRouter></SessionContext.Provider>);
 const user=userEvent.setup();await user.type(screen.getByLabelText('캠퍼스 문제 또는 질문'),'은주2관 403호에서 물이 떨어져요');await user.click(screen.getByRole('button',{name:'문의·신고 보내기'}));
 expect(await screen.findByText('접수 완료 화면')).toBeInTheDocument();expect(post).toHaveBeenCalledTimes(1);expect(vi.mocked(post).mock.calls[0][0]).toBe('/assistant/intake');
});
