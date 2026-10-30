import {ProfilePage,SignupPage} from './account';
import {clearPendingPhotos} from './attachments';
import {useEffect,useRef,useState} from 'react';
import './login.css';
import type {FormEvent} from 'react';
import {Link,NavLink,Navigate,Route,Routes,useNavigate,useLocation} from 'react-router-dom';
import {FileText,House,Layers3,LogOut,MapPin} from 'lucide-react';
import {api,ApiError,post,clearCsrf} from './api';
import type {Meta,User} from './types';
import {SessionContext} from './context';
import {ErrorBox} from './ui';
import {AnalyzePage,HomePage,SuccessPage} from './student';
import {ReportDetailPage,ReportsPage} from './reports';
import {IncidentPage,RepeatedPage} from './repeated';

function clearPrivateDraft(){clearCsrf();clearPendingPhotos();sessionStorage.removeItem('skope-draft');sessionStorage.removeItem('skope-last-report');}

export default function App(){
  const [user,setUser]=useState<User|null>(null),[meta,setMeta]=useState<Meta|null>(null),[loading,setLoading]=useState(true),[error,setError]=useState<unknown>(null),[expired,setExpired]=useState(false);
  const navigate=useNavigate();
  const [intro,setIntro]=useState('visible');
  useEffect(()=>{const fade=window.setTimeout(()=>setIntro('fading'),2000),done=window.setTimeout(()=>setIntro('done'),2700);return()=>{clearTimeout(fade);clearTimeout(done);};},[]);
  useEffect(()=>{void api<Meta>('/meta').then(setMeta).catch(()=>{});void api<User>('/auth/me').then(setUser).catch(e=>{if(e instanceof ApiError&&e.status===401)clearPrivateDraft();else setError(e);}).finally(()=>setLoading(false));const expire=()=>{clearPrivateDraft();setUser(null);setExpired(true);};window.addEventListener('session-expired',expire);return()=>window.removeEventListener('session-expired',expire);},[]);
  async function logout(){await post('/auth/logout',{});clearPrivateDraft();setUser(null);navigate('/');}
  if(loading)return <Splash fading={false}/>;
  if(!user)return <><Routes><Route path="/signup" element={<SignupPage/>}/><Route path="*" element={<Login onLogin={value=>{clearPrivateDraft();setUser(value);setExpired(false);setError(null);navigate(value.role==='ADMIN'||value.role==='DEPT_ADMIN'?'/admin/reports':'/');void api<Meta>('/meta').then(setMeta).catch(()=>{});}} error={error} expired={expired}/>}/></Routes>{intro!=='done'&&<Splash fading={intro==='fading'}/>}</>;
  const admin=user.role==='ADMIN'||user.role==='DEPT_ADMIN',departmentAdmin=user.role==='DEPT_ADMIN';
  return <SessionContext.Provider value={{user,meta,logout,updateUser:setUser,reauthenticate:()=>{clearPrivateDraft();setUser(null);setExpired(false);navigate('/login',{replace:true,state:{notice:'비밀번호가 변경됐어요. 다시 로그인해 주세요.'}});}}}><div className="app-shell"><aside className="sidebar"><NavLink to={admin?'/admin/reports':'/'} className="brand"><img className="sidebar-logo" src="/skope-sidebar-logo.png" alt=""/>SKOPE</NavLink>{admin&&<div className="workspace"><span className="workspace-icon"><MapPin size={18}/></span><div><strong>캠퍼스 워크스페이스</strong><small>{departmentAdmin?`${user.departmentName??'소속 부서'} 담당자`:admin?'총괄 관리자 공간':'학생 공간'}</small></div><span className="online-dot"/></div>}<span className="nav-label">WORKSPACE</span><nav>{admin?<><NavLink to="/admin/reports"><FileText size={19}/>신고 관리</NavLink><NavLink to="/admin/repeated"><Layers3 size={19}/>유사·반복 문제</NavLink></>:<><NavLink to="/" end><House size={19}/>캠퍼스 도우미</NavLink><NavLink to="/reports"><FileText size={19}/>접수내역</NavLink></>}</nav></aside><div className="main-shell"><header className="topbar"><div className="account"><span className="avatar">{user.displayName.slice(0,1)}</span><div><strong>{user.displayName}</strong><small>{departmentAdmin?'부서 담당자':admin?'총괄 관리자':'학생'}</small></div><Link className="button secondary" to="/profile">회원정보</Link><button className="icon-button" title="로그아웃" aria-label="로그아웃" onClick={()=>void logout().catch(setError)}><LogOut size={18}/></button></div></header><main><ErrorBox error={error}/><Routes><Route path="/profile" element={<ProfilePage/>}/>{admin?<><Route path="/admin/reports" element={<ReportsPage admin/>}/><Route path="/admin/reports/:id" element={<ReportDetailPage admin/>}/><Route path="/admin/repeated" element={<RepeatedPage/>}/><Route path="/admin/incidents/:id" element={<IncidentPage/>}/><Route path="*" element={<Navigate to="/admin/reports" replace/>}/></>:<><Route path="/" element={<HomePage/>}/><Route path="/report/analyze" element={<AnalyzePage/>}/><Route path="/report/confirm" element={<AnalyzePage/>}/><Route path="/reports/success" element={<SuccessPage/>}/><Route path="/reports" element={<ReportsPage/>}/><Route path="/reports/:id" element={<ReportDetailPage/>}/><Route path="*" element={<Navigate to="/" replace/>}/></>}</Routes></main><footer><span>SKOPE · See the problem. Shape the campus.</span>{admin&&<span>DB {meta?.databaseMode??'확인 중'} · Embedding {meta?.embeddingMode??'확인 중'}</span>}</footer></div></div></SessionContext.Provider>;
}
function Login({onLogin,error:initialError,expired}:{onLogin:(user:User)=>void;error:unknown;expired:boolean}){
 const location=useLocation(),notice=(location.state as {notice?:string}|null)?.notice;
 const [username,setUsername]=useState(''),[password,setPassword]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState<unknown>(initialError);
 async function submit(e:FormEvent){e.preventDefault();setBusy(true);setError(null);try{onLogin(await post<User>('/auth/login',{username,password}));}catch(e){setError(e);}finally{setBusy(false);}}
 return <div className="sk-login"><header className="sk-login-brand"><img src="/skope-symbol.png" alt="스코프 로고"/><span>SKOPE</span></header><div className="sk-login-content"><div className="sk-login-heading"><h1><span>캠퍼스의 궁금함과 불편함을</span><br/><span>SKOPE에 남겨주세요</span></h1><p>서경대학교 캠퍼스 문의·신고 서비스</p></div><div className="sk-login-form-wrap"><Mascot/><section className="sk-login-form" aria-label="로그인">{expired&&<div className="notice">세션이 만료되었습니다. 다시 로그인해 주세요.</div>}{notice&&<p role="status">{notice}</p>}<ErrorBox error={error}/><form onSubmit={submit}><label>아이디<input autoComplete="username" required value={username} onChange={e=>setUsername(e.target.value)} placeholder="아이디"/>{error instanceof ApiError&&error.fieldErrors.username&&<small className="photo-error">{error.fieldErrors.username}</small>}</label><label>비밀번호<input type="password" autoComplete="current-password" required value={password} onChange={e=>setPassword(e.target.value)} placeholder="비밀번호 입력"/>{error instanceof ApiError&&error.fieldErrors.password&&<small className="photo-error">{error.fieldErrors.password}</small>}</label><button className="button primary full" disabled={busy}>{busy?'로그인 중…':'로그인'}</button><Link className="button secondary full" to="/signup">회원가입</Link></form></section></div></div></div>;
}
function Splash({fading}:{fading:boolean}){
 const mark=useRef<HTMLSpanElement>(null);
 useEffect(()=>{
  if(!fading||!mark.current)return;
  const target=document.querySelector('.sk-login-brand img');
  if(!target)return;
  const start=mark.current.getBoundingClientRect(),end=target.getBoundingClientRect();
  const reduced=window.matchMedia?.('(prefers-reduced-motion: reduce)').matches;
  if(reduced||typeof mark.current.animate!=='function')return;
  const motion=mark.current.animate([
   {transform:'translate(0,0) scale(1)'},
   {transform:`translate(${end.x+end.width/2-start.x-start.width/2}px,${end.y+end.height/2-start.y-start.height/2}px) scale(${end.width/start.width})`}
  ],{duration:700,easing:'cubic-bezier(.4,0,.2,1)',fill:'forwards'});
  return()=>motion.cancel();
 },[fading]);
 return <div className={`sk-splash ${fading?'is-fading':''}`} aria-label="SKOPE 시작 화면"><span ref={mark} className="sk-splash-mark"><img src="/skope-splash-clean.svg" alt="SKOPE"/></span><p>서경대학교 캠퍼스 문의·신고 서비스</p></div>;
}
function Mascot(){return <img className="sk-mascot" src="/skot-mascot.png" alt="" aria-hidden="true"/>;}
