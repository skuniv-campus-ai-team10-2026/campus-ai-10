import {accountMock,accountMockEnabled,isAccountMockPath} from './account-mock';
export class ApiError extends Error {
  constructor(public status:number,public code:string,message:string,public fieldErrors:Record<string,string>={}) { super(message); }
}
let csrf:{token:string;headerName:string}|null=null;
export function clearCsrf(){csrf=null;}
export async function api<T>(path:string,options:RequestInit={}):Promise<T> {
  if(accountMockEnabled&&isAccountMockPath(path))return accountMock<T>(path,options);
  const method=options.method??'GET';
  if(!['GET','HEAD','OPTIONS'].includes(method)&&!csrf) csrf=await api('/auth/csrf');
  const headers=new Headers(options.headers);
  if(options.body&&!(options.body instanceof FormData))headers.set('Content-Type','application/json');
  if(!['GET','HEAD','OPTIONS'].includes(method)&&csrf)headers.set(csrf.headerName,csrf.token);
  let response:Response;
  try { response=await fetch(`/api${path}`,{...options,method,headers,credentials:'include'}); }
  catch { throw new ApiError(0,'NETWORK_ERROR','서버에 연결할 수 없습니다. 백엔드 실행 상태를 확인해 주세요.'); }
  const data=response.status===204?null:await response.json().catch(()=>null);
  if(!response.ok){
    if(response.status===401||response.status===403)clearCsrf();
    if(response.status===401&&path!='/auth/me'&&path!='/auth/login')window.dispatchEvent(new Event('session-expired'));
    throw new ApiError(response.status,data?.code??'REQUEST_FAILED',path==='/auth/login'&&response.status===401?'아이디 또는 비밀번호가 올바르지 않습니다.':data?.message??'요청을 처리하지 못했습니다.',data?.fieldErrors);
  }
  if(path==='/auth/login'||path==='/auth/logout'||path==='/auth/password')clearCsrf();
  return data as T;
}
export const post=<T,>(path:string,body:unknown)=>api<T>(path,{method:'POST',body:JSON.stringify(body)});
export const patch=<T,>(path:string,body:unknown)=>api<T>(path,{method:'PATCH',body:JSON.stringify(body)});
