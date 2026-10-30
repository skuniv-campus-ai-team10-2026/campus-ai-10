import {afterEach,expect,it,vi} from 'vitest';
import {cleanup,render,screen} from '@testing-library/react';
import {MemoryRouter} from 'react-router-dom';
import App from './App';
import {api,ApiError} from './api';

vi.mock('./api',async original=>({...await original<typeof import('./api')>(),api:vi.fn()}));
afterEach(()=>{cleanup();vi.clearAllMocks();sessionStorage.clear();});
const user={id:123,username:'route_test',displayName:'경로 확인',role:'STUDENT'};
function mount(path:string){return render(<MemoryRouter initialEntries={[path]}><App/></MemoryRouter>);}
function session(authenticated:boolean){vi.mocked(api).mockImplementation(async path=>{
 if(path==='/auth/me'){if(!authenticated)throw new ApiError(401,'UNAUTHENTICATED','로그인 필요');return user;}
 return {aiMode:'DEMO',databaseMode:'H2_DEMO',embeddingMode:'RULE_ONLY'};
});}

it('restores signup on direct entry and app remount without a session',async()=>{
 session(false);
 for(let attempt=0;attempt<2;attempt++){
  mount('/signup');
  expect(await screen.findByLabelText('이름')).toBeInTheDocument();
  expect(screen.getByRole('button',{name:'회원가입'})).toBeInTheDocument();
  cleanup();
 }
});
it('restores the authenticated profile after a fresh app mount',async()=>{
 session(true);
 for(let attempt=0;attempt<2;attempt++){
  mount('/profile');
  expect(await screen.findByRole('heading',{name:'회원정보'})).toBeInTheDocument();
  expect(screen.getByLabelText('이름')).toHaveValue(user.displayName);
  expect(screen.getByText(user.username)).toBeInTheDocument();
  cleanup();
 }
});
it('shows login for direct profile access without a session',async()=>{
 session(false);mount('/profile');
 expect(await screen.findByRole('button',{name:'로그인'})).toBeInTheDocument();
 expect(screen.queryByRole('heading',{name:'회원정보'})).not.toBeInTheDocument();
});
