import {ApiError} from './api';
import type {User} from './types';

// Opt-in, in-memory contract mock. Never persists passwords or intercepts report APIs.
const accounts=new Map<string,{user:User;password:string}>();
let current:User|null=null;
export const accountMockEnabled=(import.meta as ImportMeta & {env:Record<string,string>}).env.VITE_ACCOUNT_MOCK==='true';
export function isAccountMockPath(path:string){return ['/auth/register','/auth/login','/auth/me','/auth/password','/auth/logout'].includes(path);}
function validate(body:Record<string,unknown>,fields:string[]){
 const errors:Record<string,string>={};
 for(const field of fields){
  const value=body[field];
  if(typeof value!=='string'){errors[field]='입력값을 확인해 주세요.';continue;}
  if(field==='username'&&!/^[a-z0-9_]{4,30}$/.test(value))errors[field]='영문 소문자·숫자·밑줄로 4~30자를 입력해 주세요.';
  if(field==='displayName'&&(!value.trim()||Array.from(value.trim()).length>30))errors[field]='이름은 1~30자로 입력해 주세요.';
  if(['password','newPassword'].includes(field)&&(Array.from(value).length<12||Array.from(value).length>64||new TextEncoder().encode(value).length>72))errors[field]='비밀번호는 12~64자, UTF-8 기준 72바이트 이하로 입력해 주세요.';
 }
 if(Object.keys(errors).length)throw new ApiError(400,'VALIDATION_ERROR','입력값을 확인해 주세요.',errors);
}
export async function accountMock<T>(path:string,options:RequestInit):Promise<T>{
 const body=JSON.parse(String(options.body??'{}')),method=options.method??'GET';
 let result:unknown;
 if(path==='/auth/register'){
  validate(body,['username','displayName','password']);body.displayName=body.displayName.trim();
  if(accounts.has(body.username))throw new ApiError(409,'USERNAME_TAKEN','이미 사용 중인 아이디입니다.',{username:'다른 아이디를 입력해 주세요.'});
  const user:User={id:accounts.size+1,username:body.username,displayName:body.displayName,role:'STUDENT',departmentId:null,departmentName:null};
  accounts.set(body.username,{user,password:body.password});result=user;
 }else if(path==='/auth/login'){
  const account=accounts.get(body.username);
  if(!account||account.password!==body.password)throw new ApiError(401,'UNAUTHENTICATED','아이디 또는 비밀번호가 올바르지 않습니다.');
  current={...account.user};result=current;
 }else if(path==='/auth/logout'){current=null;result={success:true};}
 else{
  if(!current)throw new ApiError(401,'UNAUTHENTICATED','로그인이 필요합니다.');
  const account=accounts.get(current.username!)!;
  if(path==='/auth/password'){
   validate(body,['currentPassword','newPassword']);
   if(account.password!==body.currentPassword)throw new ApiError(400,'INVALID_CURRENT_PASSWORD','현재 비밀번호가 일치하지 않습니다.',{currentPassword:'현재 비밀번호를 확인해 주세요.'});
   account.password=body.newPassword;current=null;result={success:true,reauthenticationRequired:true};
  }else{if(method==='PATCH'){validate(body,['displayName']);account.user={...account.user,displayName:body.displayName.trim()};current={...account.user};}result={...current};}
 }
 return result as T;
}
