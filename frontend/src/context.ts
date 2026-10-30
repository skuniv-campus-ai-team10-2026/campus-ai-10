import {createContext,useContext} from 'react';
import type {User,Meta} from './types';
export const SessionContext=createContext<{user:User;meta:Meta|null;logout:()=>Promise<void>;updateUser?:(user:User)=>void;reauthenticate?:()=>void}>({user:{id:0,role:'STUDENT',displayName:''},meta:null,logout:async()=>{},updateUser:()=>{},reauthenticate:()=>{}});
export const useSession=()=>useContext(SessionContext);
