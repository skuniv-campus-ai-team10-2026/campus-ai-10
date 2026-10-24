type MascotAction={label:string;frame?:number;image?:string};
const actions:MascotAction[]=[
 {label:'노트북으로 문의를 정리하고 있어요',frame:0},
 {label:'노트북을 덮고 일어나요',frame:1},
 {label:'돋보기로 문제를 살펴봐요',frame:3},
 {label:'반갑게 인사해요',frame:5},
 {label:'커피를 마시며 쉬고 있어요',image:'/skot-coffee.png'},
 {label:'가방을 메고 강의실로 가요',image:'/skot-backpack.png'},
 {label:'책을 읽고 있어요',image:'/skot-reading.png'},
 {label:'노트에 필기하고 있어요',image:'/skot-writing.png'},
 {label:'휴대폰으로 일정을 확인해요',image:'/skot-phone.png'},
];
export function CampusMascot(){return <aside className="campus-mascot" aria-label="캠퍼스 도우미 캐릭터 애니메이션"><div className="mascot-stage" aria-hidden="true">{actions.map((action,i)=><span key={action.label} className={`mascot-frame ${action.image?'mascot-frame-single':`mascot-frame-${action.frame}`}`} style={{animationDelay:`${i*4-1}s`,...(action.image?{backgroundImage:`url('${action.image}')`}:{})}}/>)}</div></aside>;}