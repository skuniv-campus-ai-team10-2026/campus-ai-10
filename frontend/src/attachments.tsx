import {useEffect,useRef,useState,useSyncExternalStore} from 'react';
import {Camera,Trash2} from 'lucide-react';
import {api,ApiError} from './api';
import {ErrorBox} from './ui';

type Photo={id:number;contentPath:string};
let sessionVersion=0;
type UploadState={files:File[];photos:Photo[];busy:boolean;loaded:boolean;attempted:boolean;uncertain:boolean;error:unknown;revision:number;listeners:Set<()=>void>};
const drafts=new Map<string,File[]>(),reports=new Map<string,UploadState>();
function reportState(key:string){let state=reports.get(key);if(!state){state={files:[],photos:[],busy:false,loaded:false,attempted:false,uncertain:false,error:null,revision:0,listeners:new Set()};reports.set(key,state);}return state;}
function notify(state:UploadState){state.revision++;state.listeners.forEach(listener=>listener());}
export function clearPendingPhotos(){sessionVersion++;drafts.clear();reports.clear();}
export function stagePhotos(key:string,files:File[]){drafts.set(key,[...files]);}
export function bindPhotos(key:string,id:number){const files=drafts.get(key),state=reportState(String(id));if(files?.length&&!state.attempted&&!state.files.length){state.files=[...files];notify(state);}drafts.delete(key);}
const uncertainMessage='사진 저장 여부를 확인할 수 없습니다. 중복 방지를 위해 다시 전송하지 않습니다. 첨부 목록을 다시 확인해 주세요. 신고는 이미 접수되어 있습니다.';
// The list API has no file identity/token. Even an empty list cannot prove that a timed-out POST will not commit later.
function ambiguous(error:unknown){return !(error instanceof ApiError)||error.status===0||error.status===408||error.status>=500;}
async function loadPhotos(base:string,state:UploadState,version=sessionVersion){const data=await api<{items:Photo[]}>(base);if(version!==sessionVersion)return;state.photos=data?.items??[];state.loaded=true;notify(state);}
async function uploadPhotos(base:string,state:UploadState){
 if(state.busy||state.uncertain||!state.files.length)return;
 state.busy=true;state.attempted=true;state.error=null;notify(state);
 const version=sessionVersion;
 try{
  while(state.files.length){
   if(version!==sessionVersion)return;
   const form=new FormData();form.append('file',state.files[0]);
   try{await api(base,{method:'POST',body:form});}
   catch(error){if(version!==sessionVersion)return;state.uncertain=ambiguous(error);state.error=state.uncertain?uncertainMessage:error;break;}
   if(version!==sessionVersion)return;
   state.files=state.files.slice(1);notify(state);
  }
 }finally{
  if(version===sessionVersion){
   try{await loadPhotos(base,state,version);}catch(error){if(version===sessionVersion&&!state.uncertain)state.error=error;}
   if(version===sessionVersion){state.busy=false;notify(state);}
  }
 }
}
export function validatePhotos(files:File[],count:number){
 if(count+files.length>3)return '사진은 신고당 최대 3장까지 첨부할 수 있습니다.';
 if(files.some(file=>!['image/jpeg','image/png'].includes(file.type)||! /\.(jpe?g|png)$/i.test(file.name)))return 'JPG, JPEG, PNG 사진만 첨부할 수 있습니다.';
 if(files.some(file=>file.size>5*1024*1024))return '사진은 한 장에 5MB 이하만 첨부할 수 있습니다.';
 if(files.some(file=>file.size===0))return '비어 있는 파일은 첨부할 수 없습니다.';
 return null;
}
function Preview({file}:{file:File}){const [url,setUrl]=useState('');useEffect(()=>{const next=URL.createObjectURL(file);setUrl(next);return()=>URL.revokeObjectURL(next);},[file]);return url?<img src={url} alt={file.name}/>:null;}
export function ReportPhotos({reportId,admin=false,autoUpload=false}:{reportId:number;admin?:boolean;autoUpload?:boolean}){
 const state=reportState(`${admin?'admin:':''}${reportId}`),base=`${admin?'/admin':''}/reports/${reportId}/attachments`;
 useSyncExternalStore(listener=>{state.listeners.add(listener);return()=>{state.listeners.delete(listener);};},()=>state.revision);
 const {photos,files,error,busy,loaded,uncertain}=state;
 useEffect(()=>{
  let active=true;const version=sessionVersion;
  void loadPhotos(base,state,version).then(()=>{if(active&&version===sessionVersion&&autoUpload&&!admin&&!state.attempted)void uploadPhotos(base,state);}).catch(error=>{if(active&&version===sessionVersion){state.error=state.uncertain?uncertainMessage:error;notify(state);}});
  return()=>{active=false;};
 },[state,base,autoUpload,admin]);
 if(loaded&&admin&&!photos.length)return null;
 async function refresh(){
  if(state.busy)return;state.busy=true;notify(state);const version=sessionVersion;
  try{await loadPhotos(base,state,version);if(version===sessionVersion&&!state.uncertain)state.error=null;}
  catch(error){if(version===sessionVersion)state.error=state.uncertain?uncertainMessage:error;}
  finally{if(version===sessionVersion){state.busy=false;notify(state);}}
 }
 async function remove(id:number){
  if(state.busy||state.uncertain)return;state.busy=true;notify(state);const version=sessionVersion;
  try{await api(`${base}/${id}`,{method:'DELETE'});await loadPhotos(base,state,version);}
  catch(error){if(version===sessionVersion)state.error=error;}
  finally{if(version===sessionVersion){state.busy=false;notify(state);}}
 }
 return <section className="report-photos"><h3>첨부 사진</h3><ErrorBox error={error}/>
  {uncertain&&<button className="button secondary" type="button" disabled={busy} onClick={()=>void refresh()}>첨부 목록 다시 확인</button>}
  {busy&&<p role="status">사진을 업로드하고 있어요. 잠시 기다려 주세요.</p>}
  <div className="photo-grid">{photos.map(photo=><figure key={photo.id}><a href={photo.contentPath} target="_blank" rel="noreferrer"><img src={photo.contentPath} alt={`첨부 사진 ${photo.id}`}/></a>{!admin&&<button type="button" disabled={busy||uncertain} aria-label={`사진 ${photo.id} 삭제`} onClick={()=>void remove(photo.id)}><Trash2 size={16}/></button>}</figure>)}</div>
  {!admin&&loaded&&photos.length<3&&<><PhotoPicker files={files} onChange={next=>{if(state.busy||state.uncertain)return;if(state.photos.length+next.length>3){state.error='사진은 신고당 최대 3장까지 첨부할 수 있습니다.';}else{state.files=next;}notify(state);}} disabled={busy||uncertain}/>{files.length>0&&<button className="button secondary" type="button" disabled={busy||uncertain} onClick={()=>void uploadPhotos(base,state)}>선택한 사진 업로드</button>}</>}
  {!photos.length&&loaded&&!busy&&<p className="field-hint">등록된 사진이 없습니다.</p>}
 </section>;
}
export function PhotoPicker({files,onChange,disabled=false}:{files:File[];onChange:(files:File[])=>void;disabled?:boolean}){
  const input=useRef<HTMLInputElement>(null),[error,setError]=useState<string|null>(null),[dragging,setDragging]=useState(false);
 function addPhotos(selected:File[]){if(disabled||!selected.length)return;const message=validatePhotos(selected,files.length);setError(message);if(!message)onChange([...files,...selected]);}
 return <section className={dragging?"photo-picker is-dragging":"photo-picker"} aria-label="사진 첨부" onDragOver={e=>{if(!Array.from(e.dataTransfer.types).includes('Files'))return;e.preventDefault();e.dataTransfer.dropEffect=disabled?'none':'copy';if(!disabled)setDragging(true);}} onDragLeave={e=>{if(!e.currentTarget.contains(e.relatedTarget as Node|null))setDragging(false);}} onDrop={e=>{e.preventDefault();setDragging(false);addPhotos(Array.from(e.dataTransfer.files));}}><div className="photo-heading"><strong>사진 첨부 <span>(선택)</span></strong><small>{files.length}/3</small></div><p>현장 사진을 함께 남겨 주세요.</p><button className="photo-dropzone" type="button" disabled={disabled||files.length>=3} onClick={()=>input.current?.click()}><Camera size={28}/><strong>{dragging?'여기에 사진을 놓아주세요':'사진 추가하기'}</strong><span className="photo-drag-hint">사진을 끌어다 놓아도 첨부할 수 있어요.</span><span>JPEG · PNG / 장당 5MB 이하 / 최대 3장</span></button><input ref={input} className="sr-only" type="file" accept="image/jpeg,image/png,.jpg,.jpeg,.png" multiple aria-label="첨부 사진 선택" disabled={disabled} onChange={e=>{addPhotos(Array.from(e.target.files??[]));e.target.value='';}}/>{error&&<p className="photo-error" role="alert">{error}</p>}<div className="photo-grid">{files.map((file,index)=><figure key={`${index}-${file.name}`}><Preview file={file}/><figcaption>{file.name}</figcaption><button type="button" aria-label={`${file.name} 삭제`} disabled={disabled} onClick={()=>{onChange(files.filter((_,i)=>i!==index));setError(null);}}><Trash2 size={16}/></button></figure>)}</div><small>사진은 접수 후 업로드됩니다. 새로고침하면 선택한 사진은 다시 첨부해 주세요.</small></section>;
}
