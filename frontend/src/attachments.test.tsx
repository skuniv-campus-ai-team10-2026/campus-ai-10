import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {StrictMode} from 'react';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import {api} from './api';
import {bindPhotos,clearPendingPhotos,PhotoPicker,ReportPhotos,stagePhotos,validatePhotos} from './attachments';
vi.mock('./api',async(importOriginal)=>({...await importOriginal<typeof import('./api')>(),api:vi.fn()}));
beforeEach(()=>{vi.clearAllMocks();clearPendingPhotos();URL.createObjectURL=vi.fn(()=> 'blob:preview');URL.revokeObjectURL=vi.fn();});
afterEach(cleanup);
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
 let uploads=0;vi.mocked(api).mockImplementation(async(_path,options)=>{if(options?.method==='POST'){uploads++;if(uploads===2)throw new Error('upload failed');return {};}return {items:[]};});
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