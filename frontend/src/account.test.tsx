import {afterEach,describe,it,expect,vi} from 'vitest';
import {cleanup,render,screen,fireEvent,waitFor} from '@testing-library/react';
import {MemoryRouter} from 'react-router-dom';
import {accountValidation,ProfilePage,SignupPage} from './account';
import {SessionContext} from './context';
import {ApiError,post,patch} from './api';
vi.mock('./api',async()=>{const actual=await vi.importActual('./api');return {...actual,post:vi.fn(),patch:vi.fn()};});
afterEach(()=>{cleanup();vi.clearAllMocks();});
describe('회원 입력 조건',()=>{
 it('아이디와 공백 이름을 검증한다',()=>{expect(accountValidation({username:'Abc',displayName:'   '})).toHaveProperty('username');expect(accountValidation({displayName:'   '})).toHaveProperty('displayName');});
 it('비밀번호 문자 수와 UTF-8 바이트 수를 각각 검증한다',()=>{expect(accountValidation({password:'가'.repeat(25)})).toHaveProperty('password');expect(accountValidation({password:'a'.repeat(65)})).toHaveProperty('password');expect(accountValidation({password:' '+ 'a'.repeat(10)+' ',confirmation:' '+ 'a'.repeat(10)+' '})).toEqual({});});
});
it('가입 요청에서 이름만 trim하고 확인 비밀번호는 제외한다',async()=>{
 vi.mocked(post).mockResolvedValue({});render(<MemoryRouter><SignupPage/></MemoryRouter>);
 fireEvent.change(screen.getByLabelText('아이디'),{target:{value:'student123'}});fireEvent.change(screen.getByLabelText('이름'),{target:{value:' 홍길동 '}});
 for(const label of ['비밀번호','비밀번호 확인'])fireEvent.change(screen.getByLabelText(label),{target:{value:' 1234567890 '}});
 fireEvent.click(screen.getByRole('button',{name:'회원가입'}));await waitFor(()=>expect(post).toHaveBeenCalledWith('/auth/register',{username:'student123',displayName:'홍길동',password:' 1234567890 '}));
});
it('이름 응답을 공통 상태에 반영하고 현재 비밀번호 오류에서는 세션을 유지한다',async()=>{
 const user={id:1,username:'student123',displayName:'홍길동',role:'STUDENT' as const},updateUser=vi.fn(),reauthenticate=vi.fn();
 render(<MemoryRouter><SessionContext.Provider value={{user,meta:null,logout:async()=>{},updateUser,reauthenticate}}><ProfilePage/></SessionContext.Provider></MemoryRouter>);
 vi.mocked(patch).mockResolvedValue({...user,displayName:'새 이름'});fireEvent.change(screen.getByLabelText('이름'),{target:{value:'새 이름'}});fireEvent.click(screen.getByRole('button',{name:'이름 변경'}));await waitFor(()=>expect(updateUser).toHaveBeenCalledWith({...user,displayName:'새 이름'}));
 vi.mocked(post).mockRejectedValue(new ApiError(400,'INVALID_CURRENT_PASSWORD','현재 비밀번호 불일치',{currentPassword:'다시 확인해 주세요.'}));
 fireEvent.change(screen.getByLabelText('현재 비밀번호'),{target:{value:'wrong'}});for(const label of ['새 비밀번호','비밀번호 확인'])fireEvent.change(screen.getByLabelText(label),{target:{value:'123456789012'}});fireEvent.click(screen.getByRole('button',{name:'비밀번호 변경'}));expect(await screen.findByText('다시 확인해 주세요.')).toBeInTheDocument();expect(reauthenticate).not.toHaveBeenCalled();
 vi.mocked(post).mockResolvedValue({success:true,reauthenticationRequired:true});fireEvent.click(screen.getByRole('button',{name:'비밀번호 변경'}));await waitFor(()=>expect(reauthenticate).toHaveBeenCalledOnce());
});
