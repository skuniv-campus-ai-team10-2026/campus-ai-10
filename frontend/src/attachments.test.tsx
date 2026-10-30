import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {StrictMode} from 'react';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {api,ApiError} from './api';
import {AutoUploadPhotos,bindPhotos,clearPendingPhotos,PhotoPicker,ReportPhotos,stagePhotos,validatePhotos} from './attachments';
vi.mock('./api',async(importOriginal)=>({...await importOriginal<typeof import('./api')>(),api:vi.fn()}));
beforeEach(()=>{vi.clearAllMocks();clearPendingPhotos();URL.createObjectURL=vi.fn(()=> 'blob:preview');URL.revokeObjectURL=vi.fn();});
afterEach(cleanup);
it('keeps viewing and deleting photos while blocking additions on a closed report',async()=>{
 stagePhotos('closed',[new File(['x'],'pending.png',{type:'image/png'})]);bindPhotos('closed',42);
 vi.mocked(api).mockImplementation(async(_path,options)=>options?.method==='DELETE'?{}:{items:[{id:7,contentPath:'/photo.png'}]});
 render(<ReportPhotos reportId={42} canUpload={false} autoUpload/>);
 const photo=await screen.findByAltText('첨부 사진 7');
 expect(photo.closest('a')).toHaveAttribute('href','/photo.png');
 expect(screen.queryByRole('button',{name:'사진 추가하기'})).not.toBeInTheDocument();
 expect(screen.queryByLabelText('첨부 사진 선택')).not.toBeInTheDocument();
 expect(screen.queryByRole('button',{name:'선택한 사진 업로드'})).not.toBeInTheDocument();
 fireEvent.click(screen.getByRole('button',{name:'사진 7 삭제'}));
 await waitFor(()=>expect(api).toHaveBeenCalledWith('/reports/42/attachments/7',{method:'DELETE'}));
 expect(vi.mocked(api).mock.calls.some(([,options])=>options?.method==='POST')).toBe(false);
});
it('rejects unsupported images, over-size files and excess count',()=>{
 expect(validatePhotos([new File(['x'],'a.heic',{type:'image/heic'})],0)).toContain('PNG');
 expect(validatePhotos([new File([new Uint8Array(5*1024*1024+1)],'a.png',{type:'image/png'})],0)).toContain('5MB');
 expect(validatePhotos([new File(['x'],'a.jpg',{type:'image/jpeg'})],3)).toContain('3장');
 expect(validatePhotos([new File(['x'],'a.jpeg',{type:'image/jpeg'})],0)).toBeNull();
});
it('explains an invalid selection without adding it',()=>{
 const change=vi.fn();render(<PhotoPicker files={[]} onChange={change}/>);
 fireEvent.change(screen.getByLabelText('첨부 사진 선택'),{target:{files:[new File(['x'],'a.gif',{type:'image/gif'})]}});
 expect(screen.getByRole('alert')).toHaveTextContent('PNG');expect(change).not.toHaveBeenCalled();
});
it('uploads staged photos once in StrictMode and retains only failed files for retry',async()=>{
 const first=new File(['one'],'one.jpg',{type:'image/jpeg'}),second=new File(['two'],'two.png',{type:'image/png'});stagePhotos('draft', [first,second]);bindPhotos('draft',42);
 let uploads=0;vi.mocked(api).mockImplementation(async(_path,options)=>{if(options?.method==='POST'){uploads++;if(uploads===2)throw new ApiError(400,'ATTACHMENT_INVALID_IMAGE','upload failed');return {};}return {items:[]};});
 render(<StrictMode><ReportPhotos reportId={42} autoUpload/></StrictMode>);
 await screen.findByText('upload failed');expect(uploads).toBe(2);
 fireEvent.click(screen.getByRole('button',{name:'선택한 사진 업로드'}));
 await waitFor(()=>expect(uploads).toBe(3));
 const calls=vi.mocked(api).mock.calls.filter(([,options])=>options?.method==='POST');
 expect((calls[2][1]?.body as FormData).get('file')).toBe(second);
});

it('adds dropped photos and rejects invalid dropped files',()=>{
 const change=vi.fn();render(<PhotoPicker files={[]} onChange={change}/>);
 const area=screen.getByRole('region',{name:'사진 첨부'}),photo=new File(['x'],'drop.png',{type:'image/png'});
 fireEvent.dragOver(area,{dataTransfer:{types:['Files']}});
 expect(screen.getByText('여기에 사진을 놓아주세요')).toBeInTheDocument();
 fireEvent.drop(area,{dataTransfer:{files:[photo]}});
 expect(change).toHaveBeenCalledWith([photo]);
 change.mockClear();fireEvent.drop(area,{dataTransfer:{files:[new File(['x'],'bad.gif',{type:'image/gif'})]}});
 expect(screen.getByRole('alert')).toHaveTextContent('PNG');expect(change).not.toHaveBeenCalled();
});
it('ignores dropped photos while disabled',()=>{
 const change=vi.fn();render(<PhotoPicker files={[]} onChange={change} disabled/>);
 fireEvent.drop(screen.getByRole('region',{name:'사진 첨부'}),{dataTransfer:{files:[new File(['x'],'drop.png',{type:'image/png'})]}});
 expect(change).not.toHaveBeenCalled();
});

