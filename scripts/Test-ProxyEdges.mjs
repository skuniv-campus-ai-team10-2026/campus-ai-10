// Run after stopping the verification backend while preview port 4173 remains open.
import assert from 'node:assert/strict';
import {mkdir,writeFile,symlink,unlink,lstat,realpath} from 'node:fs/promises';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
const root=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const base='http://127.0.0.1:4173';
const checks=[];
async function check(name,fn){try{await fn();checks.push({name,status:'PASS'});}catch(e){checks.push({name,status:'FAIL',reason:e.message});}}
await check('New production bundle and HEAD remain available without backend',async()=>{
 const r=await fetch(base);assert.equal(r.status,200);const html=await r.text();
 const js=html.match(/src="([^\"]+\.js)"/)?.[1];assert.ok(js?.startsWith('/assets/'));
 const asset=await fetch(base+js);assert.equal(asset.status,200);assert.match(asset.headers.get('content-type'),/javascript/);
 const head=await fetch(base,{method:'HEAD'});assert.equal(head.status,200);assert.equal(await head.text(),'');
});
await check('Stopped backend produces 502 JSON without HTML fallback',async()=>{
 const r=await fetch(base+'/api/meta');assert.equal(r.status,502);assert.match(r.headers.get('content-type'),/application\/json/);
 assert.equal((await r.json()).code,'UPSTREAM_UNAVAILABLE');
});
const outside=path.join(root,'.runtime/proxy-edge'),link=path.join(root,'frontend/dist/verification-link');
let created=false;
await check('Directory junction cannot expose a file outside dist',async()=>{
 await mkdir(outside,{recursive:true});await writeFile(path.join(outside,'probe.txt'),'SYNTHETIC_OUTSIDE_DIST_MARKER');
 await symlink(outside,link,'junction');created=true;
 const r=await fetch(base+'/verification-link/probe.txt');assert.equal(r.status,403);assert.ok(!(await r.text()).includes('SYNTHETIC_OUTSIDE_DIST_MARKER'));
});
if(created){
 // Remove only the exact junction created above, never its target directory.
 assert.equal((await lstat(link)).isSymbolicLink(),true);assert.equal(await realpath(link),await realpath(outside));await unlink(link);
}
const result={at:new Date().toISOString(),checks,status:checks.every(c=>c.status==='PASS')?'PASS':'FAIL'};
await writeFile(path.join(root,'docs/evaluation/proxy-edge-results.json'),JSON.stringify(result,null,2));
console.log(JSON.stringify(result,null,2));if(result.status==='FAIL')process.exitCode=1;
