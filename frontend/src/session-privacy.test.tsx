import {afterEach,beforeEach,expect,it,vi} from 'vitest';
import {cleanup,fireEvent,render,screen,waitFor} from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import {MemoryRouter} from 'react-router-dom';
import App from './App';
import {api,ApiError,post,clearCsrf} from './api';

vi.mock('./api',async(importOriginal)=>({...await importOriginal<typeof import('./api')>(),api:vi.fn(),post:vi.fn(),clearCsrf:vi.fn()}));
const meta={aiMode:'DEMO',embeddingMode:'RULE_ONLY',databaseMode:'MYSQL'};
function previousDraft(){sessionStorage.setItem('skope-draft',JSON.stringify({originalText:'이전 학생의 비공개 초안'}));sessionStorage.setItem('skope-last-report','77');sessionStorage.setItem('unrelated','keep');}
function expectCleared(){expect(sessionStorage.getItem('skope-draft')).toBeNull();expect(sessionStorage.getItem('skope-last-report')).toBeNull();expect(sessionStorage.getItem('unrelated')).toBe('keep');}
beforeEach(()=>{vi.clearAllMocks();sessionStorage.clear();previousDraft();});
afterEach(cleanup);

it('removes private drafts when initial session validation finds a logged-out browser',async()=>{
 vi.mocked(api).mockImplementation(async path=>{if(path==='/auth/me')throw new ApiError(401,'UNAUTHENTICATED','로그인 필요');return meta;});
 render(<MemoryRouter><App/></MemoryRouter>);
 await screen.findByRole('button',{name:'로그인'});expectCleared();
});

it('clears drafts on expiry and before a different student starts a new session',async()=>{
 vi.mocked(api).mockImplementation(async path=>path==='/auth/me'?{id:1,role:'STUDENT',displayName:'이전 학생'}:meta);
 vi.mocked(post).mockResolvedValue({id:2,role:'STUDENT',displayName:'새 학생'});
 render(<MemoryRouter><App/></MemoryRouter>);
 await screen.findByText('이전 학생');
 expect(sessionStorage.getItem('skope-draft')).not.toBeNull();
 fireEvent(window,new Event('session-expired'));
 await screen.findByText('세션이 만료되었습니다. 다시 로그인해 주세요.');expectCleared();
 // A stale browser draft must also be discarded at the authentication boundary.
 previousDraft();const user=userEvent.setup();
 await user.type(screen.getByLabelText('아이디'),'student2');await user.type(screen.getByLabelText('비밀번호'),'synthetic-password');
 await user.click(screen.getByRole('button',{name:'로그인'}));
 await screen.findByText('새 학생');expectCleared();
 expect(screen.queryByDisplayValue('이전 학생의 비공개 초안')).not.toBeInTheDocument();
});

it('clears the session and private drafts after a password change without logging out again',async()=>{
 vi.mocked(api).mockImplementation(async path=>path==='/auth/me'?{id:1,username:'student123',role:'STUDENT',displayName:'학생'}:meta);
 vi.mocked(post).mockResolvedValue({success:true,reauthenticationRequired:true});
 render(<MemoryRouter initialEntries={['/profile']}><App/></MemoryRouter>);
 await screen.findByLabelText('현재 비밀번호');
 fireEvent.change(screen.getByLabelText('현재 비밀번호'),{target:{value:'old-password'}});
 for(const label of ['새 비밀번호','비밀번호 확인'])fireEvent.change(screen.getByLabelText(label),{target:{value:'new-password123'}});
 fireEvent.click(screen.getByRole('button',{name:'비밀번호 변경'}));
 await screen.findByText('비밀번호가 변경됐어요. 다시 로그인해 주세요.');
 expectCleared();expect(clearCsrf).toHaveBeenCalled();
 await waitFor(()=>expect(screen.getByRole('button',{name:'로그인'})).toBeInTheDocument());
 expect(post).toHaveBeenCalledTimes(1);expect(post).toHaveBeenCalledWith('/auth/password',{currentPassword:'old-password',newPassword:'new-password123'});
});