it.each([new ApiError(0,'NETWORK_ERROR','lost response'),new ApiError(500,'REQUEST_FAILED','server error'),new Error('response interrupted')])('refreshes saved attachments after an ambiguous upload and never retries it: %s',async(error)=>{
 const photo=new File(['photo'],'saved.jpg',{type:'image/jpeg'});stagePhotos('draft',[photo]);bindPhotos('draft',42);
 let uploads=0;
 vi.mocked(api).mockImplementation(async(_path,options)=>{if(options?.method==='POST'){uploads++;throw error;}return {items:uploads?[{id:7,contentPath:'/saved.jpg'}]:[]};});
 const view=render(<ReportPhotos reportId={42} autoUpload/>);
 await screen.findByText(/사진 저장 여부를 확인할 수 없습니다/);
 expect(await screen.findByAltText('첨부 사진 7')).toBeInTheDocument();
 await waitFor(()=>expect(screen.getByRole('button',{name:'첨부 목록 다시 확인'})).toBeEnabled());
 expect(screen.getByRole('button',{name:'선택한 사진 업로드'})).toBeDisabled();
 fireEvent.click(screen.getByRole('button',{name:'첨부 목록 다시 확인'}));
 await waitFor(()=>expect(screen.getByRole('button',{name:'첨부 목록 다시 확인'})).toBeEnabled());
 view.unmount();render(<ReportPhotos reportId={42} autoUpload/>);
 await screen.findByAltText('첨부 사진 7');expect(uploads).toBe(1);
 expect(vi.mocked(api).mock.calls.every(([path])=>path==='/reports/42/attachments')).toBe(true);
});

it('keeps ambiguous uploads blocked when listing also fails or later returns empty',async()=>{
 stagePhotos('draft',[new File(['photo'],'a.jpg',{type:'image/jpeg'})]);bindPhotos('draft',42);
 let uploads=0,lists=0;
 vi.mocked(api).mockImplementation(async(_path,options)=>{if(options?.method==='POST'){uploads++;throw new ApiError(0,'NETWORK_ERROR','lost response');}lists++;if(lists===2)throw new Error('list failed');return {items:[]};});
 render(<ReportPhotos reportId={42} autoUpload/>);
 await waitFor(()=>expect(screen.getByRole('button',{name:'첨부 목록 다시 확인'})).toBeEnabled());
 fireEvent.click(screen.getByRole('button',{name:'첨부 목록 다시 확인'}));
 await waitFor(()=>expect(lists).toBe(3));
 expect(screen.getByRole('button',{name:'선택한 사진 업로드'})).toBeDisabled();expect(uploads).toBe(1);
 expect(screen.getByRole('alert')).toHaveTextContent('신고는 이미 접수되어 있습니다');
});

it('shares in-flight uploads across navigation and continues the remaining queue only once',async()=>{
 const first=new File(['one'],'one.jpg',{type:'image/jpeg'}),second=new File(['two'],'two.jpg',{type:'image/jpeg'});
 stagePhotos('draft',[first,second]);bindPhotos('draft',42);
 let finish!:(value:unknown)=>void;
 const inFlight=new Promise(resolve=>{finish=resolve;});let uploads=0;
 vi.mocked(api).mockImplementation(async(_path,options)=>{if(options?.method==='POST'){uploads++;if(uploads===1)return await inFlight;return {};}return {items:[]};});
 const firstView=render(<ReportPhotos reportId={42} autoUpload/>);
 await waitFor(()=>expect(uploads).toBe(1));firstView.unmount();
 render(<StrictMode><ReportPhotos reportId={42} autoUpload/></StrictMode>);
 await screen.findByRole('status');expect(uploads).toBe(1);
 expect(screen.getByRole('button',{name:'선택한 사진 업로드'})).toBeDisabled();
 finish({});await waitFor(()=>expect(uploads).toBe(2));
 await waitFor(()=>expect(screen.queryByRole('status')).not.toBeInTheDocument());
 const sent=vi.mocked(api).mock.calls.filter(([,options])=>options?.method==='POST').map(([,options])=>(options!.body as FormData).get('file'));
 expect(sent).toEqual([first,second]);
 cleanup();render(<ReportPhotos reportId={42} autoUpload/>);
 await screen.findByLabelText('첨부 사진 선택');expect(uploads).toBe(2);
});

