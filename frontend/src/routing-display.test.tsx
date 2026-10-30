import {afterEach,expect,it} from 'vitest';
import {cleanup,render,screen} from '@testing-library/react';
import {AssignmentSource,RoutingBadge,reportTypeLabel} from './ui';

afterEach(cleanup);
it('separates assigned demo departments from pending assignment and work verification',()=>{
 render(<><RoutingBadge status="MATCHED" assignment={{assignmentBasis:'DEMO'}}/><AssignmentSource assignment={{assignmentBasis:'DEMO',verified:false}}/></>);
 expect(screen.getByText('담당 부서 배정')).toBeInTheDocument();
 expect(screen.getByText('시연용 배정 · 실제 담당 업무 미검증')).toBeInTheDocument();
 cleanup();
 render(<RoutingBadge status="NEEDS_REVIEW" assignment={null}/>);
 expect(screen.getByText('담당 부서 배정 대기')).toBeInTheDocument();
});
it('names existing facility types and falls back only for unknown codes',()=>{
 expect(['HVAC','WATER_DISPENSER','FURNITURE','UNRECOGNIZED'].map(reportTypeLabel)).toEqual(['냉난방','정수기','가구·비품','기타 문의·불편']);
});
