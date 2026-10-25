import {bindPhotos} from './attachments';
import {ChevronLeft,ClipboardCheck,FilePenLine,Send} from 'lucide-react';
import {useEffect,useRef,useState} from 'react';
import {Link,useNavigate} from 'react-router-dom';
import {post} from './api';
import type {Draft,IntakeResult} from './types';
import {Emergency,ErrorBox,Loading,ModeBadge,PageHeading,Steps} from './ui';

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
  try{const next=await post<IntakeResult>('/assistant/intake',{clientRequestId:draft.clientRequestId,revision:result?.revision,...(review?{submitForReview:true}:{answer})});receive(next);setAnswer('');}
  catch(e){setError(e);}finally{pending.current=false;setBusy(false);}
 }
 const analysis=result?.analysis,question=analysis?.report?.followUpQuestion;
 return <><Link to="/" className="back-link"><ChevronLeft size={16} aria-hidden="true"/>처음으로</Link><PageHeading eyebrow="" title={<>조금만 더 알려주세요 <span className="orange-dot">!</span></>}/>
  <Steps current={1}/>

  <section className="card answer-card intake-card">{result?.status==='NEEDS_INPUT'&&<div className="card-top intake-assistant-heading"><span className="icon-tile"><img className="assistant-logo" src="/skope-assistant-outline.svg" alt=""/></span><div><h2>SKOPE 도우미</h2><p>자유롭게 이야기 해주세요.<span className="intake-desktop-space"> </span><br className="intake-mobile-break"/>SKOPE가 필요한 내용을 정리해요.</p></div></div>}<ModeBadge mode={result?.aiMode}/><>{result?.latestAnswer&&<p className="intake-answer-label">내 마지막 추가 답변</p>}<blockquote>{result?.latestAnswer||draft.originalText}</blockquote></>

   {analysis?.report?.emergencyHint&&<Emergency/>}<ErrorBox error={error}/>
   {busy&&<Loading text="내용을 확인하고 있어요"/>}
   {result?.status==='NEEDS_INPUT'&&question?<form onSubmit={e=>{e.preventDefault();void send();}}>{analysis?.clarification?.message&&analysis.clarification.message!=='담당자가 어떤 문제를 처리해야 하는지 알아야 해요.'&&<p className="field-hint">{analysis.clarification.message}</p>}<label>추가 답변<textarea required maxLength={500} value={answer} onChange={e=>setAnswer(e.target.value)} placeholder={question?.startsWith('어떤 점이 불편한가요?')?'어떤점이 불편한가요? 실제 문제를 알려주세요.\n예: 냉난방, 책상·의자, 소음':question} rows={3}/></label><button className="button primary" disabled={busy}><Send size={16} aria-hidden="true"/>답변 보내기</button></form>:<p>{result?.message}</p>}
   {result&&<div className="intake-actions">
    <button className="button intake-outline" disabled={busy} onClick={onEdit}><FilePenLine size={16} aria-hidden="true"/>내용 직접 수정</button>
    <button className="button intake-outline" disabled={busy} onClick={()=>void send(true)}><ClipboardCheck size={16} aria-hidden="true"/>더 설명하기 어려워요 · 관리자 검토로 접수</button>
   </div>}
  </section></>;
}