it('does not resume a definitely failed upload on remount without an explicit retry',async()=>{
 stagePhotos('draft',[new File(['photo'],'a.jpg',{type:'image/jpeg'})]);bindPhotos('draft',42);let uploads=0;
 vi.mocked(api).mockImplementation(async(_path,options)=>{if(options?.method==='POST'){uploads++;throw new ApiError(400,'ATTACHMENT_INVALID_IMAGE','invalid image');}return {items:[]};});
 const view=render(<ReportPhotos reportId={42} autoUpload/>);await screen.findByText('invalid image');
 await waitFor(()=>expect(screen.getByRole('button',{name:'선택한 사진 업로드'})).toBeEnabled());
 view.unmount();render(<ReportPhotos reportId={42} autoUpload/>);
 expect(await screen.findByText('invalid image')).toBeInTheDocument();expect(uploads).toBe(1);
});

it('does not resend successful files when refreshing the attachment list fails',async()=>{
 stagePhotos('draft',[new File(['photo'],'a.jpg',{type:'image/jpeg'})]);bindPhotos('draft',42);let uploads=0,lists=0;
 vi.mocked(api).mockImplementation(async(_path,options)=>{if(options?.method==='POST'){uploads++;return {};}if(++lists===2)throw new Error('list failed');return {items:[]};});
 const view=render(<ReportPhotos reportId={42} autoUpload/>);
 await screen.findByText('list failed');view.unmount();render(<ReportPhotos reportId={42} autoUpload/>);
 await waitFor(()=>expect(lists).toBe(3));expect(uploads).toBe(1);
 expect(screen.queryByRole('button',{name:'선택한 사진 업로드'})).not.toBeInTheDocument();
});

it('stops the remaining upload queue when the session is cleared',async()=>{
 stagePhotos('draft',[new File(['one'],'one.jpg',{type:'image/jpeg'}),new File(['two'],'two.jpg',{type:'image/jpeg'})]);bindPhotos('draft',42);
 let finish!:(value:unknown)=>void;const inFlight=new Promise(resolve=>{finish=resolve;});let uploads=0;
 vi.mocked(api).mockImplementation(async(_path,options)=>{if(options?.method==='POST'){uploads++;return await inFlight;}return {items:[]};});
 const view=render(<ReportPhotos reportId={42} autoUpload/>);await waitFor(()=>expect(uploads).toBe(1));
 clearPendingPhotos();view.unmount();finish({});render(<ReportPhotos reportId={42} autoUpload/>);
 await screen.findByLabelText('첨부 사진 선택');expect(uploads).toBe(1);
 expect(screen.queryByRole('button',{name:'선택한 사진 업로드'})).not.toBeInTheDocument();
});

it('uploads from the receipt without rendering a gallery and never resends on remount',async()=>{
 stagePhotos('receipt',[new File(['x'],'one.png',{type:'image/png'})]);bindPhotos('receipt',42);
 let uploads=0;vi.mocked(api).mockImplementation(async(_path,options)=>{if(options?.method==='POST'){uploads++;throw new ApiError(500,'FAILED','failed');}return {items:[]};});
 const view=render(<StrictMode><AutoUploadPhotos reportId={42}/></StrictMode>);
 await screen.findByRole('alert');expect(uploads).toBe(1);expect(screen.queryByText('첨부 사진')).not.toBeInTheDocument();expect(screen.queryByLabelText('첨부 사진 선택')).not.toBeInTheDocument();
 view.unmount();render(<AutoUploadPhotos reportId={42}/>);expect(uploads).toBe(1);
});

it('continues receipt uploads after leaving before the initial list resolves',async()=>{
 const photo=new File(['photo'],'receipt.png',{type:'image/png'});
 stagePhotos('receipt',[photo]);bindPhotos('receipt',42);
 let finish!:(value:unknown)=>void;
 const initialList=new Promise(resolve=>{finish=resolve;});let uploads=0,lists=0;
 vi.mocked(api).mockImplementation(async(_path,options)=>{
  if(options?.method==='POST'){uploads++;return {};}
  if(++lists===1)return await initialList;
  return {items:uploads?[{id:7,contentPath:'/receipt.png'}]:[]};
 });
 const receipt=render(<AutoUploadPhotos reportId={42}/>);
 await waitFor(()=>expect(lists).toBe(1));receipt.unmount();
 render(<ReportPhotos reportId={42}/>);
 finish({items:[]});
 expect(await screen.findByAltText('첨부 사진 7')).toBeInTheDocument();
 await waitFor(()=>expect(uploads).toBe(1));
 expect((vi.mocked(api).mock.calls.find(([,options])=>options?.method==='POST')![1]!.body as FormData).get('file')).toBe(photo);
});

it('does not start receipt uploads after session cleanup while the list is pending',async()=>{
 stagePhotos('receipt',[new File(['photo'],'receipt.png',{type:'image/png'})]);bindPhotos('receipt',42);
 let finish!:(value:unknown)=>void;const initialList=new Promise(resolve=>{finish=resolve;});
 vi.mocked(api).mockImplementation(async()=>await initialList);
 const receipt=render(<AutoUploadPhotos reportId={42}/>);
 clearPendingPhotos();receipt.unmount();finish({items:[]});
 await initialList;await Promise.resolve();
 expect(vi.mocked(api).mock.calls.some(([,options])=>options?.method==='POST')).toBe(false);
});
