import {beforeEach,expect,it,vi} from 'vitest';

beforeEach(()=>vi.resetModules());

it('validates the mock contract and preserves password spaces across reauthentication',async()=>{
 const {accountMock}=await import('./account-mock');
 const request=(path:string,body:unknown,method='POST')=>accountMock(path,{method,body:JSON.stringify(body)});
 await expect(request('/auth/register',{username:'Ab',displayName:' ',password:'short'})).rejects.toMatchObject({status:400,code:'VALIDATION_ERROR',fieldErrors:{username:expect.any(String),displayName:expect.any(String),password:expect.any(String)}});
 const password=' 1234567890 ';
 await expect(request('/auth/register',{username:'student123',displayName:' 홍길동 ',password,role:'ADMIN'})).resolves.toMatchObject({username:'student123',displayName:'홍길동',role:'STUDENT'});
 await expect(accountMock('/auth/me',{})).rejects.toMatchObject({status:401});
 await expect(request('/auth/register',{username:'student123',displayName:'홍길동',password})).rejects.toMatchObject({status:409,fieldErrors:{username:expect.any(String)}});
 await request('/auth/login',{username:'student123',password});
 await expect(request('/auth/me',{displayName:' '},'PATCH')).rejects.toMatchObject({status:400,fieldErrors:{displayName:expect.any(String)}});
 await expect(request('/auth/me',{displayName:' 새 이름 '},'PATCH')).resolves.toMatchObject({displayName:'새 이름'});
 await expect(request('/auth/password',{currentPassword:password,newPassword:'가'.repeat(25)})).rejects.toMatchObject({status:400,fieldErrors:{newPassword:expect.any(String)}});
 await expect(request('/auth/password',{currentPassword:'wrong',newPassword:'new-password123'})).rejects.toMatchObject({status:400,code:'INVALID_CURRENT_PASSWORD'});
 await expect(accountMock('/auth/me',{})).resolves.toMatchObject({displayName:'새 이름'});
 await expect(request('/auth/password',{currentPassword:password,newPassword:' new-password123 '})).resolves.toEqual({success:true,reauthenticationRequired:true});
 await expect(accountMock('/auth/me',{})).rejects.toMatchObject({status:401});
 await expect(request('/auth/login',{username:'student123',password:' new-password123 '})).resolves.toMatchObject({username:'student123'});
});
