import {useState} from 'react';
import type {FormEvent} from 'react';
import {Link,useNavigate} from 'react-router-dom';
import {ApiError,post,patch} from './api';
import {useSession} from './context';
import type {User} from './types';

export function accountValidation(values:Record<string,string>){
 const errors:Record<string,string>={};
 if('username' in values&&!/^[a-z0-9_]{4,30}$/.test(values.username))errors.username='영문 소문자·숫자·밑줄로 4~30자를 입력해 주세요.';
 if('displayName' in values&&(!values.displayName.trim()||Array.from(values.displayName.trim()).length>30))errors.displayName='이름은 앞뒤 공백을 제외하고 1~30자로 입력해 주세요.';
 const password=values.password??values.newPassword;
 if(password!==undefined&&(Array.from(password).length<12||Array.from(password).length>64||new TextEncoder().encode(password).length>72))errors['password' in values?'password':'newPassword']='비밀번호는 12~64자, UTF-8 기준 72바이트 이하로 입력해 주세요.';
 if('confirmation' in values&&values.confirmation!==password)errors.confirmation='비밀번호가 일치하지 않습니다.';
 if('currentPassword' in values&&!values.currentPassword)errors.currentPassword='현재 비밀번호를 입력해 주세요.';
 return errors;
}
function AccountForm({kind,onSuccess}:{kind:'signup'|'name'|'password';onSuccess:(user?:User)=>void}){
 const {user}=useSession();
 const initial:Record<string,string>=kind==='signup'?{username:'',displayName:'',password:'',confirmation:''}:kind==='name'?{displayName:user.displayName}:{currentPassword:'',newPassword:'',confirmation:''};
 const [values,setValues]=useState<Record<string,string>>(initial),[errors,setErrors]=useState<Record<string,string>>({}),[message,setMessage]=useState(''),[busy,setBusy]=useState(false);
 const labels:Record<string,string>={username:'아이디',displayName:'이름',password:'비밀번호',currentPassword:'현재 비밀번호',newPassword:'새 비밀번호',confirmation:'비밀번호 확인'};
 async function submit(event:FormEvent){event.preventDefault();const validation=accountValidation(values);setErrors(validation);setMessage('');if(Object.keys(validation).length)return;setBusy(true);
  try{if(kind==='signup'){await post<User>('/auth/register',{username:values.username,displayName:values.displayName.trim(),password:values.password});onSuccess();}
   else if(kind==='name'){onSuccess(await patch<User>('/auth/me',{displayName:values.displayName.trim()}));setMessage('이름을 변경했어요.');}
   else {await post('/auth/password',{currentPassword:values.currentPassword,newPassword:values.newPassword});onSuccess();}
  }catch(error){if(error instanceof ApiError){setErrors({...error.fieldErrors,...(error.code==='INVALID_CURRENT_PASSWORD'&&!error.fieldErrors.currentPassword?{currentPassword:error.message}:{})});setMessage(error.message);}else setMessage('요청을 처리하지 못했어요. 다시 시도해 주세요.');}finally{setBusy(false);}
 }
 return <form className="account-form" onSubmit={submit} noValidate>{Object.keys(values).map(key=><label key={key} htmlFor={`${kind}-${key}`}>{labels[key]}<input id={`${kind}-${key}`} type={key.toLowerCase().includes('password')||key==='confirmation'?'password':'text'} autoComplete={key==='username'?'username':key==='displayName'?'nickname':key==='currentPassword'?'current-password':'new-password'} value={values[key]} disabled={busy} aria-invalid={!!errors[key]} aria-describedby={errors[key]?`${kind}-${key}-error`:undefined} onChange={e=>{setValues({...values,[key]:e.target.value});setErrors({...errors,[key]:''});}}/>{errors[key]&&<small id={`${kind}-${key}-error`} className="photo-error">{errors[key]}</small>}</label>)}{message&&<p role="status">{message}</p>}<button className="button primary full" disabled={busy}>{busy?'처리 중…':kind==='signup'?'회원가입':kind==='name'?'이름 변경':'비밀번호 변경'}</button></form>;
}
export function SignupPage(){const navigate=useNavigate();return <div className="account-page"><section className="card"><h1>회원가입</h1><AccountForm kind="signup" onSuccess={()=>navigate('/login',{replace:true,state:{notice:'회원가입이 완료됐어요. 로그인해 주세요.'}})}/><Link className="button secondary full" to="/login">로그인으로 돌아가기</Link></section></div>;}
export function ProfilePage(){const {user,updateUser,reauthenticate}=useSession();return <div className="account-page"><h1>회원정보</h1><section className="card"><dl><dt>아이디</dt><dd>{user.username??'—'}</dd><dt>역할</dt><dd>{user.role==='STUDENT'?'학생':user.role==='DEPT_ADMIN'?'부서 담당자':'총괄 관리자'}</dd><dt>소속 부서</dt><dd>{user.departmentName??'없음'}</dd></dl><h2>이름 수정</h2><AccountForm kind="name" onSuccess={value=>{if(value)updateUser?.(value);}}/></section><section className="card"><h2>비밀번호 변경</h2><AccountForm kind="password" onSuccess={()=>reauthenticate?.()}/></section></div>;}
