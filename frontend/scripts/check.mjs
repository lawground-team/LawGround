import {readdirSync,readFileSync} from 'node:fs';
import {resolve,dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {spawnSync} from 'node:child_process';
const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
const source=resolve(root,'src');
for(const file of readdirSync(source).filter(name=>name.endsWith('.js'))){
  const result=spawnSync(process.execPath,['--check',resolve(source,file)],{stdio:'inherit'});
  if(result.status!==0)process.exit(result.status??1);
}
const html=readFileSync(resolve(source,'index.html'),'utf8');
if(!html.includes('lang="ko"')||!html.includes('type="module"')||!html.includes('name="viewport"'))throw new Error('Missing accessible application entry point');
const tests=readdirSync(resolve(root,'tests')).filter(name=>name.endsWith('.test.js')).map(name=>resolve(root,'tests',name));
if(!tests.length)throw new Error('No frontend tests');
const result=spawnSync(process.execPath,['--test',...tests],{stdio:'inherit'});
process.exit(result.status??1);
