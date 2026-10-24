import {beforeEach,describe,expect,it,vi} from 'vitest';
import {api,ApiError,clearCsrf,post} from './api';
const response=(body:unknown,status=200)=>new Response(JSON.stringify(body),{status,headers:{'Content-Type':'application/json'}});
describe('session API client',()=>{
 beforeEach(()=>{clearCsrf();vi.restoreAllMocks();});
 it('retrieves CSRF before mutation and renews the token after login',async()=>{
  const fetch=vi.spyOn(globalThis,'fetch').mockResolvedValueOnce(response({token:'first',headerName:'X-CSRF-TOKEN'})).mockResolvedValueOnce(response({id:1,role:'STUDENT'})).mockResolvedValueOnce(response({token:'second',headerName:'X-CSRF-TOKEN'})).mockResolvedValueOnce(response({id:42}));
  await post('/auth/login',{username:'student1',password:'test-only'});await post('/reports',{originalText:'test'});
  expect(fetch.mock.calls.map(([url])=>url)).toEqual(['/api/auth/csrf','/api/auth/login','/api/auth/csrf','/api/reports']);
  expect(new Headers(fetch.mock.calls[1][1]?.headers).get('X-CSRF-TOKEN')).toBe('first');
  expect(new Headers(fetch.mock.calls[3][1]?.headers).get('X-CSRF-TOKEN')).toBe('second');
  expect(fetch.mock.calls[3][1]?.credentials).toBe('include');
 });
 it('keeps the server error code and field-level validation',async()=>{
  vi.spyOn(globalThis,'fetch').mockResolvedValue(response({code:'VALIDATION_ERROR',message:'입력 오류',fieldErrors:{symptom:'필수'}},400));
  await expect(api('/reports')).rejects.toMatchObject({status:400,code:'VALIDATION_ERROR',fieldErrors:{symptom:'필수'}});
 });
 it('emits session expiration only on authenticated requests',async()=>{
  const listener=vi.fn();window.addEventListener('session-expired',listener);
  vi.spyOn(globalThis,'fetch').mockResolvedValue(response({code:'UNAUTHENTICATED',message:'로그인 필요'},401));
  await expect(api('/auth/me')).rejects.toBeInstanceOf(ApiError);expect(listener).not.toHaveBeenCalled();
  await expect(api('/reports')).rejects.toBeInstanceOf(ApiError);expect(listener).toHaveBeenCalledOnce();window.removeEventListener('session-expired',listener);
 });
 it('does not silently convert a network failure to successful data',async()=>{
  vi.spyOn(globalThis,'fetch').mockRejectedValue(new TypeError('Failed to fetch'));
  await expect(api('/reports')).rejects.toMatchObject({status:0,code:'NETWORK_ERROR'});
 });
 it('discards an expired session CSRF token before the next login attempt',async()=>{
  const fetch=vi.spyOn(globalThis,'fetch')
   .mockResolvedValueOnce(response({token:'expired-session-token',headerName:'X-CSRF-TOKEN'}))
   .mockResolvedValueOnce(response({id:42}))
   .mockResolvedValueOnce(response({code:'UNAUTHENTICATED',message:'세션 만료'},401))
   .mockResolvedValueOnce(response({token:'fresh-session-token',headerName:'X-CSRF-TOKEN'}))
   .mockResolvedValueOnce(response({id:1,role:'STUDENT'}));
  await post('/reports',{originalText:'test'});
  await expect(api('/reports/42')).rejects.toMatchObject({status:401});
  await post('/auth/login',{username:'student1',password:'test-only'});
  expect(fetch.mock.calls.map(([url])=>url)).toEqual(['/api/auth/csrf','/api/reports','/api/reports/42','/api/auth/csrf','/api/auth/login']);
  expect(new Headers(fetch.mock.calls[4][1]?.headers).get('X-CSRF-TOKEN')).toBe('fresh-session-token');
 });
});
