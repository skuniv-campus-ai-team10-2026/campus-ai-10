import {useEffect,useRef,useState} from 'react';
import {Link,useNavigate} from 'react-router-dom';
import {post} from './api';
import type {Draft,IntakeResult} from './types';
import {Emergency,ErrorBox,Loading,ModeBadge,PageHeading} from './ui';

export function IntakeFlow({draft,onEdit}:{draft:Draft;onEdit:()=>void}) {
 const navigate=useNavigate(),[result,setResult]=useState(draft.intakeResult),[answer,setAnswer]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState<unknown>(null),pending=useRef(false);
 function receive(next:IntakeResult){
  if(next.status==='ACCEPTED'&&next.report){sessionStorage.removeItem('skope-draft');sessionStorage.setItem('skope-last-report',String(next.report.id));navigate('/reports/success',{state:{report:next.report},replace:true});return;}
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
 return <><PageHeading eyebrow="CAMPUS ASSISTANT" title={result?.status==='NEEDS_INPUT'?'조금만 더 알려주세요':'관리자 검토로 이어갈 수 있어요'} description="처리에 필요한 내용만 확인합니다. 날짜와 시간은 따로 입력하지 않아도 됩니다."/>
  <section className="card answer-card"><ModeBadge mode={result?.aiMode}/><blockquote>{draft.originalText}</blockquote>
   {result?.latestAnswer&&<p>마지막 추가 답변: {result.latestAnswer}</p>}
   {analysis?.report?.emergencyHint&&<Emergency/>}<ErrorBox error={error}/>
   {busy&&<Loading text="내용을 확인하고 있어요"/>}
   {result?.status==='NEEDS_INPUT'&&question?<form onSubmit={e=>{e.preventDefault();void send();}}><p>{question}</p>{analysis?.clarification?.message&&<p className="field-hint">{analysis.clarification.message}</p>}<label>추가 답변<textarea required maxLength={500} value={answer} onChange={e=>setAnswer(e.target.value)} placeholder="한 문장으로 편하게 답해 주세요." rows={3}/></label><button className="button primary" disabled={busy}>답변 보내기</button><p className="field-hint">충분한 정보가 모이면 별도 확인 화면 없이 접수됩니다.</p></form>:<p>{result?.message}</p>}
   <button className="button secondary" disabled={busy||!result} onClick={()=>void send(true)}>{result?.status==='NEEDS_INPUT'?'더 설명하기 어려워요 · 관리자 검토로 접수':'관리자 검토로 접수'}</button>
   <button className="text-button" disabled={busy} onClick={onEdit}>내용 직접 수정</button>
   <p className="field-hint">SKOPE 실험용 관리자에게 접수되며 학교에 실제 전송되지는 않습니다.</p>
  </section><Link to="/" className="text-button">처음으로</Link></>;
}
