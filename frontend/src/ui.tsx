import { useEffect, useState } from 'react';
import type { ReactNode } from 'react';
import { AlertCircle, ArrowUpRight, Check, CircleHelp, LoaderCircle, ShieldCheck, TriangleAlert } from 'lucide-react';
import { ApiError } from './api';
export const typeLabels:Record<string,string>={WIFI:'무선 인터넷',LEAK:'누수',ELECTRIC:'전기',STUDENT_RECORD:'학적·행정',UNKNOWN:'기타 / 직접 확인'};
export const statusLabels:Record<string,string>={RECEIVED:'접수 완료',IN_PROGRESS:'처리 중',RESOLVED:'처리 완료',OPEN:'진행 중',MATCHED:'검증 규칙 배정',NEEDS_REVIEW:'관리자 확인 필요',MANUAL:'관리자 배정',CANDIDATE:'검토 대기',SAME_INCIDENT:'동일 사건',RELATED_DISTINCT:'관련된 별도 사건',REJECTED:'반려',CONFIRMED:'재발 확인',NOT_EVALUATED:'미검토'};
export function Badge({value,children}:{value?:string;children?:ReactNode}){return <span className={`badge badge-${value?.toLowerCase()??'neutral'}`}>{children??statusLabels[value??'']??value}</span>;}
export function ModeBadge({mode}:{mode?:string}){return <Badge value={mode==='LIVE'?'matched':'demo'}>{mode==='LIVE'?'LIVE · 실제 AI':mode==='RULE_ONLY'?'RULE ONLY · 규칙 후보':mode?`${mode} · 시연 모드`:'모드 확인 중'}</Badge>;}
export function ErrorBox({error}:{error:unknown}){
  if(!error)return null;
  const err=error instanceof Error?error:new Error(String(error));
  return <div className="notice danger" role="alert"><AlertCircle size={19}/><div><strong>{error instanceof ApiError&&error.status===401?'로그인이 만료되었습니다':err.message}</strong>{error instanceof ApiError&&Object.keys(error.fieldErrors).length>0&&<ul>{Object.entries(error.fieldErrors).map(([key,value])=><li key={key}>{key}: {value}</li>)}</ul>}{error instanceof ApiError&&<small>{error.code}</small>}</div></div>;
}
export function Loading({text='불러오는 중입니다'}:{text?:string}){return <div className="loading" role="status"><LoaderCircle className="spin" size={22}/>{text}</div>;}
export function Empty({title,children}:{title:string;children?:ReactNode}){return <div className="empty"><CircleHelp size={30}/><h3>{title}</h3><p>{children}</p></div>;}
export function Emergency(){return <div className="notice emergency" role="alert"><TriangleAlert size={22}/><div><strong>위험이 느껴진다면 안전을 먼저 확보해 주세요.</strong><p>해당 장소에서 벗어나고 즉각적인 위험은 119 등 긴급 연락처로 직접 신고해 주세요. SKOPE는 긴급 신고 전송이나 출동 기능을 제공하지 않습니다.</p></div></div>;}
export function Source({sourceUrl,verified}:{sourceUrl?:string|null;verified?:boolean}){
  const safe=sourceUrl&&/^https?:\/\//i.test(sourceUrl)?sourceUrl:null;
  return <div className="source"><ShieldCheck size={15}/><span>{verified?'공식 출처 확인 자료':'현재 공식 출처 미검증'}</span>{safe&&<a href={safe} target="_blank" rel="noreferrer">출처 보기 <ArrowUpRight size={13}/></a>}</div>;
}
export function DateText({value}:{value?:string|null}){if(!value)return <>—</>;const date=new Date(value);return <>{isNaN(date.getTime())?value:date.toLocaleString('ko-KR',{timeZone:'Asia/Seoul',month:'numeric',day:'numeric',hour:'2-digit',minute:'2-digit'})}</>;}
export function Steps({current}:{current:number}){return <div className="steps">{['내용 입력','필요한 정보 보완','접수 완료'].map((label,index)=><div key={label} className={index<=current?'active':''}><span>{index<current?<Check size={13}/>:index+1}</span>{label}</div>)}</div>;}
export function PageHeading({eyebrow,title,description,action}:{eyebrow:string;title:string;description?:string;action?:ReactNode}){return <div className="page-heading"><div><span className="eyebrow">{eyebrow}</span><h1>{title}</h1>{description&&<p>{description}</p>}</div>{action}</div>;}
export function useLoad<T>(loader:()=>Promise<T>,deps:unknown[]=[]){
  const [value,setValue]=useState<T|null>(null),[error,setError]=useState<unknown>(null),[loading,setLoading]=useState(true),[revision,setRevision]=useState(0);
  useEffect(()=>{let active=true;setLoading(true);setError(null);loader().then(data=>{if(active)setValue(data);}).catch(e=>{if(active)setError(e);}).finally(()=>{if(active)setLoading(false);});return()=>{active=false;};},[...deps,revision]);
  return {value,error,loading,reload:()=>setRevision(x=>x+1)};
}
