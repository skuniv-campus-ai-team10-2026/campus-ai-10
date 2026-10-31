import {bindPhotos,DraftPhotoRecovery} from './attachments';
import {ChevronLeft,ClipboardCheck,FilePenLine,Send} from 'lucide-react';
import {useEffect,useRef,useState} from 'react';
import {Link,useNavigate} from 'react-router-dom';
import {post} from './api';
import type {Draft,IntakeResult} from './types';
import {Emergency,ErrorBox,Loading,PageHeading,Steps} from './ui';

export function IntakeFlow({draft,onEdit}:{draft:Draft;onEdit:()=>void}) {
 const navigate=useNavigate(),[result,setResult]=useState(draft.intakeResult),[answer,setAnswer]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState<unknown>(null),pending=useRef(false);
 function receive(next:IntakeResult){
  if(!next||!['NEEDS_INPUT','REVIEW_REQUIRED','ACCEPTED'].includes(next.status)
   ||(next.status==='NEEDS_INPUT'&&!next.analysis?.report?.followUpQuestion?.trim())
   ||(next.status==='ACCEPTED'&&!next.report?.id))throw new Error('접수 응답을 확인하지 못했습니다. 내용을 직접 수정하거나 관리자 검토로 접수해 주세요.');
  if(next.status==='ACCEPTED'&&next.report){bindPhotos(draft.clientRequestId,next.report.id);sessionStorage.removeItem('skope-draft');sessionStorage.setItem('skope-last-report',String(next.report.id));navigate('/reports/success',{state:{report:next.report},replace:true});return;}
  const saved=sessionStorage.getItem('skope-draft');const current:Draft=saved?JSON.parse(saved):draft;
  setResult(next);sessionStorage.setItem('skope-draft',JSON.stringify({...draft,hadPhotos:current.clientRequestId===draft.clientRequestId?current.hadPhotos:draft.hadPhotos,intakeResult:next,analysis:next.analysis??undefined}));
 }
 useEffect(()=>{
  let active=true;setBusy(true);
  // Resume only: mounting/reloading never repeats an AI analysis or invents a new request ID.
  post<IntakeResult>('/assistant/intake',{clientRequestId:draft.clientRequestId,resume:true}).then(next=>{if(active)receive(next);}).catch(e=>{if(active)setError(e);}).finally(()=>{if(active)setBusy(false);});
  return()=>{active=false;};
 },[draft.clientRequestId]);
 async function send(review=false){
  if(pending.current)return;pending.current=true;setBusy(true);setError(null);
  try{const next=await post<IntakeResult>('/assistant/intake',{clientRequestId:draft.clientRequestId,revision:result?.revision,...(review?{submitForReview:true,...(answer.trim()?{answer}:{} )}:{answer})});receive(next);setAnswer('');}
  catch(e){setError(e);}finally{pending.current=false;setBusy(false);}
 }
 const analysis=result?.analysis,question=result?.status==='NEEDS_INPUT'?analysis?.report?.followUpQuestion?.trim():undefined;
 const report=analysis?.report,reason=analysis?.clarification?.reason;
 // Input hints use server metadata only; the question itself always comes from the server.
 const field=analysis?.followUpState?.nextField;
 const placeholder=reason?.startsWith('LOCATION')||field==='LOCATION'?'예: 북악관 3층, 엘리베이터 옆':reason==='TARGET_UNCLEAR'?'예: 창가 쪽 책상, 복도 정수기':reason==='WORKFLOW_UNCLEAR'?'예: 신청서를 제출하는 단계에서 오류가 나요':field==='OCCURRED_AT'?'예: 오늘 오전부터, 정확한 시점은 모르겠어요':reason==='SYMPTOM_DETAIL'||reason==='ISSUE_UNCLEAR'||field==='SYMPTOM'?'직접 겪은 현상이나 궁금한 내용을 적어주세요.':'위 질문에 대한 답변을 자유롭게 적어주세요.';
 return <div className="intake-question-page"><Link to="/" className="back-link"><ChevronLeft size={16} aria-hidden="true"/>처음으로</Link><PageHeading eyebrow="" className="intake-question-heading" title={<span id="intake-question">{question||'접수 내용 확인'}</span>}/>
  <Steps current={1}/>

  <section className="card answer-card intake-card">
   {analysis?.report?.emergencyHint&&<Emergency/>}<ErrorBox error={error}/>
   {busy&&<Loading text="내용을 확인하고 있어요"/>}
   {question&&<form id="intake-answer-form" onSubmit={e=>{e.preventDefault();void send();}}>{analysis?.clarification?.message&&analysis.clarification.message.trim()!==question&&<p className="field-hint intake-question-reason">{analysis.clarification.message}</p>}<label>추가 답변<textarea required maxLength={500} value={answer} onChange={e=>setAnswer(e.target.value)} placeholder={placeholder} aria-describedby="intake-question" rows={3}/></label></form>}
   {!question&&<p>{result?.message||'내용을 직접 수정하거나 관리자 검토로 접수할 수 있습니다.'}</p>}
   <div className="intake-context">
    {(report?.locationText||report?.symptom||report?.occurredAtText)&&<div className="intake-summary" aria-label="현재까지 파악한 내용"><h3>현재까지 파악한 내용</h3><dl>{report.locationText&&<div><dt>장소</dt><dd>{report.locationText}</dd></div>}{report.symptom&&<div><dt>문제·문의</dt><dd>{report.symptom}</dd></div>}{report.occurredAtText&&<div><dt>발생 시점</dt><dd>{report.occurredAtText}</dd></div>}</dl></div>}
    <details><summary>최초 원문 보기</summary><blockquote>{draft.originalText}</blockquote></details>
    {result?.latestAnswer&&<p className="intake-latest-answer">마지막 답변: {result.latestAnswer}</p>}
   </div>

   <DraftPhotoRecovery draft={draft} disabled={busy}/>
   {question&&<button form="intake-answer-form" className="button primary" disabled={busy}><Send size={16} aria-hidden="true"/>답변 보내기</button>}
   {result&&<div className="intake-actions">
    <button className="button intake-outline" disabled={busy} onClick={onEdit}><FilePenLine size={16} aria-hidden="true"/>내용 직접 수정</button>
    <button className="button intake-outline" disabled={busy} onClick={()=>void send(true)}><ClipboardCheck size={16} aria-hidden="true"/>이 내용으로 접수하기</button>
   </div>}
   {result&&<p className="field-hint">확인되지 않은 부분은 관리자에게 함께 전달합니다.</p>}
  </section></div>;
}
