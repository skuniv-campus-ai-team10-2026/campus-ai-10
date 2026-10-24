import {useEffect,useRef,useState} from 'react';
import {Camera,Trash2} from 'lucide-react';
import {api} from './api';
import {ErrorBox} from './ui';

type Photo={id:number;contentPath:string};
let sessionVersion=0;
const drafts=new Map<string,File[]>(),pending=new Map<number,File[]>();
export function clearPendingPhotos(){sessionVersion++;drafts.clear();pending.clear();}
export function stagePhotos(key:string,files:File[]){drafts.set(key,[...files]);}
export function bindPhotos(key:string,id:number){const files=drafts.get(key);if(files?.length)pending.set(id,files);drafts.delete(key);}
export function validatePhotos(files:File[],count:number){
 if(count+files.length>3)return '사진은 신고당 최대 3장까지 첨부할 수 있습니다.';
 if(files.some(file=>!['image/jpeg','image/png'].includes(file.type)||! /\.(jpe?g|png)$/i.test(file.name)))return 'JPG, JPEG, PNG 사진만 첨부할 수 있습니다.';
 if(files.some(file=>file.size>5*1024*1024))return '사진은 한 장에 5MB 이하만 첨부할 수 있습니다.';
 if(files.some(file=>file.size===0))return '비어 있는 파일은 첨부할 수 없습니다.';
 return null;
}
function Preview({file}:{file:File}){const [url,setUrl]=useState('');useEffect(()=>{const next=URL.createObjectURL(file);setUrl(next);return()=>URL.revokeObjectURL(next);},[file]);return url?<img src={url} alt={file.name}/>:null;}
export function PhotoPicker({files,onChange,disabled=false}:{files:File[];onChange:(files:File[])=>void;disabled?:boolean}){
  const input=useRef<HTMLInputElement>(null),[error,setError]=useState<string|null>(null),[dragging,setDragging]=useState(false);
 function addPhotos(selected:File[]){if(disabled||!selected.length)return;const message=validatePhotos(selected,files.length);setError(message);if(!message)onChange([...files,...selected]);}
 return <section className={dragging?"photo-picker is-dragging":"photo-picker"} aria-label="사진 첨부" onDragOver={e=>{if(!Array.from(e.dataTransfer.types).includes('Files'))return;e.preventDefault();e.dataTransfer.dropEffect=disabled?'none':'copy';if(!disabled)setDragging(true);}} onDragLeave={e=>{if(!e.currentTarget.contains(e.relatedTarget as Node|null))setDragging(false);}} onDrop={e=>{e.preventDefault();setDragging(false);addPhotos(Array.from(e.dataTransfer.files));}}><div className="photo-heading"><strong>사진 첨부 <span>(선택)</span></strong><small>{files.length}/3</small></div><p>현장 사진을 함께 남겨 주세요.</p><button className="photo-dropzone" type="button" disabled={disabled||files.length>=3} onClick={()=>input.current?.click()}><Camera size={28}/><strong>{dragging?'여기에 사진을 놓아주세요':'사진 추가하기'}</strong><span className="photo-drag-hint">사진을 끌어다 놓아도 첨부할 수 있어요.</span><span>JPEG · PNG / 장당 5MB 이하 / 최대 3장</span></button><input ref={input} className="sr-only" type="file" accept="image/jpeg,image/png,.jpg,.jpeg,.png" multiple aria-label="첨부 사진 선택" disabled={disabled} onChange={e=>{addPhotos(Array.from(e.target.files??[]));e.target.value='';}}/>{error&&<p className="photo-error" role="alert">{error}</p>}<div className="photo-grid">{files.map((file,index)=><figure key={`${index}-${file.name}`}><Preview file={file}/><figcaption>{file.name}</figcaption><button type="button" aria-label={`${file.name} 삭제`} disabled={disabled} onClick={()=>{onChange(files.filter((_,i)=>i!==index));setError(null);}}><Trash2 size={16}/></button></figure>)}</div><small>사진은 접수 후 업로드됩니다. 새로고침하면 선택한 사진은 다시 첨부해 주세요.</small></section>;
}
export function ReportPhotos({reportId,admin=false,autoUpload=false}:{reportId:number;admin?:boolean;autoUpload?:boolean}){
 const [photos,setPhotos]=useState<Photo[]>([]),[files,setFiles]=useState<File[]>([]),[error,setError]=useState<unknown>(null),[busy,setBusy]=useState(false),[loaded,setLoaded]=useState(false);
 const lock=useRef(false),base=`${admin?'/admin':''}/reports/${reportId}/attachments`;
 async function load(){const data=await api<{items:Photo[]}>(base);const items=data?.items??[];setPhotos(items);setLoaded(true);return items;}
 async function upload(selected:File[]){if(lock.current||!selected.length)return;lock.current=true;setBusy(true);setError(null);const rest=[...selected],version=sessionVersion;try{for(const file of selected){if(version!==sessionVersion)return;const form=new FormData();form.append('file',file);await api(base,{method:'POST',body:form});rest.shift();if(autoUpload)pending.set(reportId,[...rest]);setFiles([...rest]);}pending.delete(reportId);}catch(e){setError(e);}finally{try{await load();}catch(e){setError(e);}lock.current=false;setBusy(false);}}
 useEffect(()=>{let active=true;void load().then(()=>{if(active&&autoUpload){const selected=pending.get(reportId)??[];setFiles(selected);void upload(selected);}}).catch(e=>{if(active)setError(e);});return()=>{active=false;};},[reportId,admin]);
 if(loaded&&admin&&!photos.length)return null;
 return <section className="report-photos"><h3>첨부 사진</h3><ErrorBox error={error}/>{busy&&<p role="status">사진을 업로드하고 있어요. 잠시 기다려 주세요.</p>}<div className="photo-grid">{photos.map(photo=><figure key={photo.id}><a href={photo.contentPath} target="_blank" rel="noreferrer"><img src={photo.contentPath} alt={`첨부 사진 ${photo.id}`}/></a>{!admin&&<button type="button" disabled={busy} aria-label={`사진 ${photo.id} 삭제`} onClick={async()=>{if(lock.current)return;lock.current=true;setBusy(true);try{await api(`${base}/${photo.id}`,{method:'DELETE'});await load();}catch(e){setError(e);}finally{lock.current=false;setBusy(false);}}}><Trash2 size={16}/></button>}</figure>)}</div>{!admin&&loaded&&photos.length<3&&<><PhotoPicker files={files} onChange={next=>{if(photos.length+next.length>3){setError('사진은 신고당 최대 3장까지 첨부할 수 있습니다.');return;}setFiles(next);if(autoUpload)pending.set(reportId,next);}} disabled={busy}/>{files.length>0&&<button className="button secondary" type="button" disabled={busy} onClick={()=>void upload(files)}>선택한 사진 업로드</button>}</>}{!photos.length&&loaded&&!busy&&<p className="field-hint">등록된 사진이 없습니다.</p>}</section>;
}
