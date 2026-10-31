import {beforeEach,expect,it,vi} from 'vitest';
import {cleanup,render,screen,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {MemoryRouter,Route,Routes} from 'react-router-dom';
import {api,post} from './api';
import {AutoUploadPhotos,clearPendingPhotos,stagePhotos} from './attachments';
import {IntakeFlow} from './intake';
import {HomePage,SuccessPage} from './student';
import {SessionContext} from './context';
import type {Draft,IntakeResult} from './types';
vi.mock('./api',async original=>({...await original<typeof import('./api')>(),post:vi.fn(),api:vi.fn()}));
const pending:IntakeResult={status:'NEEDS_INPUT',revision:1,aiMode:'LIVE',analysis:{intent:'UNKNOWN',aiMode:'LIVE',report:{category:'OTHER',subcategory:'UNKNOWN',symptom:null,followUpQuestion:'어떤 점이 불편한가요?'},questionAnswer:null,routingPreview:null}};
const draft:Draft={originalText:'강의실이 너무 불편해요',preferredIntent:'AUTO',clientRequestId:'test-request',intake:true,intakeResult:pending};
beforeEach(()=>{cleanup();clearPendingPhotos();URL.createObjectURL=vi.fn(()=>'blob:preview');URL.revokeObjectURL=vi.fn();sessionStorage.clear();vi.clearAllMocks();});
it('shows the server clarification message as supporting text',async()=>{
 vi.mocked(post).mockResolvedValueOnce({...pending,analysis:{...pending.analysis!,clarification:{reason:'ISSUE_UNCLEAR',message:'담당자가 어떤 문제를 처리해야 하는지 알아야 해요.',neededReasons:['ISSUE_UNCLEAR'],source:'SERVER_RULES'}}});
 mount();await screen.findByRole('button',{name:'답변 보내기'});await waitFor(()=>expect(screen.getByRole('button',{name:'답변 보내기'})).toBeEnabled());expect(screen.getByText('담당자가 어떤 문제를 처리해야 하는지 알아야 해요.')).toBeInTheDocument();
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
 expect(screen.queryByLabelText('추가 답변')).not.toBeInTheDocument();await user.click(screen.getByRole('button',{name:'이 내용으로 접수하기'}));
 expect(await screen.findByText('접수 완료 화면')).toBeInTheDocument();expect(vi.mocked(post).mock.calls[1][1]).toMatchObject({submitForReview:true,revision:1});
});
it('accepts a sufficient initial input directly from Home',async()=>{
 vi.mocked(post).mockResolvedValue({status:'ACCEPTED',aiMode:'LIVE',report:{id:23}});
 render(<SessionContext.Provider value={{user:{id:1,displayName:'학생',role:'STUDENT'},meta:{aiMode:'LIVE',embeddingMode:'LIVE',databaseMode:'MYSQL'},logout:async()=>{}}}><MemoryRouter><Routes><Route path="/" element={<HomePage/>}/><Route path="/reports/success" element={<p>접수 완료 화면</p>}/></Routes></MemoryRouter></SessionContext.Provider>);
 const user=userEvent.setup();await user.type(screen.getByLabelText('캠퍼스 문제 또는 질문'),'은주2관 403호에서 물이 떨어져요');await user.click(screen.getByRole('button',{name:'문의·신고 보내기'}));
 expect(await screen.findByText('접수 완료 화면')).toBeInTheDocument();expect(post).toHaveBeenCalledTimes(1);expect(vi.mocked(post).mock.calls[0][0]).toBe('/assistant/intake');
});

it('allows administrator review during a question without an answer and preserves the original draft on failure',async()=>{
 vi.mocked(post).mockResolvedValueOnce(pending).mockRejectedValueOnce(new Error('연결 실패')).mockResolvedValueOnce({status:'ACCEPTED',aiMode:'LIVE',report:{id:24}});
 mount();const user=userEvent.setup();
 const review=await screen.findByRole('button',{name:'이 내용으로 접수하기'});
 await waitFor(()=>expect(review).toBeEnabled());
 expect(screen.getByRole('button',{name:'내용 직접 수정'})).toBeEnabled();
 await user.click(review);
 expect(await screen.findByText('연결 실패')).toBeInTheDocument();
 expect(screen.queryByText('처음 남긴 내용')).not.toBeInTheDocument();
 expect(JSON.parse(sessionStorage.getItem('skope-draft')!).originalText).toBe(draft.originalText);
 expect(post).toHaveBeenNthCalledWith(2,'/assistant/intake',{clientRequestId:draft.clientRequestId,revision:1,submitForReview:true});
 await user.click(review);expect(await screen.findByText('접수 완료 화면')).toBeInTheDocument();
 expect(post).toHaveBeenNthCalledWith(3,'/assistant/intake',{clientRequestId:draft.clientRequestId,revision:1,submitForReview:true});
});

it('allows editing the existing input while a follow-up question is shown',async()=>{
 vi.mocked(post).mockResolvedValueOnce(pending);const edit=vi.fn();
 render(<MemoryRouter><IntakeFlow draft={draft} onEdit={edit}/></MemoryRouter>);
 await waitFor(()=>expect(screen.getByRole('button',{name:'내용 직접 수정'})).toBeEnabled());
 await userEvent.click(screen.getByRole('button',{name:'내용 직접 수정'}));
 expect(edit).toHaveBeenCalledTimes(1);
 expect(JSON.parse(sessionStorage.getItem('skope-draft')!).originalText).toBe(draft.originalText);
 expect(post).toHaveBeenCalledTimes(1);
});

it('keeps the actual question visible while typing and shows cumulative facts with the original input',async()=>{
 const question='어느 층이나 공간에서 발생하나요?';
 const next:IntakeResult={...pending,latestAnswer:'정수기',analysis:{...pending.analysis!,report:{category:'OTHER',subcategory:'UNKNOWN',symptom:'정수기 고장',locationText:'은주2관',followUpQuestion:question},clarification:{reason:'LOCATION_DETAIL',message:'담당자가 확인할 장소를 정하기 위해 필요해요.',neededReasons:['LOCATION_DETAIL'],source:'SERVER_RULES'}}};
 vi.mocked(post).mockResolvedValueOnce(next);mount();
 await waitFor(()=>expect(screen.getByRole('button',{name:'답변 보내기'})).toBeEnabled());
 const user=userEvent.setup();await user.type(screen.getByLabelText('추가 답변'),'지하 1층');
 expect(screen.getByRole('heading',{name:question})).toBeVisible();
 expect(screen.getByLabelText('추가 답변')).toHaveAccessibleDescription(question);
 expect(screen.getByRole('heading',{name:'현재까지 파악한 내용'})).toBeInTheDocument();
 const original=screen.getByText(draft.originalText);expect(original.closest('details')).not.toHaveAttribute('open');
 await user.click(screen.getByText('최초 원문 보기'));expect(original).toBeVisible();
 expect(screen.getByText('정수기 고장')).toBeVisible();expect(screen.getByText('은주2관')).toBeVisible();
 expect(screen.getByText('마지막 답변: 정수기')).toBeInTheDocument();
 expect(screen.queryByPlaceholderText(/냉난방/)).not.toBeInTheDocument();
});

it('includes a typed reply when submitting directly for review without another analysis request',async()=>{
 vi.mocked(post).mockResolvedValueOnce(pending).mockResolvedValueOnce({status:'ACCEPTED',aiMode:'LIVE',report:{id:25}});
 mount();const user=userEvent.setup();await waitFor(()=>expect(screen.getByRole('button',{name:'이 내용으로 접수하기'})).toBeEnabled());
 await user.type(screen.getByLabelText('추가 답변'),'지하 1층인데 자세한 위치는 모르겠어요');
 await user.click(screen.getByRole('button',{name:'이 내용으로 접수하기'}));
 expect(await screen.findByText('접수 완료 화면')).toBeInTheDocument();
 expect(post).toHaveBeenNthCalledWith(2,'/assistant/intake',{clientRequestId:draft.clientRequestId,revision:1,submitForReview:true,answer:'지하 1층인데 자세한 위치는 모르겠어요'});
 expect(post).toHaveBeenCalledTimes(2);
});

it('puts the exact server question once above the input, summary and photos',async()=>{
 const question='어느 층이나 어느 쪽에서 발생했나요?';
 vi.mocked(post).mockResolvedValue({...pending,analysis:{...pending.analysis!,report:{...pending.analysis!.report,symptom:'정수기 문제',followUpQuestion:question},clarification:{reason:'LOCATION_DETAIL',message:question,neededReasons:['LOCATION_DETAIL'],source:'SERVER_RULES'}}});
 mount();await waitFor(()=>expect(screen.getByRole('button',{name:'답변 보내기'})).toBeEnabled());
 const heading=screen.getByRole('heading',{level:1,name:question});expect(screen.getAllByText(question)).toHaveLength(1);
 const input=screen.getByLabelText('추가 답변');expect(input).toHaveAttribute('placeholder','예: 북악관 3층, 엘리베이터 옆');
 expect(heading.compareDocumentPosition(input)&Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
 expect(input.compareDocumentPosition(screen.getByRole('heading',{name:'현재까지 파악한 내용'}))&Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
 expect(screen.queryByText('조금만 더 알려주세요')).not.toBeInTheDocument();
});

it.each([
 ['LOCATION_REQUIRED','LOCATION','북악관 3층'],
 ['SYMPTOM_DETAIL','SYMPTOM','직접 겪은 현상'],
 ['WORKFLOW_UNCLEAR','SYMPTOM','신청서를 제출하는 단계'],
 [null,'OCCURRED_AT','오늘 오전부터'],
 [null,null,'위 질문에 대한 답변'],
] as const)('uses server metadata for input guidance: %s / %s',async(reason,nextField,hint)=>{
 vi.mocked(post).mockResolvedValue({...pending,analysis:{...pending.analysis!,clarification:{reason,message:null,neededReasons:[],source:'SERVER_RULES'},followUpState:{turns:2,noProgress:1,askedFields:[],status:'CONTINUE',nextField}}});
 mount();await waitFor(()=>expect(screen.getByRole('button',{name:'답변 보내기'})).toBeEnabled());
 expect(screen.getByLabelText('추가 답변').getAttribute('placeholder')).toContain(hint);
 expect(screen.getByRole('heading',{level:1})).toHaveTextContent(pending.analysis!.report!.followUpQuestion!);
});

it('updates the question, cumulative facts and revision after an answer without resetting server state',async()=>{
 const next={...pending,revision:7,latestAnswer:'북악관 3층',analysis:{...pending.analysis!,report:{...pending.analysis!.report,locationText:'북악관 3층',followUpQuestion:'언제부터 발생했나요?'},followUpState:{turns:3,noProgress:1,askedFields:['LOCATION'],status:'CONTINUE' as const,nextField:'OCCURRED_AT'}}};
 vi.mocked(post).mockResolvedValueOnce(pending).mockResolvedValueOnce(next).mockResolvedValueOnce({status:'ACCEPTED',aiMode:'DEMO',report:{id:123}});
 mount();const user=userEvent.setup();await waitFor(()=>expect(screen.getByRole('button',{name:'답변 보내기'})).toBeEnabled());
 await user.type(screen.getByLabelText('추가 답변'),'북악관 3층');await user.click(screen.getByRole('button',{name:'답변 보내기'}));
 expect(await screen.findByRole('heading',{level:1,name:'언제부터 발생했나요?'})).toBeVisible();
 expect(screen.queryByText(pending.analysis!.report!.followUpQuestion!)).not.toBeInTheDocument();expect(screen.getByText('북악관 3층')).toBeVisible();
 expect(JSON.parse(sessionStorage.getItem('skope-draft')!).intakeResult.analysis.followUpState).toEqual(next.analysis.followUpState);
 await user.type(screen.getByLabelText('추가 답변'),'오늘 오전부터');await user.click(screen.getByRole('button',{name:'답변 보내기'}));
 expect(await screen.findByText('접수 완료 화면')).toBeInTheDocument();expect(vi.mocked(post).mock.calls[2][1]).toEqual({clientRequestId:draft.clientRequestId,revision:7,answer:'오늘 오전부터'});
});

it.each(['', '   '])('offers error and manual review instead of inventing an empty question: %j',async question=>{
 const invalid={...pending,analysis:{...pending.analysis!,report:{...pending.analysis!.report!,followUpQuestion:question}}};
 vi.mocked(post).mockResolvedValue(invalid);
 render(<MemoryRouter><IntakeFlow draft={{...draft,intakeResult:invalid}} onEdit={vi.fn()}/></MemoryRouter>);
 expect(await screen.findByText(/접수 응답을 확인하지 못했습니다/)).toBeInTheDocument();
 expect(screen.queryByLabelText('추가 답변')).not.toBeInTheDocument();expect(screen.getByRole('button',{name:'내용 직접 수정'})).toBeEnabled();expect(screen.getByRole('button',{name:'이 내용으로 접수하기'})).toBeEnabled();expect(post).toHaveBeenCalledTimes(1);
});

it('keeps the typed answer and previous question when a malformed response is returned',async()=>{
 vi.mocked(post).mockResolvedValueOnce(pending).mockResolvedValueOnce({status:'ACCEPTED',aiMode:'DEMO'});
 mount();const user=userEvent.setup();await waitFor(()=>expect(screen.getByRole('button',{name:'답변 보내기'})).toBeEnabled());
 await user.type(screen.getByLabelText('추가 답변'),'지하 1층');await user.click(screen.getByRole('button',{name:'답변 보내기'}));
 expect(await screen.findByText(/접수 응답을 확인하지 못했습니다/)).toBeInTheDocument();expect(screen.getByLabelText('추가 답변')).toHaveValue('지하 1층');expect(post).toHaveBeenCalledTimes(2);
});

it('resumes a saved question without starting a new analysis or changing its revision',async()=>{
 const saved={...pending,revision:9};vi.mocked(post).mockResolvedValue(saved);
 const view=mount();await waitFor(()=>expect(screen.getByRole('button',{name:'답변 보내기'})).toBeEnabled());view.unmount();mount();
 await waitFor(()=>expect(post).toHaveBeenCalledTimes(2));
 for(const [,body] of vi.mocked(post).mock.calls)expect(body).toEqual({clientRequestId:draft.clientRequestId,resume:true});
 expect(JSON.parse(sessionStorage.getItem('skope-draft')!).intakeResult.revision).toBe(9);
});

it.each([false,true])('shows recovery only when selected photos were lost: %s',async(hadPhotos)=>{
 vi.mocked(post).mockResolvedValue(pending);
 render(<MemoryRouter><IntakeFlow draft={{...draft,hadPhotos}} onEdit={vi.fn()}/></MemoryRouter>);
 await waitFor(()=>expect(screen.getByRole('button',{name:'답변 보내기'})).toBeEnabled());
 expect(!!screen.queryByText(/새로고침으로/)).toBe(hadPhotos);
 expect(screen.getByLabelText('첨부 사진 선택')).toBeInTheDocument();
});
it('does not warn when staged photos are still available',async()=>{
 stagePhotos(draft.clientRequestId,[new File(['x'],'original.png',{type:'image/png'})]);
 vi.mocked(post).mockResolvedValue(pending);
 render(<MemoryRouter><IntakeFlow draft={{...draft,hadPhotos:true}} onEdit={vi.fn()}/></MemoryRouter>);
 await waitFor(()=>expect(screen.getByRole('button',{name:'답변 보내기'})).toBeEnabled());
 expect(screen.queryByText(/새로고침으로/)).not.toBeInTheDocument();
 expect(screen.getByAltText('original.png')).toBeInTheDocument();
});
it.each([false,true])('uploads an added or reselected photo once after answering: hadPhotos=%s',async(hadPhotos)=>{
 const restored=JSON.parse(JSON.stringify({...draft,hadPhotos}));
 vi.mocked(post).mockResolvedValueOnce(pending).mockResolvedValueOnce({status:'ACCEPTED',aiMode:'MOCK',report:{id:91}});
 vi.mocked(api).mockResolvedValue({items:[]});
 const view=render(<MemoryRouter><Routes><Route path="/" element={<IntakeFlow draft={restored} onEdit={vi.fn()}/>}/><Route path="/reports/success" element={<AutoUploadPhotos reportId={91}/>}/></Routes></MemoryRouter>);
 const user=userEvent.setup();await waitFor(()=>expect(screen.getByRole('button',{name:'답변 보내기'})).toBeEnabled());
 const photo=new File(['replacement'],'again.png',{type:'image/png'});
 await user.upload(screen.getByLabelText('첨부 사진 선택'),photo);
 expect(screen.getByAltText('again.png')).toBeInTheDocument();
 expect(JSON.parse(sessionStorage.getItem('skope-draft')!).hadPhotos).toBe(true);
 expect(screen.queryByText(/새로고침으로/)).not.toBeInTheDocument();
 await user.type(screen.getByLabelText('추가 답변'),'물이 떨어져요');await user.click(screen.getByRole('button',{name:'답변 보내기'}));
 await waitFor(()=>expect(vi.mocked(api).mock.calls.filter(([,options])=>options?.method==='POST')).toHaveLength(1));
 expect((vi.mocked(api).mock.calls.find(([,options])=>options?.method==='POST')![1]!.body as FormData).get('file')).toBe(photo);
 view.unmount();render(<AutoUploadPhotos reportId={91}/>);
 expect(vi.mocked(api).mock.calls.filter(([,options])=>options?.method==='POST')).toHaveLength(1);
 expect(post).toHaveBeenCalledTimes(2);
});
