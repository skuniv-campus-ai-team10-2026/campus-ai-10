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
  if(next.status==='ACCEPTED'&&next.report){bindPhotos(draft.clientRequestId,next.report.id);sessionStorage.removeItem('skope-draft');sessionStorage.setItem('skope-last-report',String(next.report.id));navigate('/reports/success',{state:{report:next.report},replace:true});return;}
  setResult(next);sessionStorage.setItem('skope-draft',JSON.stringify({...draft,intakeResult:next,analysis:next.analysis??undefined}));
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
 const analysis=result?.analysis,question=analysis?.report?.followUpQuestion;
 const report=analysis?.report,reason=analysis?.clarification?.reason;
 const placeholder=reason?.startsWith('LOCATION')?'예: 지하 1층 엘리베이터 옆':reason==='TARGET_UNCLEAR'?'예: 창가 쪽 책상, 복도 정수기':reason==='WORKFLOW_UNCLEAR'?'예: 신청서를 제출할 때 오류가 나요':'직접 겪은 내용을 편하게 적어주세요.';
 return <><Link to="/" className="back-link"><ChevronLeft size={16} aria-hidden="true"/>처음으로</Link><PageHeading eyebrow="" title={<>조금만 더 알려주세요 <span className="orange-dot">!</span></>}/>
  <Steps current={1}/><DraftPhotoRecovery draft={draft} disabled={busy}/>

  <section className="card answer-card intake-card">{result?.status==='NEEDS_INPUT'&&<div className="card-top intake-assistant-heading"><span className="icon-tile"><img className="assistant-logo" src="/skope-assistant-outline.svg" alt=""/></span><div><h2>SKOPE 도우미</h2><p>접수에 필요한 내용만 확인할게요.</p></div></div>}
   <div className="intake-context">
    {(report?.locationText||report?.symptom||report?.occurredAtText)&&<div className="intake-summary" aria-label="현재 파악한 내용"><h3>현재 파악한 내용</h3><dl>{report.locationText&&<div><dt>장소</dt><dd>{report.locationText}</dd></div>}{report.symptom&&<div><dt>문제·문의</dt><dd>{report.symptom}</dd></div>}{report.occurredAtText&&<div><dt>발생 시점</dt><dd>{report.occurredAtText}</dd></div>}</dl></div>}
    <details><summary>처음 남긴 내용</summary><blockquote>{draft.originalText}</blockquote></details>
    {result?.latestAnswer&&<p className="intake-latest-answer">마지막 답변: {result.latestAnswer}</p>}
   </div>

   {analysis?.report?.emergencyHint&&<Emergency/>}<ErrorBox error={error}/>
   {busy&&<Loading text="내용을 확인하고 있어요"/>}
   {result?.status==='NEEDS_INPUT'&&question?<form onSubmit={e=>{e.preventDefault();void send();}}><h3 id="intake-question" className="intake-question">{question}</h3>{analysis?.clarification?.message&&analysis.clarification.message!=='담당자가 어떤 문제를 처리해야 하는지 알아야 해요.'&&<p className="field-hint">{analysis.clarification.message}</p>}<label>추가 답변<textarea required maxLength={500} value={answer} onChange={e=>setAnswer(e.target.value)} placeholder={placeholder} aria-describedby="intake-question" rows={3}/></label><button className="button primary" disabled={busy}><Send size={16} aria-hidden="true"/>답변 보내기</button></form>:<p>{result?.message}</p>}
   {result&&<div className="intake-actions">
    <button className="button intake-outline" disabled={busy} onClick={onEdit}><FilePenLine size={16} aria-hidden="true"/>내용 직접 수정</button>
    <button className="button intake-outline" disabled={busy} onClick={()=>void send(true)}><ClipboardCheck size={16} aria-hidden="true"/>이 내용으로 접수하기</button>
   </div>}
   {result&&<p className="field-hint">확인되지 않은 부분은 관리자에게 함께 전달합니다.</p>}
  </section></>;
}
